package nl.rijksoverheid.moz.fbs.berichtenmagazijn.opslag

import nl.rijksoverheid.moz.fbs.common.exception.requireValid
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
    // Metadata van bijlagen bij het bericht. Bytes worden separaat opgehaald via
    // de bijlage-repository; in de berichtenlijst is alleen metadata zichtbaar.
    val bijlagen: List<BijlageMetadata> = emptyList(),
    // Leesstatus voor de ontvanger die het bericht opvraagt. `null` als de
    // ontvanger het bericht nog niet heeft aangeraakt.
    val status: BerichtStatus? = null,
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
        // Vergelijk op het hele Identificatienummer-object, niet op `waarde`:
        // BSN en RSIN delen lengte, maar zijn distinct via hun type.
        requireValid(afzender != ontvanger) {
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
