package nl.rijksoverheid.moz.fbs.berichtenuitvraag.uitvraag

import io.mockk.every
import io.mockk.mockk
import io.opentelemetry.api.common.AttributeKey
import io.opentelemetry.api.trace.StatusCode
import io.opentelemetry.sdk.trace.ReadableSpan
import io.opentelemetry.sdk.trace.SdkTracerProvider
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
import nl.rijksoverheid.moz.fbs.berichtensessiecache.SessiecacheException
import nl.rijksoverheid.moz.fbs.berichtenuitvraag.ProcessingActivities
import nl.rijksoverheid.moz.fbs.berichtenuitvraag.sessie.SessieResource
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import org.junit.jupiter.params.provider.ValueSource
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
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

    /**
     * De span van elke logregel van een verzoek, op naam. Een echte span en geen mock: de resource
     * schrijft via `Span.current()`, en alleen een echte span wordt door de interceptor current.
     */
    private val verzoekSpans = ConcurrentHashMap<String, ReadableSpan>()
    private val tracer = SdkTracerProvider.builder().build().get("ldv-test")

    /**
     * Vervangt de [ProcessingHandler] van de wrapper, zodat elke logregel van de `@Logboek`-interceptor
     * zichtbaar wordt. De context wordt bij het schrijven gekopieerd, want de interceptor zet hem
     * daarna terug.
     */
    private fun vangLogregels() {
        val handler = mockk<ProcessingHandler>(relaxed = true)

        every { handler.startSpan(any(), any()) } answers {
            val naam = firstArg<String>()
            gestarteSpans += naam

            tracer.spanBuilder(naam).startSpan().also { verzoekSpans[naam] = it as ReadableSpan }
        }

        every { handler.addLogboekContextToSpan(any(), any(), any()) } answers {
            val context = secondArg<LogboekContext>()
            logregels += Logregel(context.actionName, context.processingActivityId, context.dataSubjectType, context.dataSubjectId, context.status)

            emptyList()
        }

        QuarkusMock.installMockForType(handler, ProcessingHandler::class.java)
    }

    private fun sessieRegel(naam: String) = Logregel(naam, ProcessingActivities.UITVRAAG_SESSIE, "BSN", "999990019", StatusCode.UNSET)

    @Test
    fun `een start levert precies de logregel van de start`() {
        vangLogregels()

        given().contentType("application/json").header("X-Ontvanger", "BSN:999990019").post("/api/v1/sessies").then().statusCode(201)

        assertEquals(listOf("uitvraag-sessie-start"), gestarteSpans.toList())
        assertEquals(listOf(sessieRegel("uitvraag-sessie-start")), logregels.toList())
    }

    /** Nul sessies vooraf: een onbekende sessie. Eén: de laatste. Twee: er blijft er een over. */
    @ParameterizedTest(name = "{0} sessies vooraf")
    @ValueSource(ints = [0, 1, 2])
    fun `het beeindigen legt precies de logregel van het einde vast`(aantal: Int) {
        val sessies = (1..aantal).map { sessiecache.sessieVoor() }
        vangLogregels()

        given()
            .header("X-Ontvanger", "BSN:999990019")
            .header("X-Sessie", sessies.firstOrNull() ?: "A".repeat(22))
            .delete("/api/v1/sessies/huidige")
            .then()
            .statusCode(204)

        assertEquals(listOf("uitvraag-sessie-einde"), gestarteSpans.toList())
        assertEquals(listOf(sessieRegel("uitvraag-sessie-einde")), logregels.toList())
    }

    /**
     * Zonder authenticatie kan iedereen een einde voor een willekeurige ontvanger aanvragen. De
     * logregel van het einde zegt daarom wat het deed: een onbekende sessie beëindigde niets.
     */
    @ParameterizedTest(name = "{0} sessies vooraf: {1}")
    @CsvSource("0, onbekend", "1, beeindigd", "2, beeindigd")
    fun `de logregel van een einde noemt wat het einde deed`(aantal: Int, uitkomst: String) {
        val sessies = (1..aantal).map { sessiecache.sessieVoor() }
        vangLogregels()

        given()
            .header("X-Ontvanger", "BSN:999990019")
            .header("X-Sessie", sessies.firstOrNull() ?: "A".repeat(22))
            .delete("/api/v1/sessies/huidige")
            .then()
            .statusCode(204)

        val einde = verzoekSpans.getValue("uitvraag-sessie-einde")

        assertEquals(uitkomst, einde.getAttribute(AttributeKey.stringKey(SessieResource.EINDE_UITKOMST_ATTRIBUUT)))
    }

    @Test
    fun `een start die op een storing strandt, blijft wel een Error`() {
        sessiecache.startSessieFout = SessiecacheException.Onbereikbaar("Redis weg")
        vangLogregels()

        given().contentType("application/json").header("X-Ontvanger", "BSN:999990019").post("/api/v1/sessies").then().statusCode(503)

        val start = verzoekSpans.getValue("uitvraag-sessie-start")

        assertEquals(StatusCode.ERROR, start.toSpanData().status.statusCode)
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
