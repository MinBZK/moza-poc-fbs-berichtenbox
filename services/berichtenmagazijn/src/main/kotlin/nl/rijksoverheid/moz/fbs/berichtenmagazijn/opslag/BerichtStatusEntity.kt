package nl.rijksoverheid.moz.fbs.berichtenmagazijn.opslag

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.Instant
import java.util.UUID

/**
 * Leesstatus van een bericht. In de PoC heeft elk bericht hooguit één ontvanger;
 * de bericht-rij draagt al de ontvanger-identiteit, dus de status hangt enkel
 * aan `bericht_id`. Afwezigheid van een rij betekent "nog niet bekeken, geen
 * map gekozen"; bij de eerste PATCH wordt de rij aangemaakt.
 */
@Entity
@Table(name = "bericht_status")
internal class BerichtStatusEntity {

    @Id
    @Column(name = "bericht_id", nullable = false)
    var berichtId: UUID = PLACEHOLDER_UUID

    @Column(nullable = false)
    var gelezen: Boolean = false

    @Column(length = 64)
    var map: String? = null

    @Column(name = "gewijzigd_op", nullable = false)
    var gewijzigdOp: Instant = Instant.EPOCH

    companion object {
        private val PLACEHOLDER_UUID: UUID = UUID(0L, 0L)
    }
}
