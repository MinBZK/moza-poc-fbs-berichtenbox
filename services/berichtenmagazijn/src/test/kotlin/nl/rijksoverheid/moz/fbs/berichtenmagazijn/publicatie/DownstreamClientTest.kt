package nl.rijksoverheid.moz.fbs.berichtenmagazijn.publicatie

import com.fasterxml.jackson.core.JsonProcessingException
import com.fasterxml.jackson.databind.ObjectMapper
import io.mockk.every
import io.mockk.mockk
import io.opentelemetry.api.OpenTelemetry
import io.quarkus.runtime.StartupEvent
import jakarta.enterprise.inject.Instance
import nl.rijksoverheid.moz.fbs.common.fsc.FscOutwayHeaders
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.NullSource
import org.junit.jupiter.params.provider.ValueSource
import java.time.Instant
import java.util.Optional
import java.util.UUID

/**
 * Unit-tests voor [DownstreamClient]: succes-pad, foutpaden en URL-validatie.
 * Gebruikt [DownstreamHttpServer] (JDK `com.sun.net.httpserver`) voor het succes-pad
 * en mockt [PublicatieConfig] voor configuratiefouten.
 *
 * `DownstreamStub` is een top-level class zodat Quarkus' ARC-validatie de
 * `@Transactional`-binding van de parent `PublicatieConfig.Downstream`-interface
 * niet probeert te verwerken op een anonymous-class (CDI verbiedt dat).
 */
class DownstreamClientTest {

    private class DownstreamStub(
        private val u: String,
        private val hash: String? = null,
    ) : PublicatieConfig.Downstream {
        override fun url(): String = u
        override fun grantHash(): Optional<String> = Optional.ofNullable(hash)
        override fun maxPogingen(): Int = 5
        override fun backoff(): PublicatieConfig.Backoff = object : PublicatieConfig.Backoff {
            override fun basis(): java.time.Duration = java.time.Duration.ofSeconds(1)
            override fun plafond(): java.time.Duration = java.time.Duration.ofHours(1)
        }
    }

    private class SimuleerdeJsonFout(msg: String) : JsonProcessingException(msg)

    private companion object {
        /** Waar [DownstreamHttpServer] op bindt, en daarmee de outway-host in deze tests. */
        const val SERVER_HOST = "127.0.0.1"
    }

    private lateinit var server: DownstreamHttpServer
    private lateinit var config: PublicatieConfig
    private lateinit var client: DownstreamClient

    private val objectMapper = ObjectMapper().registerModule(
        com.fasterxml.jackson.datatype.jsr310.JavaTimeModule(),
    )

    private val openTelemetry = mockk<Instance<OpenTelemetry>>().apply {
        every { isResolvable } returns false
    }

    private val event = CloudEvent(
        id = "11111111-1111-1111-1111-111111111111",
        source = "urn:nld:oin:00000001003214345000:systeem:fbs-magazijn",
        specversion = "1.0",
        type = "nl.rijksoverheid.fbs.bericht.gepubliceerd",
        subject = UUID.randomUUID().toString(),
        time = Instant.parse("2026-05-12T10:00:00Z"),
        datacontenttype = "application/json",
        dataschema = "https://schemas.fbs.rijksoverheid.nl/bericht-gepubliceerd/v1",
        data = BerichtData(
            berichtId = UUID.randomUUID(),
            afzender = "00000001003214345000",
            ontvanger = OntvangerData("BSN", "999993653"),
            onderwerp = "Test",
            inhoud = "Inhoud",
            tijdstipOntvangst = Instant.parse("2026-05-12T10:00:00Z"),
            publicatietijdstip = Instant.parse("2026-05-12T10:00:00Z"),
        ),
    )

    @BeforeEach
    fun start() {
        server = DownstreamHttpServer()
        server.start()
        config = mockk()
        every { config.downstreams() } returns mapOf("aanmeld" to DownstreamStub(server.baseUrl))
        every { config.client() } returns mockk {
            every { connectTimeout() } returns java.time.Duration.ofSeconds(5)
            every { requestTimeout() } returns java.time.Duration.ofSeconds(10)
        }
        // De embedded server ís hier de outway: zonder deze koppeling valt elke downstream met een
        // grant-hash af op "wijst niet naar de eigen outway".
        stelOutwayIn(SERVER_HOST)
        client = DownstreamClient(config, objectMapper, openTelemetry, "prod")
    }

    private fun stelOutwayIn(host: String?) {
        every { config.outway() } returns object : PublicatieConfig.Outway {
            override fun host(): Optional<String> = Optional.ofNullable(host)
        }
    }

    @AfterEach
    fun stop() {
        server.close()
        client.stop()
    }

    @Test
    fun `2xx response geeft Geslaagd`() {
        val resultaat = client.lever(Publicatiedoel("aanmeld"), event)
        assertEquals(DownstreamResultaat.Geslaagd, resultaat)
    }

