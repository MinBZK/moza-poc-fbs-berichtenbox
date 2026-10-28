package nl.rijksoverheid.moz.fbs.berichtenuitvraag.e2e

import com.github.tomakehurst.wiremock.WireMockServer
import com.github.tomakehurst.wiremock.client.WireMock.aResponse
import com.github.tomakehurst.wiremock.client.WireMock.equalTo as wmEqualTo
import com.github.tomakehurst.wiremock.client.WireMock.equalToJson
import com.github.tomakehurst.wiremock.client.WireMock.get
import com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor
import com.github.tomakehurst.wiremock.client.WireMock.post
import com.github.tomakehurst.wiremock.client.WireMock.patch as wmPatch
import com.github.tomakehurst.wiremock.client.WireMock.delete as wmDelete
import com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo
import com.github.tomakehurst.wiremock.client.WireMock.urlPathMatching
import io.mockk.every
import io.mockk.mockk
import io.opentelemetry.api.trace.Span
import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.junit.QuarkusMock
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.junit.QuarkusTestProfile
import io.quarkus.test.junit.TestProfile
import io.restassured.RestAssured
import io.restassured.RestAssured.given
import io.quarkus.redis.datasource.RedisDataSource
import jakarta.inject.Inject
import nl.mijnoverheidzakelijk.ldv.logboekdataverwerking.ProcessingHandler
import nl.rijksoverheid.moz.fbs.berichtensessiecache.Sessiecache
import nl.rijksoverheid.moz.fbs.berichtenuitvraag.sessie.SessieLogboek
import nl.rijksoverheid.moz.fbs.berichtenuitvraag.uitvraag.WireMockBackendsResource
import org.hamcrest.CoreMatchers.containsString
import org.hamcrest.CoreMatchers.equalTo
import org.hamcrest.CoreMatchers.nullValue
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import org.hamcrest.Matchers.contains
import org.hamcrest.Matchers.empty
import org.hamcrest.Matchers.hasKey
import org.hamcrest.Matchers.not
import org.junit.jupiter.api.fail
import java.net.HttpURLConnection
import java.net.URI
import java.security.MessageDigest
import java.util.HexFormat
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.logging.Handler
import java.util.logging.LogRecord
import java.util.logging.Logger

/**
 * Volledige keten door de échte bedrading (CLAUDE.md testlaag 4): HTTP-request →
 * uitvraag-resource → in-process Sessiecache-facade → echte Redis (Dev Services,
 * Redis Stack voor RediSearch) → Profiel-resolver + magazijnen via WireMock →
 * SSE-respons en daaropvolgende reads/writes. Dit is de regressiewacht voor de
 * in-process-herbedrading: een kapotte CDI-wiring, config-key of Redis-interactie
 * faalt hier, niet pas in een omgeving.
 */
@QuarkusTest
@TestProfile(KetenE2eProfile::class)
@QuarkusTestResource(value = WireMockBackendsResource::class, restrictToAnnotatedClass = true)
class UitvraagKetenE2eTest {

    private val profiel get() = WireMockBackendsResource.profiel
    private val magazijnA get() = WireMockBackendsResource.magazijnA
    private val magazijnB get() = WireMockBackendsResource.magazijnB

    @Inject
    lateinit var redis: RedisDataSource

    /** BSN → sessie-id van de sessies die deze test via de API startte. */
    private val sessies = mutableMapOf<String, String>()

    /** Logt in zoals een berichtenbox dat doet: één sessie per ontvanger, gestart via `POST /sessies`. */
    private fun sessie(bsn: String): String = sessies.getOrPut(bsn) {
        given().contentType("application/json")
            .header("X-Ontvanger", "BSN:$bsn")
            .`when`().post("/api/v1/sessies")
            .then()
            .statusCode(201)
            .extract().path<String>("sessieId")
    }

    /** Elke test begint met een vers beeld. */
    @AfterEach
    fun uitloggen() {
        sessies.forEach { (bsn, sessieId) ->
            given()
                .header("X-Ontvanger", "BSN:$bsn")
                .header("X-Sessie", sessieId)
                .`when`().delete("/api/v1/sessies/huidige")
                .then()
                .statusCode(204)
        }
    }

    @BeforeEach
    fun resetStubs() {
        profiel.resetAll()
        magazijnA.resetAll()
        magazijnB.resetAll()
    }

    private fun stubProfielOptIn(bsn: String, vararg oins: String) {
        val scopes = oins.joinToString(",") {
            """{ "partij": { "identificatieType": "OIN", "identificatieNummer": "$it" } }"""
        }
        profiel.stubFor(
            post(urlEqualTo("/api/profielservice/v1/partij")).withRequestBody(
                equalToJson("""{"identificatieType":"BSN","identificatieNummer":"$bsn"}"""),
            ).willReturn(
                aResponse().withStatus(200)
                    .withHeader("Content-Type", "application/json")
                    .withBody("""{"voorkeuren": [ { "voorkeurType": "OntvangViaBerichtenbox", "waarde": "true", "scopes": [ $scopes ] } ]}"""),
            ),
        )
    }

