package nl.rijksoverheid.moz.fbs.berichtenuitvraag.uitvraag

import io.opentelemetry.api.trace.Span
import io.quarkus.vertx.http.runtime.CurrentVertxRequest
import jakarta.enterprise.context.ApplicationScoped
import jakarta.ws.rs.WebApplicationException
import jakarta.ws.rs.core.Response
import jakarta.ws.rs.core.StreamingOutput
import nl.rijksoverheid.moz.fbs.kern.identificatie.Identificatienummer
import org.jboss.logging.Logger
import java.io.IOException
import java.io.InterruptedIOException
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Het antwoord op een bijlage-download, zodra [BerichtOphaalService.haalBijlage] het begin heeft
 * gelezen. Draait binnen de `@Logboek`-methode van `getBijlage`: de span van de logregel is dan
 * current, en daarnaar verwijzen de logregels hieronder.
 */
@ApplicationScoped
class BijlageDoorgifte(
    private val vertxRequest: CurrentVertxRequest,
    private val streams: Bijlagestreams,
    private val config: BijlageConfig,
) {

    /**
     * Is de bijlage kleiner dan [BijlageInhoud.BEGIN_BYTES], dan gaat ze als `byte[]` de deur uit: dat schrijft Quarkus zonder
     * een worker-thread vast te houden, ook als de afnemer traag leest. Anders streamt de rest.
     */
    fun antwoord(bijlage: BijlageInhoud, xOntvanger: String, kenmerken: String): Response {
        bijlage.volledig?.let { return Response.ok(it).build() }

        return Response.ok(stream(bijlage, xOntvanger, kenmerken)).build()
    }

    /**
     * De rest van een grote bijlage. Een plek onder het plafond van [Bijlagestreams], en een bewaking
     * die de download reset als één write langer dan [BijlageConfig.maxStilstand] op de afnemer
     * wacht; zonder die bewaking houdt een afnemer die niet leest de worker-thread vast tot de
     * idle-timeout van de HTTP-server.
     */
    private fun stream(bijlage: BijlageInhoud, xOntvanger: String, kenmerken: String): StreamingOutput {
        val verzoek = vertxRequest.current
        val vrijgave = try {
            streams.reserveer(Identificatienummer.fromHeader(xOntvanger))
        } catch (e: WebApplicationException) {
            bijlage.sluitAlsOngeschreven()

            throw e
        }

        val maxStilstand = config.maxStilstand()
        val stilgevallen = AtomicBoolean(false)
        val bewaking = verzoek.vertx().setPeriodic(BEWAKING_MS) {
            if (bijlage.wachtLangerDan(maxStilstand) && stilgevallen.compareAndSet(false, true)) verzoek.response().reset()
        }

        // Idempotent: de writer ruimt op als hij klaar is; de end-handler als hij nooit draait (HEAD,
        // of een fout na de methode). De end-handler alleen bleek niet genoeg: na een gestreamd
        // antwoord kwam de plek daarlangs niet terug.
        val ruimOp = {
            verzoek.vertx().cancelTimer(bewaking)
            vrijgave()
            bijlage.sluitAlsOngeschreven()
        }

        verzoek.addEndHandler { ruimOp() }

        // Een afnemer die al weg was, zet de end-handler niet meer in gang.
        if (verzoek.response().closed()) ruimOp()

        // De logregel van deze methode: de interceptor houdt zijn span actief zolang ze loopt.
        val logregel = Span.current().spanContext

        return StreamingOutput { doel ->
            try {
                bijlage.schrijfNaar(doel) { verzoek.response().closed() }
            } catch (e: BijlageOverdrachtAfgebroken) {
                log.errorf(
                    e,
                    "magazijn-bijlage: overdracht afgebroken nadat het antwoord begonnen was (%s) [%s:%s]",
                    kenmerken,
                    logregel.traceId,
                    logregel.spanId,
                )

                // Zelf resetten en doorgooien: de afnemer ziet dan een onvolledige download in plaats
                // van een ingekorte bijlage die netjes eindigt, ook als Quarkus het antwoord anders
                // zou afronden.
                verzoek.response().reset()

                throw e
            } catch (e: IOException) {
                when {
                    stilgevallen.get() -> log.warnf(
                        "magazijn-bijlage: afnemer las langer dan %s niet; download afgebroken (%s) [%s:%s]",
                        maxStilstand,
                        kenmerken,
                        logregel.traceId,
                        logregel.spanId,
                    )
                    generateSequence<Throwable>(e) { it.cause }.any { it is InterruptedIOException } ->
                        log.warnf(e, "magazijn-bijlage: overdracht onderbroken (%s) [%s:%s]", kenmerken, logregel.traceId, logregel.spanId)
                    // Meestal annuleerde de afnemer: geen fout van de verwerking, dus niet op error.
                    else -> log.debugf("afnemer brak de bijlage-download af (%s)", kenmerken)
                }

                verzoek.response().reset()

                throw e as? BijlageNietAfgeleverd ?: BijlageNietAfgeleverd(e)
            } finally {
                ruimOp()
            }
        }
    }

    private companion object {
        private val log: Logger = Logger.getLogger(BijlageDoorgifte::class.java)

        /** Hoe vaak de bewaking kijkt of een write te lang op de afnemer wacht. */
        private const val BEWAKING_MS = 1000L
    }
}
