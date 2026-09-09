package nl.rijksoverheid.moz.fbs.common.profiel

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper

/**
 * Herkent het ene 404-antwoord van de Profiel-service dat géén storing is: "deze partij heeft
 * nog geen profiel". De dienst gebruikt 404 óók voor een verkeerd pad of een tussenliggende
 * voorziening die de aanvraag niet kwijt kan, en die twee mogen niet als "geen voorkeuren"
 * doorgaan — dan levert een storing een misleidend lege berichtenbox op.
 *
 * De aanknoping is de `title` uit het `application/problem+json`-lichaam. Niet `type`: de
 * Profiel-service laat dat op `about:blank` staan, waarmee het geen enkel 404-geval van een
 * ander onderscheidt. Zodra `type` daar een eigen waarde krijgt is dat het stabielere veld om
 * op te matchen.
 *
 * Alles wat niet ondubbelzinnig als dit antwoord te lezen is — geen lichaam, geen JSON, een
 * ander `title` — telt als storing. Liever een zichtbare fout dan een stil lege berichtenbox.
 */
object ProfielNietGevonden {

    /** De `title` waarmee de Profiel-service een onbekende partij aankondigt. */
    private const val TITEL_PARTIJ_NIET_GEVONDEN = "Partij niet gevonden"

    // Eén instantie: dit pad wordt per 404 geraakt en een ObjectMapper is duur om te bouwen.
    // Alleen readTree wordt gebruikt, dus geen configuratie nodig; ObjectMapper is thread-safe
    // zolang hij niet meer geconfigureerd wordt.
    private val mapper = ObjectMapper()

    /**
     * Of [problemBody] het "partij niet gevonden"-antwoord is. Geeft `false` bij twijfel; de
     * aanroeper behandelt dat als storing.
     */
    fun isPartijZonderProfiel(problemBody: String?): Boolean {
        if (problemBody.isNullOrBlank()) return false

        val titel = leesTitel(problemBody) ?: return false

        return titel.trim().equals(TITEL_PARTIJ_NIET_GEVONDEN, ignoreCase = true)
    }

    /**
     * De `title` uit een problem+json-lichaam, of `null` als het lichaam er geen draagt.
     * Een niet te parsen of niet-object lichaam is precies wat een routing- of infra-404
     * oplevert en levert daarom `null` in plaats van een uitzondering.
     */
    private fun leesTitel(problemBody: String): String? {
        val boom: JsonNode = try {
            mapper.readTree(problemBody)
        } catch (ignored: com.fasterxml.jackson.core.JacksonException) {
            return null
        }

        if (!boom.isObject) return null

        val titel = boom.get("title") ?: return null

        return if (titel.isTextual) titel.asText() else null
    }
}
