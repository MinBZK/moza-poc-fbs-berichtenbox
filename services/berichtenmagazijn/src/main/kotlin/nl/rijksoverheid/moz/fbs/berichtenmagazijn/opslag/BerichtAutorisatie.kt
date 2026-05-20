package nl.rijksoverheid.moz.fbs.berichtenmagazijn.opslag

import jakarta.ws.rs.ForbiddenException
import org.jboss.logging.Logger

/**
 * Centrale PEP voor toegang tot een bericht: ontvanger op het bericht moet
 * matchen met de ontvanger uit de `X-Ontvanger`-header. Wordt vervangen door
 * een AuthZEN PEP/PDP zodra die beschikbaar is — door dit centraal te houden
 * blijft die overstap één plek code.
 *
 * Logging laat opzettelijk de `waarde` weg (kan een BSN zijn): alleen
 * `berichtId` + `ontvanger.type` is voor diagnose voldoende; de Problem-
 * response draagt de correlation-id voor support.
 */
internal object BerichtAutorisatie {

    private val log = Logger.getLogger(BerichtAutorisatie::class.java)

    fun vereisOntvanger(bericht: Bericht, ontvanger: Identificatienummer) {
        // Value-class equals over (type, waarde): één check vangt zowel type- als
        // waarde-mismatch, zonder dat callers het risico lopen één van beide te
        // vergeten.
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
