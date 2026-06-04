package nl.rijksoverheid.moz.fbs.berichtensessiecache.berichten

import io.smallrye.config.ConfigMapping

/**
 * Configureerbare, defensieve grenzen op binnenkomende cache-entries (cache-store
 * en magazijn-mapping). Geen functionele limieten — enkel om pathologische of
 * kwaadaardige input te weren. Validatie wordt afgedwongen door [BerichtValidator]
 * buiten de data classes, zodat de limieten via property-overrides per omgeving
 * scherper of soepeler te zetten zijn zonder de domeintypen te raken.
 */
@ConfigMapping(prefix = "berichtensessiecache.bericht")
internal interface BerichtLimieten {
    fun maxBijlagen(): Int
    fun maxBijlageNaamLengte(): Int
}
