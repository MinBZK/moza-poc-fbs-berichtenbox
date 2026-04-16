package nl.rijksoverheid.moz.berichtenmagazijn.aanlever

import jakarta.enterprise.context.ApplicationScoped
import jakarta.persistence.PersistenceException
import jakarta.transaction.Transactional
import jakarta.ws.rs.ClientErrorException
import nl.rijksoverheid.moz.berichtenmagazijn.opslag.Bericht
import nl.rijksoverheid.moz.berichtenmagazijn.opslag.BerichtRepository
import nl.rijksoverheid.moz.berichtenmagazijn.opslag.Identificatienummer
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
        ontvanger: String,
        onderwerp: String,
        inhoud: String,
    ): Bericht {
        val bericht = Bericht(
            berichtId = UUID.randomUUID(),
            afzender = Oin(afzender),
            ontvanger = Identificatienummer.parse(ontvanger),
            onderwerp = onderwerp,
            inhoud = inhoud,
            tijdstip = Instant.now(),
        )

        try {
            repository.opslaan(bericht)
        } catch (ex: PersistenceException) {
            // Persist-specifieke fouten loggen we met service-context (afzender/ontvanger)
            // zodat diagnose mogelijk blijft óók wanneer de mapper het detail maskeert.
            // Andere RuntimeExceptions (NPE, programmeerfout) vallen door — die worden
            // door ProblemExceptionMapper / IllegalArgumentExceptionMapper afgehandeld
            // mét eigen errorId, zodat we hier geen verwarrende dubbele log-context maken.
            log.errorf(
                ex,
                "Persist mislukt voor berichtId=%s afzender=%s ontvanger=%s onderwerp.length=%d inhoud.length=%d",
                bericht.berichtId,
                bericht.afzender.waarde,
                bericht.ontvanger.waarde,
                bericht.onderwerp.length,
                bericht.inhoud.length,
            )
            throw ex
        }

        log.debugf(
            "Bericht opgeslagen: berichtId=%s afzender=%s ontvanger=%s",
            bericht.berichtId,
            bericht.afzender.waarde,
            bericht.ontvanger.waarde,
        )
        return bericht
    }
}
