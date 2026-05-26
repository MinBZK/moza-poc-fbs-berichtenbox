package nl.rijksoverheid.moz.fbs.berichtenuitvraag.uitvraag

import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import nl.rijksoverheid.moz.fbs.berichtenuitvraag.api.model.BerichtenLijst
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Test

class BerichtenlijstServiceTest {

    private val sessiecache: SessiecacheClient = mockk()
    private val service = BerichtenlijstService(sessiecache)

    @Test
    fun `lijst delegeert met juiste headers en query`() {
        val expected = BerichtenLijst()
        every { sessiecache.lijst("BSN:1", "archief", 0, 20) } returns expected

        val actual = service.lijst("BSN:1", "archief", 0, 20)

        assertSame(expected, actual)
        verify(exactly = 1) { sessiecache.lijst("BSN:1", "archief", 0, 20) }
    }

    @Test
    fun `lijst geeft null-parameters door zonder default-invulling`() {
        val expected = BerichtenLijst()
        every { sessiecache.lijst("BSN:1", null, null, null) } returns expected

        val actual = service.lijst("BSN:1", null, null, null)

        assertSame(expected, actual)
    }

    @Test
    fun `zoek delegeert q en optionele map`() {
        val expected = BerichtenLijst()
        every { sessiecache.zoek("BSN:1", "rente", null) } returns expected

        val actual = service.zoek("BSN:1", "rente", null)

        assertSame(expected, actual)
    }

    @Test
    fun `zoek geeft map door als die meegegeven is`() {
        val expected = BerichtenLijst()
        every { sessiecache.zoek("BSN:1", "rente", "archief") } returns expected

        val actual = service.zoek("BSN:1", "rente", "archief")

        assertSame(expected, actual)
    }
}
