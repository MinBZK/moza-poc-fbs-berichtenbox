package nl.rijksoverheid.moz.fbs.berichtenuitvraag.uitvraag

import nl.mijnoverheidzakelijk.ldv.logboekdataverwerking.LogboekContext
import org.jboss.logging.Logger

/**
 * Validatie-pattern voor de `X-Ontvanger`-header. Identiek aan de spec
 * (`berichtenuitvraag-api.yaml#/components/parameters/OntvangerHeader`) en aan
 * de `@Pattern` op de gegenereerde JAX-RS-interface, zodat handgeschreven
 * endpoints (bv. de SSE-passthrough die buiten codegen valt) en [splitOntvanger]
 * niet kunnen divergeren met de validator. Een getypeerde, elfproef-gevalideerde
 * `Ontvanger` (uit het token) komt met #414 op deze seam in de plaats.
 */
internal const val ONTVANGER_PATTERN = "^(BSN|RSIN|KVK|OIN):[0-9]+\$"

private val ONTVANGER_REGEX = Regex(ONTVANGER_PATTERN)

private val log: Logger = Logger.getLogger("nl.rijksoverheid.moz.fbs.berichtenuitvraag.uitvraag.Ontvanger")

/** Named-type-vorm van `X-Ontvanger` (`type`/`waarde`) — geen `.first`/`.second`-verwarring op call-sites. */
internal data class OntvangerHeader(val type: String, val waarde: String)

/**
 * Splitst `X-Ontvanger` naar [OntvangerHeader], of `null` als de waarde niet aan
 * [ONTVANGER_PATTERN] voldoet. Pure functie zonder LDV-zijeffect zodat de format-
 * invariant — die de AVG art. 30-audittrail voedt — los testbaar is.
 */
internal fun splitOntvanger(xOntvanger: String): OntvangerHeader? {
    if (!ONTVANGER_REGEX.matches(xOntvanger)) return null

    val delen = xOntvanger.split(':', limit = 2)

    return OntvangerHeader(delen[0], delen[1])
}

/**
 * Registreert `type` en `waarde` uit `X-Ontvanger` als dataSubject op de LDV-context
 * van de huidige request. Aangeroepen ná Bean Validation (`@Pattern`), dus een
 * parse-fout hier is een invariant-breuk en faalt luid.
 */
internal fun registreerLdvSubject(logboekContext: LogboekContext, xOntvanger: String) {
    val parsed = splitOntvanger(xOntvanger)

    if (parsed == null) {
        log.errorf("X-Ontvanger voldoet niet aan het verwachte formaat (Type:waarde) bij LDV-registratie ná validatie (invariant-breuk)")

        // Faal luid (500) i.p.v. `return`: anders ontstaat een dataSubject-loos LDV-record onder een 2xx — het stille AVG art. 30-audittrail-gat dat we willen voorkomen.
        error("X-Ontvanger ongeldig bij LDV-registratie ná validatie; dataSubject niet gezet")
    }

    logboekContext.dataSubjectType = parsed.type
    logboekContext.dataSubjectId = parsed.waarde
}
