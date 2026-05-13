package nl.rijksoverheid.moz.fbs.berichtenmagazijn.opslag

import jakarta.ws.rs.ForbiddenException
import org.jboss.logging.Logger

/**
 * PoC-PEP voor toegang tot een bericht: ontvanger op het bericht moet matchen
 * met de ontvanger uit de `X-Ontvanger`-header. Wordt later vervangen door de
 * echte AuthZEN PEP/PDP (Issue 10) — tot die tijd centraliseren we de check
 * hier zodat Ophaal- en Beheer-services niet uit elkaar drijven.
 *
 * Logging laat opzettelijk de `waarde` weg (kan een BSN zijn): alleen
 * `berichtId` + `ontvanger.type` is voor diagnose voldoende; de Problem-
 * response draagt de correlation-id voor support.
 */
internal object BerichtAutorisatie {

    private val log = Logger.getLogger(BerichtAutorisatie::class.java)

    fun vereisOntvanger(bericht: Bericht, ontvanger: Identificatienummer) {
        if (bericht.ontvanger.type != ontvanger.type || bericht.ontvanger.waarde != ontvanger.waarde) {
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
