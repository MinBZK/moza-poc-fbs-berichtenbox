package nl.rijksoverheid.moz.fbs.berichtenuitvraag.logboek

import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import io.quarkus.test.junit.QuarkusMock
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.junit.TestProfile
import io.restassured.RestAssured.given
import nl.rijksoverheid.moz.fbs.berichtenuitvraag.uitvraag.MockSessiecacheProfile
import nl.rijksoverheid.moz.fbs.common.logboek.LogboekRij
import nl.rijksoverheid.moz.fbs.kern.identificatie.Identificatienummer
import org.hamcrest.Matchers.equalTo
import org.hamcrest.Matchers.hasSize
import org.hamcrest.Matchers.nullValue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.time.Instant

/**
 * De HTTP-kant van de inzage: zonder sessie bereikbaar, de leesregel toegepast, de periode
 * doorgegeven. De leesregel en de SQL zelf staan in fbs-common; tegen een echte PostgreSQL test
 * het magazijn ze.
 */
@QuarkusTest
@TestProfile(MockSessiecacheProfile::class)
class LogboekInzageTest {

    private val lezer = mockk<LogboekLezer>()

    @BeforeEach
    fun installeer() {
        QuarkusMock.installMockForType(lezer, LogboekLezer::class.java)
    }

    @Test
    fun `de inzage vraagt geen sessie en geeft per verwerking één uitkomst`() {
        every { lezer.lees(any(), any(), any()) } returns listOf(
            rij("lijst", "uitvraag-lijst", start = 2),
            rij("ophalen", "uitvraag-ophalen", start = 1),
            rij("fout", "uitvraag-ophalen", start = 1, parent = "ophalen", status = "ERROR", uitkomst = "mislukt"),
        )

        given()
            .header("X-Betrokkene", "BSN:$BSN")
            .get("/api/v1/logboek/verwerkingen")
            .then()
            .statusCode(200)
            .body("betrokkene.type", equalTo("BSN"))
            .body("betrokkene.waarde", equalTo(BSN))
            .body("verwerkingen", hasSize<Any>(2))
            .body("verwerkingen[0].handeling", equalTo("uitvraag-lijst"))
            .body("verwerkingen[0].uitkomst", equalTo("geslaagd"))
            .body("verwerkingen[0].berichtId", nullValue())
            .body("verwerkingen[1].uitkomst", equalTo("mislukt"))
            .body("verwerkingen[1].foutcategorie", equalTo("NetwerkFout"))
    }

    @Test
    fun `de periode gaat door naar de lezer`() {
        every { lezer.lees(any(), any(), any()) } returns emptyList()

        given()
            .header("X-Betrokkene", "BSN:$BSN")
            .queryParam("van", "2026-11-01T00:00:00Z")
            .queryParam("tot", "2026-11-02T00:00:00Z")
            .get("/api/v1/logboek/verwerkingen")
            .then()
            .statusCode(200)
            .body("verwerkingen", hasSize<Any>(0))

        verify {
            lezer.lees(
                Identificatienummer.fromHeader("BSN:$BSN"),
                Instant.parse("2026-11-01T00:00:00Z"),
                Instant.parse("2026-11-02T00:00:00Z"),
            )
        }
    }

    @Test
    fun `een ongeldige betrokkene geeft 400`() {
        given().header("X-Betrokkene", "BSN:123456789").get("/api/v1/logboek/verwerkingen")
            .then().statusCode(400)
    }

    @Suppress("LongParameterList")
    private fun rij(
        span: String,
        naam: String,
        start: Long,
        parent: String? = null,
        status: String = "UNSET",
        uitkomst: String? = null,
    ) = LogboekRij(
        traceId = "t",
        spanId = span,
        parentSpanId = parent,
        status = status,
        naam = naam,
        start = Instant.ofEpochMilli(start),
        activiteit = "urn:nl:moz:fbs:verwerkingsactiviteit:berichtenuitvraag:lezen",
        subjectType = "BSN",
        subjectId = BSN,
        uitkomstAttribuut = uitkomst,
        berichtId = null,
        doel = null,
        poging = null,
        publicatieFout = null,
        foutType = null,
        foutMessage = if (uitkomst != null) "NetwerkFout" else null,
    )

    private companion object {
        const val BSN = "999900109"
    }
}
