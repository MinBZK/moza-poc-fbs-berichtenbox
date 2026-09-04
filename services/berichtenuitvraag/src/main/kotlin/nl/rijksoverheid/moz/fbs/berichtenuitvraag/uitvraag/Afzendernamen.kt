package nl.rijksoverheid.moz.fbs.berichtenuitvraag.uitvraag

import jakarta.enterprise.context.ApplicationScoped
import nl.rijksoverheid.moz.fbs.common.exception.DomainValidationException
import nl.rijksoverheid.moz.fbs.common.identificatie.Oin
import nl.rijksoverheid.moz.fbs.magazijnregister.Magazijnregister
import org.jboss.logging.Logger

/**
 * Levert de weergavenaam van de afzendende organisatie bij een bericht uit de sessiecache.
 *
 * Elk bericht draagt de naam die bij het schrijven uit het register kwam, dus het antwoord is
 * er altijd. Toch wint het register wanneer het de organisatie kent: dan werkt een hernoeming
 * meteen door in plaats van pas na het verlopen van de sessie. De meegeschreven naam is het
 * vangnet voor het ene geval waarin het register niets weet — een organisatie die uit het
 * register verdween terwijl haar berichten nog in een lopende sessie staan.
 *
 * De lookup gaat op `magazijnId` en niet op het `afzender`-veld van het bericht: het eerste is
 * door de aggregatie toegekend en tegen het register gehouden, het tweede komt ongevalideerd uit
 * de payload van het magazijn. De naam volgt dus de organisatie waar het bericht vandaan kwam,
 * niet de organisatie die het bericht over zichzelf claimt.
 */
@ApplicationScoped
class Afzendernamen(private val register: Magazijnregister) {

    fun naamVoor(magazijnId: String, meegeschrevenNaam: String): String {
        val oin = try {
            Oin(magazijnId)
        } catch (ex: DomainValidationException) {
            // De rauwe waarde blijft uit de log: hij haalde de OIN-validatie niet en kan dus
            // alles bevatten, inclusief regeleindes die een logregel zouden kunnen vervalsen.
            log.debugf(ex, "Bericht in de sessiecache draagt een magazijnId dat geen geldige OIN is")

            return meegeschrevenNaam
        }

        val inschrijving = register.voorOin(oin)

        if (inschrijving == null) {
            // OIN voluit: publiek organisatienummer, geen PII, en precies wat ops nodig heeft om
            // de mismatch te herleiden. Debug en niet warn omdat deze lookup per bericht gebeurt —
            // één gedrift magazijn zou anders een hele pagina volschrijven, elke poll-ronde
            // opnieuw. Waar drift de gebruiker écht blokkeert (routering van detail, PATCH, DELETE
            // en bijlagen) escaleert MagazijnRouter hem naar error + 502.
            log.debugf("magazijnId '%s' uit de sessiecache staat niet in het magazijnregister — config-drift?", oin.waarde)

            return meegeschrevenNaam
        }

        return inschrijving.naam
    }

    private companion object {
        private val log: Logger = Logger.getLogger(Afzendernamen::class.java)
    }
}
