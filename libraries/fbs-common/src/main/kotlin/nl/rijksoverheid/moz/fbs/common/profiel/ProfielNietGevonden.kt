package nl.rijksoverheid.moz.fbs.common.profiel

import com.fasterxml.jackson.core.JacksonException
import com.fasterxml.jackson.core.StreamReadConstraints
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper

/**
 * Duiding van een 404 van de Profiel-service. De storings-variant draagt een korte
 * omschrijving die veilig is om te loggen.
 */
sealed interface Profiel404Duiding {

    /** Het herkenbare "partij niet gevonden": deze ontvanger heeft nog geen voorkeuren. */
    data object PartijZonderProfiel : Profiel404Duiding

    /**
     * Alles wat niet als opt-out te lezen is. [omschrijving] benoemt wát er niet klopte —
     * begrensd en gesaniteerd, zodat de aanroeper hem zonder verdere bewerking mag loggen.
     */
    data class Storing(val omschrijving: String) : Profiel404Duiding
}

/**
 * Scheidt het ene 404-antwoord van de Profiel-service dat géén storing is — "deze partij
 * heeft nog geen profiel" — van alle andere. Een 404 kan ook van het framework of van een
 * tussenliggende voorziening komen, en die mag niet als "geen voorkeuren" doorgaan: dan
 * levert een storing een misleidend lege berichtenbox op.
 *
 * De aanknoping is de `title` uit het `application/problem+json`-lichaam. Niet `type`: de
 * Profiel-service laat dat op `about:blank` staan, waarmee het geen enkel 404-geval van een
 * ander onderscheidt.
 *
 * Alles wat niet ondubbelzinnig als het partij-antwoord te lezen is, telt als storing.
 * Liever een zichtbare fout dan een stil lege berichtenbox.
 */
object ProfielNietGevonden {

    /**
     * De `title` waarmee de Profiel-service een onbekende partij aankondigt, zoals
     * `ProfielController.getPartij` in `MinBZK/moza-profiel-service` hem opbouwt via
     * `Problems.notFound(...)`. Wijzigt die bewoording upstream, dan valt élke ontvanger
     * zonder voorkeuren in de storings-tak — zichtbaar, niet stil.
     */
    private const val TITEL_PARTIJ_NIET_GEVONDEN = "Partij niet gevonden"

    /**
     * Bovengrens op het lichaam dat we parsen. Ruim boven een problem+json met een handvol
     * velden, en het houdt een defecte upstream die een foutpagina van megabytes teruggeeft
     * van de heap. Te groot telt als storing — het is per definitie niet het antwoord dat we
     * zoeken.
     */
    const val MAX_LICHAAM_TEKENS: Int = 64 * 1024

    // Eén instantie: dit pad wordt per 404 geraakt en een ObjectMapper is duur om te bouwen.
    // Alleen readTree wordt gebruikt; de leesgrenzen zijn een tweede vangnet naast
    // MAX_LICHAAM_TEKENS, tegen diep geneste of extreem lange invoer.
    private val mapper = ObjectMapper().apply {
        factory.setStreamReadConstraints(
            StreamReadConstraints.builder()
                .maxNestingDepth(MAX_NESTING)
                .maxStringLength(MAX_LICHAAM_TEKENS)
                .build(),
        )
    }

    private const val MAX_NESTING = 20

    /** Maximale lengte van een titel in de log; ruim boven elke upstream-titel. */
    private const val MAX_TITEL_IN_LOG = 80

    // C0-control-chars + DEL + Unicode line/paragraph separators (U+2028/U+2029) → '?'.
    // Neutraliseert CRLF-log-injectie én de separators die sommige log-pipelines ook als
    // regeleinde lezen, bij het loggen van een titel die van buiten komt.
    private val CONTROL_CHARS = Regex("[\\u0000-\\u001f\\u007f\\u2028\\u2029]")

    /**
     * Duidt het lichaam van een 404-respons. [problemBody] is `null` wanneer de respons geen
     * lichaam had; gebruik [Profiel404Duiding.Storing] met een eigen omschrijving wanneer het
     * lichaam niet uitgelezen kón worden — dat onderscheid kent alleen de aanroeper.
     */
    fun duid(problemBody: String?): Profiel404Duiding {
        if (problemBody == null) return Profiel404Duiding.Storing("zonder lichaam")

        if (problemBody.isBlank()) return Profiel404Duiding.Storing("leeg lichaam")

        if (problemBody.length > MAX_LICHAAM_TEKENS) {
            return Profiel404Duiding.Storing("lichaam te groot (${problemBody.length} tekens)")
        }

        val titel = leesTitel(problemBody)
            ?: return Profiel404Duiding.Storing("geen problem+json met een title")

        if (titel.trim().equals(TITEL_PARTIJ_NIET_GEVONDEN, ignoreCase = true)) {
            return Profiel404Duiding.PartijZonderProfiel
        }

        return Profiel404Duiding.Storing("title='${veiligeTitel(titel)}'")
    }

    /**
     * De `title` uit een problem+json-lichaam, of `null` als het lichaam er geen draagt.
     * Een niet te parsen of niet-object lichaam is precies wat een routing- of infra-404
     * oplevert en levert daarom `null` in plaats van een uitzondering.
     *
     * De `status`-controle is een extra bevestiging: een antwoord dat zichzélf niet als 404
     * aankondigt, is niet het antwoord waarover deze klasse gaat. Ontbreekt `status`, dan
     * telt alleen de titel — het veld is optioneel in RFC 9457.
     */
    private fun leesTitel(problemBody: String): String? {
        val tree: JsonNode = try {
            mapper.readTree(problemBody)
        } catch (ignored: JacksonException) {
            return null
        }

        if (!tree.isObject) return null

        val status = tree.get("status")

        if (status != null && status.isNumber && status.asInt() != 404) return null

        val titel = tree.get("title") ?: return null

        return if (titel.isTextual) titel.asText() else null
    }

    /** Kapt een van buiten gekomen titel af en neutraliseert control-chars voor de log. */
    private fun veiligeTitel(titel: String): String =
        titel.take(MAX_TITEL_IN_LOG).replace(CONTROL_CHARS, "?")
}
