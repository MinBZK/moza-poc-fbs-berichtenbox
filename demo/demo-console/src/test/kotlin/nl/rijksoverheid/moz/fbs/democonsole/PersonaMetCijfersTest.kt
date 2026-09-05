package nl.rijksoverheid.moz.fbs.democonsole

import io.quarkus.test.common.http.TestHTTPResource
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.junit.QuarkusTestProfile
import io.quarkus.test.junit.TestProfile
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.net.URI
import java.net.URL
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse

/**
 * Een persona wiens id cijfers draagt. De personadienst staat zo'n id toe — `DemoPersona` weigert
 * alleen een id dat kaal een identificatienummer ís — dus het paneel biedt hem gewoon aan.
 *
 * Deze inrichting staat in een eigen profiel en niet in de demo-configuratie: hij bestaat om een
 * volgorde te bewaken, niet om iets te demonstreren. Die volgorde is dat de nummercontrole pas
 * draait wanneer de opzoeking niets oplevert. Zou ze vooraan komen te staan, dan weigert dit adres
 * met een 400 "gebruik een naam, geen nummer" — midden in een demo, op een persona die de
 * keuzelijst zelf aanbiedt.
 */
@QuarkusTest
@TestProfile(PersonaMetCijfersTest.MetCijfersInDeId::class)
class PersonaMetCijfersTest {

    @TestHTTPResource("/")
    lateinit var basis: URL

    @BeforeEach
    fun leegDeOpnames() {
        VasteOntdubbelingService.nummers.clear()
    }

    @Test
    fun `een ingerichte persona met cijfers in zijn id wordt gewoon aangenomen`() {
        val respons = HttpClient.newHttpClient().send(
            HttpRequest.newBuilder(URI.create(basis.toString().removeSuffix("/") + "/api/demo/ontdubbeling?persona=$ID"))
                .POST(HttpRequest.BodyPublishers.noBody())
                .build(),
            HttpResponse.BodyHandlers.ofString(),
        )

        assertEquals(200, respons.statusCode(), respons.body())
        assertEquals(listOf(BSN), VasteOntdubbelingService.nummers)
    }

    class MetCijfersInDeId : QuarkusTestProfile {

        // Zonder magazijnen: de ontdubbeling levert niets aan, en zo blijft de rest van de
        // demo-inrichting ongemoeid.
        override fun getConfigOverrides(): Map<String, String> = mapOf(
            "demo.personas.$ID.label" to "Demo-onderneming 2026",
            "demo.personas.$ID.type" to "BSN",
            "demo.personas.$ID.waarde" to BSN,
            "demo.personas.$ID.bron" to "keten",
        )
    }

    private companion object {

        const val ID = "proeftuin-2026"

        /** Fictief, uit de 999-testreeks, en niet van een andere persona: die moeten uniek zijn. */
        const val BSN = "999999990"
    }
}
