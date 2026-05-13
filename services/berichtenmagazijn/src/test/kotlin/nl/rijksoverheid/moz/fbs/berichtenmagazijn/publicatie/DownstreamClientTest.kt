package nl.rijksoverheid.moz.fbs.berichtenmagazijn.publicatie

import com.fasterxml.jackson.core.JsonProcessingException
import com.fasterxml.jackson.databind.ObjectMapper
import io.mockk.every
import io.mockk.mockk
import io.opentelemetry.api.OpenTelemetry
import jakarta.enterprise.inject.Instance
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.time.Instant
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

    private class DownstreamStub(private val u: String) : PublicatieConfig.Downstream {
        override fun url(): String = u
    }

    private class SimuleerdeJsonFout(msg: String) : JsonProcessingException(msg)

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
            publicatieDatum = Instant.parse("2026-05-12T10:00:00Z"),
        ),
    )

    @BeforeEach
    fun start() {
        server = DownstreamHttpServer()
        server.start()
        config = mockk()
        every { config.downstreams() } returns mapOf("aanmeld" to DownstreamStub(server.baseUrl))
        client = DownstreamClient(config, objectMapper, openTelemetry)
    }

    @AfterEach
    fun stop() {
        server.close()
        client.stop()
    }

    @Test
    fun `2xx response geeft Geslaagd`() {
        val resultaat = client.lever(PublicatieDoel("aanmeld"), event)
        assertEquals(DownstreamResultaat.Geslaagd, resultaat)
    }

    @Test
    fun `5xx response geeft HttpFout met herstelbaar`() {
        server.close()
        server = DownstreamHttpServer(statusVoorAanroep = { _ -> 500 })
        server.start()
        every { config.downstreams() } returns mapOf("aanmeld" to DownstreamStub(server.baseUrl))
        client = DownstreamClient(config, objectMapper, openTelemetry)

        val resultaat = client.lever(PublicatieDoel("aanmeld"), event)
        assertTrue(resultaat is DownstreamResultaat.HttpFout)
        val httpFout = resultaat as DownstreamResultaat.HttpFout
        assertEquals(500, httpFout.statusCode)
        assertTrue(httpFout.herstelbaar)
    }

    @Test
    fun `4xx response geeft HttpFout niet-herstelbaar`() {
        server.close()
        server = DownstreamHttpServer(statusVoorAanroep = { _ -> 400 })
        server.start()
        every { config.downstreams() } returns mapOf("aanmeld" to DownstreamStub(server.baseUrl))
        client = DownstreamClient(config, objectMapper, openTelemetry)

        val resultaat = client.lever(PublicatieDoel("aanmeld"), event)
        assertTrue(resultaat is DownstreamResultaat.HttpFout)
        assertEquals(false, (resultaat as DownstreamResultaat.HttpFout).herstelbaar)
    }

    @Test
    fun `onbekend doel geeft ConfiguratieFout`() {
        val resultaat = client.lever(PublicatieDoel("onbekend"), event)
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
        client = DownstreamClient(config, objectMapper, openTelemetry)

        val resultaat = client.mapDeliveryException(
            javax.net.ssl.SSLHandshakeException("Unable to find valid certification path"),
            PublicatieDoel("aanmeld"),
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
        client = DownstreamClient(config, objectMapper, openTelemetry)

        val resultaat = client.mapDeliveryException(
            javax.net.ssl.SSLProtocolException("Connection reset during handshake"),
            PublicatieDoel("aanmeld"),
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
        client = DownstreamClient(config, objectMapper, openTelemetry)

        val handshake = javax.net.ssl.SSLHandshakeException("test")
        // Bewijs class-hierarchy: SSLHandshakeException IS-A SSLException
        assertTrue(handshake is javax.net.ssl.SSLException, "Java class-hierarchy assumption")

        val resultaat = client.mapDeliveryException(handshake, PublicatieDoel("aanmeld"))

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
        client = DownstreamClient(config, objectMapper, openTelemetry)

        val ct = java.net.http.HttpConnectTimeoutException("connect timed out")
        assertTrue(ct is java.net.http.HttpTimeoutException, "Java class-hierarchy assumption")

        val resultaat = client.mapDeliveryException(ct, PublicatieDoel("aanmeld"))

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
        client = DownstreamClient(config, objectMapper, openTelemetry)

        val resultaat = client.mapDeliveryException(
            java.io.IOException("Connection reset"),
            PublicatieDoel("aanmeld"),
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
        val resultaat = client.lever(PublicatieDoel("aanmeld"), event)
        assertTrue(resultaat is DownstreamResultaat.ConfiguratieFout)
        assertTrue((resultaat as DownstreamResultaat.ConfiguratieFout).reden.contains("TLS"))
    }

    @Test
    fun `plain http naar localhost wordt toegestaan voor dev`() {
        // server.baseUrl is al http://127.0.0.1:* — dat moet door valideerUrl heen komen.
        val resultaat = client.lever(PublicatieDoel("aanmeld"), event)
        assertEquals(DownstreamResultaat.Geslaagd, resultaat)
    }

    @Test
    fun `ongeldige URL geeft ConfiguratieFout`() {
        every { config.downstreams() } returns mapOf("aanmeld" to DownstreamStub("not a url at all"))
        val resultaat = client.lever(PublicatieDoel("aanmeld"), event)
        assertTrue(resultaat is DownstreamResultaat.ConfiguratieFout)
    }

    @Test
    fun `serialisatie-fout geeft SerialisatieFout`() {
        val kapotteMapper = mockk<ObjectMapper>()
        every { kapotteMapper.writeValueAsBytes(any()) } throws SimuleerdeJsonFout("ka-boom")
        client = DownstreamClient(config, kapotteMapper, openTelemetry)

        val resultaat = client.lever(PublicatieDoel("aanmeld"), event)
        assertTrue(resultaat is DownstreamResultaat.SerialisatieFout)
    }

    @Test
    fun `connect-naar-niet-luisterende-poort geeft NetwerkFout of Timeout`() {
        every { config.downstreams() } returns mapOf("aanmeld" to DownstreamStub("http://127.0.0.1:1/events"))
        val resultaat = client.lever(PublicatieDoel("aanmeld"), event)
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
            client = DownstreamClient(config, objectMapper, openTelemetry)

            val resultaat = client.lever(PublicatieDoel("aanmeld"), event)
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
        val resultaat = client.lever(PublicatieDoel("aanmeld"), event)
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
        val resultaat = client.lever(PublicatieDoel("aanmeld"), event)
        assertEquals(DownstreamResultaat.Geslaagd, resultaat)
        // Wacht tot de server de request verwerkt heeft.
        org.awaitility.Awaitility.await()
            .atMost(2, java.util.concurrent.TimeUnit.SECONDS)
            .until { server.aantalAanroepen >= 1 }
        val headers = server.headers.first()
        // OpenTelemetry-instance niet resolvable in deze test → propagator wordt
        // niet ingjecteerd, dus traceparent ontbreekt; tracestate per definitie ook.
        // Doel-assertie: tracestate is niet aanwezig (case-insensitive header-keys).
        val tracestateAanwezig = headers.keys.any { it.equals("tracestate", ignoreCase = true) }
        assertFalse(tracestateAanwezig, "tracestate header lekt naar downstream: $headers")
    }
}
