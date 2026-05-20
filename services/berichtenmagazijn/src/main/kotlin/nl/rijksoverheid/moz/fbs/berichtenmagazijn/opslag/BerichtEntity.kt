package nl.rijksoverheid.moz.fbs.berichtenmagazijn.opslag

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import jakarta.ws.rs.InternalServerErrorException
import nl.rijksoverheid.moz.fbs.common.exception.DomainValidationException
import org.jboss.logging.Logger
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

    @Column(name = "publicatiedatum", nullable = false)
    var publicatiedatum: Instant = Instant.EPOCH

    // Soft-delete marker. NULL = actief; niet-NULL betekent dat het bericht door
    // de ontvanger is verwijderd via DELETE /berichten/{id}. Ophaal-endpoints
    // filteren rijen met niet-NULL `verwijderdOp` uit; de rij blijft fysiek
    // aanwezig voor audit en eventueel herstel.
    @Column(name = "verwijderd_op")
    var verwijderdOp: Instant? = null

    fun toDomain(): Bericht = try {
        Bericht(
            berichtId = berichtId,
            afzender = Oin(afzender),
            ontvanger = Identificatienummer.of(ontvangerType, ontvangerWaarde),
            onderwerp = onderwerp,
            inhoud = inhoud,
            tijdstipOntvangst = tijdstipOntvangst,
            publicatiedatum = publicatiedatum,
        )
    } catch (ex: DomainValidationException) {
        // Invarianten zijn vóór persist al door fromDomain geverifieerd; een DVE hier
        // betekent een niet-conforme DB-rij (handmatige edit, oude schemaversie). Dat is een
        // serverfout → 500 zodat ProblemExceptionMapper maskeert i.p.v. een 400 te exposen.
        log.errorf(
            ex,
            "DB-rij corrupt of niet-conform: id=%d berichtId=%s afzender.length=%d ontvangerType=%s ontvangerWaarde.length=%d",
            id,
            berichtId,
            afzender.length,
            ontvangerType,
            ontvangerWaarde.length,
        )
        throw InternalServerErrorException(
            "DB-rij berichtId=$berichtId voldoet niet aan domein-invarianten",
            ex,
        )
    }

    companion object {
        private val log = Logger.getLogger(BerichtEntity::class.java)
        private val PLACEHOLDER_UUID: UUID = UUID(0L, 0L)

        internal fun fromDomain(bericht: Bericht): BerichtEntity = BerichtEntity().apply {
            berichtId = bericht.berichtId
            afzender = bericht.afzender.waarde
            ontvangerType = bericht.ontvanger.type
            ontvangerWaarde = bericht.ontvanger.waarde
            onderwerp = bericht.onderwerp
            inhoud = bericht.inhoud
            tijdstipOntvangst = bericht.tijdstipOntvangst
            publicatiedatum = bericht.publicatiedatum
        }
    }
}
