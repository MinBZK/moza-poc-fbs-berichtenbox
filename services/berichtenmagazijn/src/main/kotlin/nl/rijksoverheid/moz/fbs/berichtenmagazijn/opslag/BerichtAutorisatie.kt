package nl.rijksoverheid.moz.fbs.berichtenmagazijn.opslag

import jakarta.ws.rs.ForbiddenException
import org.jboss.logging.Logger

/**
 * Centrale plek voor de ontvanger-check op een bericht: het bericht moet
 * matchen met de ontvanger uit de `X-Ontvanger`-header. Door deze check op
 * één plek te houden, blijft een eventuele uitbreiding (rolgebaseerde
 * autorisatie, externe PDP) lokaal.
 *
 * Logging laat opzettelijk de `waarde` weg (kan een BSN zijn): alleen
 * `berichtId` + `ontvanger.type` is voor diagnose voldoende; de Problem-
 * response draagt de correlation-id voor support.
 */
internal object BerichtAutorisatie {

    private val log = Logger.getLogger(BerichtAutorisatie::class.java)

    fun vereisOntvanger(bericht: Bericht, ontvanger: Identificatienummer) {
        // Value-class equals over (type, waarde): één check vangt zowel
        // type-mismatch als waarde-mismatch, zonder dat callers het risico lopen
        // om één van beide te vergeten.
        if (bericht.ontvanger != ontvanger) {
            log.warnf(
                "Autorisatie geweigerd: ontvanger-mismatch berichtId=%s berichtOntvangerType=%s headerOntvangerType=%s",
                bericht.berichtId,
                bericht.ontvanger.type,
                ontvanger.type,
            )
            throw ForbiddenException("Geen toegang tot dit bericht")
        }
    }
}
