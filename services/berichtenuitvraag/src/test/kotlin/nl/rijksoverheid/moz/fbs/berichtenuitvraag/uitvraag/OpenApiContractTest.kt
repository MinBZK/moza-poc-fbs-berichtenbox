package nl.rijksoverheid.moz.fbs.berichtenuitvraag.uitvraag

import com.atlassian.oai.validator.OpenApiInteractionValidator
import com.atlassian.oai.validator.report.LevelResolver
import com.atlassian.oai.validator.report.ValidationReport
import com.atlassian.oai.validator.restassured.OpenApiValidationFilter
import com.github.tomakehurst.wiremock.client.WireMock.aResponse
import com.github.tomakehurst.wiremock.client.WireMock.anyRequestedFor
import com.github.tomakehurst.wiremock.client.WireMock.delete
import com.github.tomakehurst.wiremock.client.WireMock.get
import com.github.tomakehurst.wiremock.client.WireMock.patch
import com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo
import com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor
import com.github.tomakehurst.wiremock.client.WireMock.urlPathMatching
import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.junit.TestProfile
import io.restassured.RestAssured.given
import jakarta.inject.Inject
import nl.rijksoverheid.moz.fbs.berichtensessiecache.SessiecacheException
import nl.rijksoverheid.moz.fbs.berichtensessiecache.berichten.Bericht
import nl.rijksoverheid.moz.fbs.berichtensessiecache.berichten.BerichtenPagina
import nl.rijksoverheid.moz.fbs.berichtensessiecache.berichten.MagazijnStatus
import nl.rijksoverheid.moz.fbs.berichtensessiecache.berichten.NietGeleverd
import nl.rijksoverheid.moz.fbs.berichtensessiecache.berichten.Afgekapt
import nl.rijksoverheid.moz.fbs.berichtensessiecache.berichten.BijlageSamenvatting
import nl.rijksoverheid.moz.fbs.kern.identificatie.Bsn
import org.hamcrest.Matchers.contains
import org.hamcrest.Matchers.hasKey
import org.hamcrest.Matchers.not
import org.hamcrest.Matchers.equalTo
import org.hamcrest.Matchers.notNullValue
import org.hamcrest.Matchers.nullValue
import org.hamcrest.Matchers.startsWith
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource
import java.time.Duration
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID
import nl.rijksoverheid.moz.fbs.berichtensessiecache.berichten.MagazijnFoutStatus
import nl.rijksoverheid.moz.fbs.berichtensessiecache.berichten.Volledigheid
import org.hamcrest.Matchers.equalTo
import com.github.tomakehurst.wiremock.client.WireMock.patchRequestedFor
import com.github.tomakehurst.wiremock.client.WireMock.matchingJsonPath
import com.github.tomakehurst.wiremock.client.WireMock.equalTo as wmEqualTo
import org.junit.jupiter.params.provider.CsvSource
import org.junit.jupiter.params.provider.ValueSource

/**
 * Contract-tests: response-shapes per endpoint conform berichtenuitvraag-api.yaml.
 * De sessiecache-facade is in-memory ([MockSessiecache]); het magazijn is een
 * WireMock. Tolerantie op `validation.response.body.schema.type` op WARN —
 * voorkomt false positives bij null-velden in HAL-_links.
 *
 * De HAL `_links.*.href` zijn bewust relatieve URI-references (`/api/v1/...`);
 * networknt 2.x (via openapi-request-validator) dwingt `format: uri` sinds deze
 * versie strikt als absolute RFC 3986 URI af, dus ook die assertie op WARN zodat
 * de contractcheck het vorige gedrag behoudt. TODO(#76): spec aanlijnen op
 * `uri-reference` en deze downgrade verwijderen.
 */
@QuarkusTest
@TestProfile(MockSessiecacheProfile::class)
@QuarkusTestResource(value = WireMockBackendsResource::class, restrictToAnnotatedClass = true)
class OpenApiContractTest {

    private val validator = OpenApiValidationFilter(
        OpenApiInteractionValidator
            .createForSpecificationUrl("openapi/berichtenuitvraag-api.yaml")
            .withLevelResolver(
                LevelResolver.create()
                    .withLevel("validation.response.body.schema.type", ValidationReport.Level.WARN)
                    .withLevel("validation.response.body.schema.format.uri", ValidationReport.Level.WARN)
                    .build(),
            )
            .build(),
    )

    private val ontvanger = "BSN:999990019"

    @Inject
    lateinit var sessiecache: MockSessiecache

    /** Een lopende sessie van de standaard-ontvanger; zonder die weigert de sessiecontrole elk berichtenpad. */
    private lateinit var sessie: String

    @BeforeEach
    fun reset() {
        sessiecache.reset()
        sessie = sessiecache.sessieVoor()
        WireMockBackendsResource.magazijnA.resetAll()
    }

    private fun seedBericht(
        berichtId: UUID,
        magazijnId: String = WireMockBackendsResource.OIN_A,
        bijlagen: List<BijlageSamenvatting> = emptyList(),
    ): Bericht {
        val bericht = Bericht(
            berichtId = berichtId,
            afzender = "00000001003214345000",
            afzenderNaam = "Magazijn A",
            ontvanger = Bsn("999990019"),
            onderwerp = "Test",
            publicatietijdstip = Instant.parse("2026-05-26T10:00:00Z"),
            magazijnId = magazijnId,
            aantalBijlagen = bijlagen.size,
            bijlagen = bijlagen,
        )
        sessiecache.zet(bericht)

        return bericht
    }

