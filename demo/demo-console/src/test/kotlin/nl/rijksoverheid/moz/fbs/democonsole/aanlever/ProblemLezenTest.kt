package nl.rijksoverheid.moz.fbs.democonsole.aanlever

import com.sun.net.httpserver.HttpServer
import io.quarkus.rest.client.reactive.QuarkusRestClientBuilder
import io.quarkus.test.junit.QuarkusTest
import nl.rijksoverheid.moz.fbs.democonsole.generator.AanleverVerzoek
import nl.rijksoverheid.moz.fbs.democonsole.generator.OntvangerDto
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.net.InetSocketAddress
import java.net.URI
import java.nio.charset.StandardCharsets

/**
 * Of de console de reden die het magazijn meestuurt werkelijk uit de lijn krijgt.
 *
 * De andere tests zetten `readEntity` met een dubbel klaar en slaan daarmee juist het stuk over dat
 * hier stuk kan: het magazijn antwoordt met `application/problem+json`, en of de REST-client daar
 * een reader voor vindt is een eigenschap van de Quarkus-versie, niet van onze code. Vindt hij er
 * geen, dan valt de melding voorgoed terug op de algemene zin terwijl elke gemockte test groen
 * blijft — dan is de kern van deze functie dood zonder dat iets dat zegt.
 *
 * Een JDK-`HttpServer` en geen WireMock: deze module draait haar tests zonder Docker.
 */
@QuarkusTest
class ProblemLezenTest {

    private lateinit var server: HttpServer
    private lateinit var client: MagazijnAanleverClient

    @BeforeEach
    fun start() {
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/api/v1/aanleveringen") { uitwisseling ->
            val body = """{"type":"about:blank","title":"Forbidden","status":403,"detail":"$DETAIL"}"""
                .toByteArray(StandardCharsets.UTF_8)

            uitwisseling.responseHeaders.add("Content-Type", "application/problem+json")
            uitwisseling.sendResponseHeaders(403, body.size.toLong())
            uitwisseling.responseBody.use { it.write(body) }
        }
        server.start()

        client = QuarkusRestClientBuilder.newBuilder()
            .baseUri(URI.create("http://127.0.0.1:${server.address.port}"))
            .build(MagazijnAanleverClient::class.java)
    }

    @AfterEach
    fun stop() = server.stop(0)

    @Test
    fun `de reden uit een problem+json-antwoord komt er als tekst uit`() {
        val reden = client.leverAan(verzoek()).use { antwoord ->
            assertEquals(403, antwoord.status)

            antwoord.readEntity(Problem::class.java).detail
        }

        assertEquals(DETAIL, reden)
    }

    private fun verzoek() = AanleverVerzoek(
        afzender = "00000000000000100000",
        ontvanger = OntvangerDto("BSN", "999993653"),
        onderwerp = "Demo",
        inhoud = "Demo-inhoud",
        publicatietijdstip = "2026-09-04T10:00:00Z",
    )

    private companion object {

        /** Woordelijk wat het magazijn stuurt; zie ToestemmingGeweigerdExceptionMapper. */
        const val DETAIL = "Ontvanger heeft geen actieve berichtenbox-voorkeur voor deze afzender."
    }
}
