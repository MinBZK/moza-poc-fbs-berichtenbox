package nl.rijksoverheid.moz.fbs.berichtenuitvraag.uitvraag

import jakarta.enterprise.context.ApplicationScoped
import nl.rijksoverheid.moz.fbs.common.exception.DomainValidationException
import nl.rijksoverheid.moz.fbs.common.identificatie.Oin
import nl.rijksoverheid.moz.fbs.magazijnregister.Magazijnregister
import org.jboss.logging.Logger

/**
 * Zoekt bij een `magazijnId` de weergavenaam van de afzendende organisatie op.
 *
 * Het register is de bron, niet de ophaalronde: daardoor draagt ook een bericht van een
 * organisatie die in deze sessie nog niet bevraagd is meteen een naam, en hoeft een
 * afnemer niets tussen aanroepen door te onthouden. In het 1:1-model OIN↔magazijn is het
 * `magazijnId` de afzender-OIN zelf, dus is er geen tussenstap nodig om de organisatie te
 * vinden. De naam volgt daarmee het magazijn waaruit het bericht kwam, niet de afzender
 * die het bericht over zichzelf claimt — het eerste is tegen het register gehouden, het
 * tweede komt ongevalideerd uit de magazijn-payload.
 *
 * Er zijn drie redenen waarom er geen naam is, en alle drie leveren `null`: het register
 * kent de organisatie zonder naam (bedoelde configuratie), het kent de organisatie niet
 * (config-drift), of het `magazijnId` is geen geldige OIN (een cache-entry uit een oudere
 * registerstaat). Terugvallen op het `magazijnId` zou een twintigcijferig nummer als naam
 * presenteren en juist verbergen dát er geen naam is.
 */
@ApplicationScoped
class Afzendernamen(private val register: Magazijnregister) {

    fun naamVoor(magazijnId: String): String? {
        val oin = try {
            Oin(magazijnId)
        } catch (ex: DomainValidationException) {
            // De rauwe waarde blijft uit de log: hij haalde de OIN-validatie niet en kan dus
            // alles bevatten, inclusief regeleindes die een logregel zouden kunnen vervalsen.
            log.debugf(ex, "Bericht in de sessiecache draagt een magazijnId dat geen geldige OIN is; geen afzendernaam")

            return null
        }

        val inschrijving = register.voorOin(oin)

        if (inschrijving == null) {
            // OIN voluit: publiek organisatienummer, geen PII, en het is precies wat ops nodig
            // heeft om de mismatch te herleiden. Debug en niet warn omdat deze lookup per
            // bericht gebeurt — één gedrift magazijn zou anders een hele pagina volschrijven,
            // elke poll-ronde opnieuw. Waar drift de gebruiker écht blokkeert (routering van
            // detail, PATCH, DELETE en bijlagen) escaleert MagazijnRouter hem naar error + 502.
            log.debugf("magazijnId '%s' uit de sessiecache staat niet in het magazijnregister — config-drift?", oin.waarde)

            return null
        }

        return inschrijving.naam
    }

    private companion object {
        private val log: Logger = Logger.getLogger(Afzendernamen::class.java)
    }
}
