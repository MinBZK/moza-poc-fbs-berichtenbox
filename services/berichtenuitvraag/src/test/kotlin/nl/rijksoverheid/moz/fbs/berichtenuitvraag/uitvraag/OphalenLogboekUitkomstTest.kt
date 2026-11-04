package nl.rijksoverheid.moz.fbs.berichtenuitvraag.uitvraag

import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import io.quarkus.test.junit.QuarkusMock
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.junit.TestProfile
import io.restassured.RestAssured.given
import io.smallrye.mutiny.Multi
import jakarta.inject.Inject
import jakarta.ws.rs.WebApplicationException
import nl.mijnoverheidzakelijk.ldv.logboekdataverwerking.DataSubject
import nl.mijnoverheidzakelijk.ldv.logboekdataverwerking.Logregel
import nl.mijnoverheidzakelijk.ldv.logboekdataverwerking.ProcessingHandler
import nl.rijksoverheid.moz.fbs.berichtensessiecache.berichten.MagazijnBevragingGestart
import nl.rijksoverheid.moz.fbs.berichtensessiecache.berichten.MagazijnEvent
import nl.rijksoverheid.moz.fbs.berichtensessiecache.berichten.OphalenGereed
import nl.rijksoverheid.moz.fbs.berichtensessiecache.berichten.OphalenMisluktNaBevraging
import nl.rijksoverheid.moz.fbs.berichtenuitvraag.ProcessingActivities
import nl.rijksoverheid.moz.fbs.common.LdvFoutSamenvatting
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.MethodSource

/**
 * Borgt via het echte endpoint dat een mislukte ophaalronde één ERROR-child onder de logregel van
 * `_ophalen` krijgt ([OphaalUitkomst]), en een geslaagde of geweigerde geen.
 *
 * De [ProcessingHandler] is per test vervangen: LDV staat in tests uit, en deze test gaat over wát
 * de uitvraag de wrapper vraagt vast te leggen, niet over hoe die het wegschrijft.
 */
@QuarkusTest
@TestProfile(MockSessiecacheProfile::class)
class OphalenLogboekUitkomstTest {

    @Inject
    lateinit var sessiecache: MockSessiecache

    private val processingHandler = mockk<ProcessingHandler>(relaxed = true)

    @BeforeEach
    fun installeer() {
        sessiecache.reset()
        every { processingHandler.recordFailedOutcome(any<Logregel>(), any()) } returns emptyList()
        QuarkusMock.installMockForType(processingHandler, ProcessingHandler::class.java)
    }

