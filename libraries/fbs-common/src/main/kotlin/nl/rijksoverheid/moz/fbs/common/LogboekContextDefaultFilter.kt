package nl.rijksoverheid.moz.fbs.common

import io.quarkus.arc.properties.UnlessBuildProperty
import jakarta.annotation.Priority
import jakarta.inject.Inject
import jakarta.ws.rs.Priorities
import jakarta.ws.rs.container.ContainerRequestContext
import jakarta.ws.rs.container.ContainerRequestFilter
import jakarta.ws.rs.ext.Provider
import nl.mijnoverheidzakelijk.ldv.logboekdataverwerking.LogboekContext

/**
 * Priority-anker voor [LogboekContextDefaultFilter]. Andere filters die vóór
 * óf ná de LDV-context-defaults willen draaien gebruiken `LDV_CONTEXT_DEFAULT_PRIORITY ± n`
 * i.p.v. raw arithmetic op `Priorities.AUTHENTICATION` — voorkomt drift tussen
 * filters die om dezelfde "vroege" slot vragen.
 */
const val LDV_CONTEXT_DEFAULT_PRIORITY = Priorities.AUTHENTICATION - 100

/** Build-time-schakelaar waarmee een dienst zonder logboek [LogboekContextDefaultFilter] weglaat. */
const val LOGBOEK_AFWEZIG_KEY = "fbs.logboek.afwezig"

/**
 * Zet safe defaults op LogboekContext vóór resource-code de echte `dataSubjectId` zet.
 * Zonder deze defaults levert een request dat vóór de resource sneuvelt — Bean Validation
 * wijst het af, of de service doet zelf span-management zoals `AanleverResource` — een
 * logregel op met lege betrokkene-velden, die de wrapper als onvolledige context
 * wegschrijft met een waarschuwing.
 *
 * Vroege [LDV_CONTEXT_DEFAULT_PRIORITY] zodat latere filters op een gevulde context rekenen.
 *
 * Een dienst zonder logboek — één die geen persoonsgegevens verwerkt en de wrapper dus niet op
 * zijn classpath heeft — zet `fbs.logboek.afwezig=true`. Zonder die schakelaar eist dit filter een
 * [LogboekContext] die daar niet bestaat en start de dienst niet. Het is een build-time-property:
 * een provider uitsluiten kan alleen bij het bouwen, en `quarkus.arc.exclude-types` haalt wel de
 * bean weg maar niet de registratie als filter.
 */
@UnlessBuildProperty(name = LOGBOEK_AFWEZIG_KEY, stringValue = "true", enableIfMissing = true)
@Provider
@Priority(LDV_CONTEXT_DEFAULT_PRIORITY)
class LogboekContextDefaultFilter : ContainerRequestFilter {

    @Inject
    lateinit var logboekContext: LogboekContext

    override fun filter(requestContext: ContainerRequestContext) {
        logboekContext.dataSubjectId = "unknown"
        logboekContext.dataSubjectType = "system"
    }
}
