package nl.rijksoverheid.moz.fbs.magazijnregister

/**
 * De naam van de Quarkus-TLS-configuratie voor uitgaand verkeer door de eigen FSC-outway.
 *
 * De outway serveert zijn poort met een certificaat uit de interne PKI van de peer; die CA
 * zit niet in de JVM-default trust-store. Wie de outway over https aanroept moet dus een
 * expliciet anker meegeven, en dat gebeurt door onder deze naam een trust-store te
 * configureren:
 *
 * ```
 * QUARKUS_TLS_OUTWAY_TRUST_STORE_PEM_CERTS=/etc/fsc/internal/logius/ca/root.pem
 * ```
 *
 * Eén naam voor magazijn-verkeer én de profiel-service: beide lopen door dezelfde outway en
 * hangen dus aan hetzelfde anker. De constante staat hier omdat twee modules 'm gebruiken —
 * de sessiecache bouwt de magazijn-clients, de uitvraag routeert per bericht — en een los
 * gekopieerde string in beide zou stilzwijgend uiteen kunnen lopen.
 *
 * **Aanwezigheid is de schakelaar.** Is er geen configuratie met deze naam, dan valt het
 * verkeer terug op de JVM-default trust-store: precies het gedrag van vóór deze knop, en het
 * juiste gedrag voor een outway die via een publiek vertrouwde ingress bereikt wordt.
 *
 * Let op dat het anker de default trust-store **vervangt** en niet aanvult: bestaat de
 * configuratie, dan valideert élk magazijn-endpoint tegen deze CA, ook een endpoint met een
 * publiek certificaat. Configureer 'm dus samen met de adressen die erdoor gedekt worden.
 */
object OutwayTls {
    const val CONFIG_NAAM = "outway"
}
