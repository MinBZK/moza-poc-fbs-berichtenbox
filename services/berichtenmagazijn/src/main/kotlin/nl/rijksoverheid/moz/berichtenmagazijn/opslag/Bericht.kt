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
        requireValid(onderwerp.isNotBlank()) { "Onderwerp mag niet leeg zijn" }
        requireValid(onderwerp.length <= MAX_ONDERWERP_LENGTE) {
            "Onderwerp mag max $MAX_ONDERWERP_LENGTE characters zijn"
        }
        requireValid(inhoud.isNotBlank()) { "Inhoud mag niet leeg zijn" }
        val inhoudBytes = inhoud.toByteArray(Charsets.UTF_8).size
        requireValid(inhoudBytes <= MAX_INHOUD_BYTES) {
            "Inhoud mag max ${MAX_INHOUD_BYTES / 1024 / 1024} MiB UTF-8 zijn (kreeg $inhoudBytes bytes)"
        }
        requireValid(afzender.waarde != ontvanger.waarde) {
            "Afzender en ontvanger mogen niet hetzelfde identificatienummer hebben"
        }
    }

    companion object {
        const val MAX_ONDERWERP_LENGTE = 255

        /** Max inhoudgrootte in UTF-8 bytes (1 MiB). Bytes, niet characters,
         *  zodat een 4-byte emoji niet 4× meer geheugen kost binnen dezelfde limiet. */
        const val MAX_INHOUD_BYTES = 1_048_576
    }
}