    @Test
    fun `5xx response geeft HttpFout met herstelbaar`() {
        server.close()
        server = DownstreamHttpServer().apply { statusVoorAanroep = { _ -> 500 } }
        server.start()
        every { config.downstreams() } returns mapOf("aanmeld" to DownstreamStub(server.baseUrl))
        client = DownstreamClient(config, objectMapper, openTelemetry, "prod")

        val resultaat = client.lever(Publicatiedoel("aanmeld"), event)
        assertTrue(resultaat is DownstreamResultaat.HttpFout)
        val httpFout = resultaat as DownstreamResultaat.HttpFout
        assertEquals(500, httpFout.statusCode)
        assertTrue(httpFout.herstelbaar)
    }

    @Test
    fun `4xx response geeft HttpFout niet-herstelbaar`() {
        server.close()
        server = DownstreamHttpServer().apply { statusVoorAanroep = { _ -> 400 } }
        server.start()
        every { config.downstreams() } returns mapOf("aanmeld" to DownstreamStub(server.baseUrl))
        client = DownstreamClient(config, objectMapper, openTelemetry, "prod")

        val resultaat = client.lever(Publicatiedoel("aanmeld"), event)
        assertTrue(resultaat is DownstreamResultaat.HttpFout)
        assertEquals(false, (resultaat as DownstreamResultaat.HttpFout).herstelbaar)
    }

    @Test
    fun `onbekend doel geeft ConfiguratieFout`() {
        val resultaat = client.lever(Publicatiedoel("onbekend"), event)
        assertTrue(resultaat is DownstreamResultaat.ConfiguratieFout)
    }

    @Test
    fun `mapDeliveryException SSLHandshakeException naar ConfiguratieFout (non-herstelbaar)`() {
        // Round 8 H2 + Round 9 M2 invariant: SSLHandshakeException = cert-config-fout.
        // Retry binnen pollvenster zinloos — herstel vereist cert-rotatie. Mapping
        // moet ConfiguratieFout (non-herstelbaar) zijn; eerdere flaky network-test
        // (TCP-garbage server) is vervangen door deze deterministische unit-test
        // op de geëxtraheerde mapping-functie.
        every { config.downstreams() } returns emptyMap()
        client = DownstreamClient(config, objectMapper, openTelemetry, "prod")

        val resultaat = client.mapDeliveryException(
            javax.net.ssl.SSLHandshakeException("Unable to find valid certification path"),
            Publicatiedoel("aanmeld"),
        )

        assertTrue(
            resultaat is DownstreamResultaat.ConfiguratieFout,
            "SSLHandshakeException moet ConfiguratieFout (non-herstelbaar) worden — gevonden: $resultaat",
        )
        val reden = (resultaat as DownstreamResultaat.ConfiguratieFout).reden
        assertTrue(
            reden.contains("TLS-handshake"),
            "reden moet TLS-handshake-categorie aangeven — gevonden: $reden",
        )
        assertTrue(
            reden.contains("SSLHandshakeException"),
            "reden moet exception-class voor support-correlatie bevatten — gevonden: $reden",
        )
    }

    @Test
    fun `mapDeliveryException generieke SSLException naar NetwerkFout (herstelbaar)`() {
        // Verschilt van SSLHandshakeException: SSLProtocolException, partial-handshake-RST,
        // transient cert-rotatie-window — kunnen na pod-restart slagen. Daarom
        // herstelbaar = NetwerkFout. Mag NIET als ConfiguratieFout (eindeloos
        // retry-besluit) of als generieke IOException (mist TLS-context in log).
        every { config.downstreams() } returns emptyMap()
        client = DownstreamClient(config, objectMapper, openTelemetry, "prod")

        val resultaat = client.mapDeliveryException(
            javax.net.ssl.SSLProtocolException("Connection reset during handshake"),
            Publicatiedoel("aanmeld"),
        )

        assertTrue(
            resultaat is DownstreamResultaat.NetwerkFout,
            "generieke SSLException moet NetwerkFout (herstelbaar) worden — gevonden: $resultaat",
        )
        val reden = (resultaat as DownstreamResultaat.NetwerkFout).reden
        assertTrue(
            reden.contains("TLS-fout"),
            "reden moet TLS-laag-categorie aangeven (niet generieke netwerk) — gevonden: $reden",
        )
    }

    @Test
    fun `mapDeliveryException SSLHandshakeException matcht VOOR generieke SSLException`() {
        // Borgt de when-volgorde-invariant: SSLHandshakeException IS-A SSLException
        // (Java class-hierarchy). Een refactor die de when-branches herordent zou
        // SSLHandshakeException naar de generieke SSLException-tak laten vallen
        // → NetwerkFout i.p.v. ConfiguratieFout → eindeloos retry op cert-faal.
        // Deze test mist als de mapping `is SSLException` vóór `is SSLHandshakeException`
        // zou plaatsen.
        every { config.downstreams() } returns emptyMap()
        client = DownstreamClient(config, objectMapper, openTelemetry, "prod")

        val handshake = javax.net.ssl.SSLHandshakeException("test")
        // Bewijs class-hierarchy: SSLHandshakeException IS-A SSLException
        assertTrue(handshake is javax.net.ssl.SSLException, "Java class-hierarchy assumption")

        val resultaat = client.mapDeliveryException(handshake, Publicatiedoel("aanmeld"))

        // Specifiekere subklasse moet eerst matchen → ConfiguratieFout, niet NetwerkFout
        assertEquals(
            DownstreamResultaat.ConfiguratieFout::class,
            resultaat::class,
            "SSLHandshakeException moet specifiekere ConfiguratieFout-tak raken vóór generieke SSLException-tak",
        )
    }

