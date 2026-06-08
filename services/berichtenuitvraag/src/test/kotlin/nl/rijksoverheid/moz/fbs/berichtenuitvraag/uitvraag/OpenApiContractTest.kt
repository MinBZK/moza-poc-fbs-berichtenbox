package nl.rijksoverheid.moz.fbs.berichtenuitvraag.uitvraag

import com.atlassian.oai.validator.OpenApiInteractionValidator
import com.atlassian.oai.validator.report.LevelResolver
import com.atlassian.oai.validator.report.ValidationReport
import com.atlassian.oai.validator.restassured.OpenApiValidationFilter
import com.github.tomakehurst.wiremock.client.WireMock.aResponse
import com.github.tomakehurst.wiremock.client.WireMock.delete
import com.github.tomakehurst.wiremock.client.WireMock.get
import com.github.tomakehurst.wiremock.client.WireMock.patch
import com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo
import com.github.tomakehurst.wiremock.client.WireMock.urlPathMatching
import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.junit.TestProfile
import io.restassured.RestAssured.given
import jakarta.inject.Inject
import nl.rijksoverheid.moz.fbs.berichtensessiecache.berichten.Bericht
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID

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
@QuarkusTestResource(WireMockBackendsResource::class)
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

    @BeforeEach
    fun reset() {
        sessiecache.reset()
        WireMockBackendsResource.magazijn?.resetAll()
    }

    private fun seedBericht(berichtId: UUID, magazijnId: String = WireMockBackendsResource.OIN_A): Bericht {
        val bericht = Bericht(
            berichtId = berichtId,
            afzender = "00000001003214345000",
            ontvanger = "999990019",
            onderwerp = "Test",
            inhoud = "Inhoud",
            publicatietijdstip = Instant.parse("2026-05-26T10:00:00Z"),
            magazijnId = magazijnId,
            aantalBijlagen = 0,
        )
        sessiecache.berichten[berichtId] = bericht

        return bericht
    }

    @Test
    fun `GET berichten levert valide BerichtenLijst`() {
        seedBericht(UUID.randomUUID())

        given()
            .filter(validator)
            .header("X-Ontvanger", ontvanger)
            .`when`()
            .get("/api/v1/berichten")
            .then()
            .statusCode(200)
    }

    @Test
    fun `GET berichten-zoeken levert valide BerichtenLijst`() {
        given()
            .filter(validator)
            .header("X-Ontvanger", ontvanger)
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

        given()
            .filter(validator)
            .header("X-Ontvanger", ontvanger)
            .`when`()
            .get("/api/v1/berichten/$id")
            .then()
            .statusCode(200)
    }

    @Test
    fun `PATCH bericht doet dual-write en levert valide Bericht`() {
        val id = UUID.randomUUID()
        seedBericht(id)
        WireMockBackendsResource.magazijn!!.stubFor(
            patch(urlPathMatching("/api/v1/berichten/$id"))
                .willReturn(aResponse().withStatus(204)),
        )

        given()
            .filter(validator)
            .header("X-Ontvanger", ontvanger)
            .header("Content-Type", "application/merge-patch+json")
            .body("""{"status":"gelezen"}""")
            .`when`()
            .patch("/api/v1/berichten/$id?magazijnId=${WireMockBackendsResource.OIN_A}")
            .then()
            .statusCode(200)
    }

    @Test
    fun `DELETE bericht doet dual-write en geeft 204`() {
        val id = UUID.randomUUID()
        seedBericht(id)
        WireMockBackendsResource.magazijn!!.stubFor(
            delete(urlPathMatching("/api/v1/berichten/$id"))
                .willReturn(aResponse().withStatus(204)),
        )

        given()
            .filter(validator)
            .header("X-Ontvanger", ontvanger)
            .`when`()
            .delete("/api/v1/berichten/$id?magazijnId=${WireMockBackendsResource.OIN_A}")
            .then()
            .statusCode(204)
    }

    @Test
    fun `GET bijlage streamt bytes met juist Content-Type`() {
        val berichtId = UUID.randomUUID()
        val bijlageId = UUID.randomUUID()
        // De service haalt eerst het bericht op om de magazijnId te bepalen.
        seedBericht(berichtId)
        WireMockBackendsResource.magazijn!!.stubFor(
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
            .`when`()
            .get("/api/v1/berichten/$berichtId/bijlagen/$bijlageId")
            .then()
            .statusCode(200)
            .header("Content-Type", "application/pdf")
            .header("Content-Disposition", "attachment")
    }

    // --- Foutresponses tegen het Problem-schema (RFC 9457), via de validator ---

    @Test
    fun `GET bericht by id - cache-miss levert valide Problem-404`() {
        given()
            .filter(validator)
            .header("X-Ontvanger", ontvanger)
            .`when`()
            .get("/api/v1/berichten/${UUID.randomUUID()}")
            .then()
            .statusCode(404)
            .contentType("application/problem+json")
    }

    @Test
    fun `GET bijlage - magazijn-5xx levert valide Problem-502`() {
        val berichtId = UUID.randomUUID()
        val bijlageId = UUID.randomUUID()
        seedBericht(berichtId)
        WireMockBackendsResource.magazijn!!.stubFor(
            get(urlPathEqualTo("/api/v1/berichten/$berichtId/bijlagen/$bijlageId"))
                .willReturn(aResponse().withStatus(500)),
        )

        given()
            .filter(validator)
            .header("X-Ontvanger", ontvanger)
            .`when`()
            .get("/api/v1/berichten/$berichtId/bijlagen/$bijlageId")
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
            nl.rijksoverheid.moz.fbs.berichtensessiecache.SessiecacheException.NogNietGevuld("Berichten zijn nog niet opgehaald.")

        given()
            .filter(validator)
            .header("X-Ontvanger", ontvanger)
            .`when`()
            .get("/api/v1/berichten/$id")
            .then()
            .statusCode(409)
            .contentType("application/problem+json")
    }

    @Test
    fun `GET berichten - cache nog niet gevuld levert valide Problem-409`() {
        sessiecache.lijstFout = nl.rijksoverheid.moz.fbs.berichtensessiecache.SessiecacheException.NogNietGevuld("Berichten zijn nog niet opgehaald.")

        given()
            .filter(validator)
            .header("X-Ontvanger", ontvanger)
            .`when`()
            .get("/api/v1/berichten")
            .then()
            .statusCode(409)
            .contentType("application/problem+json")
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
            .queryParam("q", "x")
            .`when`()
            .get("/api/v1/berichten/_zoeken")
            .then()
            .statusCode(400)
            .contentType("application/problem+json")
    }
}
