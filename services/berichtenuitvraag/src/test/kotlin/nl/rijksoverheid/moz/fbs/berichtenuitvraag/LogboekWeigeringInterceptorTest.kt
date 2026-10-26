package nl.rijksoverheid.moz.fbs.berichtenuitvraag

import io.mockk.every
import io.mockk.mockk
import io.quarkus.test.junit.QuarkusMock
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.junit.TestProfile
import io.restassured.RestAssured.given
import jakarta.inject.Inject
import jakarta.ws.rs.WebApplicationException
import nl.mijnoverheidzakelijk.ldv.logboekdataverwerking.LogboekContext
import nl.mijnoverheidzakelijk.ldv.logboekdataverwerking.ProcessingHandler
import nl.rijksoverheid.moz.fbs.berichtensessiecache.SessiecacheException
import nl.rijksoverheid.moz.fbs.berichtenuitvraag.uitvraag.MockSessiecache
import nl.rijksoverheid.moz.fbs.berichtenuitvraag.uitvraag.MockSessiecacheProfile
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.util.Collections

/**
 * Borgt via de echte interceptorketen wat de LDV-interceptor als aangekondigde uitkomst ziet: een
 * weigering (`UNSET`) of een fout (`ERROR`). Het oordeel wordt gelezen op het moment dat de
 * LDV-interceptor de logregel opbouwt — daarna trekt hij de aankondiging in.
 *
 * De [ProcessingHandler] is per test vervangen: LDV staat in tests uit, en het gaat hier om wat de
 * uitvraag aankondigt, niet om hoe de wrapper het wegschrijft. Dat toetst de wrapper zelf.
 */
@QuarkusTest
@TestProfile(MockSessiecacheProfile::class)
class LogboekWeigeringInterceptorTest {

    @Inject
    lateinit var sessiecache: MockSessiecache

    /** Per afgeronde actie: `null` zonder exceptie, anders of die exceptie aangekondigd was. */
    private val oordelen: MutableList<Boolean?> = Collections.synchronizedList(mutableListOf())

    @BeforeEach
    fun installeer() {
        sessiecache.reset()
        oordelen.clear()

        val processingHandler = mockk<ProcessingHandler>(relaxed = true)

        every { processingHandler.addLogboekContextToSpan(any(), any(), any()) } answers {
            val context = secondArg<LogboekContext>()
            val exceptie = thirdArg<Throwable?>()

            oordelen += exceptie?.let { context.isExpected(it) }

            emptyList()
        }

        QuarkusMock.installMockForType(processingHandler, ProcessingHandler::class.java)
    }

    @Test
    fun `een lijst vóór de eerste ophaalronde is een weigering`() {
        sessiecache.lijstFout = SessiecacheException.NogNietGevuld("Berichten zijn nog niet opgehaald")

        lijst().then().statusCode(409)

        assertEquals(listOf(true), oordelen)
    }

    @Test
    fun `een paginagrootte buiten de grenzen is een weigering, ook al ontstaat die vóór de methode`() {
        // Bean Validation draait in een interceptor binnen de LDV-interceptor en vóór de methode.
        lijst(paginaGrootte = 0).then().statusCode(400)

        assertEquals(listOf(true), oordelen)
    }

    @Test
    fun `een onbereikbare opslag is een fout`() {
        sessiecache.lijstFout = SessiecacheException.Onbereikbaar("Redis weg")

        lijst().then().statusCode(503)

        assertEquals(listOf(false), oordelen)
    }

    @Test
    fun `een geslaagde lijst geeft geen exceptie door`() {
        lijst().then().statusCode(200)

        assertEquals(listOf<Boolean?>(null), oordelen)
    }

    @Test
    fun `een al lopende ophaalronde is ook bij _ophalen een weigering`() {
        sessiecache.ophalenFout = WebApplicationException("Berichten worden al opgehaald.", 409)

        given()
            .header("X-Ontvanger", "BSN:999990019")
            .header("Accept", "text/event-stream")
            .`when`()
            .get("/api/v1/berichten/_ophalen")
            .then()
            .statusCode(409)

        assertEquals(listOf(true), oordelen)
    }

    private fun lijst(paginaGrootte: Int? = null) = given()
        .header("X-Ontvanger", "BSN:999990019")
        .apply { if (paginaGrootte != null) queryParam("paginaGrootte", paginaGrootte) }
        .`when`()
        .get("/api/v1/berichten")
}
