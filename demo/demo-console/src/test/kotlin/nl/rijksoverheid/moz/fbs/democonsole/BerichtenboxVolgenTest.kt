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

        // Een hartslag hoeft niets te doen.
        val onverwerkt = (soorten - "hartslag").filterNot { box.contains("'$it'") }

        assertEquals(emptyList<String>(), onverwerkt, "berichtenbox.js verwerkt deze events niet")
    }

    @Test
    fun `de berichtenbox volgt, en stopt daarmee bij het wisselen van persona`() {
        assertTrue(box.contains("'/berichten/_volgen'"), "de berichtenbox opent _volgen niet")

        val wissel = box.substringAfter("function wisPersonaState()").substringBefore("\n}")

        assertTrue(wissel.contains("stopVolgen()"), "de stroom van de vorige persona blijft open na een wissel")
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
