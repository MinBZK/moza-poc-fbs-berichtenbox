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
        val magazijn = GesimuleerdMagazijn(dbId = 1, oin = "00000009000000000001", naam = "Demo-magazijn 1")
        val context = MagazijnContext().apply { kies(magazijn) }

        assertEquals(magazijn, context.magazijn)
    }

    /**
     * Eén keuze per request. Overschrijven zou betekenen dat het antwoord uit een ánder magazijn
     * komt dan waar de autorisatie op is gedaan, en dat hoort geen stille mogelijkheid te zijn.
     */
    @Test
    fun `een tweede keuze is een fout`() {
        val context = MagazijnContext().apply {
            kies(GesimuleerdMagazijn(dbId = 1, oin = "00000009000000000001", naam = "Demo-magazijn 1"))
        }

        assertThrows<IllegalStateException> {
            context.kies(GesimuleerdMagazijn(dbId = 2, oin = "00000009000000000002", naam = "Demo-magazijn 2"))
        }
    }
}
