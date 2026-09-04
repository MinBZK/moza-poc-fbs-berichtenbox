package nl.rijksoverheid.moz.fbs.democonsole

import com.fasterxml.jackson.databind.ObjectMapper
import io.quarkus.test.Mock
import io.quarkus.test.common.http.TestHTTPResource
import io.quarkus.test.junit.QuarkusTest
import jakarta.inject.Singleton
import nl.rijksoverheid.moz.fbs.democonsole.aanlever.AanleverResultaat
import nl.rijksoverheid.moz.fbs.democonsole.aanlever.AanleverService
import nl.rijksoverheid.moz.fbs.democonsole.generator.AanleverOpdracht
import nl.rijksoverheid.moz.fbs.democonsole.storing.StoringService
import nl.rijksoverheid.moz.fbs.democonsole.storing.Storingstoestand
import nl.rijksoverheid.moz.fbs.democonsole.storing.ToxiproxyRegister
import nl.rijksoverheid.moz.fbs.democonsole.tempo.TempoResource
import nl.rijksoverheid.moz.fbs.democonsole.tempo.TempoService
import nl.rijksoverheid.moz.fbs.democonsole.omgeving.OmgevingConfig
import nl.rijksoverheid.moz.fbs.democonsole.simulator.SimulatorBeheerClient
import nl.rijksoverheid.moz.fbs.democonsole.simulator.SimulatorService
import nl.rijksoverheid.moz.fbs.democonsole.simulator.SimulatorStand
import org.eclipse.microprofile.rest.client.inject.RestClient
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource
import org.junit.jupiter.params.provider.ValueSource
import java.io.File
import java.net.URI
import java.net.URL
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.util.concurrent.CopyOnWriteArrayList

