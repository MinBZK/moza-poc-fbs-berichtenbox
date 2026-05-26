package nl.rijksoverheid.moz.fbs.common.profiel

/**
 * Gegooid wanneer de Profiel Service expliciet `toegestaan=false` retourneert
 * voor de combinatie van afzender en ontvanger. Wordt door
 * [ToestemmingGeweigerdExceptionMapper] vertaald naar HTTP 403 Problem JSON.
 *
 * Geen subklasse van DomainValidationException: dit is geen domein-invariant
 * (zou altijd waar moeten zijn) maar een policy-besluit van een externe partij,
 * waarbij 403 — niet 400 — de juiste status is.
 */
class ToestemmingGeweigerdException(message: String) : RuntimeException(message)
