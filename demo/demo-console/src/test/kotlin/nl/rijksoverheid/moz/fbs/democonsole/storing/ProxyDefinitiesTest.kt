package nl.rijksoverheid.moz.fbs.democonsole.storing

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ProxyDefinitiesTest {

    private fun definities(vararg paren: Pair<String, TestInstantie>) = ProxyDefinities(testConfig(*paren))

    private val compleet = TestInstantie(
        url = "http://een:8474",
        listen = "0.0.0.0:18089",
        upstream = "profiel-service:8080",
    )

    @Test
    fun `een lege configuratie levert niets om aan te maken`() {
        val leeg = definities()

        assertTrue(leeg.alle().isEmpty())
        assertTrue(leeg.onvolledig().isEmpty())
    }

    @Test
    fun `een volledige proxy levert zijn listen en upstream`() {
        val een = definities("profiel" to compleet)

        assertEquals(listOf(ProxyDefinitie("profiel", "0.0.0.0:18089", "profiel-service:8080")), een.alle())
        assertTrue(een.onvolledig().isEmpty())
    }

    @Test
    fun `meerdere proxies leveren elk hun eigen definitie`() {
        // Eén definitie zou "geeft de enige terug" niet onderscheiden van "koppelt per sleutel".
        val meerdere = definities(
            "profiel" to compleet,
            "redis" to TestInstantie(url = "http://twee:8474", listen = "0.0.0.0:16379", upstream = "redis:6379"),
        )

        assertEquals(
            setOf(
                ProxyDefinitie("profiel", "0.0.0.0:18089", "profiel-service:8080"),
                ProxyDefinitie("redis", "0.0.0.0:16379", "redis:6379"),
            ),
            meerdere.alle().toSet(),
        )
    }

    @Test
    fun `een proxy zonder url telt niet mee en heet ook niet onvolledig`() {
        // De ZAD-vorm: een omgeving laat een stroom weg door alleen de url-env-var leeg te laten.
        // Dat is een keuze, geen fout, dus er hoort geen waarschuwing over.
        val uitgezet = definities(
            "profiel" to compleet,
            "magazijn-a" to TestInstantie(url = "", listen = "0.0.0.0:18090", upstream = "berichtenmagazijn-a:8090"),
        )

        assertEquals(listOf("profiel"), uitgezet.alle().map { it.naam })
        assertTrue(uitgezet.onvolledig().isEmpty())
    }

    @Test
    fun `een proxy met url maar zonder listen is onvolledig en wordt niet aangemaakt`() {
        val half = definities("profiel" to TestInstantie(url = "http://een:8474", upstream = "profiel-service:8080"))

        assertTrue(half.alle().isEmpty())
        assertEquals(listOf("profiel"), half.onvolledig())
    }

    @Test
    fun `een proxy met url maar zonder upstream is onvolledig en wordt niet aangemaakt`() {
        val half = definities("profiel" to TestInstantie(url = "http://een:8474", listen = "0.0.0.0:18089"))

        assertTrue(half.alle().isEmpty())
        assertEquals(listOf("profiel"), half.onvolledig())
    }

    @Test
    fun `blanco listen of upstream telt als ontbrekend, niet als adres van spaties`() {
        val blanco = definities(
            "profiel" to TestInstantie(url = "http://een:8474", listen = "   ", upstream = "profiel-service:8080"),
            "redis" to TestInstantie(url = "http://een:8474", listen = "0.0.0.0:16379", upstream = " "),
        )

        assertTrue(blanco.alle().isEmpty())
        assertEquals(listOf("profiel", "redis"), blanco.onvolledig())
    }

    @Test
    fun `spaties rond een adres verdwijnen, zodat Toxiproxy geen onbindbare listen krijgt`() {
        val rommelig = definities(
            "profiel" to TestInstantie(url = "http://een:8474", listen = " 0.0.0.0:18089 ", upstream = " stub:8080 "),
        )

        assertEquals(listOf(ProxyDefinitie("profiel", "0.0.0.0:18089", "stub:8080")), rommelig.alle())
    }
}
