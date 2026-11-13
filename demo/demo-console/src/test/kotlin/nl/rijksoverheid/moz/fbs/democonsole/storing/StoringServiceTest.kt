package nl.rijksoverheid.moz.fbs.democonsole.storing

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import jakarta.ws.rs.core.Response
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class StoringServiceTest {

    private val instantie = mockk<ToxiproxyClient>(relaxed = false)
    private val tweede = mockk<ToxiproxyClient>(relaxed = false)

    // Vóór `service`: Kotlin initialiseert velden in volgorde. Relaxed, dus bewaakt() is false en
    // een test zonder eigen aankondiging ziet geen extra uitlezing.
    private val stil = mockk<StoringAankondiging>(relaxed = true)

    private fun registerMet(vararg clients: Pair<String, ToxiproxyClient>) =
        mockk<ToxiproxyRegister> {
            every { namen() } returns clients.map { it.first }.toSet()
            every { client(any()) } answers { clients.toMap()[firstArg()] ?: error("niet geconfigureerd") }
        }

    private val service = StoringService(stil, registerMet("magazijn-a" to instantie, "magazijn-b" to instantie))

    // Response via MockK (relaxed sluit .use()/close() af) i.p.v. Response.ok().build(),
    // dat een JAX-RS RuntimeDelegate vereist die in een pure unittest kan ontbreken.
    private fun respons(code: Int) = mockk<Response>(relaxed = true) { every { status } returns code }

    private fun ok() = respons(200)

    private fun noContent() = respons(204)

    @Test
    fun `traag voegt een latency-toxic van 6000ms toe`() {
        every { instantie.voegToxicToe(any(), any()) } returns ok()
        every { instantie.proxies() } returns mapOf(
            "magazijn-a" to ProxyStatus(enabled = true, toxics = listOf(ToxicStatus("latency_downstream"))),
        )

        service.traag("magazijn-a", 6000)

        verify { instantie.voegToxicToe("magazijn-a", ToxicVerzoek("latency", mapOf("latency" to 6000))) }
    }

    @Test
    fun `uit schakelt de proxy uit`() {
        every { instantie.zetProxy(any(), any()) } returns ok()
        every { instantie.proxies() } returns mapOf("magazijn-b" to ProxyStatus(enabled = false))

        service.uit("magazijn-b")

        verify { instantie.zetProxy("magazijn-b", ProxyPatch(enabled = false)) }
    }

    @Test
    fun `reset schakelt uitgeschakelde proxies weer in en wist toxics`() {
        every { instantie.proxies() } returns mapOf(
            "magazijn-a" to ProxyStatus(enabled = false, toxics = listOf(ToxicStatus("latency_downstream"))),
            "magazijn-b" to ProxyStatus(enabled = true, toxics = emptyList()),
        )
        every { instantie.zetProxy(any(), any()) } returns ok()
        every { instantie.verwijderToxic(any(), any()) } returns noContent()

        service.reset()

        verify { instantie.zetProxy("magazijn-a", ProxyPatch(enabled = true)) }
        verify { instantie.verwijderToxic("magazijn-a", "latency_downstream") }
        verify(exactly = 0) { instantie.zetProxy("magazijn-b", any()) }
    }

    @Test
    fun `reset faalt als Toxiproxy geen enkele proxy kent`() {
        // Toxiproxy start gezond op met nul proxies, terwijl al het uitvraag- en magazijnverkeer
        // erdoorheen loopt. De lus over een lege map zou stil slagen en "alles normaal" bevestigen
        // terwijl de keten dood is.
        every { instantie.proxies() } returns emptyMap()

        val fout = assertThrows(IllegalStateException::class.java) { service.reset() }

        assertTrue(fout.message!!.contains("proxies.json"), "melding moet de lokale oorzaak noemen, was: ${fout.message}")
        assertTrue(fout.message!!.contains("herstartte"), "melding moet ook de ZAD-oorzaak noemen, was: ${fout.message}")
    }

    @Test
    fun `een niet-2xx-respons van Toxiproxy faalt met een duidelijke melding`() {
        every { instantie.zetProxy(any(), any()) } returns respons(404)

        try {
            service.uit("magazijn-a")
            throw AssertionError("verwacht een IllegalStateException")
        } catch (fout: IllegalStateException) {
            check(fout.message!!.contains("404"))
        }
    }

    @Test
    fun `reset gaat elke instantie langs`() {
        // Op ZAD staat elke stroom op zijn eigen Toxiproxy; een reset die er maar één langsgaat
        // laat de rest van de storingen aan staan.
        every { instantie.proxies() } returns mapOf("profiel" to ProxyStatus(enabled = false))
        every { tweede.proxies() } returns mapOf("redis" to ProxyStatus(enabled = false))
        every { instantie.zetProxy(any(), any()) } returns ok()
        every { tweede.zetProxy(any(), any()) } returns ok()

        StoringService(stil, registerMet("profiel" to instantie, "redis" to tweede)).reset()

        verify { instantie.zetProxy("profiel", ProxyPatch(enabled = true)) }
        verify { tweede.zetProxy("redis", ProxyPatch(enabled = true)) }
    }

    @Test
    fun `reset faalt zodra een van de instanties geen enkele proxy kent`() {
        every { instantie.proxies() } returns mapOf("profiel" to ProxyStatus(enabled = true))
        every { tweede.proxies() } returns emptyMap()

        val fout = assertThrows(IllegalStateException::class.java) {
            StoringService(stil, registerMet("profiel" to instantie, "redis" to tweede)).reset()
        }

        assertTrue(fout.message!!.contains("proxies.json"), "melding moet de lokale oorzaak noemen, was: ${fout.message}")
        assertTrue(fout.message!!.contains("herstartte"), "melding moet ook de ZAD-oorzaak noemen, was: ${fout.message}")
    }

    @Test
    fun `reset faalt zodra Toxiproxy een geconfigureerde proxy niet kent`() {
        // De gevaarlijkste tussenstand: Toxiproxy kent er één van de twee. "Herstel wat er is" zou
        // 200 geven en het paneel "alles normaal" laten melden, terwijl al het verkeer van de
        // ontbrekende stroom nergens doorheen loopt.
        every { instantie.proxies() } returns mapOf("magazijn-a" to ProxyStatus(enabled = true))

        val fout = assertThrows(IllegalStateException::class.java) { service.reset() }

        assertTrue(
            fout.message!!.contains("magazijn-b"),
            "melding moet de ontbrekende proxy noemen, was: ${fout.message}",
        )
    }

    @Test
    fun `reset herstelt de proxies die er wel zijn, ook als er een ontbreekt`() {
        // Andersom zou één ontbrekende proxy de storingen op alle overige laten staan — en juist
        // deze knop wordt ingedrukt wanneer er al iets niet klopt.
        every { instantie.proxies() } returns mapOf(
            "magazijn-a" to ProxyStatus(enabled = false, toxics = listOf(ToxicStatus("latency_downstream"))),
        )
        every { instantie.zetProxy(any(), any()) } returns ok()
        every { instantie.verwijderToxic(any(), any()) } returns noContent()

        assertThrows(IllegalStateException::class.java) { service.reset() }

        verify { instantie.zetProxy("magazijn-a", ProxyPatch(enabled = true)) }
        verify { instantie.verwijderToxic("magazijn-a", "latency_downstream") }
    }

    @Test
    fun `reset faalt als er geen enkele proxy geconfigureerd is`() {
        // Lege TOXIPROXY_*_URL's schakelen elke proxy uit; het register is dan leeg. Zonder guard
        // zou de lege forEach niets doen en de resource "alles normaal" laten melden.
        val fout = assertThrows(IllegalStateException::class.java) { StoringService(stil, registerMet()).reset() }

        assertTrue(
            fout.message!!.contains("Geen enkele proxy geconfigureerd"),
            "melding moet naar de oorzaak wijzen, was: ${fout.message}",
        )
    }

    @Test
    fun `reset meldt een fout zonder message met de exceptienaam, in plaats van hem stil te laten vallen`() {
        // Een IllegalStateException zonder message zou anders door mapNotNull() verdwijnen: de
        // fouten-lijst blijft dan leeg terwijl de instantie wel degelijk faalde, en reset() meldt
        // ten onrechte "alles gelukt".
        every { instantie.proxies() } throws IllegalStateException()

        val fout = assertThrows(IllegalStateException::class.java) { service.reset() }

        assertTrue(
            fout.message!!.contains("IllegalStateException"),
            "melding moet de fout zonder message toch tonen, was: ${fout.message}",
        )
    }

    @Test
    fun `reset herstelt een gezonde instantie ook als een eerdere instantie geen proxies kent`() {
        // Eén kapotte instantie mag de rest niet gijzelen: reset() gaat alle instanties langs en
        // meldt de fouten pas aan het eind, verzameld.
        every { instantie.proxies() } returns emptyMap()
        every { tweede.proxies() } returns mapOf("redis" to ProxyStatus(enabled = false, toxics = emptyList()))
        every { tweede.zetProxy(any(), any()) } returns ok()

        assertThrows(IllegalStateException::class.java) {
            StoringService(stil, registerMet("profiel" to instantie, "redis" to tweede)).reset()
        }

        verify { tweede.zetProxy("redis", ProxyPatch(enabled = true)) }
    }

    @Test
    fun `status meldt per proxy of hij normaal, traag of uit staat`() {
        every { instantie.proxies() } returns mapOf(
            "magazijn-a" to ProxyStatus(enabled = true, toxics = emptyList()),
            "magazijn-b" to ProxyStatus(enabled = true, toxics = listOf(ToxicStatus("latency_downstream"))),
            "redis" to ProxyStatus(enabled = false, toxics = emptyList()),
        )

        val status = StoringService(
            stil,
            registerMet("magazijn-a" to instantie, "magazijn-b" to instantie, "redis" to instantie),
        ).status()

        assertEquals(
            mapOf(
                "magazijn-a" to Storingstoestand.NORMAAL,
                "magazijn-b" to Storingstoestand.TRAAG,
                "redis" to Storingstoestand.UIT,
            ),
            status,
        )
    }

    @Test
    fun `een uitgeschakelde proxy met een toxic meldt uit, niet traag`() {
        every { instantie.proxies() } returns mapOf(
            "magazijn-a" to ProxyStatus(enabled = false, toxics = listOf(ToxicStatus("latency_downstream"))),
        )

        assertEquals(Storingstoestand.UIT, service.status()["magazijn-a"])
    }

    @Test
    fun `status vraagt elke instantie eenmaal, niet eenmaal per proxy`() {
        // Het paneel pollt dit doorlopend; een aanroep per proxy vermenigvuldigt dat met het
        // aantal knoppen zonder dat er meer te weten valt.
        every { instantie.proxies() } returns mapOf(
            "magazijn-a" to ProxyStatus(enabled = true),
            "magazijn-b" to ProxyStatus(enabled = true),
        )

        service.status()

        verify(exactly = 1) { instantie.proxies() }
    }

    @Test
    fun `een proxy die Toxiproxy niet kent is onbekend, niet normaal`() {
        // Een naam die wel geconfigureerd is maar niet in proxies.json staat, laat verkeer nergens
        // langs. "Normaal" melden verbergt precies die misconfiguratie.
        every { instantie.proxies() } returns mapOf("magazijn-a" to ProxyStatus(enabled = true))

        assertEquals(Storingstoestand.ONBEKEND, service.status()["magazijn-b"])
    }

    @Test
    fun `een onbereikbare instantie maakt alleen zijn eigen proxies onbekend`() {
        every { instantie.proxies() } throws IllegalStateException("verbinding geweigerd")
        every { tweede.proxies() } returns mapOf("redis" to ProxyStatus(enabled = false))

        val status = StoringService(stil, registerMet("profiel" to instantie, "redis" to tweede)).status()

        assertEquals(mapOf("profiel" to Storingstoestand.ONBEKEND, "redis" to Storingstoestand.UIT), status)
    }

    @Test
    fun `status zonder geconfigureerde proxies levert een lege map en geen fout`() {
        assertEquals(emptyMap<String, Storingstoestand>(), StoringService(stil, registerMet()).status())
    }

    @Test
    fun `status met precies een geconfigureerde proxy levert alleen die proxy`() {
        // Onderscheidt "geeft alles terug wat Toxiproxy kent" van "geeft terug wat geconfigureerd
        // is": Toxiproxy kent hier twee proxies, het register maar een.
        every { instantie.proxies() } returns mapOf(
            "redis" to ProxyStatus(enabled = false),
            "profiel" to ProxyStatus(enabled = false),
        )

        assertEquals(
            mapOf("redis" to Storingstoestand.UIT),
            StoringService(stil, registerMet("redis" to instantie)).status(),
        )
    }

    @Test
    fun `status staat op alfabet, zodat de volgorde in het paneel niet springt`() {
        every { instantie.proxies() } returns mapOf(
            "redis" to ProxyStatus(enabled = true),
            "aanmeld" to ProxyStatus(enabled = true),
            "profiel" to ProxyStatus(enabled = true),
        )

        val status = StoringService(
            stil,
            registerMet("redis" to instantie, "aanmeld" to instantie, "profiel" to instantie),
        ).status()

        assertEquals(listOf("aanmeld", "profiel", "redis"), status.keys.toList())
    }

    @Test
    fun `elke toxic telt als traag, niet alleen latency`() {
        // De console zet zelf alleen latency, maar een met de hand toegevoegde toxic mag niet als
        // "normaal" doorgaan: er staat dan wel degelijk iets op de proxy.
        every { instantie.proxies() } returns mapOf(
            "magazijn-a" to ProxyStatus(enabled = true, toxics = listOf(ToxicStatus("bandwidth_downstream"))),
        )

        assertEquals(Storingstoestand.TRAAG, service.status()["magazijn-a"])
    }

    @Test
    fun `de toestand gaat in kleine letters over de lijn`() {
        // Het paneel filtert op de letterlijke tekst 'normaal'. Verdwijnt @JsonValue, dan
        // serialiseert Jackson "NORMAAL", telt elke proxy als afwijkend en staat de chip
        // permanent op rood — zonder dat een test rood wordt.
        val json = jacksonObjectMapper().writeValueAsString(
            mapOf(
                "magazijn-a" to Storingstoestand.NORMAAL,
                "magazijn-b" to Storingstoestand.TRAAG,
                "redis" to Storingstoestand.UIT,
                "profiel" to Storingstoestand.ONBEKEND,
            ),
        )

        assertEquals(
            """{"magazijn-a":"normaal","magazijn-b":"traag","redis":"uit","profiel":"onbekend"}""",
            json,
        )
    }

    @Test
    fun `proxies uit echt Toxiproxy-JSON leveren enabled en toxics op`() {
        // Parseert `toxics` niet, dan is de lijst altijd leeg en meldt elke traag-gezette proxy
        // NORMAAL — de enige richting die dit paneel niet mag hebben.
        val json = """
            {"magazijn-a":{"name":"magazijn-a","listen":"[::]:18090","upstream":"berichtenmagazijn-a:8090",
             "enabled":true,"toxics":[{"attributes":{"latency":6000,"jitter":0},"name":"latency_downstream",
             "type":"latency","stream":"downstream","toxicity":1}]}}
        """.trimIndent()

        val gelezen: Map<String, ProxyStatus> = jacksonObjectMapper().readValue(json)

        assertEquals(true, gelezen.getValue("magazijn-a").enabled)
        assertEquals(listOf("latency_downstream"), gelezen.getValue("magazijn-a").toxics.map { it.name })
    }

    @Test
    fun `uit op een bewaakte proxy kondigt de overgang aan met de toestand ervoor en erna`() {
        val aankondiging = mockk<StoringAankondiging>(relaxed = true) { every { bewaakt("redis") } returns true }
        every { instantie.zetProxy(any(), any()) } returns ok()
        every { instantie.proxies() } returnsMany listOf(
            mapOf("redis" to ProxyStatus(enabled = true)),
            mapOf("redis" to ProxyStatus(enabled = false)),
        )

        StoringService(aankondiging, registerMet("redis" to instantie)).uit("redis")

        verify { aankondiging.overgang("redis", Storingstoestand.NORMAAL, Storingstoestand.UIT) }
    }

    @Test
    fun `traag op een bewaakte proxy kondigt de overgang aan`() {
        val aankondiging = mockk<StoringAankondiging>(relaxed = true) { every { bewaakt("redis") } returns true }
        every { instantie.voegToxicToe(any(), any()) } returns ok()
        every { instantie.proxies() } returnsMany listOf(
            mapOf("redis" to ProxyStatus(enabled = true)),
            mapOf("redis" to ProxyStatus(enabled = true, toxics = listOf(ToxicStatus("latency_downstream")))),
        )

        StoringService(aankondiging, registerMet("redis" to instantie)).traag("redis", 6000)

        verify { aankondiging.overgang("redis", Storingstoestand.NORMAAL, Storingstoestand.TRAAG) }
    }

    @Test
    fun `een onleesbare toestand vooraf houdt de knop niet tegen`() {
        // Zonder vangnet zou een haperende uitlezing vóór de knop de storing zelf tegenhouden:
        // de aankondiging is bijzaak, de knop niet.
        val aankondiging = mockk<StoringAankondiging>(relaxed = true) { every { bewaakt("redis") } returns true }
        every { instantie.zetProxy(any(), any()) } returns ok()
        every { instantie.proxies() } throws IllegalStateException("weg") andThen
            mapOf("redis" to ProxyStatus(enabled = false))

        val toestand = StoringService(aankondiging, registerMet("redis" to instantie)).uit("redis")

        assertEquals(Storingstoestand.UIT, toestand)
        verify { aankondiging.overgang("redis", Storingstoestand.ONBEKEND, Storingstoestand.UIT) }
    }

    @Test
    fun `een knop zonder aankondiging leest Toxiproxy niet extra uit`() {
        every { instantie.zetProxy(any(), any()) } returns ok()
        every { instantie.proxies() } returns mapOf("magazijn-b" to ProxyStatus(enabled = false))

        service.uit("magazijn-b")

        verify(exactly = 1) { instantie.proxies() }
        verify(exactly = 0) { stil.overgang(any(), any(), any()) }
    }

    @Test
    fun `reset kondigt het herstel van een bewaakte proxy aan`() {
        val aankondiging = mockk<StoringAankondiging>(relaxed = true) { every { bewaakt("redis") } returns true }
        every { instantie.zetProxy(any(), any()) } returns ok()
        every { instantie.proxies() } returnsMany listOf(
            mapOf("redis" to ProxyStatus(enabled = false)),
            mapOf("redis" to ProxyStatus(enabled = false)),
            mapOf("redis" to ProxyStatus(enabled = true)),
        )

        StoringService(aankondiging, registerMet("redis" to instantie)).reset()

        verify { aankondiging.overgang("redis", Storingstoestand.UIT, Storingstoestand.NORMAAL) }
    }

    @Test
    fun `reset kondigt ook aan als het herstel elders mislukt`() {
        // De bewaking meldt het herstel van Redis hoe dan ook; dat een andere instantie niet
        // meewilde, mag die aankondiging niet opslokken.
        val aankondiging = mockk<StoringAankondiging>(relaxed = true) { every { bewaakt("redis") } returns true }
        every { instantie.zetProxy(any(), any()) } returns ok()
        every { instantie.proxies() } returnsMany listOf(
            mapOf("redis" to ProxyStatus(enabled = false)),
            mapOf("redis" to ProxyStatus(enabled = false)),
            mapOf("redis" to ProxyStatus(enabled = true)),
        )
        every { tweede.proxies() } throws IllegalStateException("weg")

        assertThrows(IllegalStateException::class.java) {
            StoringService(aankondiging, registerMet("redis" to instantie, "profiel" to tweede)).reset()
        }

        verify { aankondiging.overgang("redis", Storingstoestand.UIT, Storingstoestand.NORMAAL) }
    }
}