    private fun stubMagazijnBericht(server: WireMockServer, berichtId: String, bsn: String, label: String, afzender: String) {
        server.stubFor(
            get(urlPathMatching("/api/v1/berichten")).willReturn(
                aResponse().withStatus(200).withHeader("Content-Type", "application/json").withBody(
                    """
                    {
                      "berichten": [
                        {
                          "berichtId": "$berichtId",
                          "afzender": "$afzender",
                          "ontvanger": { "type": "BSN", "waarde": "$bsn" },
                          "onderwerp": "Bericht van $label",
                          "publicatietijdstip": "2026-03-10T10:00:00Z",
                          "aantalBijlagen": 0
                        }
                      ]
                    }
                    """.trimIndent(),
                ),
            ),
        )

        // De berichttekst zit niet in het lijstantwoord: het detailpad haalt hem op bij
        // het bronmagazijn op het moment dat de ontvanger het bericht opent.
        server.stubFor(
            get(urlEqualTo("/api/v1/berichten/$berichtId")).willReturn(
                aResponse().withStatus(200).withHeader("Content-Type", "application/json")
                    .withBody("""{"inhoud": "Inhoud van $label"}"""),
            ),
        )
    }

    /** Levert [ids] in magazijn-pagina's van [MAGAZIJN_PAGINA], zoals een echt magazijn pagineert. */
    private fun stubMagazijnPaginas(server: WireMockServer, ids: List<String>, bsn: String, afzender: String) {
        ids.chunked(MAGAZIJN_PAGINA).forEachIndexed { pagina, deel ->
            val berichten = deel.joinToString(",") { id ->
                """{"berichtId":"$id","afzender":"$afzender","ontvanger":{"type":"BSN","waarde":"$bsn"},""" +
                    """"onderwerp":"Bericht $id","publicatietijdstip":"2026-03-10T10:00:00Z","aantalBijlagen":0}"""
            }

            server.stubFor(
                get(urlPathMatching("/api/v1/berichten")).withQueryParam("page", wmEqualTo(pagina.toString())).willReturn(
                    aResponse().withStatus(200).withHeader("Content-Type", "application/json")
                        .withBody("""{"berichten":[$berichten],"totalElements":${ids.size}}"""),
                ),
            )
        }
    }

    @Test
    fun `volledige keten - ophalen vult Redis en daarna werken lijst detail patch en delete`() {
        val bsn = "999990019"
        val berichtId = "11111111-1111-1111-1111-111111111111"
        stubProfielOptIn(bsn, OIN_A)
        stubMagazijnBericht(magazijnA, berichtId, bsn, "magazijn-a", OIN_A)

        // Vóór de eerste ophaling weigert de leesweg met 409 (cache nog niet gevuld).
        given()
            .header("X-Ontvanger", "BSN:$bsn")
            .header("X-Sessie", sessie(bsn))
            .`when`().get("/api/v1/berichten")
            .then()
            .statusCode(409)

        // Ophalen: SSE-stream eindigt met ophalen-gereed en 1 bericht.
        val sse = given()
            .header("X-Ontvanger", "BSN:$bsn")
            .header("X-Sessie", sessie(bsn))
            .`when`().get("/api/v1/berichten/_ophalen")
            .then()
            .statusCode(200)
            .extract().body().asString()

        assertTrue(sse.contains("\"event\":\"ophalen-gereed\""), "Verwacht ophalen-gereed in: $sse")
        assertTrue(sse.contains("\"totaalBerichten\":1"), "Verwacht totaalBerichten:1 in: $sse")

        // Lijst uit de (echte) Redis-cache, met uitvraag-parameternamen in de links.
        given()
            .header("X-Ontvanger", "BSN:$bsn")
            .header("X-Sessie", sessie(bsn))
            .`when`().get("/api/v1/berichten")
            .then()
            .statusCode(200)
            .body("berichten[0].berichtId", equalTo(berichtId))
            .body("berichten[0].magazijnId", equalTo(OIN_A))
            .body("aantalNietGeleverd", equalTo(0))
            .body("nietGeleverd", empty<Any>())

        // Detail inclusief inhoud.
        given()
            .header("X-Ontvanger", "BSN:$bsn")
            .header("X-Sessie", sessie(bsn))
            .`when`().get("/api/v1/berichten/$berichtId?magazijnId=$OIN_A")
            .then()
            .statusCode(200)
            .body("inhoud", equalTo("Inhoud van magazijn-a"))

        // Dual-write PATCH: magazijn (WireMock) + cache (Redis) — status komt terug.
        magazijnA.stubFor(
            wmPatch(urlPathMatching("/api/v1/berichten/$berichtId")).willReturn(aResponse().withStatus(204)),
        )

        given()
            .header("X-Ontvanger", "BSN:$bsn")
            .header("X-Sessie", sessie(bsn))
            .header("Content-Type", "application/merge-patch+json")
            .body("""{"status":"gelezen"}""")
            .`when`().patch("/api/v1/berichten/$berichtId?magazijnId=$OIN_A")
            .then()
            .statusCode(200)
            .body("status", equalTo("gelezen"))

        // Map zetten en weer wissen tegen de échte cache: daarna staat het bericht in Postvak IN,
        // ook in de lijst, en de leesstatus is gebleven.
        given()
            .header("X-Ontvanger", "BSN:$bsn")
            .header("X-Sessie", sessie(bsn))
            .header("Content-Type", "application/merge-patch+json")
            .body("""{"map":"werk"}""")
            .`when`().patch("/api/v1/berichten/$berichtId?magazijnId=$OIN_A")
            .then()
            .statusCode(200)
            .body("map", equalTo("werk"))

        given()
            .header("X-Ontvanger", "BSN:$bsn")
            .header("X-Sessie", sessie(bsn))
            .header("Content-Type", "application/merge-patch+json")
            .body("""{"map":""}""")
            .`when`().patch("/api/v1/berichten/$berichtId?magazijnId=$OIN_A")
            .then()
            .statusCode(200)
            .body("\$", not(hasKey("map")))
            .body("status", equalTo("gelezen"))

        given()
            .header("X-Ontvanger", "BSN:$bsn")
            .header("X-Sessie", sessie(bsn))
            .`when`().get("/api/v1/berichten")
            .then()
            .statusCode(200)
            .body("berichten[0]", not(hasKey("map")))

        // Dual-write DELETE; daarna is het bericht ook uit de cache verdwenen, maar laat het
        // wél een spoor na: de ondernemer hoort te horen dát hij het zelf weggooide.
        magazijnA.stubFor(
            wmDelete(urlPathMatching("/api/v1/berichten/$berichtId")).willReturn(aResponse().withStatus(204)),
        )

        given()
            .header("X-Ontvanger", "BSN:$bsn")
            .header("X-Sessie", sessie(bsn))
            .`when`().delete("/api/v1/berichten/$berichtId?magazijnId=$OIN_A")
            .then()
            .statusCode(204)

        given()
            .header("X-Ontvanger", "BSN:$bsn")
            .header("X-Sessie", sessie(bsn))
            .`when`().get("/api/v1/berichten/$berichtId?magazijnId=$OIN_A")
            .then()
            .statusCode(410)
            .body("type", equalTo("urn:fbs:fout:bericht-verwijderd"))

        // Een bericht dat er nooit was, blijft ononderscheidbaar van dat van een ander.
        given()
            .header("X-Ontvanger", "BSN:$bsn")
            .header("X-Sessie", sessie(bsn))
            .`when`().get("/api/v1/berichten/33333333-3333-3333-3333-333333333333?magazijnId=$OIN_A")
            .then()
            .statusCode(404)
            .body("type", equalTo("urn:fbs:fout:bericht-onbekend"))

        // En een ánder krijgt op datzelfde berichtId exact dat antwoord: het spoor van de
        // verwijdering is per ontvanger gesleuteld, dus verraadt het niets over andermans bericht.
        val andereBsn = "999993653"
        stubProfielOptIn(andereBsn, OIN_A)

        given()
            .header("X-Ontvanger", "BSN:$andereBsn")
            .header("X-Sessie", sessie(andereBsn))
            .`when`().get("/api/v1/berichten/_ophalen")
            .then()
            .statusCode(200)

        given()
            .header("X-Ontvanger", "BSN:$andereBsn")
            .header("X-Sessie", sessie(andereBsn))
            .`when`().get("/api/v1/berichten/$berichtId?magazijnId=$OIN_A")
            .then()
            .statusCode(404)
            .body("type", equalTo("urn:fbs:fout:bericht-onbekend"))
    }

