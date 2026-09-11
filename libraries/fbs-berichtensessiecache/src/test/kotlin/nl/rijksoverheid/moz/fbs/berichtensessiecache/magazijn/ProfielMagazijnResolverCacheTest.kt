package nl.rijksoverheid.moz.fbs.berichtensessiecache.magazijn

import com.github.tomakehurst.wiremock.client.WireMock.aResponse
import com.github.tomakehurst.wiremock.client.WireMock.equalToJson
import com.github.tomakehurst.wiremock.client.WireMock.post
import com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor
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

/**
 * Pint het cache-gedrag van de hand-rolled Caffeine-cache in [ProfielMagazijnResolver]:
 * een tweede call binnen het TTL-window levert een cache-hit op (0 extra Profiel-calls).
 * Voorkomt regressie waar iemand de cache-laag verwijdert of de TTL losraakt van de bean.
 *
 * In het main-test-profile is de cache bewust effectief uit (`%test.profiel.resolver.cache
 * .ttl-seconds=0` — Caffeine `expireAfterWrite(0)` levert nooit een hit). Deze testklasse
 * overschrijft dat met een korte TTL (1s) zodat zowel cache-hit als TTL-expiry binnen één
 * test-run reproduceerbaar zijn.
 */
@QuarkusTest
@TestProfile(CacheEnabledTestProfile::class)
@QuarkusTestResource(WireMockProfielServiceResource::class)
@QuarkusTestResource(WireMockMagazijnResource::class)
class ProfielMagazijnResolverCacheTest {

    @Inject
    internal lateinit var resolver: MagazijnResolver

    private val wireMock get() = WireMockProfielServiceResource.server!!

    @BeforeEach
    fun resetStubs() {
        wireMock.resetAll()
    }

    @Test
    fun `tweede call binnen TTL doet 0 extra Profiel-calls (cache-hit)`() {
        val urlPath = "/api/profielservice/v1/partij"

        wireMock.stubFor(
            post(urlEqualTo(urlPath)).willReturn(
                aResponse().withStatus(200).withHeader("Content-Type", "application/json").withBody(
                    """{"partijId":1,"voorkeuren":[]}""",
                ),
            ),
        )

        val first = resolver.resolve(Bsn("999993653")).await().atMost(Duration.ofSeconds(5))
        val second = resolver.resolve(Bsn("999993653")).await().atMost(Duration.ofSeconds(5))

        assertEquals(first, second)
        wireMock.verify(1, postRequestedFor(urlEqualTo(urlPath)))
    }

    @Test
    fun `fout-response wordt NIET gecacht (volgende call triggert nieuwe Profiel-call)`() {
        // Cruciaal: hand-rolled cache vult alleen bij succes. Anders zou tijdelijke
        // Profiel-storing de ontvanger TTL-lang in een 503-loop houden.
        val urlPath = "/api/profielservice/v1/partij"

        wireMock.stubFor(
            post(urlEqualTo(urlPath))
                .willReturn(aResponse().withStatus(500)),
        )

        assertThrows(ProfielServiceFoutException::class.java) {
            resolver.resolve(Bsn("999991772")).await().atMost(Duration.ofSeconds(20))
        }
        assertThrows(ProfielServiceFoutException::class.java) {
            resolver.resolve(Bsn("999991772")).await().atMost(Duration.ofSeconds(20))
        }

        // 500 = WebApplicationException, niet ProcessingException → @Retry niet getriggerd
        // → 1 POST per resolve-call. Belangrijk: 2 resolve-calls → 2 POSTs (niet 1).
        wireMock.verify(2, postRequestedFor(urlEqualTo(urlPath)))
    }

    @Test
    fun `een 404 zonder herkenbaar antwoord wordt NIET gecacht`() {
        // Een storing mag niet TTL-lang blijven hangen: zodra de koppeling hersteld is moet de
        // eerstvolgende ophaalactie de dienst weer bereiken. De opt-out-404 hieronder wordt wél
        // gecacht — dat onderscheid is precies wat deze twee tests naast elkaar vastleggen.
        val urlPath = "/api/profielservice/v1/partij"

        wireMock.stubFor(
            post(urlEqualTo(urlPath)).willReturn(aResponse().withStatus(404)),
        )

        assertThrows(ProfielServiceFoutException::class.java) {
            resolver.resolve(Bsn("999990044")).await().atMost(Duration.ofSeconds(20))
        }
        assertThrows(ProfielServiceFoutException::class.java) {
            resolver.resolve(Bsn("999990044")).await().atMost(Duration.ofSeconds(20))
        }

        wireMock.verify(2, postRequestedFor(urlEqualTo(urlPath)))
    }

