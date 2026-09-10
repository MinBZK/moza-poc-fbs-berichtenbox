package nl.rijksoverheid.moz.fbs.berichtenuitvraag.uitvraag

import jakarta.enterprise.context.ApplicationScoped
import nl.rijksoverheid.moz.fbs.berichtensessiecache.berichten.Bericht
import nl.rijksoverheid.moz.fbs.berichtensessiecache.berichten.BerichtSamenvatting
import nl.rijksoverheid.moz.fbs.common.exception.DomainValidationException
import nl.rijksoverheid.moz.fbs.common.identificatie.Oin
import nl.rijksoverheid.moz.fbs.magazijnregister.Magazijnregister
import org.jboss.logging.Logger
import java.util.concurrent.ConcurrentHashMap

/**
 * Levert de weergavenaam van de afzendende organisatie bij een bericht uit de sessiecache.
 *
 * Elk bericht draagt de naam die bij het schrijven uit het register kwam, dus er is altijd een
 * antwoord. Toch wint het register wanneer het de organisatie kent: dan werkt een hernoeming
 * meteen door in plaats van pas na het verlopen van de sessie. De meegeschreven naam is het
 * vangnet voor de gevallen waarin het register niets weet — een organisatie die eruit verdween
 * terwijl haar berichten nog in een lopende sessie staan, of een `magazijnId` uit een oudere
 * registerstaat.
 *
 * De lookup gaat op `magazijnId` en niet op het `afzender`-veld van het bericht. Op het
 * aggregatiepad is dat de sterkere van de twee: `magazijnId` is de sleutel van het magazijn dat
 * daadwerkelijk bevraagd is, terwijl `afzender` ongevalideerd uit de payload van dat magazijn
 * komt. Op het aanmeld-pad is `magazijnId` afgeleid van `data.afzender` uit de CloudEvent, dus
 * daar is de binding niet sterker dan wat de webhook zelf afdwingt.
 */
@ApplicationScoped
class Afzendernamen(private val register: Magazijnregister) {

    /**
     * Welke magazijnId's al gemeld zijn, zodat drift één regel per organisatie oplevert en niet
     * één per bericht: deze lookup draait per bericht en per poll-ronde. De set wordt nooit
     * geleegd — drift is een configuratiefout die tot de volgende deploy blijft bestaan — en is
     * begrensd op [MAX_GEMELDE_DRIFT] zodat een cache vol onleesbare waarden hem niet laat groeien.
     */
    private val gemeldeDrift = ConcurrentHashMap.newKeySet<String>()

    fun naamVoor(bericht: Bericht): String = naamVoor(bericht.magazijnId, bericht.afzenderNaam)

    fun naamVoor(samenvatting: BerichtSamenvatting): String =
        naamVoor(samenvatting.magazijnId, samenvatting.afzenderNaam)

    /**
     * Private, en dat is de bedoeling: twee `String`-parameters naast elkaar zijn verwisselbaar, en
     * verwisseld zou deze functie het `magazijnId` als naam teruggeven — precies het nummer-als-naam
     * dat de rest van deze wijziging wegneemt. De overloads hierboven maken die fout onmogelijk.
     */
    private fun naamVoor(magazijnId: String, meegeschrevenNaam: String): String {
        val oin = try {
            Oin(magazijnId)
        } catch (ex: DomainValidationException) {
            // De rauwe waarde blijft uit de log: hij haalde de OIN-validatie niet en kan dus alles
            // bevatten, inclusief regeleindes die een logregel zouden kunnen vervalsen. Beide
            // schrijfpaden valideren de OIN vóór opslag, dus elk voorkomen is een echt datadefect.
            meldEenmalig(magazijnId) {
                log.warnf(ex, "Bericht in de sessiecache draagt een magazijnId dat geen geldige OIN is")
            }

            return meegeschrevenNaam
        }

        val inschrijving = register.voorOin(oin)

        if (inschrijving == null) {
            // OIN voluit: publiek organisatienummer, geen PII, en precies wat ops nodig heeft om de
            // mismatch te herleiden. De lijst blijft leesbaar, maar bijlagen, markeren en
            // verwijderen op dit magazijn lopen wél stuk op 502 — daarom warn en niet debug.
            meldEenmalig(oin.waarde) {
                log.warnf(
                    "magazijnId '%s' uit de sessiecache staat niet in het magazijnregister; de lijst " +
                        "toont de meegeschreven naam, maar bijlage, markeren en verwijderen falen met 502",
                    oin.waarde,
                )
            }

            return meegeschrevenNaam
        }

        return inschrijving.naam
    }

    private fun meldEenmalig(sleutel: String, meld: () -> Unit) {
        if (gemeldeDrift.size < MAX_GEMELDE_DRIFT && gemeldeDrift.add(sleutel)) meld()
    }

    private companion object {

        // Ruim boven het aantal deelnemende organisaties; de cap is er alleen zodat de set niet
        // kan meegroeien met onleesbare waarden uit een corrupte cache.
        private const val MAX_GEMELDE_DRIFT = 1_000

        private val log: Logger = Logger.getLogger(Afzendernamen::class.java)
    }
}
