package nl.rijksoverheid.moz.fbs.stelselregister.stelseldocument

import io.quarkus.runtime.LaunchMode
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import org.junit.jupiter.params.provider.ValueSource
import java.time.Instant
import java.util.Optional

class SleutelbronTest {

    private val nu = Instant.now()
    private val uitgever = Wegwerpketen.STANDAARD_OIN

    private fun keystore(pad: String?, wachtwoord: String? = null, alias: String = Wegwerpketen.ALIAS) =
        object : StelseldocumentConfig.Keystore {
            override fun pad() = Optional.ofNullable(pad)
            override fun wachtwoord() = Optional.ofNullable(wachtwoord)
            override fun alias() = alias
        }

    @Test
    fun `met een pad komt de sleutel uit de keystore, in elke modus`() {
        val config = keystore(Testketens.geldig.pad.toString(), String(Testketens.geldig.wachtwoord))

        LaunchMode.entries.forEach { modus ->
            val sleutel = Sleutelbron.laad(config, modus, nu, uitgever)

            assertEquals(Sleutelherkomst.KEYSTORE, sleutel.herkomst)
            assertEquals(Testketens.geldig.certificaat, sleutel.certificaat)
        }
    }

    @ParameterizedTest
    @EnumSource(LaunchMode::class, names = ["DEVELOPMENT", "TEST"])
    fun `zonder pad levert ontwikkel- en testmodus een wegwerpketen`(modus: LaunchMode) {
        val sleutel = Sleutelbron.laad(keystore(pad = null), modus, nu, "00000000000000004321")

        assertEquals(Sleutelherkomst.WEGWERP, sleutel.herkomst)
        assertEquals("00000000000000004321", sleutel.uitgeverOin)
        assertEquals(64, sleutel.onderteken("x".toByteArray()).size)
    }

    // De profielnaam is geen invoer van deze beslissing: een productie-build die met het
    // test-profiel gestart wordt, komt hier net zo goed in NORMAL binnen.
    @ParameterizedTest
    @ValueSource(strings = ["", "   "])
    fun `zonder pad start een productie-build niet`(pad: String) {
        val leeg = assertThrows(OngeldigeOndertekensleutelException::class.java) {
            Sleutelbron.laad(keystore(pad = pad), LaunchMode.NORMAL, nu, uitgever)
        }
        val afwezig = assertThrows(OngeldigeOndertekensleutelException::class.java) {
            Sleutelbron.laad(keystore(pad = null), LaunchMode.NORMAL, nu, uitgever)
        }

        assertTrue(leeg.message.orEmpty().contains("keystore.pad ontbreekt"))
        assertTrue(afwezig.message.orEmpty().contains("keystore.pad ontbreekt"))
    }

    @Test
    fun `een pad zonder wachtwoord wordt geweigerd`() {
        val fout = assertThrows(OngeldigeOndertekensleutelException::class.java) {
            Sleutelbron.laad(keystore(Testketens.geldig.pad.toString()), LaunchMode.NORMAL, nu, uitgever)
        }

        assertTrue(fout.message.orEmpty().contains("wachtwoord ontbreekt"))
    }

    @Test
    fun `een onbruikbare keystore valt niet terug op een wegwerpketen`() {
        val config = keystore(Testketens.rsa.pad.toString(), String(Testketens.rsa.wachtwoord))

        assertThrows(OngeldigeOndertekensleutelException::class.java) { Sleutelbron.laad(config, LaunchMode.TEST, nu, uitgever) }
    }
}
