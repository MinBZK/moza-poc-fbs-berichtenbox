package nl.rijksoverheid.moz.fbs.berichtenmagazijn.opslag

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.util.UUID

/**
 * JPA-persistentie van een bijlage bij een bericht.
 *
 * `berichtId` verwijst naar de bedrijfs-identifier (UUID) van het bericht — niet
 * naar de surrogate PK — zodat de bijlage-repository met dezelfde sleutel kan
 * zoeken die de API exposeert. De FK in de DB zorgt voor cascade-delete als
 * een bericht ooit hard verwijderd wordt; soft-delete op `berichten.verwijderd_op`
 * raakt bijlagen niet aan.
 *
 * `content` is een onverpakte byte-array (PostgreSQL `BYTEA`). Voor de PoC houden
 * we de bijlage in dezelfde transactie als het bericht; bij grote bijlagen of
 * hoge volumes zou een aparte object-store (S3/Swift) passender zijn.
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

    @Column(name = "bericht_id", nullable = false)
    var berichtId: UUID = PLACEHOLDER_UUID

    @Column(nullable = false, length = 255)
    var naam: String = ""

    @Column(name = "mime_type", nullable = false, length = 127)
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
