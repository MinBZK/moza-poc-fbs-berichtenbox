package nl.rijksoverheid.moz.fbs.magazijnsimulator.magazijn

import nl.rijksoverheid.moz.fbs.magazijnsimulator.gedrag.Gedrag

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
 *
 * [gedrag] is de momentopname bij het begin van de request. Wordt het gedrag tijdens een demo
 * bijgesteld, dan geldt dat vanaf de volgende aanroep — een lopende aanroep halverwege van karakter
 * laten veranderen zou alleen maar verwarren.
 */
data class GesimuleerdMagazijn(
    val dbId: Long,
    val oin: String,
    val naam: String,
    val gedrag: Gedrag = Gedrag.NORMAAL,
)
