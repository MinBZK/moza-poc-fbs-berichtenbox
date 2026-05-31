package nl.rijksoverheid.moz.fbs.berichtenuitvraag.uitvraag

import nl.mijnoverheidzakelijk.ldv.logboekdataverwerking.LogboekContext
import org.jboss.logging.Logger

/**
 * Validatie-pattern voor de `X-Ontvanger`-header. Identiek aan de spec
 * (`berichtenuitvraag-api.yaml#/components/parameters/OntvangerHeader`) zodat
 * handgeschreven endpoints (bv. de SSE-passthrough, die buiten codegen valt)
 * dezelfde input-integriteit afdwingen als de gegenereerde JAX-RS-interface.
 */
internal const val ONTVANGER_PATTERN = "^(BSN|RSIN|KVK|OIN):[0-9]+\$"

// Eén bron van waarheid: dezelfde regex die de Bean Validation op de endpoints
// (`@Pattern`) afdwingt, gecompileerd voor [splitOntvanger]. Zo kunnen parser en
// validator niet divergeren — een waarde die de validator afkeurt levert hier
// ook géén dataSubject. Een getypeerde Ontvanger met BSN-elfproef volgt met de
// tokenvalidatie van #414 (de ontvanger komt dan uit het gevalideerde token).
private val ONTVANGER_REGEX = Regex(ONTVANGER_PATTERN)

private val log: Logger = Logger.getLogger("nl.rijksoverheid.moz.fbs.berichtenuitvraag.uitvraag.Ontvanger")

/**
 * Splitst `X-Ontvanger` naar `(type, waarde)`, of `null` als de waarde niet aan
 * [ONTVANGER_PATTERN] voldoet. Pure functie zonder LDV-zijeffect zodat de format-
 * invariant — die de AVG art. 30-audittrail voedt — los testbaar is.
 */
internal fun splitOntvanger(xOntvanger: String): Pair<String, String>? {
    if (!ONTVANGER_REGEX.matches(xOntvanger)) return null

    val delen = xOntvanger.split(':', limit = 2)

    return delen[0] to delen[1]
}

/**
 * Registreert `type` en `waarde` uit `X-Ontvanger` in de LDV-context voor de
 * huidige request. Vooraf gevalideerd door Bean Validation op het resource-
 * parameter; een waarde zonder `:` betekent dat de validator faalde of werd
 * omzeild — dan géén dataSubject schrijven (de globale ExceptionMapper vertaalt
 * validatie-fouten al naar 400), maar wél signaleren zodat een audittrail met
 * ontbrekend subject niet stil ontstaat.
 */
internal fun registreerLdvSubject(logboekContext: LogboekContext, xOntvanger: String) {
    val parsed = splitOntvanger(xOntvanger)

    if (parsed == null) {
        log.warnf("X-Ontvanger voldoet niet aan het verwachte formaat (Type:waarde) bij LDV-registratie; dataSubject niet gezet")

        return
    }

    logboekContext.dataSubjectType = parsed.first
    logboekContext.dataSubjectId = parsed.second
}
