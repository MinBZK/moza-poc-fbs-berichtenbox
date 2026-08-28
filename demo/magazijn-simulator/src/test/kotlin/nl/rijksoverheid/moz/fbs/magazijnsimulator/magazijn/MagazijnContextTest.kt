package nl.rijksoverheid.moz.fbs.magazijnsimulator.magazijn

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

/**
 * Dat een niet-gevuld context-object hard faalt, is de reden dat hij geen default heeft: een
 * terugval op "het eerste magazijn" zou een request die het filter niet is gepasseerd stil laten
 * slagen bij een willekeurige organisatie.
 */
class MagazijnContextTest {

    @Test
    fun `zonder gezet magazijn faalt het uitlezen in plaats van iets terug te geven`() {
        assertThrows<IllegalStateException> { MagazijnContext().magazijn }
    }

    @Test
    fun `het gezette magazijn komt er ongewijzigd uit`() {
        val magazijn = GesimuleerdMagazijn("00000009000000000001", "Demo-magazijn 1")
        val context = MagazijnContext().apply { this.magazijn = magazijn }

        assertEquals(magazijn, context.magazijn)
    }
}
