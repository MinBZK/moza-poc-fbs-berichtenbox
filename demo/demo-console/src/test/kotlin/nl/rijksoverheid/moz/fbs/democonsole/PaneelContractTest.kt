package nl.rijksoverheid.moz.fbs.democonsole

import com.fasterxml.jackson.databind.ObjectMapper
import io.quarkus.test.Mock
import io.quarkus.test.common.http.TestHTTPResource
import io.quarkus.test.junit.QuarkusTest
import jakarta.inject.Singleton
import nl.rijksoverheid.moz.fbs.democonsole.storing.StoringService
import nl.rijksoverheid.moz.fbs.democonsole.storing.Storingstoestand
import nl.rijksoverheid.moz.fbs.democonsole.storing.ToxiproxyRegister
import nl.rijksoverheid.moz.fbs.democonsole.omgeving.OmgevingConfig
import nl.rijksoverheid.moz.fbs.democonsole.simulator.SimulatorBeheerClient
import nl.rijksoverheid.moz.fbs.democonsole.simulator.SimulatorService
import nl.rijksoverheid.moz.fbs.democonsole.simulator.SimulatorStand
import org.eclipse.microprofile.rest.client.inject.RestClient
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File
import java.net.URI
import java.net.URL
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse

/**
 * De afspraak tussen het bedieningspaneel en de console, over HTTP. `bediening.js` hangt aan drie
 * dingen die nergens anders vastliggen: de paden, de kleine letters van de storingstoestanden
 * (het paneel filtert op de letterlijke tekst `normaal`) en de sleutels `actief`/`totaal`. Alle
 * drie falen stil — een verschoven pad geeft een chip "onbekend", een `"NORMAAL"` in hoofdletters
 * kleurt élke proxy als afwijkend — dus zonder deze test merkt niemand het tot de dag van de demo.
 *
 * De services zijn vervangen door vaste dubbels: hun logica heeft eigen unittests, en Toxiproxy en
 * de simulator draaien hier niet. Wat overblijft is precies wat we willen pinnen — route, status,
 * content-type en de vorm van het JSON.
 */
@QuarkusTest
class PaneelContractTest {

    @TestHTTPResource("/api/demo/storing")
    lateinit var storingUrl: URL

    @TestHTTPResource("/api/demo/simulator")
    lateinit var simulatorUrl: URL

    @TestHTTPResource("/api/demo/omgeving")
    lateinit var omgevingUrl: URL

    @TestHTTPResource("/api/demo/personas")
    lateinit var personasUrl: URL

    @TestHTTPResource("/")
    lateinit var basis: URL

    private fun haal(url: URL): HttpResponse<String> =
        HttpClient.newHttpClient().send(
            HttpRequest.newBuilder(url.toURI()).GET().build(),
            HttpResponse.BodyHandlers.ofString(),
        )

    private fun haalJson(url: URL): String {
        val respons = haal(url)

        assertEquals(200, respons.statusCode(), "onverwachte status voor $url")
        assertTrue(
            respons.headers().firstValue("content-type").orElse("").startsWith("application/json"),
            "onverwacht content-type voor $url",
        )

        return respons.body()
    }

    @Test
    fun `de storingen komen per proxy in kleine letters over de lijn`() {
        assertEquals(
            """{"magazijn-a":"normaal","magazijn-b":"traag","redis":"uit","profiel":"onbekend"}""",
            haalJson(storingUrl),
        )
    }

    @Test
    fun `de simulator levert de sleutels actief en totaal`() {
        assertEquals("""{"actief":3,"totaal":12}""", haalJson(simulatorUrl))
    }

    @Test
    fun `de omgeving meldt of er een simulator is`() {
        // Het paneel beslist hierop of het de magazijnen-chip toont en of het dat endpoint pollt.
        assertTrue(
            haalJson(omgevingUrl).contains(""""simulator":"""),
            "veldnaam simulator ontbreekt in de omgeving-respons",
        )
    }

    @Test
    fun `de omgeving draagt de persona-lijst voor de twee pagina's van deze module`() {
        // Het paneel en de wegwerp-berichtenbox lezen hem hieruit; zonder dit veld blijft hun
        // keuzelijst leeg en meldt de pagina dat er niets is ingericht. Op de geparste boom, want
        // een assertie op ruwe tekst hangt aan de veldvolgorde en aan een niet-lege lijst.
        val personas = ObjectMapper().readTree(haalJson(omgevingUrl)).path("personas")

        assertTrue(personas.isArray, "veld personas ontbreekt of is geen lijst")
        personas.forEach {
            assertEquals(setOf("id", "label", "ontvanger", "bron"), it.fieldNames().asSequence().toSet())
        }
    }

    @Test
    fun `de omgeving draagt de persona's waarvoor het paneel een los bericht kan plaatsen`() {
        // Een eigen lijst naast `personas`: alleen wie een magazijn heeft kan een bericht krijgen,
        // en de knop stuurt de id — niet het identificatienummer, dat hoort niet in een URL.
        val doelen = ObjectMapper().readTree(haalJson(omgevingUrl)).path("berichtPersonas")

        assertTrue(doelen.isArray, "veld berichtPersonas ontbreekt of is geen lijst")
        assertTrue(!doelen.isEmpty, "geen enkele persona om een bericht voor te plaatsen")
        doelen.forEach { assertEquals(setOf("id", "label"), it.fieldNames().asSequence().toSet()) }
    }

