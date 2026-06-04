package nl.rijksoverheid.moz.fbs.berichtenmagazijn.retention

import jakarta.enterprise.context.ApplicationScoped
import nl.rijksoverheid.moz.fbs.berichtenmagazijn.ProcessingActivities
import org.jboss.logging.Logger

/**
 * Schrijft per hard-delete één gestructureerde log-regel met het processing-activity-ID,
 * het type van de ontvanger en het berichtId.
 *
 * **Bewust geen `@Logboek`-annotatie:** die interceptor probeert via
 * `W3CTraceContextPropagator` HTTP-headers te lezen voor trace-context, wat buiten
 * een actieve REST-request faalt met `No REST request in progress`. De scheduler-thread
 * heeft van zichzelf geen REST-context, dus de annotatie is hier niet bruikbaar.
 * Volwaardige LDV-integratie (ClickHouse-span via `ProcessingHandler.startSpan` direct)
 * is een vervolg-issue.
 *
 * Privacy: `ontvangerWaarde` (BSN/RSIN/KVK/OIN) staat conform CLAUDE.md *niet* in
 * applicatie-logs; alleen het `type` en `berichtId` worden gelogd.
 */
@ApplicationScoped
class HardDeleteLdvLogger {

    private val log = Logger.getLogger(HardDeleteLdvLogger::class.java)

    fun logHardDelete(candidate: HardDeleteKandidaat) {
        log.infof(
            "verwerkingsactiviteit=%s berichtId=%s ontvangerType=%s verwijderdOp=%s tijdstipOntvangst=%s",
            ProcessingActivities.MAGAZIJN_RETENTIE,
            candidate.berichtId,
            candidate.ontvangerType,
            candidate.verwijderdOp,
            candidate.tijdstipOntvangst,
        )
    }
}
