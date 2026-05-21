package nl.rijksoverheid.moz.fbs.berichtenmagazijn.publicatie

import nl.rijksoverheid.moz.fbs.common.exception.DomainValidationException
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import java.util.UUID

class PublicatieClaimTest {

    private val berichtId = UUID.randomUUID()

    @Test
    fun `valide claim wordt geaccepteerd`() {
        PublicatieClaim(claimId = 1L, berichtId = berichtId, doel = Publicatiedoel("aanmeld"), pogingen = 0)
    }

    @Test
    fun `nul of negatieve claimId wordt geweigerd`() {
        assertThrows(DomainValidationException::class.java) {
            PublicatieClaim(claimId = 0L, berichtId = berichtId, doel = Publicatiedoel("aanmeld"), pogingen = 0)
        }
        assertThrows(DomainValidationException::class.java) {
            PublicatieClaim(claimId = -1L, berichtId = berichtId, doel = Publicatiedoel("aanmeld"), pogingen = 0)
        }
    }

    @Test
    fun `negatieve pogingen wordt geweigerd`() {
        assertThrows(DomainValidationException::class.java) {
            PublicatieClaim(claimId = 1L, berichtId = berichtId, doel = Publicatiedoel("aanmeld"), pogingen = -1)
        }
    }
}
