package nl.rijksoverheid.moz.berichtenlijst.berichten

import java.time.Instant
import java.util.UUID

data class Bericht(
    val berichtId: UUID,
    val afzender: String,
    val ontvanger: String,
    val onderwerp: String,
    val tijdstip: Instant,
    val magazijnId: String,
)
