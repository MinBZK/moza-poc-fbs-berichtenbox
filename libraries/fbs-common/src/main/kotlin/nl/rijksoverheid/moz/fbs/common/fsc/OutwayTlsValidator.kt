package nl.rijksoverheid.moz.fbs.common.fsc

import io.quarkus.runtime.StartupEvent
import jakarta.enterprise.context.ApplicationScoped
import jakarta.enterprise.event.Observes
import nl.rijksoverheid.moz.fbs.common.PROFIELEN_ZONDER_TLS_EIS
import org.eclipse.microprofile.config.Config
import org.eclipse.microprofile.config.ConfigProvider
import org.eclipse.microprofile.config.inject.ConfigProperty
import java.util.logging.Logger

/**
 * Maakt bij boot zichtbaar of het uitgaande verkeer door de eigen FSC-outway een eigen
 * trust-anker heeft, en weigert een anker dat certificaatvalidatie juist opheft.
 *
 * Twee dingen maken dit nodig, en beide volgen uit hoe Quarkus deze configuratie behandelt.
 *
 * **Een verkeerd gespelde naam degradeert geruisloos.** `quarkus.tls.<naam>.*` is map-shaped:
 * elke naam is per definitie een geldige sleutel, dus een typefout levert geen "unrecognized
 * configuration key" op. De bucket wordt zelfs eager gevalideerd — het trust-store-bestand
 * wordt geopend en gelezen — terwijl niemand hem opvraagt. Het verkeer valt dan terug op de
 * JVM-default trust-store en de handshake faalt later met een certificaatfout die als
 * netwerkstoring leest. Vandaar dat deze validator naar de CONFIG-SLEUTELS kijkt en niet naar
 * de uitkomst van [io.quarkus.tls.TlsConfigurationRegistry]: alleen zo is "geen anker" te
 * onderscheiden van "anker onder een andere naam".
 *
 * **Aanwezigheid van de bucket is de schakelaar, maar niet elke bucket ís een anker.**
 * `trust-all` en een hostnaam-verificatie van `NONE` maken de configuratie net zo goed
 * aanwezig, terwijl ze het vertrouwen opheffen in plaats van het te richten. Over dit verkeer
 * lopen berichten van burgers en ondernemers; wie dat tijdens een incident aanzet om een route
 * te bewijzen, hoort dat niet stilzwijgend te laten staan.
 */
