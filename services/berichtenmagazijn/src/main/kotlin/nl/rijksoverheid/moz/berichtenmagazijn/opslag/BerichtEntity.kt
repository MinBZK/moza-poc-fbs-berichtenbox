package nl.rijksoverheid.moz.berichtenmagazijn.opslag

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.Id
import jakarta.persistence.Table
import jakarta.ws.rs.InternalServerErrorException
import nl.rijksoverheid.moz.fbs.common.DomainValidationException
import org.jboss.logging.Logger
import java.time.Instant
import java.util.UUID

/**
 * JPA-persistentie-representatie van een [Bericht]. Deze klasse is `internal`
 * omdat externe code alleen via [BerichtRepository.opslaan] (schrijven) of
 * [BerichtEntity.toDomain] (lezen, via repository) mag werken; directe entity-
 * mutatie omzeilt de invarianten van [Bericht].
 *
 * De velden hebben default-initialisers zodat Hibernate via de no-arg constructor kan
 * hydrateren en `fromDomain` meteen alle waarden zet. Daarmee bestaat er geen
 * gedeeltelijk-geïnitialiseerde staat tussen `BerichtEntity()` en de setters — in
 * tegenstelling tot `lateinit var`, dat een window met `UninitializedPropertyAccessException`
 * open laat wanneer Hibernate een subset van kolommen leest.
 */
@Entity
@Table(name = "berichten")
internal class BerichtEntity {

    @Id
    @Column(nullable = false)
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

    @Column(nullable = false, columnDefinition = "CLOB")
    var inhoud: String = ""

    @Column(name = "tijdstip_ontvangst", nullable = false)
    var tijdstipOntvangst: Instant = Instant.EPOCH

    fun toDomain(): Bericht = try {
        Bericht(
            berichtId = berichtId,
            afzender = Oin(afzender),
            ontvanger = Identificatienummer.of(ontvangerType, ontvangerWaarde),
            onderwerp = onderwerp,
            inhoud = inhoud,
            tijdstipOntvangst = tijdstipOntvangst,
        )
    } catch (ex: DomainValidationException) {
        // Bij hydratatie zijn alle invarianten al door fromDomain geverifieerd vóór persist.
        // Een DVE hier betekent dat de DB-rij niet meer aan de huidige domein-invarianten
        // voldoet (bv. handmatige edit, vorige schemaversie, aangescherpte validatie).
        // Dat is een serverfout, geen clientfout — gooi een 500-WebApplicationException
        // zodat ProblemExceptionMapper het maskeert met correlation-id i.p.v. de
        // raw DomainValidationException als 400 te exposen.
        log.errorf(
            ex,
            "DB-rij corrupt of niet-conform: berichtId=%s afzender.length=%d ontvangerType=%s ontvangerWaarde.length=%d",
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
        }
    }
}
