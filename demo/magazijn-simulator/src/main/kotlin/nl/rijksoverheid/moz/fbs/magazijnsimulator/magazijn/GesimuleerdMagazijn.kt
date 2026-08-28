package nl.rijksoverheid.moz.fbs.magazijnsimulator.magazijn

/**
 * Eén magazijn dat de simulator voorstelt. De OIN is zowel de identiteit van de afzender als het
 * pad-segment waarop het magazijn bereikbaar is; er bestaat geen tweede sleutel die daarmee uit de
 * pas kan lopen.
 *
 * [dbId] is de discriminator waarop elke query filtert. Hij reist mee in de request-context zodat
 * geen enkele query hem eerst hoeft op te zoeken.
 *
 * De OIN is een publieke organisatie-identificator en geen persoonsgegeven, dus hij mag voluit in
 * logs en in de HAL-links van het antwoord staan.
 */
data class GesimuleerdMagazijn(val dbId: Long, val oin: String, val naam: String)
