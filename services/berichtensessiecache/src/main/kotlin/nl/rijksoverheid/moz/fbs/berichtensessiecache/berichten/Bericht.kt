package nl.rijksoverheid.moz.fbs.berichtensessiecache.berichten

import java.time.Instant
import java.util.UUID

data class Bericht(
    val berichtId: UUID,
    val afzender: String,
    val ontvanger: String,
    val onderwerp: String,
    val tijdstip: Instant,
    val magazijnId: String,
    val status: String? = null,
) {
    init {
        require(afzender.isNotBlank()) { "afzender mag niet leeg zijn" }
        require(ontvanger.isNotBlank()) { "ontvanger mag niet leeg zijn" }
        require(onderwerp.isNotBlank()) { "onderwerp mag niet leeg zijn" }
        require(magazijnId.isNotBlank()) { "magazijnId mag niet leeg zijn" }
    }
}
