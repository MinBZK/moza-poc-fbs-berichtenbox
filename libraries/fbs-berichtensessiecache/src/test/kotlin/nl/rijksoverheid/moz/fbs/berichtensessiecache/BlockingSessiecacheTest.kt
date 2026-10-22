package nl.rijksoverheid.moz.fbs.berichtensessiecache

import com.fasterxml.jackson.core.JsonParseException
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import io.mockk.verifyOrder
import io.smallrye.mutiny.Multi
import io.smallrye.mutiny.Uni
import jakarta.ws.rs.WebApplicationException
import nl.rijksoverheid.moz.fbs.berichtensessiecache.berichten.AggregationStatus
import nl.rijksoverheid.moz.fbs.berichtensessiecache.berichten.Bericht
import nl.rijksoverheid.moz.fbs.berichtensessiecache.berichten.BerichtSleutel
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
import nl.rijksoverheid.moz.fbs.berichtensessiecache.berichten.Afgekapt
import nl.rijksoverheid.moz.fbs.berichtensessiecache.berichten.SessieGebeurtenis
import nl.rijksoverheid.moz.fbs.berichtensessiecache.berichten.AbonnementGesloten
import nl.rijksoverheid.moz.fbs.berichtensessiecache.berichten.SessieVolger
import nl.rijksoverheid.moz.fbs.berichtensessiecache.berichten.sleutel
import nl.rijksoverheid.moz.fbs.berichtensessiecache.sessie.OpruimUitkomst
import nl.rijksoverheid.moz.fbs.berichtensessiecache.sessie.Opruiming
import nl.rijksoverheid.moz.fbs.berichtensessiecache.sessie.SessieEinde
import nl.rijksoverheid.moz.fbs.berichtensessiecache.sessie.SessieRegister
import nl.rijksoverheid.moz.fbs.berichtensessiecache.sessie.SessieStart
import nl.rijksoverheid.moz.fbs.berichtensessiecache.sessie.StartUitkomst
import nl.rijksoverheid.moz.fbs.berichtensessiecache.berichten.BerichtenCache
import nl.rijksoverheid.moz.fbs.berichtensessiecache.sessie.VeegKandidaat
import nl.rijksoverheid.moz.fbs.kern.identificatie.Bsn
import nl.rijksoverheid.moz.fbs.kern.identificatie.Identificatienummer
import nl.rijksoverheid.moz.fbs.kern.identificatie.Oin
import nl.rijksoverheid.moz.fbs.magazijnregister.Magazijninschrijving
import nl.rijksoverheid.moz.fbs.magazijnregister.Magazijnregister
import java.net.URI
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import org.junit.jupiter.params.provider.ValueSource
import java.time.Instant
import java.util.UUID
import nl.rijksoverheid.moz.fbs.berichtensessiecache.berichten.MagazijnFoutStatus
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
    private val volger = mockk<SessieVolger>(relaxed = true)
    // Een echte implementatie en geen MockK-mock: `any()` op de value class Oin bouwt een lege OIN,
    // en die haalt de validatie niet.
    private val register = object : Magazijnregister {
        private val inschrijvingen = listOf(MAGAZIJN, MAGAZIJN_B)
            .map { Magazijninschrijving(Oin(it), URI("https://magazijn.example"), "Magazijn $it") }

        override fun alle() = inschrijvingen

        override fun voorOin(oin: Oin) = inschrijvingen.firstOrNull { it.oin == oin }
    }
    // Standaard loopt er nog een sessie: de schrijftests gaan over het schrijven, niet over het kruisen.
    // De ontvanger zelf en niet `any()`: MockK bouwt voor `any()` een Bsn zonder waarde.
    private val ontvanger = Bsn("999990019")
    private val sessies = mockk<SessieRegister>().also {
        every { it.ruimOpZonderSessie(ontvanger) } returns Uni.createFrom().item(false)
    }
    private val facade = BlockingSessiecache(service, volger, register, sessies, facadeAwaitTimeoutSeconds = 5)

    private val gereed = AggregationStatus(status = OphalenStatus.GEREED, totaalMagazijnen = 1, geslaagd = 1)
    private val legePagina = BerichtenPagina(emptyList(), 0, 20, 0L, 0)

    private fun testBericht(ontvanger: Identificatienummer = this.ontvanger) = Bericht(
        berichtId = UUID.randomUUID(),
        afzender = "00000001003214345000",
        afzenderNaam = "Magazijn A",
        ontvanger = ontvanger,
        onderwerp = "Testonderwerp",
        publicatietijdstip = Instant.parse("2026-01-01T10:00:00Z"),
        magazijnId = MAGAZIJN,
        aantalBijlagen = 0,
    )

    companion object {
        const val MAGAZIJN = "00000001003214345000"
        const val MAGAZIJN_B = "00000001823288444000"
        const val ONBEKEND_MAGAZIJN = "00000009999999999999"
        const val SESSIE_ID = "AAAAAAAAAAAAAAAAAAAAAA"
        const val TIMEOUT_MELDING = "Cache niet bereikbaar. Probeer het later opnieuw"

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

        every { service.getBerichten(3, 200, ontvanger, "afz", "werk") } returns Uni.createFrom().item(legePagina)

        assertEquals(legePagina.copy(volledigheid = Volledigheid.VOLLEDIG), facade.lijst(ontvanger, pagina = 3, paginaGrootte = 500, afzender = "afz", map = "werk"))
    }

    @ParameterizedTest(name = "paginaGrootte {0} wordt {1}")
    @CsvSource("1, 1", "100, 100", "150, 150", "199, 199", "200, 200", "201, 200", "10000, 200")
    fun `lijst en zoek leveren de gevraagde paginaGrootte tot en met het plafond van 200`(gevraagd: Int, verwacht: Int) {
        stubStatus(gereed)
        every { service.getBerichten(0, verwacht, ontvanger, null, null) } returns Uni.createFrom().item(legePagina)
        every { service.zoekBerichten("factuur", 0, verwacht, ontvanger, null, null) } returns Uni.createFrom().item(legePagina)

        assertEquals(legePagina.copy(volledigheid = Volledigheid.VOLLEDIG), facade.lijst(ontvanger, paginaGrootte = gevraagd))
        assertEquals(legePagina.copy(volledigheid = Volledigheid.VOLLEDIG), facade.zoek(ontvanger, "factuur", paginaGrootte = gevraagd))
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
        val nietGeleverd = listOf(NietGeleverd(MAGAZIJN_B, "Belasting", MagazijnFoutStatus.TIMEOUT))

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
    fun `lijst en zoek dragen de afgekapte organisaties uit de aggregatiestatus`() {
        val afgekapt = listOf(Afgekapt(MAGAZIJN_B, "Belasting", 500, 812L))

        stubStatus(AggregationStatus(status = OphalenStatus.GEREED, totaalMagazijnen = 2, geslaagd = 2, afgekapt = afgekapt))
        every { service.getBerichten(0, 20, ontvanger, null, null) } returns Uni.createFrom().item(legePagina)
        every { service.zoekBerichten("factuur", 0, 20, ontvanger, null, null) } returns Uni.createFrom().item(legePagina)

        assertEquals(Volledigheid(0, emptyList(), afgekapt), facade.lijst(ontvanger).volledigheid)
        assertEquals(Volledigheid(0, emptyList(), afgekapt), facade.zoek(ontvanger, "factuur").volledigheid)
    }

    @Test
    fun `bericht vereist gereed-status en levert null door bij onbekend id`() {
        stubStatus(gereed)
        val id = UUID.randomUUID()

        every { service.getBerichtById(BerichtSleutel(MAGAZIJN, id), ontvanger) } returns Uni.createFrom().nullItem()
        every { service.isBerichtVerwijderd(BerichtSleutel(MAGAZIJN, id), ontvanger) } returns Uni.createFrom().item(false)

        assertNull(facade.bericht(ontvanger, MAGAZIJN, id))
    }

    @Test
    fun `bericht meldt een misser met tombstone als zelf verwijderd`() {
        stubStatus(gereed)
        val id = UUID.randomUUID()

        every { service.getBerichtById(BerichtSleutel(MAGAZIJN, id), ontvanger) } returns Uni.createFrom().nullItem()
        every { service.isBerichtVerwijderd(BerichtSleutel(MAGAZIJN, id), ontvanger) } returns Uni.createFrom().item(true)

        assertThrows<SessiecacheException.BerichtVerwijderd> { facade.bericht(ontvanger, MAGAZIJN, id) }
    }

    @Test
    fun `het bericht van een andere ontvanger levert dezelfde misser als een onbekend bericht`() {
        // Het privacy-criterium van deze facade: de tombstone van de verwijderaar mag het
        // antwoord aan een ander niet kleuren, anders is het bestaan van andermans bericht
        // aftastbaar via het verschil tussen 404 en 410.
        stubStatus(gereed)
        val id = UUID.randomUUID()

        every { service.getBerichtById(BerichtSleutel(MAGAZIJN, id), ontvanger) } returns Uni.createFrom().nullItem()
        every { service.isBerichtVerwijderd(BerichtSleutel(MAGAZIJN, id), ontvanger) } returns Uni.createFrom().item(false)

        assertNull(facade.bericht(ontvanger, MAGAZIJN, id))
    }

    // Alleen een ingeschreven magazijn komt bij de cache: de magazijnId belandt daar in Redis-sleutels
    // en in het aanmeldkanaal, en een spatie of dubbele punt zou die vormen breken.
    @ParameterizedTest(name = "magazijnId=\"{0}\"")
    @ValueSource(strings = [ONBEKEND_MAGAZIJN, "a b", "$MAGAZIJN:x", ""])
    fun `bericht van een magazijn buiten het register is onbekend, zonder de cache te raadplegen`(magazijnId: String) {
        stubStatus(gereed)

        assertNull(facade.bericht(ontvanger, magazijnId, UUID.randomUUID()))

        verify(exactly = 0) { service.getBerichtById(any(), ontvanger) }
        verify(exactly = 0) { service.isBerichtVerwijderd(any(), ontvanger) }
    }

    @ParameterizedTest(name = "magazijnId=\"{0}\"")
    @ValueSource(strings = [ONBEKEND_MAGAZIJN, "a b"])
    fun `werkBerichtBij voor een magazijn buiten het register wijzigt niets`(magazijnId: String) {
        assertNull(facade.werkBerichtBij(ontvanger, magazijnId, UUID.randomUUID(), Leesstatus.GELEZEN, null))

        verify(exactly = 0) { service.updateBerichtMetadata(any(), ontvanger, any(), any()) }
    }

    @ParameterizedTest(name = "magazijnId=\"{0}\"")
    @ValueSource(strings = [ONBEKEND_MAGAZIJN, "a b"])
    fun `verwijder voor een magazijn buiten het register is een no-op`(magazijnId: String) {
        facade.verwijder(ontvanger, magazijnId, UUID.randomUUID())

        verify(exactly = 0) { service.verwijderBericht(any(), ontvanger) }
    }

    @ParameterizedTest(name = "magazijnId=\"{0}\"")
    @ValueSource(strings = [ONBEKEND_MAGAZIJN, "a b"])
    fun `schrijfBericht van een magazijn buiten het register is ongeldige invoer`(magazijnId: String) {
        val bericht = testBericht().copy(magazijnId = magazijnId)

        assertThrows<SessiecacheException.OngeldigeInvoer> { facade.schrijfBericht(ontvanger, bericht) }

        verify(exactly = 0) { service.createBericht(any(), ontvanger) }
    }

    @Test
    fun `bericht levert het bericht van het genoemde magazijn, ook bij een gedeeld berichtnummer`() {
        stubStatus(gereed)
        val vanA = testBericht()
        val vanB = vanA.copy(magazijnId = MAGAZIJN_B, onderwerp = "van B")

        every { service.getBerichtById(vanA.sleutel, ontvanger) } returns Uni.createFrom().item(vanA)
        every { service.getBerichtById(vanB.sleutel, ontvanger) } returns Uni.createFrom().item(vanB)

        assertSame(vanB, facade.bericht(ontvanger, MAGAZIJN_B, vanA.berichtId))
        assertSame(vanA, facade.bericht(ontvanger, MAGAZIJN, vanA.berichtId))
    }

    @Test
    fun `bericht raadpleegt de tombstone niet zolang het bericht er is`() {
        // Een achtergebleven tombstone van een eerder verwijderd-en-opnieuw-aangeleverd
        // bericht mag een bestaand bericht niet als verwijderd bestempelen.
        stubStatus(gereed)
        val bericht = testBericht()

        every { service.getBerichtById(bericht.sleutel, ontvanger) } returns Uni.createFrom().item(bericht)

        // `isBerichtVerwijderd` blijft bewust ongestubd: de mock is strikt, dus een aanroep
        // laat deze test falen. Dat is directer dan een verify achteraf.
        assertSame(bericht, facade.bericht(ontvanger, bericht.magazijnId, bericht.berichtId))
    }

    // --- werkBerichtBij ---

    @Test
    fun `werkBerichtBij zonder status en map geeft OngeldigeInvoer`() {
        assertThrows<SessiecacheException.OngeldigeInvoer> {
            facade.werkBerichtBij(ontvanger, MAGAZIJN, UUID.randomUUID(), status = null, map = null)
        }
    }

    /**
     * De facade bewaakt zijn eigen contract: een ongeldige naam hoort als [SessiecacheException.OngeldigeInvoer]
     * terug te komen en de cache niet te raken, niet als 500 diep uit Redis.
     */
    @ParameterizedTest(name = "map=\"{0}\"")
    @MethodSource("ongeldigeMapnamen")
    fun `werkBerichtBij weigert een ongeldige mapnaam zonder de cache te raken`(map: String) {
        assertThrows<SessiecacheException.OngeldigeInvoer> { facade.werkBerichtBij(ontvanger, MAGAZIJN, UUID.randomUUID(), null, map) }

        verify(exactly = 0) { service.updateBerichtMetadata(any(), ontvanger, any(), any()) }
    }

    @ParameterizedTest(name = "map=\"{0}\"")
    @MethodSource("geldigeMapnamen")
    fun `werkBerichtBij laat de wis-waarde en een naam tot de grens door`(map: String) {
        val bijgewerkt = testBericht()

        every { service.updateBerichtMetadata(any(), ontvanger, null, map) } returns Uni.createFrom().item(bijgewerkt)

        assertSame(bijgewerkt, facade.werkBerichtBij(ontvanger, bijgewerkt.magazijnId, bijgewerkt.berichtId, null, map))
    }

    @Test
    fun `werkBerichtBij geeft wire-status door en retourneert het bijgewerkte bericht`() {
        val id = UUID.randomUUID()
        val bijgewerkt = testBericht().copy(berichtId = id, status = Leesstatus.GELEZEN)

        every { service.updateBerichtMetadata(BerichtSleutel(MAGAZIJN, id), ontvanger, "gelezen", null) } returns Uni.createFrom().item(bijgewerkt)

        assertSame(bijgewerkt, facade.werkBerichtBij(ontvanger, MAGAZIJN, id, Leesstatus.GELEZEN, null))
    }

    @Test
    fun `werkBerichtBij vertaalt schrijf-contentie naar Onbereikbaar`() {
        val id = UUID.randomUUID()

        every { service.updateBerichtMetadata(BerichtSleutel(MAGAZIJN, id), ontvanger, null, "archief") } returns
            Uni.createFrom().failure(CacheContentieException(BerichtSleutel(MAGAZIJN, id)))

        assertThrows<SessiecacheException.Onbereikbaar> {
            facade.werkBerichtBij(ontvanger, MAGAZIJN, id, status = null, map = "archief")
        }
    }

    // --- verwijder / ophalen ---

    @Test
    fun `verwijder delegeert en vertaalt Redis-fouten naar Onbereikbaar`() {
        val id = UUID.randomUUID()

        every { service.verwijderBericht(BerichtSleutel(MAGAZIJN, id), ontvanger) } returns Uni.createFrom().voidItem()

        facade.verwijder(ontvanger, MAGAZIJN, id)

        verify { service.verwijderBericht(BerichtSleutel(MAGAZIJN, id), ontvanger) }

        every { service.verwijderBericht(BerichtSleutel(MAGAZIJN, id), ontvanger) } returns
            Uni.createFrom().failure(RuntimeException("redis weg"))

        assertThrows<SessiecacheException.Onbereikbaar> { facade.verwijder(ontvanger, MAGAZIJN, id) }
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
    fun `schrijfBericht zonder opgehaalde lijst geeft GeenLijstInCache`() {
        stubStatus(null)

        assertThrows<SessiecacheException.GeenLijstInCache> { facade.schrijfBericht(ontvanger, testBericht()) }
    }

    @Test
    fun `schrijfBericht met opgehaalde lijst levert het gevalideerde bericht`() {
        stubStatus(gereed)
        val bericht = testBericht()

        every { service.createBericht(bericht, ontvanger) } returns Uni.createFrom().item(bericht)

        assertSame(bericht, facade.schrijfBericht(ontvanger, bericht))
    }

    @Test
    fun `schrijfBericht meldt het bericht aan bij de gevolgde sessies`() {
        stubStatus(gereed)
        val bericht = testBericht()
        every { service.createBericht(bericht, ontvanger) } returns Uni.createFrom().item(bericht)
        every { volger.meldAan(ontvanger, bericht.sleutel) } returns Uni.createFrom().voidItem()

        facade.schrijfBericht(ontvanger, bericht)

        verify(exactly = 1) { volger.meldAan(ontvanger, bericht.sleutel) }
    }

    @Test
    fun `schrijfBericht slaagt ook als het aanmelden bij gevolgde sessies mislukt`() {
        stubStatus(gereed)
        val bericht = testBericht()
        every { service.createBericht(bericht, ontvanger) } returns Uni.createFrom().item(bericht)
        every { volger.meldAan(ontvanger, bericht.sleutel) } returns
            Uni.createFrom().failure(IllegalStateException("redis weg"))

        assertSame(bericht, facade.schrijfBericht(ontvanger, bericht))
    }

    @Test
    fun `schrijfBericht dat kruist met het einde van de laatste sessie geeft GeenLijstInCache en meldt niets aan`() {
        stubStatus(gereed)
        val bericht = testBericht()
        every { service.createBericht(bericht, ontvanger) } returns Uni.createFrom().item(bericht)
        every { sessies.ruimOpZonderSessie(ontvanger) } returns Uni.createFrom().item(true)

        assertThrows<SessiecacheException.GeenLijstInCache> { facade.schrijfBericht(ontvanger, bericht) }

        verifyOrder {
            service.createBericht(bericht, ontvanger)
            sessies.ruimOpZonderSessie(ontvanger)
        }
        verify(exactly = 0) { volger.meldAan(ontvanger, bericht.sleutel) }
    }

    @ParameterizedTest(name = "markeren faalt={0}")
    @ValueSource(booleans = [false, true])
    fun `schrijfBericht met een mislukte opruimcontrole markeert voor de veeg en is Onbereikbaar`(markerenFaalt: Boolean) {
        // De aanleveraar moet het opnieuw proberen: of het bericht een sessie heeft, is niet bekend.
        // De herlevering vindt dan geen status meer, dus alleen de veegtaak kan het nog opruimen.
        stubStatus(gereed)
        val bericht = testBericht()
        every { service.createBericht(bericht, ontvanger) } returns Uni.createFrom().item(bericht)
        every { sessies.ruimOpZonderSessie(ontvanger) } returns Uni.createFrom().failure(IllegalStateException("redis weg"))
        every { sessies.markeerVoorVeeg(ontvanger) } returns
            if (markerenFaalt) Uni.createFrom().failure(IllegalStateException("nog steeds weg")) else Uni.createFrom().item(Unit)

        val fout = assertThrows<SessiecacheException.Onbereikbaar> { facade.schrijfBericht(ontvanger, bericht) }

        assertEquals("redis weg", fout.cause?.message, "de oorspronkelijke fout hoort door te komen")
        verifyOrder {
            sessies.ruimOpZonderSessie(ontvanger)
            sessies.markeerVoorVeeg(ontvanger)
        }
        verify(exactly = 0) { volger.meldAan(ontvanger, bericht.sleutel) }
    }

    @Test
    fun `een mislukt markeren voor de veeg geeft het alert zonder de ontvanger, ook als de fout hem noemt`() {
        stubStatus(gereed)
        val bericht = testBericht()
        every { service.createBericht(bericht, ontvanger) } returns Uni.createFrom().item(bericht)
        every { sessies.ruimOpZonderSessie(ontvanger) } returns Uni.createFrom().failure(IllegalStateException("redis weg"))
        every { sessies.markeerVoorVeeg(ontvanger) } returns
            Uni.createFrom().failure(IllegalStateException("ERR bij ${ontvanger.waarde} (${BerichtenCache.kenmerk(ontvanger)})"))

        val regels = vangLog(BlockingSessiecache::class.java) {
            assertThrows<SessiecacheException.Onbereikbaar> { facade.schrijfBericht(ontvanger, bericht) }
        }

        assertEquals(1, regels.count { it.contains("[ALERT opruimen_na_schrijven]") }, "Log: $regels")
        regels.forEach { regel ->
            assertFalse(ontvanger.waarde in regel, "BSN in de log: $regel")
            assertFalse(BerichtenCache.kenmerk(ontvanger) in regel, "kenmerk in de log: $regel")
        }
    }

    // --- volg ---

    @Test
    fun `volg kent dezelfde gating als lijst, vóór de stream`() {
        stubLopendeSessie()
        stubStatus(null)
        assertThrows<SessiecacheException.NogNietGevuld> { facade.volg(ontvanger, SESSIE_ID) }

        stubStatus(AggregationStatus(status = OphalenStatus.BEZIG, totaalMagazijnen = 1))
        assertThrows<SessiecacheException.OphalenBezig> { facade.volg(ontvanger, SESSIE_ID) }

        verify(exactly = 0) { volger.volg(ontvanger, SESSIE_ID) }
    }

    @Test
    fun `volg geeft de stream van de volger door bij een lopende sessie en een afgeronde ophaling`() {
        stubLopendeSessie()
        stubStatus(gereed)
        val stream = Multi.createFrom().item<SessieGebeurtenis>(SessieGebeurtenis.VolgenGestart)
        every { volger.actief() } returns Uni.createFrom().voidItem()
        every { volger.volg(ontvanger, SESSIE_ID) } returns stream

        assertSame(stream, facade.volg(ontvanger, SESSIE_ID))
        verify(exactly = 1) { sessies.raak(SESSIE_ID, ontvanger) }
    }

    @Test
    fun `volg zonder lopende sessie gooit SessieOngeldig, vóór de stream en vóór de cache`() {
        // Synchroon, zoals elke andere ongeldige sessie: een stream die meteen SessieVerlopen meldt,
        // zou de afnemer een 200 geven op een verzoek dat niet mocht.
        every { sessies.raak(SESSIE_ID, ontvanger) } returns Uni.createFrom().item(false)

        assertThrows<SessiecacheException.SessieOngeldig> { facade.volg(ontvanger, SESSIE_ID) }
        verify(exactly = 0) { service.getAggregationStatus(ontvanger) }
        verify(exactly = 0) { volger.volg(ontvanger, SESSIE_ID) }
    }

    @Test
    fun `volg bij een storing in het sessieregister is Onbereikbaar en geen ongeldige sessie`() {
        every { sessies.raak(SESSIE_ID, ontvanger) } returns Uni.createFrom().failure(IllegalStateException("redis connection reset"))

        assertThrows<SessiecacheException.Onbereikbaar> { facade.volg(ontvanger, SESSIE_ID) }
        verify(exactly = 0) { volger.volg(ontvanger, SESSIE_ID) }
    }

    @Test
    fun `volg zonder werkend abonnement is Onbereikbaar, vóór de stream`() {
        // Anders valt de mislukking pas op een geopende stream: de afnemer ziet een lege, sluitende
        // 200 in plaats van een storing waarop hij kan wachten.
        stubLopendeSessie()
        stubStatus(gereed)
        every { volger.actief() } returns Uni.createFrom().failure(IllegalStateException("probe kwam niet terug"))

        assertThrows<SessiecacheException.Onbereikbaar> { facade.volg(ontvanger, SESSIE_ID) }
        verify(exactly = 0) { volger.volg(ontvanger, SESSIE_ID) }
    }

    @Test
    fun `volg op een stoppende pod is ook Onbereikbaar, vóór de stream`() {
        // Een gewone uitrol: de afnemer krijgt een 503 en verbindt bij een andere pod. De facade
        // logt dit niet als error per stream; dat de pod stopt, meldt hij zelf één keer.
        stubLopendeSessie()
        stubStatus(gereed)
        every { volger.actief() } returns Uni.createFrom().failure(AbonnementGesloten("Deze pod stopt"))

        val fout = assertThrows<SessiecacheException.Onbereikbaar> { facade.volg(ontvanger, SESSIE_ID) }

        assertTrue(fout.cause is AbonnementGesloten)
        // Eigen melding, en daarmee aantoonbaar een eigen tak: het vangnet voor onbekende fouten
        // geeft ook Onbereikbaar, maar logt er een error met stack bij.
        assertEquals("Nieuwe berichten volgen kan nu niet. Probeer het straks opnieuw.", fout.message)
        verify(exactly = 0) { volger.volg(ontvanger, SESSIE_ID) }
    }

    private fun stubLopendeSessie() {
        every { sessies.raak(SESSIE_ID, ontvanger) } returns Uni.createFrom().item(true)
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

        every { service.getBerichtById(BerichtSleutel(MAGAZIJN, id), ontvanger) } returns
            Uni.createFrom().failure(JsonParseException(null, "corrupt"))

        assertThrows<SessiecacheException.Onleesbaar> { facade.bericht(ontvanger, MAGAZIJN, id) }
    }

    @Test
    fun `corrupte cache-hash geeft Onleesbaar`() {
        stubStatus(gereed)
        val id = UUID.randomUUID()

        every { service.getBerichtById(BerichtSleutel(MAGAZIJN, id), ontvanger) } returns
            Uni.createFrom().failure(CacheCorruptedException.veldOntbreekt("onderwerp"))

        assertThrows<SessiecacheException.Onleesbaar> { facade.bericht(ontvanger, MAGAZIJN, id) }
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

    // --- sessies ---

    @Test
    fun `startSessie geeft de gestarte sessie door`() {
        val start = SessieStart(SESSIE_ID, Instant.parse("2026-10-21T12:00:00Z"), restenOpgeruimd = true)
        every { sessies.start(ontvanger) } returns Uni.createFrom().item(StartUitkomst.Gestart(start))

        assertSame(start, facade.startSessie(ontvanger))
    }

    @Test
    fun `startSessie op de grens gooit TeVeelSessies`() {
        every { sessies.start(ontvanger) } returns Uni.createFrom().item(StartUitkomst.LimietBereikt)

        assertThrows<SessiecacheException.TeVeelSessies> { facade.startSessie(ontvanger) }
    }

    @Test
    fun `raakSessie zonder lopende sessie gooit SessieOngeldig`() {
        every { sessies.raak(SESSIE_ID, ontvanger) } returns Uni.createFrom().item(false)

        assertThrows<SessiecacheException.SessieOngeldig> { facade.raakSessie(SESSIE_ID, ontvanger) }
    }

    @Test
    fun `beeindigSessie geeft de uitkomst door`() {
        every { sessies.beeindig(SESSIE_ID, ontvanger) } returns Uni.createFrom().item(SessieEinde.BEEINDIGD)

        assertEquals(SessieEinde.BEEINDIGD, facade.beeindigSessie(SESSIE_ID, ontvanger))
    }

    @Test
    fun `een Redis-storing in het sessieregister is Onbereikbaar en geen ongeldige sessie`() {
        // Een storing als ongeldige sessie melden zou elke ingelogde gebruiker uitloggen.
        val storing = IllegalStateException("redis connection reset")
        every { sessies.start(ontvanger) } returns Uni.createFrom().failure(storing)
        every { sessies.raak(SESSIE_ID, ontvanger) } returns Uni.createFrom().failure(storing)
        every { sessies.beeindig(SESSIE_ID, ontvanger) } returns Uni.createFrom().failure(storing)

        assertThrows<SessiecacheException.Onbereikbaar> { facade.startSessie(ontvanger) }
        assertThrows<SessiecacheException.Onbereikbaar> { facade.raakSessie(SESSIE_ID, ontvanger) }
        assertThrows<SessiecacheException.Onbereikbaar> { facade.beeindigSessie(SESSIE_ID, ontvanger) }
    }

    // --- verlopen sessies vegen ---

    /** Een veegronde met de meldingen in volgorde, en of de kandidatenbatch vol was. */
    private fun BlockingSessiecache.veeg(max: Int = 100): Pair<List<Opruiming>, Boolean> {
        val gemeld = mutableListOf<Opruiming>()
        val vol = ruimVerlopenSessiesOp(max) { gemeld += it }

        return gemeld to vol
    }

    /** Een kandidaat met een leesbare ontvanger, zoals de index hem na een start kent. */
    private fun kandidaat(n: Int): VeegKandidaat = Oin("%020d".format(n)).let { VeegKandidaat(BerichtenCache.kenmerk(it), it) }

    private fun kenmerkVan(opruiming: Opruiming): String = when (opruiming) {
        is Opruiming.VanOntvanger -> BerichtenCache.kenmerk(opruiming.ontvanger)
        is Opruiming.ZonderOntvanger -> opruiming.kenmerk
    }

    @Test
    fun `vegen meldt per ontvanger de uitkomst en loopt na een fout of timeout door`() {
        // De gewiste ontvanger staat achteraan: een ronde die op een fout afbreekt, verliest hem.
        val kandidaten = List(7) { kandidaat(it + 1) }
        val snel = BlockingSessiecache(service, volger, register, sessies, facadeAwaitTimeoutSeconds = 1)
        every { sessies.verlopen(100) } returns Uni.createFrom().item(kandidaten)
        every { sessies.veeg(kandidaten[0].kenmerk) } returns Uni.createFrom().nothing()
        every { sessies.veeg(kandidaten[1].kenmerk) } returns Uni.createFrom().failure(IllegalStateException("ERR in script"))
        every { sessies.veeg(kandidaten[2].kenmerk) } returns Uni.createFrom().failure(NumberFormatException("geen getal"))
        every { sessies.veeg(kandidaten[3].kenmerk) } throws IllegalArgumentException("synchroon")
        every { sessies.veeg(kandidaten[4].kenmerk) } returns Uni.createFrom().item(false)
        every { sessies.veeg(kandidaten[5].kenmerk) } returns
            Uni.createFrom().failure(IllegalStateException("client", java.util.concurrent.TimeoutException()))
        every { sessies.veeg(kandidaten[6].kenmerk) } returns Uni.createFrom().item(true)
        // Per kandidaat en niet met `any()`: dat bouwt een lege OIN, en die haalt de validatie niet.
        kandidaten.forEach { every { sessies.stelUit(it.kenmerk) } returns Uni.createFrom().item(Unit) }

        val (opruimingen, vol) = snel.veeg()

        assertEquals(
            listOf(
                Opruiming.VanOntvanger(kandidaten[0].ontvanger!!, OpruimUitkomst.ONBEVESTIGD),
                Opruiming.VanOntvanger(kandidaten[1].ontvanger!!, OpruimUitkomst.MISLUKT),
                Opruiming.VanOntvanger(kandidaten[2].ontvanger!!, OpruimUitkomst.MISLUKT),
                Opruiming.VanOntvanger(kandidaten[3].ontvanger!!, OpruimUitkomst.MISLUKT),
                Opruiming.VanOntvanger(kandidaten[5].ontvanger!!, OpruimUitkomst.ONBEVESTIGD),
                Opruiming.VanOntvanger(kandidaten[6].ontvanger!!, OpruimUitkomst.GEWIST),
            ),
            opruimingen,
        )
        assertFalse(vol)

        // Ook een onbevestigde schuift naar achteren: hangt zijn script structureel, dan kost hij
        // anders elke ronde de volle wachttijd. Alleen wie gewist is of niet geveegd hoefde, niet.
        kandidaten.forEachIndexed { i, kandidaat ->
            verify(exactly = if (i in setOf(0, 1, 2, 3, 5)) 1 else 0) { sessies.stelUit(kandidaat.kenmerk) }
        }
    }

    @Test
    fun `elke opruiming wordt gemeld voordat de volgende ontvanger geveegd wordt`() {
        val kandidaten = List(3) { kandidaat(it + 1) }
        val volgorde = mutableListOf<String>()
        every { sessies.verlopen(100) } returns Uni.createFrom().item(kandidaten)
        kandidaten.forEach { kandidaat ->
            every { sessies.veeg(kandidaat.kenmerk) } answers {
                volgorde += "veeg ${kandidaat.kenmerk}"
                Uni.createFrom().item(true)
            }
        }

        facade.ruimVerlopenSessiesOp { volgorde += "meld ${kenmerkVan(it)}" }

        assertEquals(kandidaten.flatMap { listOf("veeg ${it.kenmerk}", "meld ${it.kenmerk}") }, volgorde)
    }

    @Test
    fun `een fout uit de melding breekt de ronde af en komt door`() {
        val kandidaten = List(2) { kandidaat(it + 1) }
        every { sessies.verlopen(100) } returns Uni.createFrom().item(kandidaten)
        every { sessies.veeg(kandidaten[0].kenmerk) } returns Uni.createFrom().item(true)

        assertThrows<IllegalStateException> { facade.ruimVerlopenSessiesOp { throw IllegalStateException("logboek stuk") } }

        verify(exactly = 0) { sessies.veeg(kandidaten[1].kenmerk) }
    }

    @ParameterizedTest(name = "{0} kandidaten bij max 3")
    @ValueSource(ints = [0, 1, 2, 3])
    fun `vegen meldt een volle batch alleen als er max kandidaten waren, ongeacht het aantal opruimingen`(aantal: Int) {
        // Een kandidaat die een andere pod al veegde, wordt niet gemeld maar telt wel voor de batch.
        val kandidaten = List(aantal) { kandidaat(it + 1) }
        every { sessies.verlopen(3) } returns Uni.createFrom().item(kandidaten)
        kandidaten.forEach { every { sessies.veeg(it.kenmerk) } returns Uni.createFrom().item(false) }

        val (opruimingen, vol) = facade.veeg(max = 3)

        assertEquals(emptyList<Opruiming>(), opruimingen)
        assertEquals(aantal == 3, vol)
    }

    @Test
    fun `een interrupt tijdens het vegen meldt de ontvanger als onbevestigd en stopt de ronde`() {
        // De pod stopt: geen MISLUKT met uitstel, maar ook geen stilte, want het script kan gelopen hebben.
        val kandidaten = List(3) { kandidaat(it + 1) }
        val gemeld = mutableListOf<Opruiming>()
        val gemeldMetVlag = mutableListOf<Boolean>()
        every { sessies.verlopen(100) } returns Uni.createFrom().item(kandidaten)
        every { sessies.veeg(kandidaten[0].kenmerk) } returns Uni.createFrom().item(true)
        every { sessies.veeg(kandidaten[1].kenmerk) } answers {
            Thread.currentThread().interrupt()
            Uni.createFrom().nothing()
        }

        val vol = try {
            facade.ruimVerlopenSessiesOp { opruiming ->
                gemeld += opruiming
                gemeldMetVlag += Thread.currentThread().isInterrupted
            }.also { assertTrue(Thread.currentThread().isInterrupted, "de interrupt-vlag hoort hersteld te zijn") }
        } finally {
            Thread.interrupted()
        }

        assertFalse(vol)
        assertEquals(
            listOf(
                Opruiming.VanOntvanger(kandidaten[0].ontvanger!!, OpruimUitkomst.GEWIST),
                Opruiming.VanOntvanger(kandidaten[1].ontvanger!!, OpruimUitkomst.ONBEVESTIGD),
            ),
            gemeld,
        )
        assertEquals(listOf(false, false), gemeldMetVlag, "de melding hoort zonder vlag te lopen")
        verify(exactly = 0) { sessies.veeg(kandidaten[2].kenmerk) }
        kandidaten.forEach { verify(exactly = 0) { sessies.stelUit(it.kenmerk) } }
    }

    @ParameterizedTest(name = "{0}")
    @CsvSource("GEWIST,true", "MISLUKT,false")
    fun `een kandidaat zonder leesbare ontvanger wordt geveegd en zonder ontvanger gemeld`(uitkomst: OpruimUitkomst, geveegd: Boolean) {
        val kandidaat = VeegKandidaat("kenmerk-zonder-ontvanger", null)
        every { sessies.verlopen(100) } returns Uni.createFrom().item(listOf(kandidaat))
        every { sessies.veeg(kandidaat.kenmerk) } returns
            if (geveegd) Uni.createFrom().item(true) else Uni.createFrom().failure(IllegalStateException("ERR in script"))
        every { sessies.stelUit(kandidaat.kenmerk) } returns Uni.createFrom().item(Unit)

        assertEquals(listOf(Opruiming.ZonderOntvanger(kandidaat.kenmerk, uitkomst)), facade.veeg().first)
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = ["fout", "timeout", "uitstel"])
    fun `de veeg-, index- en uitstelpaden loggen de ontvanger niet, ook niet als een foutmelding hem noemt`(geval: String) {
        val bsn = Bsn("999993653")
        val kandidaat = VeegKandidaat(BerichtenCache.kenmerk(bsn), bsn)
        val snel = BlockingSessiecache(service, volger, register, sessies, facadeAwaitTimeoutSeconds = 1)
        val fout = IllegalStateException("ERR bij ${bsn.waarde} (${kandidaat.kenmerk})", java.util.concurrent.TimeoutException(bsn.waarde))
        every { sessies.verlopen(100) } returns Uni.createFrom().item(listOf(kandidaat))
        every { sessies.veeg(kandidaat.kenmerk) } returns when (geval) {
            "timeout" -> Uni.createFrom().failure(fout)
            else -> Uni.createFrom().failure(IllegalStateException("ERR bij ${bsn.waarde}"))
        }
        every { sessies.stelUit(kandidaat.kenmerk) } returns
            if (geval == "uitstel") Uni.createFrom().failure(fout) else Uni.createFrom().item(Unit)

        val regels = vangLog(BlockingSessiecache::class.java) { snel.veeg() }

        assertTrue(regels.isNotEmpty(), "het pad hoort te loggen")
        regels.forEach { regel ->
            assertFalse(bsn.waarde in regel, "BSN in de log: $regel")
            assertFalse(kandidaat.kenmerk in regel, "kenmerk in de log: $regel")
        }
    }

    @Test
    fun `een mislukt uitstel na een mislukt opruimen laat de uitkomst MISLUKT en breekt de ronde niet af`() {
        val kandidaten = List(3) { kandidaat(it + 1) }
        every { sessies.verlopen(100) } returns Uni.createFrom().item(kandidaten)
        every { sessies.veeg(kandidaten[0].kenmerk) } returns Uni.createFrom().failure(IllegalStateException("ERR in script"))
        every { sessies.veeg(kandidaten[1].kenmerk) } returns Uni.createFrom().failure(IllegalStateException("ERR in script"))
        every { sessies.veeg(kandidaten[2].kenmerk) } returns Uni.createFrom().item(true)
        every { sessies.stelUit(kandidaten[0].kenmerk) } returns Uni.createFrom().failure(IllegalStateException("redis weg"))
        every { sessies.stelUit(kandidaten[1].kenmerk) } throws IllegalStateException("synchroon")

        assertEquals(
            listOf(
                Opruiming.VanOntvanger(kandidaten[0].ontvanger!!, OpruimUitkomst.MISLUKT),
                Opruiming.VanOntvanger(kandidaten[1].ontvanger!!, OpruimUitkomst.MISLUKT),
                Opruiming.VanOntvanger(kandidaten[2].ontvanger!!, OpruimUitkomst.GEWIST),
            ),
            facade.veeg().first,
        )
    }

    @Test
    fun `vegen zonder kandidaten meldt niets en geen volle batch`() {
        every { sessies.verlopen(3) } returns Uni.createFrom().item(emptyList())

        assertEquals(emptyList<Opruiming>() to false, facade.veeg(max = 3))
    }

    @Test
    fun `vegen waarbij de kandidaten niet op te vragen zijn is Onbereikbaar`() {
        every { sessies.verlopen(100) } returns Uni.createFrom().failure(IllegalStateException("redis connection reset"))

        assertThrows<SessiecacheException.Onbereikbaar> { facade.veeg() }
    }

    // --- timeouts ---

    @Test
    fun `een await die de timeout overschrijdt is Onbereikbaar via de timeout-tak`() {
        // Mutiny's TimeoutException (van `atMost`) is geen subklasse van die uit de JDK; beide horen
        // als timeout herkend te worden en niet als willekeurige fout.
        val snel = BlockingSessiecache(service, volger, register, sessies, facadeAwaitTimeoutSeconds = 1)
        every { sessies.raak(SESSIE_ID, ontvanger) } returns Uni.createFrom().nothing()
        every { sessies.beeindig(SESSIE_ID, ontvanger) } returns Uni.createFrom().failure(java.util.concurrent.TimeoutException())

        val mutiny = assertThrows<SessiecacheException.Onbereikbaar> { snel.raakSessie(SESSIE_ID, ontvanger) }
        val jdk = assertThrows<SessiecacheException.Onbereikbaar> { snel.beeindigSessie(SESSIE_ID, ontvanger) }

        assertEquals(TIMEOUT_MELDING, mutiny.message)
        assertTrue(mutiny.cause is io.smallrye.mutiny.TimeoutException, "Was: ${mutiny.cause}")
        assertEquals(TIMEOUT_MELDING, jdk.message)
    }

    // --- config-validatie facade-await-timeout ---

    @Test
    fun `facade-await-timeout van 0 wordt geweigerd bij constructie`() {
        val ex = assertThrows<IllegalArgumentException> {
            BlockingSessiecache(service, volger, register, sessies, facadeAwaitTimeoutSeconds = 0)
        }

        assertTrue(ex.message!!.contains("facade-await-timeout-seconds"), "Was: ${ex.message}")
    }

    private fun vangLog(klasse: Class<*>, blok: () -> Unit): List<String> {
        val regels = java.util.concurrent.CopyOnWriteArrayList<String>()
        val logger = java.util.logging.Logger.getLogger(klasse.name)
        val vanger = object : java.util.logging.Handler() {
            override fun publish(record: java.util.logging.LogRecord) {
                val oorzaken = generateSequence(record.thrown) { it.cause }.joinToString("") { " | ${it.javaClass.name}: ${it.message}" }

                regels += String.format(record.message, *(record.parameters ?: emptyArray())) + oorzaken
            }

            override fun flush() = Unit

            override fun close() = Unit
        }

        logger.addHandler(vanger)

        try {
            blok()
        } finally {
            logger.removeHandler(vanger)
        }

        return regels
    }
}
