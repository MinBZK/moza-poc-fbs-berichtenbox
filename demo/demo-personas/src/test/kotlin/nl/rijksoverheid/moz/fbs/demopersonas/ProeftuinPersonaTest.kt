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

    private val ingericht = TestPersonas.uitConfiguratie().alle().associateBy { it.id }
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
    fun `elke KVK-persona van ons wordt door de proeftuin ook aangeboden`() {
        // De andere richting, en die is even belangrijk: wie hier wél staat maar bij hen niet, is
        // in de berichtenbox niet te kiezen. Dat merkte niemand, want de kruiscontrole liep maar
        // één kant op — zo bleef Landelijk Concern onzichtbaar terwijl hij netjes ingericht was.
        val hunNummers = overgenomen.map { it.path("bedrijf").path("kvkNummer").asText() }.toSet()

        val ontbrekend = ingericht.values
            .filter { it.bron == PersonaBron.KETEN && it.type == "KVK" && it.id !in ALLEEN_BIJ_ONS }
            .filterNot { it.waarde in hunNummers }

        assertTrue(
            ontbrekend.isEmpty(),
            "deze persona's staan in demo.personas maar biedt de proeftuin niet aan: " +
                ontbrekend.joinToString { it.id } +
                ". Laat ze daar toevoegen, of zet ze in ALLEEN_BIJ_ONS met de reden erbij.",
        )
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

    private companion object {

        /**
         * Persona's die wij inrichten en de proeftuin niet aanbiedt. Leeg houden is het doel: staat
         * hier iets, dan is dat een openstaande vraag aan het proeftuin-team en geen eindtoestand.
         *
         * `concern` (Landelijk Concern N.V., KVK 90000003) is de persona met honderd aangesloten
         * organisaties, waarmee de demo laat zien hoe de keten zich in de breedte houdt. Hij staat
         * niet in hun `_data/personas.json`, dus in de berichtenbox is hij niet te kiezen — daar is
         * gevraagd hem toe te voegen. Haal hem hier weg zodra dat gebeurd is; dan bewaakt deze test
         * dat het zo blijft.
         */
        val ALLEEN_BIJ_ONS = setOf("concern")
    }
}
