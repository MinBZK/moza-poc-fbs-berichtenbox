package nl.rijksoverheid.moz.fbs.berichtenapp

import org.junit.jupiter.api.Assertions.assertThrows
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
    @ValueSource(strings = ["", "/relatief/", "https://app-manager.example", "https://app-manager.example/?a=1", "https://app-manager.example/#x", "geen url/"])
    fun `een adres dat de app niet kan aanvullen wordt geweigerd`(adres: String) {
        assertThrows(IllegalArgumentException::class.java) { ConfigControle.controleerAdres("x", adres, ontwikkel = true) }
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

    private fun config(oin: String) = object : BerichtenAppConfig {
        override fun appManagerUrl() = "https://app-manager.example/"
        override fun appManagerOin() = oin
        override fun stelselregisterUrl() = "https://stelselregister.example/"
        override fun magazijnen(): Optional<String> = Optional.empty()
    }
}
