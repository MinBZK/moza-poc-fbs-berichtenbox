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
 * Pint welke status elke cache-uitkomst aan de client oplevert. Het lees-pad geeft die
 * status rechtstreeks door: de cache classificeert zijn eigen uitkomst al precies, en die
 * door de upstream-politiek halen maakte er een 502 van — waarmee "de vorige ophaalronde is
 * mislukt, haal opnieuw op" niet meer te onderscheiden was van een infrastructuurstoring.
 * De `when` in [naApiFout] dekt alle gevallen zonder `else`, dus een nieuw foutscenario in
 * de cache-library breekt hier de build i.p.v. stil verkeerd bij de gebruiker te landen.
 */
class SessiecacheFoutMappingTest {

    private val log: Logger = Logger.getLogger(SessiecacheFoutMappingTest::class.java)

    @Test
    fun `naApiFout reproduceert de facade-statuscodes per foutscenario`() {
        assertEquals(409, SessiecacheException.NogNietGevuld("x").naApiFout().response.status)
        assertEquals(409, SessiecacheException.OphalenBezig("x").naApiFout().response.status)
        assertEquals(503, SessiecacheException.OphalenMislukt("x").naApiFout().response.status)
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

    /**
     * De opslag is er niet: een storing, maar wel een tijdelijke — 503 nodigt uit tot opnieuw
     * proberen, waar 502 de client op een kapotte keten wijst.
     */
    @Test
    fun `leesUitCache geeft een onbereikbare opslag als 503 door`() {
        val ex = assertThrows<WebApplicationException> {
            leesUitCache<Unit>(log, "test") { throw SessiecacheException.Onbereikbaar("cache weg") }
        }

        assertEquals(503, ex.response.status)
    }

    /**
     * De kern van deze wijziging: een mislukte ophaalronde (bv. doordat de voorkeurenbron een
     * serverfout gaf) hoort als zodanig herkenbaar te zijn, niet als een kale 502 die voor de
     * client niet te onderscheiden is van een infrastructuurstoring.
     */
    @Test
    fun `leesUitCache geeft een mislukte ophaalronde als 503 door`() {
        val ex = assertThrows<WebApplicationException> {
            leesUitCache<Unit>(log, "test") { throw SessiecacheException.OphalenMislukt("ophaling mislukt") }
        }

        assertEquals(503, ex.response.status)
    }

    /** Onleesbare cache-data is wél een defect en blijft 500: opnieuw proberen helpt niet. */
    @Test
    fun `leesUitCache houdt onleesbare cache-data op 500`() {
        val ex = assertThrows<WebApplicationException> {
            leesUitCache<Unit>(log, "test") { throw SessiecacheException.Onleesbaar("cache-data niet leesbaar") }
        }

        assertEquals(500, ex.response.status)
    }

    /**
     * De 502-politiek blijft staan voor wat er wél een is: een fout die niet uit de
     * cache-classificatie komt, zoals een transport-fout of een onverwachte upstream-status.
     */
    @Test
    fun `leesUitCache houdt de 502-politiek voor niet-cache-fouten`() {
        val ex = assertThrows<WebApplicationException> {
            leesUitCache<Unit>(log, "test") { throw jakarta.ws.rs.ProcessingException("verbinding brak") }
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
