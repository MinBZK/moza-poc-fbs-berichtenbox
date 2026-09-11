package nl.rijksoverheid.moz.fbs.berichtenmagazijn.opslag

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import nl.rijksoverheid.moz.fbs.common.identificatie.Identificatienummer
import nl.rijksoverheid.moz.fbs.common.identificatie.IdentificatienummerType
import nl.rijksoverheid.moz.fbs.common.identificatie.Oin
import java.time.Instant
import java.util.UUID

/**
 * JPA-persistentie-representatie van een [Bericht]. Deze klasse is `internal`
 * omdat externe code alleen via [BerichtRepository] (schrijven/lezen) mag werken;
 * directe entity-mutatie omzeilt de invarianten van [Bericht].
 *
 * [id] is een DB-gegenereerde surrogate key, los van de bedrijfs-id [berichtId] (die blijft
 * UNIQUE voor de 409-semantiek van de Aanlever API) — zo blijft de PK stabiel.
 *
 * Velden hebben default-initialisers zodat Hibernate via de no-arg constructor hydrateert
 * zonder een `lateinit`-window met `UninitializedPropertyAccessException` bij een partiële read.
 */
@Entity
@Table(name = "berichten")
internal class BerichtEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(nullable = false)
    var id: Long = 0

    @Column(name = "bericht_id", nullable = false, unique = true)
    var berichtId: UUID = PLACEHOLDER_UUID

    @Column(nullable = false, length = 20)
    var afzender: String = ""

    // Het type wordt als string (enum-name) opgeslagen i.p.v. ordinaal: leesbaar in de DB
    // en forward-compatible met nieuwe enum-waarden (herordening van de enum zou ordinale
    // opslag stiekem breken).
    @Column(name = "ontvanger_type", nullable = false, length = 8)
    @Enumerated(EnumType.STRING)
    var ontvangerType: IdentificatienummerType = IdentificatienummerType.OIN

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

    // Soft-delete marker. NULL = actief; niet-NULL betekent dat het bericht door
    // de ontvanger is verwijderd via DELETE /berichten/{id}. Ophaal-endpoints
    // filteren rijen met niet-NULL `verwijderdOp` uit; de rij blijft fysiek
    // aanwezig voor audit en eventueel herstel.
    @Column(name = "verwijderd_op")
    var verwijderdOp: Instant? = null

    fun toDomain(): Bericht = uitDbRij(
        berichtId,
        diagnose = {
            "id=$id afzender.length=${afzender.length} ontvangerType=$ontvangerType " +
                "ontvangerWaarde.length=${ontvangerWaarde.length}"
        },
    ) {
        Bericht(
            berichtId = berichtId,
            afzender = Oin(afzender),
            ontvanger = Identificatienummer.of(ontvangerType, ontvangerWaarde),
            onderwerp = onderwerp,
            inhoud = inhoud,
            tijdstipOntvangst = tijdstipOntvangst,
            publicatietijdstip = publicatietijdstip,
        )
    }

    companion object {
        private val PLACEHOLDER_UUID: UUID = UUID(0L, 0L)

        internal fun fromDomain(bericht: Bericht): BerichtEntity = BerichtEntity().apply {
            berichtId = bericht.berichtId
            afzender = bericht.afzender.waarde
            ontvangerType = bericht.ontvanger.type
            ontvangerWaarde = bericht.ontvanger.waarde
            onderwerp = bericht.onderwerp
            inhoud = bericht.inhoud
            tijdstipOntvangst = bericht.tijdstipOntvangst
            publicatietijdstip = bericht.publicatietijdstip
        }
    }
}
