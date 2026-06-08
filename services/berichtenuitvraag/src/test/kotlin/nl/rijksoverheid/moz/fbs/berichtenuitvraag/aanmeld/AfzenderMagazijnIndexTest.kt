package nl.rijksoverheid.moz.fbs.berichtenuitvraag.aanmeld

import nl.rijksoverheid.moz.fbs.common.identificatie.Oin
import nl.rijksoverheid.moz.fbs.magazijnregister.Magazijninschrijving
import nl.rijksoverheid.moz.fbs.magazijnregister.Magazijnregister
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import java.net.URI

class AfzenderMagazijnIndexTest {

    private val oinA = Oin("00000001003214345000")
    private val oinB = Oin("00000001823288444000")
    private val oinOnbekend = Oin("99999999999999999999")

    private fun indexMet(ingeschreven: List<Oin>): AfzenderMagazijnIndex {
        val entries = ingeschreven.map { oin ->
            Magazijninschrijving(oin, URI.create("http://localhost:8081"), naam = null)
        }

        val register = object : Magazijnregister {
            override fun alle(): Collection<Magazijninschrijving> = entries
            override fun voorOin(oin: Oin): Magazijninschrijving? = entries.firstOrNull { it.oin == oin }
        }

        return AfzenderMagazijnIndex(register)
    }

    @Test
    fun `mapt elke afzender-OIN naar zijn eigen magazijn (= de OIN zelf)`() {
        // Twee ingeschreven magazijnen: borgt dat de lookup per afzender
        // discrimineert i.p.v. de enige/eerste inschrijving terug te geven.
        val index = indexMet(listOf(oinA, oinB))

        assertEquals(oinA.waarde, index.magazijnVoor(oinA))
        assertEquals(oinB.waarde, index.magazijnVoor(oinB))
    }

    @Test
    fun `onbekende afzender geeft null`() {
        val index = indexMet(listOf(oinA, oinB))

        assertNull(index.magazijnVoor(oinOnbekend))
    }
}
