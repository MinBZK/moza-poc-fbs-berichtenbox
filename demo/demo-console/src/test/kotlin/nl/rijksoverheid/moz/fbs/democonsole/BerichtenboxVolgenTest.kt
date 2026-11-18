package nl.rijksoverheid.moz.fbs.democonsole

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File

/**
 * Houdt de berichtenbox van de demo bij wat de uitvraag op `_volgen` stuurt. Komt er een event bij
 * in de spec, dan hoort de demo te laten zien wat het betekent; anders blijft een gelezen of
 * verwijderd bericht in een ander tabblad gewoon staan en lijkt de keten stuk.
 *
 * Leest de spec van de uitvraag als bestand: een verwijzing van de demo naar het stelsel mag, en
 * staat zo niet in de reactor-graaf.
 */
class BerichtenboxVolgenTest {

    private val box = PaneelBestanden.boxScript()

    private val spec = File(SPEC_PAD).readText()

    @Test
    fun `de berichtenbox verwerkt elk event dat de uitvraag op _volgen kent`() {
        val soorten = volgEvents()

        assertTrue(soorten.containsAll(listOf("bericht-gewijzigd", "bericht-verwijderd")), "spec niet goed gelezen: $soorten")

        // In de switch zelf, niet ergens in een comment of een andere functie. Een hartslag hoeft
        // niets te doen.
        val verwerking = functie("verwerkVolgEvent")
        val onverwerkt = (soorten - "hartslag").filterNot { verwerking.contains("case '$it'") }

        assertEquals(emptyList<String>(), onverwerkt, "verwerkVolgEvent in berichtenbox.js verwerkt deze events niet")
    }

    @Test
    fun `een event tijdens het laden van de lijst wordt na het laden opnieuw toegepast`() {
        // Anders zet een antwoord van vóór de wijziging de oude toestand terug, zoals de spec van
        // _volgen beschrijft.
        assertTrue(functie("verwerkVolgEvent").contains("lopendeLezingen.forEach"), "events tijdens een lezing worden niet bewaard")
        assertTrue(functie("laadLijst").contains("lopendeLezingen.add"), "laadLijst houdt de events tijdens de lezing niet bij")

        val laden = functie("laadLijstMetEvents")

        assertTrue(
            laden.indexOf("tijdensLezing.forEach(pasVolgEventToe)") > laden.indexOf("alleBerichten = verzameld"),
            "de bewaarde events gaan niet ná het vervangen van de lijst opnieuw over de lijst",
        )
    }

    @Test
    fun `de berichtenbox volgt, en stopt daarmee bij het wisselen van persona`() {
        assertTrue(box.contains("'/berichten/_volgen'"), "de berichtenbox opent _volgen niet")

        assertTrue(functie("wisPersonaState").contains("stopVolgen()"), "de stream van de vorige persona blijft open na een wissel")
    }

    /** De body van een functie op het hoogste niveau van het script, tot de sluitaccolade aan het begin van een regel. */
    private fun functie(naam: String): String {
        val start = Regex("""(?m)^(async )?function $naam\(""").find(box)

        assertTrue(start != null, "functie $naam niet gevonden in berichtenbox.js")

        return box.substring(start!!.range.first).substringBefore("\n}")
    }

    /** De sleutels van de discriminator-mapping van `VolgEvent`. */
    private fun volgEvents(): List<String> {
        val blok = spec.substringAfter("\n    VolgEvent:\n").substringAfter("mapping:\n").substringBefore("\n\n")

        return Regex("""^\s+([a-z-]+): '#/components/schemas/""", RegexOption.MULTILINE)
            .findAll(blok)
            .map { it.groupValues[1] }
            .toList()
    }

    private companion object {
        const val SPEC_PAD = "../../services/berichtenuitvraag/src/main/resources/openapi/berichtenuitvraag-api.yaml"
    }
}