    @Test
    fun `mapDeliveryException Connect-timeout vóór generieke timeout (subklasse-volgorde)`() {
        // HttpConnectTimeoutException IS-A HttpTimeoutException — mapping moet specifiek
        // catch'en zodat connect-timeout en read-timeout via verschillende
        // diagnostiek-paden kunnen lopen (DNS-faal vs. server-overload).
        every { config.downstreams() } returns emptyMap()
        client = DownstreamClient(config, objectMapper, openTelemetry, "prod")

        val ct = java.net.http.HttpConnectTimeoutException("connect timed out")
        assertTrue(ct is java.net.http.HttpTimeoutException, "Java class-hierarchy assumption")

        val resultaat = client.mapDeliveryException(ct, Publicatiedoel("aanmeld"))

        assertTrue(resultaat is DownstreamResultaat.Timeout)
        assertTrue(
            (resultaat as DownstreamResultaat.Timeout).reden.contains("Connect-timeout"),
            "Connect-specifiek bericht vereist (niet generieke 'Read-timeout') — gevonden: ${resultaat.reden}",
        )
    }

    @Test
    fun `mapDeliveryException generieke IOException naar NetwerkFout`() {
        // Vangnet voor connection-reset, broken-pipe, host-unreachable — alles
        // behalve TLS en timeouts. Mag GEEN TLS-context impliceren.
        every { config.downstreams() } returns emptyMap()
        client = DownstreamClient(config, objectMapper, openTelemetry, "prod")

        val resultaat = client.mapDeliveryException(
            java.io.IOException("Connection reset"),
            Publicatiedoel("aanmeld"),
        )

        assertTrue(resultaat is DownstreamResultaat.NetwerkFout)
        val reden = (resultaat as DownstreamResultaat.NetwerkFout).reden
        assertFalse(
            reden.contains("TLS"),
            "generieke IOException mag GEEN TLS-context bevatten (verwarrend voor ops) — gevonden: $reden",
        )
        assertTrue(
            reden.contains("IOException"),
            "exception-class voor support-correlatie vereist — gevonden: $reden",
        )
    }

    @Test
    fun `plain http naar niet-localhost geeft ConfiguratieFout`() {
        every { config.downstreams() } returns mapOf("aanmeld" to DownstreamStub("http://prod.example.com/events"))
        val resultaat = client.lever(Publicatiedoel("aanmeld"), event)
        assertTrue(resultaat is DownstreamResultaat.ConfiguratieFout)
        assertTrue((resultaat as DownstreamResultaat.ConfiguratieFout).reden.contains("TLS"))
    }

    @Test
    fun `in dev mag plain http naar niet-loopback (geen TLS- of SSRF-afkeuring)`() {
        every { config.downstreams() } returns mapOf("aanmeld" to DownstreamStub("http://demo.invalid/events"))
        client = DownstreamClient(config, objectMapper, openTelemetry, "dev")

        val resultaat = client.lever(Publicatiedoel("aanmeld"), event)

        // De URL passeert de validatie in dev; de aflevering faalt daarna op DNS (host .invalid),
        // maar dat is een netwerk-/afleverfout, geen TLS-/SSRF-validatie-afkeuring.
        val isValidatieAfkeuring = resultaat is DownstreamResultaat.ConfiguratieFout &&
            ((resultaat as DownstreamResultaat.ConfiguratieFout).reden.contains("TLS") || resultaat.reden.contains("SSRF"))

        assertFalse(isValidatieAfkeuring, "dev-profiel moet http naar niet-loopback toestaan, kreeg: $resultaat")
    }

    @Test
    fun `in test blijft plain http naar niet-loopback afgekeurd`() {
        // Alleen dev heeft de bypass nodig (container-DNS in de demo-stack). De testconfig gebruikt
        // loopback-URL's, die sowieso mogen; 'test' meenemen zou de validatie over de hele
        // QuarkusTest-oppervlakte uitschakelen zonder er iets voor terug te krijgen.
        every { config.downstreams() } returns mapOf("aanmeld" to DownstreamStub("http://demo.invalid/events"))
        client = DownstreamClient(config, objectMapper, openTelemetry, "test")

        val resultaat = client.lever(Publicatiedoel("aanmeld"), event)

        assertTrue(resultaat is DownstreamResultaat.ConfiguratieFout)
        assertTrue((resultaat as DownstreamResultaat.ConfiguratieFout).reden.contains("TLS"))
    }