    @Test
    fun `uitloggen wist de gegevens in Redis, en daarna weigert de uitvraag de sessie`() {
        val bsn = "999990019"
        val berichtId = "77777777-7777-7777-7777-777777777777"
        stubProfielOptIn(bsn, OIN_A)
        stubMagazijnBericht(magazijnA, berichtId, bsn, "magazijn-a", OIN_A)
        val sessieId = sessie(bsn)

        given()
            .header("X-Ontvanger", "BSN:$bsn")
            .header("X-Sessie", sessieId)
            .`when`().get("/api/v1/berichten/_ophalen")
            .then()
            .statusCode(200)
            .body(containsString("\"totaalBerichten\":1"))

        assertTrue(sessieSleutels(bsn).isNotEmpty(), "na het ophalen staan de gegevens in Redis")
        assertTrue(redis.key().exists("bericht:v4:$OIN_A:$berichtId"))

        given()
            .header("X-Ontvanger", "BSN:$bsn")
            .header("X-Sessie", sessieId)
            .`when`().delete("/api/v1/sessies/huidige")
            .then()
            .statusCode(204)
        sessies.remove(bsn)

        given()
            .header("X-Ontvanger", "BSN:$bsn")
            .header("X-Sessie", sessieId)
            .`when`().get("/api/v1/berichten")
            .then()
            .statusCode(401)
            .body("type", equalTo("urn:fbs:fout:sessie-ongeldig"))

        assertEquals(emptyList<String>(), sessieSleutels(bsn), "de laatste sessie is weg, dus de gegevens ook")
        assertFalse(redis.key().exists("bericht:v4:$OIN_A:$berichtId"), "ook de berichthash is gewist")
    }

    /**
     * Opnieuw inloggen geeft een vers beeld, langs de publieke API: geen lijst, geen zoektreffers en
     * geen bericht van vóór het uitloggen. Vooraf bewijzen lijst, zoeken en de zoekindex dat de
     * gegevens er stonden, anders slaagt de controle achteraf ook bij een zoekopdracht die nooit iets
     * vindt. [aantal] gaat met 257 voorbij de grootste pagina, de magazijnpagina en een Redis-batch.
     */
    @ParameterizedTest(name = "{0} berichten")
    @ValueSource(ints = [0, 1, 257])
    fun `na uitloggen en opnieuw inloggen zijn lijst, zoeken en bericht weer leeg`(aantal: Int) {
        val bsn = BSN_PER_AANTAL.getValue(aantal)
        val ids = (0 until aantal).map { i -> "00000000-0000-4000-9000-%012d".format(i) }
        stubProfielOptIn(bsn, OIN_A)
        stubMagazijnPaginasOfLeeg(magazijnA, ids, bsn, OIN_A)
        val eerste = sessie(bsn)

        given().header("X-Ontvanger", "BSN:$bsn").header("X-Sessie", eerste)
            .`when`().get("/api/v1/berichten/_ophalen")
            .then().statusCode(200).body(containsString("\"totaalBerichten\":$aantal"))

        val zichtbaar = minOf(aantal, Sessiecache.MAX_PAGINA_GROOTTE)
        leesLijst(bsn, eerste, "/api/v1/berichten?paginaGrootte=${Sessiecache.MAX_PAGINA_GROOTTE}")
            .statusCode(200).body("berichten.size()", equalTo(zichtbaar))
        leesLijst(bsn, eerste, "/api/v1/berichten/_zoeken?q=Bericht")
            .statusCode(200).body("berichten.size()", equalTo(minOf(aantal, ZOEK_TREFFERS)))
        assertEquals(aantal.toLong(), zoekindexTotaal(bsn), "de zoekindex kent vooraf elk opgehaald bericht")

        given().header("X-Ontvanger", "BSN:$bsn").header("X-Sessie", eerste)
            .`when`().delete("/api/v1/sessies/huidige")
            .then().statusCode(204)
        sessies.remove(bsn)
        val tweede = sessie(bsn)

        leesLijst(bsn, tweede, "/api/v1/berichten").statusCode(409).body("type", equalTo(NOG_NIET_OPGEHAALD))
        leesLijst(bsn, tweede, "/api/v1/berichten/_zoeken?q=Bericht").statusCode(409).body("type", equalTo(NOG_NIET_OPGEHAALD))

        ids.firstOrNull()?.let { id ->
            leesLijst(bsn, tweede, "/api/v1/berichten/$id?magazijnId=$OIN_A").statusCode(409).body("type", equalTo(NOG_NIET_OPGEHAALD))
        }

        assertEquals(0L, zoekindexTotaal(bsn), "de zoekindex vindt na het uitloggen niets meer")
        assertTrue(ids.none { redis.key().exists("bericht:v4:$OIN_A:$it") }, "een berichthash overleefde het uitloggen")
    }

