package nl.rijksoverheid.moz.fbs.berichtenmagazijn.publicatie

import io.quarkus.runtime.StartupEvent
import jakarta.enterprise.context.ApplicationScoped
import jakarta.enterprise.event.Observes
import jakarta.transaction.Transactional
import org.jboss.logging.Logger
import java.time.Clock
import java.time.Instant
import java.util.UUID

/**
 * Schrijfpad van de outbox: één rij per geconfigureerde downstream voor elk
 * opgeslagen bericht. Aangeroepen door de aanlever-flow binnen dezelfde
 * transactie als de berichtopslag — atomair: óf beide zichtbaar, óf geen
 * van beide.
 *
 * Aanmelder / Notificatie-stub / nieuwe downstream wijzigen = config-only,
 * geen schema- of code-wijziging.
 *
 * Bij applicatie-start wordt afgedwongen dat er minstens één downstream
 * geconfigureerd staat in `prod` en `dev` (zie [valideerStartConfiguratie])
 * zodat een misconfiguratie niet stilletjes berichten laat "verdampen" in
 * productie.
 */
@ApplicationScoped
class PublicatieOutbox(
    private val config: PublicatieConfig,
    private val deliveries: PublicatieDeliveryRepository,
    private val clock: Clock,
) {

    private val log = Logger.getLogger(PublicatieOutbox::class.java)

    fun valideerStartConfiguratie(@Observes startup: StartupEvent) {
        // Fail-closed: minstens één `magazijn.publicatie.downstreams.*`-entry
        // is vereist. Anders blijft een opgeslagen bericht onopgemerkt hangen
        // omdat de outbox geen rijen plant.
        check(config.downstreams().isNotEmpty()) {
            "Publicatie-stream heeft geen downstreams geconfigureerd " +
                "(magazijn.publicatie.downstreams.*); berichten zouden onopgemerkt " +
                "blijven hangen na opslag. Configureer minstens één downstream."
        }
    }

    /**
     * Plant deliveries voor [berichtId] vanaf [publicatiedatum].
     *
     * Idempotency: dezelfde [berichtId] tweemaal aanleveren zou een UNIQUE-violation
     * op (bericht_id, doel) opleveren — dat is acceptabel omdat de aanlever-flow
     * dat scenario al als 409 afhandelt (zie `DbConstraintViolationExceptionMapper`).
     *
     * Itereert over `downstreams.keys.sorted()` zodat de insert-volgorde
     * deterministisch is (de SmallRye-config map-iteratievolgorde is dat niet
     * gegarandeerd).
     */
    @Transactional(Transactional.TxType.MANDATORY)
    fun planDeliveries(berichtId: UUID, publicatiedatum: Instant) {
        val downstreams = config.downstreams()
        check(downstreams.isNotEmpty()) {
            // Geen warn-and-return: dat zou een "phantom-published" bericht
            // produceren — opgeslagen, maar nooit naar een downstream gepland.
            // In dev/prod faalt valideerStartConfiguratie al; deze check vangt
            // testen waarin een TestProfile per ongeluk geen downstream zet.
            "Geen downstreams geconfigureerd voor berichtId=$berichtId; bericht zou onopgemerkt blijven hangen"
        }
        val nu = clock.instant()
        downstreams.keys.sorted().forEach { key ->
            deliveries.persist(
                PublicatieDeliveryEntity.nieuwe(
                    berichtId = berichtId,
                    doel = Publicatiedoel(key),
                    volgendePoging = publicatiedatum,
                    aangemaaktOp = nu,
                ),
            )
        }
        log.debugf(
            "Publicatie-deliveries gepland: berichtId=%s aantalDoelen=%d publicatiedatum=%s",
            berichtId,
            downstreams.size,
            publicatiedatum,
        )
    }
}
