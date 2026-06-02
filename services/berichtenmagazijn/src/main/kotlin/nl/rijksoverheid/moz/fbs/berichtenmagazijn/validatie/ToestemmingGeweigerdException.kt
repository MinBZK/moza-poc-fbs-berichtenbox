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
 * de betrokken [afzender]. Daarmee logt de mapper een gemaskeerd afzender-prefix,
 * zodat operations kan aggregeren wélke afzender vaak geweigerd wordt zonder dat de
 * volledige OIN per logregel zichtbaar is. De afzender is de aanleverende organisatie
 * zelf en mag in de response-`detail` staan; de ontvanger (mogelijk een BSN) blijft
 * bewust buiten zowel boodschap als log.
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