    /**
     * Uitloggen terwijl een ophaalronde loopt, met en zonder direct opnieuw inloggen. De ronde leest
     * uit een traag magazijn en rondt pas na het uitloggen af. Ze mag dan niets wegschrijven, ook niet
     * in de nieuwe sessie, en haar stream sluit zonder `ophalen-gereed` en zonder fout. Een nieuwe
     * ronde in de nieuwe sessie loopt daarna gewoon: de oude ronde hield de lock niet vast.
     */
    @ParameterizedTest(name = "opnieuw inloggen: {0}")
    @ValueSource(booleans = [false, true])
    fun `uitloggen tijdens een ophaalronde laat de ronde niets wegschrijven`(opnieuwInloggen: Boolean) {
        val bsn = if (opnieuwInloggen) "999990135" else "999990147"
        val berichtId = "88888888-8888-4888-8888-88888888888${if (opnieuwInloggen) 1 else 2}"
        stubProfielOptIn(bsn, OIN_A)
        stubMagazijnBericht(magazijnA, berichtId, bsn, "magazijn-a", OIN_A)
        magazijnA.stubFor(
            get(urlPathMatching("/api/v1/berichten")).willReturn(
                aResponse().withStatus(200).withHeader("Content-Type", "application/json").withFixedDelay(TRAAG_MAGAZIJN_MS)
                    .withBody("""{"berichten":[${berichtJson(berichtId, bsn)}],"totalElements":1}"""),
            ),
        )
        val eerste = sessie(bsn)

        val ronde = CompletableFuture.supplyAsync {
            given().header("X-Ontvanger", "BSN:$bsn").header("X-Sessie", eerste)
                .`when`().get("/api/v1/berichten/_ophalen")
                .then().extract()
        }

        wachtTot("het magazijn is bevraagd") { magazijnA.findAll(getRequestedFor(urlPathMatching("/api/v1/berichten"))).isNotEmpty() }

        given().header("X-Ontvanger", "BSN:$bsn").header("X-Sessie", eerste)
            .`when`().delete("/api/v1/sessies/huidige")
            .then().statusCode(204)
        sessies.remove(bsn)
        val tweede = if (opnieuwInloggen) sessie(bsn) else null

        val stream = ronde.get(RONDE_S, TimeUnit.SECONDS)

        assertEquals(200, stream.statusCode())
        assertFalse(stream.body().asString().contains("ophalen-gereed"), "de ronde rondde af na het uitloggen: ${stream.body().asString()}")
        assertFalse(stream.body().asString().contains("ophalen-fout"), "de stream eindigde met een fout: ${stream.body().asString()}")
        assertFalse(redis.key().exists("bericht:v4:$OIN_A:$berichtId"), "de ronde schreef haar bericht weg")
        leesLijst(bsn, eerste, "/api/v1/berichten").statusCode(401)

        if (tweede == null) {
            assertEquals(emptyList<String>(), sessieSleutels(bsn), "zonder sessie hoort er niets van de ontvanger te staan")

            return
        }

        leesLijst(bsn, tweede, "/api/v1/berichten").statusCode(409).body("type", equalTo(NOG_NIET_OPGEHAALD))

        // Na de oude ronde: de nieuwe sessie haalt zelf op, met een vlot magazijn.
        stubMagazijnBericht(magazijnA, berichtId, bsn, "magazijn-a", OIN_A)

        given().header("X-Ontvanger", "BSN:$bsn").header("X-Sessie", tweede)
            .`when`().get("/api/v1/berichten/_ophalen")
            .then().statusCode(200).body(containsString("\"totaalBerichten\":1"))
        leesLijst(bsn, tweede, "/api/v1/berichten").statusCode(200).body("berichten[0].berichtId", equalTo(berichtId))
    }

