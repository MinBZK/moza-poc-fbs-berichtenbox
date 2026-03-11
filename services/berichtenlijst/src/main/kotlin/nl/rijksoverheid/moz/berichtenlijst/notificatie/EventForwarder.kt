package nl.rijksoverheid.moz.berichtenlijst.notificatie

import jakarta.enterprise.context.ApplicationScoped
import nl.rijksoverheid.moz.berichtenlijst.berichten.Bericht
import org.jboss.logging.Logger

@ApplicationScoped
class EventForwarder {

    private val log = Logger.getLogger(EventForwarder::class.java)

    fun forwardBerichtOntvangen(bericht: Bericht) {
        log.infof("Forwarding bericht-ontvangen event voor bericht %s naar Notificatie Service", bericht.berichtId)
        // TODO: Implementeer CloudEvents forwarding naar Notificatie Service
    }
}
