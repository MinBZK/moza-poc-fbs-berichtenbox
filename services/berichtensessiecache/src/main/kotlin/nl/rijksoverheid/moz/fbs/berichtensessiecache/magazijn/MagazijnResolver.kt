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
 * Foutpaden: 200/404 zonder voorkeur leidt tot een lege set (caller toont lege
 * resultaten). 5xx/timeout/malformed leidt tot een [ProfielServiceFoutException]
 * (caller propageert 503 + Retry-After).
 */
interface MagazijnResolver {
    fun resolve(ontvanger: Identificatienummer): Uni<Set<String>>
}