    @Test
    fun `GET berichten levert valide BerichtenLijst`() {
        // Twee bronnen in één antwoord, zodat de validator een lijst met meerdere
        // `afzenderNaam`-waarden ziet en niet alleen de default-seed.
        seedBericht(UUID.randomUUID(), magazijnId = WireMockBackendsResource.OIN_B)
        seedBericht(UUID.randomUUID())

        given()
            .filter(validator)
            .header("X-Ontvanger", ontvanger)
            .header("X-Sessie", sessie)
            .`when`()
            .get("/api/v1/berichten")
            .then()
            .statusCode(200)
    }

    /** Elke status die een niet-geleverde organisatie kan dragen, moet door de schema-validatie komen. */
    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = ["/api/v1/berichten", "/api/v1/berichten/_zoeken?q=rente"])
    fun `lijst en zoeken met niet-geleverde organisaties leveren valide BerichtenLijst`(pad: String) {
        sessiecache.lijstResultaat = BerichtenPagina(
            berichten = emptyList(),
            page = 0,
            pageSize = 20,
            totalElements = 0L,
            totalPages = 0,
            volledigheid = Volledigheid(
                aantalNietGeleverd = 4,
                nietGeleverd = listOf(
                    NietGeleverd(WireMockBackendsResource.OIN_A, "Organisatie A", MagazijnFoutStatus.FOUT),
                    NietGeleverd(WireMockBackendsResource.OIN_B, "Organisatie B", MagazijnFoutStatus.TIMEOUT),
                    NietGeleverd("00000001003214345000", "Organisatie C", MagazijnFoutStatus.NIET_OPGEHAALD),
                ),
            ),
        )

        given()
            .filter(validator)
            .header("X-Ontvanger", ontvanger)
            .header("X-Sessie", sessie)
            .`when`()
            .get(pad)
            .then()
            .statusCode(200)
            .body("aantalNietGeleverd", equalTo(4))
            .body("nietGeleverd.status", contains("FOUT", "TIMEOUT", "NIET_OPGEHAALD"))
    }

    /** Met en zonder totaal: het optionele veld moet ontbreken, niet `null` zijn. */
    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = ["/api/v1/berichten", "/api/v1/berichten/_zoeken?q=rente"])
    fun `lijst en zoeken met afgekapte organisaties leveren valide BerichtenLijst`(pad: String) {
        sessiecache.lijstResultaat = BerichtenPagina(
            berichten = emptyList(),
            page = 0,
            pageSize = 20,
            totalElements = 0L,
            totalPages = 0,
            volledigheid = Volledigheid(
                aantalNietGeleverd = 0,
                nietGeleverd = emptyList(),
                afgekapt = listOf(
                    Afgekapt(WireMockBackendsResource.OIN_A, "Organisatie A", 500, 812L),
                    Afgekapt(WireMockBackendsResource.OIN_B, "Organisatie B", 500),
                ),
            ),
        )

        given()
            .filter(validator)
            .header("X-Ontvanger", ontvanger)
            .header("X-Sessie", sessie)
            .`when`()
            .get(pad)
            .then()
            .statusCode(200)
            .body("afgekapt.magazijnId", contains(WireMockBackendsResource.OIN_A, WireMockBackendsResource.OIN_B))
            .body("afgekapt[0].totaalBeschikbaar", equalTo(812))
            .body("afgekapt[1]", not(hasKey("totaalBeschikbaar")))
    }

    @Test
    fun `GET berichten-zoeken levert valide BerichtenLijst`() {
        given()
            .filter(validator)
            .header("X-Ontvanger", ontvanger)
            .header("X-Sessie", sessie)
            .queryParam("q", "rente")
            .`when`()
            .get("/api/v1/berichten/_zoeken")
            .then()
            .statusCode(200)
    }

    @Test
    fun `GET bericht by id levert valide Bericht`() {
        val id = UUID.randomUUID()
        seedBericht(id)
        WireMockBackendsResource.magazijnA.stubFor(
            get(urlPathEqualTo("/api/v1/berichten/$id")).willReturn(
                aResponse().withStatus(200)
                    .withHeader("Content-Type", "application/json")
                    .withBody("""{"inhoud": "Tekst uit het magazijn"}"""),
            ),
        )

        given()
            .filter(validator)
            .header("X-Ontvanger", ontvanger)
            .header("X-Sessie", sessie)
            .`when`()
            .get("/api/v1/berichten/$id?magazijnId=${WireMockBackendsResource.OIN_A}")
            .then()
            .statusCode(200)
    }

    @Test
    fun `PATCH bericht doet dual-write en levert valide Bericht`() {
        val id = UUID.randomUUID()
        val bijlageId = UUID.randomUUID()
        // Met bijlage, zodat de validator ook op dit pad de verplichte bijlage-link ziet.
        seedBericht(id, bijlagen = listOf(BijlageSamenvatting(bijlageId, "factuur.pdf")))
        WireMockBackendsResource.magazijnA.stubFor(
            patch(urlPathMatching("/api/v1/berichten/$id"))
                .willReturn(aResponse().withStatus(204)),
        )

        given()
            .filter(validator)
            .header("X-Ontvanger", ontvanger)
            .header("X-Sessie", sessie)
            .header("Content-Type", "application/merge-patch+json")
            .body("""{"status":"gelezen"}""")
            .`when`()
            .patch("/api/v1/berichten/$id?magazijnId=${WireMockBackendsResource.OIN_A}")
            .then()
            .statusCode(200)
            .body(
                "bijlagen[0]._links.self.href",
                equalTo("/api/v1/berichten/$id/bijlagen/$bijlageId?magazijnId=${WireMockBackendsResource.OIN_A}"),
            )
    }

    /** De lege string is een geldige waarde voor `map` (terug naar Postvak IN); de respons draagt dan geen map. */
    @Test
    fun `PATCH met lege map levert valide Bericht zonder map`() {
        val id = UUID.randomUUID()
        seedBericht(id)
        WireMockBackendsResource.magazijnA.stubFor(
            patch(urlPathMatching("/api/v1/berichten/$id"))
                .willReturn(aResponse().withStatus(204)),
        )

        given()
            .filter(validator)
            .header("X-Ontvanger", ontvanger)
            .header("X-Sessie", sessie)
            .header("Content-Type", "application/merge-patch+json")
            .body("""{"map":""}""")
            .`when`()
            .patch("/api/v1/berichten/$id?magazijnId=${WireMockBackendsResource.OIN_A}")
            .then()
            .statusCode(200)
            .body("\$", not(hasKey("map")))

        // De lege string moet bij het magazijn aankomen: valt hij onderweg weg (bv. door een
        // NON_EMPTY-serialisatie), dan krijgt het magazijn een lege patch en wist er niets.
        WireMockBackendsResource.magazijnA.verify(
            patchRequestedFor(urlPathEqualTo("/api/v1/berichten/$id"))
                .withRequestBody(matchingJsonPath("\$.map", wmEqualTo(""))),
        )
    }

    /** Witruimte is geen wis-waarde; de 400 hoort een geldige problem+json te zijn en het magazijn niet te raken. */
    @ParameterizedTest(name = "map=\"{0}\"")
    @ValueSource(strings = [" ", "   ", "\t", "\n "])
    fun `PATCH met een mapnaam van alleen witruimte levert valide Problem-400 zonder magazijn-write`(map: String) {
        val id = UUID.randomUUID()
        seedBericht(id)

        given()
            .filter(validator)
            .header("X-Ontvanger", ontvanger)
            .header("X-Sessie", sessie)
            .header("Content-Type", "application/merge-patch+json")
            .body(mapOf("map" to map))
            .`when`()
            .patch("/api/v1/berichten/$id?magazijnId=${WireMockBackendsResource.OIN_A}")
            .then()
            .statusCode(400)
            .contentType("application/problem+json")

        WireMockBackendsResource.magazijnA.verify(0, patchRequestedFor(urlPathEqualTo("/api/v1/berichten/$id")))
    }

    @Test
    fun `DELETE bericht doet dual-write en geeft 204`() {
        val id = UUID.randomUUID()
        seedBericht(id)
        WireMockBackendsResource.magazijnA.stubFor(
            delete(urlPathMatching("/api/v1/berichten/$id"))
                .willReturn(aResponse().withStatus(204)),
        )

        given()
            .filter(validator)
            .header("X-Ontvanger", ontvanger)
            .header("X-Sessie", sessie)
            .`when`()
            .delete("/api/v1/berichten/$id?magazijnId=${WireMockBackendsResource.OIN_A}")
            .then()
            .statusCode(204)
    }

    @Test
    fun `GET bijlage streamt bytes met juist Content-Type`() {
        val berichtId = UUID.randomUUID()
        val bijlageId = UUID.randomUUID()
        // De service haalt eerst het bericht op om de magazijnId te bepalen, en haalt
        // de bestandsnaam uit datzelfde detail.
        seedBericht(berichtId, bijlagen = listOf(BijlageSamenvatting(bijlageId, "aanslag 2026.pdf")))
        WireMockBackendsResource.magazijnA.stubFor(
            get(urlPathEqualTo("/api/v1/berichten/$berichtId/bijlagen/$bijlageId"))
                .willReturn(
                    aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/pdf")
                        .withBody(byteArrayOf(37, 80, 68, 70)), // "%PDF"
                ),
        )

        given()
            .filter(validator)
            .header("X-Ontvanger", ontvanger)
            .header("X-Sessie", sessie)
            .`when`()
            .get("/api/v1/berichten/$berichtId/bijlagen/$bijlageId?magazijnId=${WireMockBackendsResource.OIN_A}")
            .then()
            .statusCode(200)
            .header("Content-Type", "application/pdf")
            .header("Content-Disposition", "inline; filename=\"aanslag_2026.pdf\"; filename*=UTF-8''aanslag%202026.pdf")
    }

    @Test
    fun `GET bijlage via de self-link uit het berichtdetail levert die bijlage`() {
        val berichtId = UUID.randomUUID()
        val eerste = UUID.randomUUID()
        val tweede = UUID.randomUUID()
        seedBericht(
            berichtId,
            bijlagen = listOf(BijlageSamenvatting(eerste, "eerste.pdf"), BijlageSamenvatting(tweede, "tweede.png")),
        )
        WireMockBackendsResource.magazijnA.stubFor(
            get(urlPathEqualTo("/api/v1/berichten/$berichtId")).willReturn(
                aResponse().withStatus(200)
                    .withHeader("Content-Type", "application/json")
                    .withBody("""{"inhoud": "Tekst"}"""),
            ),
        )
        WireMockBackendsResource.magazijnA.stubFor(
            get(urlPathEqualTo("/api/v1/berichten/$berichtId/bijlagen/$tweede")).willReturn(
                aResponse().withStatus(200)
                    .withHeader("Content-Type", "image/png")
                    .withBody(byteArrayOf(-119, 80, 78, 71)), // PNG-signatuur
            ),
        )

        // De tweede bijlage volgen: bij één bijlage zou een link die altijd naar de eerste
        // wijst niet opvallen.
        val href: String = given()
            .filter(validator)
            .header("X-Ontvanger", ontvanger)
            .`when`()
            .get("/api/v1/berichten/$berichtId?magazijnId=${WireMockBackendsResource.OIN_A}")
            .then()
            .statusCode(200)
            .extract()
            .path("bijlagen[1]._links.self.href")

        given()
            .filter(validator)
            .header("X-Ontvanger", ontvanger)
            .`when`()
            .get(href)
            .then()
            .statusCode(200)
            .header("Content-Type", "image/png")
            .header("Content-Disposition", startsWith("inline; filename=\"tweede.png\""))
    }

    // --- Foutresponses tegen het Problem-schema (RFC 9457), via de validator ---

    @Test
    fun `GET bericht by id - twee magazijnen met hetzelfde nummer leveren elk hun eigen bericht`() {
        WireMockBackendsResource.magazijnB.resetAll()
        val gedeeld = UUID.randomUUID()
        seedBericht(gedeeld, magazijnId = WireMockBackendsResource.OIN_A)
        seedBericht(gedeeld, magazijnId = WireMockBackendsResource.OIN_B)

        listOf(WireMockBackendsResource.magazijnA to "Tekst van A", WireMockBackendsResource.magazijnB to "Tekst van B")
            .forEach { (magazijn, tekst) ->
                magazijn.stubFor(
                    get(urlPathEqualTo("/api/v1/berichten/$gedeeld")).willReturn(
                        aResponse().withStatus(200)
                            .withHeader("Content-Type", "application/json")
                            .withBody("""{"inhoud": "$tekst"}"""),
                    ),
                )
            }

        listOf(WireMockBackendsResource.OIN_A to "Tekst van A", WireMockBackendsResource.OIN_B to "Tekst van B")
            .forEach { (oin, tekst) ->
                given()
                    .filter(validator)
                    .header("X-Ontvanger", ontvanger)
                    .header("X-Sessie", sessie)
                    .`when`()
                    .get("/api/v1/berichten/$gedeeld?magazijnId=$oin")
                    .then()
                    .statusCode(200)
                    .body("magazijnId", equalTo(oin))
                    .body("inhoud", equalTo(tekst))
                    .body("_links.self.href", equalTo("/api/v1/berichten/$gedeeld?magazijnId=$oin"))
            }
    }

    @Test
    fun `GET bericht by id zonder magazijnId levert 400 problem+json`() {
        val id = UUID.randomUUID()
        seedBericht(id)

        given()
            .header("X-Ontvanger", ontvanger)
            .header("X-Sessie", sessie)
            .`when`()
            .get("/api/v1/berichten/$id")
            .then()
            .statusCode(400)
            .contentType("application/problem+json")
    }

    @ParameterizedTest(name = "magazijnId={0}")
    @ValueSource(strings = ["00000009999999999999", WireMockBackendsResource.OIN_B])
    fun `GET bericht by id met een magazijnId waaronder het bericht niet staat levert 404`(magazijnId: String) {
        WireMockBackendsResource.magazijnB.resetAll()
        val id = UUID.randomUUID()
        seedBericht(id)

        given()
            .filter(validator)
            .header("X-Ontvanger", ontvanger)
            .header("X-Sessie", sessie)
            .queryParam("magazijnId", magazijnId)
            .`when`()
            .get("/api/v1/berichten/$id")
            .then()
            .statusCode(404)
            .contentType("application/problem+json")
            .body("type", equalTo("urn:fbs:fout:bericht-onbekend"))

        // Geen van beide magazijnen: ook niet het magazijn dat in het verzoek genoemd wordt.
        WireMockBackendsResource.magazijnA.verify(0, getRequestedFor(urlPathEqualTo("/api/v1/berichten/$id")))
        WireMockBackendsResource.magazijnB.verify(0, getRequestedFor(urlPathEqualTo("/api/v1/berichten/$id")))
    }

    // Alleen een OIN komt door: de waarde belandt in sleutels en logregels, en mag daar geen BSN of
    // regelafbreking binnensmokkelen.
    @ParameterizedTest(name = "magazijnId={0}")
    @ValueSource(strings = ["", "999990019", "a:b", "0000000100000000000\r\nX", "000000010000000000001"])
    fun `GET bericht by id met een magazijnId dat geen OIN is levert 400 problem+json`(magazijnId: String) {
        val id = UUID.randomUUID()
        seedBericht(id)

        given()
            .header("X-Ontvanger", ontvanger)
            .header("X-Sessie", sessie)
            .queryParam("magazijnId", magazijnId)
            .`when`()
            .get("/api/v1/berichten/$id")
            .then()
            .statusCode(400)
            .contentType("application/problem+json")
    }

    @Test
    fun `GET bijlage van een bericht met hetzelfde nummer bij een ander magazijn gaat naar dat magazijn`() {
        WireMockBackendsResource.magazijnB.resetAll()
        val gedeeld = UUID.randomUUID()
        val bijlageId = UUID.randomUUID()
        seedBericht(gedeeld, magazijnId = WireMockBackendsResource.OIN_A)
        seedBericht(gedeeld, magazijnId = WireMockBackendsResource.OIN_B, bijlagen = listOf(BijlageSamenvatting(bijlageId, "b.pdf")))
        WireMockBackendsResource.magazijnB.stubFor(
            get(urlPathEqualTo("/api/v1/berichten/$gedeeld/bijlagen/$bijlageId")).willReturn(
                aResponse().withStatus(200).withHeader("Content-Type", "application/pdf").withBody(byteArrayOf(37, 80, 68, 70)),
            ),
        )

        given()
            .filter(validator)
            .header("X-Ontvanger", ontvanger)
            .header("X-Sessie", sessie)
            .`when`()
            .get("/api/v1/berichten/$gedeeld/bijlagen/$bijlageId?magazijnId=${WireMockBackendsResource.OIN_B}")
            .then()
            .statusCode(200)

        WireMockBackendsResource.magazijnA.verify(0, getRequestedFor(urlPathEqualTo("/api/v1/berichten/$gedeeld/bijlagen/$bijlageId")))
    }

    @Test
    fun `GET bijlage met een magazijnId waaronder het bericht niet staat levert 404 zonder magazijn-aanroep`() {
        WireMockBackendsResource.magazijnB.resetAll()
        val id = UUID.randomUUID()
        val bijlageId = UUID.randomUUID()
        seedBericht(id, bijlagen = listOf(BijlageSamenvatting(bijlageId, "a.pdf")))

        given()
            .filter(validator)
            .header("X-Ontvanger", ontvanger)
            .header("X-Sessie", sessie)
            .`when`()
            .get("/api/v1/berichten/$id/bijlagen/$bijlageId?magazijnId=${WireMockBackendsResource.OIN_B}")
            .then()
            .statusCode(404)
            .contentType("application/problem+json")
            .body("type", equalTo("urn:fbs:fout:bericht-onbekend"))

        WireMockBackendsResource.magazijnA.verify(0, getRequestedFor(urlPathEqualTo("/api/v1/berichten/$id/bijlagen/$bijlageId")))
        WireMockBackendsResource.magazijnB.verify(0, getRequestedFor(urlPathEqualTo("/api/v1/berichten/$id/bijlagen/$bijlageId")))
    }

    @Test
    fun `GET bijlage zonder magazijnId levert 400 problem+json`() {
        given()
            .header("X-Ontvanger", ontvanger)
            .header("X-Sessie", sessie)
            .`when`()
            .get("/api/v1/berichten/${UUID.randomUUID()}/bijlagen/${UUID.randomUUID()}")
            .then()
            .statusCode(400)
            .contentType("application/problem+json")
    }

    @Test
    fun `GET bericht by id - cache-miss levert valide Problem-404`() {
        given()
            .filter(validator)
            .header("X-Ontvanger", ontvanger)
            .header("X-Sessie", sessie)
            .`when`()
            .get("/api/v1/berichten/${UUID.randomUUID()}?magazijnId=${WireMockBackendsResource.OIN_A}")
            .then()
            .statusCode(404)
            .contentType("application/problem+json")
    }

    @Test
    fun `GET bijlage - magazijn-5xx levert valide Problem-502`() {
        val berichtId = UUID.randomUUID()
        val bijlageId = UUID.randomUUID()
        seedBericht(berichtId)
        WireMockBackendsResource.magazijnA.stubFor(
            get(urlPathEqualTo("/api/v1/berichten/$berichtId/bijlagen/$bijlageId"))
                .willReturn(aResponse().withStatus(500)),
        )

        given()
            .filter(validator)
            .header("X-Ontvanger", ontvanger)
            .header("X-Sessie", sessie)
            .`when`()
            .get("/api/v1/berichten/$berichtId/bijlagen/$bijlageId?magazijnId=${WireMockBackendsResource.OIN_A}")
            .then()
            .statusCode(502)
            .contentType("application/problem+json")
    }

    @Test
    fun `GET bericht by id - cache nog niet gevuld levert valide Problem-409`() {
        // De gereed-status-gating geldt óók op het detail-pad: NogNietGevuld propageert
        // status-behoudend als 409 (client-aanwijzing: eerst _ophalen) en moet als
        // gedeclareerde Problem-409 valideren tegen de spec.
        val id = UUID.randomUUID()
        sessiecache.berichtFout =
            SessiecacheException.NogNietGevuld("Berichten zijn nog niet opgehaald.")

        given()
            .filter(validator)
            .header("X-Ontvanger", ontvanger)
            .header("X-Sessie", sessie)
            .`when`()
            .get("/api/v1/berichten/$id?magazijnId=${WireMockBackendsResource.OIN_A}")
            .then()
            .statusCode(409)
            .contentType("application/problem+json")
    }

    @Test
    fun `GET berichten-volgen - cache nog niet gevuld levert valide Problem-409`() {
        // De SSE-body van een 200 kan de validator niet lezen, een fout vóór de stream wel.
        sessiecache.volgFout =
            SessiecacheException.NogNietGevuld("Berichten zijn nog niet opgehaald.")

        given()
            .filter(validator)
            .header("X-Ontvanger", ontvanger)
            .header("X-Sessie", sessie)
            .header("Accept", "text/event-stream")
            .`when`()
            .get("/api/v1/berichten/_volgen")
            .then()
            .statusCode(409)
            .contentType("application/problem+json")
            .header("API-Version", notNullValue())
    }

    @Test
    fun `GET berichten-volgen - opslag onbereikbaar levert valide Problem-503 met Retry-After`() {
        sessiecache.volgFout =
            SessiecacheException.Onbereikbaar("Cache niet bereikbaar.")

        given()
            .filter(validator)
            .header("X-Ontvanger", ontvanger)
            .header("X-Sessie", sessie)
            .header("Accept", "text/event-stream")
            .`when`()
            .get("/api/v1/berichten/_volgen")
            .then()
            .statusCode(503)
            .contentType("application/problem+json")
            .header("Retry-After", "30")
    }

    /** De sessiecontrole raakt de opslag vóór het magazijn: valt die weg, dan een 503 die de spec kent. */
    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = ["PATCH", "DELETE"])
    fun `een onbereikbare opslag in de sessiecontrole levert valide Problem-503 met Retry-After`(methode: String) {
        val id = UUID.randomUUID()
        seedBericht(id)
        sessiecache.raakFout = SessiecacheException.Onbereikbaar("Cache niet bereikbaar.")

        given()
            .filter(validator)
            .header("X-Ontvanger", ontvanger)
            .header("X-Sessie", sessie)
            .apply { if (methode == "PATCH") contentType("application/merge-patch+json").body("""{"status":"gelezen"}""") }
            .`when`()
            .request(methode, "/api/v1/berichten/$id?magazijnId=${WireMockBackendsResource.OIN_A}")
            .then()
            .statusCode(503)
            .contentType("application/problem+json")
            .header("Retry-After", "30")

        WireMockBackendsResource.magazijnA.verify(0, anyRequestedFor(urlPathEqualTo("/api/v1/berichten/$id")))
    }

    @Test
    fun `GET bericht by id - zelf verwijderd levert valide Problem-410 met kenmerk`() {
        val id = UUID.randomUUID()
        sessiecache.berichtFout =
            SessiecacheException.BerichtVerwijderd("weg")

        given()
            .filter(validator)
            .header("X-Ontvanger", ontvanger)
            .header("X-Sessie", sessie)
            .`when`()
            .get("/api/v1/berichten/$id?magazijnId=${WireMockBackendsResource.OIN_A}")
            .then()
            .statusCode(410)
            .contentType("application/problem+json")
            .body("type", equalTo("urn:fbs:fout:bericht-verwijderd"))
    }

    @Test
    fun `GET bericht by id - onbekend bericht levert 404 met het kenmerk bericht-onbekend`() {
        // Ononderscheidbaar van het bericht van een andere ondernemer: de cache is per
        // ontvanger gesleuteld, dus beide gevallen komen hier als dezelfde misser binnen.
        given()
            .filter(validator)
            .header("X-Ontvanger", ontvanger)
            .header("X-Sessie", sessie)
            .`when`()
            .get("/api/v1/berichten/${UUID.randomUUID()}?magazijnId=${WireMockBackendsResource.OIN_A}")
            .then()
            .statusCode(404)
            .contentType("application/problem+json")
            .body("type", equalTo("urn:fbs:fout:bericht-onbekend"))
    }

    @Test
    fun `GET bijlage - zelf verwijderd bericht levert een antwoord dat de spec kent`() {
        // De bijlage-download begint met dezelfde cache-opzoeking als het bericht zelf, dus de
        // tombstone slaat hier net zo goed toe — vóór het magazijn ooit gebeld wordt. Zonder
        // gedeclareerde 410 op dit pad zou dat een spec-schending zijn.
        sessiecache.berichtFout =
            SessiecacheException.BerichtVerwijderd("weg")

        given()
            .filter(validator)
            .header("X-Ontvanger", ontvanger)
            .header("X-Sessie", sessie)
            .`when`()
            .get("/api/v1/berichten/${UUID.randomUUID()}/bijlagen/${UUID.randomUUID()}?magazijnId=${WireMockBackendsResource.OIN_A}")
            .then()
            .statusCode(410)
            .body("type", equalTo("urn:fbs:fout:bericht-verwijderd"))
    }

    /**
     * De belofte van dit kenmerk is dat élk foutantwoord het draagt, ook de statussen die het
     * framework zelf produceert zonder onze mapper te raadplegen. Dat is precies wat een
     * unit-test op de mapper niet kan aantonen: die bewijst dat de mapper het goed doet, niet
     * dat hij aan bod komt. Geen validator-filter — deze requests schenden de spec met opzet.
     */
    @ParameterizedTest
    @MethodSource("kromRequests")
    fun `elk foutantwoord van de service draagt een ketenkenmerk`(krom: KromRequest) {
        krom.stuur()
            .then()
            .contentType("application/problem+json")
            .body("type", startsWith("urn:fbs:fout:"))
    }

    @Test
    fun `GET berichten - cache nog niet gevuld levert valide Problem-409`() {
        sessiecache.lijstFout = SessiecacheException.NogNietGevuld("Berichten zijn nog niet opgehaald.")

        given()
            .filter(validator)
            .header("X-Ontvanger", ontvanger)
            .header("X-Sessie", sessie)
            .`when`()
            .get("/api/v1/berichten")
            .then()
            .statusCode(409)
            .contentType("application/problem+json")
    }

    // --- Sessies: start, einde, en de weigeringen die erbij horen ---

    @Test
    fun `POST sessies levert valide Sessie met 201 en Location`() {
        given().contentType("application/json")
            .filter(validator)
            .header("X-Ontvanger", ontvanger)
            .`when`()
            .post("/api/v1/sessies")
            .then()
            .statusCode(201)
            .header("Location", "/api/v1/sessies/huidige")
            .body("geldigTot", notNullValue())
            .body("uiterlijkGeldigTot", notNullValue())
            .body("_links.self.href", equalTo("/api/v1/sessies/huidige"))
            .body("_links.verlengen.href", equalTo("/api/v1/sessies/huidige/_verlengen"))
    }

    @Test
    fun `POST sessies met een plafond levert valide Sessie met 201`() {
        val plafond = Instant.now().plusSeconds(600).truncatedTo(ChronoUnit.SECONDS)

        given()
            .filter(validator)
            .header("X-Ontvanger", ontvanger)
            .contentType("application/json")
            .body("""{"uiterlijkGeldigTot":"$plafond"}""")
            .`when`()
            .post("/api/v1/sessies")
            .then()
            .statusCode(201)
            .body("uiterlijkGeldigTot", equalTo(plafond.toString()))
    }

    @Test
    fun `POST sessies met een plafond in het verleden levert valide Problem-400`() {
        given()
            .filter(validator)
            .header("X-Ontvanger", ontvanger)
            .contentType("application/json")
            .body("""{"uiterlijkGeldigTot":"2020-01-01T00:00:00Z"}""")
            .`when`()
            .post("/api/v1/sessies")
            .then()
            .statusCode(400)
            .contentType("application/problem+json")
            .body("type", equalTo("urn:fbs:fout:ongeldig-verzoek"))
    }

    @Test
    fun `POST sessies boven de grens levert valide Problem-429 met Retry-After`() {
        repeat(4) { sessiecache.sessieVoor(ontvanger) }
        sessiecache.vrijOverBijGrens = Duration.ofMillis(89_100)

        given().contentType("application/json")
            .filter(validator)
            .header("X-Ontvanger", ontvanger)
            .`when`()
            .post("/api/v1/sessies")
            .then()
            .statusCode(429)
            .contentType("application/problem+json")
            .header("Retry-After", "90")
            .body("type", equalTo("urn:fbs:fout:te-veel-sessies"))
    }

    @Test
    fun `GET sessies-huidige levert valide SessieGeldigheid zonder sessieId`() {
        given()
            .filter(validator)
            .header("X-Ontvanger", ontvanger)
            .header("X-Sessie", sessie)
            .`when`()
            .get("/api/v1/sessies/huidige")
            .then()
            .statusCode(200)
            .header("API-Version", notNullValue())
            .body("geldigTot", notNullValue())
            .body("uiterlijkGeldigTot", notNullValue())
            .body("sessieId", nullValue())
            .body("_links.self.href", equalTo("/api/v1/sessies/huidige"))
    }

    @Test
    fun `POST sessies-huidige-verlengen levert valide SessieGeldigheid met 200`() {
        given()
            .filter(validator)
            .header("X-Ontvanger", ontvanger)
            .header("X-Sessie", sessie)
            .`when`()
            .post("/api/v1/sessies/huidige/_verlengen")
            .then()
            // Een actie op een bestaande sessie, geen aanmaak: dus geen 201.
            .statusCode(200)
            .body("geldigTot", notNullValue())
            .body("sessieId", nullValue())
    }

    @Test
    fun `DELETE sessies-huidige geeft 204`() {
        given()
            .filter(validator)
            .header("X-Ontvanger", ontvanger)
            .header("X-Sessie", sessie)
            .`when`()
            .delete("/api/v1/sessies/huidige")
            .then()
            .statusCode(204)
    }

    // Goed gevormd maar nooit gestart: zo blijft het verzoek binnen de spec en toetst de validator
    // het antwoord. Een ontbrekende header zou al op de request-validatie stranden.
    @ParameterizedTest
    @CsvSource(
        "GET, /api/v1/berichten",
        "GET, /api/v1/berichten/_zoeken?q=rente",
        "GET, /api/v1/berichten/_volgen",
        "GET, /api/v1/sessies/huidige",
        "POST, /api/v1/sessies/huidige/_verlengen",
    )
    fun `een onbekende sessie levert valide Problem-401 met WWW-Authenticate`(methode: String, pad: String) {
        given()
            .filter(validator)
            .header("X-Ontvanger", ontvanger)
            .header("X-Sessie", "A".repeat(22))
            .`when`()
            .request(methode, pad)
            .then()
            .statusCode(401)
            .contentType("application/problem+json")
            .header("WWW-Authenticate", "X-Sessie realm=\"berichtenuitvraag\"")
            .body("type", equalTo("urn:fbs:fout:sessie-ongeldig"))
    }

    @ParameterizedTest
    @ValueSource(strings = ["/api/v1/sessies/huidige", "/api/v1/sessies/huidige/_verlengen", "/api/v1/berichten"])
    fun `een beeindigde sessie levert valide Problem-401 met WWW-Authenticate`(pad: String) {
        sessiecache.beeindigSessie(sessie, Bsn("999990019"))

        given()
            .filter(validator)
            .header("X-Ontvanger", ontvanger)
            .header("X-Sessie", sessie)
            .`when`()
            .request(if (pad.endsWith("_verlengen")) "POST" else "GET", pad)
            .then()
            .statusCode(401)
            .header("WWW-Authenticate", "X-Sessie realm=\"berichtenuitvraag\"")
            .body("type", equalTo("urn:fbs:fout:sessie-ongeldig"))
    }

    /**
     * Ontbrekend, krom en herhaald: elke 401 draagt de challenge, ook die van het filter vóór de
     * resource. Geen validator: deze verzoeken missen het securityschema met opzet.
     */
    @ParameterizedTest(name = "{0} {1} met X-Sessie {2}")
    @CsvSource(
        "GET, /api/v1/berichten, ontbreekt",
        "GET, /api/v1/berichten, krom",
        "GET, /api/v1/berichten, herhaald",
        "GET, /api/v1/sessies/huidige, ontbreekt",
        "POST, /api/v1/sessies/huidige/_verlengen, herhaald",
        "DELETE, /api/v1/sessies/huidige, ontbreekt",
        "DELETE, /api/v1/sessies/huidige, krom",
        "DELETE, /api/v1/sessies/huidige, herhaald",
    )
    fun `elke 401 van het sessiefilter draagt WWW-Authenticate`(methode: String, pad: String, variant: String) {
        val verzoek = given().header("X-Ontvanger", ontvanger)

        when (variant) {
            "krom" -> verzoek.header("X-Sessie", "te-kort")
            "herhaald" -> verzoek.header("X-Sessie", sessie).header("X-Sessie", sessie)
        }

        verzoek.`when`()
            .request(methode, pad)
            .then()
            .statusCode(401)
            .contentType("application/problem+json")
            .header("WWW-Authenticate", "X-Sessie realm=\"berichtenuitvraag\"")
            .body("type", equalTo("urn:fbs:fout:sessie-ongeldig"))
    }

    @Test
    fun `een 401 uit de cache bij volgen draagt ook WWW-Authenticate`() {
        // Het filter liet hem door, maar de facade ziet de sessie verlopen: ook die 401 hoort de challenge te dragen.
        sessiecache.volgFout = SessiecacheException.SessieOngeldig("verlopen")

        given()
            .header("X-Ontvanger", ontvanger)
            .header("X-Sessie", sessie)
            .`when`()
            .get("/api/v1/berichten/_volgen")
            .then()
            .statusCode(401)
            .header("WWW-Authenticate", "X-Sessie realm=\"berichtenuitvraag\"")
    }

    // --- Input-validatie (400): X-Ontvanger en zoek-parameter `q` ---
    // Geen validator-filter: de triggerende request is bewust spec-ongeldig
    // (ontbrekende/kromme header, ontbrekende verplichte param). We borgen hier
    // dat de afwijzing 400 + application/problem+json is — de PII-invariant dat
    // een ongevalideerde X-Ontvanger nooit de LDV-audittrail of een magazijn raakt.

    @Test
    fun `GET berichten zonder X-Ontvanger levert 400 problem+json`() {
        given()
            .`when`()
            .get("/api/v1/berichten")
            .then()
            .statusCode(400)
            .contentType("application/problem+json")
    }

    @Test
    fun `GET berichten met malformed X-Ontvanger levert 400 problem+json`() {
        given()
            .header("X-Ontvanger", "GEENGELDIGTYPE:123")
            .header("X-Sessie", sessie)
            .`when`()
            .get("/api/v1/berichten")
            .then()
            .statusCode(400)
            .contentType("application/problem+json")
    }

    @Test
    fun `GET berichten-zoeken zonder verplichte q levert 400 problem+json`() {
        given()
            .header("X-Ontvanger", ontvanger)
            .header("X-Sessie", sessie)
            .`when`()
            .get("/api/v1/berichten/_zoeken")
            .then()
            .statusCode(400)
            .contentType("application/problem+json")
    }

    @Test
    fun `GET berichten-zoeken met te lange q levert 400 problem+json`() {
        given()
            .header("X-Ontvanger", ontvanger)
            .header("X-Sessie", sessie)
            .queryParam("q", "x".repeat(201))
            .`when`()
            .get("/api/v1/berichten/_zoeken")
            .then()
            .statusCode(400)
            .contentType("application/problem+json")
    }

    // Borgt dat de generator de spec-grenzen op de query-parameters daadwerkelijk als
    // Bean Validation afdwingt: een out-of-range pagina/paginaGrootte mag niet stilletjes
    // naar de cache doorlekken maar hoort 400 te geven.

    @Test
    fun `GET berichten met paginaGrootte boven maximum levert 400 problem+json`() {
        given()
            .header("X-Ontvanger", ontvanger)
            .header("X-Sessie", sessie)
            .queryParam("paginaGrootte", 201)
            .`when`()
            .get("/api/v1/berichten")
            .then()
            .statusCode(400)
            .contentType("application/problem+json")
    }

    @Test
    fun `GET berichten met paginaGrootte onder minimum levert 400 problem+json`() {
        given()
            .header("X-Ontvanger", ontvanger)
            .header("X-Sessie", sessie)
            .queryParam("paginaGrootte", 0)
            .`when`()
            .get("/api/v1/berichten")
            .then()
            .statusCode(400)
            .contentType("application/problem+json")
    }

    @Test
    fun `GET berichten met negatieve pagina levert 400 problem+json`() {
        given()
            .header("X-Ontvanger", ontvanger)
            .header("X-Sessie", sessie)
            .queryParam("pagina", -1)
            .`when`()
            .get("/api/v1/berichten")
            .then()
            .statusCode(400)
            .contentType("application/problem+json")
    }

    // Uitvraag harmoniseert `q` minLength met de cache (2), zodat een 1-teken-
    // zoekopdracht hier al op 400 strandt. Bewijst tevens dat de generator de
    // @Size(min=2)-constraint toepast.
    @Test
    fun `GET berichten-zoeken met te korte q levert 400 problem+json`() {
        given()
            .header("X-Ontvanger", ontvanger)
            .header("X-Sessie", sessie)
            .queryParam("q", "x")
            .`when`()
            .get("/api/v1/berichten/_zoeken")
            .then()
            .statusCode(400)
            .contentType("application/problem+json")
    }

    /** Eén spec-schendend request plus de naam die in de testuitvoer leesbaar is. */
    class KromRequest(private val naam: String, val stuur: () -> io.restassured.response.Response) {
        override fun toString() = naam
    }

    companion object {
        private const val ONTVANGER_HEADER = "BSN:999990019"

        @JvmStatic
        fun kromRequests(): List<KromRequest> = listOf(
            KromRequest("405 verkeerde methode") {
                given().header("X-Ontvanger", ONTVANGER_HEADER)
                    .`when`().post("/api/v1/berichten/${UUID.randomUUID()}")
            },
            KromRequest("415 niet-ondersteund request-mediatype") {
                given().header("X-Ontvanger", ONTVANGER_HEADER)
                    .contentType("text/plain")
                    .body("geen merge-patch")
                    .queryParam("magazijnId", "00000001003214345000")
                    .`when`().patch("/api/v1/berichten/${UUID.randomUUID()}")
            },
            KromRequest("404 onbekend pad") {
                given().header("X-Ontvanger", ONTVANGER_HEADER)
                    .`when`().get("/api/v1/bestaat-niet")
            },
        )
    }
}