    @Test
    fun `een bericht voor een onbekende persona geeft 404 en niet een lege 500`() {
        // De knop faalt dan met een leesbare melding in plaats van met de regel die het paneel voor
        // elke storing toont; er gaat bovendien geen aanlevering naar een magazijn.
        val respons = HttpClient.newHttpClient().send(
            HttpRequest.newBuilder(URI.create(basis.toString().removeSuffix("/") + "/api/demo/bericht?persona=bestaat-niet"))
                .POST(HttpRequest.BodyPublishers.noBody())
                .build(),
            HttpResponse.BodyHandlers.ofString(),
        )

        assertEquals(404, respons.statusCode())
        assertTrue(respons.body().contains("bestaat-niet"), "de melding hoort de gevraagde persona te noemen")
    }

    @Test
    fun `deze module beantwoordt het personas-adres niet`() {
        // Dat adres hoort bij de personadienst. Zouden beide het beantwoorden, dan levert een proxy
        // die per ongeluk hierheen wijst hetzelfde antwoord en valt de scheiding stil weg.
        assertEquals(404, haal(personasUrl).statusCode())
    }

    @Test
    fun `de omgeving levert het adres van de berichtenbox`() {
        // Het paneel toont de berichtenbox in een frame en kent zelf alleen het lokale pad achter
        // de demo-proxy. Ontbreekt dit veld, dan valt het frame op een gedeelde omgeving terug op
        // een pad dat daar niet bestaat en blijft de berichtenbox onzichtbaar.
        assertTrue(
            haalJson(omgevingUrl).contains(""""berichtenboxUrl":"""),
            "veldnaam berichtenboxUrl ontbreekt in de omgeving-respons",
        )
    }

    /**
     * Elk pad achter een knop moet in deze applicatie op een route uitkomen.
     *
     * De knop "Persona's" wees een tijd lang naar `/api/demo/personas`, dat deze module juist met
     * 404 beantwoordt; achter de demo-proxy werkte hij, rechtstreeks op poort 8095 en op een
     * gedeelde omgeving niet. Dat leest als een kapotte keten terwijl er niets stuk is.
     *
     * Met `DELETE`, een methode die geen enkele resource van deze module aanbiedt. De router matcht
     * dan wél het pad en antwoordt met 405 als het bestaat en 404 als het niet bestaat, zonder ook
     * maar één resource-methode uit te voeren — anders zou deze test de magazijnen legen. `OPTIONS`
     * kan dat niet: dat beantwoordt Quarkus zelf met 200, ook voor een pad dat nergens op uitkomt.
     *
     * Query-strings en de `{veld}`-vorm die het paneel zelf invult gaan eraf; padparameters krijgen
     * een waarde die alleen hoeft te routeren.
     */
    @Test
    fun `elk pad achter een knop komt uit op een route van deze applicatie`() {
        val paden = Regex("""data-pad="([^"]+)"""")
            .findAll(File("src/main/resources/META-INF/resources/index.html").readText())
            .map { it.groupValues[1].substringBefore('?').replace(Regex("""\{[^}]+}"""), "1") }
            .toSet()

        assertTrue(paden.isNotEmpty(), "geen enkele data-pad gevonden in index.html")

        // Eerst bewijzen dat de meting discrimineert. Antwoordt de router overal hetzelfde, dan
        // slaagt de controle hieronder ook voor een pad dat niet bestaat en bewaakt hij niets.
        assertEquals(404, zonderUitvoeren("/api/demo/bestaat-niet"), "onbekende route wordt niet herkend")
        assertEquals(404, zonderUitvoeren("/api/demo/personas"), "uitgeschakelde route wordt niet herkend")

        assertEquals(
            emptyList<String>(),
            paden.filter { zonderUitvoeren(it) == 404 },
            "knoppen die naar een niet-bestaande route wijzen",
        )
    }

    private fun zonderUitvoeren(pad: String): Int = HttpClient.newHttpClient().send(
        HttpRequest.newBuilder(URI.create(basis.toString().removeSuffix("/") + pad))
            .method("DELETE", HttpRequest.BodyPublishers.noBody())
            .build(),
        HttpResponse.BodyHandlers.discarding(),
    ).statusCode()

    @Test
    fun `de omgeving meldt of de sessiecache bereikbaar is`() {
        // Het paneel laat de sessie-groep hierop weg; ontbreekt het veld, dan blijft een knop
        // staan die op een omgeving zonder netwerkregel gegarandeerd faalt.
        assertTrue(
            haalJson(omgevingUrl).contains(""""sessiecache":"""),
            "veldnaam sessiecache ontbreekt in de omgeving-respons",
        )
    }
}

/**
 * Vaste toestand in plaats van Toxiproxy: alle vier de waarden komen langs, zodat de test rood
 * wordt zodra er één anders over de lijn gaat.
 *
 * `@Singleton` en niet `@ApplicationScoped`: een normaal-scoped bean wordt geproxyd en vraagt
 * daarvoor een no-args-constructor, die een subklasse van een service mét constructorparameters
 * niet kan hebben. Voor een testdubbel voegt lazy proxying niets toe.
 */
@Mock
@Singleton
class VasteStoringService(register: ToxiproxyRegister) : StoringService(register) {

    override fun status(): Map<String, Storingstoestand> = linkedMapOf(
        "magazijn-a" to Storingstoestand.NORMAAL,
        "magazijn-b" to Storingstoestand.TRAAG,
        "redis" to Storingstoestand.UIT,
        "profiel" to Storingstoestand.ONBEKEND,
    )
}

/** Vaste telling in plaats van de simulator; alleen de vorm van het antwoord doet er hier toe. */
@Mock
@Singleton
class VasteSimulatorService(
    @RestClient beheer: SimulatorBeheerClient,
    omgeving: OmgevingConfig,
) : SimulatorService(beheer, omgeving) {

    override fun status(): SimulatorStand = SimulatorStand(actief = 3, totaal = 12)
}
