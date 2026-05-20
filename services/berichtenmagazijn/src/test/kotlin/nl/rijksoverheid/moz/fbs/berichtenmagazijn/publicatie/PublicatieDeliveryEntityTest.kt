package nl.rijksoverheid.moz.fbs.berichtenmagazijn.publicatie

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID

class PublicatieDeliveryEntityTest {

    private val berichtId = UUID.randomUUID()
    private val nu = Instant.parse("2026-05-12T10:00:00Z")

    private fun nieuweEntity(): PublicatieDeliveryEntity = PublicatieDeliveryEntity.nieuwe(
        berichtId = berichtId,
        doel = Publicatiedoel("aanmeld"),
        volgendePoging = nu,
        aangemaaktOp = nu,
    )

    @Test
    fun `markeerGeslaagd vanuit TE_PUBLICEREN zet status en tijdstip`() {
        val entity = nieuweEntity()
        entity.markeerGeslaagd(nu.plusSeconds(10))
        assertEquals(DeliveryStatus.GEPUBLICEERD, entity.status)
        assertEquals(nu.plusSeconds(10), entity.gepubliceerdOp)
        assertNull(entity.laatsteFout)
    }

    @Test
    fun `markeerGeslaagd vanuit MISLUKT faalt`() {
        val entity = nieuweEntity()
        entity.markeerMislukt("definitief", volgendePoging = null)
        assertThrows(IllegalStateException::class.java) {
            entity.markeerGeslaagd(nu)
        }
    }

    @Test
    fun `markeerGeslaagd dubbel uitvoeren faalt`() {
        val entity = nieuweEntity()
        entity.markeerGeslaagd(nu)
        assertThrows(IllegalStateException::class.java) {
            entity.markeerGeslaagd(nu.plusSeconds(1))
        }
    }

    @Test
    fun `markeerMislukt met volgendePoging blijft TE_PUBLICEREN en hoogt pogingen`() {
        val entity = nieuweEntity()
        val later = nu.plusSeconds(30)
        entity.markeerMislukt("retry-baar", volgendePoging = later)
        assertEquals(DeliveryStatus.TE_PUBLICEREN, entity.status)
        assertEquals(1, entity.pogingen)
        assertEquals(later, entity.volgendePoging)
        assertEquals("retry-baar", entity.laatsteFout)
    }

    @Test
    fun `markeerMislukt zonder volgendePoging zet MISLUKT`() {
        val entity = nieuweEntity()
        entity.markeerMislukt("hopeloos", volgendePoging = null)
        assertEquals(DeliveryStatus.MISLUKT, entity.status)
        assertEquals(1, entity.pogingen)
    }

    @Test
    fun `markeerMislukt vanuit GEPUBLICEERD faalt`() {
        val entity = nieuweEntity()
        entity.markeerGeslaagd(nu)
        assertThrows(IllegalStateException::class.java) {
            entity.markeerMislukt("late fout", volgendePoging = null)
        }
    }

    @Test
    fun `markeerMislukt knipt zeer lange fout-tekst af op 4 KiB`() {
        val entity = nieuweEntity()
        val lange = "x".repeat(10_000)
        entity.markeerMislukt(lange, volgendePoging = nu.plusSeconds(1))
        assertNotNull(entity.laatsteFout)
        assertEquals(4_096, entity.laatsteFout!!.length)
    }

    @Test
    fun `toClaim mapt entity-velden naar PublicatieClaim`() {
        val entity = nieuweEntity()
        entity.id = 42L
        val claim = entity.toClaim()
        assertEquals(42L, claim.claimId)
        assertEquals(berichtId, claim.berichtId)
        assertEquals(Publicatiedoel("aanmeld"), claim.doel)
        assertEquals(0, claim.pogingen)
    }
}
