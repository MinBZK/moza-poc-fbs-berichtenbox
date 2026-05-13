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
    fun `https tegen TCP-server die garbage stuurt triggert SSLException-pad`() {
        // Borgt de SSLException-catch-volgorde (Round 8 H2 + Round 9 M2):
        // SSLHandshakeException → ConfiguratieFout (non-herstelbaar) staat
        // VOOR de generieke SSLException → NetwerkFout (herstelbaar). Een
        // refactor die deze volgorde wijzigt zou eindeloos retry op een
        // permanent cert-faal — ressource-verspilling. Test door een TCP-
        // server te starten die garbage als TLS-handshake-response stuurt:
        // JDK SSL-stack detecteert dit en gooit een SSLException-variant.
        val tcpServer = java.net.ServerSocket(0)
        val acceptThread = Thread {
            try {
                val socket = tcpServer.accept()
                // Stuur 5 bytes die geen geldige TLS server-hello vormen.
                // JDK SSLEngine herkent dit als protocol-violation → SSLException.
                socket.outputStream.write(byteArrayOf(0x00, 0x01, 0x02, 0x03, 0x04))
                socket.outputStream.flush()
                socket.close()
            } catch (_: Exception) {
                // Server-side close tijdens shutdown — irrelevant voor test.
            }
        }
        acceptThread.isDaemon = true
        acceptThread.start()

        try {
            val httpsUrl = "https://localhost:${tcpServer.localPort}/events"
            every { config.downstreams() } returns mapOf("aanmeld" to DownstreamStub(httpsUrl))
            client = DownstreamClient(config, objectMapper, openTelemetry)

            val resultaat = client.lever(PublicatieDoel("aanmeld"), event)

            // Resultaat moet ConfiguratieFout (SSLHandshakeException) of NetwerkFout
            // (overige SSLException) zijn — beide bewijzen dat de SSL-catches vóór
            // de generieke IOException-catch geraakt worden. Reden bevat "TLS".
            val isMislukt = resultaat is DownstreamResultaat.ConfiguratieFout ||
                resultaat is DownstreamResultaat.NetwerkFout
            assertTrue(isMislukt, "verwacht TLS-classificatie — gevonden: $resultaat")
            val reden = when (resultaat) {
                is DownstreamResultaat.ConfiguratieFout -> resultaat.reden
                is DownstreamResultaat.NetwerkFout -> resultaat.reden
                else -> ""
            }
            assertTrue(
                reden.contains("TLS"),
                "reden moet TLS-laag fout aangeven (anders raakt code de generieke IOException-catch en is volgorde gebroken) — gevonden: $reden",
            )
        } finally {
            tcpServer.close()
        }
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