    /**
     * Het wissen bij uitloggen is onherroepelijk; een logboek dat het niet kan vastleggen, mag de
     * uitlog dus niet laten mislukken. Wel hoort het gat zichtbaar te worden: het alert-token, zonder
     * de BSN. Alleen de opruimregel faalt hier; de logregel van het verzoek zelf blijft werken.
     */
    @Test
    fun `een uitlog geeft 204 en wist alles, ook als het logboek het opruimen niet kan vastleggen`() {
        val bsn = "999990159"
        val berichtId = "99999999-9999-4999-8999-999999999999"
        stubProfielOptIn(bsn, OIN_A)
        stubMagazijnBericht(magazijnA, berichtId, bsn, "magazijn-a", OIN_A)
        val sessieId = sessie(bsn)

        given().header("X-Ontvanger", "BSN:$bsn").header("X-Sessie", sessieId)
            .`when`().get("/api/v1/berichten/_ophalen")
            .then().statusCode(200).body(containsString("\"totaalBerichten\":1"))
        assertTrue(redis.key().exists("bericht:v4:$OIN_A:$berichtId"))
        installeerLogboekDatOpruimenWeigert()

        val regels = vangLog(SessieLogboek::class.java.name) {
            given().header("X-Ontvanger", "BSN:$bsn").header("X-Sessie", sessieId)
                .`when`().delete("/api/v1/sessies/huidige")
                .then().statusCode(204)
        }

        sessies.remove(bsn)

        val melding = regels.single { it.contains(SessieLogboek.ALERT_TOKEN) }

        assertTrue(melding.contains("aanleiding=uitgelogd"), melding)
        assertFalse(melding.contains(bsn), "de BSN hoort niet in de applicatielog: $melding")
        assertEquals(emptyList<String>(), sessieSleutels(bsn), "het wissen hoort door te gaan")
        assertFalse(redis.key().exists("bericht:v4:$OIN_A:$berichtId"), "ook de berichthash hoort weg te zijn")
        leesLijst(bsn, sessieId, "/api/v1/berichten").statusCode(401)
    }

    /** Elke logregel slaagt, behalve die van het opruimen: die gooit zoals een onbereikbaar logboek. */
    private fun installeerLogboekDatOpruimenWeigert() {
        val handler = mockk<ProcessingHandler>(relaxed = true)
        val opruimSpan = mockk<Span>(relaxed = true)

        every { handler.startSpan(any(), any()) } answers {
            if (firstArg<String>() == SessieLogboek.NAAM) opruimSpan else mockk(relaxed = true)
        }

        every { handler.addLogboekContextToSpan(any(), any(), any()) } answers {
            if (firstArg<Span>() === opruimSpan) throw IllegalStateException("logboek onbereikbaar")

            emptyList()
        }

        QuarkusMock.installMockForType(handler, ProcessingHandler::class.java)
    }

    private fun vangLog(loggerNaam: String, blok: () -> Unit): List<String> {
        val regels = CopyOnWriteArrayList<String>()
        val logger = Logger.getLogger(loggerNaam)
        val vanger = object : Handler() {
            override fun publish(record: LogRecord) {
                regels += String.format(record.message, *(record.parameters ?: emptyArray()))
            }

            override fun flush() = Unit

            override fun close() = Unit
        }

        logger.addHandler(vanger)

        try {
            blok()
        } finally {
            logger.removeHandler(vanger)
        }

        return regels
    }

    private fun leesLijst(bsn: String, sessieId: String, pad: String) =
        given().header("X-Ontvanger", "BSN:$bsn").header("X-Sessie", sessieId).`when`().get(pad).then()

    /** Wat RediSearch voor [bsn] vindt: het totaal, niet de grootte van één antwoordpagina. */
    private fun zoekindexTotaal(bsn: String): Long =
        redis.search().ftSearch("berichten-idx-v4", "@ontvanger:{$bsn}").count().toLong()

    private fun stubMagazijnPaginasOfLeeg(server: WireMockServer, ids: List<String>, bsn: String, afzender: String) {
        if (ids.isNotEmpty()) return stubMagazijnPaginas(server, ids, bsn, afzender)

        server.stubFor(
            get(urlPathMatching("/api/v1/berichten")).willReturn(
                aResponse().withStatus(200).withHeader("Content-Type", "application/json")
                    .withBody("""{"berichten":[],"totalElements":0}"""),
            ),
        )
    }

    private fun berichtJson(berichtId: String, bsn: String) =
        """{"berichtId":"$berichtId","afzender":"$OIN_A","ontvanger":{"type":"BSN","waarde":"$bsn"},""" +
            """"onderwerp":"Traag bericht","publicatietijdstip":"2026-03-10T10:00:00Z","aantalBijlagen":0}"""

    private fun wachtTot(wat: String, voorwaarde: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(RONDE_S)

        while (!voorwaarde()) {
            if (System.nanoTime() > deadline) fail("$wat: niet binnen $RONDE_S s")

            Thread.sleep(20)
        }
    }

    /** Alle sleutels onder de sessie-prefix van [bsn]; die prefix is een SHA-256, zodat de BSN niet in Redis staat. */
    private fun sessieSleutels(bsn: String): List<String> {
        val hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest("BSN:$bsn".toByteArray()))

