package nl.rijksoverheid.moz.fbs.berichtenuitvraag.aanmeld

import io.mockk.every
import io.mockk.mockk
import nl.rijksoverheid.moz.fbs.berichtenuitvraag.uitvraag.MagazijnenConfig
import nl.rijksoverheid.moz.fbs.common.identificatie.Oin
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class AfzenderMagazijnIndexTest {

    private val oinA = Oin("00000001003214345000")
    private val oinB = Oin("00000001823288444000")
    private val oinOnbekend = Oin("99999999999999999999")

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

        assertEquals("magazijn-a", index.magazijnVoor(oinA))
        assertEquals("magazijn-b", index.magazijnVoor(oinB))
    }

    @Test
    fun `onbekende afzender geeft null`() {
        val index = indexMet(mapOf("magazijn-a" to listOf("00000001003214345000")))

        assertNull(index.magazijnVoor(oinOnbekend))
    }

    @Test
    fun `bij meerdere magazijnen voor een afzender wint het eerste gesorteerde id`() {
        val index = indexMet(
            mapOf(
                "magazijn-b" to listOf("00000001003214345000"),
                "magazijn-a" to listOf("00000001003214345000"),
            ),
        )

        assertEquals("magazijn-a", index.magazijnVoor(oinA))
    }
}
