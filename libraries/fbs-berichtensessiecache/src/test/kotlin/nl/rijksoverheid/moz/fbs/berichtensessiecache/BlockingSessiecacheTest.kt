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
import nl.rijksoverheid.moz.fbs.berichtensessiecache.berichten.Leesstatus
import nl.rijksoverheid.moz.fbs.berichtensessiecache.berichten.MagazijnEvent
import nl.rijksoverheid.moz.fbs.berichtensessiecache.berichten.OphalenGereed
import nl.rijksoverheid.moz.fbs.berichtensessiecache.berichten.OphalenStatus
import nl.rijksoverheid.moz.fbs.berichtensessiecache.berichten.MagazijnStatus
import nl.rijksoverheid.moz.fbs.berichtensessiecache.berichten.NietGeleverd
import nl.rijksoverheid.moz.fbs.common.identificatie.Bsn
import nl.rijksoverheid.moz.fbs.common.identificatie.Identificatienummer
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.time.Instant
import java.util.UUID
import nl.rijksoverheid.moz.fbs.berichtensessiecache.berichten.MagazijnFoutStatus
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource
import nl.rijksoverheid.moz.fbs.berichtensessiecache.berichten.Volledigheid

/**
 * Pin het facade-contract van [BlockingSessiecache]: de gereed-status-gating op
 * leespaden, de vertaling van infrastructuurfouten naar de gesloten
 * [SessiecacheException]-hiërarchie en de invoervalidatie op de schrijfpaden. Dit
 * gedrag zat eerder in de REST-resource van de sessiecache-deployable; de facade is
 * nu het contractuele seam voor consumers. De facade draagt géén HTTP-transport-type
 * meer — de vertaling naar statuscodes leeft bij de consumer (zie de uitvraag-service).
 */
class BlockingSessiecacheTest {

    private val service = mockk<BerichtensessiecacheService>(relaxed = false)
    private val facade = BlockingSessiecache(service, facadeAwaitTimeoutSeconds = 5)
    private val ontvanger = Bsn("999990019")

    private val gereed = AggregationStatus(status = OphalenStatus.GEREED, totaalMagazijnen = 1, geslaagd = 1)
    private val legePagina = BerichtenPagina(emptyList(), 0, 20, 0L, 0)

    private fun testBericht(ontvanger: Identificatienummer = this.ontvanger) = Bericht(
        berichtId = UUID.randomUUID(),
        afzender = "00000001003214345000",
        afzenderNaam = "Magazijn A",
        ontvanger = ontvanger,
        onderwerp = "Testonderwerp",
        publicatietijdstip = Instant.parse("2026-01-01T10:00:00Z"),
        magazijnId = "magazijn-a",
        aantalBijlagen = 0,
    )

    companion object {
        @JvmStatic
        fun ongeldigeMapnamen(): List<String> = listOf(" ", "   ", "\t", "a".repeat(Bericht.MAX_MAPNAAM_LENGTE + 1))

        @JvmStatic
        fun geldigeMapnamen(): List<String> = listOf(Sessiecache.MAP_WISSEN, "a", "a".repeat(Bericht.MAX_MAPNAAM_LENGTE))
    }

    private fun stubStatus(status: AggregationStatus?) {
        every { service.getAggregationStatus(ontvanger) } returns Uni.createFrom().item(status)
    }

    // --- gereed-status-gating op leespaden ---

    @Test
    fun `lijst zonder aggregatie-status geeft NogNietGevuld`() {
        stubStatus(null)

        assertThrows<SessiecacheException.NogNietGevuld> { facade.lijst(ontvanger) }
    }

    @Test
    fun `lijst met BEZIG-status geeft OphalenBezig`() {
        stubStatus(AggregationStatus(status = OphalenStatus.BEZIG, totaalMagazijnen = 2))

        assertThrows<SessiecacheException.OphalenBezig> { facade.lijst(ontvanger) }
    }

    @Test
    fun `lijst met FOUT-status geeft OphalenMislukt`() {
        stubStatus(AggregationStatus(status = OphalenStatus.FOUT))

        assertThrows<SessiecacheException.OphalenMislukt> { facade.lijst(ontvanger) }
    }

