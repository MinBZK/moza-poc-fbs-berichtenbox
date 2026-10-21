package nl.rijksoverheid.moz.fbs.berichtenuitvraag.uitvraag

import io.quarkus.test.junit.QuarkusTestProfile
import io.smallrye.mutiny.Multi
import jakarta.enterprise.context.ApplicationScoped
import jakarta.enterprise.inject.Alternative
import nl.rijksoverheid.moz.fbs.berichtensessiecache.Sessiecache
import nl.rijksoverheid.moz.fbs.berichtensessiecache.SessiecacheException
import nl.rijksoverheid.moz.fbs.berichtensessiecache.berichten.Bericht
import nl.rijksoverheid.moz.fbs.berichtensessiecache.berichten.BerichtenPagina
import nl.rijksoverheid.moz.fbs.berichtensessiecache.berichten.Leesstatus
import nl.rijksoverheid.moz.fbs.berichtensessiecache.berichten.MagazijnEvent
import nl.rijksoverheid.moz.fbs.berichtensessiecache.berichten.SessieGebeurtenis
import nl.rijksoverheid.moz.fbs.berichtensessiecache.berichten.toSamenvatting
import nl.rijksoverheid.moz.fbs.berichtensessiecache.sessie.Opruiming
import nl.rijksoverheid.moz.fbs.berichtensessiecache.sessie.SessieEinde
import nl.rijksoverheid.moz.fbs.berichtensessiecache.sessie.SessieStart
import nl.rijksoverheid.moz.fbs.kern.identificatie.Identificatienummer
import java.time.Instant
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import nl.rijksoverheid.moz.fbs.berichtensessiecache.berichten.Volledigheid

/**
 * In-memory [Sessiecache]-facade voor de uitvraag-testsuite: seedbare berichten,
 * per-methode fout-injectie (FIFO voor schrijfpaden zodat dual-write-scenario's
 * "eerste call faalt, compensatie slaagt" kunnen uitdrukken) en aanroep-registratie
 * voor parameter-binding-asserties. De gereed-status-gating en foutvertaling van de
 * echte facade worden in de library zelf getest; hier telt het uitvraag-gedrag.
 *
 * Geactiveerd per testklasse via [MockSessiecacheProfile] — geen globale `@Mock`,
 * zodat de E2E-keten-test de échte facade kan draaien.
 */
@Alternative
@ApplicationScoped
class MockSessiecache : Sessiecache {

    // Gesleuteld op (magazijnId, berichtId), zoals de echte cache: hetzelfde nummer kan bij twee
    // organisaties voorkomen.
    val berichten = ConcurrentHashMap<Pair<String, UUID>, Bericht>()

    fun zet(bericht: Bericht) {
        berichten[bericht.magazijnId to bericht.berichtId] = bericht
    }

    /** Het enige bericht met dit nummer; voor tests die maar één magazijn kennen. */
    fun berichtMet(berichtId: UUID): Bericht? = berichten.values.singleOrNull { it.berichtId == berichtId }

    // Sync-facade-foutinjectie is getypeerd op SessiecacheException: de echte facade gooit
    // uitsluitend dat type, dus dwingt de compiler af dat tests geen onbereikbaar fout-pad
    // (bv. een rauwe WebApplicationException/ForbiddenException) simuleren.
    var lijstFout: SessiecacheException? = null
    var zoekFout: SessiecacheException? = null
    var berichtFout: SessiecacheException? = null
    val werkBijFouten = ArrayDeque<SessiecacheException>()
    val verwijderFouten = ArrayDeque<SessiecacheException>()
    // ophalen() is het streaming-pad met een eigen (WAE-)foutkanaal; bewust niet vernauwd.
    var ophalenFout: RuntimeException? = null
    var ophalenEvents: Multi<MagazijnEvent> = Multi.createFrom().empty()
    var volgFout: SessiecacheException? = null
    var volgGebeurtenissen: Multi<SessieGebeurtenis> = Multi.createFrom().item(SessieGebeurtenis.SessieVerlopen)
    val volgSessies = mutableListOf<String>()

    var lijstResultaat: BerichtenPagina? = null

    var laatstePagina: Int? = null
    var laatsteGrootte: Int? = null
    var laatsteZoekQ: String? = null
    var laatsteWerkBijStatus: Leesstatus? = null
    var laatsteWerkBijMap: String? = null
    var werkBijAanroepen = 0
    val verwijderAanroepen = mutableListOf<Pair<String, UUID>>()

