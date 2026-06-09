package nl.rijksoverheid.moz.fbs.berichtenuitvraag.uitvraag

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
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.util.UUID

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
        sessiecache.berichten[id] = nl.rijksoverheid.moz.fbs.berichtensessiecache.berichten.Bericht(
            berichtId = id,
            afzender = "00000001003214345000",
            ontvanger = nl.rijksoverheid.moz.fbs.common.identificatie.Bsn("999990019"),
            onderwerp = "X",
            inhoud = "Inhoud",
            publicatietijdstip = java.time.Instant.parse("2026-05-26T10:00:00Z"),
            magazijnId = "magazijn-a",
            aantalBijlagen = 0,
        )

        given()
            .header("X-Ontvanger", "BSN:999990019")
            .`when`()
            .get("/api/v1/berichten/$id")
            .then()
            .statusCode(200)

        assertEquals("BSN", CaptureFilter.dataSubjectType)
        assertEquals("999990019", CaptureFilter.dataSubjectId)
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
