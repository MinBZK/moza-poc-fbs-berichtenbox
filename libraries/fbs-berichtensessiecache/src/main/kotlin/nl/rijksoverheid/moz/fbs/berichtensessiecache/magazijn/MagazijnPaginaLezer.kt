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
 * Alle berichten van één magazijn, over de pagina's heen. [afgekapt] zegt dat er méér bij die
 * organisatie staat dan [berichten] draagt — meestal omdat de cap bereikt was, maar ook wanneer het
 * magazijn zelf een hoger totaal meldt. [totaalBeschikbaar] is het aantal dat het magazijn noemde,
 * of null als het geen totaal meestuurde of een onmogelijk getal gaf.
 */
internal data class GepagineerdeBerichten(
    val berichten: List<MagazijnBericht>,
    val afgekapt: Boolean,
    val totaalBeschikbaar: Long?,
)

/**
 * Leest de berichtenlijst van één magazijn uit, pagina voor pagina, tot het magazijn leeg is of de
 * cap bereikt is.
 *
 * Waarom doorpagineren en niet één call: zonder `page`/`pageSize` kiest het magazijn zijn eigen
 * default (twintig). Een ontvanger met meer post bij dezelfde organisatie zag de rest dan nooit —
 * en niets meldde dat. Een berichtenbox die post weglaat zonder het te zeggen is erger dan een die
 * traag is: de ontvanger kan niet weten wat hij mist.
 *
 * De cap is dus een grens, geen fout. Zit een magazijn erboven, dan levert dit de eerste
 * [maxBerichtenPerMagazijn] berichten mét [GepagineerdeBerichten.afgekapt], zodat het portaal kan
 * tonen dat er meer is. "Eerste" is letterlijk de volgorde die het magazijn aanhoudt: ons eigen
 * magazijn zet de nieuwste vooraan, maar de magazijn-API schrijft geen ordening voor, dus die
 * belofte is niet van deze lezer. Alleen een magazijn dat zijn eigen paginering negeert — één
 * pagina groter dan de gevraagde [paginaGrootte] — is een echte fout: dan is de respons onbegrensd
 * én niet te pagineren, en gooit dit een [MagazijnResponseOverflow].
 *
 * Blokkerend: [MagazijnClient] is synchroon en de pagina's worden na elkaar opgehaald. De aanroeper
 * draait dit op de worker-pool, binnen de per-magazijn query-timeout die om álle pagina's samen
 * staat — een magazijn houdt zo hetzelfde totale tijdsbudget als toen het één call was.
 */
