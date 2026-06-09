package nl.rijksoverheid.moz.fbs.berichtenuitvraag.uitvraag

import jakarta.ws.rs.WebApplicationException
import nl.rijksoverheid.moz.fbs.berichtensessiecache.SessiecacheException
import org.jboss.logging.Logger
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

/**
 * De enige plek waar de gesloten [SessiecacheException]-hiërarchie naar een API-status
 * wordt vertaald ([naApiFout]) en het lees-pad-gedrag erbovenop ([leesUitCache]).
 *
 * Pint dat [naApiFout] exact de status reproduceert die de facade vroeger zelf gooide
 * (gedragspariteit), en dat het lees-pad daar bovenop dezelfde upstream-politiek
 * toepast als op het magazijn: storing → 502, client-aanwijzing (409) propageert.
 * De `when` in [naApiFout] dekt alle gevallen zonder `else`, dus een nieuw foutscenario in
 * de cache-library breekt hier de build i.p.v. stil verkeerd bij de gebruiker te landen.
 */
class SessiecacheFoutMappingTest {

    private val log: Logger = Logger.getLogger(SessiecacheFoutMappingTest::class.java)

    @Test
    fun `naApiFout reproduceert de facade-statuscodes per foutscenario`() {
        assertEquals(409, SessiecacheException.NogNietGevuld("x").naApiFout().response.status)
        assertEquals(409, SessiecacheException.OphalenBezig("x").naApiFout().response.status)
        assertEquals(500, SessiecacheException.OphalenMislukt("x").naApiFout().response.status)
        assertEquals(503, SessiecacheException.Onbereikbaar("x").naApiFout().response.status)
        assertEquals(500, SessiecacheException.Onleesbaar("x").naApiFout().response.status)
        assertEquals(400, SessiecacheException.OngeldigeInvoer("x").naApiFout().response.status)
        assertEquals(404, SessiecacheException.GeenActieveSessie("x").naApiFout().response.status)
    }

    @Test
    fun `isStoring classificeert storing- versus client-fouten`() {
        assertTrue(SessiecacheException.OphalenMislukt("x").isStoring())
        assertTrue(SessiecacheException.Onbereikbaar("x").isStoring())
        assertTrue(SessiecacheException.Onleesbaar("x").isStoring())

        assertFalse(SessiecacheException.NogNietGevuld("x").isStoring())
        assertFalse(SessiecacheException.OphalenBezig("x").isStoring())
        assertFalse(SessiecacheException.OngeldigeInvoer("x").isStoring())
        assertFalse(SessiecacheException.GeenActieveSessie("x").isStoring())
    }

    @Test
    fun `leesUitCache maakt een cache-storing 502`() {
        val ex = assertThrows<WebApplicationException> {
            leesUitCache<Unit>(log, "test") { throw SessiecacheException.Onbereikbaar("cache weg") }
        }

        assertEquals(502, ex.response.status)
    }

    @Test
    fun `leesUitCache maakt OphalenMislukt 502`() {
        val ex = assertThrows<WebApplicationException> {
            leesUitCache<Unit>(log, "test") { throw SessiecacheException.OphalenMislukt("ophaling mislukt") }
        }

        assertEquals(502, ex.response.status)
    }

    @Test
    fun `leesUitCache maakt Onleesbaar 502`() {
        val ex = assertThrows<WebApplicationException> {
            leesUitCache<Unit>(log, "test") { throw SessiecacheException.Onleesbaar("cache-data niet leesbaar") }
        }

        assertEquals(502, ex.response.status)
    }

    @Test
    fun `leesUitCache laat een client-aanwijzing (409) ongewijzigd door`() {
        val ex = assertThrows<WebApplicationException> {
            leesUitCache<Unit>(log, "test") { throw SessiecacheException.NogNietGevuld("nog niet opgehaald") }
        }

        assertEquals(409, ex.response.status)
    }

    @Test
    fun `leesUitCache geeft een succesvol resultaat ongewijzigd terug`() {
        assertEquals(42, leesUitCache(log, "test") { 42 })
    }
}
