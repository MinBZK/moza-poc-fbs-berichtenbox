package nl.rijksoverheid.moz.fbs.berichtenmagazijn.retention

import java.time.Instant
import java.util.UUID

/**
 * Projectie van een rij uit de retentie-claim-query. Bevat alleen velden die
 * downstream nodig zijn (LDV-velden + surrogate PK voor de delete-cascade);
 * de volledige `BerichtEntity` (incl. tot 1 MiB `inhoud`) wordt bewust niet
 * geladen.
 */
data class HardDeleteCandidaat(
    val id: Long,
    val berichtId: UUID,
    val ontvangerType: String,
    val ontvangerWaarde: String,
    val tijdstipOntvangst: Instant,
    val verwijderdOp: Instant,
)
