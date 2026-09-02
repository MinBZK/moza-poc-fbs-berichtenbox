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

    /** Zoals Toxiproxy hem teruggeeft: gebonden op `[::]`, niet op wat er gepost werd. */
    private fun zoalsGebonden(poort: String, upstream: String) =
        ProxyStatus(enabled = true, listen = "[::]:$poort", upstream = upstream)

    @Test
    fun `een ontbrekende proxy wordt aangemaakt op zijn eigen instantie`() {
        every { eerste.proxies() } returns emptyMap()
        every { tweede.proxies() } returns emptyMap()
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
    fun `een proxy die klopt blijft ongemoeid`() {
        // De normale ronde, en lokaal élke ronde: compose zette de proxies al uit proxies.json.
        // Zou dit toch aanmaken of herbouwen, dan brak elke dertig seconden de lopende demo.
        every { eerste.proxies() } returns mapOf("profiel" to zoalsGebonden("18089", "profiel-service:8080"))

        ProxyBootstrap(registerMet("profiel" to eerste), testConfig("profiel" to profiel)).reconcile()

        verify(exactly = 0) { eerste.maakProxy(any()) }
        verify(exactly = 0) { eerste.verwijderProxy(any()) }
    }

    @Test
    fun `het bind-adres telt niet als afwijking, alleen de poort`() {
        // Toxiproxy antwoordt met `[::]:18089` op een gepostte `0.0.0.0:18089`. Vergelijkt de
        // reconcile die strings letterlijk, dan herbouwt hij elke proxy elke ronde.
        every { eerste.proxies() } returns mapOf(
            "profiel" to ProxyStatus(enabled = true, listen = "[::]:18089", upstream = "profiel-service:8080"),
        )

        ProxyBootstrap(registerMet("profiel" to eerste), testConfig("profiel" to profiel)).reconcile()

        verify(exactly = 0) { eerste.verwijderProxy(any()) }
    }

    @Test
    fun `een proxy met de verkeerde upstream wordt opnieuw gebouwd`() {
        // De faalwijze waarvoor dit mechanisme bestaat: een proxy die naar de upstream van een
        // ándere deployment wijst. Zonder deze vergelijking blijft die eeuwig staan.
        every { eerste.proxies() } returns mapOf("profiel" to zoalsGebonden("18089", "test-profiel:8080"))
        every { eerste.verwijderProxy(any()) } returns respons(204)
        every { eerste.maakProxy(any()) } returns respons(201)

        ProxyBootstrap(registerMet("profiel" to eerste), testConfig("profiel" to profiel)).reconcile()

        verify { eerste.verwijderProxy("profiel") }
        verify { eerste.maakProxy(ProxyVerzoek("profiel", "0.0.0.0:18089", "profiel-service:8080")) }
    }

    @Test
    fun `een proxy op de verkeerde poort wordt opnieuw gebouwd`() {
        every { eerste.proxies() } returns mapOf("profiel" to zoalsGebonden("19999", "profiel-service:8080"))
        every { eerste.verwijderProxy(any()) } returns respons(204)
        every { eerste.maakProxy(any()) } returns respons(201)

        ProxyBootstrap(registerMet("profiel" to eerste), testConfig("profiel" to profiel)).reconcile()

        verify { eerste.verwijderProxy("profiel") }
        verify { eerste.maakProxy(any()) }
    }

    @Test
    fun `een uitgezette proxy die klopt blijft uit`() {
        // Een storing die iemand bewust aanzette mag geen halve minuut later vanzelf verdwijnen.
        every { eerste.proxies() } returns mapOf(
            "profiel" to ProxyStatus(enabled = false, listen = "[::]:18089", upstream = "profiel-service:8080"),
        )

        ProxyBootstrap(registerMet("profiel" to eerste), testConfig("profiel" to profiel)).reconcile()

        verify(exactly = 0) { eerste.verwijderProxy(any()) }
        verify(exactly = 0) { eerste.maakProxy(any()) }
    }

    @Test
    fun `409 bij het aanmaken is geen fout`() {
        // Een race met een andere ronde of met compose; de volgende ronde vergelijkt hem alsnog.
        every { eerste.proxies() } returns emptyMap()
        every { eerste.maakProxy(any()) } returns respons(409)

        ProxyBootstrap(registerMet("profiel" to eerste), testConfig("profiel" to profiel)).reconcile()

        verify(exactly = 1) { eerste.maakProxy(any()) }
    }

    @Test
    fun `een onbereikbare instantie houdt de overige proxies niet tegen`() {
        // Op ZAD staan de instanties in verschillende projecten; één weggevallen Toxiproxy mag de
        // andere stromen niet ongemoeid laten, want dan blijft de halve keten dood na een herstart.
        every { eerste.proxies() } throws ProcessingException("connection refused")
        every { tweede.proxies() } returns emptyMap()
        every { tweede.maakProxy(any()) } returns respons(201)

        ProxyBootstrap(
            registerMet("profiel" to eerste, "redis" to tweede),
            testConfig("profiel" to profiel, "redis" to redis),
        ).reconcile()

        verify { tweede.maakProxy(ProxyVerzoek("redis", "0.0.0.0:16379", "redis:6379")) }
    }

    @Test
    fun `een mislukte verwijdering blokkeert de overige instanties evenmin`() {
        every { eerste.proxies() } returns mapOf("profiel" to zoalsGebonden("18089", "test-profiel:8080"))
        every { eerste.verwijderProxy(any()) } returns respons(500)
        every { tweede.proxies() } returns emptyMap()
        every { tweede.maakProxy(any()) } returns respons(201)

        ProxyBootstrap(
            registerMet("profiel" to eerste, "redis" to tweede),
            testConfig("profiel" to profiel, "redis" to redis),
        ).reconcile()

        verify(exactly = 0) { eerste.maakProxy(any()) }
        verify { tweede.maakProxy(any()) }
    }

    @Test
    fun `een mislukte verwijdering blokkeert de overige proxies op dezelfde instantie evenmin`() {
        // Lokaal dragen alle zes proxies dezelfde Toxiproxy. Vangt de bootstrap per instantie in
        // plaats van per proxy, dan slaat één mislukte herbouw alles wat erná komt elke ronde
        // opnieuw over — en blijft de stroom van die vijf dood zonder dat iets ze noemt.
        every { eerste.proxies() } returns mapOf("profiel" to zoalsGebonden("18089", "test-profiel:8080"))
        every { eerste.verwijderProxy("profiel") } returns respons(500)
        every { eerste.maakProxy(any()) } returns respons(201)

        ProxyBootstrap(
            registerMet("profiel" to eerste, "redis" to eerste),
            testConfig("profiel" to profiel, "redis" to redis.copy(url = "http://een:8474")),
        ).reconcile()

        verify { eerste.maakProxy(ProxyVerzoek("redis", "0.0.0.0:16379", "redis:6379")) }
        verify(exactly = 0) { eerste.maakProxy(ProxyVerzoek("profiel", "0.0.0.0:18089", "profiel-service:8080")) }
    }

    @Test
    fun `een onleesbare instantie levert één melding en geen poging per proxy`() {
        // Komt er niets uit het uitlezen, dan is er over geen enkele proxy van die instantie iets
        // te zeggen; hem dan tóch per proxy proberen levert alleen een rij identieke fouten op.
        every { eerste.proxies() } throws ProcessingException("connection refused")

        ProxyBootstrap(
            registerMet("profiel" to eerste, "redis" to eerste),
            testConfig("profiel" to profiel, "redis" to redis.copy(url = "http://een:8474")),
        ).reconcile()

        verify(exactly = 0) { eerste.maakProxy(any()) }
    }

    @Test
    fun `een niet-2xx-antwoord bij het aanmaken blokkeert de overige instanties niet`() {
        every { eerste.proxies() } returns emptyMap()
        every { eerste.maakProxy(any()) } returns respons(500)
        every { tweede.proxies() } returns emptyMap()
        every { tweede.maakProxy(any()) } returns respons(201)

        ProxyBootstrap(
            registerMet("profiel" to eerste, "redis" to tweede),
            testConfig("profiel" to profiel, "redis" to redis),
        ).reconcile()

        verify { tweede.maakProxy(ProxyVerzoek("redis", "0.0.0.0:16379", "redis:6379")) }
    }

    @Test
    fun `proxies op dezelfde instantie kosten samen een enkele leesronde`() {
        // Lokaal staan alle proxies op één Toxiproxy. Per proxy lezen zou die instantie elke ronde
        // net zo vaak bevragen als er proxies zijn.
        every { eerste.proxies() } returns emptyMap()
        every { eerste.maakProxy(any()) } returns respons(201)

        ProxyBootstrap(
            registerMet("profiel" to eerste, "redis" to eerste),
            testConfig("profiel" to profiel, "redis" to redis.copy(url = "http://een:8474")),
        ).reconcile()

        verify(exactly = 1) { eerste.proxies() }
        verify(exactly = 2) { eerste.maakProxy(any()) }
    }

    @Test
    fun `een uitgezette proxy wordt niet aangemaakt`() {
        // Lege url = de omgeving bedient deze stroom niet. Zou de bootstrap hem toch aanmaken, dan
        // zocht hij een instantie die het register niet kent en faalde elke ronde.
        val register = registerMet("profiel" to eerste)
        every { eerste.proxies() } returns emptyMap()
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
