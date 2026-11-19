package nl.rijksoverheid.moz.fbs.berichtenmagazijn.ophaal

import io.quarkus.test.junit.QuarkusTest
import io.restassured.RestAssured.given
import io.restassured.path.json.JsonPath
import io.restassured.specification.RequestSpecification
import jakarta.inject.Inject
import jakarta.transaction.Transactional
import nl.rijksoverheid.moz.fbs.berichtenmagazijn.ApiInfo
import nl.rijksoverheid.moz.fbs.berichtenmagazijn.opslag.Bericht
import nl.rijksoverheid.moz.fbs.berichtenmagazijn.opslag.BerichtRepository
import nl.rijksoverheid.moz.fbs.berichtenmagazijn.opslag.BerichtStatusRepository
import nl.rijksoverheid.moz.fbs.berichtenmagazijn.opslag.Bijlage
import nl.rijksoverheid.moz.fbs.berichtenmagazijn.opslag.BijlageRepository
import nl.rijksoverheid.moz.fbs.kern.identificatie.Bsn
import nl.rijksoverheid.moz.fbs.kern.identificatie.Oin
import org.eclipse.microprofile.config.ConfigProvider
import org.hamcrest.Matchers.equalTo
import org.hamcrest.Matchers.hasKey
import org.hamcrest.Matchers.hasSize
import org.hamcrest.Matchers.not
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import java.time.Instant
import java.util.UUID

/**
 * De HAL-links zijn absolute-path references: geen schema en geen host uit het verzoek. Achter een
 * ingress die TLS beëindigt komt het verzoek als plain http binnen, en buiten ZAD (compose, lokaal,
 * achter een andere proxy) kiest de aanroeper de `Host`-header; geen van beide mag in een link
 * terechtkomen.
 */
@QuarkusTest
class HalLinksIntegrationTest {

    @Inject lateinit var berichtRepository: BerichtRepository
    @Inject lateinit var bijlageRepository: BijlageRepository
    @Inject lateinit var statusRepository: BerichtStatusRepository

    @BeforeEach
    @Transactional
    fun cleanDatabase() {
        statusRepository.deleteAll()
        bijlageRepository.deleteAll()
        berichtRepository.deleteAll()
    }

    @Test
    fun `lijst negeert een vervalste Host en X-Forwarded-Proto http`() {
        repeat(3) { insertBericht() }

        val lijst = vervalst()
            .`when`().get("/api/v1/berichten?page=0&pageSize=2&afzender=$AFZENDER")
            .then()
            .statusCode(200)
            .body("_links.self.href", equalTo(lijst(page = 0, pageSize = 2, afzender = AFZENDER)))
            .body("_links.first.href", equalTo(lijst(page = 0, pageSize = 2, afzender = AFZENDER)))
            .body("_links.last.href", equalTo(lijst(page = 1, pageSize = 2, afzender = AFZENDER)))
            .body("_links.next.href", equalTo(lijst(page = 1, pageSize = 2, afzender = AFZENDER)))
            .body("berichten", hasSize<Any>(2))
            .extract().jsonPath()

        // Per bericht exact: een link die bij elk item naar hetzelfde bericht wijst, past ook op een
        // patroon.
        repeat(2) { i ->
            assertEquals(
                "/api/v1/berichten/${lijst.getString("berichten[$i].berichtId")}",
                lijst.getString("berichten[$i]._links.self.href"),
            )
        }
    }

    @Test
    fun `detail en bijlagen negeren een vervalste Host en X-Forwarded-Proto http`() {
        val bericht = insertBericht()
        val bijlagen = listOf(insertBijlage(bericht.berichtId), insertBijlage(bericht.berichtId))

        val detail = vervalst()
            .`when`().get("/api/v1/berichten/${bericht.berichtId}")
            .then()
            .statusCode(200)
            .body("_links.self.href", equalTo("/api/v1/berichten/${bericht.berichtId}"))
            .extract().jsonPath()

        assertEquals(verwachteBijlageLinks(bericht.berichtId, bijlagen), bijlageLinks(detail))
    }

    @Test
    fun `statuswijziging negeert een vervalste Host en X-Forwarded-Proto http`() {
        val bericht = insertBericht()
        val bijlagen = listOf(insertBijlage(bericht.berichtId), insertBijlage(bericht.berichtId))

        val gewijzigd = vervalst()
            .contentType("application/merge-patch+json")
            .body("""{"gelezen": true}""")
            .`when`().patch("/api/v1/berichten/${bericht.berichtId}")
            .then()
            .statusCode(200)
            .body("_links.self.href", equalTo("/api/v1/berichten/${bericht.berichtId}"))
            .extract().jsonPath()

        assertEquals(verwachteBijlageLinks(bericht.berichtId, bijlagen), bijlageLinks(gewijzigd))
    }

