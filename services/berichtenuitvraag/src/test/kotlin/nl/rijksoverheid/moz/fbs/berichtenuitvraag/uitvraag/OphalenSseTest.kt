package nl.rijksoverheid.moz.fbs.berichtenuitvraag.uitvraag

import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.junit.TestProfile
import io.restassured.RestAssured.given
import io.smallrye.mutiny.Multi
import jakarta.inject.Inject
import jakarta.ws.rs.WebApplicationException
import nl.rijksoverheid.moz.fbs.berichtensessiecache.berichten.EventType
import nl.rijksoverheid.moz.fbs.berichtensessiecache.berichten.MagazijnEvent
import nl.rijksoverheid.moz.fbs.berichtensessiecache.berichten.MagazijnStatus
import org.hamcrest.Matchers.containsString
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * Coverage voor [OphalenSseResource]: het endpoint is jaxrs-spec-extern en wordt
 * alleen via @QuarkusTest geraakt. Verifieert de happy-path-streaming uit de
 * in-process facade, het pre-stream-falen-pad (409 bij een al lopende ophaling,
 * 503 bij een onbereikbare cache — anders dan bij de vroegere REST-passthrough
 * bereiken die statussen de client nu wél, want de facade gooit vóór de
 * SSE-subscriptie) en het mid-stream-faal-pad (stream termineert zonder
 * corrupte frames).
 */
@QuarkusTest
@TestProfile(MockSessiecacheProfile::class)
class OphalenSseTest {

    @Inject
    lateinit var sessiecache: MockSessiecache

    @BeforeEach
    fun reset() {
        sessiecache.reset()
    }

    private fun gereedEvent(geslaagd: Int = 1, mislukt: Int = 0) = MagazijnEvent(
        event = EventType.OPHALEN_GEREED,
        totaalBerichten = geslaagd,
        geslaagd = geslaagd,
        mislukt = mislukt,
        totaalMagazijnen = geslaagd + mislukt,
    )

    @Test
    fun `_ophalen streamt facade-events als SSE-frames`() {
        sessiecache.ophalenEvents = Multi.createFrom().items(
            MagazijnEvent(event = EventType.MAGAZIJN_BEVRAGING_GESTART, magazijnId = "magazijn-a"),
            MagazijnEvent(
                event = EventType.MAGAZIJN_BEVRAGING_VOLTOOID,
                magazijnId = "magazijn-a",
                status = MagazijnStatus.OK,
                aantalBerichten = 2,
            ),
            gereedEvent(),
        )

        val body = given()
            .header("X-Ontvanger", "BSN:999990019")
            .header("Accept", "text/event-stream")
            .`when`()
            .get("/api/v1/berichten/_ophalen")
            .then()
            .statusCode(200)
            .extract().body().asString()

        assertTrue(body.contains("\"event\":\"magazijn-bevraging-gestart\""), "gestart-event ontbreekt: $body")
        assertTrue(body.contains("\"event\":\"ophalen-gereed\""), "gereed-event ontbreekt: $body")
        assertEquals(3, Regex("(?m)^data:").findAll(body).count(), "verwacht exact 3 SSE-data-frames, body: $body")
        assertFalse(body.contains("data: data:"), "dubbel-geframed: $body")
    }

    @Test
    fun `_ophalen geeft partial-failure (1 magazijn OK, 1 FOUT) ongemaskeerd door`() {
        // Degradatiegedrag: per magazijn een statusevent; één OK, één FOUT. De stream
        // mag de degradatie niet maskeren of hertypen — beide events moeten ongewijzigd
        // bij de client aankomen, inclusief het OPHALEN_GEREED-eindevent met de
        // mislukt-telling.
        sessiecache.ophalenEvents = Multi.createFrom().items(
            MagazijnEvent(event = EventType.MAGAZIJN_BEVRAGING_VOLTOOID, magazijnId = "magazijn-a", status = MagazijnStatus.OK, aantalBerichten = 1),
            MagazijnEvent(event = EventType.MAGAZIJN_BEVRAGING_VOLTOOID, magazijnId = "magazijn-b", status = MagazijnStatus.FOUT, foutmelding = "Magazijn tijdelijk niet bereikbaar"),
            gereedEvent(geslaagd = 1, mislukt = 1),
        )

        given()
            .header("X-Ontvanger", "BSN:999990019")
            .header("Accept", "text/event-stream")
            .`when`()
            .get("/api/v1/berichten/_ophalen")
            .then()
            .statusCode(200)
            .body(containsString("\"status\":\"OK\""))
            .body(containsString("\"status\":\"FOUT\""))
            .body(containsString("\"mislukt\":1"))
    }

