package nl.rijksoverheid.moz.fbs.berichtenmagazijn.validatie

import com.github.tomakehurst.wiremock.client.WireMock.aResponse
import com.github.tomakehurst.wiremock.client.WireMock.equalTo
import com.github.tomakehurst.wiremock.client.WireMock.get
import com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor
import com.github.tomakehurst.wiremock.client.WireMock.matching
import com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo
import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.junit.QuarkusTestProfile
import io.quarkus.test.junit.TestProfile
import jakarta.inject.Inject
import nl.rijksoverheid.moz.fbs.common.profiel.ProfielServiceClient
import org.eclipse.microprofile.rest.client.inject.RestClient
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * Bewijst dat de FSC-outway-headers op de échte, door CDI gebouwde Profiel-client staan —
 * niet alleen dat de filter-logica klopt (dat dekt ProfielFscOutwayHeadersFilterTest).
 * Het negatieve geval staat in ProfielServiceClientWireMockTest, dat zonder grant-hash draait.
 */
@QuarkusTest
@TestProfile(ProfielFscGrantHashTestProfile::class)
@QuarkusTestResource(WireMockProfielServiceResource::class)
class ProfielFscOutwayHeadersWireMockTest {

    @Inject
    @RestClient
    lateinit var client: ProfielServiceClient

    private val wireMock get() = WireMockProfielServiceResource.server!!

    @BeforeEach
    fun resetStubs() {
        wireMock.resetAll()
    }

    @Test
    fun `met grant-hash draagt de Profiel-call Fsc-Grant-Hash en een v7 Fsc-Transaction-Id`() {
        wireMock.stubFor(
            get(urlEqualTo(PROFIEL_PAD)).willReturn(
                aResponse()
                    .withStatus(200)
                    .withHeader("Content-Type", "application/json")
                    .withBody("""{"voorkeuren":[]}""")
            )
        )

        client.getPartij("BSN", "999993653")

        wireMock.verify(
            getRequestedFor(urlEqualTo(PROFIEL_PAD))
                .withHeader("Fsc-Grant-Hash", equalTo(ProfielFscGrantHashTestProfile.GRANT_HASH))
                .withHeader("Fsc-Transaction-Id", matching(UUID_V7_REGEX))
        )
    }

    companion object {
        const val PROFIEL_PAD = "/api/profielservice/v1/BSN/999993653"

        // De version-nibble "7" wordt expliciet gepind: de outway wijst v4 af, dus een
        // test die alleen "is een UUID" controleert vangt precies die fout niet.
        const val UUID_V7_REGEX =
            "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-7[0-9a-fA-F]{3}-[89abAB][0-9a-fA-F]{3}-[0-9a-fA-F]{12}"
    }
}

class ProfielFscGrantHashTestProfile : QuarkusTestProfile {

    override fun getConfigOverrides(): Map<String, String> = mapOf(
        // Sluit de @Mock-bean uit zodat de échte REST-client (mét de geregistreerde filter)
        // wordt geïnjecteerd en het HTTP-pad onder test komt.
        "quarkus.arc.exclude-types" to MockProfielServiceClient::class.java.name,
        "profiel-service.grant-hash" to GRANT_HASH,
    )

    companion object {
        const val GRANT_HASH = "profiel-grant-hash-test"
    }
}
