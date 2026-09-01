package nl.rijksoverheid.moz.fbs.demopersonas

import com.fasterxml.jackson.databind.ObjectMapper
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * De keten-personas bestaan in twee repositories: hun identiteit staat in `_data/personas.json` van
 * de proeftuin, de opt-ins en het nummer waarop wij afleveren in onze configuratie. Lopen die uiteen,
 * dan levert het magazijn aan een nummer dat de berichtenbox niet toont — zonder dat iets faalt.
 *
 * `proeftuin-personas.json` is een kopie van hún bestand, beperkt tot de twee velden die wij nodig
 * hebben. Deze test houdt de twee tegen elkaar; een wijziging aan hun kant hoort hier rood te worden.
 */
class ProeftuinPersonaTest {

    private val ingericht = TestPersonas.uitApplicationProperties().alle().associateBy { it.id }
    private val overgenomen = ObjectMapper().readTree(
        javaClass.classLoader.getResourceAsStream("proeftuin-personas.json"),
    ).path("personas")

    @Test
    fun `elke overgenomen persona staat met dezelfde naam en hetzelfde nummer in de configuratie`() {
        assertTrue(overgenomen.any(), "geen keten-personas overgenomen uit de proeftuin")

        overgenomen.forEach {
            val id = it.path("id").asText()
            val persona = ingericht[id]

            assertTrue(persona != null, "keten-persona '$id' ontbreekt in demo.personas.*")
            assertEquals(it.path("bedrijf").path("handelsnaam").asText(), persona!!.label, "label van '$id'")
            assertEquals("KVK:${it.path("bedrijf").path("kvkNummer").asText()}", persona.ontvanger, "nummer van '$id'")
        }
    }

    @Test
    fun `de keten-personas ontvangen berichten, anders blijft de berichtenbox leeg`() {
        overgenomen.forEach {
            val persona = ingericht.getValue(it.path("id").asText())

            assertEquals(PersonaBron.KETEN, persona.bron, "bron van '${persona.id}'")

            // Een persona die alleen ophaalt krijgt zijn berichten van de stub-magazijnen; die
            // staan niet in demo.magazijnen en dus ook niet in zijn opt-ins.
            if (!it.path("opthaaltAlleen").asBoolean()) {
                assertTrue(persona.magazijnen.isNotEmpty(), "keten-persona '${persona.id}' heeft geen opt-in")
            }
        }
    }
}
