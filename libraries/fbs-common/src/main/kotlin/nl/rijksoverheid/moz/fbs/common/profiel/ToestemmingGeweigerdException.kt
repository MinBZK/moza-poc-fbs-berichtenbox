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
 */
class ToestemmingGeweigerdException(message: String) : RuntimeException(message)
