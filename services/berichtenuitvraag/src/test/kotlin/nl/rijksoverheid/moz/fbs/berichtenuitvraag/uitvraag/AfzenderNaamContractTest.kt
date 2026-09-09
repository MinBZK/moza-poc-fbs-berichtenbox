package nl.rijksoverheid.moz.fbs.berichtenuitvraag.uitvraag

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.io.File

/**
 * Bewaakt de belofte waarop afnemers hun code bouwen: elk bericht draagt een leesbare
 * afzendernaam, dus niemand hoeft een terugval op het `magazijnId` te schrijven.
 *
 * De contracttest met `swagger-request-validator` kan dit niet vangen: die valideert responses
 * tégen de spec, en een aanwezig veld voldoet net zo goed aan een optioneel schema. Wie
 * `afzenderNaam` uit de `required`-lijst haalt of `minLength` weghaalt, versoepelt het contract
 * zonder dat er iets rood wordt — daarom leest deze test de spec zelf.
 */
class AfzenderNaamContractTest {

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = ["BerichtSamenvatting", "Bericht"])
    fun `afzenderNaam is een verplicht veld in de uitvraag-spec`(schema: String) {
        val verplicht = verplichteVelden(schemaBlok(schema))

        assertTrue(
            "afzenderNaam" in verplicht,
            "$schema mist afzenderNaam in required; afnemers zouden weer een terugval moeten bouwen. Was: $verplicht",
        )
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = ["BerichtSamenvatting", "Bericht"])
    fun `afzenderNaam mag niet leeg zijn in de uitvraag-spec`(schema: String) {
        val blok = schemaBlok(schema)
        val naVeld = blok.substringAfter("afzenderNaam:")
        val eind = Regex("\n {8}\\S").find(naVeld)?.range?.first ?: naVeld.length
        val veld = naVeld.substring(0, eind)

        assertTrue(
            "minLength: 1" in veld,
            "$schema.afzenderNaam mist minLength: 1; een lege naam is geen naam. Was: $veld",
        )
    }

    /** Het YAML-blok van één schema onder `components.schemas`, tot aan het volgende schema. */
    private fun schemaBlok(naam: String): String {
        val spec = File(SPEC).readText()
        val start = spec.indexOf("\n    $naam:\n")

        assertTrue(start >= 0, "schema '$naam' niet gevonden in $SPEC")

        val rest = spec.substring(start + 1)
        val eind = Regex("\n    [A-Z][A-Za-z]*:\n").find(rest, startIndex = 1)?.range?.first ?: rest.length

        return rest.substring(0, eind)
    }

    private fun verplichteVelden(blok: String): List<String> =
        blok.substringAfter("required: [").substringBefore("]")
            .split(",")
            .map { it.trim() }

    private companion object {

        private const val SPEC = "src/main/resources/openapi/berichtenuitvraag-api.yaml"
    }
}
