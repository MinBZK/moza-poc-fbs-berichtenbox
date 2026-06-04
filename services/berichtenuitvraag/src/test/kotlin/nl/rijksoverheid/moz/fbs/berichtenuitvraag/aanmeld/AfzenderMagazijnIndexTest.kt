package nl.rijksoverheid.moz.fbs.berichtenuitvraag.aanmeld

import io.mockk.every
import io.mockk.mockk
import nl.rijksoverheid.moz.fbs.berichtenuitvraag.uitvraag.MagazijnenConfig
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class AfzenderMagazijnIndexTest {

    private fun indexMet(instances: Map<String, List<String>>): AfzenderMagazijnIndex {
        val config = mockk<MagazijnenConfig>()
        every { config.instances() } returns instances.mapValues { (_, afzenders) ->
            mockk<MagazijnenConfig.Instance> { every { afzenders() } returns afzenders }
        }

        return AfzenderMagazijnIndex(config)
    }

    @Test
    fun `mapt afzender-OIN naar zijn magazijn`() {
        val index = indexMet(
            mapOf(
                "magazijn-a" to listOf("00000001003214345000"),
                "magazijn-b" to listOf("00000001823288444000"),
            ),
        )

        assertEquals("magazijn-a", index.magazijnVoor("00000001003214345000"))
        assertEquals("magazijn-b", index.magazijnVoor("00000001823288444000"))
    }

    @Test
    fun `onbekende afzender geeft null`() {
        val index = indexMet(mapOf("magazijn-a" to listOf("00000001003214345000")))

        assertNull(index.magazijnVoor("99999999999999999999"))
    }

    @Test
    fun `bij meerdere magazijnen voor een afzender wint het eerste gesorteerde id`() {
        val index = indexMet(
            mapOf(
                "magazijn-b" to listOf("00000001003214345000"),
                "magazijn-a" to listOf("00000001003214345000"),
            ),
        )

        assertEquals("magazijn-a", index.magazijnVoor("00000001003214345000"))
    }
}