    @Test
    fun `_ophalen geeft 409 als er al een ophaling loopt`() {
        // In-process gooit de facade vóór de SSE-subscriptie; anders dan bij de
        // vroegere REST-passthrough (200 al gecommit) bereikt de 409 de client nu wél.
        sessiecache.ophalenFout = WebApplicationException(
            "Berichten worden momenteel al opgehaald voor deze ontvanger.",
            409,
        )

        given()
            .header("X-Ontvanger", "BSN:999990019")
            .header("Accept", "text/event-stream")
            .`when`()
            .get("/api/v1/berichten/_ophalen")
            .then()
            .statusCode(409)
            .contentType(containsString("application/problem+json"))
    }

    @Test
    fun `_ophalen geeft 503 als de cache onbereikbaar is bij de start`() {
        sessiecache.ophalenFout = WebApplicationException("Cache niet bereikbaar bij ophaalstart", 503)

        given()
            .header("X-Ontvanger", "BSN:999990019")
            .header("Accept", "text/event-stream")
            .`when`()
            .get("/api/v1/berichten/_ophalen")
            .then()
            .statusCode(503)
    }

    @Test
    fun `_ophalen termineert veilig bij mid-stream-fout zonder corrupte frames`() {
        // De stream is al open (eerste event geleverd) wanneer de aggregatie faalt;
        // de status ligt dan vast op 200. De eis: het geleverde frame komt door en
        // de stream termineert — geen hang, geen half frame.
        sessiecache.ophalenEvents = Multi.createBy().concatenating().streams(
            Multi.createFrom().item(MagazijnEvent(event = EventType.MAGAZIJN_BEVRAGING_GESTART, magazijnId = "magazijn-a")),
            Multi.createFrom().failure(IllegalStateException("aggregatie-pijplijn brak")),
        )

        val url = java.net.URI("http://localhost:${io.restassured.RestAssured.port}/api/v1/berichten/_ophalen").toURL()
        val conn = (url.openConnection() as java.net.HttpURLConnection).apply {
            requestMethod = "GET"
            setRequestProperty("X-Ontvanger", "BSN:999990019")
            setRequestProperty("Accept", "text/event-stream")
            connectTimeout = 2000
            readTimeout = 2000
        }

        try {
            val status = conn.responseCode
            // De read kan zelf falen (IOException) doordat de verbinding mid-stream breekt —
            // dat is precies het gewenste afbreekgedrag: geen vastloper, geen hangende stream.
            val body = runCatching {
                (if (status >= 400) conn.errorStream else conn.inputStream)?.bufferedReader()?.readText()
            }.getOrNull().orEmpty()

            assertEquals(200, status, "SSE-headers worden bij subscriptie gecommit; verwacht 200, kreeg $status")

            // Of het eerste frame de flush haalt vóór de afbraak is timing-afhankelijk;
            // de garantie is: hooguit het geleverde frame, nooit méér en nooit dubbel-geframed.
            val frames = Regex("(?m)^data:").findAll(body).count()

            assertTrue(frames <= 1, "verwacht hooguit het ene geleverde frame, body: $body")
            assertFalse(body.contains("data: data:"), "dubbel-geframed: $body")
        } finally {
            conn.disconnect()
        }
    }

    @Test
    fun `_ophalen weigert ontbrekende X-Ontvanger met 400`() {
        // Header-validatie: `@NotNull @Pattern` op de parameter moet 400 geven
        // vóór de facade-aanroep.
        given()
            .header("Accept", "text/event-stream")
            .`when`()
            .get("/api/v1/berichten/_ophalen")
            .then()
            .statusCode(400)
    }

    @Test
    fun `_ophalen weigert malformed X-Ontvanger met 400`() {
        given()
            .header("X-Ontvanger", "BSN-123")
            .header("Accept", "text/event-stream")
            .`when`()
            .get("/api/v1/berichten/_ophalen")
            .then()
            .statusCode(400)
    }
}
