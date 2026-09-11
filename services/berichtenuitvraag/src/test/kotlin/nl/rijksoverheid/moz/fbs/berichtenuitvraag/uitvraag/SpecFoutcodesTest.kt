package nl.rijksoverheid.moz.fbs.berichtenuitvraag.uitvraag

import nl.rijksoverheid.moz.fbs.common.exception.Foutcode
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * De foutentabel in de spec is wat een afnemer leest; [Foutcode] is wat hij krijgt. Die twee staan
 * in verschillende bestanden en kunnen zonder deze test uit elkaar lopen zonder dat iets rood
 * wordt: het spec-schema voor `Problem.type` is `format: uri`, dus ook de contracttests merken een
 * code die nergens meer bestaat niet op.
 *
 * Alleen deze richting: elke code die de spec noemt, bestaat. Andersom mag niet gelden — de spec
 * beschrijft wat een afnemer van *deze* API kan zien, en dat is met opzet minder dan de hele enum.
 */
class SpecFoutcodesTest {

    private val kenmerkPatroon = Regex("""urn:fbs:fout:[a-z-]+""")

    @Test
    fun `elke code in de API-beschrijving bestaat als Foutcode`() {
        val spec = checkNotNull(javaClass.classLoader.getResourceAsStream("openapi/berichtenuitvraag-api.yaml")) {
            "berichtenuitvraag-api.yaml staat niet op het test-classpath"
        }.bufferedReader().use { it.readText() }

        val genoemd = kenmerkPatroon.findAll(spec).map { it.value }.toSet()
        val bekend = Foutcode.entries.map { it.uri.toString() }.toSet()

        assertTrue(genoemd.isNotEmpty(), "de spec noemt geen enkel kenmerk; is de foutentabel weg?")
        assertEquals(emptySet<String>(), genoemd - bekend, "de spec noemt kenmerken die niet bestaan")
    }
}
