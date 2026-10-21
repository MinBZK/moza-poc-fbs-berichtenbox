package nl.rijksoverheid.moz.fbs.berichtenuitvraag.uitvraag

import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import io.opentelemetry.api.trace.Span
import io.opentelemetry.api.trace.StatusCode
import io.quarkus.test.junit.QuarkusMock
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.junit.TestProfile
import io.restassured.RestAssured.given
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject
import jakarta.ws.rs.container.ContainerRequestContext
import jakarta.ws.rs.container.ContainerResponseContext
import jakarta.ws.rs.container.ContainerResponseFilter
import jakarta.ws.rs.ext.Provider
import nl.mijnoverheidzakelijk.ldv.logboekdataverwerking.LogboekContext
import nl.mijnoverheidzakelijk.ldv.logboekdataverwerking.ProcessingHandler
import nl.rijksoverheid.moz.fbs.berichtenuitvraag.ProcessingActivities
import nl.rijksoverheid.moz.fbs.berichtenuitvraag.sessie.SessieLogboek
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Pint de AVG art. 30-audittrail-invariant end-to-end: een succesvolle request
 * met `X-Ontvanger: BSN:<waarde>` zet via [registreerLdvSubject] het parsed
 * `type`/`waarde`-paar op de request-scoped [LogboekContext] (CLAUDE.md staat de
 * BSN-waarde in `dataSubjectId` toe zolang het endpoint TLS gebruikt).
 *
 * [LogboekContext] is `@RequestScoped` en wordt na de request vernietigd; we
 * observeren daarom de werkelijke instance binnen de request via [CaptureFilter]
 * (een test-scoped response-filter dat dezelfde geïnjecteerde bean leest die de
 * resource net heeft gevuld). Geen reflectie, geen mock — de échte registratie.
 */
@QuarkusTest
@TestProfile(MockSessiecacheProfile::class)
class LdvSubjectRegistrationTest {

    @Inject
    lateinit var sessiecache: MockSessiecache

    @BeforeEach
    fun reset() {
        sessiecache.reset()
        CaptureFilter.reset()
    }

    @Test
    fun `succesvolle request schrijft type en waarde uit X-Ontvanger naar de LDV-context`() {
        val id = UUID.randomUUID()
        sessiecache.zet(nl.rijksoverheid.moz.fbs.berichtensessiecache.berichten.Bericht(
            berichtId = id,
            afzender = "00000001003214345000",
            afzenderNaam = "Magazijn A",
            ontvanger = nl.rijksoverheid.moz.fbs.kern.identificatie.Bsn("999990019"),
            onderwerp = "X",
            publicatietijdstip = java.time.Instant.parse("2026-05-26T10:00:00Z"),
            magazijnId = "magazijn-a",
            aantalBijlagen = 0,
        ))

        // Via de lijst: elk leespad registreert het subject op dezelfde manier, en de
        // lijst komt volledig uit de cache — dan hoeft deze test geen magazijn te stubben.
        given()
            .header("X-Ontvanger", "BSN:999990019")
            .header("X-Sessie", sessiecache.sessieVoor())
            .`when`()
            .get("/api/v1/berichten")
            .then()
            .statusCode(200)

        assertEquals("BSN", CaptureFilter.dataSubjectType)
        assertEquals("999990019", CaptureFilter.dataSubjectId)
    }

    /** Wat de wrapper van één logregel te zien kreeg, op het moment dat hij hem schreef. */
    data class Logregel(val naam: String?, val activiteit: String?, val type: String?, val waarde: String?, val status: StatusCode?)

    private val gestarteSpans = CopyOnWriteArrayList<String>()
    private val logregels = CopyOnWriteArrayList<Logregel>()
    private val opruimSpan = mockk<Span>(relaxed = true)

