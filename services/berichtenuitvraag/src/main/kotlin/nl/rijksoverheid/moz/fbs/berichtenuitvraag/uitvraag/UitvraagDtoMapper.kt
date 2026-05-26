package nl.rijksoverheid.moz.fbs.berichtenuitvraag.uitvraag

import nl.rijksoverheid.moz.fbs.berichtenuitvraag.api.model.BerichtPatch
import nl.rijksoverheid.moz.fbs.berichtenuitvraag.api.model.BerichtStatus

/**
 * Mapt tussen de uitvraag-API-DTO's en de DTO's van downstream-services. Op
 * één punt zijn de vormen niet identiek: magazijn modelleert `gelezen` als
 * boolean, uitvraag/sessiecache als enum `gelezen|ongelezen`. Alle andere
 * velden zijn naam-gealigneerd (`berichtId`, `tijdstipOntvangst`,
 * `grootteInBytes`).
 */
object UitvraagDtoMapper {

    data class MagazijnPatch(val gelezen: Boolean?, val map: String?)

    fun toMagazijnPatch(patch: BerichtPatch): MagazijnPatch =
        MagazijnPatch(
            gelezen = when (patch.status) {
                BerichtStatus.GELEZEN -> true
                BerichtStatus.ONGELEZEN -> false
                null -> null
            },
            map = patch.map,
        )
}
