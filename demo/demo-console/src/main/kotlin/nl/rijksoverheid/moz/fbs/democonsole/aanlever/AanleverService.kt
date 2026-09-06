package nl.rijksoverheid.moz.fbs.democonsole.aanlever

import nl.rijksoverheid.moz.fbs.democonsole.DemoConfig
import io.quarkus.rest.client.reactive.QuarkusRestClientBuilder
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject
import jakarta.ws.rs.ProcessingException
import jakarta.ws.rs.WebApplicationException
import jakarta.ws.rs.core.Response
import nl.rijksoverheid.moz.fbs.democonsole.generator.AanleverOpdracht
import java.net.URI
import java.util.logging.Logger

/**
 * Uitkomst van een vulronde. `mislukt` telt berichten waarvan het magazijn de ontvangst niet
 * bevestigde — meestal omdat ze er niet zijn, maar een antwoord dat wegvalt ná het opslaan komt
 * hier ook terecht. De twee tellers daarna tellen berichten die wél in het magazijn staan — en dus
 * ook als geslaagd tellen — maar waar naast de aflevering iets misging:
 *
 * - `markeringMislukt`: de PATCH die het bericht op gelezen zet, is geprobeerd en mislukt.
 * - `zonderBerichtId`: het magazijn bevestigde de ontvangst met een antwoord waar geen bruikbaar
 *   berichtId uit te halen was — en zonder dat valt er ook niets te markeren. Dat telt los van
 *   `gelezen`: ook wanneer er niets te markeren viel, hoort de bediener te zien dát het magazijn
 *   haperde in plaats van een volledig groene melding.
 *
 * De twee sluiten elkaar uit, zodat het paneel per bericht één cijfer noemt: een aflevering zonder
 * berichtId telt alleen als `zonderBerichtId`, ook wanneer om gelezen was gevraagd. Anders leest één
 * bericht als twee problemen.
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
                // Geen storing maar een inrichtingsfout: deze OIN staat niet in demo.magazijnen,
                // en dat herstelt zichzelf niet en raakt elk bericht voor dat magazijn.
                meld("geen magazijn-URL voor OIN ${opdracht.magazijnOin} — opdracht overgeslagen", storing = false)
                mislukt++

                return@forEach
            }

            when (val uitkomst = lever(opdracht, client)) {
                LeverUitkomst.Mislukt -> mislukt++

                LeverUitkomst.AfgeleverdZonderId -> {
                    geslaagd++
                    zonderBerichtId++
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
        // en dat vangnet is bewust breed. Het uitlezen van de statuscode valt erbuiten: dat is een
        // veldlezing op een antwoord dat er al is. Welk exception-type een afgekapt antwoord precies
        // oplevert, is een implementatiedetail van de REST-client dat met een upgrade kan
        // verschuiven; de garantie dat de ronde doorloopt mag daar niet aan hangen.
        val response = try {
            client.leverAan(opdracht.verzoek)
        } catch (fout: Exception) {
            meld("aanleveren bij magazijn ${opdracht.magazijnOin} mislukte", isStoring(fout, bijUitlezen = false), fout)

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
            //
            // Een 4xx betekent dat de console iets ongeldigs stuurde — geen magazijnstoring, en het
            // treft elk bericht van de ronde op dezelfde manier.
            meld(
                "aanleveren bij magazijn ${opdracht.magazijnOin} gaf HTTP ${response.status} " +
                    "voor ontvanger-type ${opdracht.verzoek.ontvanger.type}",
                storing = isStoring(response.status),
            )

            return LeverUitkomst.Mislukt
        }

        val berichtId = try {
            response.readEntity(AanleverRespons::class.java)?.berichtId
        } catch (fout: Exception) {
            return onleesbaar(opdracht, fout)
        }

        if (berichtId.isNullOrBlank()) return onleesbaar(opdracht, null)

        return LeverUitkomst.Afgeleverd(berichtId)
    }

    private fun onleesbaar(opdracht: AanleverOpdracht, fout: Throwable?): LeverUitkomst {
        val melding = "aanleveren bij magazijn ${opdracht.magazijnOin} gaf HTTP 201 zonder bruikbaar " +
            "berichtId; het bericht staat in het magazijn, maar de console kan het niet meer aanwijzen"

        if (fout == null) {
            meld("$melding (het antwoord droeg er geen)", storing = true)
        } else {
            meld(melding, isStoring(fout, bijUitlezen = true), fout)
        }

        return LeverUitkomst.AfgeleverdZonderId
    }

    private fun markeerGelezen(client: MagazijnAanleverClient, opdracht: AanleverOpdracht, berichtId: String): Boolean {
        val ontvanger = opdracht.verzoek.ontvanger

        val response = try {
            client.markeer(berichtId, "${ontvanger.type}:${ontvanger.waarde}", StatusPatch(gelezen = true))
        } catch (fout: Exception) {
            meld(
                "markeren-gelezen van bericht $berichtId bij magazijn ${opdracht.magazijnOin} mislukte",
                isStoring(fout, bijUitlezen = false),
                fout,
            )

            return false
        }

        return try {
            val gelukt = response.status == 200

            if (!gelukt) {
                meld(
                    "markeren-gelezen gaf HTTP ${response.status} voor bericht $berichtId " +
                        "bij magazijn ${opdracht.magazijnOin}",
                    // Een 404 hoort er ook bij: het magazijn is dan het bericht kwijt dat het één
                    // aanroep eerder zelf met een 201 bevestigde. Dat is de overkant, niet de console.
                    storing = isStoring(response.status) || response.status == 404,
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
            meld("antwoord van magazijn $magazijnOin niet netjes te sluiten", isStoring(fout, bijUitlezen = true), fout)
        }
    }

    /**
     * Schrijft een onderdrukte fout weg. `storing` scheidt wat het magazijn of de lijn ernaartoe
     * aangaat van wat uit de console zelf komt: het paneel telt allebei hooguit als één cijfer en
     * noemt de oorzaak nooit, dus de log is het enige dat een bediener bij de goede kant brengt — en
     * dan hoort een fout aan onze kant erbovenuit te komen. Die treft namelijk elk bericht van de
     * ronde, terwijl een storing bij het volgende bericht alweer voorbij kan zijn.
     */
    private fun meld(melding: String, storing: Boolean, fout: Throwable? = null) {
        val oorzaak = fout?.let { ": ${oorzaakketen(it)}" }.orEmpty()

        if (storing) {
            log.warning("$melding$oorzaak")
        } else {
            log.severe("$melding — onverwacht, dit is geen magazijnstoring$oorzaak${plek(fout)}")
        }
    }

    /**
     * Gaat deze fout het magazijn of de lijn ernaartoe aan, of komt hij uit de console zelf? De
     * REST-client wikkelt alles wat er onderweg misgaat in een `ProcessingException`, ook wat er in
     * de asynchrone pipeline gebeurt zoals het schrijven van de request-body. Alleen een
     * `WebApplicationException` en een blocking aanroep op de event-loop komen ongewikkeld door, dus
     * het bovenste type is hier het signaal en niet de oorzaak eronder.
     *
     * Een `WebApplicationException` draagt een statuscode van het magazijn en wordt dus op status
     * beoordeeld. Bij het uitlezen of sluiten van een antwoord telt `IllegalStateException` als
     * storing: een stream die al gesloten is doordat het antwoord halverwege wegviel, meldt zich zo.
     * Bij de aanroep zélf wijst datzelfde type juist op blocking op de event-loop, en dan hoort het
     * luid.
     */
    private fun isStoring(fout: Throwable, bijUitlezen: Boolean) = when {
        fout is WebApplicationException -> isStoring(fout.response.status)
        fout is ProcessingException -> true
        else -> bijUitlezen && fout is IllegalStateException
    }

    /**
     * Alleen een 5xx zegt dat het magazijn het even niet aankon; daar komen de
     * wacht-en-probeer-later-codes bij, want die komen ook van de overkant en het magazijn gebruikt
     * voor zijn eigen retries dezelfde lijst. Al het andere is onze kant: een 4xx betekent dat we
     * iets ongeldigs stuurden, en een status buiten 4xx en 5xx die hier belandt is een gebroken
     * contract — het magazijn antwoordt dan iets waar de API geen betekenis aan geeft, en dat treft
     * elk bericht van de ronde.
     */
    private fun isStoring(status: Int) = status in 500..599 || status in WACHTCODES

    /**
     * De klassennamen van een fout en zijn oorzaken, zonder ook maar één melding. `toString()` laat
     * juist de oorzaak weg terwijl die hier de diagnose draagt, maar een melding is geen veilige
     * logregel: de ontvanger reist mee in de request-body en in de X-Ontvanger-header, en een BSN
     * hoort niet in een applicatielog.
     *
     * Java-reflectie en niet `::class`: deze functie draait binnen elke catch, en Kotlin-reflectie
     * kan zelf gooien — dan ontsnapt er alsnog een fout uit de ronde die dit vangnet moest houden.
     * `take` maakt de keten meteen cyclusvast: a-b-a levert vijf namen en stopt.
     */
    private fun oorzaakketen(fout: Throwable): String =
        generateSequence<Throwable>(fout) { huidige -> huidige.cause?.takeIf { it !== huidige } }
            .take(MAX_OORZAKEN)
            .joinToString(" <- ") { it.javaClass.simpleName.ifEmpty { it.javaClass.name } }

    /** Waar een onverwachte fout ontstond. Een frame draagt namen en regelnummers, geen gegevens. */
    private fun plek(fout: Throwable?): String =
        fout?.stackTrace?.firstOrNull()?.let { " @ ${it.className}.${it.methodName}:${it.lineNumber}" }.orEmpty()

    private companion object {

        /** Hoe diep de oorzaakketen de log in gaat; genoeg voor wrapper-om-wrapper. */
        const val MAX_OORZAKEN = 5

        /** Statuscodes die zeggen "later nog eens proberen"; die komen van de overkant. */
        val WACHTCODES = setOf(408, 429)

        fun bouwClients(config: DemoConfig): Map<String, MagazijnAanleverClient> =
            config.magazijnen().mapValues { (_, magazijn) ->
                QuarkusRestClientBuilder.newBuilder()
                    .baseUri(URI.create(magazijn.url()))
                    .build(MagazijnAanleverClient::class.java)
            }
    }
}
