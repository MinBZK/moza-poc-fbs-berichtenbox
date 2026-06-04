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
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.time.Duration

@QuarkusTest
@TestProfile(WireMockProfielServiceTestProfile::class)
@QuarkusTestResource(WireMockProfielServiceResource::class)
@QuarkusTestResource(WireMockMagazijnResource::class)
class ProfielMagazijnResolverIntegrationTest {

    @Inject
    lateinit var resolver: MagazijnResolver

    private val wireMock get() = WireMockProfielServiceResource.server!!

    @BeforeEach
    fun resetStubs() {
        wireMock.resetAll()
    }

    @Test
    fun `200 met opted-in voorkeur retourneert magazijn-a`() {
        wireMock.stubFor(
            get(urlEqualTo("/api/profielservice/v1/BSN/999993653")).willReturn(
                aResponse().withStatus(200).withHeader("Content-Type", "application/json").withBody(
                    """
                    {
                      "partijId": 1,
                      "voorkeuren": [
                        { "voorkeurType": "OntvangViaBerichtenbox", "waarde": "true",
                          "scopes": [ { "partij": { "identificatieType": "OIN", "identificatieNummer": "00000001003214345000" } } ] }
                      ]
                    }
                    """.trimIndent(),
                ),
            ),
        )
        val result = resolver.resolve(Bsn("999993653")).await().atMost(Duration.ofSeconds(5))
        assertEquals(setOf("magazijn-a"), result)
    }

    @Test
    fun `404 retourneert lege set`() {
        wireMock.stubFor(
            get(urlEqualTo("/api/profielservice/v1/BSN/999993653"))
                .willReturn(aResponse().withStatus(404)),
        )
        val result = resolver.resolve(Bsn("999993653")).await().atMost(Duration.ofSeconds(5))
        assertEquals(emptySet<String>(), result)
    }

    @Test
    fun `500 werpt ProfielServiceFoutException`() {
        wireMock.stubFor(
            get(urlEqualTo("/api/profielservice/v1/BSN/999993653"))
                .willReturn(aResponse().withStatus(500)),
        )
        assertThrows(ProfielServiceFoutException::class.java) {
            resolver.resolve(Bsn("999993653")).await().atMost(Duration.ofSeconds(20))
        }
    }

    @Test
    fun `malformed JSON werpt ProfielServiceFoutException`() {
        wireMock.stubFor(
            get(urlEqualTo("/api/profielservice/v1/BSN/999993653"))
                .willReturn(aResponse().withStatus(200).withHeader("Content-Type", "application/json").withBody("{this is not json")),
        )
        assertThrows(ProfielServiceFoutException::class.java) {
            resolver.resolve(Bsn("999993653")).await().atMost(Duration.ofSeconds(5))
        }
    }

    @Test
    fun `200 met lege body retourneert lege voorkeurenset`() {
        // Profiel-service stuurt geldig JSON-object zonder voorkeuren-veld → default emptyList.
        wireMock.stubFor(
            get(urlEqualTo("/api/profielservice/v1/BSN/999993653"))
                .willReturn(aResponse().withStatus(200).withHeader("Content-Type", "application/json").withBody("{}")),
        )
        val result = resolver.resolve(Bsn("999993653")).await().atMost(Duration.ofSeconds(5))
        assertEquals(emptySet<String>(), result)
    }

    @Test
    fun `RSIN-pad gebruikt URL-template per type (200 met opt-in retourneert magazijn-b)`() {
        // naarProfielType-mapping is alleen unit-getest via MockK; deze test pinned dat
        // het echte URL-pad `/RSIN/...` wordt geraakt op de upstream-stub (geen drift
        // tussen interne enum-naam en extern contract-pad).
        wireMock.stubFor(
            get(urlEqualTo("/api/profielservice/v1/RSIN/002564440")).willReturn(
                aResponse().withStatus(200).withHeader("Content-Type", "application/json").withBody(
                    """
                    {
                      "partijId": 2,
                      "voorkeuren": [
                        { "voorkeurType": "OntvangViaBerichtenbox", "waarde": "true",
                          "scopes": [ { "partij": { "identificatieType": "OIN", "identificatieNummer": "00000001823288444000" } } ] }
                      ]
                    }
                    """.trimIndent(),
                ),
            ),
        )

        val result = resolver.resolve(nl.rijksoverheid.moz.fbs.common.identificatie.Rsin("002564440")).await().atMost(Duration.ofSeconds(5))

        assertEquals(setOf("magazijn-b"), result)
    }

    @Test
    fun `KVK-pad gebruikt URL-template per type (200 met opt-in retourneert magazijn-a)`() {
        wireMock.stubFor(
            get(urlEqualTo("/api/profielservice/v1/KVK/12345678")).willReturn(
                aResponse().withStatus(200).withHeader("Content-Type", "application/json").withBody(
                    """
                    {
                      "partijId": 3,
                      "voorkeuren": [
                        { "voorkeurType": "OntvangViaBerichtenbox", "waarde": "true",
                          "scopes": [ { "partij": { "identificatieType": "OIN", "identificatieNummer": "00000001003214345000" } } ] }
                      ]
                    }
                    """.trimIndent(),
                ),
            ),
        )

        val result = resolver.resolve(nl.rijksoverheid.moz.fbs.common.identificatie.Kvk("12345678")).await().atMost(Duration.ofSeconds(5))

        assertEquals(setOf("magazijn-a"), result)
    }

    @Test
    fun `200 met voorkeuren null wordt als malformed gemeld (geen stille lege set)`() {
        // `voorkeuren` is in `PartijResponse` een non-nullable List met default emptyList.
        // Een expliciete null in de upstream-body is een contract-breuk: Jackson Kotlin
        // module weigert (KotlinInvalidNullException). Dit MOET zichtbaar zijn als
        // ProfielServiceFoutException.malformed, niet stilzwijgend tot een lege set
        // worden gedegradeerd.
        wireMock.stubFor(
            get(urlEqualTo("/api/profielservice/v1/BSN/999993653"))
                .willReturn(aResponse().withStatus(200).withHeader("Content-Type", "application/json").withBody("""{"voorkeuren": null}""")),
        )
        assertThrows(ProfielServiceFoutException::class.java) {
            resolver.resolve(Bsn("999993653")).await().atMost(Duration.ofSeconds(5))
        }
    }
}

class WireMockProfielServiceTestProfile : QuarkusTestProfile {

    override fun getEnabledAlternatives(): Set<Class<*>> = setOf(
        nl.rijksoverheid.moz.fbs.berichtensessiecache.berichten.MockBerichtenCache::class.java,
    )

    override fun getConfigOverrides(): Map<String, String> = mapOf(
        "quarkus.arc.exclude-types" to MockProfielServiceClient::class.java.name,
        "quarkus.redis.devservices.enabled" to "false",
        "quarkus.redis.hosts" to "redis://localhost:6379",
    )
}
