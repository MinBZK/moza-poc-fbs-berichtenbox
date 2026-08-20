package nl.rijksoverheid.moz.fbs.berichtenuitvraag.uitvraag

import com.github.tomakehurst.wiremock.client.WireMock.aResponse
import com.github.tomakehurst.wiremock.client.WireMock.equalTo
import com.github.tomakehurst.wiremock.client.WireMock.get
import com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor
import com.github.tomakehurst.wiremock.client.WireMock.urlPathMatching
import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.junit.TestProfile
import jakarta.inject.Inject
import nl.rijksoverheid.moz.fbs.common.identificatie.Oin
import nl.rijksoverheid.moz.fbs.magazijnregister.Magazijnregister
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.UUID
import javax.net.ssl.SSLException

/**
 * De outway op ZAD serveert zijn poort met een certificaat uit de interne PKI van de peer, en
 * die CA kent de JVM niet. Deze test zet precies die situatie neer: een magazijn achter TLS met
 * een vers, zelf-ondertekend certificaat, en `quarkus.tls.outway.*` als enige anchor.
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

    @Inject
    lateinit var register: Magazijnregister

    @Test
    fun `een magazijn met grant-hash krijgt de outway-TLS-configuratie`() {
        val inschrijving = register.voorOin(Oin(HttpsMagazijnResource.OIN))

        assertNotNull(inschrijving)
        assertNotNull(router.outwayTlsConfiguratie(inschrijving!!))
    }

    @Test
    fun `een magazijn achter een eigen CA is bereikbaar met dat anchor`() {
        val berichtId = UUID.randomUUID()
        val bijlageId = UUID.randomUUID()

        HttpsMagazijnResource.magazijn.stubFor(
            get(urlPathMatching("/api/v1/berichten/.*/bijlagen/.*"))
                .willReturn(aResponse().withStatus(200).withHeader("Content-Type", "text/plain").withBody("ok")),
        )

        // Sluiten: de entity-stream houdt anders een connection uit de pool vast tot de GC 'm
        // opruimt, en dat is precies het soort lek dat pas opvalt zodra iemand deze test in een
        // lus kopieert.
        val respons = router.forMagazijn(HttpsMagazijnResource.OIN)
            .bijlage("BSN:999993653", berichtId, bijlageId)

        respons.use { assertEquals(200, it.status) }

        // De FSC-header hoort over diezelfde TLS-verbinding aan te komen: op ZAD dragen TLS en
        // grant-hash altijd samen, en ze worden op dezelfde builder geregistreerd.
        HttpsMagazijnResource.magazijn.verify(
            getRequestedFor(urlPathMatching("/api/v1/berichten/.*/bijlagen/.*"))
                .withHeader("Fsc-Grant-Hash", equalTo(HttpsMagazijnResource.GRANT_HASH)),
        )
    }

    /**
     * De keerzijde, op het TLS-pad zelf. Dit magazijn draait op exact hetzelfde endpoint als
     * hierboven; het enige verschil is de ontbrekende grant-hash. Faalt de handshake hier, dan
     * zit de discriminator aantoonbaar op de grant-hash en niet op het adres — en valt een
     * magazijn met een publiek certificaat dus niet om zodra het anchor geconfigureerd wordt.
     */
    @Test
    fun `een magazijn zonder grant-hash valt terug op de JVM-default trust-store`() {
        HttpsMagazijnResource.magazijn.stubFor(
            get(urlPathMatching("/api/v1/berichten/.*/bijlagen/.*"))
                .willReturn(aResponse().withStatus(200).withHeader("Content-Type", "text/plain").withBody("ok")),
        )

        val fout = assertThrows(Exception::class.java) {
            router.forMagazijn(HttpsMagazijnResource.OIN_ZONDER_GRANT_HASH)
                .bijlage("BSN:999993653", UUID.randomUUID(), UUID.randomUUID())
        }

        assertTrue(
            generateSequence(fout as Throwable) { it.cause }.any { it is SSLException },
            "verwachtte een TLS-fout omdat dit certificaat niet in de default trust-store staat, kreeg: $fout",
        )
    }
}
