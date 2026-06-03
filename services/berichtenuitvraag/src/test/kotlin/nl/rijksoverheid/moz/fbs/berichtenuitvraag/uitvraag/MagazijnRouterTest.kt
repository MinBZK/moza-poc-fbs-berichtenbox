package nl.rijksoverheid.moz.fbs.berichtenuitvraag.uitvraag

import io.quarkus.runtime.StartupEvent
import io.quarkus.test.junit.QuarkusTest
import jakarta.ws.rs.WebApplicationException
import org.junit.jupiter.api.Assertions.assertDoesNotThrow
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import java.time.Duration

// @QuarkusTest zodat de rest-client-runtime aanwezig is; `RestClientBuilder.build`
// heeft die nodig om een client-proxy op te bouwen.
@QuarkusTest
class MagazijnRouterTest {

    private fun routerMet(urls: Map<String, String>): MagazijnRouter =
        MagazijnRouter(
            object : MagazijnenConfig {
                override fun urls(): Map<String, String> = urls
                override fun client(): MagazijnenConfig.Client = object : MagazijnenConfig.Client {
                    override fun connectTimeout(): Duration = Duration.ofSeconds(2)
                    override fun readTimeout(): Duration = Duration.ofSeconds(10)
                }
            },
        )

    @Test
    fun `forMagazijn bouwt een client voor een bekende magazijnId`() {
        val router = routerMet(mapOf("magazijn-a" to "http://localhost:8081"))

        assertNotNull(router.forMagazijn("magazijn-a"))
    }

    @Test
    fun `forMagazijn hergebruikt dezelfde client-instance per magazijnId`() {
        val router = routerMet(mapOf("magazijn-a" to "http://localhost:8081"))

        val eerste = router.forMagazijn("magazijn-a")
        val tweede = router.forMagazijn("magazijn-a")

        assertSame(eerste, tweede)
    }

    @Test
    fun `forMagazijn gooit 502 bij een onbekende magazijnId`() {
        val router = routerMet(mapOf("magazijn-a" to "http://localhost:8081"))

        val ex = assertThrows(WebApplicationException::class.java) {
            router.forMagazijn("magazijn-onbekend")
        }

        assertEquals(502, ex.response.status)
    }

    // NB: de `getOrElse`-tak in forMagazijn (RestClientBuilder.build() faalt → 502) is
    // defense-in-depth en bewust niet los getest: `build()` is lui en gooit niet op een
    // syntactisch geldige-maar-kromme URL. Zo'n URL surfacet pas bij de echte HTTP-call als
    // ProcessingException → 502 via mapUpstreamFout (gedekt in de service-faulttests). De
    // tak vangt enkel een RestClientDefinitionException die een config-URL niet kan uitlokken.

    @Test
    fun `valideerConfigBijOpstart faalt bij lege magazijn-config`() {
        val router = routerMet(emptyMap())

        assertThrows(IllegalArgumentException::class.java) {
            router.valideerConfigBijOpstart(StartupEvent())
        }
    }

    @Test
    fun `valideerConfigBijOpstart faalt bij een niet-http(s) URL`() {
        val router = routerMet(mapOf("magazijn-a" to "ftp://host/path"))

        assertThrows(IllegalArgumentException::class.java) {
            router.valideerConfigBijOpstart(StartupEvent())
        }
    }

    @Test
    fun `valideerConfigBijOpstart faalt bij een onparseerbare URI`() {
        // Een spatie is een illegaal URI-teken → URI.create gooit; de check moet dat
        // omzetten naar een boot-blokkerende fout i.p.v. het door te laten naar runtime.
        val router = routerMet(mapOf("magazijn-a" to "ht tp://host"))

        assertThrows(IllegalStateException::class.java) {
            router.valideerConfigBijOpstart(StartupEvent())
        }
    }

    @Test
    fun `valideerConfigBijOpstart slaagt bij geldige http(s)-URLs`() {
        val router = routerMet(mapOf("magazijn-a" to "http://localhost:8081", "magazijn-b" to "https://magazijn.example"))

        assertDoesNotThrow {
            router.valideerConfigBijOpstart(StartupEvent())
        }
    }
}