    @Test
    fun `lijst met GEREED-status levert de pagina met defaults en cap`() {
        stubStatus(gereed)
        every { service.getBerichten(0, 20, ontvanger, null, null) } returns Uni.createFrom().item(legePagina)

        assertEquals(legePagina.copy(volledigheid = Volledigheid.VOLLEDIG), facade.lijst(ontvanger))

        // paginaGrootte boven het plafond wordt op 100 gecapt
        every { service.getBerichten(3, 100, ontvanger, "afz", "werk") } returns Uni.createFrom().item(legePagina)

        assertEquals(legePagina.copy(volledigheid = Volledigheid.VOLLEDIG), facade.lijst(ontvanger, pagina = 3, paginaGrootte = 500, afzender = "afz", map = "werk"))
    }

    @Test
    fun `zoek kent dezelfde gating en geeft q door`() {
        stubStatus(null)

        assertThrows<SessiecacheException.NogNietGevuld> { facade.zoek(ontvanger, "factuur") }

        stubStatus(gereed)
        every { service.zoekBerichten("factuur", 0, 20, ontvanger, null, null) } returns Uni.createFrom().item(legePagina)

        assertEquals(legePagina.copy(volledigheid = Volledigheid.VOLLEDIG), facade.zoek(ontvanger, "factuur"))
    }

    /**
     * Wie in de laatste ronde niet leverde, staat in de aggregatiestatus en niet in de berichten.
     * Lijst én zoek moeten het meegeven, anders verdwijnt het signaal zodra de ondernemer zoekt.
     */
    @Test
    fun `lijst en zoek dragen de niet-geleverde organisaties uit de aggregatiestatus`() {
        val nietGeleverd = listOf(NietGeleverd("magazijn-b", "Belasting", MagazijnFoutStatus.TIMEOUT))

        stubStatus(
            AggregationStatus(
                status = OphalenStatus.GEREED,
                totaalMagazijnen = 3,
                geslaagd = 1,
                mislukt = 1,
                nietOpgehaald = 1,
                nietGeleverd = nietGeleverd,
            ),
        )
        every { service.getBerichten(0, 20, ontvanger, null, null) } returns Uni.createFrom().item(legePagina)
        every { service.zoekBerichten("factuur", 0, 20, ontvanger, null, null) } returns Uni.createFrom().item(legePagina)

        // Twee organisaties niet geleverd, één bij naam bekend: het aantal komt uit de tellers.
        assertEquals(Volledigheid(2, nietGeleverd), facade.lijst(ontvanger).volledigheid)
        assertEquals(Volledigheid(2, nietGeleverd), facade.zoek(ontvanger, "factuur").volledigheid)
    }

    @Test
    fun `bericht vereist gereed-status en levert null door bij onbekend id`() {
        stubStatus(gereed)
        val id = UUID.randomUUID()

        every { service.getBerichtById(id, ontvanger) } returns Uni.createFrom().nullItem()
        every { service.isBerichtVerwijderd(id, ontvanger) } returns Uni.createFrom().item(false)

        assertNull(facade.bericht(ontvanger, id))
    }

    @Test
    fun `bericht meldt een misser met tombstone als zelf verwijderd`() {
        stubStatus(gereed)
        val id = UUID.randomUUID()

        every { service.getBerichtById(id, ontvanger) } returns Uni.createFrom().nullItem()
        every { service.isBerichtVerwijderd(id, ontvanger) } returns Uni.createFrom().item(true)

        assertThrows<SessiecacheException.BerichtVerwijderd> { facade.bericht(ontvanger, id) }
    }

    @Test
    fun `het bericht van een andere ontvanger levert dezelfde misser als een onbekend bericht`() {
        // Het privacy-criterium van deze facade: de tombstone van de verwijderaar mag het
        // antwoord aan een ander niet kleuren, anders is het bestaan van andermans bericht
        // aftastbaar via het verschil tussen 404 en 410.
        stubStatus(gereed)
        val id = UUID.randomUUID()

        every { service.getBerichtById(id, ontvanger) } returns Uni.createFrom().nullItem()
        every { service.isBerichtVerwijderd(id, ontvanger) } returns Uni.createFrom().item(false)

        assertNull(facade.bericht(ontvanger, id))
    }

