package nl.rijksoverheid.moz.fbs.berichtensessiecache.magazijn

import io.quarkus.runtime.StartupEvent
import jakarta.enterprise.context.ApplicationScoped
import jakarta.enterprise.event.Observes
import org.eclipse.microprofile.config.inject.ConfigProperty
import org.jboss.logging.Logger
import java.time.Duration
import java.util.UUID
import java.util.concurrent.TimeoutException

/**
 * Alle berichten van één magazijn, over de pagina's heen. [afgekapt]: er staat méér bij die
 * organisatie dan [berichten] draagt. [totaalBeschikbaar] is wat het magazijn zelf noemde, of null
 * als het geen bruikbaar totaal gaf.
 */
internal data class GepagineerdeBerichten(
    val berichten: List<MagazijnBericht>,
    val afgekapt: Boolean,
    val totaalBeschikbaar: Long?,
)

/**
 * Leest de berichtenlijst van één magazijn uit, pagina voor pagina. Zonder `page`/`pageSize` kiest
 * een magazijn zijn eigen default (twintig) en bleef de rest van de post liggen.
 *
 * De cap is een grens, geen fout: erboven komen de eerste [maxBerichtenPerMagazijn] berichten mét
 * [GepagineerdeBerichten.afgekapt]. "Eerste" is de volgorde die het magazijn aanhoudt — de
 * magazijn-API schrijft er geen voor. Eén pagina gróter dan gevraagd is wél een fout
 * ([MagazijnResponseOverflow]): onbegrensd en niet te pagineren.
 */