    @Test
    fun `plain http naar loopback wordt toegestaan zonder profiel-uitzondering`() {
        // De client uit @BeforeEach draait onder 'prod'; server.baseUrl is http://127.0.0.1:*.
        // Bewijst dus de loopback-uitzondering zélf, niet de dev/test-bypass eromheen.
        val resultaat = client.lever(Publicatiedoel("aanmeld"), event)
        assertEquals(DownstreamResultaat.Geslaagd, resultaat)
    }

    @Test
    fun `ongeldige URL geeft ConfiguratieFout`() {
        every { config.downstreams() } returns mapOf("aanmeld" to DownstreamStub("not a url at all"))
        val resultaat = client.lever(Publicatiedoel("aanmeld"), event)
        assertTrue(resultaat is DownstreamResultaat.ConfiguratieFout)
    }

    @Test
    fun `serialisatie-fout geeft SerialisatieFout`() {
        val kapotteMapper = mockk<ObjectMapper>()
        every { kapotteMapper.writeValueAsBytes(any()) } throws SimuleerdeJsonFout("ka-boom")
        client = DownstreamClient(config, kapotteMapper, openTelemetry, "prod")

        val resultaat = client.lever(Publicatiedoel("aanmeld"), event)
        assertTrue(resultaat is DownstreamResultaat.SerialisatieFout)
    }

    @Test
    fun `lage request-timeout uit config leidt tot Timeout-degradatie`() {
        // Een server die langer wacht dan de geconfigureerde request-timeout: bewijst dat
        // magazijn.publicatie.client.request-timeout daadwerkelijk de per-request deadline
        // bedient (een losgekoppelde property zou op de default 10s blijven en niet aanslaan).
        val traag = com.sun.net.httpserver.HttpServer.create(
            java.net.InetSocketAddress("127.0.0.1", 0), 0,
        )
        traag.createContext("/events") { exchange ->
            Thread.sleep(2_000)
            exchange.sendResponseHeaders(202, -1)
            exchange.close()
        }
        traag.start()
        try {
            every { config.client() } returns mockk {
                every { connectTimeout() } returns java.time.Duration.ofSeconds(5)
                every { requestTimeout() } returns java.time.Duration.ofMillis(200)
            }
            every { config.downstreams() } returns mapOf(
                "aanmeld" to DownstreamStub("http://127.0.0.1:${traag.address.port}/events"),
            )
            client = DownstreamClient(config, objectMapper, openTelemetry, "prod")

            val resultaat = client.lever(Publicatiedoel("aanmeld"), event)

            assertTrue(resultaat is DownstreamResultaat.Timeout, "verwacht Timeout, kreeg $resultaat")
            assertTrue(
                (resultaat as DownstreamResultaat.Timeout).reden.contains("Read-timeout"),
                "verwacht read-timeout-categorie, kreeg: ${resultaat.reden}",
            )
        } finally {
            traag.stop(0)
        }
    }

    @Test
    fun `connect-naar-niet-luisterende-poort geeft NetwerkFout of Timeout`() {
        every { config.downstreams() } returns mapOf("aanmeld" to DownstreamStub("http://127.0.0.1:1/events"))
        val resultaat = client.lever(Publicatiedoel("aanmeld"), event)
        assertTrue(
            resultaat is DownstreamResultaat.NetwerkFout || resultaat is DownstreamResultaat.Timeout,
            "verwacht NetwerkFout of Timeout, kreeg $resultaat",
        )
    }

    @Test
    fun `Retry-After-header op 503 wordt geparsed naar HttpFout retryAfter`() {
        server.close()
        // Eigen JDK HttpServer met expliciete Retry-After-header (DownstreamHttpServer
        // ondersteunt dat niet uit de doos).
        val httpServer = com.sun.net.httpserver.HttpServer.create(
            java.net.InetSocketAddress("127.0.0.1", 0), 0,
        )
        httpServer.createContext("/events") { exchange ->
            exchange.responseHeaders.add("Retry-After", "30")
            exchange.sendResponseHeaders(503, -1)
            exchange.close()
        }
        httpServer.start()
        try {
            every { config.downstreams() } returns mapOf(
                "aanmeld" to DownstreamStub("http://127.0.0.1:${httpServer.address.port}/events"),
            )
            client = DownstreamClient(config, objectMapper, openTelemetry, "prod")

            val resultaat = client.lever(Publicatiedoel("aanmeld"), event)
            assertTrue(resultaat is DownstreamResultaat.HttpFout)
            val httpFout = resultaat as DownstreamResultaat.HttpFout
            assertEquals(503, httpFout.statusCode)
            assertTrue(httpFout.herstelbaar)
            assertEquals(java.time.Duration.ofSeconds(30), httpFout.retryAfter)
        } finally {
            httpServer.stop(0)
        }
    }

