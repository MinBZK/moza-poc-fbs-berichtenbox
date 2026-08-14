package nl.rijksoverheid.moz.fbs.berichtenmagazijn.publicatie

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse

/**
 * Bewaakt de aannames waarop de gedeelde stub-server in [PublicatieStreamE2ETest] rust.
 *
 * Die tests delen één server en één applicatie-instantie; het antwoordgedrag wordt per test
 * gezet en hoort door `reset()` weer op 202 te komen. Valt die reset weg, dan blijft de suite
 * groen zolang de methodevolgorde meezit en faalt hij daarna op een plek ver van de oorzaak.
 * Deze test is bewust puur JUnit: hij hoeft geen Quarkus, en zou dan een applicatie-start kosten
 * voor iets wat losstaat van de applicatie.
 */
class DownstreamHttpServerTest {

    private lateinit var server: DownstreamHttpServer

    @BeforeEach
    fun start() {
        server = DownstreamHttpServer()
        server.start()
    }

    @AfterEach
    fun stop() {
        server.close()
    }

    /**
     * Nul, één en meerdere aanroepen vóór de reset: bij één aanroep zou een implementatie die
     * alleen de laatste poging terugdraait er ook doorheen komen.
     */
    @ParameterizedTest
    @ValueSource(ints = [0, 1, 3])
    fun `reset zet het antwoordgedrag terug op geaccepteerd`(aanroepenVooraf: Int) {
        server.statusVoorAanroep = { _ -> 500 }

        repeat(aanroepenVooraf) {
            assertEquals(500, post("mislukt"), "gezet gedrag moet gelden tot de reset")
        }

        server.reset()

        assertEquals(202, post("na reset"), "na reset hoort de default weer te gelden")
        assertEquals(1, server.aantalAanroepen, "reset moet ook de teller op nul zetten")
        assertEquals(listOf("na reset"), server.bodies, "reset moet de eerdere bodies wissen")
    }

    @Test
    fun `het antwoordgedrag krijgt het pogingnummer 1-geindexeerd`() {
        val gezien = mutableListOf<Int>()
        server.statusVoorAanroep = { poging ->
            gezien += poging
            if (poging == 1) 500 else 202
        }

        assertEquals(500, post("eerste"))
        assertEquals(202, post("tweede"))
        assertEquals(listOf(1, 2), gezien, "de eerste aanroep is poging 1, niet 0")
    }

    @Test
    fun `de body is beschikbaar zodra de teller hem meetelt`() {
        // De stream-tests wachten op `aantalAanroepen` en lezen daarna `bodies[n]`; als de
        // teller vóór de body zou worden opgehoogd, is dat leesmoment een race.
        repeat(5) { post("body-$it") }

        assertEquals(5, server.aantalAanroepen)
        assertEquals((0 until 5).map { "body-$it" }, server.bodies)
    }

    private fun post(body: String): Int {
        val request = HttpRequest.newBuilder(URI.create(server.baseUrl))
            .POST(HttpRequest.BodyPublishers.ofString(body))
            .build()

        return HttpClient.newHttpClient()
            .send(request, HttpResponse.BodyHandlers.discarding())
            .statusCode()
    }
}