@ApplicationScoped
internal class MagazijnPaginaLezer(
    // Lager kost extra round-trips binnen hetzelfde tijdsbudget; boven het spec-maximum start de
    // service niet meer op.
    @param:ConfigProperty(name = "berichtensessiecache.magazijn-page-size", defaultValue = "100")
    private val paginaGrootte: Int,
    // Vijf pagina's van honderd: ruim voor jaren post bij dezelfde afzender, en vijf sequentiële
    // calls passen binnen de query-timeout. Hoger vergroot de kans dat een traag magazijn die
    // timeout raakt en dan hélemaal niets levert. Een cap op het AANTAL berichten, niet op bytes.
    @param:ConfigProperty(name = "berichtensessiecache.max-berichten-per-magazijn", defaultValue = "500")
    private val maxBerichtenPerMagazijn: Int,
) {
    private val log = Logger.getLogger(MagazijnPaginaLezer::class.java)

    init {
        require(paginaGrootte in 1..SPEC_MAX_PAGE_SIZE) {
            "berichtensessiecache.magazijn-page-size ($paginaGrootte) moet tussen 1 en $SPEC_MAX_PAGE_SIZE liggen"
        }

        require(maxBerichtenPerMagazijn > 0) {
            "berichtensessiecache.max-berichten-per-magazijn ($maxBerichtenPerMagazijn) moet groter zijn dan 0"
        }
    }

    /**
     * Dwingt de config-validatie hierboven af bij het opstarten. Zonder deze observer maakt ArC de
     * bean pas aan bij de eerste ophaalronde: readiness groen, en daarna faalt élke ronde.
     */
    fun onStartup(@Observes event: StartupEvent) = Unit

    /**
     * Gooit [MagazijnResponseOverflow] bij een pagina groter dan gevraagd, en [TimeoutException] als
     * [budget] op is voordat de lijst binnen was.
     *
     * [budget] is de query-timeout van de aanroeper. Die faalt de `Uni` wel, maar onderbreekt deze
     * blokkerende lus niet — zonder eigen deadline haalt de verlaten thread nog pagina's op terwijl
     * zijn bulkhead-permit al terug is. Afbreken meldt een timeout en geen halve oogst: die zou de
     * ontvanger als volledige lijst bereiken.
     */
    fun leesAlleBerichten(
        client: MagazijnClient,
        magazijnId: String,
        ontvanger: String,
        budget: Duration,
    ): GepagineerdeBerichten {
        // Op berichtId, want pagina's zijn niet gegarandeerd disjunct: een bericht dat tijdens het
        // pagineren binnenkomt schuift het venster op, en staat dan tweemaal in de oogst.
        val verzameld = LinkedHashMap<UUID, MagazijnBericht>()
        val deadline = System.nanoTime() + budget.toNanos()
        var totaalBeschikbaar: Long? = null
        var paginaNummer = 0
        var lijstCompleet = false

        while (verzameld.size < maxBerichtenPerMagazijn) {
            val respons = haalPagina(client, ontvanger, paginaNummer)
            val paginaLeverdeNieuws = voegToe(verzameld, respons.berichten)

            totaalBeschikbaar = respons.totalElements ?: totaalBeschikbaar
            paginaNummer++
            lijstCompleet = isEindeVanDeLijst(respons, verzameld.size, paginaNummer)

            // De ontvanger blijft bewust buiten elke regel: die string draagt het BSN.
            log.debugf(
                "Magazijn %s pagina %d: %d van %d gevraagd, oogst nu %d; magazijn meldt totalElements=%s totalPages=%s",
                magazijnId,
                paginaNummer - 1,
                respons.berichten.size,
                paginaGrootte,
                verzameld.size,
                respons.totalElements?.toString() ?: "-",
                respons.totalPages?.toString() ?: "-",
            )

            // Volle pagina zonder nieuw bericht: het magazijn negeert `page`, doorvragen herhaalt.
            if (lijstCompleet || !paginaLeverdeNieuws) {
                break
            }

            if (System.nanoTime() >= deadline) {
                throw TimeoutException("Magazijn niet uitgelezen binnen $budget (pagina $paginaNummer)")
            }
        }

        val berichten = verzameld.values.take(maxBerichtenPerMagazijn)
        val bruikbaarTotaal = saneerTotaal(totaalBeschikbaar, verzameld.size)
        val afgekapt = isAfgekapt(berichten.size, verzameld.size, bruikbaarTotaal, lijstCompleet)

        log.debugf(
            "Magazijn %s uitgelezen: %d pagina's, %d berichten, afgekapt=%s, totaalBeschikbaar=%s; gestopt op %s",
            magazijnId,
            paginaNummer,
            berichten.size,
            afgekapt,
            bruikbaarTotaal?.toString() ?: "onbekend",
            stopreden(lijstCompleet, verzameld.size),
        )

        return GepagineerdeBerichten(
            berichten = berichten,
            afgekapt = afgekapt,
            totaalBeschikbaar = bruikbaarTotaal,
        )
    }

    /** Waaróm de lus stopte, voor de debug-regel; af te leiden uit de eindtoestand. */
    private fun stopreden(lijstCompleet: Boolean, verzameld: Int): String = when {
        lijstCompleet -> "einde van de lijst"
        verzameld >= maxBerichtenPerMagazijn -> "cap van $maxBerichtenPerMagazijn bereikt"
        else -> "magazijn herhaalde dezelfde pagina"
    }

    /** Eén pagina, met de contract-check: méér dan gevraagd is niet te pagineren en onbegrensd. */
    private fun haalPagina(client: MagazijnClient, ontvanger: String, paginaNummer: Int): MagazijnBerichtenResponse {
        val respons = client.getBerichten(ontvanger, null, paginaNummer, paginaGrootte)

        if (respons.berichten.size > paginaGrootte) {
            throw MagazijnResponseOverflow()
        }

        return respons
    }

    private fun voegToe(verzameld: MutableMap<UUID, MagazijnBericht>, pagina: List<MagazijnBericht>): Boolean {
        val voorDezePagina = verzameld.size

        pagina.forEach { bericht -> verzameld.putIfAbsent(bericht.berichtId, bericht) }

        return verzameld.size > voorDezePagina
    }

    /**
     * Een lege pagina is het einde; een korte pagina alleen als het magazijn dat met een eigen teller
     * bevestigt. Een magazijn mag `pageSize` naar zijn eigen maximum bijstellen, en dan is een
     * halfvolle pagina een clamp en geen einde — het pad waarlangs deze lus opnieuw stil twintig van
     * driehonderd berichten zou ophalen.
     */
    private fun isEindeVanDeLijst(respons: MagazijnBerichtenResponse, verzameld: Int, gelezenPaginas: Int): Boolean {
        if (respons.berichten.isEmpty()) {
            return true
        }

        // Alleen geldig als de teller niet lager is dan wat we binnenhaalden: anders spreekt hij
        // zichzelf tegen en zegt hij niets.
        if (respons.totalElements?.let { verzameld >= it && it >= 0 } == true) {
            return true
        }

        val paginasOp = respons.totalPages?.let { gelezenPaginas >= it } ?: false

        return respons.berichten.size < paginaGrootte && paginasOp
    }

    /**
     * Null zodra het totaal zichzelf tegenspreekt — negatief, of lager dan onze eigen oogst.
     * Doorlaten zou het laten meetellen als "er is niet meer" én het tonen als "3 van -1".
     */
    private fun saneerTotaal(totaal: Long?, verzameld: Int): Long? = totaal?.takeIf { it >= 0 && it >= verzameld }

    /**
     * Of wij minder leveren dan er staat. Een bruikbaar totaal beslist — exact, en het bespaart een
     * melding als "500 van 500 — niet alles opgehaald"; anders beslist ons eigen bewijs. Restrisico:
     * een magazijn dat precies uitpagineert wat het als totaal noemt terwijl er meer ligt.
     */
    private fun isAfgekapt(geleverd: Int, verzameld: Int, totaalBeschikbaar: Long?, lijstCompleet: Boolean): Boolean =
        totaalBeschikbaar?.let { it > geleverd } ?: (verzameld > geleverd || !lijstCompleet)

    private companion object {
        /** `pageSize`-maximum uit `berichtenmagazijn-api.yaml`; erboven antwoordt het magazijn 400. */
        const val SPEC_MAX_PAGE_SIZE = 100
    }
}