    @org.junit.jupiter.params.ParameterizedTest(name = "SSRF blokkeert {0}")
    @org.junit.jupiter.params.provider.ValueSource(strings = [
        "https://169.254.169.254/x", // AWS IMDS v4 (link-local)
        "https://10.0.0.1/x", // RFC1918 (site-local)
        "https://172.16.0.1/x", // RFC1918
        "https://192.168.1.1/x", // RFC1918
        "https://0.0.0.0/x", // any-local
        "https://[fe80::1]/x", // IPv6 link-local
        "https://[fd00:ec2::254]/x", // AWS IMDS v6 (ULA + cloud-metadata literal)
        "https://[fc00::1]/x", // IPv6 ULA
    ])
    fun `SSRF-guard weigert interne en cloud-metadata adressen`(url: String) {
        every { config.downstreams() } returns mapOf("aanmeld" to DownstreamStub(url))
        val resultaat = client.lever(Publicatiedoel("aanmeld"), event)
        assertTrue(
            resultaat is DownstreamResultaat.ConfiguratieFout,
            "verwacht ConfiguratieFout voor $url, kreeg $resultaat",
        )
        val reden = (resultaat as DownstreamResultaat.ConfiguratieFout).reden
        assertTrue(
            reden.contains("intern adres") || reden.contains("metadata") || reden.contains("ULA"),
            "verwachte SSRF-melding voor $url, kreeg: $reden",
        )
    }

    @Test
    fun `tracestate wordt NIET als header naar downstream gestuurd (vendor-leak voorkomen)`() {
        val resultaat = client.lever(Publicatiedoel("aanmeld"), event)
        assertEquals(DownstreamResultaat.Geslaagd, resultaat)
        // Wacht tot de server de request verwerkt heeft.
        org.awaitility.Awaitility.await()
            .pollDelay(java.time.Duration.ZERO)
            .atMost(2, java.util.concurrent.TimeUnit.SECONDS)
            .until { server.aantalAanroepen >= 1 }
        val headers = server.headers.first()
        // OpenTelemetry-instance niet resolvable in deze test → propagator wordt
        // niet ingjecteerd, dus traceparent ontbreekt; tracestate per definitie ook.
        // Doel-assertie: tracestate is niet aanwezig (case-insensitive header-keys).
        val tracestateAanwezig = headers.keys.any { it.equals("tracestate", ignoreCase = true) }
        assertFalse(tracestateAanwezig, "tracestate header lekt naar downstream: $headers")
    }

    /**
     * Headers van de embedded server, met kleine-letter-sleutels. `HttpExchange` normaliseert
     * headernamen naar `Capitalized-Case`, dus zoeken op de exacte constante zou missen.
     */
    private fun ontvangenHeaders(): Map<String, List<String>> {
        // `pollDelay` op nul: `http.send` is blokkerend en de teller loopt vóór het antwoord op,
        // dus de default van 100 ms is puur wachttijd — over alle aanroepen heen een halve seconde
        // per testrun.
        org.awaitility.Awaitility.await()
            .pollDelay(java.time.Duration.ZERO)
            .atMost(2, java.util.concurrent.TimeUnit.SECONDS)
            .until { server.aantalAanroepen >= 1 }

        return server.headers.single().mapKeys { (naam, _) -> naam.lowercase() }
    }

    /**
     * Alleen afwezig en leeg: dát is het gedocumenteerde gedrag van een niet-gezette
     * `*_GRANT_HASH`-env-var. Een waarde van alleen witruimte hoort níet stil terug te vallen op
     * verkeer buiten de mesh en heeft een eigen test.
     */
    @ParameterizedTest(name = "grant-hash [{0}] levert geen FSC-headers")
    @NullSource
    @ValueSource(strings = [""])
    fun `zonder grant-hash gaan er geen FSC-headers mee`(hash: String?) {
        every { config.downstreams() } returns mapOf("aanmeld" to DownstreamStub(server.baseUrl, hash))

        val resultaat = client.lever(Publicatiedoel("aanmeld"), event)

        assertEquals(DownstreamResultaat.Geslaagd, resultaat)

        val headers = ontvangenHeaders()

        assertFalse(headers.containsKey(FscOutwayHeaders.GRANT_HASH_HEADER.lowercase()))
        assertFalse(headers.containsKey(FscOutwayHeaders.TRANSACTION_ID_HEADER.lowercase()))
    }

    @Test
    fun `met grant-hash gaan beide FSC-outway-headers mee`() {
        val hash = "\$1\$4\$k4rwlWTsCM_j89Fc3nrbnQa9-KB43"

        every { config.downstreams() } returns mapOf("aanmeld" to DownstreamStub(server.baseUrl, hash))

        val resultaat = client.lever(Publicatiedoel("aanmeld"), event)

        assertEquals(DownstreamResultaat.Geslaagd, resultaat)

        val headers = ontvangenHeaders()

        assertEquals(hash, headers.getValue(FscOutwayHeaders.GRANT_HASH_HEADER.lowercase()).single())

        val transactionId = UUID.fromString(
            headers.getValue(FscOutwayHeaders.TRANSACTION_ID_HEADER.lowercase()).single(),
        )

        assertEquals(7, transactionId.version(), "de outway weigert een transaction-id die geen UUID v7 is")
    }

