package nl.rijksoverheid.moz.fbs.berichtensessiecache

import com.fasterxml.jackson.core.JsonParseException
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import io.smallrye.mutiny.Multi
import io.smallrye.mutiny.Uni
import jakarta.ws.rs.WebApplicationException
import nl.rijksoverheid.moz.fbs.berichtensessiecache.berichten.AggregationStatus
import nl.rijksoverheid.moz.fbs.berichtensessiecache.berichten.Bericht
import nl.rijksoverheid.moz.fbs.berichtensessiecache.berichten.BerichtenPagina
import nl.rijksoverheid.moz.fbs.berichtensessiecache.berichten.BerichtensessiecacheService
import nl.rijksoverheid.moz.fbs.berichtensessiecache.berichten.CacheContentieException
import nl.rijksoverheid.moz.fbs.berichtensessiecache.berichten.CacheCorruptedException
import nl.rijksoverheid.moz.fbs.berichtensessiecache.berichten.EventType
import nl.rijksoverheid.moz.fbs.berichtensessiecache.berichten.Leesstatus
import nl.rijksoverheid.moz.fbs.berichtensessiecache.berichten.MagazijnEvent
import nl.rijksoverheid.moz.fbs.berichtensessiecache.berichten.OphalenStatus
import nl.rijksoverheid.moz.fbs.common.identificatie.Bsn
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.time.Instant
import java.util.UUID

/**
 * Pin het facade-contract van [BlokkerendeSessiecache]: de gereed-status-gating op
 * leespaden, de vertaling van infrastructuurfouten naar de gedocumenteerde
 * statuscodes en de invoervalidatie op de schrijfpaden. Dit gedrag zat eerder
 * in de REST-resource van de sessiecache-deployable; de facade is nu het
 * contractuele seam voor consumers.
 */
class BlokkerendeSessiecacheTest {

    private val service = mockk<BerichtensessiecacheService>(relaxed = false)
    private val facade = BlokkerendeSessiecache(service)
    private val ontvanger = Bsn("999990019")

    private val gereed = AggregationStatus(status = OphalenStatus.GEREED, totaalMagazijnen = 1, geslaagd = 1)
    private val legePagina = BerichtenPagina(emptyList(), 0, 20, 0L, 0)

    private fun testBericht(ontvangerWaarde: String = ontvanger.waarde) = Bericht(
        berichtId = UUID.randomUUID(),
        afzender = "00000001003214345000",
        ontvanger = ontvangerWaarde,
        onderwerp = "Testonderwerp",
        inhoud = "Testinhoud",
        publicatietijdstip = Instant.parse("2026-01-01T10:00:00Z"),
        magazijnId = "magazijn-a",
        aantalBijlagen = 0,
    )

    private fun stubStatus(status: AggregationStatus?) {
        every { service.getAggregationStatus(ontvanger) } returns Uni.createFrom().item(status)
    }

    // --- gereed-status-gating op leespaden ---

    @Test
    fun `lijst zonder aggregatie-status geeft 409`() {
        stubStatus(null)

        val ex = assertThrows<WebApplicationException> { facade.lijst(ontvanger) }

        assertEquals(409, ex.response.status)
    }

    @Test
    fun `lijst met BEZIG-status geeft 409`() {
        stubStatus(AggregationStatus(status = OphalenStatus.BEZIG, totaalMagazijnen = 2))

        val ex = assertThrows<WebApplicationException> { facade.lijst(ontvanger) }

        assertEquals(409, ex.response.status)
    }

    @Test
    fun `lijst met FOUT-status geeft 500`() {
        stubStatus(AggregationStatus(status = OphalenStatus.FOUT))

        val ex = assertThrows<WebApplicationException> { facade.lijst(ontvanger) }

        assertEquals(500, ex.response.status)
    }

    @Test
    fun `lijst met GEREED-status levert de pagina met defaults en cap`() {
        stubStatus(gereed)
        every { service.getBerichten(0, 20, ontvanger, null, null) } returns Uni.createFrom().item(legePagina)

        assertSame(legePagina, facade.lijst(ontvanger))

        // paginaGrootte boven het plafond wordt op 100 gecapt
        every { service.getBerichten(3, 100, ontvanger, "afz", "werk") } returns Uni.createFrom().item(legePagina)

        assertSame(legePagina, facade.lijst(ontvanger, pagina = 3, paginaGrootte = 500, afzender = "afz", map = "werk"))
    }

    @Test
    fun `zoek kent dezelfde gating en geeft q door`() {
        stubStatus(null)

        assertThrows<WebApplicationException> { facade.zoek(ontvanger, "factuur") }

        stubStatus(gereed)
        every { service.zoekBerichten("factuur", 0, 20, ontvanger, null, null) } returns Uni.createFrom().item(legePagina)

        assertSame(legePagina, facade.zoek(ontvanger, "factuur"))
    }

    @Test
    fun `bericht vereist gereed-status en levert null door bij onbekend id`() {
        stubStatus(gereed)
        val id = UUID.randomUUID()

        every { service.getBerichtById(id, ontvanger) } returns Uni.createFrom().nullItem()

        assertNull(facade.bericht(ontvanger, id))
    }

    // --- werkBerichtBij ---