    /**
     * Vervangt de [ProcessingHandler] van de wrapper, zodat elke logregel zichtbaar wordt: die van
     * de `@Logboek`-interceptor en die van [SessieLogboek], die buiten de interceptor schrijft. De
     * context wordt bij het schrijven gekopieerd, want de interceptor zet hem daarna terug.
     */
    private fun vangLogregels() {
        val handler = mockk<ProcessingHandler>(relaxed = true)

        every { handler.startSpan(any(), any()) } answers {
            val naam = firstArg<String>()
            gestarteSpans += naam

            if (naam == SessieLogboek.NAAM) opruimSpan else Span.getInvalid()
        }

        every { handler.addLogboekContextToSpan(any(), any(), any()) } answers {
            val context = secondArg<LogboekContext>()
            val naam = if (firstArg<Span>() === opruimSpan) SessieLogboek.NAAM else context.actionName
            logregels += Logregel(naam, context.processingActivityId, context.dataSubjectType, context.dataSubjectId, context.status)

            emptyList()
        }

        QuarkusMock.installMockForType(handler, ProcessingHandler::class.java)
    }

    private fun sessieRegel(naam: String) = Logregel(naam, ProcessingActivities.UITVRAAG_SESSIE, "BSN", "999990019", StatusCode.UNSET)

    @Test
    fun `een start zonder resten levert precies de logregel van de start`() {
        vangLogregels()

        given().header("X-Ontvanger", "BSN:999990019").post("/api/v1/sessies").then().statusCode(201)

        assertEquals(listOf("uitvraag-sessie-start"), gestarteSpans.toList())
        assertEquals(listOf(sessieRegel("uitvraag-sessie-start")), logregels.toList())
    }

    @Test
    fun `een start die resten wist, legt ook het opruimen vast`() {
        vangLogregels()
        sessiecache.restenBijVolgendeStart = true

        given().header("X-Ontvanger", "BSN:999990019").post("/api/v1/sessies").then().statusCode(201)

        assertEquals(listOf("uitvraag-sessie-start", SessieLogboek.NAAM), gestarteSpans.toList())
        assertEquals(
            listOf(sessieRegel(SessieLogboek.NAAM).copy(status = StatusCode.OK), sessieRegel("uitvraag-sessie-start")),
            logregels.toList(),
        )
        verify { opruimSpan.setAttribute(SessieLogboek.AANLEIDING_ATTRIBUUT, "nieuwe-start") }
    }

    /** Nul sessies vooraf: een onbekende sessie. Eén: de laatste, dus opruimen. Twee: er blijft er een over. */
    @ParameterizedTest(name = "{0} sessies vooraf, opruimen: {1}")
    @CsvSource("0, false", "1, true", "2, false")
    fun `het beeindigen legt het einde vast, en het opruimen alleen als het de laatste sessie was`(aantal: Int, opgeruimd: Boolean) {
        val sessies = (1..aantal).map { sessiecache.sessieVoor() }
        vangLogregels()

        given()
            .header("X-Ontvanger", "BSN:999990019")
            .header("X-Sessie", sessies.firstOrNull() ?: "A".repeat(22))
            .delete("/api/v1/sessies/huidige")
            .then()
            .statusCode(204)

        val opruimen = listOf(sessieRegel(SessieLogboek.NAAM).copy(status = StatusCode.OK)).takeIf { opgeruimd }.orEmpty()

        assertEquals(listOf("uitvraag-sessie-einde") + listOf(SessieLogboek.NAAM).takeIf { opgeruimd }.orEmpty(), gestarteSpans.toList())
        assertEquals(opruimen + sessieRegel("uitvraag-sessie-einde"), logregels.toList())

        if (opgeruimd) verify { opruimSpan.setAttribute(SessieLogboek.AANLEIDING_ATTRIBUUT, "uitgelogd") }
    }

    /**
     * Leest de request-scoped [LogboekContext] op response-tijd — ná de resource
     * `registreerLdvSubject` heeft uitgevoerd, maar nog binnen request-scope zodat
     * de bean leeft. Stasht de waarden statisch zodat de test ze ná de request kan
     * inspecteren. Alleen test-scope; raakt geen productiecode.
     */
    @Provider
    @ApplicationScoped
    class CaptureFilter : ContainerResponseFilter {

        @Inject
        lateinit var logboekContext: LogboekContext

        override fun filter(req: ContainerRequestContext, resp: ContainerResponseContext) {
            dataSubjectType = logboekContext.dataSubjectType
            dataSubjectId = logboekContext.dataSubjectId
        }

        companion object {
            @Volatile
            var dataSubjectType: String? = null

            @Volatile
            var dataSubjectId: String? = null

            fun reset() {
                dataSubjectType = null
                dataSubjectId = null
            }
        }
    }
}