@ApplicationScoped
internal class MagazijnPaginaLezer(
    // Het spec-maximum van `pageSize` op de magazijn-API. Lager zetten kost extra round-trips
    // binnen hetzelfde tijdsbudget; hoger weigert de service bij het opstarten (zie het init-blok).
    @param:ConfigProperty(name = "berichtensessiecache.magazijn-page-size", defaultValue = "100")
    private val paginaGrootte: Int,
    // Grens op wat één magazijn aan berichten in de sessiecache mag leggen. Vijf pagina's van
    // honderd: ruim voor jaren post bij dezelfde afzender, en vijf sequentiële calls passen binnen
    // de query-timeout. Hoger zetten maakt de kans groter dat een traag magazijn de timeout raakt
    // en dan hélemaal niets levert. LET OP: dit is een cap op het AANTAL berichten, niet op bytes —
    // `quarkus.http.limits.max-body-size` geldt enkel voor INKOMENDE requests naar deze service.
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
     * Dwingt bean-instantiatie — en daarmee de config-validatie hierboven — af bij het opstarten.
     * Zonder deze observer maakt ArC de bean pas aan bij de eerste ophaalronde, en komt een
     * ongeldige paginagrootte pas aan het licht wanneer een gebruiker berichten opvraagt: pod
     * gestart, readiness groen, rollout geslaagd, en daarna faalt élk magazijn in élke ronde.
     */
    fun onStartup(@Observes event: StartupEvent) = Unit

    /**
     * Haalt de berichten van [client] op voor [ontvanger]. Gooit [MagazijnResponseOverflow] als een
     * pagina groter is dan gevraagd, en [TimeoutException] als [budget] op is voordat de lijst
     * binnen was.
     *
     * [budget] is dezelfde per-magazijn query-timeout die de aanroeper om deze aanroep legt. Die
     * timeout faalt de `Uni` wel, maar onderbreekt deze blokkerende lus niet: zonder eigen deadline
     * zou de verlaten thread ná de timeout nog pagina's blijven ophalen, terwijl zijn
     * bulkhead-permit al is vrijgegeven. De lus stopt daarom zelf zodra het budget op is, en meldt
     * dat als timeout in plaats van als deelresultaat — een halve lijst als "geslaagd" presenteren
     * zou opnieuw post weglaten, en de ontvanger kan aan zo'n lijst niet zien dat er iets ontbreekt.
     */
    fun leesAlleBerichten(
        client: MagazijnClient,
        magazijnId: String,
        ontvanger: String,
        budget: Duration,
    ): GepagineerdeBerichten {
        // Op berichtId, want opeenvolgende pagina's zijn niet gegarandeerd disjunct: een bericht
        // dat tijdens het doorpagineren wordt aangeleverd schuift het venster op, en dan staat het
        // bericht op de paginagrens tweemaal in de oogst — en straks tweemaal in de berichtenbox.
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

            // Een volle pagina zonder één nieuw bericht betekent dat het magazijn `page` negeert en
            // steeds hetzelfde teruggeeft; doorvragen levert dan alleen herhaling op.
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

    /**
     * Waaróm de lus stopte, voor de debug-regel. Af te leiden uit de eindtoestand: de enige uitgang
     * die noch het einde van de lijst noch de cap is, is de pagina die niets nieuws bracht.
     */
    private fun stopreden(lijstCompleet: Boolean, verzameld: Int): String = when {
        lijstCompleet -> "einde van de lijst"
        verzameld >= maxBerichtenPerMagazijn -> "cap van $maxBerichtenPerMagazijn bereikt"
        else -> "magazijn herhaalde dezelfde pagina"
    }

    /**
     * Eén pagina, met de contract-check erop: levert het magazijn er méér dan gevraagd, dan negeert
     * het zijn eigen paginering en is de respons onbruikbaar — niet te pagineren en onbegrensd.
     */
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
     * Een lege pagina is het einde, punt. Een korte pagina alleen wanneer het magazijn het met een
     * eigen teller bevestigt: een magazijn mag `pageSize` naar zijn eigen maximum bijstellen, en dan
     * is een halfvolle pagina géén einde maar een clamp — precies het pad waarlangs deze lus opnieuw
     * stil twintig van driehonderd berichten zou ophalen. Kost dat een extra lege call bij een
     * magazijn dat geen tellers meestuurt, dan is dat de prijs voor een `afgekapt` dat klopt.
     */
    private fun isEindeVanDeLijst(respons: MagazijnBerichtenResponse, verzameld: Int, gelezenPaginas: Int): Boolean {
        if (respons.berichten.isEmpty()) {
            return true
        }

        // Het magazijn zegt zelf dat we alles hebben. Alleen geldig als de teller niet lager is dan
        // wat we werkelijk binnenhaalden — anders spreekt hij zichzelf tegen en zegt hij niets.
        if (respons.totalElements?.let { verzameld >= it && it >= 0 } == true) {
            return true
        }

        val paginasOp = respons.totalPages?.let { gelezenPaginas >= it } ?: false

        return respons.berichten.size < paginaGrootte && paginasOp
    }

    /**
     * Het totaal dat het magazijn noemt, of null zodra dat getal aantoonbaar onzin is: negatief, of
     * lager dan wat we zelf al uit dat magazijn hebben opgehaald. Zo'n teller weerspreekt zichzelf,
     * en hem toch doorlaten zou hem als "er is niet meer" laten meetellen én als getal aan de
     * gebruiker tonen ("3 van -1"). Onbekend is dan een eerlijker antwoord dan onzin.
     */
    private fun saneerTotaal(totaal: Long?, verzameld: Int): Long? = totaal?.takeIf { it >= 0 && it >= verzameld }

    /**
     * Of wij minder leveren dan er bij die organisatie staat.
     *
     * Noemt het magazijn een bruikbaar totaal, dan is dat het antwoord: het is exact, en het houdt
     * de gebruiker een tegenstrijdige melding als "500 van 500 — niet alles opgehaald" bespaard.
     * Bruikbaar betekent hier: door [saneerTotaal] gekomen, dus niet lager dan wat we al binnen
     * hebben — een teller die zichzelf tegenspreekt is null en telt niet mee.
     *
     * Zonder zo'n totaal beslist ons eigen bewijs: hebben we op de cap weggelaten, of stopte de lus
     * voordat de lijst uit was, dan is "er is meer" het veilige antwoord. Blijft één restrisico dat
     * niemand kan wegnemen zonder voorbij de cap te lezen: een magazijn dat een totaal noemt dat
     * precies gelijk is aan wat het uitpagineert terwijl er meer ligt. Liever dat restrisico dan
     * standaard "er is meer" melden bij elke organisatie die toevallig precies de cap vult.
     */
    private fun isAfgekapt(geleverd: Int, verzameld: Int, totaalBeschikbaar: Long?, lijstCompleet: Boolean): Boolean =
        totaalBeschikbaar?.let { it > geleverd } ?: (verzameld > geleverd || !lijstCompleet)

    private companion object {
        /** `pageSize`-maximum uit `berichtenmagazijn-api.yaml`; erboven antwoordt het magazijn 400. */
        const val SPEC_MAX_PAGE_SIZE = 100
    }
}