    val schrijfFouten = ArrayDeque<SessiecacheException>()
    val schrijfAanroepen = mutableListOf<Bericht>()

    /** Lopende sessies: id → ontvanger. Geen verloop; dat gedrag wordt in de library getest. */
    val sessies = ConcurrentHashMap<String, Identificatienummer>()
    var startSessieFout: SessiecacheException? = null

    /** Laat de volgende start melden dat hij resten van een verlopen sessie wiste. */
    var restenBijVolgendeStart = false
    var raakFout: SessiecacheException? = null

    /** Wat de volgende veegrondes als opruimingen teruggeven, één lijst per aanroep; daarna leeg. */
    val veegRondes = ConcurrentLinkedQueue<List<Opruiming>>()

    /** Neemt het over van [veegRondes]: krijgt het gevraagde maximum en levert zelf de ronde. */
    @Volatile
    var veegRonde: ((Int) -> List<Opruiming>)? = null
    var veegFout: SessiecacheException? = null
    val veegAanroepen = AtomicInteger()
    val geraakteSessies = CopyOnWriteArrayList<String>()

    /** Start een sessie zoals `POST /sessies` dat zou doen, voor tests die een geldige `X-Sessie` nodig hebben. */
    fun sessieVoor(ontvanger: String = "BSN:999990019"): String =
        startSessie(Identificatienummer.fromHeader(ontvanger)).sessieId

    fun reset() {
        sessies.clear()
        startSessieFout = null
        restenBijVolgendeStart = false
        raakFout = null
        veegRondes.clear()
        veegRonde = null
        veegFout = null
        veegAanroepen.set(0)
        geraakteSessies.clear()
        berichten.clear()
        schrijfFouten.clear()
        schrijfAanroepen.clear()
        lijstFout = null
        zoekFout = null
        berichtFout = null
        werkBijFouten.clear()
        verwijderFouten.clear()
        ophalenFout = null
        ophalenEvents = Multi.createFrom().empty()
        volgFout = null
        volgGebeurtenissen = Multi.createFrom().item(SessieGebeurtenis.SessieVerlopen)
        volgSessies.clear()
        lijstResultaat = null
        laatstePagina = null
        laatsteGrootte = null
        laatsteZoekQ = null
        laatsteWerkBijStatus = null
        laatsteWerkBijMap = null
        werkBijAanroepen = 0
        verwijderAanroepen.clear()
    }

    override fun lijst(
        ontvanger: Identificatienummer,
        pagina: Int?,
        paginaGrootte: Int?,
        afzender: String?,
        map: String?,
    ): BerichtenPagina {
        lijstFout?.let { throw it }

        laatstePagina = pagina
        laatsteGrootte = paginaGrootte

        return lijstResultaat ?: paginaVan(pagina, paginaGrootte)
    }

    override fun zoek(
        ontvanger: Identificatienummer,
        q: String,
        pagina: Int?,
        paginaGrootte: Int?,
        afzender: String?,
        map: String?,
    ): BerichtenPagina {
        zoekFout?.let { throw it }

        laatsteZoekQ = q

        return lijstResultaat ?: paginaVan(pagina, paginaGrootte)
    }

    override fun bericht(ontvanger: Identificatienummer, magazijnId: String, berichtId: UUID): Bericht? {
        berichtFout?.let { throw it }

        return berichten[magazijnId to berichtId]
    }

    override fun werkBerichtBij(
        ontvanger: Identificatienummer,
        magazijnId: String,
        berichtId: UUID,
        status: Leesstatus?,
        map: String?,
    ): Bericht? {
        werkBijAanroepen++
        werkBijFouten.removeFirstOrNull()?.let { throw it }
        laatsteWerkBijStatus = status
        laatsteWerkBijMap = map

        val bestaand = berichten[magazijnId to berichtId] ?: return null
        val nieuweMap = if (map == Sessiecache.MAP_WISSEN) null else map ?: bestaand.map
        val bijgewerkt = bestaand.copy(status = status ?: bestaand.status, map = nieuweMap)
        berichten[magazijnId to berichtId] = bijgewerkt

        return bijgewerkt
    }

    override fun verwijder(ontvanger: Identificatienummer, magazijnId: String, berichtId: UUID) {
        verwijderAanroepen += magazijnId to berichtId
        verwijderFouten.removeFirstOrNull()?.let { throw it }
        berichten.remove(magazijnId to berichtId)
    }

