package nl.rijksoverheid.moz.fbs.berichtenuitvraag.uitvraag

import nl.rijksoverheid.moz.fbs.berichtensessiecache.berichten.Leesstatus
import nl.rijksoverheid.moz.fbs.berichtenuitvraag.ApiInfo
import nl.rijksoverheid.moz.fbs.berichtenuitvraag.api.model.Bericht
import nl.rijksoverheid.moz.fbs.berichtenuitvraag.api.model.BerichtLinks
import nl.rijksoverheid.moz.fbs.berichtenuitvraag.api.model.BerichtPatch
import nl.rijksoverheid.moz.fbs.berichtenuitvraag.api.model.BerichtSamenvatting
import nl.rijksoverheid.moz.fbs.berichtenuitvraag.api.model.BerichtStatus
import nl.rijksoverheid.moz.fbs.berichtenuitvraag.api.model.BijlageMetadata
import nl.rijksoverheid.moz.fbs.berichtenuitvraag.api.model.Link
import nl.rijksoverheid.moz.fbs.berichtensessiecache.berichten.Bericht as DomeinBericht
import nl.rijksoverheid.moz.fbs.berichtensessiecache.berichten.BerichtSamenvatting as DomeinSamenvatting
import nl.rijksoverheid.moz.fbs.berichtensessiecache.berichten.BijlageSamenvatting as DomeinBijlage

/**
 * Mapt tussen de uitvraag-API-modellen, het sessiecache-domein en het
 * magazijn-patch-formaat. Twee vorm-verschillen: magazijn modelleert `gelezen`
 * als boolean waar uitvraag/sessiecache een enum gebruiken, en het sessiecache-
 * domein draagt een `ontvanger`-veld dat de uitvraag-API bewust niet exposeert
 * (de client ís de ontvanger).
 *
 * `BijlageMetadata.mimeType`/`grootteInBytes` blijven leeg: de sessiecache
 * bewaart per bijlage alleen `bijlageId` en `naam`; het werkelijke MIME-type
 * komt mee bij het downloaden van de bijlage zelf.
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

    fun toLeesstatus(status: BerichtStatus?): Leesstatus? = when (status) {
        BerichtStatus.GELEZEN -> Leesstatus.GELEZEN
        BerichtStatus.ONGELEZEN -> Leesstatus.ONGELEZEN
        null -> null
    }

    fun toApiStatus(status: Leesstatus?): BerichtStatus? = when (status) {
        Leesstatus.GELEZEN -> BerichtStatus.GELEZEN
        Leesstatus.ONGELEZEN -> BerichtStatus.ONGELEZEN
        null -> null
    }

    fun toApiBericht(bericht: DomeinBericht): Bericht = Bericht().apply {
        berichtId = bericht.berichtId
        onderwerp = bericht.onderwerp
        afzender = bericht.afzender
        inhoud = bericht.inhoud
        publicatietijdstip = bericht.publicatietijdstip
        map = bericht.map
        status = toApiStatus(bericht.status)
        magazijnId = bericht.magazijnId
        bijlagen = bericht.bijlagen.map { toApiBijlage(it) }
        links = berichtLinks(bericht.berichtId)
    }

    fun toApiSamenvatting(samenvatting: DomeinSamenvatting): BerichtSamenvatting = BerichtSamenvatting().apply {
        berichtId = samenvatting.berichtId
        onderwerp = samenvatting.onderwerp
        afzender = samenvatting.afzender
        publicatietijdstip = samenvatting.publicatietijdstip
        aantalBijlagen = samenvatting.aantalBijlagen
        map = samenvatting.map
        status = toApiStatus(samenvatting.status)
        magazijnId = samenvatting.magazijnId
        links = berichtLinks(samenvatting.berichtId)
    }

    private fun toApiBijlage(bijlage: DomeinBijlage): BijlageMetadata = BijlageMetadata().apply {
        bijlageId = bijlage.bijlageId
        naam = bijlage.naam
    }

    private fun berichtLinks(berichtId: java.util.UUID): BerichtLinks = BerichtLinks().apply {
        self = Link().apply { href = "${ApiInfo.BASE_PATH}/berichten/$berichtId" }
    }
}
