package nl.rijksoverheid.moz.fbs.berichtenmagazijn.publicatie

import io.quarkus.scheduler.Scheduled
import jakarta.enterprise.context.ApplicationScoped
import jakarta.persistence.EntityManager
import jakarta.persistence.PersistenceException
import jakarta.transaction.Transactional
import jakarta.transaction.TransactionalException
import org.jboss.logging.Logger

/**
 * Opschoner voor terminale `GEPUBLICEERD`-rijen ouder dan 30 dagen. Voorkomt
 * lineaire groei van `publicatie_deliveries` (zie [PublicatieDeliveryEntity]).
 *
 * Gebruikt de view `publicatie_deliveries_oud` (gedefinieerd in V2-migratie)
 * zodat de retentie-regel op één plek (SQL) leeft en hier alleen de DELETE
 * staat. `MISLUKT`-rijen
 * blijven intentioneel staan (forensisch onderzoek + juridische
 * bewaartermijnen vereisen een aparte beleidsbeslissing).
 *
 * Tuning, default en operator-overweging staan op één plek:
 * [PublicatieConfig.Opschonen.interval]. Default eens per 24 uur.
 *
 * **Startup-delay (`delay = 5 MINUTES`)**: geeft Flyway/Hibernate-validatie
 * tijd af te ronden vóór de eerste DELETE; voorkomt dat een misconfigured
 * schema op opstart-pad direct een query op een onbestaande view zou doen.
 *
 * **Geen expliciete `identity`**: Quarkus Scheduler leidt een unieke
 * identifier af van klasse + methode-naam. Deze bean heeft slechts één
 * `@Scheduled`-methode, dus de auto-afgeleide identifier is gegarandeerd
 * uniek — handmatige `identity` zou alleen ruis zijn.
 */
@ApplicationScoped
class PublicatieDeliveriesOpschoner(
    private val entityManager: EntityManager,
) {

    private val log = Logger.getLogger(PublicatieDeliveriesOpschoner::class.java)

    @Scheduled(
        every = "{magazijn.publicatie.opschonen.interval}",
        delay = 5,
        delayUnit = java.util.concurrent.TimeUnit.MINUTES,
        concurrentExecution = Scheduled.ConcurrentExecution.SKIP,
    )
    @Transactional
    fun verwijderTerminaleRijen() {
        // Wrap rond `executeUpdate` zodat DB-uitval/lock-timeout/schema-drift een
        // greppable ERROR geeft; de volgende ronde retried (DELETE is idempotent).
        // Smal vangen op persistence/transactie/EM-lifecycle; programmeerfouten vliegen
        // door naar SimpleScheduler (die logt zelf ERROR + stack-trace).
        try {
            val verwijderd = entityManager.createNativeQuery(
                "DELETE FROM publicatie_deliveries WHERE id IN (SELECT id FROM publicatie_deliveries_oud)",
            ).executeUpdate()
            if (verwijderd > 0) {
                log.infof("Outbox-opschoning verwijderde %d GEPUBLICEERD-rijen ouder dan retentie-grens", verwijderd)
            }
        } catch (ex: PersistenceException) {
            log.errorf(
                ex,
                "Outbox-opschoning faalde (persistence) — outbox kan groeien tot volgende ronde slaagt; categorie=%s",
                ex.javaClass.simpleName,
            )
        } catch (ex: TransactionalException) {
            log.errorf(
                ex,
                "Outbox-opschoning faalde (transactie) — outbox kan groeien tot volgende ronde slaagt; categorie=%s",
                ex.javaClass.simpleName,
            )
        } catch (ex: IllegalStateException) {
            // Bij graceful shutdown kan de EM al gesloten zijn terwijl de trigger nog
            // vuurt — normaal lifecycle-gedrag, geen incident. `isOpen` kan zelf ISE
            // gooien op een disposed EM; alleen die specifiek vangen (niet OOM/SOF).
            val emOpen = try {
                entityManager.isOpen
            } catch (_: IllegalStateException) {
                false
            }
            if (emOpen) {
                // EM nog open → geen shutdown en geen DB-uitval (anders PersistenceException):
                // wijst op een echte lifecycle-/programmeerbug. ERROR zodat ops het ziet.
                log.errorf(
                    ex,
                    "Outbox-opschoning ISE buiten shutdown-window: onverwachte transactie-/sessie-state " +
                        "(geen DB-uitval — anders zou PersistenceException gevangen zijn); categorie=%s",
                    ex.javaClass.simpleName,
                )
            } else {
                log.infof("Outbox-opschoning overgeslagen: EntityManager gesloten (shutdown in progress)")
            }
        }
    }
}
