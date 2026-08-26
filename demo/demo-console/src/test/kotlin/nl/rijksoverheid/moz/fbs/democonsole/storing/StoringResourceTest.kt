package nl.rijksoverheid.moz.fbs.democonsole.storing

import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import jakarta.ws.rs.BadRequestException
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource

class StoringResourceTest {

    private val storingService = mockk<StoringService>(relaxed = true)
    private val resource = StoringResource(storingService)

    @ParameterizedTest
    @ValueSource(strings = ["profiel", "redis", "notificatie", "aanmeld"])
    fun `infraUit schakelt een toegestane proxy uit`(proxy: String) {
        every { storingService.uit(proxy) } returns Unit

        resource.infraUit(proxy)

        verify { storingService.uit(proxy) }
    }

    @ParameterizedTest
    @ValueSource(strings = ["magazijn-a", "onbekend", "", "redis; drop"])
    fun `infraUit weigert een proxy buiten de allowlist`(proxy: String) {
        assertThrows(BadRequestException::class.java) { resource.infraUit(proxy) }

        verify(exactly = 0) { storingService.uit(any()) }
    }
}