    @Test
    fun `een outway-call biedt geen h2c-upgrade aan`() {
        // De outway proxyt hop-by-hop-headers ongewijzigd door naar de inway, en het
        // Go-http2-transport daarachter weigert een `Upgrade: h2c` met 502. De JDK-client stuurt
        // die upgrade standaard mee op een plain-http-doel, dus het uitblijven ervan is precies
        // wat deze call bruikbaar maakt.
        every { config.downstreams() } returns mapOf("aanmeld" to DownstreamStub(server.baseUrl, "hash"))

        client.lever(Publicatiedoel("aanmeld"), event)

        val headers = ontvangenHeaders()

        assertFalse(headers.containsKey("upgrade"), "h2c-upgrade aangeboden: $headers")
        assertFalse(headers.containsKey("http2-settings"), "h2c-upgrade aangeboden: $headers")
    }

    @Test
    fun `grant-hash met omringende whitespace gaat getrimd de header in`() {
        // Een hash komt via een env-var uit een gegenereerd bestand en krijgt makkelijk een
        // newline mee; de outway antwoordt daarop met 400 UNKNOWN_GRANT_HASH_IN_HEADER.
        every { config.downstreams() } returns
            mapOf("aanmeld" to DownstreamStub(server.baseUrl, "  hash-met-ruimte  "))

        client.lever(Publicatiedoel("aanmeld"), event)

        val headers = ontvangenHeaders()

        assertEquals(
            "hash-met-ruimte",
            headers.getValue(FscOutwayHeaders.GRANT_HASH_HEADER.lowercase()).single(),
        )
    }

    @Test
    fun `een intern adres zonder grant-hash blijft geweigerd`() {
        val resultaat = client.valideerUrl("https://10.0.0.1:8443/events", viaOutway = false)

        assertTrue(
            resultaat != null && resultaat.reden.contains("SSRF"),
            "een RFC1918-adres zonder grant-hash hoort op de SSRF-blocklist te stranden, kreeg: $resultaat",
        )
    }

    @Test
    fun `een intern adres mag wel als het de eigen outway is - het contract bepaalt de bestemming`() {
        // De outway luistert op een adres dat naar RFC1918 resolveert; de blocklist zou dat pad
        // blokkeren terwijl het FSC-contract achter de hash de bestemming al vastlegt.
        stelOutwayIn("10.255.255.1")

        val resultaat = client.valideerUrl("https://10.255.255.1:8443/events", viaOutway = true)

        assertNull(resultaat, "een downstream op de eigen outway hoort de blocklist te passeren")
    }

    @Test
    fun `een grant-hash opent de blocklist niet voor een ander intern adres`() {
        // De rechtvaardiging voor de uitzondering is dat het FSC-contract de bestemming bepaalt, en
        // dat geldt alleen voor verkeer dat de outway ook echt binnengaat. Zonder deze grens maakt
        // één grant-hash in de config van de magazijn-pod een proxy naar elk intern adres.
        stelOutwayIn("10.255.255.1")

        val resultaat = client.valideerUrl("https://10.0.0.1:8443/interne-dienst", viaOutway = true)

        assertTrue(
            resultaat != null && resultaat.reden.contains("outway"),
            "een grant-hash bij een andere host dan de outway hoort te stranden, kreeg: $resultaat",
        )
    }

    @Test
    fun `een grant-hash zonder geconfigureerde outway-host levert een configuratiefout`() {
        // Fail-closed en met de werkelijke reden: een stille bypass is van buitenaf niet te
        // onderscheiden van een werkende configuratie.
        stelOutwayIn(null)

        val resultaat = client.valideerUrl("https://10.255.255.1:8443/events", viaOutway = true)

        assertTrue(
            resultaat != null && resultaat.reden.contains("outway.host"),
            "zonder outway-host hoort de hash de blocklist niet te openen, kreeg: $resultaat",
        )
    }

    @Test
    fun `de outway-host wordt hoofdletterongevoelig vergeleken`() {
        // `URI.getHost()` geeft de host terug zoals hij in de URL staat; DNS is
        // hoofdletterongevoelig, dus een verschil in schrijfwijze mag geen aflevering kosten.
        stelOutwayIn("Outway.Intern.Example")

        val resultaat = client.valideerUrl("https://outway.intern.EXAMPLE:8443/events", viaOutway = true)

        assertNull(resultaat, "een verschil in hoofdletters hoort de host-match niet te breken")
    }

    @Test
    fun `met meerdere downstreams gaat de hash van het aangeroepen doel mee, niet die van een buur`() {
        // Met een map van één entry is "kiest de juiste downstream" niet te onderscheiden van
        // "pakt de enige": een refactor naar `values.first()` zou dan groen blijven terwijl een
        // geldige grant-hash naar de verkeerde partij vertrekt.
        every { config.downstreams() } returns mapOf(
            "aanmeld" to DownstreamStub(server.baseUrl, "hash-van-aanmeld"),
            "notificatie" to DownstreamStub(server.baseUrl, "hash-van-notificatie"),
            "archief" to DownstreamStub(server.baseUrl, "hash-van-archief"),
        )

        val resultaat = client.lever(Publicatiedoel("notificatie"), event)

        assertEquals(DownstreamResultaat.Geslaagd, resultaat)

        val headers = ontvangenHeaders()

        assertEquals(
            "hash-van-notificatie",
            headers.getValue(FscOutwayHeaders.GRANT_HASH_HEADER.lowercase()).single(),
        )
    }

