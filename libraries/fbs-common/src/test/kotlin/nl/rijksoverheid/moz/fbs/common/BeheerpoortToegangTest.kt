package nl.rijksoverheid.moz.fbs.common

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.NullSource
import org.junit.jupiter.params.provider.ValueSource

class BeheerpoortToegangTest {

    private val token = "s3cr3t-token"

    @ParameterizedTest
    @ValueSource(strings = ["dev", "test"])
    fun `zonder token is de poort in dev en test open`(profiel: String) {
        assertTrue(BeheerpoortToegang.toegestaan(null, null, profiel))
        assertTrue(BeheerpoortToegang.toegestaan("", "Bearer iets", profiel))
    }

    // Een vergeten geheim mag geen open endpoint opleveren; ook een samengesteld profiel niet.
    @ParameterizedTest
    @ValueSource(strings = ["prod", "staging", "acceptatie", "dev,demo", "DEV", ""])
    fun `zonder token is de poort buiten dev en test dicht`(profiel: String) {
        assertFalse(BeheerpoortToegang.toegestaan(null, null, profiel))
        assertFalse(BeheerpoortToegang.toegestaan("  ", "Bearer   ", profiel))
        assertFalse(BeheerpoortToegang.toegestaan(null, "Bearer $token", profiel))
    }

    @ParameterizedTest
    @ValueSource(strings = ["Bearer s3cr3t-token", "bearer s3cr3t-token", "BEARER s3cr3t-token"])
    fun `het juiste token mag door, het schema is hoofdletterongevoelig`(header: String) {
        assertTrue(BeheerpoortToegang.toegestaan(token, header, "prod"))
    }

    // Mét token geldt de eis ook lokaal: anders test een lokale opstelling met token niets.
    @ParameterizedTest
    @ValueSource(strings = ["dev", "test", "prod"])
    fun `met token geldt de eis in elk profiel`(profiel: String) {
        assertFalse(BeheerpoortToegang.toegestaan(token, null, profiel))
        assertTrue(BeheerpoortToegang.toegestaan(token, "Bearer $token", profiel))
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(
        strings = [
            "",
            "Bearer",
            "Bearer ",
            "Bearer  s3cr3t-token",
            "Bearer s3cr3t-token ",
            "Bearer s3cr3t-tokem",
            "Bearer s3cr3t-toke",
            "Bearer s3cr3t-token2",
            "Bearer S3CR3T-TOKEN",
            "Basic czNjcjN0LXRva2Vu",
            "s3cr3t-token",
            "Bearers3cr3t-token",
            "Token s3cr3t-token",
        ],
    )
    fun `elk ander token of een andere header wordt geweigerd`(header: String?) {
        assertFalse(BeheerpoortToegang.toegestaan(token, header, "prod"))
    }

    // Een geheim dat met `echo` zonder `-n` is gemaakt, eindigt op een newline; de scraper stuurt hem zonder.
    @ParameterizedTest
    @ValueSource(strings = ["s3cr3t-token\n", " s3cr3t-token ", "\ts3cr3t-token\r\n"])
    fun `witruimte om het geconfigureerde token telt niet mee`(geconfigureerd: String) {
        assertTrue(BeheerpoortToegang.toegestaan(geconfigureerd, "Bearer $token", "prod"))
        assertEquals(token, BeheerpoortToegang.ingesteld(geconfigureerd))
    }

    // Een token van alleen witruimte is geen token: dicht buiten dev/test, en dan hoort de startwaarschuwing erbij.
    @ParameterizedTest
    @NullSource
    @ValueSource(strings = ["", " ", "\n", " \t "])
    fun `een leeg token is geen token`(geconfigureerd: String?) {
        assertNull(BeheerpoortToegang.ingesteld(geconfigureerd))
        assertTrue(BeheerpoortToegang.dichtZonderToken(geconfigureerd, "prod"))
        assertFalse(BeheerpoortToegang.dichtZonderToken(geconfigureerd, "test"))
        assertFalse(BeheerpoortToegang.toegestaan(geconfigureerd, "Bearer  ", "prod"))
    }

    @Test
    fun `met een token is de poort nooit dicht zonder token`() {
        assertFalse(BeheerpoortToegang.dichtZonderToken(token, "prod"))
    }
}
