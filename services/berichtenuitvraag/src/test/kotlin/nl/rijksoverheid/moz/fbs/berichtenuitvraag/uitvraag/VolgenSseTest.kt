package nl.rijksoverheid.moz.fbs.berichtenuitvraag.uitvraag

import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.junit.TestProfile
import io.restassured.RestAssured
import io.restassured.RestAssured.given
import io.smallrye.mutiny.Multi
import io.smallrye.mutiny.Uni
import jakarta.inject.Inject
import nl.rijksoverheid.moz.fbs.berichtensessiecache.SessiecacheException
import nl.rijksoverheid.moz.fbs.berichtensessiecache.berichten.Bericht
import nl.rijksoverheid.moz.fbs.berichtensessiecache.berichten.SessieGebeurtenis
import nl.rijksoverheid.moz.fbs.common.identificatie.Bsn
import org.hamcrest.Matchers.containsString
import org.hamcrest.Matchers.equalTo
import org.hamcrest.Matchers.notNullValue
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.MethodSource
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI
import java.time.Duration
import java.time.Instant
import java.util.UUID

/**
 * Coverage en wire-vorm van [VolgenSseResource]: het endpoint valt buiten codegen en wordt alleen
 * via `@QuarkusTest` geraakt. Het volgen zelf (hartslag, doorgeven tussen pods) is library-werk en
 * daar getest; hier telt wat er op de lijn komt en welke status vóór de stream valt.
 */
@QuarkusTest
@TestProfile(MockSessiecacheProfile::class)
class VolgenSseTest {

    @Inject
    lateinit var sessiecache: MockSessiecache

    @BeforeEach
    fun reset() {
        sessiecache.reset()
    }

    companion object {
        private const val RVO = "00000000000000100000"
        private val BERICHT_ID: UUID = UUID.fromString("0b0e5a52-8a8f-4d5f-9d2e-2f7f7a0c1d11")

        @JvmStatic
        fun wireContract(): List<Arguments> = listOf(
            Arguments.of(SessieGebeurtenis.VolgenGestart, """{"event":"volgen-gestart"}"""),
            Arguments.of(SessieGebeurtenis.Hartslag, """{"event":"hartslag"}"""),
            Arguments.of(SessieGebeurtenis.SessieVerlopen, """{"event":"sessie-verlopen"}"""),
            Arguments.of(
                SessieGebeurtenis.BerichtBijgekomen(bericht()),
                """{"event":"bericht-bijgekomen","bericht":{"berichtId":"$BERICHT_ID","onderwerp":"Nieuwe beschikking",""" +
                    """"afzenderNaam":"RVO","publicatietijdstip":"2026-09-21T10:00:00Z","aantalBijlagen":0,""" +
                    """"magazijnId":"$RVO",""" +
                    """"_links":{"self":{"href":"/api/v1/berichten/$BERICHT_ID"}}}}""",
            ),
        )

        @JvmStatic
        fun foutenVoorDeStream(): List<Arguments> = listOf(
            Arguments.of(SessiecacheException.NogNietGevuld("nog niet"), 409, "nog-niet-opgehaald"),
            Arguments.of(SessiecacheException.OphalenBezig("bezig"), 409, "ophalen-bezig"),
            Arguments.of(SessiecacheException.OphalenMislukt("mislukt"), 503, "ophalen-mislukt"),
            Arguments.of(SessiecacheException.Onbereikbaar("weg"), 503, "tijdelijk-niet-beschikbaar"),
        )

        @JvmStatic
        fun ongeldigeOntvangers(): List<String?> = listOf(null, "", "BSN-999990019", "BSN:abc")

        // De meegeschreven naam wijkt bewust af: de lijn hoort de registernaam te tonen, net als
        // `GET /berichten`.
        private fun bericht() = Bericht(
            berichtId = BERICHT_ID,
            afzender = RVO,
            afzenderNaam = "Oude naam",
            ontvanger = Bsn("999990019"),
            onderwerp = "Nieuwe beschikking",
            publicatietijdstip = Instant.parse("2026-09-21T10:00:00Z"),
            magazijnId = RVO,
            aantalBijlagen = 0,
        )
    }

    @Test
    fun `elk soort gebeurtenis staat in de wire-tabel`() {
        val gedekt = wireContract().map { it.get()[0]!!.javaClass }.toSet()
        val alle = SessieGebeurtenis::class.sealedSubclasses.map { it.java }.toSet()

        assertEquals(alle, gedekt)
    }

