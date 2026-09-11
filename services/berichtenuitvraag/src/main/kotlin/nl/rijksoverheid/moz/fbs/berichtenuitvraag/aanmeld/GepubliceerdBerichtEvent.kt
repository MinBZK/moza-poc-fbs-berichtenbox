package nl.rijksoverheid.moz.fbs.berichtenuitvraag.aanmeld

import nl.rijksoverheid.moz.fbs.common.identificatie.Identificatienummer
import nl.rijksoverheid.moz.fbs.common.identificatie.Oin
import java.time.Instant
import java.util.UUID

/**
 * Gevalideerd, getypeerd resultaat van het parsen van een [AangemeldCloudEvent].
 * In tegenstelling tot de ruwe wire-DTO (alles nullable) zijn hier alle velden
 * verplicht en dragen `afzender`/`ontvanger` hun domeintype — illegale staat is
 * onrepresenteerbaar, zodat downstream-code geen null-checks of `!!` meer nodig
 * heeft. Constructie gebeurt uitsluitend via de parse-stap in [AanmeldService].
 *
 * Het event draagt de berichttekst niet; die wordt bij het openen bij het bronmagazijn
 * opgehaald. Een peer die hem nog wél meestuurt, wordt genegeerd door
 * [AangemeldBerichtData]'s `ignoreUnknown`.
 */
internal data class GepubliceerdBerichtEvent(
    val eventId: String,
    val berichtId: UUID,
    val afzender: Oin,
    val ontvanger: Identificatienummer,
    val magazijnId: String,
    val afzenderNaam: String,
    val onderwerp: String,
    val publicatietijdstip: Instant,
)
