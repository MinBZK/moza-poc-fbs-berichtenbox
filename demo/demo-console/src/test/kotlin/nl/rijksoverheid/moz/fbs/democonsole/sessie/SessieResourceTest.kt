package nl.rijksoverheid.moz.fbs.democonsole.sessie

import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource

class SessieResourceTest {

    private val sessieService = mockk<SessieService>()
    private val resource = SessieResource(sessieService)

    @ParameterizedTest(name = "{0} gewiste keys")
    @ValueSource(ints = [0, 1, 7])
    fun `verlopen meldt het aantal gewiste keys onder gewisteKeys`(aantal: Int) {
        // Nul hoort erbij: bij de eerste ronde is er nog niets te wissen.
        every { sessieService.laatSessiesVerlopen() } returns aantal

        assertEquals(mapOf("gewisteKeys" to aantal), resource.verlopen())
    }
}
