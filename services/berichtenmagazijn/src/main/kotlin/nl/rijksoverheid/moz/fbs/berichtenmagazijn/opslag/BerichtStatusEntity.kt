package nl.rijksoverheid.moz.fbs.berichtenmagazijn.opslag

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.FetchType
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.JoinColumn
import jakarta.persistence.ManyToOne
import jakarta.persistence.Table
import java.time.Instant

/**
 * Leesstatus van een bericht. In de PoC heeft elk bericht hooguit één ontvanger;
 * de bericht-rij draagt al de ontvanger-identiteit, dus de status hangt enkel
 * aan het bericht. Afwezigheid van een rij betekent "nog niet bekeken, geen
 * map gekozen"; bij de eerste PATCH wordt de rij aangemaakt.
 *
 * Surrogate `id` als PK; de relatie naar [BerichtEntity] loopt via
 * `bericht_db_id` (FK op `berichten.id`) met een unique-constraint zodat een
 * bericht hooguit één status-rij heeft.
 */
@Entity
@Table(name = "bericht_status")
internal class BerichtStatusEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(nullable = false)
    var id: Long = 0

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "bericht_db_id", nullable = false, unique = true)
    lateinit var bericht: BerichtEntity

    @Column(nullable = false)
    var gelezen: Boolean = false

    @Column(length = 64)
    var map: String? = null

    @Column(name = "gewijzigd_op", nullable = false)
    var gewijzigdOp: Instant = Instant.EPOCH
}
