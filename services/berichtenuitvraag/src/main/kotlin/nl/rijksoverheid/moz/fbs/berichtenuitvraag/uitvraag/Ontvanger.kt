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
 * Ontleed `X-Ontvanger` in zijn `type` (BSN/RSIN/KVK/OIN) en `waarde`. Named type
 * i.p.v. een positionele `Pair` zodat call-sites `.type`/`.waarde` lezen — geen
 * `.first`/`.second`-voetkanon. Vormt de seam waar #414 een getypeerde, elfproef-
 * gevalideerde `Ontvanger` (uit het token) in de plaats kan zetten.
 */
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
 * Registreert `type` en `waarde` uit `X-Ontvanger` in de LDV-context voor de
 * huidige request. Vooraf gevalideerd door Bean Validation op het resource-
 * parameter; een waarde zonder `:` betekent dat de validator faalde of werd
 * omzeild — een invariant-breuk. Dan géén audittrail-record onder een geslaagde
 * (2xx) response laten ontstaan: faal luid zodat het AVG art. 30-gat niet stil
 * doorgaat.
 */
internal fun registreerLdvSubject(logboekContext: LogboekContext, xOntvanger: String) {
    val parsed = splitOntvanger(xOntvanger)

    if (parsed == null) {
        // De waarde is op dit punt al door dezelfde regex (@Pattern op het endpoint)
        // gevalideerd; een null betekent dus regex-divergentie of een validator-bypass —
        // een invariant-breuk, geen normale gebruikersfout. errorf (niet warnf) zodat de
        // breuk alertbaar is i.p.v. tussen warnings te verdwijnen. Waarde niet loggen (PII).
        log.errorf("X-Ontvanger voldoet niet aan het verwachte formaat (Type:waarde) bij LDV-registratie ná validatie (invariant-breuk)")

        // Faal luid (500 via UncaughtExceptionMapper) i.p.v. `return`: een `return` zou de
        // request laten doorlopen en een dataSubject-loos LDV-record onder een 2xx schrijven —
        // exact het stille AVG art. 30-audittrail-gat dat we willen voorkomen. Consistent met
        // het `error(...)`-patroon in [BerichtBeheerService.herverpakCache4xx].
        error("X-Ontvanger ongeldig bij LDV-registratie ná validatie; dataSubject niet gezet")
    }

    logboekContext.dataSubjectType = parsed.type
    logboekContext.dataSubjectId = parsed.waarde
}
