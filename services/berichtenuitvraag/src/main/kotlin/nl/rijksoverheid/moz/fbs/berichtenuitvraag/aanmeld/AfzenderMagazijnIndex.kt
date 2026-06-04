package nl.rijksoverheid.moz.fbs.berichtenuitvraag.aanmeld

import jakarta.enterprise.context.ApplicationScoped
import nl.rijksoverheid.moz.fbs.berichtenuitvraag.uitvraag.MagazijnenConfig
import nl.rijksoverheid.moz.fbs.common.identificatie.Oin
import org.jboss.logging.Logger

/**
 * Leidt uit een afzender-OIN het bron-magazijn-id af, op basis van de
 * `magazijnen.instances.<id>.afzenders`-config. Spiegelt de afzender→magazijn-
 * routing die de sessiecache-library hanteert (`MagazijnClientFactory.
 * magazijnenVoorAfzender`): bewust een eigen, kleine reverse-index tegen dezelfde
 * property-conventie (de `afzenders`-sleutels zijn gedeeld, niet de code), zodat een
 * aanmeld-geschreven cache-entry hetzelfde `magazijnId` krijgt en PATCH/DELETE/
 * bijlage-routering blijft werken.
 *
 * De reverse-index wordt één keer bij constructie opgebouwd (config is statisch
 * gedurende de applicatie-levensduur).
 */
@ApplicationScoped
class AfzenderMagazijnIndex(config: MagazijnenConfig) {

    private val log = Logger.getLogger(AfzenderMagazijnIndex::class.java)

    // afzender-OIN → gesorteerde magazijn-id's. Sortering maakt de keuze bij een
    // afzender die (per config) bij meerdere magazijnen hoort deterministisch.
    private val afzenderNaarMagazijnen: Map<String, List<String>> =
        buildMap<String, MutableSet<String>> {
            config.instances().forEach { (magazijnId, instance) ->
                instance.afzenders().forEach { afzender ->
                    getOrPut(afzender) { sortedSetOf() }.add(magazijnId)
                }
            }
        }.mapValues { (_, magazijnen) -> magazijnen.toList() }

    /**
     * Magazijn-id voor [afzender], of `null` als geen enkel magazijn die afzender
     * serveert (onbekende bron / config-drift). Bij meerdere matches wint het eerste
     * gesorteerde id; dat is een config-randgeval (in de praktijk is afzender→magazijn
     * 1-op-1) en wordt gelogd zodat de ambiguïteit zichtbaar is.
     */
    fun magazijnVoor(afzender: Oin): String? {
        val magazijnen = afzenderNaarMagazijnen[afzender.waarde] ?: return null

        if (magazijnen.size > 1) {
            log.warnf(
                "Afzender-OIN '%s' is aan meerdere magazijnen gekoppeld %s; kies '%s'. Controleer magazijnen.instances.*.afzenders",
                afzender.waarde,
                magazijnen,
                magazijnen.first(),
            )
        }

        return magazijnen.first()
    }
}
