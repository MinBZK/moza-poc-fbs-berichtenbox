package nl.rijksoverheid.moz.fbs.democonsole.aanlever

import jakarta.enterprise.context.ApplicationScoped
import jakarta.ws.rs.ProcessingException
import jakarta.ws.rs.core.Response
import nl.rijksoverheid.moz.fbs.democonsole.generator.AanleverOpdracht
import nl.rijksoverheid.moz.fbs.democonsole.generator.OntvangerDto
import java.util.logging.Logger

/**
 * Uitkomst van een vulronde. `mislukt` telt niet-afgeleverde berichten; `markeringMislukt` telt
 * berichten die wél zijn afgeleverd maar niet op gelezen konden worden gezet — die tellen als
 * geslaagd, want het bericht staat in het magazijn, alleen de lees-mix klopt niet.
 *
 * `letOp` draagt de reden uit [Faalreden], en is null zolang er niets in de *aflevering* mislukte —
 * een mislukte markering krijgt geen reden, want dat bericht kwam wél aan. Alleen via [van] te
 * maken, en `copy()` erft die zichtbaarheid: `mislukt` en `letOp` komen zo aantoonbaar uit dezelfde
 * lijst en kunnen elkaar niet tegenspreken.
 */
@ConsistentCopyVisibility
data class AanleverResultaat private constructor(
    val aangeboden: Int,
    val geslaagd: Int,
    val mislukt: Int,
    val markeringMislukt: Int,
    val letOp: String?,
) {

    internal companion object {

        fun van(aangeboden: Int, geslaagd: Int, markeringMislukt: Int, redenen: List<String>) =
            AanleverResultaat(aangeboden, geslaagd, redenen.size, markeringMislukt, Faalreden.samenvatting(redenen))
    }
}

/** Uitkomst van één aanlevering: het toegekende berichtId, of de reden dat er geen kwam. */
private sealed interface Aanlevering {

    data class Gelukt(val berichtId: String) : Aanlevering

    data class Mislukt(val reden: String) : Aanlevering
}

/** Levert opdrachten aan bij het juiste magazijn. */
@ApplicationScoped
class AanleverService(private val clients: MagazijnClients) {

    private val log = Logger.getLogger(AanleverService::class.java.name)

    fun leverAan(opdrachten: List<AanleverOpdracht>): AanleverResultaat {
        var geslaagd = 0
        var markeringMislukt = 0
        val redenen = mutableListOf<String>()

        opdrachten.forEach { opdracht ->
            val client = clients[opdracht.magazijnOin]

            if (client == null) {
                log.warning("geen magazijn-URL voor OIN ${opdracht.magazijnOin} — opdracht overgeslagen")
                redenen += Faalreden.geenMagazijn(opdracht.magazijnOin)

                return@forEach
            }

            when (val uitkomst = leverBehoedzaam(opdracht, client)) {
                is Aanlevering.Mislukt -> redenen += uitkomst.reden

                is Aanlevering.Gelukt -> {
                    geslaagd++

                    if (opdracht.gelezen && !markeerGelezen(client, opdracht, uitkomst.berichtId)) markeringMislukt++
                }
            }
        }

        return AanleverResultaat.van(
            aangeboden = opdrachten.size,
            geslaagd = geslaagd,
            markeringMislukt = markeringMislukt,
            redenen = redenen,
        )
    }

    /**
     * Geen enkele fout mag de ronde afbreken: dan rapporteert de console niets over wat al wél is
     * afgeleverd en levert een tweede poging dubbele berichten op. Ruimer dan de `catch` in [lever],
     * die alleen dekt dat het magazijn niet te bereiken was — het lézen van het antwoord kan net zo
     * goed struikelen, op een 201 zonder berichtId bijvoorbeeld, of op een verbinding die na de
     * statusregel wegvalt omdat de bediener midden in de ronde een storing aanzette.
     */
    private fun leverBehoedzaam(opdracht: AanleverOpdracht, client: MagazijnAanleverClient): Aanlevering = try {
        lever(opdracht, client)
    } catch (fout: Exception) {
        log.warning("aanleveren bij magazijn ${opdracht.magazijnOin} brak af, ${herkomst(fout)}")

        Aanlevering.Mislukt(Faalreden.onverwacht(opdracht.magazijnOin, fout))
    }

