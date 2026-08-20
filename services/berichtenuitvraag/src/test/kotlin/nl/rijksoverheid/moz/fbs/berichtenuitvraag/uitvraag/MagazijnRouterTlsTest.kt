package nl.rijksoverheid.moz.fbs.berichtenuitvraag.uitvraag

import com.github.tomakehurst.wiremock.client.WireMock.aResponse
import com.github.tomakehurst.wiremock.client.WireMock.get
import com.github.tomakehurst.wiremock.client.WireMock.urlPathMatching
import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.junit.TestProfile
import jakarta.inject.Inject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Test
import java.util.UUID

/**
 * De outway op ZAD serveert zijn poort met een certificaat uit de interne PKI van de peer,
 * en die CA kent de JVM niet. Deze test zet precies die situatie neer: een magazijn achter
 * TLS met een vers, zelf-ondertekend certificaat, en `quarkus.tls.outway.*` als enige anker.
 *
 * Dat de aanroep slaagt ís het bewijs. Zou [MagazijnRouter] de TLS-configuratie niet aan de
 * client meegeven, dan viel de handshake terug op de JVM-default trust-store en faalde hij —
 * dit certificaat staat daar niet in en kan er ook niet in staan.
 */
@QuarkusTest
@TestProfile(MockSessiecacheProfile::class)
@QuarkusTestResource(value = HttpsMagazijnResource::class, restrictToAnnotatedClass = true)
class MagazijnRouterTlsTest {

    @Inject
    lateinit var router: MagazijnRouter

    @Test
    fun `de outway-TLS-configuratie wordt gevonden zodra hij geconfigureerd is`() {
        assertNotNull(router.outwayTlsConfiguratie())
    }

    @Test
    fun `een magazijn achter een eigen CA is bereikbaar met dat anker`() {
        val berichtId = UUID.randomUUID()
        val bijlageId = UUID.randomUUID()

        HttpsMagazijnResource.magazijn.stubFor(
            get(urlPathMatching("/api/v1/berichten/.*/bijlagen/.*"))
                .willReturn(aResponse().withStatus(200).withHeader("Content-Type", "text/plain").withBody("ok")),
        )

        val respons = router.forMagazijn(HttpsMagazijnResource.OIN)
            .bijlage("BSN:999993653", berichtId, bijlageId)

        assertEquals(200, respons.status)
    }
}
