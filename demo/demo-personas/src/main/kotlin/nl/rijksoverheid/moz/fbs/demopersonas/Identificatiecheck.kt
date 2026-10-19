package nl.rijksoverheid.moz.fbs.demopersonas

import nl.rijksoverheid.moz.fbs.kern.identificatie.Identificatienummer
import nl.rijksoverheid.moz.fbs.kern.identificatie.IdentificatienummerType

/**
 * Toetst het nummer van een persona met dezelfde regels als het stelsel, zodat het magazijn straks
 * geen 400 geeft midden in een demo. Fail-fast bij een typfout in de persona-lijst.
 *
 * Wat hier zelf staat is alleen welke types een persona mag hebben: een ondernemer is een persoon
 * of een onderneming, geen organisatie met een OIN. Het type wordt via `firstOrNull` opgezocht en
 * niet via `valueOf`, want die laatste noemt de aangeboden tekst in zijn melding — en wie `type` en
 * `waarde` verwisselt, zou zo zijn identificatienummer in de opstartlog terugvinden.
 */
object Identificatiecheck {

    private val TOEGESTAAN = listOf(IdentificatienummerType.BSN, IdentificatienummerType.RSIN, IdentificatienummerType.KVK)

    fun valideer(type: String, waarde: String) {
        val bekend = TOEGESTAAN.firstOrNull { it.name == type }

        requireNotNull(bekend) { "onbekend ontvanger-type; toegestaan: ${TOEGESTAAN.joinToString()}" }

        Identificatienummer.of(bekend, waarde)
    }
}
