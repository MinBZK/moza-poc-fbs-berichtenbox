package nl.rijksoverheid.moz.fbs.berichtenmagazijn.aanlever

import jakarta.enterprise.context.ApplicationScoped
import jakarta.persistence.PersistenceException
import jakarta.transaction.Transactional
import jakarta.ws.rs.ClientErrorException
import nl.rijksoverheid.moz.fbs.berichtenmagazijn.opslag.Bericht
import nl.rijksoverheid.moz.fbs.berichtenmagazijn.opslag.BerichtRepository
import nl.rijksoverheid.moz.fbs.berichtenmagazijn.opslag.Identificatienummer
import nl.rijksoverheid.moz.fbs.berichtenmagazijn.opslag.IdentificatienummerType
import nl.rijksoverheid.moz.fbs.berichtenmagazijn.opslag.Oin
import nl.rijksoverheid.moz.fbs.berichtenmagazijn.publicatie.PublicatieOutbox
import nl.rijksoverheid.moz.fbs.common.exception.DomainValidationException
import org.eclipse.microprofile.faulttolerance.CircuitBreaker
import org.jboss.logging.Logger
import java.time.Instant
import java.util.UUID
import org.hibernate.exception.ConstraintViolationException as HibernateConstraintViolationException

@ApplicationScoped
class BerichtOpslagService(
    private val repository: BerichtRepository,
    private val publicatieOutbox: PublicatieOutbox,
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
    fun slaBerichtOp(
        afzender: String,
        ontvangerType: IdentificatienummerType,
        ontvangerWaarde: String,
        onderwerp: String,
        inhoud: String,
        publicatiedatum: Instant? = null,
    ): Bericht {
        val tijdstipOntvangst = Instant.now()
        val bericht = Bericht(
            berichtId = UUID.randomUUID(),
            afzender = Oin(afzender),
            ontvanger = Identificatienummer.of(ontvangerType, ontvangerWaarde),
            onderwerp = onderwerp,
            inhoud = inhoud,
            tijdstipOntvangst = tijdstipOntvangst,
            // Geen publicatiedatum meegegeven = direct publiceren. We gebruiken expliciet
            // tijdstipOntvangst (niet Instant.now() opnieuw) zodat outbox-rij en bericht
            // dezelfde "T0" delen — anders zou volgende_poging een hair-trigger later
            // liggen dan tijdstipOntvangst en kan de domein-invariant per ongeluk falen.
            publicatiedatum = publicatiedatum ?: tijdstipOntvangst,
        )

        try {
            repository.save(bericht)
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
            publicatieOutbox.planDeliveries(bericht.berichtId, bericht.publicatiedatum)
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
            "Bericht opgeslagen: berichtId=%s ontvangerType=%s",
            bericht.berichtId,
            bericht.ontvanger.type,
        )
        return bericht
    }
}