    @Test
    fun `een 404 met het partij-niet-gevonden-antwoord wordt wel gecacht`() {
        val urlPath = "/api/profielservice/v1/partij"

        wireMock.stubFor(
            post(urlEqualTo(urlPath)).willReturn(
                aResponse().withStatus(404)
                    .withHeader("Content-Type", "application/problem+json")
                    .withBody("""{"type":"about:blank","title":"Partij niet gevonden","status":404}"""),
            ),
        )

        val eerste = resolver.resolve(Bsn("999990020")).await().atMost(Duration.ofSeconds(5))
        val tweede = resolver.resolve(Bsn("999990020")).await().atMost(Duration.ofSeconds(5))

        assertEquals(emptySet<String>(), eerste)
        assertEquals(eerste, tweede)
        wireMock.verify(1, postRequestedFor(urlEqualTo(urlPath)))
    }

    @Test
    fun `de storing van de ene ontvanger raakt de opt-out van de andere niet`() {
        // Twee ontvangers naast elkaar: met één ontvanger zou een cache die alles onder
        // dezelfde sleutel legt er net zo goed uitzien. Hier moet de opt-out van de één
        // gecacht blijven terwijl de storing van de ander elke keer opnieuw uitgaat.
        val urlPath = "/api/profielservice/v1/partij"
        val optOut = Bsn("999990056")
        val storing = Bsn("999990068")

        wireMock.stubFor(
            post(urlEqualTo(urlPath)).withRequestBody(
                equalToJson("""{"identificatieType":"BSN","identificatieNummer":"${optOut.waarde}"}"""),
            ).willReturn(
                aResponse().withStatus(404)
                    .withHeader("Content-Type", "application/problem+json")
                    .withBody("""{"type":"about:blank","title":"Partij niet gevonden","status":404}"""),
            ),
        )
        wireMock.stubFor(
            post(urlEqualTo(urlPath)).withRequestBody(
                equalToJson("""{"identificatieType":"BSN","identificatieNummer":"${storing.waarde}"}"""),
            ).willReturn(aResponse().withStatus(404)),
        )

        assertEquals(emptySet<String>(), resolver.resolve(optOut).await().atMost(Duration.ofSeconds(5)))

        assertThrows(ProfielServiceFoutException::class.java) {
            resolver.resolve(storing).await().atMost(Duration.ofSeconds(20))
        }

        assertEquals(emptySet<String>(), resolver.resolve(optOut).await().atMost(Duration.ofSeconds(5)))

        assertThrows(ProfielServiceFoutException::class.java) {
            resolver.resolve(storing).await().atMost(Duration.ofSeconds(20))
        }

        // Opt-out: 1 call (tweede kwam uit de cache). Storing: 2 calls (nooit gecacht).
        wireMock.verify(
            1,
            postRequestedFor(urlEqualTo(urlPath)).withRequestBody(
                equalToJson("""{"identificatieType":"BSN","identificatieNummer":"${optOut.waarde}"}"""),
            ),
        )
        wireMock.verify(
            2,
            postRequestedFor(urlEqualTo(urlPath)).withRequestBody(
                equalToJson("""{"identificatieType":"BSN","identificatieNummer":"${storing.waarde}"}"""),
            ),
        )
    }

    @Test
    fun `call na TTL-expiry triggert nieuwe Profiel-call (cache miss)`() {
        val urlPath = "/api/profielservice/v1/partij"

        wireMock.stubFor(
            post(urlEqualTo(urlPath)).willReturn(
                aResponse().withStatus(200).withHeader("Content-Type", "application/json").withBody(
                    """{"partijId":1,"voorkeuren":[]}""",
                ),
            ),
        )

        resolver.resolve(Bsn("999996915")).await().atMost(Duration.ofSeconds(5))
        // TTL is 1s (zie CacheEnabledTestProfile); 1.5s wachten dwingt expiry af.
        Thread.sleep(1500)
        resolver.resolve(Bsn("999996915")).await().atMost(Duration.ofSeconds(5))

        wireMock.verify(2, postRequestedFor(urlEqualTo(urlPath)))
    }
}

class CacheEnabledTestProfile : QuarkusTestProfile {

    override fun getEnabledAlternatives(): Set<Class<*>> = setOf(
        nl.rijksoverheid.moz.fbs.berichtensessiecache.berichten.MockBerichtenCache::class.java,
    )

    override fun getConfigOverrides(): Map<String, String> = mapOf(
        "quarkus.arc.exclude-types" to MockProfielServiceClient::class.java.name,
        "quarkus.redis.devservices.enabled" to "false",
        "quarkus.redis.hosts" to "redis://localhost:6379",
        // Override de test-default (ttl=0 = effectief uit); korte TTL voor expiry-test.
        // Caffeine TTL is in seconden; 1s is minimum bruikbare granulariteit, Thread.sleep
        // van 1500ms in TTL-expiry-test geeft 1.5× marge.
        "profiel.resolver.cache.ttl-seconds" to "1",
        "profiel.resolver.cache.max-size" to "100",
    )
}
