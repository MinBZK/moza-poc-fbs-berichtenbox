package nl.rijksoverheid.moz.fbs.democonsole.storing

import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import jakarta.ws.rs.ProcessingException
import jakarta.ws.rs.core.Response
import org.junit.jupiter.api.Test

class ProxyBootstrapTest {

    private val eerste = mockk<ToxiproxyClient>(relaxed = false)
    private val tweede = mockk<ToxiproxyClient>(relaxed = false)

    private fun respons(code: Int) = mockk<Response>(relaxed = true) { every { status } returns code }

    private fun registerMet(vararg clients: Pair<String, ToxiproxyClient>) =
        mockk<ToxiproxyRegister> {
            every { client(any()) } answers { clients.toMap()[firstArg()] ?: error("niet geconfigureerd") }
        }

    private val profiel = TestInstantie(
        url = "http://een:8474",
        listen = "0.0.0.0:18089",
        upstream = "profiel-service:8080",
    )

    private val redis = TestInstantie(
        url = "http://twee:8474",
        listen = "0.0.0.0:16379",
        upstream = "redis:6379",
    )

    @Test
    fun `elke geconfigureerde proxy wordt aangemaakt op zijn eigen instantie`() {
        every { eerste.maakProxy(any()) } returns respons(201)
        every { tweede.maakProxy(any()) } returns respons(201)

        ProxyBootstrap(
            registerMet("profiel" to eerste, "redis" to tweede),
            testConfig("profiel" to profiel, "redis" to redis),
        ).reconcile()

        verify { eerste.maakProxy(ProxyVerzoek("profiel", "0.0.0.0:18089", "profiel-service:8080")) }
        verify { tweede.maakProxy(ProxyVerzoek("redis", "0.0.0.0:16379", "redis:6379")) }
    }

    @Test
    fun `een bestaande proxy levert 409 en dat is geen fout`() {
        // De normale uitkomst van elke ronde na de eerste, en van elke ronde lokaal waar compose de
        // proxies uit proxies.json zet. Zou dit als fout tellen, dan liep de log elke dertig
        // seconden vol en werd een échte storing onvindbaar.
        every { eerste.maakProxy(any()) } returns respons(409)

        ProxyBootstrap(registerMet("profiel" to eerste), testConfig("profiel" to profiel)).reconcile()

        verify(exactly = 1) { eerste.maakProxy(any()) }
    }

    @Test
    fun `een onbereikbare instantie houdt de overige proxies niet tegen`() {
        // Op ZAD staan de instanties in verschillende projecten; één weggevallen Toxiproxy mag de
        // andere stromen niet ongemoeid laten, want dan blijft de halve keten dood na een herstart.
        every { eerste.maakProxy(any()) } throws ProcessingException("connection refused")
        every { tweede.maakProxy(any()) } returns respons(201)

        ProxyBootstrap(
            registerMet("profiel" to eerste, "redis" to tweede),
            testConfig("profiel" to profiel, "redis" to redis),
        ).reconcile()

        verify { tweede.maakProxy(ProxyVerzoek("redis", "0.0.0.0:16379", "redis:6379")) }
    }

    @Test
    fun `een niet-2xx-antwoord blokkeert de overige proxies evenmin`() {
        every { eerste.maakProxy(any()) } returns respons(500)
        every { tweede.maakProxy(any()) } returns respons(201)

        ProxyBootstrap(
            registerMet("profiel" to eerste, "redis" to tweede),
            testConfig("profiel" to profiel, "redis" to redis),
        ).reconcile()

        verify { tweede.maakProxy(ProxyVerzoek("redis", "0.0.0.0:16379", "redis:6379")) }
    }

    @Test
    fun `een uitgezette proxy wordt niet aangemaakt`() {
        // Lege url = de omgeving bedient deze stroom niet. Zou de bootstrap hem toch aanmaken, dan
        // zocht hij een instantie die het register niet kent en faalde elke ronde.
        val register = registerMet("profiel" to eerste)
        every { eerste.maakProxy(any()) } returns respons(201)

        ProxyBootstrap(
            register,
            testConfig(
                "profiel" to profiel,
                "magazijn-a" to TestInstantie(url = "", listen = "0.0.0.0:18090", upstream = "magazijn-a:8090"),
            ),
        ).reconcile()

        verify(exactly = 1) { eerste.maakProxy(any()) }
        verify(exactly = 0) { register.client("magazijn-a") }
    }

    @Test
    fun `zonder geconfigureerde proxies gebeurt er niets`() {
        val register = registerMet()

        ProxyBootstrap(register, testConfig()).reconcile()

        verify(exactly = 0) { register.client(any()) }
    }
}