        return redis.key().keys("berichtensessiecache:v4:$hash*")
    }

    @Test
    fun `partial failure - magazijn-b 500 degradeert maar cachet het overlevende magazijn`() {
        val bsn = "999993653"
        val berichtId = "22222222-2222-2222-2222-222222222222"
        stubProfielOptIn(bsn, OIN_A, OIN_B)
        stubMagazijnBericht(magazijnA, berichtId, bsn, "magazijn-a", OIN_A)
        magazijnB.stubFor(get(urlPathMatching("/.*")).willReturn(aResponse().withStatus(500)))

        val sse = given()
            .header("X-Ontvanger", "BSN:$bsn")
            .header("X-Sessie", sessie(bsn))
            .`when`().get("/api/v1/berichten/_ophalen")
            .then()
            .statusCode(200)
            .extract().body().asString()

        assertTrue(sse.contains("\"event\":\"ophalen-gereed\""), "Verwacht ophalen-gereed in: $sse")
        assertTrue(sse.contains("\"geslaagd\":1"), "Verwacht geslaagd:1 in: $sse")
        assertTrue(sse.contains("\"mislukt\":1"), "Verwacht mislukt:1 in: $sse")

        given()
            .header("X-Ontvanger", "BSN:$bsn")
            .header("X-Sessie", sessie(bsn))
            .`when`().get("/api/v1/berichten")
            .then()
            .statusCode(200)
            .body("berichten[0].berichtId", equalTo(berichtId))
            // Wie niet leverde, overleeft de stroom: via Redis terug op de lijst.
            .body("aantalNietGeleverd", equalTo(1))
            .body("nietGeleverd.magazijnId", contains(OIN_B))
            .body("nietGeleverd[0].status", equalTo("FOUT"))
    }

    /**
     * Een organisatie die meer heeft dan ze leverde, blijft dat op de lijst: op elke pagina, in de
     * zoekresultaten en bij elke volgende lezing, niet alleen in het event van de ophaalronde. De
     * tweede lezing is de herlaadactie — er draait geen nieuwe ronde, alles komt uit de sessie in
     * Redis. Magazijn B levert alles en een totaal dat dat bevestigt, dus dat staat er níet bij.
     */
    @Test
    fun `een afgekapte organisatie blijft op de lijst staan bij bladeren, zoeken en verversen`() {
        val bsn = "999990056"
        val ids = (1..2).map { i -> "00000000-0000-4000-8000-%012d".format(i) }
        stubProfielOptIn(bsn, OIN_A, OIN_B)
        magazijnB.stubFor(
            get(urlPathMatching("/api/v1/berichten")).willReturn(
                aResponse().withStatus(200).withHeader("Content-Type", "application/json").withBody(
                    """{"berichten":[{"berichtId":"00000000-0000-4000-8000-000000000003","afzender":"$OIN_B",""" +
                        """"ontvanger":{"type":"BSN","waarde":"$bsn"},"onderwerp":"Bericht van B",""" +
                        """"publicatietijdstip":"2026-03-09T10:00:00Z","aantalBijlagen":0}],"totalElements":1}""",
                ),
            ),
        )
        magazijnA.stubFor(
            get(urlPathMatching("/api/v1/berichten")).willReturn(
                aResponse().withStatus(200).withHeader("Content-Type", "application/json").withBody(
                    """{"berichten":[${ids.joinToString(",") { id ->
                        """{"berichtId":"$id","afzender":"$OIN_A","ontvanger":{"type":"BSN","waarde":"$bsn"},""" +
                            """"onderwerp":"Bericht $id","publicatietijdstip":"2026-03-10T10:00:00Z","aantalBijlagen":0}"""
                    }}],"totalElements":812}""",
                ),
            ),
        )

        val sse = given()
            .header("X-Ontvanger", "BSN:$bsn")
            .header("X-Sessie", sessie(bsn))
            .`when`().get("/api/v1/berichten/_ophalen")
            .then().statusCode(200).extract().body().asString()

        assertTrue(sse.contains("\"afgekapt\":true"), "Verwacht het afkap-signaal in de ronde zelf: $sse")

        // Drie berichten: twee pagina's van één, en de zoekresultaten in één keer.
        val paden = mapOf(
            "/api/v1/berichten?pagina=0&paginaGrootte=1" to 1,
            "/api/v1/berichten?pagina=1&paginaGrootte=1" to 1,
            "/api/v1/berichten/_zoeken?q=Bericht" to 3,
        )

        repeat(2) {
            for ((pad, aantal) in paden) {
                given()
                    .header("X-Ontvanger", "BSN:$bsn")
                    .header("X-Sessie", sessie(bsn))
                    .`when`().get(pad)
                    .then()
                    .statusCode(200)
                    .body("berichten.size()", equalTo(aantal))
                    .body("afgekapt.magazijnId", contains(OIN_A))
                    .body("afgekapt[0].aantalOpgehaald", equalTo(2))
                    .body("afgekapt[0].totaalBeschikbaar", equalTo(812))
                    .body("aantalNietGeleverd", equalTo(0))
            }
        }
    }

    @Test
    fun `ophaalronde levert per bericht de naam die het register voor zijn magazijn kent`() {
        // Het andere been van de aanmeld-test: hier komen de berichten uit een échte ophaalronde
        // langs twee magazijnen, elk met een eigen naam in het register.
        val bsn = "999990111"
        val berichtVanA = "44444444-4444-4444-4444-444444444444"
        val berichtVanB = "55555555-5555-5555-5555-555555555555"
        stubProfielOptIn(bsn, OIN_A, OIN_B)
        stubMagazijnBericht(magazijnA, berichtVanA, bsn, "magazijn-a", OIN_A)
        stubMagazijnBericht(magazijnB, berichtVanB, bsn, "magazijn-b", OIN_B)

        given()
            .header("X-Ontvanger", "BSN:$bsn")
            .header("X-Sessie", sessie(bsn))
            .`when`().get("/api/v1/berichten/_ophalen")
            .then()
            .statusCode(200)

        given()
            .header("X-Ontvanger", "BSN:$bsn")
            .header("X-Sessie", sessie(bsn))
            .`when`().get("/api/v1/berichten")
            .then()
            .statusCode(200)
            .body(
                "berichten.find { it.magazijnId == '$OIN_B' }.afzenderNaam",
                equalTo(WireMockBackendsResource.NAAM_B),
            )
            .body(
                "berichten.find { it.magazijnId == '$OIN_A' }.afzenderNaam",
                equalTo(WireMockBackendsResource.NAAM_A),
            )
    }

    @Test
    fun `de afzendernaam volgt het bevraagde magazijn, niet de afzender die het bericht claimt`() {
        // Magazijn A levert een bericht dat zichzelf als magazijn B presenteert. De weergavenaam
        // hoort die claim niet te volgen: hij komt uit het register op het magazijn dat we
        // daadwerkelijk bevraagd hebben. Anders kan een magazijn zich als een andere organisatie
        // voordoen in de berichtenlijst van een ondernemer.
        val bsn = "999990123"
        val berichtId = "66666666-6666-6666-6666-666666666666"
        stubProfielOptIn(bsn, OIN_A)
        stubMagazijnBericht(magazijnA, berichtId, bsn, "magazijn-a", afzender = OIN_B)

        given()
            .header("X-Ontvanger", "BSN:$bsn")
            .header("X-Sessie", sessie(bsn))
            .`when`().get("/api/v1/berichten/_ophalen")
            .then()
            .statusCode(200)

        given()
            .header("X-Ontvanger", "BSN:$bsn")
            .header("X-Sessie", sessie(bsn))
            .`when`().get("/api/v1/berichten")
            .then()
            .statusCode(200)
            .body("berichten[0].magazijnId", equalTo(OIN_A))
            .body("berichten[0].afzenderNaam", equalTo(WireMockBackendsResource.NAAM_A))
    }

    @Test
    fun `een 404 zonder herkenbaar antwoord meldt de storing in plaats van een lege berichtenbox`() {
        val bsn = "999990032"
        val berichtId = "33333333-3333-3333-3333-333333333333"

        stubProfielOptIn(bsn, OIN_A)
        stubMagazijnBericht(magazijnA, berichtId, bsn, "magazijn-a", OIN_A)

        given()
            .header("X-Ontvanger", "BSN:$bsn")
            .header("X-Sessie", sessie(bsn))
            .`when`().get("/api/v1/berichten/_ophalen")
            .then()
            .statusCode(200)
            .body(containsString("\"totaalBerichten\":1"))

        // De koppeling naar de voorkeurenbron valt weg: een kale 404 in plaats van het
        // herkenbare "partij niet gevonden"-antwoord.
        profiel.resetAll()
        profiel.stubFor(
            post(urlEqualTo("/api/profielservice/v1/partij")).willReturn(aResponse().withStatus(404)),
        )

        given()
            .header("X-Ontvanger", "BSN:$bsn")
            .header("X-Sessie", sessie(bsn))
            .`when`().get("/api/v1/berichten/_ophalen")
            .then()
            .statusCode(503)
            .header("Retry-After", "30")

        // De leesweg meldt de mislukte ophaling; nergens een geslaagde lege lijst.
        given()
            .header("X-Ontvanger", "BSN:$bsn")
            .header("X-Sessie", sessie(bsn))
            .`when`().get("/api/v1/berichten")
            .then()
            .statusCode(503)
            .body("berichten", nullValue())
    }

    @Test
    fun `een 404 met het partij-niet-gevonden-antwoord geeft een gewone lege berichtenbox`() {
        val bsn = "999990044"

        profiel.stubFor(
            post(urlEqualTo("/api/profielservice/v1/partij")).willReturn(
                aResponse().withStatus(404)
                    .withHeader("Content-Type", "application/problem+json")
                    .withBody("""{"type":"about:blank","title":"Partij niet gevonden","status":404}"""),
            ),
        )

        given()
            .header("X-Ontvanger", "BSN:$bsn")
            .header("X-Sessie", sessie(bsn))
            .`when`().get("/api/v1/berichten/_ophalen")
            .then()
            .statusCode(200)
            .body(containsString("\"event\":\"ophalen-gereed\""))
            .body(containsString("\"totaalBerichten\":0"))

        given()
            .header("X-Ontvanger", "BSN:$bsn")
            .header("X-Sessie", sessie(bsn))
            .`when`().get("/api/v1/berichten")
            .then()
            .statusCode(200)
            .body("berichten.size()", equalTo(0))
    }

    @Test
    fun `profiel-500 geeft 503 met Retry-After vóór de stream`() {
        profiel.stubFor(
            post(urlEqualTo("/api/profielservice/v1/partij")).willReturn(aResponse().withStatus(500)),
        )

        given()
            .header("X-Ontvanger", "BSN:999991401")
            .header("X-Sessie", sessie("999991401"))
            .`when`().get("/api/v1/berichten/_ophalen")
            .then()
            .statusCode(503)
            .header("Retry-After", "30")
    }

    @Test
    fun `wie de next-links volgt met de grootste pagina, krijgt de volledige opgehaalde set`() {
        // Meer berichten dan één pagina van het plafond, uit meerdere magazijn-pagina's: een
        // afnemer die zelf filtert en sorteert, moet de hele set binnenkrijgen zonder gaten of
        // dubbelingen, en weten wanneer hij klaar is.
        val bsn = "999990020"
        val aantal = Sessiecache.MAX_PAGINA_GROOTTE + 50
        val ids = (0 until aantal).map { i -> "00000000-0000-4000-8000-%012d".format(i) }
        stubProfielOptIn(bsn, OIN_A)
        stubMagazijnPaginas(magazijnA, ids, bsn, OIN_A)

        given()
            .header("X-Ontvanger", "BSN:$bsn")
            .header("X-Sessie", sessie(bsn))
            .`when`().get("/api/v1/berichten/_ophalen")
            .then()
            .statusCode(200)

        val gelezen = mutableListOf<String>()
        var href: String? = "/api/v1/berichten?paginaGrootte=${Sessiecache.MAX_PAGINA_GROOTTE}"
        var paginas = 0

        while (href != null) {
            val antwoord = given()
                .header("X-Ontvanger", "BSN:$bsn")
                .header("X-Sessie", sessie(bsn))
                .`when`().get(href)
                .then()
                .statusCode(200)
                .extract().jsonPath()
            gelezen += antwoord.getList<String>("berichten.berichtId")
            href = antwoord.getString("_links.next.href")
            paginas++
        }

        assertEquals(2, paginas)
        assertEquals(ids.toSet(), gelezen.toSet())
        assertEquals(aantal, gelezen.size, "geen dubbelingen over de paginagrens heen")
    }

    @Test
    fun `een aanmelding verschijnt in een gevolgde sessie zonder nieuwe ophaalronde`() {
        val bsn = "999993653"
        val nieuwId = "22222222-2222-2222-2222-222222222222"
        stubProfielOptIn(bsn, OIN_A)
        stubMagazijnBericht(magazijnA, "33333333-3333-3333-3333-333333333333", bsn, "magazijn-a", OIN_A)
        given()
            .header("X-Ontvanger", "BSN:$bsn")
            .header("X-Sessie", sessie(bsn))
            .`when`().get("/api/v1/berichten/_ophalen")
            .then()
            .statusCode(200)
        val lijstVerzoeken = { magazijnA.findAll(getRequestedFor(urlPathMatching("/api/v1/berichten"))).size }
        val naOphalen = lijstVerzoeken()

        val regels = LinkedBlockingQueue<String>()

        val connection = (URI("http://localhost:${RestAssured.port}/api/v1/berichten/_volgen").toURL().openConnection() as HttpURLConnection)
            .apply {
                setRequestProperty("X-Ontvanger", "BSN:$bsn")
                setRequestProperty("X-Sessie", sessie(bsn))
                setRequestProperty("Accept", "text/event-stream")
                readTimeout = 10_000
            }

        // Eerst de status, dan pas de lezer: HttpURLConnection is niet thread-safe.
        assertEquals(200, connection.responseCode)

        val lezer = Thread { runCatching { connection.inputStream.bufferedReader().lineSequence().forEach { regels += it } } }
            .apply { isDaemon = true; start() }

        try {
            wachtOpRegel(regels, "\"event\":\"volgen-gestart\"")

            given()
                .contentType("application/cloudevents+json")
                .body(aanmelding(nieuwId, bsn))
                .`when`().post("/api/v1/aanmeldingen")
                .then()
                .statusCode(202)

            val frame = wachtOpRegel(regels, "\"event\":\"bericht-bijgekomen\"")

            assertTrue(frame.contains("\"berichtId\":\"$nieuwId\""), "Verwacht het aangemelde bericht in: $frame")
            assertEquals(naOphalen, lijstVerzoeken(), "het bericht hoort zonder nieuwe bevraging van het magazijn te komen")
        } finally {
            connection.disconnect()
            lezer.join(2_000)
        }
    }

    private fun wachtOpRegel(regels: LinkedBlockingQueue<String>, bevat: String): String {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)

        while (System.nanoTime() < deadline) {
            val regel = regels.poll(100, TimeUnit.MILLISECONDS) ?: continue

            if (regel.contains(bevat)) return regel
        }

        fail("Geen regel met $bevat binnen 5 s")
    }

    private fun aanmelding(berichtId: String, bsn: String) = """
        {
          "id": "evt-$berichtId",
          "source": "urn:nld:oin:$OIN_A:systeem:fbs-magazijn",
          "specversion": "1.0",
          "type": "nl.rijksoverheid.fbs.bericht.gepubliceerd",
          "subject": "$berichtId",
          "datacontenttype": "application/json",
          "data": {
            "berichtId": "$berichtId",
            "afzender": "$OIN_A",
            "ontvanger": { "type": "BSN", "waarde": "$bsn" },
            "onderwerp": "Net binnen",
            "publicatietijdstip": "2026-09-21T10:00:00Z"
          }
        }
    """.trimIndent()
}

