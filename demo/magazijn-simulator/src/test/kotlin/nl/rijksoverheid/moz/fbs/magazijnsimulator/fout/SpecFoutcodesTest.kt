package nl.rijksoverheid.moz.fbs.magazijnsimulator.fout

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * [Foutcode] is een kopie van de codes uit `fbs-common`, en een kopie kan verschuiven. Deze test
 * pint hem vast op de gedeelde `berichtenmagazijn-api.yaml` — hetzelfde bestand waaruit de
 * simulator zijn interfaces genereert — en wel in beide richtingen:
 *
 * - elke code die de simulator kan produceren staat in de foutentabel, zodat een afnemer hem daar
 *   terugvindt;
 * - elke code die die tabel noemt kent de simulator, zodat hij niet stilzwijgend achterloopt op
 *   een code die het echte magazijn er wél bij kreeg.
 *
 * Daarmee is de keten rond: een hernoeming die alleen hier gebeurt valt hier om, een hernoeming
 * die ook de spec raakt valt om in de spec-test van het magazijn, en een hernoeming die overal
 * doorgevoerd wordt valt om op de gouden test in `fbs-common`.
 */
class SpecFoutcodesTest {

    private val kenmerkPatroon = Regex("""urn:fbs:fout:[a-z-]+""")

    private fun kenmerkenUitSpec(): Set<String> {
        val spec = checkNotNull(javaClass.classLoader.getResourceAsStream("openapi/berichtenmagazijn-api.yaml")) {
            "berichtenmagazijn-api.yaml staat niet op het test-classpath"
        }.bufferedReader().use { it.readText() }

        return kenmerkPatroon.findAll(spec).map { it.value }.toSet()
    }

    @Test
    fun `elke code van de simulator staat in de gedeelde foutentabel`() {
        val eigen = Foutcode.entries.map { it.uri.toString() }.toSet()

        assertEquals(emptySet<String>(), eigen - kenmerkenUitSpec(), "de simulator kan kenmerken geven die de spec niet noemt")
    }

    @Test
    fun `elke code uit de gedeelde foutentabel kent de simulator`() {
        val genoemd = kenmerkenUitSpec()
        val eigen = Foutcode.entries.map { it.uri.toString() }.toSet()

        assertTrue(genoemd.isNotEmpty(), "de spec noemt geen enkel kenmerk; is de foutentabel weg?")
        assertEquals(emptySet<String>(), genoemd - eigen, "de simulator loopt achter op de foutentabel")
    }
}
