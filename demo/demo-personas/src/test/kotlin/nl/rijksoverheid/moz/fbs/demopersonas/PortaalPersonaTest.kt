package nl.rijksoverheid.moz.fbs.demopersonas

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * De keten-personas bestaan in twee repositories: hun identiteit staat in `src/demo/personas.json`
 * van het MOZa-portaal, de opt-ins en het nummer waarop wij afleveren in onze configuratie. Lopen die
 * uiteen, dan levert het magazijn aan een nummer dat de Berichtenbox niet toont — zonder dat iets
 * faalt.
 *
 * `portaal-personas.json` is een kopie van hún bestand, beperkt tot de velden die wij nodig hebben.
 * Het portaal koppelt op KVK-nummer en niet op id (hun `proeftuin-veel` is onze `grootbedrijf`), dus
 * deze test doet dat ook. Een wijziging aan hun kant hoort hier rood te worden.
 */
class PortaalPersonaTest {

    private val ingericht = TestPersonas.uitConfiguratie().alle().associateBy { it.ontvanger }
    private val overgenomen = ObjectMapper().readTree(
        javaClass.classLoader.getResourceAsStream("portaal-personas.json"),
    ).path("personas")

    private fun ontvanger(overgenomenPersona: JsonNode) =
        "KVK:${overgenomenPersona.path("bedrijf").path("kvkNummer").asText()}"

    @Test
    fun `elke overgenomen persona staat met dezelfde naam en hetzelfde nummer in de configuratie`() {
        assertTrue(overgenomen.any(), "geen keten-personas overgenomen uit het portaal")

        overgenomen.forEach {
            val id = it.path("id").asText()
            val persona = ingericht[ontvanger(it)]

            assertTrue(persona != null, "keten-persona '$id' (${ontvanger(it)}) ontbreekt in demo.personas.*")
            assertEquals(it.path("bedrijf").path("handelsnaam").asText(), persona!!.label, "label van '$id'")
        }
    }

    @Test
    fun `elke KVK-persona van ons wordt door het portaal ook aangeboden`() {
        // De andere richting, en die is even belangrijk: wie hier wél staat maar bij hen niet, is
        // in de Berichtenbox niet te kiezen. Dat merkte niemand, want de kruiscontrole liep maar
        // één kant op — zo bleef Landelijk Concern ooit onzichtbaar terwijl hij netjes ingericht was.
        val hunNummers = overgenomen.map { ontvanger(it) }.toSet()

        val gecontroleerd = ingericht.values.filter { it.bron == PersonaBron.KETEN && it.type == "KVK" }

        // Zonder deze guard is de test voor altijd groen zodra het filter hierboven breekt: de set
        // is dan leeg en `ontbrekend.isEmpty()` klopt per constructie.
        assertTrue(gecontroleerd.isNotEmpty(), "geen enkele KVK-keten-persona gecontroleerd; klopt het filter nog?")

        val ontbrekend = gecontroleerd.filterNot { it.ontvanger in hunNummers }

        assertTrue(
            ontbrekend.isEmpty(),
            "deze persona's staan in demo.personas maar biedt het portaal niet aan: " +
                ontbrekend.joinToString { it.id } + ". Laat ze daar toevoegen met `stelsel: true`.",
        )
    }

    @Test
    fun `de keten-personas ontvangen berichten, anders blijft de Berichtenbox leeg`() {
        overgenomen.forEach {
            val persona = ingericht.getValue(ontvanger(it))

            assertEquals(PersonaBron.KETEN, persona.bron, "bron van '${persona.id}'")

            // Een persona die alleen ophaalt krijgt zijn berichten van de gesimuleerde magazijnen;
            // die staan niet in demo.magazijnen en dus ook niet in zijn opt-ins.
            if (!it.path("ophaaltAlleen").asBoolean()) {
                assertTrue(persona.magazijnen.isNotEmpty(), "keten-persona '${persona.id}' heeft geen opt-in")
            }
        }
    }
}
