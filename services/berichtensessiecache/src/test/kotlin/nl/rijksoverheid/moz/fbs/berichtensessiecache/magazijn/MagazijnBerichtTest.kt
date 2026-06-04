package nl.rijksoverheid.moz.fbs.berichtensessiecache.magazijn

import nl.rijksoverheid.moz.fbs.berichtensessiecache.berichten.Leesstatus
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID

class MagazijnBerichtTest {

    private fun magazijnBericht(status: MagazijnBericht.MagazijnBerichtStatus?) = MagazijnBericht(
        berichtId = UUID.randomUUID(),
        afzender = "00000001234567890000",
        ontvanger = MagazijnBericht.Identificatienummer("BSN", "123456782"),
        onderwerp = "Onderwerp",
        inhoud = "Inhoud",
        publicatietijdstip = Instant.parse("2026-03-10T10:00:00Z"),
        status = status,
    )

    @Test
    fun `gelezen=true mapt naar status gelezen`() {
        val bericht = magazijnBericht(MagazijnBericht.MagazijnBerichtStatus(gelezen = true)).toBericht("magazijn-a")

        assertEquals(Leesstatus.GELEZEN, bericht.status)
    }

    @Test
    fun `gelezen=false mapt naar status ongelezen`() {
        val bericht = magazijnBericht(MagazijnBericht.MagazijnBerichtStatus(gelezen = false)).toBericht("magazijn-a")

        assertEquals(Leesstatus.ONGELEZEN, bericht.status)
    }

    @Test
    fun `status-object zonder gelezen geeft status null`() {
        val bericht = magazijnBericht(MagazijnBericht.MagazijnBerichtStatus(gelezen = null, map = "inkomend")).toBericht("magazijn-a")

        assertNull(bericht.status)
        assertEquals("inkomend", bericht.map)
    }

    @Test
    fun `ontbrekend status-object geeft status null`() {
        val bericht = magazijnBericht(null).toBericht("magazijn-a")

        assertNull(bericht.status)
    }
}
