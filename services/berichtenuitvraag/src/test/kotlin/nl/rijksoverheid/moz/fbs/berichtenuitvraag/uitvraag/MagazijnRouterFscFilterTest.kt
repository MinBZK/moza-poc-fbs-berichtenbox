package nl.rijksoverheid.moz.fbs.berichtenuitvraag.uitvraag

import nl.rijksoverheid.moz.fbs.common.identificatie.Oin
import nl.rijksoverheid.moz.fbs.magazijnregister.Magazijninschrijving
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import java.net.URI

/**
 * Dekt alleen de registratie-beslissing (`fscFilterVoor`), niet `forMagazijn` zelf: die
 * bouwt via `RestClientBuilder`, wat buiten CDI-context een proxy-klasse genereert.
 */
class MagazijnRouterFscFilterTest {

    private fun inschrijving(grantHash: String?): Magazijninschrijving = Magazijninschrijving(
        oin = Oin("00000001003214345000"),
        url = URI.create("http://localhost:8081"),
        naam = null,
        grantHash = grantHash,
    )

    @Test
    fun `grantHash aanwezig levert een FscOutwayHeadersFilter`() {
        val filter = MagazijnRouter.fscFilterVoor(inschrijving(grantHash = "abc123"))

        assertNotNull(filter)
    }

    @Test
    fun `grantHash afwezig levert geen filter`() {
        val filter = MagazijnRouter.fscFilterVoor(inschrijving(grantHash = null))

        assertNull(filter)
    }
}
