package nl.rijksoverheid.moz.fbs.berichtenmagazijn.aanlever

import jakarta.enterprise.context.ApplicationScoped
import jakarta.persistence.PersistenceException
import jakarta.transaction.Transactional
import jakarta.ws.rs.WebApplicationException
import nl.rijksoverheid.moz.fbs.berichtenmagazijn.opslag.Bericht
import nl.rijksoverheid.moz.fbs.berichtenmagazijn.opslag.BerichtRepository
import nl.rijksoverheid.moz.fbs.berichtenmagazijn.opslag.Bijlage
import nl.rijksoverheid.moz.fbs.berichtenmagazijn.opslag.BijlageRepository
import nl.rijksoverheid.moz.fbs.common.identificatie.Identificatienummer
import nl.rijksoverheid.moz.fbs.common.identificatie.IdentificatienummerType
import nl.rijksoverheid.moz.fbs.common.identificatie.Oin
import nl.rijksoverheid.moz.fbs.berichtenmagazijn.publicatie.PublicatieOutbox
import nl.rijksoverheid.moz.fbs.berichtenmagazijn.validatie.BerichtValidatieService
import nl.rijksoverheid.moz.fbs.common.profiel.ToestemmingGeweigerdException
import nl.rijksoverheid.moz.fbs.common.exception.DomainValidationException
import org.eclipse.microprofile.faulttolerance.CircuitBreaker
import org.jboss.logging.Logger
import java.time.Clock
import java.time.Instant
import java.util.UUID
import org.hibernate.exception.ConstraintViolationException as HibernateConstraintViolationException

