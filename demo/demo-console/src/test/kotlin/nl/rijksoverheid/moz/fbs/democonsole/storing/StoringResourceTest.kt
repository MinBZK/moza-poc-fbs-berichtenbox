package nl.rijksoverheid.moz.fbs.democonsole.storing

import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import jakarta.ws.rs.BadRequestException
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource

class StoringResourceTest {

    private val storingService = mockk<StoringService>(relaxed = true)
    private val resource = StoringResource(storingService)

    @ParameterizedTest
    @ValueSource(strings = ["profiel", "redis", "notificatie", "aanmeld", "magazijn-a"])
    fun `infraUit geeft de naam onveranderd door aan de service`(proxy: String) {
        every { storingService.uit(proxy) } returns Unit

        resource.infraUit(proxy)

        verify { storingService.uit(proxy) }
    }

    @Test
    fun `infraUit laat een weigering van het register door`() {
        // Het register is de allowlist; de resource mag die beslissing niet dubbel nemen, want
        // twee lijsten lopen uiteen zodra de configuratie verandert.
        every { storingService.uit("onbekend") } throws BadRequestException("onbekende proxy")

        assertThrows(BadRequestException::class.java) { resource.infraUit("onbekend") }
    }
}
