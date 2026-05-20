package nl.rijksoverheid.moz.fbs.berichtenmagazijn.retention

import jakarta.enterprise.context.ApplicationScoped
import jakarta.enterprise.context.control.ActivateRequestContext
import nl.mijnoverheidzakelijk.ldv.logboekdataverwerking.Logboek
import nl.mijnoverheidzakelijk.ldv.logboekdataverwerking.LogboekContext
import nl.rijksoverheid.moz.fbs.berichtenmagazijn.ProcessingActivities

/**
 * Schrijft één LDV-record per hard-delete. Wrapper omdat:
 *  1. `LogboekContext` is `@RequestScoped` — de scheduler-thread heeft geen
 *     actieve request, dus we activeren er handmatig één met
 *     `@ActivateRequestContext`.
 *  2. `@Logboek` werkt via een CDI-interceptor; die schiet alleen aan bij
 *     een proxy-call, dus de methode moet op een aparte bean staan (niet
 *     intern in [HardDeleteService]).
 *
 * `dataSubjectId = ontvangerWaarde` is toegestaan zolang het LDV-endpoint TLS
 * gebruikt (BIO 13.2.1 / CLAUDE.md "BSN/PII-handling"); `LdvEndpointValidator`
 * uit `fbs-common` dwingt dit al af in %prod/%staging/%acceptatie.
 */
@ApplicationScoped
class HardDeleteLdvLogger(
    private val logboekContext: LogboekContext,
) {

    @ActivateRequestContext
    @Logboek(
        name = "hard-delete-bericht",
        processingActivityId = ProcessingActivities.MAGAZIJN_RETENTIE,
    )
    fun logHardDelete(candidate: HardDeleteCandidaat) {
        logboekContext.dataSubjectId = candidate.ontvangerWaarde
        logboekContext.dataSubjectType = candidate.ontvangerType
        // De Logboek-interceptor leest de context bij span-close; daarvoor moet
        // de waarde gezet zijn vóór deze methode return.
    }
}
