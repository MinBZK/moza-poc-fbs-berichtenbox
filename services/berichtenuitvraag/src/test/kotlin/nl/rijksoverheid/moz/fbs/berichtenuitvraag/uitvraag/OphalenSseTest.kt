package nl.rijksoverheid.moz.fbs.berichtenuitvraag.uitvraag

import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.junit.TestProfile
import io.restassured.RestAssured.given
import io.smallrye.mutiny.Multi
import jakarta.inject.Inject
import jakarta.ws.rs.WebApplicationException
import nl.rijksoverheid.moz.fbs.berichtensessiecache.berichten.MagazijnBevragingGeslaagd
import nl.rijksoverheid.moz.fbs.berichtensessiecache.berichten.MagazijnBevragingGestart
import nl.rijksoverheid.moz.fbs.berichtensessiecache.berichten.MagazijnBevragingMislukt
import nl.rijksoverheid.moz.fbs.berichtensessiecache.berichten.MagazijnEvent
import nl.rijksoverheid.moz.fbs.berichtensessiecache.berichten.MagazijnFoutStatus
import nl.rijksoverheid.moz.fbs.berichtensessiecache.berichten.OphalenGereed
import nl.rijksoverheid.moz.fbs.berichtensessiecache.berichten.OphalenMisluktNaBevraging
import nl.rijksoverheid.moz.fbs.berichtensessiecache.berichten.OphalenMisluktVoorBevraging
import org.hamcrest.Matchers.containsString
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.MethodSource

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

    private fun gereedEvent(geslaagd: Int = 1, mislukt: Int = 0) = OphalenGereed(
        totaalBerichten = geslaagd,
        geslaagd = geslaagd,
        mislukt = mislukt,
        totaalMagazijnen = geslaagd + mislukt,
    )

    companion object {
        private const val OIN = "00000001001234567890"

        /**
         * Elk soort voortgangsbericht met het volledige `data:`-frame dat de client hoort te
         * zien. Dit is de plek waar de type-dispatch van de SSE-writer wordt vastgelegd:
         * serialiseert het transport op het gedeclareerde `Multi<MagazijnEvent>`-elementtype
         * in plaats van op het runtime-type, dan houdt elk frame alleen zijn discriminator
         * over — een stille regressie die geen enkele fragment-assertie zou vangen.
         */
        @JvmStatic
        fun wireContract(): List<Arguments> = listOf(
            Arguments.of(
                MagazijnBevragingGestart(magazijnId = OIN, naam = "Magazijn A"),
                """{"event":"magazijn-bevraging-gestart","magazijnId":"$OIN","naam":"Magazijn A"}""",
            ),
            Arguments.of(
                MagazijnBevragingGestart(magazijnId = OIN, naam = null),
                """{"event":"magazijn-bevraging-gestart","magazijnId":"$OIN"}""",
            ),
            Arguments.of(
                MagazijnBevragingGeslaagd(magazijnId = OIN, naam = "Magazijn A", aantalBerichten = 3),
                """{"event":"magazijn-bevraging-voltooid","magazijnId":"$OIN","naam":"Magazijn A","status":"OK","aantalBerichten":3}""",
            ),
            Arguments.of(
                MagazijnBevragingMislukt(
                    magazijnId = OIN,
                    naam = "Magazijn A",
                    fout = MagazijnFoutStatus.FOUT,
                    foutmelding = "Magazijn tijdelijk niet bereikbaar",
                ),
                """{"event":"magazijn-bevraging-voltooid","magazijnId":"$OIN","naam":"Magazijn A","status":"FOUT","foutmelding":"Magazijn tijdelijk niet bereikbaar"}""",
            ),
            Arguments.of(
                MagazijnBevragingMislukt(
                    magazijnId = OIN,
                    naam = "Magazijn A",
                    fout = MagazijnFoutStatus.TIMEOUT,
                    foutmelding = "Magazijn reageerde niet binnen de timeout",
                ),
                """{"event":"magazijn-bevraging-voltooid","magazijnId":"$OIN","naam":"Magazijn A","status":"TIMEOUT","foutmelding":"Magazijn reageerde niet binnen de timeout"}""",
            ),
            Arguments.of(
                OphalenGereed(totaalBerichten = 5, geslaagd = 2, mislukt = 0, totaalMagazijnen = 2),
                """{"event":"ophalen-gereed","totaalBerichten":5,"geslaagd":2,"mislukt":0,"totaalMagazijnen":2}""",
            ),
            Arguments.of(
                OphalenMisluktVoorBevraging(foutmelding = "Interne fout (ref: abc)", referentie = "abc"),
                """{"event":"ophalen-fout","foutmelding":"Interne fout (ref: abc)","totaalMagazijnen":0,"referentie":"abc"}""",
            ),
            Arguments.of(
                OphalenMisluktNaBevraging(
                    foutmelding = "Resultaten konden niet worden opgeslagen (ref: abc)",
                    geslaagd = 1,
                    mislukt = 1,
                    totaalMagazijnen = 2,
                    referentie = "abc",
                ),
                """{"event":"ophalen-fout","foutmelding":"Resultaten konden niet worden opgeslagen (ref: abc)","geslaagd":1,"mislukt":1,"totaalMagazijnen":2,"referentie":"abc"}""",
            ),
        )
    }

    @ParameterizedTest
    @MethodSource("wireContract")
    fun `elk soort voortgangsbericht komt volledig op de stroom`(event: MagazijnEvent, verwachtFrame: String) {
        sessiecache.ophalenEvents = Multi.createFrom().item(event)

        val body = given()
            .header("X-Ontvanger", "BSN:999990019")
            .header("Accept", "text/event-stream")
            .`when`()
            .get("/api/v1/berichten/_ophalen")
            .then()
            .statusCode(200)
            .extract().body().asString()

        val frames = body.lines().filter { it.startsWith("data:") }.map { it.removePrefix("data:").trim() }

        assertEquals(listOf(verwachtFrame), frames, "onverwacht SSE-frame, volledige body: $body")
    }

    /**
     * Een magazijnnaam met een regeleinde mag het frame niet splitsen: `data:`-frames zijn
     * regelgebaseerd, dus een rauwe newline zou de client een afgekapt JSON-fragment geven.
     */
    @Test
    fun `regeleinde in de magazijnnaam splitst het frame niet`() {
        sessiecache.ophalenEvents = Multi.createFrom().item(
            MagazijnBevragingGestart(magazijnId = OIN, naam = "Bureau \"A\"\nregel2"),
        )

        val body = given()
            .header("X-Ontvanger", "BSN:999990019")
            .header("Accept", "text/event-stream")
            .`when`()
            .get("/api/v1/berichten/_ophalen")
            .then()
            .statusCode(200)
            .extract().body().asString()

        assertEquals(1, Regex("(?m)^data:").findAll(body).count(), "verwacht precies 1 SSE-data-frame, body: $body")
        assertTrue(body.contains("""Bureau \"A\"\nregel2"""), "naam moet ge-escaped in het frame staan: $body")
    }

    @Test
    fun `_ophalen streamt facade-events als SSE-frames`() {
        sessiecache.ophalenEvents = Multi.createFrom().items(
            MagazijnBevragingGestart(magazijnId = "magazijn-a", naam = null),
            MagazijnBevragingGeslaagd(magazijnId = "magazijn-a", naam = null, aantalBerichten = 2),
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
            MagazijnBevragingGeslaagd(magazijnId = "magazijn-a", naam = null, aantalBerichten = 1),
            MagazijnBevragingMislukt(
                magazijnId = "magazijn-b",
                naam = null,
                fout = MagazijnFoutStatus.FOUT,
                foutmelding = "Magazijn tijdelijk niet bereikbaar",
            ),
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
    fun `_ophalen geeft een OPHALEN_FOUT-event ongemaskeerd door`() {
        // Het fout-eindevent (bv. cache-write-faal ná de magazijn-rondgang) moet de
        // client bereiken inclusief de referentie, zodat de UI "haal opnieuw op" kan
        // tonen; de bijbehorende LDV-ERROR-mapping is gepind in LogboekStatusVoorTest.
        sessiecache.ophalenEvents = Multi.createFrom().items(
            MagazijnBevragingGestart(magazijnId = "magazijn-a", naam = null),
            OphalenMisluktNaBevraging(
                foutmelding = "Resultaten konden niet worden opgeslagen; haal opnieuw op (ref: test)",
                geslaagd = 0,
                mislukt = 1,
                totaalMagazijnen = 1,
                referentie = "test",
            ),
        )

        given()
            .header("X-Ontvanger", "BSN:999990019")
            .header("Accept", "text/event-stream")
            .`when`()
            .get("/api/v1/berichten/_ophalen")
            .then()
            .statusCode(200)
            .body(containsString("\"event\":\"ophalen-fout\""))
            .body(containsString("\"referentie\":\"test\""))
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
            Multi.createFrom().item(MagazijnBevragingGestart(magazijnId = "magazijn-a", naam = null)),
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
