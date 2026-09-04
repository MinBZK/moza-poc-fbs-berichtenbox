package nl.rijksoverheid.moz.fbs.berichtenuitvraag.uitvraag

import jakarta.enterprise.context.ApplicationScoped
import nl.rijksoverheid.moz.fbs.common.exception.DomainValidationException
import nl.rijksoverheid.moz.fbs.common.identificatie.Oin
import nl.rijksoverheid.moz.fbs.magazijnregister.Magazijnregister

/**
 * Zoekt bij een `magazijnId` de weergavenaam van de afzendende organisatie op.
 *
 * Het register is de bron, niet de ophaalronde: daardoor draagt ook een bericht van een
 * organisatie die in deze sessie nog niet bevraagd is meteen een naam, en hoeft een
 * afnemer niets tussen aanroepen door te onthouden. In het 1:1-model OIN↔magazijn is het
 * `magazijnId` de afzender-OIN zelf, dus is de lookup een directe register-hit.
 *
 * Kent het register geen naam, dan is het antwoord `null` en ontbreekt het API-veld.
 * Terugvallen op het `magazijnId` zou een twintigcijferig nummer als naam presenteren en
 * juist verbergen dát er geen naam is.
 */
@ApplicationScoped
class Afzendernamen(private val register: Magazijnregister) {

    fun naamVoor(magazijnId: String): String? {
        // Een magazijnId uit de sessiecache hoort een geldige OIN te zijn, maar cache-
        // entries overleven een registerwijziging. Een onleesbare waarde levert dan geen
        // naam op, in plaats van de hele berichtenlijst te laten falen.
        val oin = try {
            Oin(magazijnId)
        } catch (ignored: DomainValidationException) {
            return null
        }

        return register.voorOin(oin)?.naam
    }
}
