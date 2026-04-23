package nl.rijksoverheid.moz.berichtenmagazijn.opslag

import nl.rijksoverheid.moz.fbs.common.requireValid
import java.time.Instant
import java.util.UUID

/**
 * Domeinmodel voor een opgeslagen bericht.
 * Gescheiden van [BerichtEntity] (JPA) om domeinlogica onafhankelijk te houden van persistentie.
 *
 * Afzender is altijd een [Oin] (organisatie). Ontvanger kan elk [Identificatienummer]-
 * type zijn (BSN, RSIN, KvK of OIN). Typen zijn value classes die hun eigen invarianten
 * afdwingen bij constructie — deze data class hoeft die dus niet te herhalen.
 */
data class Bericht(
    val berichtId: UUID,
    val afzender: Oin,
    val ontvanger: Identificatienummer,
    val onderwerp: String,
    val inhoud: String,
    val tijdstipOntvangst: Instant,
) {
    init {
        requireValid(onderwerp.isNotBlank()) { "onderwerp mag niet leeg zijn" }
        requireValid(onderwerp.length <= MAX_ONDERWERP_LENGTE) {
            "onderwerp mag max $MAX_ONDERWERP_LENGTE characters zijn"
        }
        requireValid(inhoud.isNotBlank()) { "inhoud mag niet leeg zijn" }
        requireValid(inhoud.length <= MAX_INHOUD_LENGTE) {
            "inhoud mag max $MAX_INHOUD_LENGTE characters zijn"
        }
        requireValid(afzender.waarde != ontvanger.waarde) {
            "afzender en ontvanger mogen niet hetzelfde identificatienummer hebben"
        }
    }

    companion object {
        const val MAX_ONDERWERP_LENGTE = 255
        const val MAX_INHOUD_LENGTE = 1_048_576 // 1 MiB, synchroon met OpenAPI spec
    }
}
