package nl.rijksoverheid.moz.fbs.berichtenuitvraag.uitvraag

import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.junit.TestProfile
import jakarta.ws.rs.WebApplicationException
import nl.rijksoverheid.moz.fbs.common.identificatie.Oin
import nl.rijksoverheid.moz.fbs.magazijnregister.Magazijninschrijving
import nl.rijksoverheid.moz.fbs.magazijnregister.Magazijnregister
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import java.net.URI
import java.time.Duration

// @QuarkusTest zodat de rest-client-runtime aanwezig is; `RestClientBuilder.build`
// heeft die nodig om een client-proxy op te bouwen. Mock-profiel: zonder profiel zou
// de (inactieve) Redis-client de boot laten falen nu de testsuite geen
// quarkus.redis.hosts meer zet (dat zou Dev Services voor de keten-E2E onderdrukken).
//
// De register-validatie zelf (OIN-keys, URL/TLS, leeg register) heeft eigen tests
// in fbs-magazijnregister; hier pinnen we alleen het routeringsgedrag.
@QuarkusTest
@TestProfile(MockSessiecacheProfile::class)
class MagazijnRouterTest {

    private val oinA = WireMockBackendsResource.OIN_A
    private val onbekendeOin = "99999999999999999999"

    private fun routerMet(vararg inschrijvingen: Pair<String, String>): MagazijnRouter =
        MagazijnRouter(
            register = object : Magazijnregister {
                private val entries = inschrijvingen.map { (oin, url) ->
                    Magazijninschrijving(Oin(oin), URI.create(url), naam = null)
                }

                override fun alle(): Collection<Magazijninschrijving> = entries
                override fun voorOin(oin: Oin): Magazijninschrijving? = entries.firstOrNull { it.oin == oin }
            },
            config = object : MagazijnRouterConfig {
                override fun connectTimeout(): Duration = Duration.ofSeconds(2)
                override fun readTimeout(): Duration = Duration.ofSeconds(10)
            },
        )

    @Test
    fun `forMagazijn bouwt een client voor een bekende magazijnId`() {
        val router = routerMet(oinA to "http://localhost:8081")

        assertNotNull(router.forMagazijn(oinA))
    }

    @Test
    fun `forMagazijn hergebruikt dezelfde client-instance per magazijnId`() {
        val router = routerMet(oinA to "http://localhost:8081")

        val eerste = router.forMagazijn(oinA)
        val tweede = router.forMagazijn(oinA)

        assertSame(eerste, tweede)
    }

    @Test
    fun `forMagazijn gooit 502 bij een onbekende magazijnId`() {
        val router = routerMet(oinA to "http://localhost:8081")

        val ex = assertThrows(WebApplicationException::class.java) {
            router.forMagazijn(onbekendeOin)
        }

        assertEquals(502, ex.response.status)
    }

    @Test
    fun `forMagazijn gooit 502 bij een niet-OIN-vormige magazijnId`() {
        // Een magazijnId die geen geldige OIN is kan nooit in het register staan;
        // zelfde topologie-mismatch-classificatie als een onbekende OIN.
        val router = routerMet(oinA to "http://localhost:8081")

        val ex = assertThrows(WebApplicationException::class.java) {
            router.forMagazijn("magazijn-a")
        }

        assertEquals(502, ex.response.status)
    }

    // NB: de `getOrElse`-tak in forMagazijn (RestClientBuilder.build() faalt → 502) is
    // defense-in-depth en bewust niet los getest: `build()` is lui en gooit niet op een
    // syntactisch geldige-maar-kromme URL. Zo'n URL surfacet pas bij de echte HTTP-call als
    // ProcessingException → 502 via mapUpstreamFout (gedekt in de service-faulttests). De
    // tak vangt enkel een RestClientDefinitionException die een config-URL niet kan uitlokken.
}