    @Test
    fun `een downstream zonder hash naast een downstream met hash krijgt geen FSC-headers`() {
        // De keerzijde van de vorige test: een grant-hash mag niet naar een buur uitlekken die
        // rechtstreeks verkeer hoort te krijgen — die zou hem doorgeven aan een partij zonder
        // contract.
        every { config.downstreams() } returns mapOf(
            "aanmeld" to DownstreamStub(server.baseUrl),
            "notificatie" to DownstreamStub(server.baseUrl, "hash-van-notificatie"),
        )

        val resultaat = client.lever(Publicatiedoel("aanmeld"), event)

        assertEquals(DownstreamResultaat.Geslaagd, resultaat)

        val headers = ontvangenHeaders()

        assertFalse(headers.containsKey(FscOutwayHeaders.GRANT_HASH_HEADER.lowercase()))
        assertFalse(headers.containsKey(FscOutwayHeaders.TRANSACTION_ID_HEADER.lowercase()))
    }

    @ParameterizedTest(name = "grant-hash met teken {0} levert een configuratiefout")
    @ValueSource(strings = ["hash\nmet-newline", "hash\u0000met-nul", "hash-mét-accent", "hash met spatie"])
    fun `een hash die niet in een header past faalt als configuratiefout`(hash: String) {
        // `HttpRequest.Builder.header` gooit hierop een IllegalArgumentException. Die zou langs
        // lever() heen lopen zonder `pogingen` op te hogen: de claim komt ongewijzigd terug in
        // TE_PUBLICEREN en struikelt elke ronde opnieuw, dus één configwaarde legt de outbox stil.
        every { config.downstreams() } returns mapOf("aanmeld" to DownstreamStub(server.baseUrl, hash))

        val resultaat = client.lever(Publicatiedoel("aanmeld"), event)

        assertTrue(
            resultaat is DownstreamResultaat.ConfiguratieFout,
            "een onbruikbare hash hoort een terminale configuratiefout te geven, kreeg: $resultaat",
        )
        assertFalse((resultaat as DownstreamResultaat.Mislukt).herstelbaar, "retryen helpt hier niet")
        assertEquals(0, server.aantalAanroepen, "er hoort geen request de deur uit te gaan")
    }

    @Test
    fun `een hash van alleen witruimte valt niet stil terug op verkeer buiten de mesh`() {
        // Leeg betekent "bewust geen outway"; alleen witruimte is een typfout. Stil rechtstreeks
        // afleveren zou de verantwoording in de txlogs kosten zonder dat iets dat meldt.
        every { config.downstreams() } returns mapOf("aanmeld" to DownstreamStub(server.baseUrl, "   "))

        val resultaat = client.lever(Publicatiedoel("aanmeld"), event)

        assertTrue(
            resultaat is DownstreamResultaat.ConfiguratieFout && resultaat.reden.contains("witruimte"),
            "een hash van alleen witruimte hoort te stranden, kreeg: $resultaat",
        )
    }

    @Test
    fun `de faalreden draagt het antwoord van de bestemming en de transaction-id`() {
        // Een kale "HTTP 400 van aanmeld" laat een eigen configuratiefout niet onderscheiden van
        // een fout van de bestemming, terwijl beide terminal zijn. Het onderscheid staat in de
        // body, en de transaction-id is de enige sleutel naar de rij in de txlogs.
        server.close()
        server = DownstreamHttpServer().apply {
            statusVoorAanroep = { _ -> 400 }
            antwoordBody = "UNKNOWN_GRANT_HASH_IN_HEADER"
        }
        server.start()
        every { config.downstreams() } returns mapOf("aanmeld" to DownstreamStub(server.baseUrl, "hash"))
        client = DownstreamClient(config, objectMapper, openTelemetry, "prod")

        val resultaat = client.lever(Publicatiedoel("aanmeld"), event)

        val reden = (resultaat as DownstreamResultaat.HttpFout).reden

        assertTrue(reden.contains("UNKNOWN_GRANT_HASH_IN_HEADER"), "body ontbreekt in de reden: $reden")
        assertTrue(reden.contains(FscOutwayHeaders.TRANSACTION_ID_HEADER), "transaction-id ontbreekt: $reden")
    }

    @Test
    fun `een rechtstreekse downstream krijgt geen transaction-id in zijn faalreden`() {
        // Zonder outway is er geen txlog om naar te verwijzen; een id in de reden zou suggereren
        // dat die correlatie bestaat.
        server.close()
        server = DownstreamHttpServer().apply { statusVoorAanroep = { _ -> 500 } }
        server.start()
        every { config.downstreams() } returns mapOf("aanmeld" to DownstreamStub(server.baseUrl))
        client = DownstreamClient(config, objectMapper, openTelemetry, "prod")

        val resultaat = client.lever(Publicatiedoel("aanmeld"), event)

        val reden = (resultaat as DownstreamResultaat.HttpFout).reden

        assertFalse(reden.contains(FscOutwayHeaders.TRANSACTION_ID_HEADER), "onterechte transaction-id: $reden")
    }

