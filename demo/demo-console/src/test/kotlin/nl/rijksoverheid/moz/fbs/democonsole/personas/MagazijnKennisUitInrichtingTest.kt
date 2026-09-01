package nl.rijksoverheid.moz.fbs.democonsole.personas

import io.mockk.every
import io.mockk.mockk
import nl.rijksoverheid.moz.fbs.democonsole.DemoConfig
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * De personadienst vraagt deze module of een magazijn-OIN ingericht is. Het antwoord bepaalt of de
 * console start: wijst een persona naar een magazijn zonder aanlever-URL, dan is er niets om voor
 * hem aan te leveren en blijft zijn berichtenbox leeg zonder dat er iets faalt.
 */
class MagazijnKennisUitInrichtingTest {

    private fun kennis(vararg magazijnen: String) = MagazijnKennisUitInrichting(
        mockk<DemoConfig> {
            every { magazijnen() } returns magazijnen.associateWith { mockk<DemoConfig.Magazijn>() }
        },
    )

    @Test
    fun `een ingericht magazijn levert geen bezwaar op`() {
        assertNull(kennis(RVO, BELASTINGDIENST).bezwaarTegen(RVO))
    }

    @Test
    fun `een OIN zonder aanlever-URL levert een bezwaar op dat dat OIN noemt`() {
        val bezwaar = kennis(RVO).bezwaarTegen("00000000000000999999")!!

        assertTrue(bezwaar.contains("00000000000000999999"), bezwaar)

        // Het bekende magazijn hoort er niet in te staan: zou de vergelijking omdraaien, dan meldt
        // deze klasse juist wat wél klopt en blijft de test zonder deze regel groen.
        assertTrue(!bezwaar.contains(RVO), bezwaar)
    }

    @Test
    fun `zonder enig ingericht magazijn wijst het bezwaar naar de inrichting en niet naar het OIN`() {
        // Anders zoekt de lezer bij de persona terwijl er met die persona niets mis is.
        assertTrue(kennis().bezwaarTegen(RVO)!!.contains("geen magazijn ingericht"))
    }

    private companion object {

        const val RVO = "00000000000000100000"
        const val BELASTINGDIENST = "00000001823288444000"
    }
}