    @Test
    fun `bericht raadpleegt de tombstone niet zolang het bericht er is`() {
        // Een achtergebleven tombstone van een eerder verwijderd-en-opnieuw-aangeleverd
        // bericht mag een bestaand bericht niet als verwijderd bestempelen.
        stubStatus(gereed)
        val bericht = testBericht()

        every { service.getBerichtById(bericht.berichtId, ontvanger) } returns Uni.createFrom().item(bericht)

        // `isBerichtVerwijderd` blijft bewust ongestubd: de mock is strikt, dus een aanroep
        // laat deze test falen. Dat is directer dan een verify achteraf.
        assertSame(bericht, facade.bericht(ontvanger, bericht.berichtId))
    }

    // --- werkBerichtBij ---

    @Test
    fun `werkBerichtBij zonder status en map geeft OngeldigeInvoer`() {
        assertThrows<SessiecacheException.OngeldigeInvoer> {
            facade.werkBerichtBij(ontvanger, UUID.randomUUID(), status = null, map = null)
        }
    }

    /**
     * De facade bewaakt zijn eigen contract: een ongeldige naam hoort als [SessiecacheException.OngeldigeInvoer]
     * terug te komen en de cache niet te raken, niet als 500 diep uit Redis.
     */
    @ParameterizedTest(name = "map=\"{0}\"")
    @MethodSource("ongeldigeMapnamen")
    fun `werkBerichtBij weigert een ongeldige mapnaam zonder de cache te raken`(map: String) {
        assertThrows<SessiecacheException.OngeldigeInvoer> { facade.werkBerichtBij(ontvanger, UUID.randomUUID(), null, map) }

        verify(exactly = 0) { service.updateBerichtMetadata(any(), ontvanger, any(), any()) }
    }

    @ParameterizedTest(name = "map=\"{0}\"")
    @MethodSource("geldigeMapnamen")
    fun `werkBerichtBij laat de wis-waarde en een naam tot de grens door`(map: String) {
        val bijgewerkt = testBericht()

        every { service.updateBerichtMetadata(any(), ontvanger, null, map) } returns Uni.createFrom().item(bijgewerkt)

        assertSame(bijgewerkt, facade.werkBerichtBij(ontvanger, bijgewerkt.berichtId, null, map))
    }

    @Test
    fun `werkBerichtBij geeft wire-status door en retourneert het bijgewerkte bericht`() {
        val id = UUID.randomUUID()
        val bijgewerkt = testBericht().copy(berichtId = id, status = Leesstatus.GELEZEN)

        every { service.updateBerichtMetadata(id, ontvanger, "gelezen", null) } returns Uni.createFrom().item(bijgewerkt)

        assertSame(bijgewerkt, facade.werkBerichtBij(ontvanger, id, Leesstatus.GELEZEN, null))
    }

    @Test
    fun `werkBerichtBij vertaalt schrijf-contentie naar Onbereikbaar`() {
        val id = UUID.randomUUID()

        every { service.updateBerichtMetadata(id, ontvanger, null, "archief") } returns
            Uni.createFrom().failure(CacheContentieException(id))

        assertThrows<SessiecacheException.Onbereikbaar> {
            facade.werkBerichtBij(ontvanger, id, status = null, map = "archief")
        }
    }

    // --- verwijder / ophalen ---

    @Test
    fun `verwijder delegeert en vertaalt Redis-fouten naar Onbereikbaar`() {
        val id = UUID.randomUUID()

        every { service.verwijderBericht(id, ontvanger) } returns Uni.createFrom().voidItem()

        facade.verwijder(ontvanger, id)

        verify { service.verwijderBericht(id, ontvanger) }

        every { service.verwijderBericht(id, ontvanger) } returns
            Uni.createFrom().failure(RuntimeException("redis weg"))

        assertThrows<SessiecacheException.Onbereikbaar> { facade.verwijder(ontvanger, id) }
    }

