package nl.rijksoverheid.moz.fbs.stelselregister

import nl.rijksoverheid.moz.fbs.common.exception.Foutcode
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * De foutentabel in de spec is wat een afnemer leest; [Foutcode] is wat hij krijgt. Zonder deze
 * test lopen die twee uit elkaar zonder dat iets rood wordt: het schema voor `Problem.type` is
 * `format: uri`, dus ook de contracttests merken een code die nergens meer bestaat niet op.
 */
class SpecFoutcodesTest {

    private val kenmerkPatroon = Regex("""urn:fbs:fout:[a-z-]+""")

    @Test
    fun `elke code in de API-beschrijving bestaat als Foutcode`() {
        val spec = checkNotNull(javaClass.classLoader.getResourceAsStream("openapi/stelselregister-api.yaml")) {
            "stelselregister-api.yaml staat niet op het test-classpath"
        }.bufferedReader().use { it.readText() }

        val genoemd = kenmerkPatroon.findAll(spec).map { it.value }.toSet()
        val bekend = Foutcode.entries.map { it.uri.toString() }.toSet()

        assertTrue(genoemd.isNotEmpty(), "de spec noemt geen enkel kenmerk; is de foutentabel weg?")
        assertEquals(emptySet<String>(), genoemd - bekend, "de spec noemt kenmerken die niet bestaan")
    }
}
