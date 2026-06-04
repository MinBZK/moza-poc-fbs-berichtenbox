package nl.rijksoverheid.moz.fbs.berichtensessiecache.magazijn

import io.smallrye.mutiny.Uni
import nl.rijksoverheid.moz.fbs.common.identificatie.Identificatienummer

/**
 * Bepaalt welke magazijnen bevraagd worden voor een specifieke ontvanger.
 *
 * Default-implementatie ([ProfielMagazijnResolver]) raadpleegt de MOZA Profiel
 * Service voor dienstvoorkeuren en kruist die met de afzender-OIN-lijst per
 * geconfigureerd magazijn. OIN-ontvangers (B2B) slaan de Profiel-call over en
 * krijgen alle magazijn-IDs terug.
 *
 * Voor de fout-taxonomie (welke upstream-fault leidt tot welke wrap), zie
 * [nl.rijksoverheid.moz.fbs.common.profiel.ProfielServiceFoutException].
 *
 * `fun interface` zodat tests inline-lambda's kunnen gebruiken zonder anonieme
 * subclass-boilerplate.
 */
internal fun interface MagazijnResolver {
    fun resolve(ontvanger: Identificatienummer): Uni<Set<String>>
}
