package nl.rijksoverheid.moz.fbs.berichtenuitvraag.uitvraag

import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import io.opentelemetry.api.common.AttributeKey
import io.opentelemetry.api.trace.Span
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
import nl.rijksoverheid.moz.fbs.berichtenuitvraag.sessie.SessieLogboek
import nl.rijksoverheid.moz.fbs.berichtenuitvraag.sessie.SessieResource
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
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
    private val opruimSpan = mockk<Span>(relaxed = true)

    /**
     * De span van elke logregel van een verzoek, op naam. Een echte span en geen mock: de resource
     * schrijft via `Span.current()`, en alleen een echte span wordt door de interceptor current.
     */
    private val verzoekSpans = ConcurrentHashMap<String, ReadableSpan>()
    private val tracer = SdkTracerProvider.builder().build().get("ldv-test")

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

            if (naam == SessieLogboek.NAAM) {
                opruimSpan
            } else {
                tracer.spanBuilder(naam).startSpan().also { verzoekSpans[naam] = it as ReadableSpan }
            }
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

        given().contentType("application/json").header("X-Ontvanger", "BSN:999990019").post("/api/v1/sessies").then().statusCode(201)

        assertEquals(listOf("uitvraag-sessie-start"), gestarteSpans.toList())
        assertEquals(listOf(sessieRegel("uitvraag-sessie-start")), logregels.toList())
    }

    @Test
    fun `een start die resten wist, legt ook het opruimen vast`() {
        vangLogregels()
        sessiecache.restenBijVolgendeStart = true

        given().contentType("application/json").header("X-Ontvanger", "BSN:999990019").post("/api/v1/sessies").then().statusCode(201)

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
     * Zonder authenticatie kan iedereen een einde voor een willekeurige ontvanger aanvragen. De
     * logregel van het einde zegt daarom wat het deed: een onbekende sessie wiste niets.
     */
    @ParameterizedTest(name = "{0} sessies vooraf: {1}")
    @CsvSource("0, onbekend", "1, opgeruimd", "2, beeindigd")
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
    fun `een start met een plafond in het verleden krijgt een logregel zonder status Error`() {
        // Een fout van de afnemer, geen storing: in het logboek betekent "Error" dat er iets misging.
        vangLogregels()

        given()
            .contentType("application/json")
            .header("X-Ontvanger", "BSN:999990019")
            .body("""{"uiterlijkGeldigTot":"2020-01-01T00:00:00Z"}""")
            .post("/api/v1/sessies")
            .then()
            .statusCode(400)

        val start = verzoekSpans.getValue("uitvraag-sessie-start").toSpanData()

        assertNotEquals(StatusCode.ERROR, start.status.statusCode, "400 als Error gelogd: ${start.status}")
        assertEquals(listOf(sessieRegel("uitvraag-sessie-start")), logregels.toList())
    }

    /** Wie de logregel van een start leest, ziet of er een sessie begon: ook een weigering heeft een regel. */
    @ParameterizedTest(name = "{0} sessies vooraf, plafond {1}: {2} {3}")
    @CsvSource("0, , 201, gestart", "4, , 201, gestart", "0, 2020-01-01T00:00:00Z, 400, geweigerd")
    fun `de logregel van een start noemt of hij gestart of geweigerd is`(aantal: Int, plafond: String?, status: Int, uitkomst: String) {
        repeat(aantal) { sessiecache.sessieVoor() }
        vangLogregels()

        given()
            .contentType("application/json")
            .header("X-Ontvanger", "BSN:999990019")
            .apply { plafond?.let { body("""{"uiterlijkGeldigTot":"$it"}""") } }
            .post("/api/v1/sessies")
            .then()
            .statusCode(status)

        val start = verzoekSpans.getValue("uitvraag-sessie-start")

        assertEquals(uitkomst, start.getAttribute(AttributeKey.stringKey(SessieResource.START_UITKOMST_ATTRIBUUT)))
    }

    @Test
    fun `een start die op een storing strandt, blijft wel een Error`() {
        sessiecache.startSessieFout = SessiecacheException.Onbereikbaar("Redis weg")
        vangLogregels()

        given().contentType("application/json").header("X-Ontvanger", "BSN:999990019").post("/api/v1/sessies").then().statusCode(503)

        val start = verzoekSpans.getValue("uitvraag-sessie-start")

        assertEquals(StatusCode.ERROR, start.toSpanData().status.statusCode)
        assertEquals(null, start.getAttribute(AttributeKey.stringKey(SessieResource.START_UITKOMST_ATTRIBUUT)), "onbekend of er iets startte")
    }

    @Test
    fun `een start zonder antwoord van de opslag legt het opruimen als onbevestigd vast`() {
        // De start kan in Redis resten gewist hebben; een volgende start ziet de spooksessie en meldt niets.
        sessiecache.startSessieFout = SessiecacheException.Onbereikbaar("Redis antwoordt niet")
        vangLogregels()

        given().contentType("application/json").header("X-Ontvanger", "BSN:999990019").post("/api/v1/sessies").then().statusCode(503)

        assertEquals(listOf(sessieRegel(SessieLogboek.NAAM).copy(status = StatusCode.ERROR)), logregels.filter { it.naam == SessieLogboek.NAAM })
        verify { opruimSpan.setAttribute(SessieLogboek.AANLEIDING_ATTRIBUUT, "nieuwe-start") }
        verify { opruimSpan.setAttribute(SessieLogboek.UITKOMST_ATTRIBUUT, "onbevestigd") }
    }

    @Test
    fun `een start met een andere fout dan onbereikbaar legt geen opruimen vast`() {
        sessiecache.startSessieFout = SessiecacheException.Onleesbaar("kapot")
        vangLogregels()

        given().contentType("application/json").header("X-Ontvanger", "BSN:999990019").post("/api/v1/sessies")

        assertEquals(emptyList<Logregel>(), logregels.filter { it.naam == SessieLogboek.NAAM })
    }

    /** Lezen en verlengen raken alleen de sessie-administratie; net als het verlengen door een berichtenverzoek geen logregel. */
    @Test
    fun `opvragen en verlengen van de sessie leveren geen logregel`() {
        val sessie = sessiecache.sessieVoor()
        vangLogregels()

        given().header("X-Ontvanger", "BSN:999990019").header("X-Sessie", sessie).get("/api/v1/sessies/huidige").then().statusCode(200)
        given().header("X-Ontvanger", "BSN:999990019").header("X-Sessie", sessie).post("/api/v1/sessies/huidige/_verlengen").then().statusCode(200)

        assertEquals(emptyList<String>(), gestarteSpans.toList())
    }

    @Test
    fun `een uitlog zonder antwoord van de opslag legt het opruimen als onbevestigd vast`() {
        val sessie = sessiecache.sessieVoor()
        sessiecache.beeindigFout = SessiecacheException.Onbereikbaar("Redis antwoordt niet")
        vangLogregels()

        given()
            .header("X-Ontvanger", "BSN:999990019")
            .header("X-Sessie", sessie)
            .delete("/api/v1/sessies/huidige")
            .then()
            .statusCode(503)

        assertEquals(listOf(sessieRegel(SessieLogboek.NAAM).copy(status = StatusCode.ERROR)), logregels.filter { it.naam == SessieLogboek.NAAM })
        verify { opruimSpan.setAttribute(SessieLogboek.AANLEIDING_ATTRIBUUT, "uitgelogd") }
        verify { opruimSpan.setAttribute(SessieLogboek.UITKOMST_ATTRIBUUT, "onbevestigd") }
    }

    @Test
    fun `een uitlog met een andere fout dan onbereikbaar legt geen opruimen vast`() {
        val sessie = sessiecache.sessieVoor()
        sessiecache.beeindigFout = SessiecacheException.Onleesbaar("kapot")
        vangLogregels()

        given()
            .header("X-Ontvanger", "BSN:999990019")
            .header("X-Sessie", sessie)
            .delete("/api/v1/sessies/huidige")

        assertEquals(emptyList<Logregel>(), logregels.filter { it.naam == SessieLogboek.NAAM })
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
