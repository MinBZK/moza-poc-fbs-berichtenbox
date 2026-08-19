package nl.rijksoverheid.moz.fbs.common.fsc

/**
 * De naam van de Quarkus-TLS-configuratie voor uitgaand verkeer door de eigen FSC-outway.
 *
 * De outway serveert zijn poort met een certificaat uit de interne PKI van de peer; die CA
 * zit niet in de JVM-default trust-store. Wie de outway over https aanroept moet dus een
 * expliciet trust anchor meegeven — het certificaat dat je bij voorbaat vertrouwt, waar de
 * verificatie van de keten ophoudt. Dat gebeurt door onder deze naam een trust-store te
 * configureren:
 *
 * ```
 * QUARKUS_TLS_OUTWAY_TRUST_STORE_PEM_CERTS=/etc/fsc/internal/logius/ca/root.pem
 * ```
 *
 * Alle drie de consumenten hangen aan dezelfde outway en dus aan hetzelfde anchor: de
 * sessiecache bouwt de magazijn-clients, de uitvraag routeert per bericht, en de
 * profiel-service-client komt er via `quarkus.rest-client.profiel-service.tls-configuration-name`
 * op uit. De eerste twee lezen deze constante; de derde is declaratief en krijgt de naam per
 * omgeving als env-var mee. Vandaar de plek in `fbs-common`: dit is FSC-transportkennis, en het
 * is de enige module die alle drie zien.
 *
 * **Aanwezigheid is de schakelaar.** Is er geen configuratie met deze naam, dan valt het
 * verkeer terug op de JVM-default trust-store: precies het gedrag van vóór deze knop, en het
 * juiste gedrag voor een outway die via een publiek vertrouwde ingress bereikt wordt.
 *
 * Een named configuratie **vervangt** de default trust-store en vult die niet aan. Daarom
 * krijgt alleen verkeer dat daadwerkelijk door de outway loopt dit anchor mee — een magazijn
 * zonder grant-hash wordt rechtstreeks met een publiek certificaat gebeld en zou tegen deze CA
 * juist stukvallen. [OutwayTlsValidator] maakt bij boot zichtbaar welke modus geldt, zodat een
 * anchor onder een verkeerd gespelde naam niet als "geen anchor" wegvalt.
 */
object OutwayTls {
    const val CONFIG_NAAM = "outway"
}
