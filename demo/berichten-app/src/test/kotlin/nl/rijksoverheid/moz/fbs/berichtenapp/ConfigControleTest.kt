package nl.rijksoverheid.moz.fbs.berichtenapp

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.util.Optional

class ConfigControleTest {

    @Test
    fun `een absoluut adres dat op een slash eindigt is goed, en http alleen in ontwikkelmodus`() {
        ConfigControle.controleerAdres("x", "https://app-manager.example/", ontwikkel = false)
        ConfigControle.controleerAdres("x", "https://app-manager.example/pad/", ontwikkel = false)
        ConfigControle.controleerAdres("x", "http://localhost:8093/", ontwikkel = true)

        assertThrows(IllegalArgumentException::class.java) {
            ConfigControle.controleerAdres("x", "http://app-manager.example/", ontwikkel = false)
        }
    }

    @ParameterizedTest
    @ValueSource(
        strings = [
            "", "/relatief/", "https://app-manager.example", "https://app-manager.example/?a=1", "https://app-manager.example/#x", "geen url/",
            "ftp://app-manager.example/", "https://iemand@app-manager.example/",
            // Tekens die in de CSP-header een eigen richtlijn of bron zouden maken.
            "https://app-manager.example/a;script-src/", "https://app-manager.example/a,b/", "https://app-manager.example/'unsafe-inline'/",
            "https://app-manager.example/a%20b/",
        ],
    )
    fun `een adres dat de app niet kan aanvullen wordt geweigerd`(adres: String) {
        assertThrows(IllegalArgumentException::class.java) { ConfigControle.controleerAdres("x", adres, ontwikkel = true) }
    }

    @Test
    fun `magazijn-origins zijn origins zonder pad, wildcard of puntkomma, en https buiten ontwikkelmodus`() {
        ConfigControle.controleerOrigins("x", "https://a.example  https://b.example:8443", ontwikkel = false)
        ConfigControle.controleerOrigins("x", "http://localhost:8092", ontwikkel = true)

        listOf(
            "https://a.example/pad", "*", "https://*.example", "https://a.example; script-src *", "https:", "http://a.example",
            "https://a.example\nhttps://b.example", "https://a.example\thttps://b.example",
        ).forEach { waarde ->
            assertThrows(IllegalArgumentException::class.java) { ConfigControle.controleerOrigins("x", waarde, ontwikkel = false) }
        }
    }

    @ParameterizedTest
    @ValueSource(strings = ["", "   "])
    fun `zonder magazijn-origins start de app alleen in ontwikkelmodus`(waarde: String) {
        ConfigControle.controleerOrigins("x", waarde, ontwikkel = true)

        assertThrows(IllegalArgumentException::class.java) { ConfigControle.controleerOrigins("x", waarde, ontwikkel = false) }
    }

    @Test
    fun `een wachtwoord in een afgewezen origin komt niet in de melding`() {
        val fout = assertThrows(IllegalArgumentException::class.java) {
            ConfigControle.controleerOrigins("x", "https://a.example https://beheer:geheim@b.example", ontwikkel = false)
        }

        assertFalse(fout.message!!.contains("geheim"), fout.message)
        assertTrue(fout.message!!.contains("origin 2"), fout.message)
    }

    @ParameterizedTest
    @ValueSource(
        strings = [
            "https://iemand:geheim@app-manager.example/", "https://iemand:ge/heim@app-manager.example/",
            "https://a@b:geheim@app-manager.example/", "https://iemand:geheim@app-manager.example",
        ],
    )
    fun `een wachtwoord in een afgewezen adres komt niet in de melding`(adres: String) {
        val fout = assertThrows(IllegalArgumentException::class.java) { ConfigControle.controleerAdres("x", adres, ontwikkel = false) }

        assertFalse(fout.message!!.contains("heim"), fout.message)
    }

    @ParameterizedTest
    @ValueSource(strings = ["", "0000000000000000110", "000000000000000011000", "0000000000000000110x"])
    fun `een OIN van iets anders dan 20 cijfers blokkeert de start`(oin: String) {
        assertThrows(IllegalArgumentException::class.java) { ConfigControle(config(oin)) }
    }

    @Test
    fun `de demo-OIN is goed`() {
        ConfigControle(config(BerichtenAppConfig.DEMO_APP_MANAGER_OIN))
    }

    // Buiten Quarkus is de launch mode NORMAL: de start toetst hier dus als in productie.
    @Test
    fun `de start toetst elk adres en de magazijn-origins`() {
        ConfigControle(config(magazijnen = Optional.of("https://a.example https://b.example")))

        listOf(
            config(appManager = "http://app-manager.example/"),
            config(stelselregister = "http://stelselregister.example/"),
            config(stelselregister = "https://stelselregister.example/a;script-src/"),
            config(magazijnen = Optional.empty()),
            config(magazijnen = Optional.of("https://a.example; script-src *")),
        ).forEach { onjuist ->
            assertThrows(IllegalArgumentException::class.java) { ConfigControle(onjuist) }
        }
    }

    private fun config(
        oin: String = BerichtenAppConfig.DEMO_APP_MANAGER_OIN,
        appManager: String = "https://app-manager.example/",
        stelselregister: String = "https://stelselregister.example/",
        magazijnen: Optional<String> = Optional.of("https://a.example"),
    ) = object : BerichtenAppConfig {
        override fun appManagerUrl() = appManager
        override fun appManagerOin() = oin
        override fun stelselregisterUrl() = stelselregister
        override fun magazijnen(): Optional<String> = magazijnen
    }
}
