package nl.rijksoverheid.moz.fbs.democonsole.storing

import jakarta.ws.rs.BadRequestException
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ToxiproxyAdressenTest {

    private fun adressen(vararg paren: Pair<String, String>) =
        ToxiproxyAdressen(object : ToxiproxyConfig {
            override fun toxiproxy() = paren.toMap().mapValues { (_, adres) ->
                object : ToxiproxyConfig.Instantie {
                    override fun url() = adres
                }
            }
        })

    @Test
    fun `een lege configuratie kent geen namen en geen instanties`() {
        val leeg = adressen()

        assertTrue(leeg.namen().isEmpty())
        assertTrue(leeg.unieke().isEmpty())
    }

    @Test
    fun `een configuratie van een proxy levert die proxy`() {
        val een = adressen("profiel" to "http://een:8474")

        assertEquals(setOf("profiel"), een.namen())
        assertEquals(listOf("http://een:8474"), een.unieke())
    }

    @Test
    fun `proxies op hetzelfde adres tellen als een instantie`() {
        // De lokale stack: zes proxies op één Toxiproxy. Zonder ontdubbeling zou reset()
        // dezelfde instantie zes keer langsgaan.
        val gedeeld = adressen("profiel" to "http://een:8474", "redis" to "http://een:8474")

        assertEquals(listOf("http://een:8474"), gedeeld.unieke())
        assertEquals(gedeeld.adres("profiel"), gedeeld.adres("redis"))
    }

    @Test
    fun `proxies op verschillende adressen tellen elk als eigen instantie`() {
        // De ZAD-stack: elke stroom een eigen Toxiproxy vóór zijn upstream.
        val gesplitst = adressen("profiel" to "http://een:8474", "redis" to "http://twee:8474")

        assertEquals(2, gesplitst.unieke().size)
    }

    @Test
    fun `een onbekende proxy wordt geweigerd met de geconfigureerde namen erbij`() {
        val een = adressen("profiel" to "http://een:8474")

        val fout = assertThrows(BadRequestException::class.java) { een.adres("magazijn-a") }

        assertTrue(fout.message!!.contains("magazijn-a"), "melding moet de gevraagde naam noemen")
        assertTrue(fout.message!!.contains("profiel"), "melding moet de beschikbare namen noemen")
    }

    @Test
    fun `een proxy met een lege url telt niet mee, de rest wel`() {
        // De ZAD-vorm: TOXIPROXY_MAGAZIJN_A_URL leeg gezet, de andere proxies gewoon geconfigureerd.
        val gemengd = adressen("profiel" to "http://een:8474", "magazijn-a" to "")

        assertEquals(setOf("profiel"), gemengd.namen())
        assertEquals(listOf("http://een:8474"), gemengd.unieke())
    }

    @Test
    fun `zijn alle url's leeg of blanco, dan is het register net zo leeg als zonder configuratie`() {
        val geenEen = adressen("magazijn-a" to "", "magazijn-b" to "   ")

        assertTrue(geenEen.namen().isEmpty())
        assertTrue(geenEen.unieke().isEmpty())
    }

    @Test
    fun `een proxy met een lege url wordt geweigerd als was hij nooit geconfigureerd`() {
        val gemengd = adressen("profiel" to "http://een:8474", "magazijn-a" to "")

        val fout = assertThrows(BadRequestException::class.java) { gemengd.adres("magazijn-a") }

        assertTrue(fout.message!!.contains("magazijn-a"), "melding moet de gevraagde naam noemen")
        assertTrue(fout.message!!.contains("profiel"), "melding moet alleen de geconfigureerde namen noemen")
    }
}