    @ParameterizedTest(name = "{0} downstream(s) met grant-hash")
    @ValueSource(ints = [0, 1, 3])
    fun `de boot-melding noemt elke downstream die door de outway loopt`(aantal: Int) {
        // Het token draagt een alert-regel bij ops: valt hij niet, dan is aan een draaiende pod
        // niet te zien dat de SSRF-blocklist voor een deel van het verkeer vervalt.
        val downstreams = (1..aantal).associate { "doel-$it" to DownstreamStub(server.baseUrl, "hash-$it") }

        every { config.downstreams() } returns downstreams

        val regels = vangLogregels { client.meldActieveUitzonderingen(StartupEvent()) }
        val melding = regels.singleOrNull { it.contains(DownstreamClient.OUTWAY_SSRF_ALERT_TOKEN) }

        if (aantal == 0) {
            assertNull(melding, "zonder outway-downstream hoort het token niet te vallen")
        } else {
            assertTrue(melding != null && melding.contains(SERVER_HOST), "outway-host ontbreekt: $melding")
            downstreams.keys.forEach { key ->
                assertTrue(melding!!.contains(key), "downstream '$key' ontbreekt in de melding: $melding")
            }
        }
    }

    @Test
    fun `een grant-hash bij een andere host dan de outway wordt bij boot gemeld`() {
        // Die combinatie is altijd een vergissing: de header komt bij een partij die er niets mee
        // doet, en het contract dat de aflevering verantwoordt blijft ongebruikt.
        every { config.downstreams() } returns mapOf(
            "notificatie" to DownstreamStub("https://ergens-anders.example/events", "hash"),
        )

        val regels = vangLogregels { client.meldActieveUitzonderingen(StartupEvent()) }

        assertTrue(
            regels.any { it.contains("wijst niet naar") && it.contains("notificatie") },
            "een hash zonder passende outway-host hoort gemeld te worden: $regels",
        )
        assertTrue(
            regels.none { it.contains(DownstreamClient.OUTWAY_SSRF_ALERT_TOKEN) },
            "het SSRF-token hoort hier niet te vallen: $regels",
        )
    }

    @Test
    fun `de outway-aflevering logt het doel maar nooit de URL`() {
        // De downstream-URL kan een pad met persoonsgegevens dragen. `FscOutwayHeadersTest` pint
        // dezelfde invariant aan de JAX-RS-kant; zonder deze test kan hij hier stil wegvallen.
        every { config.downstreams() } returns mapOf("aanmeld" to DownstreamStub(server.baseUrl, "hash"))

        val regels = vangLogregels { client.lever(Publicatiedoel("aanmeld"), event) }

        assertTrue(regels.any { it.contains("aanmeld") }, "het doel hoort in de log: $regels")
        assertTrue(regels.none { it.contains(server.baseUrl) }, "de URL lekt naar de log: $regels")
    }

    /**
     * De logregels die [actie] op [DownstreamClient] produceert, als geformatteerde tekst.
     * `Level.ALL` omdat de aflevering op DEBUG logt en de uitzonderingen op WARN.
     */
    private fun vangLogregels(actie: () -> Unit): List<String> {
        val julLogger = java.util.logging.Logger.getLogger(DownstreamClient::class.java.name)
        val records = mutableListOf<java.util.logging.LogRecord>()
        val handler = object : java.util.logging.Handler() {
            override fun publish(record: java.util.logging.LogRecord) {
                records.add(record)
            }
            override fun flush() {}
            override fun close() {}
        }
        val oudNiveau = julLogger.level

        julLogger.addHandler(handler)
        julLogger.level = java.util.logging.Level.ALL

        try {
            actie()
        } finally {
            julLogger.removeHandler(handler)
            julLogger.level = oudNiveau
        }

        // JBoss' `warnf`/`debugf` geven de format-string als message door en de argumenten los;
        // asserten op de uiteindelijke regel vraagt dus om ze hier samen te voegen.
        return records.map { record ->
            val parameters = record.parameters

            if (parameters.isNullOrEmpty()) record.message else String.format(record.message, *parameters)
        }
    }

    @Test
    fun `de TLS-eis blijft gelden voor een outway-downstream`() {
        // De uitzondering is er één, niet twee: buiten loopback blijft TLS verplicht, ook met een
        // grant-hash. Anders zou een grant-hash in de config stilzwijgend plaintext-verkeer naar
        // een externe host toestaan.
        val resultaat = client.valideerUrl("http://prod.example.com/events", viaOutway = true)

        assertTrue(
            resultaat != null && resultaat.reden.contains("TLS"),
            "plain http buiten loopback hoort ook met grant-hash af te vallen, kreeg: $resultaat",
        )
    }
}
