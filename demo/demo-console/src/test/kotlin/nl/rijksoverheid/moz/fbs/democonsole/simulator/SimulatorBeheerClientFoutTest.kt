package nl.rijksoverheid.moz.fbs.democonsole.simulator

import io.quarkus.test.junit.QuarkusTest
import jakarta.inject.Inject
import org.eclipse.microprofile.rest.client.inject.RestClient
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Een afgewezen beheeraanroep moet over de échte client een fout opleveren.
 *
 * `LeegUitkomst` en `SeedUitkomst` bestaan enkel uit primitieven, en die vult jackson-module-kotlin
 * met hun primitieve default zodra het veld ontbreekt. Zonder een geregistreerde
 * [SimulatorBeheerFout] leest de client de problem+json-body van een 401 dus uit als een geslaagde
 * uitkomst met nullen, en meldt het paneel "gelukt, 0 berichten" terwijl er niets geleegd is.
 *
 * Draait tegen [NepSimulatorBeheer] in deze applicatie zelf; geen Docker nodig.
 */
@QuarkusTest
class SimulatorBeheerClientFoutTest {

    @Inject
    @RestClient
    lateinit var beheer: SimulatorBeheerClient

    @Test
    fun `een 401 op legen wordt een fout en geen uitkomst met nullen`() {
        val fout = assertThrows(IllegalStateException::class.java) { beheer.legen() }

        assertTrue(
            fout.message!!.contains("MAGAZIJN_SIMULATOR_BEHEER_TOKEN"),
            "melding moet naar het token wijzen, was: ${fout.message}",
        )
    }

    @Test
    fun `een 401 op seed wordt net zo goed een fout`() {
        assertThrows(IllegalStateException::class.java) {
            beheer.seed(SeedVerzoek(listOf("KVK:90000001"), 1, 1))
        }
    }

    @Test
    fun `een 401 op het uitlezen van de magazijnen wordt een fout`() {
        // Deze faalde vóór de mapper al luidruchtig — een object past niet in een lijst — maar met
        // een melding over ArrayList waarin het woord token niet voorkwam.
        val fout = assertThrows(IllegalStateException::class.java) { beheer.magazijnen() }

        assertTrue(
            fout.message!!.contains("MAGAZIJN_SIMULATOR_BEHEER_TOKEN"),
            "melding moet naar het token wijzen, was: ${fout.message}",
        )
    }
}
