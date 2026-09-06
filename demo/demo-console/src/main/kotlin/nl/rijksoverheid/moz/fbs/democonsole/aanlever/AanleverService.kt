package nl.rijksoverheid.moz.fbs.democonsole.aanlever

import nl.rijksoverheid.moz.fbs.democonsole.DemoConfig
import io.quarkus.rest.client.reactive.QuarkusRestClientBuilder
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject
import jakarta.ws.rs.ProcessingException
import jakarta.ws.rs.core.Response
import nl.rijksoverheid.moz.fbs.democonsole.generator.AanleverOpdracht
import java.io.IOException
import java.net.URI
import java.util.concurrent.TimeoutException
import java.util.logging.Logger

/**
 * Uitkomst van een vulronde. `mislukt` telt berichten waarvan het magazijn de ontvangst niet
 * bevestigde — meestal omdat ze er niet zijn, maar een antwoord dat wegvalt ná het opslaan komt
 * hier ook terecht. De twee tellers daarna tellen berichten die wél in het magazijn staan — en dus
 * ook als geslaagd tellen — maar waar naast de aflevering iets misging:
 *
 * - `markeringMislukt`: het bericht was niet op gelezen te zetten.
 * - `zonderBerichtId`: het magazijn bevestigde de ontvangst met een antwoord waar geen berichtId
 *   in stond. Dat telt los van `gelezen`: ook wanneer er niets te markeren viel, hoort de bediener
 *   te zien dát het magazijn haperde in plaats van een volledig groene melding.
 *
 * De laatste twee overlappen wanneer om gelezen gevraagd was: dat bericht is zowel zonder berichtId
 * binnengekomen als niet gemarkeerd. Het paneel noemt dan twee dingen over één bericht — beide
 * waar, en niet bij elkaar op te tellen.
 */
data class AanleverResultaat(
    val aangeboden: Int,
    val geslaagd: Int,
    val mislukt: Int,
    val markeringMislukt: Int,
    val zonderBerichtId: Int,
)

/**
 * Levert opdrachten aan bij het juiste magazijn. De magazijn-URL's komen uit config
 * (`demo.magazijnen."<OIN>".url`); per URL wordt één REST-client gebouwd en hergebruikt.
 */
@ApplicationScoped
class AanleverService internal constructor(private val clients: Map<String, MagazijnAanleverClient>) {

    // CDI bouwt de clients uit config; de map-constructor is de ingang voor tests met een eigen
    // magazijn. Zonder deze @Inject weet ArC niet welke van de twee het moet zijn.
    @Inject
    constructor(config: DemoConfig) : this(bouwClients(config))

    private val log = Logger.getLogger(AanleverService::class.java.name)

    fun leverAan(opdrachten: List<AanleverOpdracht>): AanleverResultaat {
        var geslaagd = 0
        var mislukt = 0
        var markeringMislukt = 0
        var zonderBerichtId = 0

        opdrachten.forEach { opdracht ->
            val client = clients[opdracht.magazijnOin]

            if (client == null) {
                log.warning("geen magazijn-URL voor OIN ${opdracht.magazijnOin} — opdracht overgeslagen")
                mislukt++

                return@forEach
            }

            when (val uitkomst = lever(opdracht, client)) {
                LeverUitkomst.Mislukt -> mislukt++

                LeverUitkomst.AfgeleverdZonderId -> {
                    geslaagd++
                    zonderBerichtId++

                    if (opdracht.gelezen) markeringMislukt++
                }

                is LeverUitkomst.Afgeleverd -> {
                    geslaagd++

                    if (opdracht.gelezen && !markeerGelezen(client, opdracht, uitkomst.berichtId)) markeringMislukt++
                }
            }
        }

        return AanleverResultaat(opdrachten.size, geslaagd, mislukt, markeringMislukt, zonderBerichtId)
    }

    private sealed interface LeverUitkomst {

        /** Afgeleverd, met het door het magazijn toegekende berichtId. */
        data class Afgeleverd(val berichtId: String) : LeverUitkomst

        /**
         * Afgeleverd — de Aanlever-API belooft bij een 201 dat het bericht is opgeslagen — maar het
         * antwoord droeg geen bruikbaar berichtId, dus de console kan het bericht niet meer
         * aanwijzen en het dus ook niet op gelezen zetten.
         */
        data object AfgeleverdZonderId : LeverUitkomst

        data object Mislukt : LeverUitkomst
    }

    private fun lever(opdracht: AanleverOpdracht, client: MagazijnAanleverClient): LeverUitkomst {
        // Eén hapering mag de vulling niet halverwege afbreken: dan rapporteert de console niets
        // over wat al wél is afgeleverd en levert een tweede poging dubbele berichten op. De
        // aanroep hier, het uitlezen van de body en het sluiten vallen daarom elk in een vangnet,
        // en dat vangnet is bewust breed. Welk exception-type een afgekapt antwoord precies
        // oplevert, is een implementatiedetail van de REST-client dat met een upgrade kan
        // verschuiven; de garantie dat de ronde doorloopt mag daar niet aan hangen.
        val response = try {
            client.leverAan(opdracht.verzoek)
        } catch (fout: Exception) {
            meldStoring("aanleveren bij magazijn ${opdracht.magazijnOin} mislukte", fout)

            return LeverUitkomst.Mislukt
        }

        return try {
            uitkomstVan(response, opdracht)
        } finally {
            sluitStil(response, opdracht.magazijnOin)
        }
    }

