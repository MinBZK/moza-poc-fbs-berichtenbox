package nl.rijksoverheid.moz.fbs.beheerconsole.stelsel

import io.quarkus.runtime.StartupEvent
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertDoesNotThrow
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.util.Optional

class StelselregisterAdresTest {

    private fun controle(profiel: String, url: String = "https://stelselregister.example", root: String? = null, zonderRoot: Boolean = false) =
        StelselregisterAdres(
            profiel,
            url,
            object : StelseldocumentConfig {
                override fun root(): Optional<String> = Optional.ofNullable(root)
                override fun uitgever(): String = Testketen.OIN
                override fun omgeving(): String = Testketen.OMGEVING
                override fun unsafeZonderRoot(): Boolean = zonderRoot
            },
        )

    @ParameterizedTest
    @ValueSource(strings = ["prod", "acceptatie"])
    fun `buiten dev en test weigert de start zonder root`(profiel: String) {
        assertThrows<IllegalArgumentException> { controle(profiel).controleer(StartupEvent()) }
    }

    @Test
    fun `zonder root mag alleen bewust`() {
        assertDoesNotThrow { controle("prod", zonderRoot = true).controleer(StartupEvent()) }
    }

    @Test
    fun `met root start de console`() {
        assertDoesNotThrow { controle("prod", root = "pem").controleer(StartupEvent()) }
    }

    @ParameterizedTest
    @ValueSource(strings = ["dev", "test"])
    fun `in dev en test mag de root ontbreken en http`(profiel: String) {
        assertDoesNotThrow { controle(profiel, url = "http://localhost:8094").controleer(StartupEvent()) }
    }

    @Test
    fun `buiten dev en test geen http naar het stelselregister`() {
        assertThrows<IllegalArgumentException> { controle("prod", url = "http://stelselregister", root = "pem").controleer(StartupEvent()) }
    }
}