    companion object {
        private fun gereed(geslaagd: Int = 1, mislukt: Int = 0, nietOpgehaald: Int = 0): MagazijnEvent = OphalenGereed(
            totaalBerichten = geslaagd,
            geslaagd = geslaagd,
            mislukt = mislukt,
            nietOpgehaald = nietOpgehaald,
            totaalMagazijnen = geslaagd + mislukt + nietOpgehaald,
        )

        @JvmStatic
        fun mislukteRondes(): List<Arguments> = listOf(
            Arguments.of("één organisatie mislukt", gereed(geslaagd = 1, mislukt = 1), "1 van 2 organisaties mislukt"),
            Arguments.of("alle organisaties mislukt", gereed(geslaagd = 0, mislukt = 3), "3 van 3 organisaties mislukt"),
            Arguments.of(
                "resultaten niet opgeslagen",
                OphalenMisluktNaBevraging(
                    foutmelding = "Resultaten konden niet worden opgeslagen; haal opnieuw op (ref: test)",
                    geslaagd = 1,
                    mislukt = 0,
                    nietOpgehaald = 0,
                    totaalMagazijnen = 1,
                    referentie = "test",
                ),
                "OphalenMisluktNaBevraging",
            ),
        )

        @JvmStatic
        fun geslaagdeRondes(): List<Arguments> = listOf(
            Arguments.of("alles geslaagd", gereed(geslaagd = 2)),
            Arguments.of("zonder organisaties", gereed(geslaagd = 0)),
            // Overgeslagen organisaties zijn een capaciteitskeuze van de uitvraag, geen mislukking.
            Arguments.of("met niet-opgehaalde organisaties", gereed(geslaagd = 1, nietOpgehaald = 2)),
        )
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("mislukteRondes")
    fun `een mislukte ronde krijgt één uitkomst onder de logregel van _ophalen`(
        @Suppress("UNUSED_PARAMETER") geval: String,
        slotevent: MagazijnEvent,
        samenvatting: String,
    ) {
        sessiecache.ophalenEvents = Multi.createFrom().items(
            MagazijnBevragingGestart(magazijnId = "magazijn-a", naam = "Magazijn A"),
            slotevent,
        )

        haalOp()

        val logregel = slot<Logregel>()
        val mislukking = slot<Throwable>()

        // Met een timeout: het vastleggen loopt buiten de thread van het slotevent.
        verify(timeout = 5_000, exactly = 1) { processingHandler.recordFailedOutcome(capture(logregel), capture(mislukking)) }

        assertEquals("uitvraag-ophalen-sse", logregel.captured.name)
        assertEquals(ProcessingActivities.UITVRAAG_LEZEN, logregel.captured.processingActivityId)
        assertEquals(DataSubject("999990019", "BSN"), logregel.captured.subject)
        assertEquals(samenvatting, mislukking.captured.message)
    }

    @Test
    fun `een stream die faalt, krijgt een uitkomst met alleen het fouttype`() {
        sessiecache.ophalenEvents = Multi.createBy().concatenating().streams(
            Multi.createFrom().item(MagazijnBevragingGestart(magazijnId = "magazijn-a", naam = "Magazijn A")),
            Multi.createFrom().failure(IllegalStateException("aggregatie brak voor BSN 999990019")),
        )

        runCatching { haalOp() }

        val mislukking = slot<Throwable>()

        verify(timeout = 5_000, exactly = 1) { processingHandler.recordFailedOutcome(any<Logregel>(), capture(mislukking)) }

        // Het type en niet de melding: die kan gegevens van de betrokkene bevatten.
        assertEquals(LdvFoutSamenvatting::class.java, mislukking.captured.javaClass)
        assertEquals("java.lang.IllegalStateException", mislukking.captured.message)
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("geslaagdeRondes")
    fun `een geslaagde ronde krijgt geen uitkomst`(
        @Suppress("UNUSED_PARAMETER") geval: String,
        slotevent: MagazijnEvent,
    ) {
        sessiecache.ophalenEvents = Multi.createFrom().item(slotevent)

        haalOp()

        // Zonder timeout: of er iets vast te leggen is, beslist het slotevent vóór het de client
        // bereikt, en de client heeft de hele stream al gelezen.
        verify(exactly = 0) { processingHandler.recordFailedOutcome(any<Logregel>(), any()) }
    }

    @Test
    fun `een geweigerde start krijgt geen uitkomst`() {
        // De weigering is zelf de uitkomst van de logregel; een ERROR-child zou haar tegenspreken.
        sessiecache.ophalenFout = WebApplicationException("Berichten worden momenteel al opgehaald.", 409)

        given()
            .header("X-Ontvanger", "BSN:999990019")
            .header("Accept", "text/event-stream")
            .`when`()
            .get("/api/v1/berichten/_ophalen")
            .then()
            .statusCode(409)

        verify(exactly = 0) { processingHandler.recordFailedOutcome(any<Logregel>(), any()) }
    }

    private fun haalOp() {
        given()
            .header("X-Ontvanger", "BSN:999990019")
            .header("Accept", "text/event-stream")
            .`when`()
            .get("/api/v1/berichten/_ophalen")
            .then()
            .statusCode(200)
    }
}