@ApplicationScoped
class BerichtOpslagService(
    private val repository: BerichtRepository,
    private val bijlageRepository: BijlageRepository,
    private val validatieService: BerichtValidatieService,
    private val publicatieOutbox: PublicatieOutbox,
    private val clock: Clock,
) {

    private val log = Logger.getLogger(BerichtOpslagService::class.java)

    // Circuit-breaker-thresholds; tunebaar per omgeving.
    //
    // skipOn — fouten die níét meetellen voor het circuit:
    //  - DomainValidationException, ToestemmingGeweigerdException: client-fouten en
    //    policy-besluiten; zeggen niets over de gezondheid van de infrastructuur.
    //  - HibernateConstraintViolationException: unique-key (409) én NOT NULL/FK/CHECK
    //    (500) duiden op data/schema, niet op een onbereikbare DB.
    //  - WebApplicationException: vangt zowel JAX-RS-mapper-fouten als de Quarkus
    //    REST Reactive `ClientWebApplicationException` (extends WebApplicationException
    //    direct, niet via ClientErrorException). Een 4xx/5xx van een upstream zegt
    //    niets over de magazijn-DB-gezondheid waar deze CB primair tegen beschermt;
    //    de REST-client heeft zijn eigen `@Retry` op transient I/O.
    //
    // Niet in skipOn: `jakarta.validation.ConstraintViolationException` — Bean Validation
    // vuurt vóór de resource-methode en bereikt deze service niet. Generieke
    // `IllegalArgumentException` evenmin: die wijst op een programmeerfout en moet wél
    // meetellen.
    @CircuitBreaker(
        requestVolumeThreshold = 20,
        failureRatio = 0.5,
        delay = 5_000L,
        successThreshold = 2,
        skipOn = [
            DomainValidationException::class,
            HibernateConstraintViolationException::class,
            WebApplicationException::class,
            ToestemmingGeweigerdException::class,
        ],
    )
    @Transactional
    fun slaBerichtOp(
        afzender: String,
        ontvangerType: IdentificatienummerType,
        ontvangerWaarde: String,
        onderwerp: String,
        inhoud: String,
        publicatietijdstip: Instant? = null,
        bijlagen: List<BijlageInvoer> = emptyList(),
    ): Bericht {
        val tijdstipOntvangst = clock.instant()
        val berichtId = UUID.randomUUID()
        val bericht = Bericht(
            berichtId = berichtId,
            afzender = Oin(afzender),
            ontvanger = Identificatienummer.of(ontvangerType, ontvangerWaarde),
            onderwerp = onderwerp,
            inhoud = inhoud,
            tijdstipOntvangst = tijdstipOntvangst,
            // Zonder meegestuurd publicatietijdstip = direct publiceren. Hergebruik
            // tijdstipOntvangst zodat bericht en outbox-rij dezelfde T0 delen.
            publicatietijdstip = publicatietijdstip ?: tijdstipOntvangst,
        )

        // Validatie vóór persistentie: MIME-typen en toestemming (issue #541).
        // Gooit DomainValidationException (→ 400) of ToestemmingGeweigerdException (→ 403),
        // beide in skipOn van de circuit breaker hierboven.
        validatieService.valideer(bericht, bijlagen)

        try {
            repository.save(bericht)
            bijlagen.forEach { nieuw ->
                bijlageRepository.save(
                    Bijlage(
                        bijlageId = UUID.randomUUID(),
                        berichtId = berichtId,
                        naam = nieuw.naam,
                        mimeType = nieuw.mimeType,
                        content = nieuw.content,
                    ),
                )
            }
        } catch (ex: PersistenceException) {
            // Opslagfouten loggen we met service-context (type + lengtes) zodat diagnose
            // mogelijk blijft óók wanneer de mapper het detail maskeert. De waarde van
            // afzender/ontvanger blijft buiten de applicatielog: ontvanger kan een BSN zijn
            // en persoonsgegevens horen niet in de reguliere log (AVG art. 5 lid 1c,
            // BIO 12.4.1). LDV is de juiste plek voor dataSubjectId.
            log.errorf(
                ex,
                "Opslaan mislukt berichtId=%s ontvangerType=%s onderwerp.length=%d inhoud.length=%d",
                bericht.berichtId,
                bericht.ontvanger.type,
                bericht.onderwerp.length,
                bericht.inhoud.length,
            )
            throw ex
        }

        // Outbox-deliveries in dezelfde transactie als de bericht-row: ofwel beide
        // in de DB, ofwel geen van beide. Anders kan een crash tussen save en
        // planDeliveries een bericht zonder publicatie-opdracht achterlaten.
        // Outbox-fouten apart loggen: een UNIQUE-violation hier hoort niet thuis in
        // de "Opslaan mislukt"-log-prefix omdat het bericht zelf wel persistable was.
        // Vangt ook RuntimeException van bv. een kapotte SmallRye-config-proxy of
        // misconfigured downstream — anders zou de operator alleen een generieke
        // 500 zien zonder berichtId-context.
        try {
            publicatieOutbox.planDeliveries(bericht.berichtId, bericht.publicatietijdstip)
        } catch (ex: RuntimeException) {
            log.errorf(
                ex,
                "Plannen van publicatie-deliveries mislukt berichtId=%s ontvangerType=%s categorie=%s",
                bericht.berichtId,
                bericht.ontvanger.type,
                ex.javaClass.simpleName,
            )
            throw ex
        }

        log.debugf(
            "Bericht opgeslagen: berichtId=%s ontvangerType=%s bijlagen=%d",
            bericht.berichtId,
            bericht.ontvanger.type,
            bijlagen.size,
        )
        return bericht
    }
}

/**
 * Input-tuple voor één bijlage in de Aanlever-flow. Houdt de service-API
 * losgekoppeld van de gegenereerde JAX-RS DTO's: de resource-laag mapt de
 * inkomende BijlageAanleverenRequest naar dit invoer-record, dat de
 * service omzet naar een
 * [nl.rijksoverheid.moz.fbs.berichtenmagazijn.opslag.Bijlage]-domeinobject.
 */
data class BijlageInvoer(
    val naam: String,
    val mimeType: String,
    val content: ByteArray,
) {
    // contentEquals/hashCode zodat tests deterministisch met bytes kunnen werken.
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is BijlageInvoer) return false
        return naam == other.naam && mimeType == other.mimeType && content.contentEquals(other.content)
    }

    override fun hashCode(): Int {
        var result = naam.hashCode()
        result = 31 * result + mimeType.hashCode()
        result = 31 * result + content.contentHashCode()
        return result
    }
}
