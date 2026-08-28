package nl.rijksoverheid.moz.fbs.magazijnsimulator.opslag

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.FetchType
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.JoinColumn
import jakarta.persistence.ManyToOne
import jakarta.persistence.Table
import java.time.Instant
import java.util.UUID

/**
 * De JPA-mapping van het schema uit `V1__init.sql`. Alle vier de entities staan in één bestand
 * omdat ze samen één tabelgroep zijn en apart nooit betekenis hebben; ze zijn `internal` zodat
 * alleen de repositories in dit package ermee werken en niemand de invarianten van [Bericht]
 * langs de zijkant kan omzeilen.
 *
 * Velden hebben default-waardes in plaats van `lateinit`, zodat Hibernate via de no-arg
 * constructor kan hydrateren zonder een venster waarin een leesactie op een niet-geïnitialiseerd
 * veld knalt.
 */
@Entity
@Table(name = "magazijn")
internal class MagazijnEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(nullable = false)
    var id: Long = 0

    @Column(nullable = false, length = 20, unique = true)
    var oin: String = ""

    @Column(nullable = false, length = 255)
    var naam: String = ""
}

@Entity
@Table(name = "bericht")
internal class BerichtEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(nullable = false)
    var id: Long = 0

    // De discriminator. Elke query in dit package filtert hierop; er hoort er geen te bestaan die
    // dat niet doet, want dan lekt het ene gesimuleerde magazijn in het andere.
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "magazijn_db_id", nullable = false)
    var magazijn: MagazijnEntity = MagazijnEntity()

    @Column(name = "bericht_id", nullable = false)
    var berichtId: UUID = LEEG_UUID

    @Column(nullable = false, length = 20)
    var afzender: String = ""

    // Als enum-naam en niet als ordinaal: leesbaar in de database, en een herordening van de enum
    // verandert de opgeslagen betekenis dan niet stilzwijgend.
    @Column(name = "ontvanger_type", nullable = false, length = 8)
    @Enumerated(EnumType.STRING)
    var ontvangerType: IdentificatieType = IdentificatieType.OIN

    @Column(name = "ontvanger_waarde", nullable = false, length = 20)
    var ontvangerWaarde: String = ""

    @Column(nullable = false, length = 255)
    var onderwerp: String = ""

    @Column(nullable = false, columnDefinition = "TEXT")
    var inhoud: String = ""

    @Column(name = "tijdstip_ontvangst", nullable = false)
    var tijdstipOntvangst: Instant = Instant.EPOCH

    @Column(name = "publicatietijdstip", nullable = false)
    var publicatietijdstip: Instant = Instant.EPOCH

    @Column(name = "verwijderd_op")
    var verwijderdOp: Instant? = null
}

@Entity
@Table(name = "bericht_status")
internal class BerichtStatusEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(nullable = false)
    var id: Long = 0

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "bericht_db_id", nullable = false, unique = true)
    var bericht: BerichtEntity = BerichtEntity()

    @Column(nullable = false)
    var gelezen: Boolean = false

    @Column(length = BerichtStatus.MAX_MAPNAAM_LENGTE)
    var map: String? = null

    @Column(name = "gewijzigd_op", nullable = false)
    var gewijzigdOp: Instant = Instant.EPOCH
}

@Entity
@Table(name = "bijlage")
internal class BijlageEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(nullable = false)
    var id: Long = 0

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "bericht_db_id", nullable = false)
    var bericht: BerichtEntity = BerichtEntity()

    @Column(name = "bijlage_id", nullable = false)
    var bijlageId: UUID = LEEG_UUID

    @Column(nullable = false, length = 255)
    var naam: String = ""

    @Column(name = "mime_type", nullable = false, length = 127)
    var mimeType: String = ""

    // Géén @Lob: op PostgreSQL mapt Hibernate 6 dat naar `oid` (Large Object), terwijl V1 `BYTEA`
    // declareert. De default-mapping van een kaal `byte[]` levert BYTEA, en dat is wat we willen.
    @Column(nullable = false)
    var inhoud: ByteArray = LEGE_BYTES
}

private val LEEG_UUID: UUID = UUID(0L, 0L)
private val LEGE_BYTES: ByteArray = ByteArray(0)
