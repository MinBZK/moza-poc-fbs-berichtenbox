package nl.rijksoverheid.moz.fbs.democonsole.legen

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource

/** De koppeling afzender-OIN → database uit `demo.magazijnen."<OIN>".database`. */
class MagazijnKoppelingTest {

    @ParameterizedTest
    @ValueSource(ints = [0, 1, 2])
    fun `elke gekoppelde OIN houdt zijn eigen database`(aantal: Int) {
        val gewenst = listOf(RVO to "magazijn-a", BELASTINGDIENST to "magazijn-b").take(aantal).toMap()

        assertEquals(gewenst, MagazijnDatabase.koppeling(gewenst, BEKEND))
    }

    @Test
    fun `een magazijn zonder database valt buiten de koppeling in plaats van de start te stoppen`() {
        val koppeling = MagazijnDatabase.koppeling(mapOf(RVO to "magazijn-a", BELASTINGDIENST to null), BEKEND)

        assertEquals(mapOf(RVO to "magazijn-a"), koppeling)
    }

    @Test
    fun `een onbekende database noemt de sleutel en de keuzes`() {
        val fout = assertThrows(IllegalStateException::class.java) {
            MagazijnDatabase.koppeling(mapOf(RVO to "magazijn-c"), BEKEND)
        }

        assertEquals("demo.magazijnen.\"$RVO\".database is 'magazijn-c'; kies uit magazijn-a, magazijn-b", fout.message)
    }

    @Test
    fun `een lege database is ook onbekend`() {
        assertThrows(IllegalStateException::class.java) { MagazijnDatabase.koppeling(mapOf(RVO to ""), BEKEND) }
    }

    @Test
    fun `twee magazijnen op één database stoppen de start`() {
        // Anders houdt de telling van het ene magazijn de opstartvulling van het andere tegen.
        val fout = assertThrows(IllegalStateException::class.java) {
            MagazijnDatabase.koppeling(mapOf(RVO to "magazijn-a", BELASTINGDIENST to "magazijn-a"), BEKEND)
        }

        assertTrue(fout.message.orEmpty().contains("$RVO, $BELASTINGDIENST"), "kreeg: ${fout.message}")
    }

    private companion object {

        const val RVO = "00000000000000100000"
        const val BELASTINGDIENST = "00000001823288444000"

        val BEKEND = setOf("magazijn-a", "magazijn-b")
    }
}
