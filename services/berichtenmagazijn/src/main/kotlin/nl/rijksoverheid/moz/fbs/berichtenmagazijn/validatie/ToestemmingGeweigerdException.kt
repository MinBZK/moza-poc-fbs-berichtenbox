package nl.rijksoverheid.moz.fbs.berichtenmagazijn.validatie

import nl.rijksoverheid.moz.fbs.berichtenmagazijn.opslag.Oin

/**
 * Gegooid wanneer de Profiel Service expliciet `toegestaan=false` retourneert
 * voor de combinatie van afzender en ontvanger. Wordt door
 * [ToestemmingGeweigerdExceptionMapper] vertaald naar HTTP 403 Problem JSON.
 *
 * Geen subklasse van [DomainValidationException]: dit is geen domein-invariant
 * (zou altijd waar moeten zijn) maar een policy-besluit van een externe partij,
 * waarbij 403 — niet 400 — de juiste status is.
 *
 * Draagt naast de client-boodschap een korte [reden] zonder identificatienummer en
 * de [afzender] (eigen OIN van de aanleveraar). De afzender-OIN is een publiek
 * organisatienummer en mag voluit in zowel de response-`detail` als de log; de
 * ontvanger (mogelijk een BSN) blijft bewust buiten beide.
 */
class ToestemmingGeweigerdException private constructor(
    message: String,
    val reden: String,
    val afzender: Oin,
) : RuntimeException(message) {

    companion object {
        fun geenProfiel(afzender: Oin): ToestemmingGeweigerdException =
            ToestemmingGeweigerdException(
                message = "Ontvanger heeft geen profiel bij MOZA voor afzender ${afzender.waarde}",
                reden = "ontvanger heeft geen profiel",
                afzender = afzender,
            )

        fun geenActieveVoorkeur(afzender: Oin): ToestemmingGeweigerdException =
            ToestemmingGeweigerdException(
                message = "Ontvanger heeft geen actieve berichtenbox-voorkeur voor afzender ${afzender.waarde}",
                reden = "geen actieve berichtenbox-voorkeur",
                afzender = afzender,
            )
    }
}