// magazijnId == afzender-OIN (register-conventie). Bron is de gedeelde fixture, zodat
// de stub-OIN's en de geïnjecteerde register-config gegarandeerd dezelfde waarden zijn.
private val OIN_A = WireMockBackendsResource.OIN_A
private val OIN_B = WireMockBackendsResource.OIN_B

// Gelijk aan `berichtensessiecache.magazijn-page-size`; een grotere stub-pagina wijst de lezer af.
private const val MAGAZIJN_PAGINA = 100

private const val NOG_NIET_OPGEHAALD = "urn:fbs:fout:nog-niet-opgehaald"

// `_zoeken` levert de eerste twintig treffers, zonder paginering.
private const val ZOEK_TREFFERS = 20

// Lang genoeg om binnen de ronde uit te loggen, ruim binnen de query-timeout van het magazijn.
private const val TRAAG_MAGAZIJN_MS = 3_000
private const val RONDE_S = 20L

// Eén ontvanger per aantal: de Profiel-cache houdt anders iets van het vorige geval vast.
private val BSN_PER_AANTAL = mapOf(0 to "999990068", 1 to "999990081", 257 to "999990093")

/**
 * Echte facade-keten: Redis via Dev Services (Redis Stack — RediSearch is nodig
 * voor de index-bootstrap), géén MockSessiecache-alternative.
 */
class KetenE2eProfile : QuarkusTestProfile {

    override fun getConfigOverrides(): Map<String, String> = mapOf(
        "quarkus.redis.devservices.enabled" to "true",
        "quarkus.redis.devservices.image-name" to "redis/redis-stack-server:7.4.0-v3",
    )
}
