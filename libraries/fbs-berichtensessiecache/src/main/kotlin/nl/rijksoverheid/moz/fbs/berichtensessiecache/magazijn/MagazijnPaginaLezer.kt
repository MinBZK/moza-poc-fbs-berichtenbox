package nl.rijksoverheid.moz.fbs.berichtensessiecache.magazijn

import io.quarkus.runtime.StartupEvent
import jakarta.enterprise.context.ApplicationScoped
import jakarta.enterprise.event.Observes
import org.eclipse.microprofile.config.inject.ConfigProperty
import java.time.Duration
import java.util.UUID

/**
 * Alle berichten van één magazijn, over de pagina's heen. [afgekapt] zegt dat het magazijn er méér
 * heeft dan de cap toeliet: [berichten] draagt de nieuwste, de rest is niet opgehaald.
 * [totaalBeschikbaar] is het aantal dat het magazijn zelf noemde, of null als het geen totaal
 * meestuurde.
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
 * De cap is dus een grens, geen fout. Zit een magazijn erboven, dan levert dit de nieuwste
 * [maxBerichtenPerMagazijn] berichten mét [GepagineerdeBerichten.afgekapt], zodat het portaal kan
 * tonen dat er meer is. Alleen een magazijn dat zijn eigen paginering negeert — één pagina groter
 * dan de gevraagde [paginaGrootte] — is een echte fout: dan is de respons onbegrensd én niet te
 * pagineren, en gooit dit een [MagazijnResponseOverflow].
 *
 * Blokkerend: de gegenereerde [MagazijnClient] is synchroon en de pagina's worden na elkaar
 * opgehaald. De aanroeper draait dit op de worker-pool, binnen de per-magazijn query-timeout die om
 * álle pagina's samen staat — een magazijn houdt zo hetzelfde totale tijdsbudget als toen het één
 * call was.
 */
@ApplicationScoped
internal class MagazijnPaginaLezer(
    // Het spec-maximum van `pageSize` op de magazijn-API. Lager zetten kost extra round-trips
    // binnen hetzelfde tijdsbudget; hoger wijst het magazijn af met een 400.
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
     * Haalt de berichten van [client] op voor [ontvanger], binnen [budget]. Gooit
     * [MagazijnResponseOverflow] als een pagina groter is dan gevraagd.
     *
     * [budget] is dezelfde per-magazijn query-timeout die de aanroeper om deze aanroep legt. Die
     * timeout faalt de `Uni` wel, maar onderbreekt deze blokkerende lus niet: zonder eigen
     * deadline zou de verlaten thread ná de timeout nog pagina's blijven ophalen, terwijl zijn
     * bulkhead-permit al is vrijgegeven. De lus stopt daarom zelf zodra het budget op is.
     */
    fun leesAlleBerichten(client: MagazijnClient, ontvanger: String, budget: Duration): GepagineerdeBerichten {
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

            // Een niet-volle pagina is het einde van de lijst. `totalPages` is de tweede
            // stopvoorwaarde, niet de eerste: een magazijn is hier een implementatie van derden, en
            // een lus die volledig op andermans teller leunt, blijft doorvragen zodra die teller
            // onzin is. Ontbreekt de teller, dan telt alleen de paginavulling.
            lijstCompleet = respons.berichten.size < paginaGrootte ||
                (respons.totalPages ?: Int.MAX_VALUE) <= paginaNummer

            // Een volle pagina zonder één nieuw bericht betekent dat het magazijn `page` negeert en
            // steeds hetzelfde teruggeeft; doorvragen levert dan alleen herhaling op. De deadline is
            // de eigen rem van deze lus: de query-timeout van de aanroeper faalt de `Uni` wel, maar
            // onderbreekt de lopende blokkerende call niet.
            if (lijstCompleet || !paginaLeverdeNieuws || System.nanoTime() >= deadline) {
                break
            }
        }

        val berichten = verzameld.values.take(maxBerichtenPerMagazijn)

        return GepagineerdeBerichten(
            berichten = berichten,
            afgekapt = isAfgekapt(berichten.size, verzameld.size, totaalBeschikbaar, lijstCompleet),
            totaalBeschikbaar = totaalBeschikbaar,
        )
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

    /** Voegt de pagina toe zonder duplicaten; false zodra een pagina niets nieuws bracht. */
    private fun voegToe(verzameld: MutableMap<UUID, MagazijnBericht>, pagina: List<MagazijnBericht>): Boolean {
        val voorDezePagina = verzameld.size

        pagina.forEach { bericht -> verzameld.putIfAbsent(bericht.berichtId, bericht) }

        return verzameld.size > voorDezePagina
    }

    /**
     * Of wij minder leveren dan het magazijn heeft. Noemt het magazijn een totaal, dan is dat exact
     * te beantwoorden — ook als het kleinere pagina's teruggaf dan gevraagd, want dan zou een
     * volle-pagina-heuristiek de lijst stil afkappen. Zonder totaal blijft over: hebben we zelf
     * weggelaten, of stopte de lus vóór het einde van de lijst? In beide gevallen liever één keer te
     * veel "er is meer" melden dan post laten verdwijnen zonder het te zeggen.
     */
    private fun isAfgekapt(geleverd: Int, verzameld: Int, totaalBeschikbaar: Long?, lijstCompleet: Boolean): Boolean =
        totaalBeschikbaar?.let { it > geleverd } ?: (verzameld > geleverd || !lijstCompleet)

    private companion object {
        /** `pageSize`-maximum uit `berichtenmagazijn-api.yaml`; erboven antwoordt het magazijn 400. */
        const val SPEC_MAX_PAGE_SIZE = 100
    }
}
