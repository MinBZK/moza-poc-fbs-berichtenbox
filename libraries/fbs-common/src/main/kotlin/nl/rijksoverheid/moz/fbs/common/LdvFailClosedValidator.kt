package nl.rijksoverheid.moz.fbs.common

import io.quarkus.runtime.StartupEvent
import jakarta.enterprise.context.ApplicationScoped
import jakarta.enterprise.event.Observes
import org.eclipse.microprofile.config.inject.ConfigProperty

/**
 * Borgt dat het Logboek Dataverwerkingen in productie-achtige profielen daadwerkelijk
 * fail-closed werkt: een verwerking die niet in het logboek kwam, telt niet als
 * uitgevoerd.
 *
 * Twee properties bepalen dat, en beide zijn met één omgevingsvariabele (ordinal 300,
 * wint van `application.properties` op 250) stil uit te zetten:
 *
 * - `span-processor=batch` exporteert op een achtergrondthread, terwijl de
 *   schrijffout-recorder een `ThreadLocal` is. De request-thread consumeert dan altijd
 *   `null` en elke acknowledgement slaagt, ook als er niets is weggeschreven.
 * - `write-failure-policy=fail-open` laat de exporter de fout inslikken, zodat er
 *   überhaupt niets te consumeren valt.
 *
 * In `dev` en `test` blijven beide vrij: daar draait geen echte betrokkene-data en zou
 * de eis elke lokale start van een logboek afhankelijk maken.
 */
@ApplicationScoped
class LdvFailClosedValidator(
    @param:ConfigProperty(name = SPAN_PROCESSOR_KEY, defaultValue = "simple")
    private val spanProcessor: String,
    @param:ConfigProperty(name = WRITE_FAILURE_POLICY_KEY, defaultValue = "fail-closed")
    private val writeFailurePolicy: String,
    @param:ConfigProperty(name = "quarkus.profile") private val profile: String,
) {

    fun onStartup(@Observes event: StartupEvent) {
        validate(profile, spanProcessor, writeFailurePolicy)
    }

    companion object {
        const val SPAN_PROCESSOR_KEY = "logboekdataverwerking.span-processor"
        const val WRITE_FAILURE_POLICY_KEY = "logboekdataverwerking.write-failure-policy"

        private val PROFIELEN_ZONDER_EIS = setOf("dev", "test")

        /** De enige processor-modus die synchroon exporteert op de request-thread. */
        private const val SYNCHRONE_SPAN_PROCESSOR = "simple"

        /**
         * Schrijfwijzen die de wrapper als fail-closed leest (`ConfigurationLoader`
         * normaliseert case en accepteert koppelteken, underscore en aaneengeschreven).
         * Alle drie moeten hier slagen, anders wijst de guard een geldige config af.
         */
        private val FAIL_CLOSED_WAARDEN = setOf("fail-closed", "fail_closed", "failclosed")

        fun validate(profile: String, spanProcessor: String, writeFailurePolicy: String) {
            if (profile in PROFIELEN_ZONDER_EIS) return

            require(spanProcessor.lowercase() == SYNCHRONE_SPAN_PROCESSOR) {
                "$SPAN_PROCESSOR_KEY MOET '$SYNCHRONE_SPAN_PROCESSOR' zijn in profiel '$profile' — " +
                    "'batch' exporteert op een achtergrondthread, waardoor de applicatie nooit ziet of " +
                    "de logregel is opgeslagen. Huidige waarde: '$spanProcessor'"
            }

            require(writeFailurePolicy.lowercase() in FAIL_CLOSED_WAARDEN) {
                "$WRITE_FAILURE_POLICY_KEY MOET 'fail-closed' zijn in profiel '$profile' — " +
                    "een verwerking die niet in het logboek kwam, telt niet als uitgevoerd. " +
                    "Huidige waarde: '$writeFailurePolicy'"
            }
        }
    }
}
