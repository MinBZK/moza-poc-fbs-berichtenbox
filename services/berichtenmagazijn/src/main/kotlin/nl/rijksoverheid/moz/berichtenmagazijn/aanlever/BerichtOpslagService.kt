package nl.rijksoverheid.moz.berichtenmagazijn.aanlever

import jakarta.enterprise.context.ApplicationScoped
import jakarta.persistence.PersistenceException
import jakarta.transaction.Transactional
import jakarta.ws.rs.ClientErrorException
import nl.rijksoverheid.moz.berichtenmagazijn.opslag.Bericht
import nl.rijksoverheid.moz.berichtenmagazijn.opslag.BerichtRepository
import nl.rijksoverheid.moz.berichtenmagazijn.opslag.Identificatienummer
import nl.rijksoverheid.moz.berichtenmagazijn.opslag.IdentificatienummerType
import nl.rijksoverheid.moz.berichtenmagazijn.opslag.Oin
import nl.rijksoverheid.moz.fbs.common.DomainValidationException
import org.eclipse.microprofile.faulttolerance.CircuitBreaker
import org.jboss.logging.Logger
import java.time.Instant
import java.util.UUID
import org.hibernate.exception.ConstraintViolationException as HibernateConstraintViolationException

@ApplicationScoped
class BerichtOpslagService(
    private val repository: BerichtRepository,
) {

    private val log = Logger.getLogger(BerichtOpslagService::class.java)

    // Thresholds zijn PoC-defaults.
    //
    // skipOn — fouten die níét meetellen voor het circuit:
    //  - DomainValidationException, ClientErrorException: client-fouten, zeggen niets
    //    over de gezondheid van de infrastructuur.
    //  - HibernateConstraintViolationException: unique-key (409) én NOT NULL/FK/CHECK
    //    (500) duiden op data/schema, niet op een onbereikbare DB.
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
            ClientErrorException::class,
        ],
    )
    @Transactional
    fun opslaanBericht(
        afzender: String,
        ontvangerType: IdentificatienummerType,
        ontvangerWaarde: String,
        onderwerp: String,
        inhoud: String,
    ): Bericht {
        val bericht = Bericht(
            berichtId = UUID.randomUUID(),
            afzender = Oin(afzender),
            ontvanger = Identificatienummer.of(ontvangerType, ontvangerWaarde),
            onderwerp = onderwerp,
            inhoud = inhoud,
            tijdstipOntvangst = Instant.now(),
        )

        try {
            repository.save(bericht)
        } catch (ex: PersistenceException) {
            // Persist-specifieke fouten loggen we met service-context (type + lengtes) zodat
            // diagnose mogelijk blijft óók wanneer de mapper het detail maskeert. De waarde
            // van afzender/ontvanger blijft buiten de applicatielog: ontvanger kan een BSN
            // zijn en persoonsgegevens horen niet in de reguliere log (AVG art. 5 lid 1c,
            // BIO 12.4.1). LDV is de juiste plek voor dataSubjectId.
            //
            // Andere RuntimeExceptions (NPE, programmeerfout) vallen door — die worden
            // door ProblemExceptionMapper / IllegalArgumentExceptionMapper afgehandeld
            // mét eigen errorId, zodat we hier geen verwarrende dubbele log-context maken.
            log.errorf(
                ex,
                "Persist mislukt berichtId=%s afzenderType=OIN ontvangerType=%s onderwerp.length=%d inhoud.length=%d",
                bericht.berichtId,
                bericht.ontvanger.type,
                bericht.onderwerp.length,
                bericht.inhoud.length,
            )
            throw ex
        }

        log.debugf(
            "Bericht opgeslagen: berichtId=%s afzenderType=OIN ontvangerType=%s",
            bericht.berichtId,
            bericht.ontvanger.type,
        )
        return bericht
    }
}