    @Test
    fun `ophalen geeft de event-stream van de service ongewijzigd door`() {
        val event: MagazijnEvent = OphalenGereed(totaalBerichten = 0, geslaagd = 0, mislukt = 0, nietOpgehaald = 0, totaalMagazijnen = 0)

        every { service.haalBerichtenOp(ontvanger) } returns Multi.createFrom().item(event)

        val events = facade.ophalen(ontvanger).collect().asList().await().indefinitely()

        assertEquals(listOf(event), events)
    }

    // --- schrijfBericht ---

    @Test
    fun `schrijfBericht wijst ontvanger-mismatch af met OngeldigeInvoer`() {
        assertThrows<SessiecacheException.OngeldigeInvoer> {
            facade.schrijfBericht(ontvanger, testBericht(ontvanger = Bsn("999990020")))
        }
    }

    @Test
    fun `schrijfBericht zonder actieve sessie geeft GeenActieveSessie`() {
        stubStatus(null)

        assertThrows<SessiecacheException.GeenActieveSessie> { facade.schrijfBericht(ontvanger, testBericht()) }
    }

    @Test
    fun `schrijfBericht met actieve sessie levert het gevalideerde bericht`() {
        stubStatus(gereed)
        val bericht = testBericht()

        every { service.createBericht(bericht, ontvanger) } returns Uni.createFrom().item(bericht)

        assertSame(bericht, facade.schrijfBericht(ontvanger, bericht))
    }

    @Test
    fun `schrijfBericht vertaalt validator-afwijzing naar OngeldigeInvoer`() {
        stubStatus(gereed)
        val bericht = testBericht()

        every { service.createBericht(bericht, ontvanger) } answers {
            throw IllegalArgumentException("Maximaal 100 bijlagen per bericht")
        }

        assertThrows<SessiecacheException.OngeldigeInvoer> { facade.schrijfBericht(ontvanger, bericht) }
    }

    // --- foutvertaling awaitOrServiceUnavailable ---

    @Test
    fun `cache-deserialisatiefout geeft Onleesbaar`() {
        stubStatus(gereed)
        val id = UUID.randomUUID()

        every { service.getBerichtById(id, ontvanger) } returns
            Uni.createFrom().failure(JsonParseException(null, "corrupt"))

        assertThrows<SessiecacheException.Onleesbaar> { facade.bericht(ontvanger, id) }
    }

    @Test
    fun `corrupte cache-hash geeft Onleesbaar`() {
        stubStatus(gereed)
        val id = UUID.randomUUID()

        every { service.getBerichtById(id, ontvanger) } returns
            Uni.createFrom().failure(CacheCorruptedException.veldOntbreekt("onderwerp"))

        assertThrows<SessiecacheException.Onleesbaar> { facade.bericht(ontvanger, id) }
    }

    @Test
    fun `onverwachte fout geeft Onbereikbaar`() {
        every { service.getAggregationStatus(ontvanger) } returns
            Uni.createFrom().failure(IllegalStateException("redis connection reset"))

        assertThrows<SessiecacheException.Onbereikbaar> { facade.lijst(ontvanger) }
    }

    @Test
    fun `onverwacht transport-type uit de service lekt niet, maar wordt Onbereikbaar`() {
        // De interne service hoort op de sync-paden geen WebApplicationException te gooien;
        // mocht dat tóch gebeuren, dan maskeert de facade dit als infrastructuurfout zodat er
        // geen HTTP-transport-type uit het library-contract lekt.
        every { service.getAggregationStatus(ontvanger) } returns
            Uni.createFrom().failure(WebApplicationException("teapot", 418))

        assertThrows<SessiecacheException.Onbereikbaar> { facade.lijst(ontvanger) }
    }

    // --- config-validatie facade-await-timeout ---

    @Test
    fun `facade-await-timeout van 0 wordt geweigerd bij constructie`() {
        val ex = assertThrows<IllegalArgumentException> {
            BlockingSessiecache(service, facadeAwaitTimeoutSeconds = 0)
        }

        assertTrue(ex.message!!.contains("facade-await-timeout-seconds"), "Was: ${ex.message}")
    }
}
