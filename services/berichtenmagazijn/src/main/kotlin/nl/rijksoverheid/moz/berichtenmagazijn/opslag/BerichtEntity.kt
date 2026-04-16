package nl.rijksoverheid.moz.berichtenmagazijn.opslag

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import jakarta.ws.rs.InternalServerErrorException
import java.time.Instant
import java.util.UUID

/**
 * JPA-persistentie-representatie van een [Bericht]. Deze klasse is `internal`
 * omdat externe code alleen via [BerichtRepository.opslaan] (schrijven) of
 * [BerichtEntity.toDomain] (lezen, via repository) mag werken; directe entity-
 * mutatie omzeilt de invarianten van [Bericht].
 */
@Entity
@Table(name = "berichten")
internal class BerichtEntity {

    @Id
    @Column(nullable = false)
    lateinit var berichtId: UUID

    @Column(nullable = false, length = 20)
    lateinit var afzender: String

    @Column(nullable = false, length = 20)
    lateinit var ontvanger: String

    @Column(nullable = false, length = 255)
    lateinit var onderwerp: String

    @Column(nullable = false, columnDefinition = "CLOB")
    lateinit var inhoud: String

    @Column(nullable = false)
    lateinit var tijdstip: Instant

    fun toDomain(): Bericht = try {
        Bericht(
            berichtId = berichtId,
            afzender = Oin(afzender),
            ontvanger = Identificatienummer.parse(ontvanger),
            onderwerp = onderwerp,
            inhoud = inhoud,
            tijdstip = tijdstip,
        )
    } catch (ex: IllegalArgumentException) {
        // Bij hydratatie zijn alle invarianten al door fromDomain geverifieerd vóór persist.
        // Een fout hier betekent dat de DB-rij niet meer aan de huidige domein-invarianten
        // voldoet (bv. handmatige edit, vorige schemaversie, aangescherpte validatie).
        // Dat is een serverfout, geen clientfout — gooi een 500-WebApplicationException
        // zodat ProblemExceptionMapper het maskeert met correlation-id i.p.v. de
        // raw DomainValidationException als 400 te exposen.
        throw InternalServerErrorException(
            "DB-rij berichtId=$berichtId voldoet niet aan domein-invarianten",
            ex,
        )
    }

    companion object {
        internal fun fromDomain(bericht: Bericht): BerichtEntity = BerichtEntity().apply {
            berichtId = bericht.berichtId
            afzender = bericht.afzender.waarde
            ontvanger = bericht.ontvanger.waarde
            onderwerp = bericht.onderwerp
            inhoud = bericht.inhoud
            tijdstip = bericht.tijdstip
        }
    }
}