    @ParameterizedTest
    @MethodSource("wireContract")
    fun `elke gebeurtenis komt als volledig frame op de lijn`(gebeurtenis: SessieGebeurtenis, verwachtFrame: String) {
        sessiecache.volgGebeurtenissen = Multi.createFrom().item(gebeurtenis)

        assertEquals(listOf(verwachtFrame), frames(volgen()))
    }

    @Test
    fun `een verloop van gestart tot verlopen komt in volgorde door`() {
        sessiecache.volgGebeurtenissen = Multi.createFrom().items(
            SessieGebeurtenis.VolgenGestart,
            SessieGebeurtenis.BerichtBijgekomen(bericht()),
            SessieGebeurtenis.Hartslag,
            SessieGebeurtenis.SessieVerlopen,
        )

        val events = frames(volgen()).map { Regex(""""event":"([a-z-]+)"""").find(it)!!.groupValues[1] }

        assertEquals(listOf("volgen-gestart", "bericht-bijgekomen", "hartslag", "sessie-verlopen"), events)
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("foutenVoorDeStream")
    fun `zonder bruikbare sessie valt de status vóór de stream`(
        fout: SessiecacheException,
        status: Int,
        foutcode: String,
    ) {
        sessiecache.volgFout = fout

        given()
            .header("X-Ontvanger", "BSN:999990019")
            .header("Accept", "text/event-stream")
            .`when`()
            .get("/api/v1/berichten/_volgen")
            .then()
            .statusCode(status)
            .contentType(containsString("application/problem+json"))
            .body("type", equalTo("urn:fbs:fout:$foutcode"))
    }

    @ParameterizedTest(name = "X-Ontvanger ''{0}''")
    @MethodSource("ongeldigeOntvangers")
    fun `een ontbrekende of ongeldige X-Ontvanger is een 400`(ontvanger: String?) {
        val verzoek = given().header("Accept", "text/event-stream")

        ontvanger?.let { verzoek.header("X-Ontvanger", it) }

        verzoek.`when`().get("/api/v1/berichten/_volgen").then().statusCode(400)
    }

    @Test
    fun `een afgebroken stream levert de al verstuurde frames en sluit dan`() {
        // De fout pas ná een pauze: dan is het eerste frame zeker verstuurd, en toetst deze test dat
        // het ook aankomt — niet alleen dat er niet méér komt. Een test die ook bij nul frames
        // slaagt, blijft groen als het endpoint nooit iets levert.
        sessiecache.volgGebeurtenissen = Multi.createBy().concatenating().streams(
            Multi.createFrom().item(SessieGebeurtenis.VolgenGestart),
            Uni.createFrom().nullItem<SessieGebeurtenis>()
                .onItem().delayIt().by(Duration.ofMillis(300))
                .onItem().failWith { _ -> IllegalStateException("abonnement weg") }
                .toMulti(),
        )

        val connection = open()

        try {
            assertEquals(200, connection.responseCode)

            // Regel voor regel, en de afbraak apart vangen: `readText()` gooit bij een verbroken
            // verbinding alles weg wat er al binnen was.
            val regels = buildList {
                try {
                    connection.inputStream.bufferedReader().forEachLine { add(it) }
                } catch (_: IOException) {
                    // De verbroken verbinding is precies wat deze test uitlokt.
                }
            }

            assertEquals(listOf("""{"event":"volgen-gestart"}"""), frames(regels.joinToString("\n")))
        } finally {
            connection.disconnect()
        }
    }

    private fun volgen(): String =
        given()
            .header("X-Ontvanger", "BSN:999990019")
            .header("Accept", "text/event-stream")
            .`when`()
            .get("/api/v1/berichten/_volgen")
            .then()
            .statusCode(200)
            .header("API-Version", notNullValue())
            .extract().body().asString()

    private fun frames(body: String): List<String> =
        body.lines().filter { it.startsWith("data:") }.map { it.removePrefix("data:").trim() }

    private fun open(): HttpURLConnection =
        (URI("http://localhost:${RestAssured.port}/api/v1/berichten/_volgen").toURL().openConnection() as HttpURLConnection).apply {
            setRequestProperty("X-Ontvanger", "BSN:999990019")
            setRequestProperty("Accept", "text/event-stream")
            connectTimeout = 2000
            readTimeout = 2000
        }
}
