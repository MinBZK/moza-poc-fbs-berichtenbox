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
import java.util.UUID

/**
 * JPA-persistentie van een bijlage bij een bericht.
 *
 * De relatie naar [BerichtEntity] loopt via `bericht_db_id`, een FK op de
 * surrogate PK `berichten.id`. Zo blijft de child-tabel onafhankelijk van de
 * business-identifier `bericht_id`. De FK forceert verwijzingsintegriteit
 * met RESTRICT (geen cascade — zie CLAUDE.md "Database & migraties"):
 * een hard-delete op `berichten` faalt als er nog bijlagen bestaan.
 * Soft-delete op `berichten.verwijderd_op` laat bijlagen ongemoeid; queries
 * filteren expliciet op `bericht.verwijderdOp IS NULL`.
 *
 * `content` is een onverpakte byte-array (PostgreSQL `BYTEA`). De bijlage
 * wordt in dezelfde transactie als het bericht opgeslagen. Bij grote bijlagen
 * of hoge volumes verdient een aparte object-store (S3/Swift/Azure Blob) de
 * voorkeur: relationele DB's zijn niet geoptimaliseerd voor blobs >25 MiB,
 * en backups zwellen onnodig op. Dat is een vervolgstap zodra het gebruik en
 * de retentie-eisen helder zijn.
 */
@Entity
@Table(name = "bijlagen")
internal class BijlageEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(nullable = false)
    var id: Long = 0

    @Column(name = "bijlage_id", nullable = false, unique = true)
    var bijlageId: UUID = PLACEHOLDER_UUID

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "bericht_db_id", nullable = false)
    lateinit var bericht: BerichtEntity

    @Column(nullable = false, length = Bijlage.MAX_NAAM_LENGTE)
    var naam: String = ""

    @Column(name = "mime_type", nullable = false, length = Bijlage.MAX_MIME_LENGTE)
    var mimeType: String = ""

    // Geen @Lob: op PostgreSQL mapt Hibernate 6 `@Lob byte[]` naar `oid`
    // (Large Object), terwijl V2__ophaal_beheer.sql `BYTEA` declareert.
    // De default mapping voor `byte[]` is VARBINARY → BYTEA — wat hier
    // gewenst is.
    @Column(nullable = false)
    var content: ByteArray = EMPTY_BYTES

    companion object {
        private val PLACEHOLDER_UUID: UUID = UUID(0L, 0L)
        private val EMPTY_BYTES: ByteArray = ByteArray(0)
    }
}