    /**
     * De links komen uit het base-path van de spec, de routering uit `quarkus.rest.path`. Lopen die
     * uiteen, dan wijst elke link naar een pad dat niet bestaat.
     */
    @Test
    fun `het base-path van de links is het pad waarop de API draait`() {
        assertEquals(ApiInfo.BASE_PATH, ConfigProvider.getConfig().getValue("quarkus.rest.path", String::class.java))
    }

    /**
     * Nul, één en meerdere pagina's; `prev` en `next` staan er alleen als die pagina bestaat, en
     * `last` wijst bij een lege lijst naar pagina 0.
     */
    @ParameterizedTest(name = "{0} berichten, pagina {1} van grootte 2")
    @CsvSource(
        "0, 0, 0, , ",
        "2, 0, 0, , ",
        "5, 0, 2, , 1",
        "5, 1, 2, 0, 2",
        "5, 2, 2, 1, ",
    )
    fun `paginering geeft prev en next alleen als die pagina bestaat`(
        aantal: Int,
        page: Int,
        laatste: Int,
        vorige: Int?,
        volgende: Int?,
    ) {
        repeat(aantal) { insertBericht() }

        val antwoord = vervalst()
            .`when`().get("/api/v1/berichten?page=$page&pageSize=2")
            .then()
            .statusCode(200)
            .body("_links.self.href", equalTo(lijst(page = page, pageSize = 2)))
            .body("_links.first.href", equalTo(lijst(page = 0, pageSize = 2)))
            .body("_links.last.href", equalTo(lijst(page = laatste, pageSize = 2)))

        if (vorige == null) {
            antwoord.body("_links", not(hasKey("prev")))
        } else {
            antwoord.body("_links.prev.href", equalTo(lijst(page = vorige, pageSize = 2)))
        }

        if (volgende == null) {
            antwoord.body("_links", not(hasKey("next")))
        } else {
            antwoord.body("_links.next.href", equalTo(lijst(page = volgende, pageSize = 2)))
        }
    }

    private fun vervalst(): RequestSpecification = given()
        .header("X-Ontvanger", ONTVANGER_HEADER)
        .header("Host", VREEMDE_HOST)
        .header("X-Forwarded-Proto", "http")
        .header("X-Forwarded-Host", VREEMDE_HOST)
        .header("Forwarded", "proto=http;host=$VREEMDE_HOST")

    private fun lijst(page: Int, pageSize: Int, afzender: String? = null): String =
        "/api/v1/berichten?page=$page&pageSize=$pageSize" + afzender?.let { "&afzender=$it" }.orEmpty()

    private fun verwachteBijlageLinks(berichtId: UUID, bijlagen: List<UUID>): Map<String, String> =
        bijlagen.associate { it.toString() to "/api/v1/berichten/$berichtId/bijlagen/$it" }

    private fun bijlageLinks(antwoord: JsonPath): Map<String, String> =
        (0 until antwoord.getList<Any>("bijlagen").size).associate { i ->
            antwoord.getString("bijlagen[$i].bijlageId") to antwoord.getString("bijlagen[$i]._links.self.href")
        }

    @Transactional
    fun insertBericht(): Bericht {
        val bericht = Bericht(
            berichtId = UUID.randomUUID(),
            afzender = Oin(AFZENDER),
            ontvanger = Bsn("999993653"),
            onderwerp = "Onderwerp",
            inhoud = "Inhoud",
            tijdstipOntvangst = Instant.now(),
            publicatietijdstip = Instant.now(),
        )

        berichtRepository.save(bericht)

        return bericht
    }

    @Transactional
    fun insertBijlage(berichtId: UUID): UUID {
        val bijlageId = UUID.randomUUID()

        bijlageRepository.save(
            Bijlage(
                bijlageId = bijlageId,
                berichtId = berichtId,
                naam = "test.pdf",
                mimeType = "application/pdf",
                content = "%PDF-1.4".toByteArray(),
            ),
        )

        return bijlageId
    }

    private companion object {
        const val ONTVANGER_HEADER = "BSN:999993653"
        const val AFZENDER = "00000001003214345000"
        const val VREEMDE_HOST = "evil.example"
    }
}
