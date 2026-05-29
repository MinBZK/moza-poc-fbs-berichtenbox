package nl.rijksoverheid.moz.fbs.berichtenuitvraag.uitvraag

import nl.rijksoverheid.moz.fbs.berichtenuitvraag.api.model.BerichtPatch
import nl.rijksoverheid.moz.fbs.berichtenuitvraag.api.model.BerichtStatus

/**
 * Mapt het uitvraag-`BerichtPatch` naar het magazijn-patch-formaat. Op één punt
 * zijn de vormen niet identiek: magazijn modelleert `gelezen` als boolean,
 * uitvraag/sessiecache als enum `gelezen|ongelezen`. De overige patch-velden
 * (`map`) delen dezelfde naam, zodat geen veld-hernoeming nodig is.
 *
 * Lees-responses worden niet hier gemapt: die komen 1-op-1 uit de sessiecache.
 * Let op dat `BijlageMetadata.mimeType`/`grootteInBytes` daardoor leeg blijven —
 * de sessiecache bewaart per bijlage alleen `bijlageId` en `naam`.
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