@ApplicationScoped
class OutwayTlsValidator(
    @param:ConfigProperty(name = "quarkus.profile") private val profile: String,
    // BEWUST ONVEILIG; rationale en voorwaarden bij [Companion.valideer].
    @param:ConfigProperty(name = UNSAFE_KEY, defaultValue = "false")
    private val unsafeAllowUnverified: Boolean,
) {

    fun onStartup(@Observes event: StartupEvent) {
        valideer(profile, ConfigProvider.getConfig(), unsafeAllowUnverified)
    }

    companion object {
        const val UNSAFE_KEY = "fbs.outway.unsafe-allow-unverified-tls"

        /**
         * Stabiel, greppable token vooraan de waarschuwing bij een outway-anker dat het
         * certificaat niet verifieert. Ops koppelt hier een alert-regel aan; de waarde mag
         * nooit wijzigen zonder die regel mee te verhuizen, anders valt de detectie stil.
         */
        const val ONGEVERIFIEERD_ALERT_TOKEN = "OUTWAY_TLS_UNVERIFIED"

        private val log = Logger.getLogger(OutwayTlsValidator::class.java.name)

        private const val PREFIX = "quarkus.tls."

        /** Waarden waarmee de hostnaam van de tegenpartij niet meer getoetst wordt. */
        private val VERIFICATIE_UIT = setOf("none", "")

        /**
         * Beoordeelt de `quarkus.tls.*`-configuratie en logt in beide takken één regel, zodat
         * de gekozen modus na elke rollout op de eerste logregels staat in plaats van pas bij
         * de eerste mislukte handshake.
         *
         * [unsafeAllowUnverified] laat een outway-anker toe dat certificaten niet verifieert.
         * Dat is alleen verantwoord wanneer er geen echte persoonsgegevens door de mesh gaan;
         * bij gebruik wordt bij elke boot een WARNING gelogd met [ONGEVERIFIEERD_ALERT_TOKEN].
         * Default false (fail-closed) zodat het nooit per ongeluk aan staat.
         *
         * @throws IllegalStateException als het profiel verificatie vereist, het anker die
         *   opheft, en de onveilige override niet expliciet aan staat.
         */
        fun valideer(profile: String, config: Config, unsafeAllowUnverified: Boolean) {
            val buckets = bucketNamen(config)

            if (OutwayTls.CONFIG_NAAM !in buckets) {
                meldGeenAnker(buckets)

                return
            }

            val gebreken = gebrekenIn(config)

            if (gebreken.isEmpty()) {
                log.info(
                    "Uitgaand outway-verkeer gebruikt de TLS-configuratie " +
                        "'${OutwayTls.CONFIG_NAAM}' als trust-anker.",
                )

                return
            }

            val melding = "de TLS-configuratie '${OutwayTls.CONFIG_NAAM}' verifieert het " +
                "certificaat van de outway niet: ${gebreken.joinToString(", ")}"

            if (profile in PROFIELEN_ZONDER_TLS_EIS) {
                log.info("Outway-TLS: $melding (toegestaan in profiel '$profile')")

                return
            }

            if (unsafeAllowUnverified) {
                log.warning(
                    "$ONGEVERIFIEERD_ALERT_TOKEN: $melding — BEWUST toegestaan in profiel " +
                        "'$profile'. Elke partij die zich op het outway-adres kan invoegen wordt " +
                        "geaccepteerd; alleen verantwoord zonder echte persoonsgegevens.",
                )

                return
            }

            error(
                "$melding. Zet $UNSAFE_KEY=true om dit bewust toe te staan, of haal de knop " +
                    "weg (BIO 13.2.1: verkeer met persoonsgegevens verifieerbaar versleuteld).",
            )
        }

        /**
         * Meldt dat er geen anker is, en noemt de wél aanwezige bucket-namen. Die opsomming ís
         * de diagnose bij een typefout: de operator ziet dan zijn eigen spelling terug naast de
         * naam die de applicatie zoekt.
         */
        private fun meldGeenAnker(buckets: Set<String>) {
            val staart = if (buckets.isEmpty()) {
                "er is geen enkele quarkus.tls-configuratie gezet"
            } else {
                "wel gevonden: ${buckets.sorted().joinToString(", ")}"
            }

            log.info(
                "Uitgaand outway-verkeer valt terug op de JVM-default trust-store; geen " +
                    "TLS-configuratie '${OutwayTls.CONFIG_NAAM}' ($staart).",
            )
        }

        /**
         * De namen van de geconfigureerde `quarkus.tls.<naam>.*`-buckets.
         *
         * Sleutels zonder punt na de prefix (`quarkus.tls.trust-all`) horen bij de
         * DEFAULT-configuratie en zijn dus geen bucketnaam. Een naam mag zelf punten bevatten
         * en staat dan tussen aanhalingstekens in de sleutel; die vorm wordt hier ontdaan van
         * de quotes zodat hij vergelijkbaar is met [OutwayTls.CONFIG_NAAM].
         */
        private fun bucketNamen(config: Config): Set<String> =
            config.propertyNames
                .asSequence()
                .filter { it.startsWith(PREFIX) }
                .mapNotNull { sleutel ->
                    val rest = sleutel.removePrefix(PREFIX)

                    if (rest.startsWith('"')) {
                        rest.drop(1).substringBefore('"').takeIf { it.isNotEmpty() }
                    } else {
                        rest.substringBefore('.').takeIf { it.isNotEmpty() && '.' in rest }
                    }
                }
                .toSet()

        /**
         * De knoppen die het anker ontkrachten, zowel op de bucket zelf als op de
         * default-configuratie: `quarkus.tls.trust-all` geldt ook voor een named bucket die
         * hem niet zelf zet, dus alleen de bucket lezen zou die vlag missen.
         */
        private fun gebrekenIn(config: Config): List<String> {
            val gebreken = mutableListOf<String>()
            val bucket = "$PREFIX${OutwayTls.CONFIG_NAAM}"

            if (leesBoolean(config, "$bucket.trust-all") || leesBoolean(config, "${PREFIX}trust-all")) {
                gebreken += "trust-all staat aan"
            }

            val verificatie = config
                .getOptionalValue("$bucket.hostname-verification-algorithm", String::class.java)
                .orElse(null)

            if (verificatie != null && verificatie.lowercase() in VERIFICATIE_UIT) {
                gebreken += "hostname-verification-algorithm staat op '$verificatie'"
            }

            return gebreken
        }

        private fun leesBoolean(config: Config, sleutel: String): Boolean =
            config.getOptionalValue(sleutel, Boolean::class.javaObjectType).orElse(false)
    }
}