/**
 * De afspraak tussen het bedieningspaneel en de console, over HTTP. `bediening.js` hangt aan een
 * reeks dingen die nergens anders vastliggen: de paden, de kleine letters van de storingstoestanden
 * (het paneel filtert op de letterlijke tekst `normaal`), de sleutels `actief`/`totaal`, de vorm van
 * de persona-lijsten en de statussen die een bedieningsfout onderscheiden. Ze falen allemaal stil —
 * een verschoven pad geeft een chip "onbekend", een `"NORMAAL"` in hoofdletters kleurt élke proxy
 * als afwijkend — dus zonder deze test merkt niemand het tot de dag van de demo.
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

    @TestHTTPResource("/api/demo/tempo")
    lateinit var tempoUrl: URL

    @TestHTTPResource("/")
    lateinit var basis: URL

    // Onvoorwaardelijk en niet in de testbody: het dubbel vervangt AanleverService voor élke
    // @QuarkusTest van deze module, dus de eerste test die een ander vulpad aanroept zou anders
    // stil in deze lijst bijschrijven en een ándere test rood maken.
    @BeforeEach
    @AfterEach
    fun leegDeOpdrachten() {
        VasteAanleverService.opdrachten.clear()
    }

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
    fun `berichtPersonas is de echte deelverzameling van personas, met alleen id en label`() {
        // Op de ingerichte personaset, want die draagt het tegenvoorbeeld: Grootbedrijf en Landelijk
        // Concern staan zonder magazijn in de configuratie. Een regressie naar "geef alle persona's
        // terug" zet hen in de keuzelijst en levert bij elke klik een weigering van het magazijn.
        val antwoord = ObjectMapper().readTree(haalJson(omgevingUrl))
        val doelen = antwoord.path("berichtPersonas")
        val alle = antwoord.path("personas").map { it.path("id").asText() }.toSet()
        val doelIds = doelen.map { it.path("id").asText() }.toSet()

        assertTrue(doelen.isArray, "veld berichtPersonas ontbreekt of is geen lijst")
        assertFalse(doelen.isEmpty, "geen enkele persona om een bericht voor te plaatsen")
        assertEquals(emptySet<String>(), doelIds - alle, "berichtPersonas hoort binnen personas te vallen")
        assertTrue(doelIds.size < alle.size, "er wordt niets gefilterd; het tegenvoorbeeld is weggevallen")
        assertEquals(emptySet<String>(), doelIds intersect setOf("grootbedrijf", "concern"))

        // Geen `ontvanger`: dit is de lijst die als queryparameter in een URL belandt.
        doelen.forEach { assertEquals(setOf("id", "label"), it.fieldNames().asSequence().toSet()) }
    }

    @Test
    fun `het label van een doelpersona is dat van dezelfde persona in personas`() {
        // Verwisseld compileert de mapping, waarna de knop de mensennaam als id verstuurt.
        val antwoord = ObjectMapper().readTree(haalJson(omgevingUrl))
        val labels = antwoord.path("personas").associate { it.path("id").asText() to it.path("label").asText() }

        val doelen = antwoord.path("berichtPersonas")

        assertFalse(doelen.isEmpty, "zonder doelpersona's toetst deze vergelijking niets")
        doelen.forEach { assertEquals(labels[it.path("id").asText()], it.path("label").asText()) }
    }

    @Test
    fun `een bericht voor een onbekende persona geeft 404 en niet een lege 500`() {
        // De knop faalt dan met een leesbare melding in plaats van met de regel die het paneel voor
        // elke storing toont; er gaat bovendien geen aanlevering naar een magazijn.
        val respons = plaatsBericht("?persona=bestaat-niet")

        assertEquals(404, respons.statusCode())
        assertTrue(respons.body().contains("bestaat-niet"), "de melding hoort de gevraagde persona te noemen")
        assertTrue(VasteAanleverService.opdrachten.isEmpty(), "een onbekende persona hoort niets aan te leveren")
    }

    /**
     * Een ontbrekende parameter is een ander faalpad dan een onbekende waarde: Kotlin maakt er
     * zonder eigen afhandeling een `NullPointerException` van, en dat wordt een HTTP 500 met een
     * interne melding. Een lege of witruimte-waarde hoort er hetzelfde uit te zien als een
     * ontbrekende, want voor de bediener is het dezelfde vergissing.
     */
    @ParameterizedTest
    @ValueSource(strings = ["", "?aantal=1", "?persona=", "?persona=%20"])
    fun `een bericht zonder bruikbare persona geeft 400 en geen 500`(query: String) {
        val respons = plaatsBericht(query)

        assertEquals(400, respons.statusCode(), "query '$query'")
        assertTrue(respons.body().contains("berichtPersonas"), "de melding hoort de weg terug te wijzen")
        assertTrue(VasteAanleverService.opdrachten.isEmpty(), "valideren hoort vóór aanleveren te gaan")
    }

    /**
     * De browser bewaakt deze grenzen ook, maar dat is geen contract: dit adres staat open op de
     * origin van het paneel. Nul zou een groene melding "0 van 0 aangeleverd" opleveren voor een
     * actie die niets deed.
     */
    @ParameterizedTest
    @MethodSource("buitenDeGrenzen")
    fun `een bericht met een aantal buiten de grenzen geeft 400`(aantal: Int) {
        assertEquals(400, plaatsBericht("?persona=pietersen&aantal=$aantal").statusCode())
        assertTrue(VasteAanleverService.opdrachten.isEmpty(), "een geweigerd aantal hoort niets aan te leveren")
    }

    /**
     * De grenzen zelf, en niet alleen wat erbuiten valt: `aantal !in 1 until MAX_GERICHTE_BERICHTEN`
     * zou álle gevallen hierboven even goed doorstaan, en dan geeft de knop een fout op precies de
     * bovengrens die het invoerveld aanbiedt.
     */
    @ParameterizedTest
    @MethodSource("opDeGrenzen")
    fun `een bericht op de grens van het toegestane aantal slaagt`(aantal: Int) {
        assertEquals(200, plaatsBericht("?persona=pietersen&aantal=$aantal").statusCode())
        assertEquals(aantal, VasteAanleverService.opdrachten.size)
    }

    /**
     * Zou de resource het aantal als `Int` laten injecteren, dan handelt JAX-RS een mislukte
     * omzetting af vóór de eerste regel van de methode — met een 404, dezelfde status die dit
     * endpoint voor een onbekende persona gebruikt. De bediener zoekt dan in de persona-lijst naar
     * een fout die in het aantal-veld zit.
     */
    @ParameterizedTest
    @ValueSource(strings = ["abc", "1.5", "3000000000"])
    fun `een bericht met een onleesbaar aantal geeft 400 en geen 404`(aantal: String) {
        val respons = plaatsBericht("?persona=pietersen&aantal=$aantal")

        assertEquals(400, respons.statusCode(), "aantal '$aantal'")
        assertTrue(respons.body().contains("aantal"), "de melding hoort het aantal-veld aan te wijzen")
        assertTrue(VasteAanleverService.opdrachten.isEmpty(), "een onleesbaar aantal hoort niets aan te leveren")
    }

    @Test
    fun `een aantal-parameter zonder waarde valt terug op de default`() {
        // Een lege waarde telt als niet opgegeven en levert dus de default op. Vastgelegd omdat het
        // afwijkt van `?persona=`, dat wél als bedieningsfout telt: voor een aantal is er een
        // zinnige default, voor een persona niet.
        assertEquals(200, plaatsBericht("?persona=pietersen&aantal=").statusCode())
        assertEquals(DemoResource.STANDAARD_GERICHT, VasteAanleverService.opdrachten.size)
    }

    @Test
    fun `het aantal komt door tot bij de aanlevering, met 1 als default`() {
        assertEquals(200, plaatsBericht("?persona=pietersen").statusCode())
        assertEquals(1, VasteAanleverService.opdrachten.size, "zonder aantal hoort er één bericht te gaan")

        VasteAanleverService.opdrachten.clear()

        assertEquals(200, plaatsBericht("?persona=pietersen&aantal=3").statusCode())
        assertEquals(3, VasteAanleverService.opdrachten.size)
        assertTrue(
            VasteAanleverService.opdrachten.all { it.verzoek.ontvanger.waarde == PIETERSEN_BSN },
            "elk bericht hoort naar de gevraagde persona te gaan",
        )
    }

    @Test
    fun `een geweigerd verzoek antwoordt in de vorm die het paneel leest`() {
        // `bediening.js` leest `body.fout` uit het geparste antwoord; is die sleutel er niet, dan
        // valt de melding terug op de ruwe tekst en leest de bediener JSON in plaats van een zin.
        val respons = plaatsBericht("?persona=bestaat-niet")
        val body = ObjectMapper().readTree(respons.body())

        assertTrue(
            respons.headers().firstValue("content-type").orElse("").startsWith("application/json"),
            "een fout hoort als JSON terug te komen",
        )
        assertEquals(setOf("fout", "soort"), body.fieldNames().asSequence().toSet())
        assertTrue(body.path("fout").asText().contains("bestaat-niet"))
    }

    @Test
    fun `het antwoord draagt de vier tellers die het paneel samenvat`() {
        // `bediening.js` leest ze bij naam in zijn `vulling`-samenvatter, zonder te toetsen of ze er
        // zijn: een hernoemd veld levert een groene melding "undefined van 3 berichten aangeleverd",
        // want `vullingTekst` gooit niet en `vullingSoort` valt dan terug op "goed".
        val body = ObjectMapper().readTree(plaatsBericht("?persona=pietersen&aantal=3").body())

        assertEquals(
            setOf("aangeboden", "geslaagd", "mislukt", "markeringMislukt"),
            body.fieldNames().asSequence().toSet(),
        )
        assertEquals(3, body.path("aangeboden").asInt())
    }

    private fun post(pad: String, query: String = ""): HttpResponse<String> = HttpClient.newHttpClient().send(
        HttpRequest.newBuilder(URI.create(basis.toString().removeSuffix("/") + pad + query))
            .POST(HttpRequest.BodyPublishers.noBody())
            .build(),
        HttpResponse.BodyHandlers.ofString(),
    )

    private fun plaatsBericht(query: String): HttpResponse<String> = post("/api/demo/bericht", query)

    private fun voerRandomOp(query: String): HttpResponse<String> = post("/api/demo/random", query)

    /**
     * Dezelfde soort grens als bij een gericht bericht, ruimer ingesteld, en om dezelfde reden: een
     * ronde van nul berichten meldt zich anders als geslaagd terwijl er niets gebeurde, en een
     * onredelijk hoog aantal houdt de omgeving minutenlang bezig met een antwoord dat pas daarna
     * komt.
     */
    @ParameterizedTest
    @MethodSource("randomBuitenDeGrenzen")
    fun `een burst met een aantal buiten de grenzen geeft 400`(aantal: Int) {
        val respons = voerRandomOp("?aantal=$aantal")

        assertEquals(400, respons.statusCode(), "aantal $aantal")
        assertTrue(respons.body().contains("aantal"), "de melding hoort het aantal-veld aan te wijzen")
        assertTrue(VasteAanleverService.opdrachten.isEmpty(), "een geweigerd aantal hoort niets aan te leveren")
    }

    /** De grenzen zelf, om de reden die bij het gerichte bericht hierboven staat. */
    @ParameterizedTest
    @MethodSource("randomOpDeGrenzen")
    fun `een burst op de grens van het toegestane aantal slaagt`(aantal: Int) {
        assertEquals(200, voerRandomOp("?aantal=$aantal").statusCode())
        assertEquals(aantal, VasteAanleverService.opdrachten.size)
    }

    /** Zelfde 404-valkuil als bij het gerichte bericht, hier zonder de verwarring met "onbekende persona". */
    @ParameterizedTest
    @ValueSource(strings = ["abc", "1.5", "3000000000"])
    fun `een burst met een onleesbaar aantal geeft 400 en geen 404`(aantal: String) {
        val respons = voerRandomOp("?aantal=$aantal")

        assertEquals(400, respons.statusCode(), "aantal '$aantal'")
        assertTrue(respons.body().contains("aantal"), "de melding hoort het aantal-veld aan te wijzen")
        assertTrue(VasteAanleverService.opdrachten.isEmpty(), "een onleesbaar aantal hoort niets aan te leveren")
    }

    @ParameterizedTest
    @ValueSource(strings = ["", "?aantal="])
    fun `een burst zonder aantal valt terug op de default`(query: String) {
        assertEquals(200, voerRandomOp(query).statusCode(), "query '$query'")
        assertEquals(
            DemoResource.STANDAARD_RANDOM,
            VasteAanleverService.opdrachten.size,
            "de default hoort het getal van het invoerveld te zijn",
        )
    }

    @Test
    fun `een tweemaal opgegeven aantal levert één ronde op`() {
        // JAX-RS pakt de eerste waarde. Vastgelegd omdat de tweede net zo goed buiten de grenzen kan
        // liggen: zou de resource ooit de laatste lezen, dan glipt daar een ongetoetste waarde langs.
        assertEquals(200, voerRandomOp("?aantal=1&aantal=500").statusCode())
        assertEquals(1, VasteAanleverService.opdrachten.size)
    }

    /**
     * De grens zelf ligt in `TempoService`, die hem ook zonder dit adres al vasthield; wat er níet
     * aankwam was een onleesbaar interval — dat beantwoordde JAX-RS met 404. Welke van de twee lagen
     * een weigering gaf, is hier niet te zien; `TempoResourceTest` pint dat los vast.
     */
    @ParameterizedTest
    @ValueSource(strings = ["abc", "0", "-1", "3601"])
    fun `de stroom starten met een onbruikbaar interval geeft 400 en laat de stroom uit`(interval: String) {
        val respons = post("/api/demo/tempo/start", "?interval=$interval")

        assertEquals(400, respons.statusCode(), "interval '$interval'")
        assertTrue(respons.body().contains("interval"), "de melding hoort het interval-veld aan te wijzen")
        assertFalse(stroom().path("loopt").asBoolean(), "de stroom hoort uit te staan")
    }

    /**
     * De enige test die de stroom écht start: de parameter ging van `Int` naar tekst, en zonder dit
     * staat nergens vast dat een geldig interval die route overleeft. Op de bovengrens, zodat er
     * binnen de testrun niets tikt — de `@AfterEach` zet hem daarna hoe dan ook stil.
     */
    @Test
    fun `de stroom starten met een geldig interval geeft de gevraagde stand terug`() {
        assertEquals(200, post("/api/demo/tempo/start", "?interval=${TempoService.MAX_INTERVAL}").statusCode())

        val gestart = stroom()

        assertTrue(gestart.path("loopt").asBoolean(), "de stroom hoort te lopen")
        assertEquals(TempoService.MAX_INTERVAL, gestart.path("intervalSeconden").asInt())
    }

    @Test
    fun `de stroom starten zonder interval valt terug op de default van het invoerveld`() {
        assertEquals(200, post("/api/demo/tempo/start", "?interval=").statusCode())
        assertEquals(TempoResource.STANDAARD_INTERVAL, stroom().path("intervalSeconden").asInt())
    }

    private fun stroom() = ObjectMapper().readTree(haalJson(tempoUrl))

    /**
     * Onvoorwaardelijk: de stroom is procesbreed en leeft langer dan één test. Bleef hij lopen, dan
     * schrijft hij op zijn eigen tempo opdrachten in [VasteAanleverService] en maakt hij een
     * wíllekeurige andere test rood.
     */
    @AfterEach
    fun stopDeStroom() {
        post("/api/demo/tempo/stop")
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

    private companion object {

        /** Uit de ingerichte personaset van demo-personas; `pietersen` is de persona die de test aanwijst. */
        const val PIETERSEN_BSN = "999993653"

        // Afgeleid van de constante en niet overgeschreven: wie de grens verzet, verzet anders wel
        // het buiten-bereik-geval en laat de bovengrens zelf als binnenwaarde achter.
        @JvmStatic
        fun buitenDeGrenzen() = listOf(0, -1, DemoResource.MAX_GERICHTE_BERICHTEN + 1)

        @JvmStatic
        fun opDeGrenzen() = listOf(1, DemoResource.MAX_GERICHTE_BERICHTEN)

        @JvmStatic
        fun randomBuitenDeGrenzen() = listOf(0, -1, DemoResource.MAX_RANDOM_BERICHTEN + 1)

        @JvmStatic
        fun randomOpDeGrenzen() = listOf(1, DemoResource.MAX_RANDOM_BERICHTEN)
    }

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

/**
 * Vaste aanlevering in plaats van twee magazijnen: de tests hieraan pinnen de bedrading van de knop
 * — komt het aantal aan, en gaat het naar de gevraagde persona — niet wat een magazijn ermee doet.
 *
 * `@Mock` vervangt AanleverService voor élke @QuarkusTest van deze module, en de opgenomen
 * opdrachten staan dus in één procesbrede lijst. Vandaar de thread-veilige lijst — er wordt op een
 * worker-thread geschreven en op de test-thread gelezen — en de @BeforeEach die hem leegt.
 */
@Mock
@Singleton
class VasteAanleverService(config: DemoConfig) : AanleverService(config) {

    override fun leverAan(opdrachten: List<AanleverOpdracht>): AanleverResultaat {
        Companion.opdrachten += opdrachten

        return AanleverResultaat(
            aangeboden = opdrachten.size,
            geslaagd = opdrachten.size,
            mislukt = 0,
            markeringMislukt = 0,
        )
    }

    companion object {

        val opdrachten: MutableList<AanleverOpdracht> = CopyOnWriteArrayList()
    }
}
