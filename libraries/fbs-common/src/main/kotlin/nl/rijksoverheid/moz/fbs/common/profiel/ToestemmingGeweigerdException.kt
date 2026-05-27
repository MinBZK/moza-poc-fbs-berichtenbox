package nl.rijksoverheid.moz.fbs.common.profiel

/**
 * Gegooid wanneer er geen actieve `OntvangViaBerichtenbox`-voorkeur bij de Profiel
 * Service bestaat voor de combinatie afzender-OIN ↔ ontvanger (inclusief het
 * 404-pad: ontvanger heeft geen profiel). Wordt door
 * [ToestemmingGeweigerdExceptionMapper] vertaald naar HTTP 403 Problem JSON.
 *
 * Geen subklasse van DomainValidationException: dit is geen domein-invariant
 * (zou altijd waar moeten zijn) maar een policy-besluit van een externe partij,
 * waarbij 403 — niet 400 — de juiste status is.
 *
 * Constructor is `private` om factory-bypass te voorkomen: alle messages zijn
 * statisch zodat de mapper geen call-site-injecties als Problem.detail terug-
 * stuurt naar de client. De [reden]-property geeft een afzenders-context aan
 * de log-pad zonder dat dit in de response-body lekt.
 */
class ToestemmingGeweigerdException private constructor(
    message: String,
    val reden: Reden,
) : RuntimeException(message) {

    enum class Reden {
        /** 404 op Profiel-call: ontvanger heeft geen profiel bij MOZA. */
        GEEN_PROFIEL,
        /** Profiel bestaat, maar geen actieve OntvangViaBerichtenbox-voorkeur voor de afzender. */
        GEEN_ACTIEVE_VOORKEUR,
    }

    companion object {
        fun geenProfiel() = ToestemmingGeweigerdException(
            "Ontvanger heeft geen profiel bij MOZA.",
            Reden.GEEN_PROFIEL,
        )

        fun geenActieveVoorkeur() = ToestemmingGeweigerdException(
            "Ontvanger heeft geen actieve berichtenbox-voorkeur voor deze afzender.",
            Reden.GEEN_ACTIEVE_VOORKEUR,
        )
    }
}
