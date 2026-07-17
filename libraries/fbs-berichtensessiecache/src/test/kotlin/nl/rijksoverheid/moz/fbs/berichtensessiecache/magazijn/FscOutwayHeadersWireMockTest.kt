package nl.rijksoverheid.moz.fbs.berichtensessiecache.magazijn

import com.github.tomakehurst.wiremock.WireMockServer
import com.github.tomakehurst.wiremock.client.WireMock.aResponse
import com.github.tomakehurst.wiremock.client.WireMock.equalTo
import com.github.tomakehurst.wiremock.client.WireMock.get
import com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor
import com.github.tomakehurst.wiremock.client.WireMock.matching
import com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo
import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.junit.TestProfile
import jakarta.inject.Inject
import nl.rijksoverheid.moz.fbs.common.identificatie.Bsn
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * Bewijst dat de FSC-outway-headers daadwerkelijk op de uitgaande magazijn-call staan,
 * niet alleen dat `MagazijnClientFactory.fscFilterVoor` de juiste registratie-beslissing
 * neemt (dat dekt `MagazijnClientFactoryFscFilterTest` al zonder een echte REST-client-
 * build, wat buiten @QuarkusTest ArC-context faalt). `WireMockMagazijnResource` geeft
 * alleen magazijn-a een grantHash, zodat beide takken via de echte, door CDI gebouwde
 * client aan bod komen: magazijn-a moet de headers sturen, magazijn-b niet.
 */
@QuarkusTest
@TestProfile(WireMockTestProfile::class)
@QuarkusTestResource(WireMockMagazijnResource::class)
class FscOutwayHeadersWireMockTest {

    private val ontvanger = Bsn("999993653")

    // Echte factory: dezelfde client die de sessiecache-aggregatie ook gebruikt, inclusief
    // de eventueel geregistreerde FscOutwayHeadersFilter.
    @Inject
    internal lateinit var clientFactory: MagazijnClientFactory

    @BeforeEach
    fun setUp() {
        WireMockMagazijnResource.serverA!!.resetAll()
        WireMockMagazijnResource.serverB!!.resetAll()
    }

    @Test
    fun `magazijn met grantHash krijgt Fsc-Grant-Hash en een v7 Fsc-Transaction-Id op de call`() {
        stubBerichten(WireMockMagazijnResource.serverA!!)

        val client = clientFactory.getAllClients()[WireMockMagazijnResource.OIN_A]

        assertNotNull(client, "client voor magazijn-OIN A moet geconfigureerd zijn")

        client!!.getBerichten(ontvanger.toCanonicalString(), null)

        WireMockMagazijnResource.serverA!!.verify(
            getRequestedFor(urlPathEqualTo("/api/v1/berichten"))
                .withHeader("Fsc-Grant-Hash", equalTo(WireMockMagazijnResource.GRANT_HASH_A))
                .withHeader("Fsc-Transaction-Id", matching(UUID_V7_REGEX))
        )
    }

    @Test
    fun `magazijn zonder grantHash stuurt geen FSC-outway-headers op de call`() {
        stubBerichten(WireMockMagazijnResource.serverB!!)

        val client = clientFactory.getAllClients()[WireMockMagazijnResource.OIN_B]

        assertNotNull(client, "client voor magazijn-OIN B moet geconfigureerd zijn")

        client!!.getBerichten(ontvanger.toCanonicalString(), null)

        WireMockMagazijnResource.serverB!!.verify(
            getRequestedFor(urlPathEqualTo("/api/v1/berichten"))
                .withoutHeader("Fsc-Grant-Hash")
                .withoutHeader("Fsc-Transaction-Id")
        )
    }

    private fun stubBerichten(server: WireMockServer) {
        server.stubFor(
            get(urlPathEqualTo("/api/v1/berichten"))
                .willReturn(
                    aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("""{"berichten":[]}""")
                )
        )
    }

    companion object {
        // Version-nibble "7" op de tijd-hoge groep pint UUID-v7 op de header-string, zonder
        // een volledige UUID-parse (de header is op de wire gewoon tekst).
        private const val UUID_V7_REGEX =
            "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-7[0-9a-fA-F]{3}-[89abAB][0-9a-fA-F]{3}-[0-9a-fA-F]{12}"
    }
}
