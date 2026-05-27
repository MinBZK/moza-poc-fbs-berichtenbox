package nl.rijksoverheid.moz.fbs.berichtenuitvraag.uitvraag

import nl.mijnoverheidzakelijk.ldv.logboekdataverwerking.LogboekContext

/**
 * Validatie-pattern voor de `X-Ontvanger`-header. Identiek aan de spec
 * (`berichtenuitvraag-api.yaml#/components/parameters/OntvangerHeader`) zodat
 * handgeschreven endpoints (bv. de SSE-passthrough, die buiten codegen valt)
 * dezelfde input-integriteit afdwingen als de gegenereerde JAX-RS-interface.
 */
internal const val ONTVANGER_PATTERN = "^(BSN|RSIN|KVK|OIN):[0-9]+\$"

/**
 * Splitst `Type:waarde` uit `X-Ontvanger` en registreert beide in de LDV-context
 * voor de huidige request. Vooraf gevalideerd door Bean Validation op het
 * resource-parameter — een waarde zonder `:` betekent dat de validator faalde
 * of werd omzeild en wordt stil overgeslagen (de globale ExceptionMapper
 * vertaalt validatie-fouten al naar 400).
 */
internal fun registreerLdvSubject(logboekContext: LogboekContext, xOntvanger: String) {
    val delen = xOntvanger.split(':', limit = 2)

    if (delen.size == 2) {
        logboekContext.dataSubjectId = delen[1]
        logboekContext.dataSubjectType = delen[0]
    }
}
