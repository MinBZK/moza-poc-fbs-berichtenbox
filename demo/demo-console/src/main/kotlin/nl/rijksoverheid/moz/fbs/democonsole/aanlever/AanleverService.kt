package nl.rijksoverheid.moz.fbs.democonsole.aanlever

import io.quarkus.rest.client.reactive.QuarkusRestClientBuilder
import jakarta.enterprise.context.ApplicationScoped
import jakarta.ws.rs.ProcessingException
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
class AanleverService(config: DemoConfig) {

    private val log = Logger.getLogger(AanleverService::class.java.name)

    private val clients: Map<String, MagazijnAanleverClient> =
        config.magazijnen().mapValues { (_, magazijn) ->
            QuarkusRestClientBuilder.newBuilder()
                .baseUri(URI.create(magazijn.url()))
                .build(MagazijnAanleverClient::class.java)
        }

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

            val berichtId = lever(opdracht, client)

            if (berichtId == null) {
                mislukt++

                return@forEach
            }

            geslaagd++

            if (opdracht.gelezen && !markeerGelezen(client, berichtId, opdracht.verzoek.ontvanger)) markeringMislukt++
        }

        return AanleverResultaat(opdrachten.size, geslaagd, mislukt, markeringMislukt)
    }

    /** Levert één bericht aan; geeft het door het magazijn toegekende berichtId terug, of null. */
    private fun lever(opdracht: AanleverOpdracht, client: MagazijnAanleverClient): String? {
        // Een onbereikbaar magazijn — precies wat de storingsknoppen doen — mag de vulling niet
        // halverwege afbreken: dan rapporteert de console niets over wat al wél is afgeleverd en
        // levert een tweede poging dubbele berichten op.
        val response = try {
            client.leverAan(opdracht.verzoek)
        } catch (fout: ProcessingException) {
            log.warning("magazijn ${opdracht.magazijnOin} niet bereikbaar voor aanleveren: $fout")

            return null
        }

        return response.use {
            if (it.status != 201) {
                // Alleen het type van de ontvanger, nooit de waarde: een BSN hoort niet in
                // applicatielogs. De magazijn-OIN is publiek en wijst de fout net zo goed aan.
                log.warning(
                    "aanleveren bij magazijn ${opdracht.magazijnOin} gaf HTTP ${it.status} " +
                        "voor ontvanger-type ${opdracht.verzoek.ontvanger.type}",
                )

                return@use null
            }

            it.readEntity(AanleverRespons::class.java).berichtId
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
