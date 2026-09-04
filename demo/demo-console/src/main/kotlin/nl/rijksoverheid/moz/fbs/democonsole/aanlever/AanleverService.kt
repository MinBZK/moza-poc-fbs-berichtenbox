package nl.rijksoverheid.moz.fbs.democonsole.aanlever

import jakarta.enterprise.context.ApplicationScoped
import jakarta.ws.rs.ProcessingException
import nl.rijksoverheid.moz.fbs.democonsole.generator.AanleverOpdracht
import nl.rijksoverheid.moz.fbs.democonsole.generator.OntvangerDto
import java.util.logging.Logger

/**
 * Uitkomst van een vulronde. `mislukt` telt niet-afgeleverde berichten; `markeringMislukt` telt
 * berichten die wél zijn afgeleverd maar niet op gelezen konden worden gezet — die tellen als
 * geslaagd, want het bericht staat in het magazijn, alleen de lees-mix klopt niet.
 *
 * `letOp` zegt waaróm er iets niet aankwam. Zonder die zin laat "1 mislukt" de bediener kiezen
 * tussen een storing die nog aanstaat, een ondernemer die daar niet geregistreerd staat en een
 * omgeving die niet af is. Blijft leeg zolang er niets misging; het paneel toont hem dan niet.
 */
data class AanleverResultaat(
    val aangeboden: Int,
    val geslaagd: Int,
    val mislukt: Int,
    val markeringMislukt: Int,
    val letOp: String? = null,
)

/** Uitkomst van één aanlevering: het toegekende berichtId, of de reden dat er geen kwam. */
private sealed interface Aanlevering {

    data class Gelukt(val berichtId: String) : Aanlevering

    data class Mislukt(val reden: String) : Aanlevering
}

/** Levert opdrachten aan bij het juiste magazijn; de clients komen uit [MagazijnClients]. */
@ApplicationScoped
class AanleverService(private val clients: MagazijnClients) {

    private val log = Logger.getLogger(AanleverService::class.java.name)

    fun leverAan(opdrachten: List<AanleverOpdracht>): AanleverResultaat {
        var geslaagd = 0
        var markeringMislukt = 0

        // De redenen en niet alleen een teller: `mislukt` is hun aantal, en de samenvatting eronder
        // maakt er de regel van die het paneel toont.
        val redenen = mutableListOf<String>()

        opdrachten.forEach { opdracht ->
            val client = clients[opdracht.magazijnOin]

            if (client == null) {
                log.warning("geen magazijn-URL voor OIN ${opdracht.magazijnOin} — opdracht overgeslagen")
                redenen += Faalreden.geenMagazijn(opdracht.magazijnOin)

                return@forEach
            }

            when (val uitkomst = lever(opdracht, client)) {
                is Aanlevering.Mislukt -> redenen += uitkomst.reden

                is Aanlevering.Gelukt -> {
                    geslaagd++

                    if (opdracht.gelezen && !markeerGelezen(client, uitkomst.berichtId, opdracht.verzoek.ontvanger)) {
                        markeringMislukt++
                    }
                }
            }
        }

        return AanleverResultaat(
            opdrachten.size,
            geslaagd,
            redenen.size,
            markeringMislukt,
            Faalreden.samenvatting(redenen),
        )
    }

    /** Levert één bericht aan; geeft het door het magazijn toegekende berichtId terug, of de reden. */
    private fun lever(opdracht: AanleverOpdracht, client: MagazijnAanleverClient): Aanlevering {
        // Een onbereikbaar magazijn — precies wat de storingsknoppen doen — mag de vulling niet
        // halverwege afbreken: dan rapporteert de console niets over wat al wél is afgeleverd en
        // levert een tweede poging dubbele berichten op.
        val response = try {
            client.leverAan(opdracht.verzoek)
        } catch (fout: ProcessingException) {
            log.warning("magazijn ${opdracht.magazijnOin} niet bereikbaar voor aanleveren: $fout")

            return Aanlevering.Mislukt(Faalreden.onbereikbaar(opdracht.magazijnOin))
        }

        return response.use {
            if (it.status != 201) {
                // Alleen het type van de ontvanger, nooit de waarde: een BSN hoort niet in
                // applicatielogs. De magazijn-OIN is publiek en wijst de fout net zo goed aan.
                log.warning(
                    "aanleveren bij magazijn ${opdracht.magazijnOin} gaf HTTP ${it.status} " +
                        "voor ontvanger-type ${opdracht.verzoek.ontvanger.type}",
                )

                return@use Aanlevering.Mislukt(Faalreden.vanStatus(opdracht.magazijnOin, it.status))
            }

            Aanlevering.Gelukt(it.readEntity(AanleverRespons::class.java).berichtId)
        }
    }

    private fun markeerGelezen(client: MagazijnAanleverClient, berichtId: String, ontvanger: OntvangerDto): Boolean {
        val header = "${ontvanger.type}:${ontvanger.waarde}"

        val response = try {
            client.markeer(berichtId, header, StatusPatch(gelezen = true))
        } catch (fout: ProcessingException) {
            log.warning("magazijn niet bereikbaar voor markeren-gelezen van bericht $berichtId: $fout")

            return false
        }

        return response.use {
            if (it.status != 200) {
                log.warning("markeren-gelezen gaf HTTP ${it.status} voor bericht $berichtId")

                return@use false
            }

            true
        }
    }
}
