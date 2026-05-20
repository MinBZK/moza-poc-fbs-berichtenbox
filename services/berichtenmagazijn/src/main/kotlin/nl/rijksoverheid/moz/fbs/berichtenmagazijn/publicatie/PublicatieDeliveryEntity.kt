package nl.rijksoverheid.moz.fbs.berichtenmagazijn.publicatie

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.Instant
import java.util.UUID

/**
 * JPA-entity voor `publicatie_deliveries`. Internal: alleen de
 * adapter-laag binnen het `publicatie/`-package raakt deze klasse aan.
 *
 * `status` als string-enum opgeslagen: leesbaar in de DB en safe bij
 * toevoegen van nieuwe enum-waarden (ordinaal-opslag zou stiekem breken
 * bij herordening).
 *
 * Mutator-methoden ([markeerGeslaagd], [markeerMislukt]) bewaken de
 * state-machine met `check(...)` zodat een illegal state-overgang
 * (bijv. `MISLUKT → GEPUBLICEERD`) een exceptie geeft i.p.v. de DB-rij
 * stilletjes te overschrijven.
 */
@Entity
@Table(name = "publicatie_deliveries")
internal class PublicatieDeliveryEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(nullable = false)
    var id: Long = 0

    @Column(name = "bericht_id", nullable = false)
    var berichtId: UUID = PLACEHOLDER_UUID
        internal set

    /**
     * Rauw opgeslagen als String i.p.v. via `AttributeConverter<Publicatiedoel,
     * String>`: Kotlin value classes worden door de JVM ge-erased naar hun
     * onderliggende type, waardoor Hibernate de converter een raw String
     * doorgeeft en de cast naar `Publicatiedoel` faalt met `ClassCastException`.
     * `toClaim()` en `nieuwe()` valideren rondom de boundary via
     * `Publicatiedoel(...)`, zodat een corrupte rij alsnog bij read-tijd faalt.
     */
    @Column(nullable = false, length = 64)
    var doel: String = ""
        internal set

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    var status: DeliveryStatus = DeliveryStatus.TE_PUBLICEREN
        internal set

    @Column(nullable = false)
    var pogingen: Int = 0
        internal set

    @Column(name = "volgende_poging", nullable = false)
    var volgendePoging: Instant = Instant.EPOCH
        internal set

    @Column(name = "laatste_fout", columnDefinition = "TEXT")
    var laatsteFout: String? = null
        internal set

    @Column(name = "gepubliceerd_op")
    var gepubliceerdOp: Instant? = null
        internal set

    @Column(name = "aangemaakt_op", nullable = false)
    var aangemaaktOp: Instant = Instant.EPOCH

    fun toClaim(): PublicatieClaim = PublicatieClaim(
        claimId = id,
        berichtId = berichtId,
        doel = Publicatiedoel(doel),
        pogingen = pogingen,
    )

    /**
     * Markeer de delivery als succesvol afgeleverd. Status wordt terminal
     * `GEPUBLICEERD`; `gepubliceerdOp` wordt gezet, `laatsteFout` gewist.
     *
     * Fout bij illegal transition (bv. al `MISLUKT`): de claim-stream zou
     * dan door een race een al-gemarkeerde delivery opnieuw verwerken.
     */
    internal fun markeerGeslaagd(tijdstip: Instant) {
        check(status == DeliveryStatus.TE_PUBLICEREN) {
            "markeerGeslaagd: ongeldige status-overgang vanuit $status (claimId=$id)"
        }
        status = DeliveryStatus.GEPUBLICEERD
        gepubliceerdOp = tijdstip
        laatsteFout = null
    }

    /**
     * Markeer de delivery na een mislukte poging.
     *
     * - [volgendePoging] != null: blijft `TE_PUBLICEREN`, `pogingen++`,
     *   `laatsteFout` opgeslagen, nieuwe `volgendePoging` gezet.
     * - [volgendePoging] == null: status wordt terminal `MISLUKT`.
     */
    internal fun markeerMislukt(fout: String, volgendePoging: Instant?) {
        check(status == DeliveryStatus.TE_PUBLICEREN) {
            "markeerMislukt: ongeldige status-overgang vanuit $status (claimId=$id)"
        }
        pogingen += 1
        laatsteFout = fout.take(MAX_FOUT_LENGTE)
        if (volgendePoging == null) {
            status = DeliveryStatus.MISLUKT
        } else {
            this.volgendePoging = volgendePoging
        }
    }

    companion object {
        private val PLACEHOLDER_UUID: UUID = UUID(0L, 0L)

        // Defense-in-depth dubbel met [FoutBeschrijving.saneer]: die saneert
        // upstream tot 4 KiB; deze entity-grens vangt rauwe inserts (bv. via
        // toekomstige DB-import) op zodat een 1 MiB stack trace de rij niet
        // doet uitgroeien. Beide grenzen zelfde 4 KiB; pas in tandem aan.
        private const val MAX_FOUT_LENGTE = 4_096

        internal fun nieuwe(
            berichtId: UUID,
            doel: Publicatiedoel,
            volgendePoging: Instant,
            aangemaaktOp: Instant,
        ): PublicatieDeliveryEntity = PublicatieDeliveryEntity().apply {
            this.berichtId = berichtId
            this.doel = doel.key
            this.status = DeliveryStatus.TE_PUBLICEREN
            this.pogingen = 0
            this.volgendePoging = volgendePoging
            this.aangemaaktOp = aangemaaktOp
        }
    }
}
