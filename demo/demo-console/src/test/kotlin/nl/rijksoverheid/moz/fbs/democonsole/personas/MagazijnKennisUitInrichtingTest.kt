package nl.rijksoverheid.moz.fbs.democonsole.personas

import io.mockk.every
import io.mockk.mockk
import nl.rijksoverheid.moz.fbs.democonsole.DemoConfig
import org.junit.jupiter.api.Assertions.assertThrows
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
    fun `een ingericht magazijn komt er zonder fout doorheen`() {
        kennis(RVO, BELASTINGDIENST).vereisBekend(RVO)
    }

    @Test
    fun `een OIN zonder aanlever-URL wordt geweigerd, met dat OIN in de melding`() {
        val fout = assertThrows(IllegalArgumentException::class.java) {
            kennis(RVO).vereisBekend("00000000000000999999")
        }

        assertTrue(fout.message!!.contains("00000000000000999999"), fout.message)

        // Het bekende magazijn hoort er niet in te staan: zou de vergelijking omdraaien, dan meldt
        // deze klasse juist wat wél klopt en blijft de test zonder deze regel groen.
        assertTrue(!fout.message!!.contains(RVO), fout.message)
    }

    @Test
    fun `zonder enig ingericht magazijn wijst de melding naar de inrichting en niet naar het OIN`() {
        // Anders zoekt de lezer bij de persona terwijl er met die persona niets mis is.
        val fout = assertThrows(IllegalArgumentException::class.java) { kennis().vereisBekend(RVO) }

        assertTrue(fout.message!!.contains("geen magazijn ingericht"), fout.message)
    }

    private companion object {

        const val RVO = "00000000000000100000"
        const val BELASTINGDIENST = "00000001823288444000"
    }
}
