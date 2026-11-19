package nl.rijksoverheid.moz.fbs.berichtenmagazijn.ophaal

import io.quarkus.test.junit.QuarkusTest
import io.restassured.RestAssured.given
import io.restassured.specification.RequestSpecification
import jakarta.inject.Inject
import jakarta.transaction.Transactional
import nl.rijksoverheid.moz.fbs.berichtenmagazijn.opslag.Bericht
import nl.rijksoverheid.moz.fbs.berichtenmagazijn.opslag.BerichtRepository
import nl.rijksoverheid.moz.fbs.berichtenmagazijn.opslag.BerichtStatusRepository
import nl.rijksoverheid.moz.fbs.berichtenmagazijn.opslag.Bijlage
import nl.rijksoverheid.moz.fbs.berichtenmagazijn.opslag.BijlageRepository
import nl.rijksoverheid.moz.fbs.kern.identificatie.Bsn
import nl.rijksoverheid.moz.fbs.kern.identificatie.Oin
import org.hamcrest.Matchers.equalTo
import org.hamcrest.Matchers.everyItem
import org.hamcrest.Matchers.hasKey
import org.hamcrest.Matchers.hasSize
import org.hamcrest.Matchers.matchesRegex
import org.hamcrest.Matchers.not
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import java.time.Instant
import java.util.UUID

/**
 * De HAL-links zijn absolute-path references: geen schema en geen host uit het verzoek. Achter een
 * ingress die TLS beëindigt komt het verzoek als plain http binnen, en de `Host`-header kiest de
 * aanroeper; geen van beide mag in een link terechtkomen.
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

        vervalst()
            .`when`().get("/api/v1/berichten?page=0&pageSize=2&afzender=$AFZENDER")
            .then()
            .statusCode(200)
            .body("_links.self.href", equalTo(lijst(page = 0, pageSize = 2, afzender = AFZENDER)))
            .body("_links.first.href", equalTo(lijst(page = 0, pageSize = 2, afzender = AFZENDER)))
            .body("_links.last.href", equalTo(lijst(page = 1, pageSize = 2, afzender = AFZENDER)))
            .body("_links.next.href", equalTo(lijst(page = 1, pageSize = 2, afzender = AFZENDER)))
            .body("berichten", hasSize<Any>(2))
            .body("berichten._links.self.href", everyItem(matchesRegex(BERICHT_HREF)))
    }

    @Test
    fun `detail en bijlage negeren een vervalste Host en X-Forwarded-Proto http`() {
        val bericht = insertBericht()
        val bijlageId = insertBijlage(bericht.berichtId)

        vervalst()
            .`when`().get("/api/v1/berichten/${bericht.berichtId}")
            .then()
            .statusCode(200)
            .body("_links.self.href", equalTo("/api/v1/berichten/${bericht.berichtId}"))
            .body(
                "bijlagen[0]._links.self.href",
                equalTo("/api/v1/berichten/${bericht.berichtId}/bijlagen/$bijlageId"),
            )
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
        const val BERICHT_HREF = "^/api/v1/berichten/[0-9a-f-]{36}$"
    }
}