    override fun ophalen(ontvanger: Identificatienummer): Multi<MagazijnEvent> {
        ophalenFout?.let { throw it }

        return ophalenEvents
    }

    override fun schrijfBericht(ontvanger: Identificatienummer, bericht: Bericht): Bericht {
        schrijfAanroepen += bericht
        schrijfFouten.removeFirstOrNull()?.let { throw it }
        zet(bericht)

        return bericht
    }

    override fun volg(ontvanger: Identificatienummer, sessieId: String): Multi<SessieGebeurtenis> {
        volgSessies += sessieId
        volgFout?.let { throw it }

        return volgGebeurtenissen
    }

    override fun startSessie(ontvanger: Identificatienummer): SessieStart {
        startSessieFout?.let { throw it }

        // Dezelfde grens als de echte facade, zodat de 429 van de uitvraag hier ook uit de grens komt.
        if (sessies.values.count { it == ontvanger } >= MAX_SESSIES_PER_ONTVANGER) {
            throw SessiecacheException.TeVeelSessies("Te veel sessies tegelijk.")
        }

        val sessieId = UUID.randomUUID().toString().replace("-", "").take(SESSIE_ID_LENGTE)
        sessies[sessieId] = ontvanger
        val resten = restenBijVolgendeStart
        restenBijVolgendeStart = false

        return SessieStart(sessieId, Instant.now().plusSeconds(900), restenOpgeruimd = resten)
    }

    override fun raakSessie(sessieId: String, ontvanger: Identificatienummer) {
        geraakteSessies += sessieId
        raakFout?.let { throw it }

        if (sessies[sessieId] != ontvanger) throw SessiecacheException.SessieOngeldig("Sessie is verlopen of ongeldig.")
    }

    override fun beeindigSessie(sessieId: String, ontvanger: Identificatienummer): SessieEinde {
        if (!sessies.remove(sessieId, ontvanger)) return SessieEinde.ONBEKEND

        return if (sessies.containsValue(ontvanger)) SessieEinde.BEEINDIGD else SessieEinde.OPGERUIMD
    }

    override fun ruimVerlopenSessiesOp(max: Int): List<Opruiming> {
        veegAanroepen.incrementAndGet()
        veegFout?.let { throw it }

        return veegRonde?.invoke(max) ?: veegRondes.poll().orEmpty()
    }

    private fun paginaVan(pagina: Int?, paginaGrootte: Int?): BerichtenPagina {
        val alle = berichten.values.sortedByDescending { it.publicatietijdstip }.map { it.toSamenvatting() }
        val grootte = paginaGrootte ?: 20

        return BerichtenPagina(
            berichten = alle,
            page = pagina ?: 0,
            pageSize = grootte,
            totalElements = alle.size.toLong(),
            totalPages = if (alle.isEmpty()) 0 else ((alle.size + grootte - 1) / grootte),
            volledigheid = Volledigheid.VOLLEDIG,
        )
    }
}

// Gelijk aan de lengte van een echt sessie-id, zodat de vorm-eis van de uitvraag-API hem accepteert.
private const val SESSIE_ID_LENGTE = 22

// Gelijk aan de default van `berichtensessiecache.sessie.max-per-ontvanger`.
private const val MAX_SESSIES_PER_ONTVANGER = 5

/**
 * Activeert [MockSessiecache] als alternative voor de echte facade. Redis blijft
 * uit: niets in de keten raakt de library-beans (lazy), dus Dev Services zijn
 * niet nodig.
 */
class MockSessiecacheProfile : QuarkusTestProfile {

    override fun getEnabledAlternatives(): Set<Class<*>> = setOf(MockSessiecache::class.java)

    override fun getConfigOverrides(): Map<String, String> = mapOf(
        // Met de facade-alternative is de hele library-bean-graaf "ongebruikt" en zou
        // ArC hem wegoptimaliseren — inclusief de @ConfigMapping-root
        // (berichtensessiecache.bericht), waarna de main-properties met SRCFG00050 de boot
        // laten falen. Unremovable houden spiegelt de productie-samenstelling.
        "quarkus.arc.unremovable-types" to "nl.rijksoverheid.moz.fbs.berichtensessiecache.**",
        // Dummy-host: de gemockte facade raakt Redis nooit, maar de (unremovable)
        // client-bean wil bij creatie een host kunnen resolven.
        "quarkus.redis.hosts" to "redis://localhost:6379",
    )
}
