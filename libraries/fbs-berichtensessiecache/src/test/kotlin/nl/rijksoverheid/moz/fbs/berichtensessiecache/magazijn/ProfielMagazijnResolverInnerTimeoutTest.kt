package nl.rijksoverheid.moz.fbs.berichtensessiecache.magazijn

import com.github.tomakehurst.wiremock.client.WireMock.aResponse
import com.github.tomakehurst.wiremock.client.WireMock.get
import com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo
import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.junit.QuarkusTestProfile
import io.quarkus.test.junit.TestProfile
import jakarta.inject.Inject
import nl.rijksoverheid.moz.fbs.common.identificatie.Bsn
import nl.rijksoverheid.moz.fbs.common.profiel.ProfielServiceFoutException
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import java.time.Duration

/**
 * Pin dat `profiel.resolver.inner-timeout-seconds` daadwerkelijk de Mutiny
 * `ifNoItem().after(...)`-grens bedient: WireMock-respons met fixed-delay
 * boven de config-grens MOET in TIMEOUT-categorie eindigen (niet UPSTREAM_ERROR
 * via read-timeout). Beschermt tegen refactor-regressie waar de config-property
 * losgekoppeld raakt van de timeout-pipe.
 */
@QuarkusTest
@TestProfile(InnerTimeoutTestProfile::class)
@QuarkusTestResource(WireMockProfielServiceResource::class)
@QuarkusTestResource(WireMockMagazijnResource::class)
class ProfielMagazijnResolverInnerTimeoutTest {

    @Inject
    internal lateinit var resolver: MagazijnResolver

    private val wireMock get() = WireMockProfielServiceResource.server!!

    @Test
    fun `WireMock-delay boven inner-timeout levert TIMEOUT-categorie`() {
        // inner-timeout=1s (zie InnerTimeoutTestProfile), WireMock-delay=3s.
        // Resolver-interne ifNoItem-timer slaat aan vóór read-timeout (5s default).
        wireMock.stubFor(
            get(urlEqualTo("/api/profielservice/v1/BSN/999993653")).willReturn(
                aResponse()
                    .withFixedDelay(3000)
                    .withStatus(200)
                    .withHeader("Content-Type", "application/json")
                    .withBody("""{"partijId":1,"voorkeuren":[]}"""),
            ),
        )

        val ex = assertThrows(ProfielServiceFoutException::class.java) {
            resolver.resolve(Bsn("999993653")).await().atMost(Duration.ofSeconds(10))
        }

        assertEquals(ProfielServiceFoutException.Categorie.TIMEOUT, ex.categorie)
    }
}

class InnerTimeoutTestProfile : QuarkusTestProfile {

    override fun getEnabledAlternatives(): Set<Class<*>> = setOf(
        nl.rijksoverheid.moz.fbs.berichtensessiecache.berichten.MockBerichtenCache::class.java,
    )

    override fun getConfigOverrides(): Map<String, String> = mapOf(
        "quarkus.arc.exclude-types" to MockProfielServiceClient::class.java.name,
        "quarkus.redis.devservices.enabled" to "false",
        "quarkus.redis.hosts" to "redis://localhost:6379",
        "profiel.resolver.inner-timeout-seconds" to "1",
        // outer-await > inner is afgedwongen door valideerTimeouts; pin 5s als ruime marge.
        "profiel.resolver.outer-await-seconds" to "5",
    )
}
