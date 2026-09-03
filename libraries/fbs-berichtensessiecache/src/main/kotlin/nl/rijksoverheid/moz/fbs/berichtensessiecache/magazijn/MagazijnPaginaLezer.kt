package nl.rijksoverheid.moz.fbs.berichtensessiecache.magazijn

import jakarta.enterprise.context.ApplicationScoped
import org.eclipse.microprofile.config.inject.ConfigProperty

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
     * Haalt de berichten van [client] op voor [ontvanger]. Gooit [MagazijnResponseOverflow] als een
     * pagina groter is dan gevraagd.
     */
    fun leesAlleBerichten(client: MagazijnClient, ontvanger: String): GepagineerdeBerichten {
        val verzameld = mutableListOf<MagazijnBericht>()
        var totaalBeschikbaar: Long? = null
        var paginaNummer = 0
        var laatstePaginaWasVol = false

        while (verzameld.size < maxBerichtenPerMagazijn) {
            val respons = client.getBerichten(ontvanger, null, paginaNummer, paginaGrootte)

            if (respons.berichten.size > paginaGrootte) {
                throw MagazijnResponseOverflow()
            }

            verzameld.addAll(respons.berichten)
            totaalBeschikbaar = respons.totalElements ?: totaalBeschikbaar
            laatstePaginaWasVol = respons.berichten.size == paginaGrootte
            paginaNummer++

            // Een niet-volle pagina is het einde van de lijst. `totalPages` is de tweede
            // stopvoorwaarde, niet de eerste: een magazijn is hier een implementatie van derden, en
            // een lus die volledig op andermans teller leunt, blijft doorvragen zodra die teller
            // onzin is.
            val magazijnMeldtMeer = respons.totalPages?.let { paginaNummer < it } ?: true

            if (!laatstePaginaWasVol || !magazijnMeldtMeer) {
                return GepagineerdeBerichten(verzameld, afgekapt = false, totaalBeschikbaar = totaalBeschikbaar)
            }
        }

        // De cap is bereikt. Weet het magazijn een totaal te noemen, dan is "is er meer" exact te
        // beantwoorden; anders is een volle laatste pagina het enige signaal dat we hebben — en dan
        // liever één keer te veel "er is meer" melden dan post laten verdwijnen zonder het te zeggen.
        val berichten = verzameld.take(maxBerichtenPerMagazijn)
        val afgekapt = totaalBeschikbaar?.let { it > berichten.size } ?: laatstePaginaWasVol

        return GepagineerdeBerichten(berichten, afgekapt = afgekapt, totaalBeschikbaar = totaalBeschikbaar)
    }

    private companion object {
        /** `pageSize`-maximum uit `berichtenmagazijn-api.yaml`; erboven antwoordt het magazijn 400. */
        const val SPEC_MAX_PAGE_SIZE = 100
    }
}
