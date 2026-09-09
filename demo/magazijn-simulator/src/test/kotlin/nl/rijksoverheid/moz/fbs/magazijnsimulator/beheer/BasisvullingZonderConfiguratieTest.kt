package nl.rijksoverheid.moz.fbs.magazijnsimulator.beheer

import io.quarkus.test.junit.QuarkusTest
import jakarta.inject.Inject
import nl.rijksoverheid.moz.fbs.magazijnsimulator.MagazijnTestBasis
import nl.rijksoverheid.moz.fbs.magazijnsimulator.opslag.BulkOpslag
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Test

/**
 * Zonder geconfigureerde ontvangers vult de simulator niets.
 *
 * Een omgeving die deze vulling niet wil — een testrun, een laptop met eigen gegevens — hoort er
 * niets van te merken. Zou de simulator zonder configuratie tóch iets verzinnen, dan bepaalt hij
 * wie er in de demo meespeelt, en dat is niet aan hem: de ondernemers komen uit hetzelfde
 * generator-artefact als de magazijnenset.
 *
 * Draait bewust op het gewone testprofiel, want dát is het profiel zonder basisvulling.
 */
@QuarkusTest
class BasisvullingZonderConfiguratieTest : MagazijnTestBasis() {

    @Inject
    lateinit var basisvulling: Basisvulling

    @Inject
    lateinit var bulk: BulkOpslag

    @Test
    fun `zonder ontvangers blijft een lege opslag leeg`() {
        assertEquals(Basisvulling.Uitkomst.NIET_GECONFIGUREERD, basisvulling.vulIndienLeeg())
        assertFalse(bulk.ergensBerichten(), "er hoort niets klaargezet te zijn")
    }
}