    /**
     * Het type en de regel waar het misging, niet de foutmelding: die kan bij een serialisatiefout
     * een stuk van de payload dragen, en daar staat het identificatienummer van de ontvanger in.
     * Een BSN hoort niet in applicatielogs — dezelfde regel die de statusregel hieronder volgt.
     */
    private fun herkomst(fout: Throwable): String =
        "${fout.javaClass.name} bij ${fout.stackTrace.firstOrNull() ?: "een onbekende regel"}"

    private fun lever(opdracht: AanleverOpdracht, client: MagazijnAanleverClient): Aanlevering {
        val response = try {
            client.leverAan(opdracht.verzoek)
        } catch (fout: ProcessingException) {
            log.warning("magazijn ${opdracht.magazijnOin} niet bereikbaar voor aanleveren: $fout")

            return Aanlevering.Mislukt(Faalreden.onbereikbaar(opdracht.magazijnOin))
        }

        return response.use {
            if (it.status != AANGELEVERD) {
                // Alleen het type van de ontvanger, nooit de waarde: een BSN hoort niet in
                // applicatielogs. De magazijn-OIN is publiek en wijst de fout net zo goed aan.
                log.warning(
                    "aanleveren bij magazijn ${opdracht.magazijnOin} gaf HTTP ${it.status} " +
                        "voor ontvanger-type ${opdracht.verzoek.ontvanger.type}",
                )

                return@use Aanlevering.Mislukt(Faalreden.vanStatus(opdracht.magazijnOin, it.status, detailVan(it)))
            }

            Aanlevering.Gelukt(it.readEntity(AanleverRespons::class.java).berichtId)
        }
    }

    /**
     * De reden die het magazijn zelf gaf. Mislukt het lezen — een lege body, een foutpagina in
     * plaats van problem+json — dan valt [Faalreden.vanStatus] terug op zijn eigen zin; dat een
     * afwijzing niet uit te lezen was, mag die afwijzing niet verbergen.
     */
    private fun detailVan(response: Response): String? = try {
        if (response.hasEntity()) response.readEntity(Problem::class.java)?.detail else null
    } catch (fout: Exception) {
        // Op waarschuwingsniveau, want dit faalt systemisch of niet: gaat één afwijzing hierop
        // stuk, dan gaan ze allemaal stuk en toont het paneel de rest van de demo een algemene zin
        // terwijl het magazijn een preciezere gaf.
        log.warning("antwoord van het magazijn droeg geen leesbare problem+json, ${herkomst(fout)}")

        null
    }

    /**
     * Het bericht ligt hier al in het magazijn, dus een mislukte markering telt niet als mislukte
     * aflevering — maar mag de ronde net zomin afbreken. De logregels noemen het magazijn: met twee
     * magazijnen in de demo zegt een berichtId alleen niet welke van de twee de PATCH weigerde.
     */
    private fun markeerGelezen(
        client: MagazijnAanleverClient,
        opdracht: AanleverOpdracht,
        berichtId: String,
    ): Boolean = try {
        // Het hele blok in het vangnet, tot en met het sluiten van de respons: een verbinding die
        // halverwege wegvalt kan ook bij `close()` nog gooien, en dan viel de vulronde alsnog om.
        client.markeer(berichtId, ontvangerHeader(opdracht.verzoek.ontvanger), StatusPatch(gelezen = true))
            .use { antwoord ->
                if (antwoord.status != GEMARKEERD) {
                    log.warning(
                        "markeren-gelezen bij magazijn ${opdracht.magazijnOin} gaf HTTP ${antwoord.status} " +
                            "voor bericht $berichtId",
                    )
                }

                antwoord.status == GEMARKEERD
            }
    } catch (fout: Exception) {
        log.warning(
            "magazijn ${opdracht.magazijnOin} kon bericht $berichtId niet op gelezen zetten, ${herkomst(fout)}",
        )

        false
    }

    private fun ontvangerHeader(ontvanger: OntvangerDto): String = "${ontvanger.type}:${ontvanger.waarde}"

    private companion object {

        /** Het magazijn bevestigt een aanlevering met 201 en een geslaagde status-patch met 200. */
        const val AANGELEVERD = 201
        const val GEMARKEERD = 200
    }
}