    @Test
    fun `werkBerichtBij zonder status en map geeft 400`() {
        val ex = assertThrows<WebApplicationException> {
            facade.werkBerichtBij(ontvanger, UUID.randomUUID(), status = null, map = null)
        }

        assertEquals(400, ex.response.status)
    }

    @Test
    fun `werkBerichtBij geeft wire-status door en retourneert het bijgewerkte bericht`() {
        val id = UUID.randomUUID()
        val bijgewerkt = testBericht().copy(berichtId = id, status = Leesstatus.GELEZEN)

        every { service.updateBerichtMetadata(id, ontvanger, "gelezen", null) } returns Uni.createFrom().item(bijgewerkt)

        assertSame(bijgewerkt, facade.werkBerichtBij(ontvanger, id, Leesstatus.GELEZEN, null))
    }

    @Test
    fun `werkBerichtBij vertaalt schrijf-contentie naar 503`() {
        val id = UUID.randomUUID()

        every { service.updateBerichtMetadata(id, ontvanger, null, "archief") } returns
            Uni.createFrom().failure(CacheContentieException(id))

        val ex = assertThrows<WebApplicationException> {
            facade.werkBerichtBij(ontvanger, id, status = null, map = "archief")
        }

        assertEquals(503, ex.response.status)
    }

    // --- verwijder / ophalen ---

    @Test
    fun `verwijder delegeert en vertaalt Redis-fouten naar 503`() {
        val id = UUID.randomUUID()

        every { service.verwijderBericht(id, ontvanger) } returns Uni.createFrom().voidItem()

        facade.verwijder(ontvanger, id)

        verify { service.verwijderBericht(id, ontvanger) }

        every { service.verwijderBericht(id, ontvanger) } returns
            Uni.createFrom().failure(RuntimeException("redis weg"))

        val ex = assertThrows<WebApplicationException> { facade.verwijder(ontvanger, id) }

        assertEquals(503, ex.response.status)
    }

    @Test
    fun `ophalen geeft de event-stream van de service ongewijzigd door`() {
        val event = MagazijnEvent(event = EventType.OPHALEN_GEREED, totaalBerichten = 0, totaalMagazijnen = 0)

        every { service.haalBerichtenOp(ontvanger) } returns Multi.createFrom().item(event)

        val events = facade.ophalen(ontvanger).collect().asList().await().indefinitely()

        assertEquals(listOf(event), events)
    }

    // --- schrijfBericht ---

    @Test
    fun `schrijfBericht wijst ontvanger-mismatch af met 400`() {
        val ex = assertThrows<WebApplicationException> {
            facade.schrijfBericht(ontvanger, testBericht(ontvangerWaarde = "999990020"))
        }

        assertEquals(400, ex.response.status)
    }

    @Test
    fun `schrijfBericht zonder actieve sessie geeft 404`() {
        stubStatus(null)

        val ex = assertThrows<WebApplicationException> { facade.schrijfBericht(ontvanger, testBericht()) }

        assertEquals(404, ex.response.status)
    }

    @Test
    fun `schrijfBericht met actieve sessie levert het gevalideerde bericht`() {
        stubStatus(gereed)
        val bericht = testBericht()

        every { service.createBericht(bericht, ontvanger) } returns Uni.createFrom().item(bericht)

        assertSame(bericht, facade.schrijfBericht(ontvanger, bericht))
    }

    @Test
    fun `schrijfBericht vertaalt validator-afwijzing naar 400`() {
        stubStatus(gereed)
        val bericht = testBericht()

        every { service.createBericht(bericht, ontvanger) } answers {
            throw IllegalArgumentException("Maximaal 100 bijlagen per bericht")
        }

        val ex = assertThrows<WebApplicationException> { facade.schrijfBericht(ontvanger, bericht) }

        assertEquals(400, ex.response.status)
    }

    // --- foutvertaling awaitOrServiceUnavailable ---

    @Test
    fun `cache-deserialisatiefout geeft 500`() {
        stubStatus(gereed)
        val id = UUID.randomUUID()

        every { service.getBerichtById(id, ontvanger) } returns
            Uni.createFrom().failure(JsonParseException(null, "corrupt"))

        val ex = assertThrows<WebApplicationException> { facade.bericht(ontvanger, id) }

        assertEquals(500, ex.response.status)
    }

    @Test
    fun `corrupte cache-hash geeft 500`() {
        stubStatus(gereed)
        val id = UUID.randomUUID()

        every { service.getBerichtById(id, ontvanger) } returns
            Uni.createFrom().failure(CacheCorruptedException.veldOntbreekt("onderwerp"))

        val ex = assertThrows<WebApplicationException> { facade.bericht(ontvanger, id) }

        assertEquals(500, ex.response.status)
    }

    @Test
    fun `onverwachte fout geeft 503`() {
        every { service.getAggregationStatus(ontvanger) } returns
            Uni.createFrom().failure(IllegalStateException("redis connection reset"))

        val ex = assertThrows<WebApplicationException> { facade.lijst(ontvanger) }

        assertEquals(503, ex.response.status)
    }

    @Test
    fun `WebApplicationException uit de service propageert ongewijzigd`() {
        every { service.getAggregationStatus(ontvanger) } returns
            Uni.createFrom().failure(WebApplicationException("teapot", 418))

        val ex = assertThrows<WebApplicationException> { facade.lijst(ontvanger) }

        assertEquals(418, ex.response.status)
    }
}
