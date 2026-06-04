package nl.rijksoverheid.moz.fbs.berichtenuitvraag.uitvraag

import io.quarkus.test.junit.QuarkusTestProfile
import io.smallrye.mutiny.Multi
import jakarta.enterprise.context.ApplicationScoped
import jakarta.enterprise.inject.Alternative
import nl.rijksoverheid.moz.fbs.berichtensessiecache.Sessiecache
import nl.rijksoverheid.moz.fbs.berichtensessiecache.berichten.Bericht
import nl.rijksoverheid.moz.fbs.berichtensessiecache.berichten.BerichtenPagina
import nl.rijksoverheid.moz.fbs.berichtensessiecache.berichten.Leesstatus
import nl.rijksoverheid.moz.fbs.berichtensessiecache.berichten.MagazijnEvent
import nl.rijksoverheid.moz.fbs.berichtensessiecache.berichten.toSamenvatting
import nl.rijksoverheid.moz.fbs.common.identificatie.Identificatienummer
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

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

    val berichten = ConcurrentHashMap<UUID, Bericht>()

    var lijstFout: RuntimeException? = null
    var zoekFout: RuntimeException? = null
    var berichtFout: RuntimeException? = null
    val werkBijFouten = ArrayDeque<RuntimeException>()
    val verwijderFouten = ArrayDeque<RuntimeException>()
    var ophalenFout: RuntimeException? = null
    var ophalenEvents: Multi<MagazijnEvent> = Multi.createFrom().empty()

    var lijstResultaat: BerichtenPagina? = null

    var laatstePagina: Int? = null
    var laatsteGrootte: Int? = null
    var laatsteZoekQ: String? = null
    var laatsteWerkBijStatus: Leesstatus? = null
    var laatsteWerkBijMap: String? = null
    var werkBijAanroepen = 0
    val verwijderAanroepen = mutableListOf<UUID>()

    fun reset() {
        berichten.clear()
        lijstFout = null
        zoekFout = null
        berichtFout = null
        werkBijFouten.clear()
        verwijderFouten.clear()
        ophalenFout = null
        ophalenEvents = Multi.createFrom().empty()
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

    override fun bericht(ontvanger: Identificatienummer, berichtId: UUID): Bericht? {
        berichtFout?.let { throw it }

        return berichten[berichtId]
    }

    override fun werkBerichtBij(
        ontvanger: Identificatienummer,
        berichtId: UUID,
        status: Leesstatus?,
        map: String?,
    ): Bericht? {
        werkBijAanroepen++
        werkBijFouten.removeFirstOrNull()?.let { throw it }
        laatsteWerkBijStatus = status
        laatsteWerkBijMap = map

        val bestaand = berichten[berichtId] ?: return null
        val bijgewerkt = bestaand.copy(status = status ?: bestaand.status, map = map ?: bestaand.map)
        berichten[berichtId] = bijgewerkt

        return bijgewerkt
    }

    override fun verwijder(ontvanger: Identificatienummer, berichtId: UUID) {
        verwijderAanroepen += berichtId
        verwijderFouten.removeFirstOrNull()?.let { throw it }
        berichten.remove(berichtId)
    }

    override fun ophalen(ontvanger: Identificatienummer): Multi<MagazijnEvent> {
        ophalenFout?.let { throw it }

        return ophalenEvents
    }

    override fun schrijfBericht(ontvanger: Identificatienummer, bericht: Bericht): Bericht {
        berichten[bericht.berichtId] = bericht

        return bericht
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
        )
    }
}

/**
 * Activeert [MockSessiecache] als alternative voor de echte facade. Redis blijft
 * uit: niets in de keten raakt de library-beans (lazy), dus Dev Services zijn
 * niet nodig.
 */
class MockSessiecacheProfile : QuarkusTestProfile {

    override fun getEnabledAlternatives(): Set<Class<*>> = setOf(MockSessiecache::class.java)

    override fun getConfigOverrides(): Map<String, String> = mapOf(
        // Met de facade-alternative is de hele library-bean-graaf "ongebruikt" en zou
        // ArC hem wegoptimaliseren — inclusief de @ConfigMapping-roots (magazijnen.instances,
        // berichtensessiecache.bericht), waarna de main-properties met SRCFG00050 de boot
        // laten falen. Unremovable houden spiegelt de productie-samenstelling.
        "quarkus.arc.unremovable-types" to "nl.rijksoverheid.moz.fbs.berichtensessiecache.**",
        // Dummy-host: de gemockte facade raakt Redis nooit, maar de (unremovable)
        // client-bean wil bij creatie een host kunnen resolven.
        "quarkus.redis.hosts" to "redis://localhost:6379",
    )
}
