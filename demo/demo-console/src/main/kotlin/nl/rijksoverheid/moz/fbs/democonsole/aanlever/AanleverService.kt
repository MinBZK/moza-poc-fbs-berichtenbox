package nl.rijksoverheid.moz.fbs.democonsole.aanlever

import nl.rijksoverheid.moz.fbs.democonsole.DemoConfig
import io.quarkus.rest.client.reactive.QuarkusRestClientBuilder
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject
import jakarta.ws.rs.ProcessingException
import jakarta.ws.rs.core.Response
import nl.rijksoverheid.moz.fbs.democonsole.generator.AanleverOpdracht
import nl.rijksoverheid.moz.fbs.democonsole.generator.OntvangerDto
import java.net.URI
import java.util.logging.Logger

/**
 * Uitkomst van een vulronde. `mislukt` telt niet-afgeleverde berichten; `markeringMislukt` telt
 * berichten die wél zijn afgeleverd maar niet op gelezen konden worden gezet — die tellen als
 * geslaagd, want het bericht staat in het magazijn, alleen de lees-mix klopt niet.
 */
data class AanleverResultaat(
    val aangeboden: Int,
    val geslaagd: Int,
    val mislukt: Int,
    val markeringMislukt: Int,
)

/**
 * Levert opdrachten aan bij het juiste magazijn. De magazijn-URL's komen uit config
 * (`demo.magazijnen."<OIN>".url`); per URL wordt één REST-client gebouwd en hergebruikt.
 */
@ApplicationScoped
class AanleverService internal constructor(private val clients: Map<String, MagazijnAanleverClient>) {

    @Inject
    constructor(config: DemoConfig) : this(bouwClients(config))

    private val log = Logger.getLogger(AanleverService::class.java.name)

    fun leverAan(opdrachten: List<AanleverOpdracht>): AanleverResultaat {
        var geslaagd = 0
        var mislukt = 0
        var markeringMislukt = 0

        opdrachten.forEach { opdracht ->
            val client = clients[opdracht.magazijnOin]

            if (client == null) {
                log.warning("geen magazijn-URL voor OIN ${opdracht.magazijnOin} — opdracht overgeslagen")
                mislukt++

                return@forEach
            }

            val berichtId = when (val uitkomst = lever(opdracht, client)) {
                LeverUitkomst.Mislukt -> {
                    mislukt++

                    return@forEach
                }

                LeverUitkomst.AfgeleverdZonderId -> null
                is LeverUitkomst.Afgeleverd -> uitkomst.berichtId
            }

            geslaagd++

            if (!opdracht.gelezen) return@forEach

            // Zonder berichtId valt er niets te patchen: dat telt als mislukte markering, niet als
            // mislukt bericht — het bericht zelf staat wél in het magazijn.
            if (berichtId == null || !markeerGelezen(client, berichtId, opdracht.verzoek.ontvanger)) {
                markeringMislukt++
            }
        }

        return AanleverResultaat(opdrachten.size, geslaagd, mislukt, markeringMislukt)
    }

    /** Wat er van één aanlevering terechtkwam. */
    private sealed interface LeverUitkomst {

        /** Afgeleverd, met het door het magazijn toegekende berichtId. */
        data class Afgeleverd(val berichtId: String) : LeverUitkomst

        /**
         * Afgeleverd — het magazijn gaf een 201 en stuurt die pas ná het opslaan — maar het antwoord
         * droeg geen bruikbaar berichtId, dus het bericht is niet meer op gelezen te zetten.
         */
        data object AfgeleverdZonderId : LeverUitkomst

        /** Niet afgeleverd. */
        data object Mislukt : LeverUitkomst
    }

    /** Levert één bericht aan bij het magazijn. */
    private fun lever(opdracht: AanleverOpdracht, client: MagazijnAanleverClient): LeverUitkomst {
        // Een magazijn dat hapert — precies wat de storingsknoppen doen — mag de vulling niet
        // halverwege afbreken: dan rapporteert de console niets over wat al wél is afgeleverd en
        // levert een tweede poging dubbele berichten op. Zowel de aanroep als het uitlezen van het
        // antwoord moet daarom binnen een vangnet vallen.
        val response = try {
            client.leverAan(opdracht.verzoek)
        } catch (fout: ProcessingException) {
            log.warning("magazijn ${opdracht.magazijnOin} niet bereikbaar voor aanleveren: $fout")

            return LeverUitkomst.Mislukt
        }

        return response.use {
            if (it.status != 201) {
                // Alleen het type van de ontvanger, nooit de waarde: een BSN hoort niet in
                // applicatielogs. De magazijn-OIN is publiek en wijst de fout net zo goed aan.
                log.warning(
                    "aanleveren bij magazijn ${opdracht.magazijnOin} gaf HTTP ${it.status} " +
                        "voor ontvanger-type ${opdracht.verzoek.ontvanger.type}",
                )

                return@use LeverUitkomst.Mislukt
            }

            val berichtId = berichtIdUit(it, opdracht)

            if (berichtId == null) LeverUitkomst.AfgeleverdZonderId else LeverUitkomst.Afgeleverd(berichtId)
        }
    }

    /**
     * Leest het toegekende berichtId uit een 201-antwoord, of null als dat antwoord het niet draagt:
     * afgekapt, leeg, geen JSON, of JSON zonder gevulde `berichtId`. Een blanco waarde telt als
     * afwezig — die zou een PATCH op `/berichten/` opleveren, een aanroep die alleen maar een tweede
     * fout oplevert.
     */
    private fun berichtIdUit(response: Response, opdracht: AanleverOpdracht): String? {
        val respons = try {
            response.readEntity(AanleverRespons::class.java)
        } catch (fout: ProcessingException) {
            log.warning(onleesbaar(opdracht, fout))

            return null
        } catch (fout: IllegalStateException) {
            // De entity wordt niet (meer) door een stream gedragen — bijvoorbeeld omdat de
            // verbinding wegviel voordat de body er was.
            log.warning(onleesbaar(opdracht, fout))

            return null
        }

        val berichtId = respons?.berichtId

        if (berichtId.isNullOrBlank()) {
            log.warning(onleesbaar(opdracht, "het antwoord droeg geen berichtId"))

            return null
        }

        return berichtId
    }

    private fun onleesbaar(opdracht: AanleverOpdracht, oorzaak: Any) =
        "aanleveren bij magazijn ${opdracht.magazijnOin} gaf HTTP 201 zonder bruikbaar berichtId " +
            "($oorzaak); het bericht staat in het magazijn maar is niet meer op gelezen te zetten"

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

    private companion object {

        fun bouwClients(config: DemoConfig): Map<String, MagazijnAanleverClient> =
            config.magazijnen().mapValues { (_, magazijn) ->
                QuarkusRestClientBuilder.newBuilder()
                    .baseUri(URI.create(magazijn.url()))
                    .build(MagazijnAanleverClient::class.java)
            }
    }
}