    /**
     * Leest uit het antwoord wat er van de aanlevering terechtkwam. Een blanco berichtId telt als
     * afwezig: dat zou een PATCH op `/berichten/` opleveren, een aanroep die alleen een tweede fout
     * geeft.
     */
    private fun uitkomstVan(response: Response, opdracht: AanleverOpdracht): LeverUitkomst {
        if (response.status != 201) {
            // Alleen het type van de ontvanger, nooit de waarde: een BSN hoort niet in
            // applicatielogs. De magazijn-OIN is publiek en wijst de fout net zo goed aan.
            log.warning(
                "aanleveren bij magazijn ${opdracht.magazijnOin} gaf HTTP ${response.status} " +
                    "voor ontvanger-type ${opdracht.verzoek.ontvanger.type}",
            )

            return LeverUitkomst.Mislukt
        }

        val berichtId = try {
            response.readEntity(AanleverRespons::class.java)?.berichtId
        } catch (fout: Exception) {
            return onleesbaar(opdracht, oorzaakketen(fout))
        }

        if (berichtId.isNullOrBlank()) return onleesbaar(opdracht, "geen berichtId in het antwoord")

        return LeverUitkomst.Afgeleverd(berichtId)
    }

    private fun onleesbaar(opdracht: AanleverOpdracht, oorzaak: String): LeverUitkomst {
        log.warning(
            "aanleveren bij magazijn ${opdracht.magazijnOin} gaf HTTP 201 zonder bruikbaar " +
                "berichtId ($oorzaak); het bericht staat in het magazijn, maar de console kan het " +
                "niet meer aanwijzen",
        )

        return LeverUitkomst.AfgeleverdZonderId
    }

    private fun markeerGelezen(client: MagazijnAanleverClient, opdracht: AanleverOpdracht, berichtId: String): Boolean {
        val ontvanger = opdracht.verzoek.ontvanger

        val response = try {
            client.markeer(berichtId, "${ontvanger.type}:${ontvanger.waarde}", StatusPatch(gelezen = true))
        } catch (fout: Exception) {
            meldStoring("markeren-gelezen van bericht $berichtId bij magazijn ${opdracht.magazijnOin} mislukte", fout)

            return false
        }

        return try {
            val gelukt = response.status == 200

            if (!gelukt) {
                log.warning(
                    "markeren-gelezen gaf HTTP ${response.status} voor bericht $berichtId " +
                        "bij magazijn ${opdracht.magazijnOin}",
                )
            }

            gelukt
        } finally {
            sluitStil(response, opdracht.magazijnOin)
        }
    }

    /**
     * Sluit een antwoord zonder de ronde te kunnen raken. `Response.close()` mag zelf gooien — het
     * afhandelen van een half afgekapte stream is precies het geval dat hier speelt — en dat mag
     * geen bericht kosten dat al is afgeleverd.
     */
    private fun sluitStil(response: Response, magazijnOin: String) {
        try {
            response.close()
        } catch (fout: Exception) {
            meldStoring("antwoord van magazijn $magazijnOin niet netjes te sluiten", fout)
        }
    }

    /**
     * Meldt een onderdrukte fout. Een storing van het magazijn of het netwerk is waar deze demo
     * knoppen voor heeft en hoort bij de ronde; alles daarbuiten is een fout in de console zelf. Het
     * paneel toont die twee hetzelfde — als mislukt bericht — dus de log is het enige dat een
     * bediener bij de goede oorzaak brengt, en dan moet de tweede soort er bovenuit komen.
     */
    private fun meldStoring(bericht: String, fout: Throwable) {
        val storing = fout is ProcessingException || fout is IOException || fout is TimeoutException

        if (storing) {
            log.warning("$bericht: ${oorzaakketen(fout)}")
        } else {
            log.severe("$bericht — onverwacht, dit is geen magazijnstoring: ${oorzaakketen(fout)}")
        }
    }

    /**
     * De klassennamen van een fout en zijn oorzaken, zonder ook maar één melding. `toString()` laat
     * juist de oorzaak weg terwijl die hier de diagnose draagt, maar een melding is geen veilige
     * logregel: de ontvanger reist mee in de request-body en in de X-Ontvanger-header, en een BSN
     * hoort niet in een applicatielog.
     */
    private fun oorzaakketen(fout: Throwable): String =
        generateSequence<Throwable>(fout) { huidige -> huidige.cause?.takeIf { it !== huidige } }
            .take(MAX_OORZAKEN)
            .joinToString(" <- ") { it::class.simpleName ?: it::class.java.name }

    private companion object {

        /** Hoe diep de oorzaakketen de log in gaat; genoeg voor wrapper-om-wrapper, en cyclusvast. */
        const val MAX_OORZAKEN = 5

        fun bouwClients(config: DemoConfig): Map<String, MagazijnAanleverClient> =
            config.magazijnen().mapValues { (_, magazijn) ->
                QuarkusRestClientBuilder.newBuilder()
                    .baseUri(URI.create(magazijn.url()))
                    .build(MagazijnAanleverClient::class.java)
            }
    }
}
