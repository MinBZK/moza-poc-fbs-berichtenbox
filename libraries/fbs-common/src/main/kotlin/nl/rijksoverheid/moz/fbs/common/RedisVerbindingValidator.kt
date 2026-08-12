package nl.rijksoverheid.moz.fbs.common

import io.quarkus.runtime.StartupEvent
import jakarta.enterprise.context.ApplicationScoped
import jakarta.enterprise.event.Observes
import org.eclipse.microprofile.config.inject.ConfigProperty
import java.util.Optional
import java.util.logging.Logger

/**
 * Borgt dat de verbinding met de tijdelijke berichtenopslag (Redis) in productie-achtige
 * profielen versleuteld én geauthenticeerd is. Die opslag bevat berichten van burgers en
 * ondernemers gekoppeld aan hun identificatienummer, en is sinds de sessiecache in-process
 * draait de primaire plek waar die gegevens tijdens een sessie staan — BIO 13.2.1 /
 * AVG art. 32. In `dev` en `test` mag het onversleuteld en zonder wachtwoord, zodat een
 * lokale opstelling zonder certificaten blijft werken.
 *
 * Een dienst zonder Redis-configuratie wordt overgeslagen: dan is er ook geen verbinding
 * om te beveiligen.
 */
@ApplicationScoped
class RedisVerbindingValidator(
    // Optional, niet String met lege default: SmallRye ziet een lege waarde als afwezig en
    // laat een gewone String-injectie dan falen. Diensten zonder Redis mogen niet stukgaan
    // op een property die zij nooit zetten.
    @param:ConfigProperty(name = HOSTS_KEY) private val hosts: Optional<String>,
    @param:ConfigProperty(name = PASSWORD_KEY) private val password: Optional<String>,
    @param:ConfigProperty(name = "quarkus.profile") private val profile: String,
    // BEWUST ONVEILIG; rationale en voorwaarden staan bij [validate].
    @param:ConfigProperty(name = UNSAFE_KEY, defaultValue = "false")
    private val unsafeAllowPlaintext: Boolean,
) {

    fun onStartup(@Observes event: StartupEvent) {
        validate(profile, hosts.orElse(""), password.orElse(""), unsafeAllowPlaintext)
    }

    companion object {
        const val HOSTS_KEY = "quarkus.redis.hosts"
        const val PASSWORD_KEY = "quarkus.redis.password"
        const val UNSAFE_KEY = "fbs.redis.unsafe-allow-plaintext"

        /**
         * Stabiel, greppable token vooraan de waarschuwing bij een bewust onbeveiligde
         * verbinding. Ops koppelt hier een alert-regel aan; de waarde mag nooit wijzigen
         * zonder die regel mee te verhuizen, anders valt de detectie stil.
         */
        const val ONBEVEILIGD_ALERT_TOKEN = "REDIS_UNPROTECTED"

        private val log = Logger.getLogger(RedisVerbindingValidator::class.java.name)

        private val PROFIELEN_ZONDER_EIS = setOf("dev", "test")

        /** Redis over TLS. Het enkelvoudige `redis://` is de onversleutelde variant. */
        private const val TLS_SCHEME = "rediss://"

        /**
         * [unsafeAllowPlaintext] zet de eis BEWUST UIT: de berichten van de ondernemer gaan dan
         * onversleuteld en/of zonder authenticatie over het netwerk. Alleen verantwoord wanneer
         * het netwerk zelf transport-security en afscherming levert (mesh-mTLS plus een
         * netwerk-policy die de opslag afsluit), of wanneer er geen echte persoonsgegevens in
         * staan. Bij gebruik volgt bij elke boot een waarschuwing met [ONBEVEILIGD_ALERT_TOKEN];
         * ops MOET daar een alert-regel op zetten — zonder die regel scrolt de waarschuwing weg
         * en blijft een onbedoeld open opslag onopgemerkt.
         *
         * @throws IllegalStateException als het profiel de eis stelt, de verbinding er niet aan
         *   voldoet en de onveilige override niet expliciet aan staat.
         */
        fun validate(
            profile: String,
            hosts: String,
            password: String = "",
            unsafeAllowPlaintext: Boolean = false,
        ) {
            if (profile in PROFIELEN_ZONDER_EIS || hosts.isBlank()) return

            val gebreken = gebrekenIn(hosts, password)

            if (gebreken.isEmpty()) return

            // De hosts-waarde zelf komt hier nooit in: hij mag een wachtwoord in de
            // userinfo dragen, en deze tekst belandt in de opstartlog.
            val samenvatting = gebreken.joinToString("; ")

            if (unsafeAllowPlaintext) {
                log.warning(
                    "$ONBEVEILIGD_ALERT_TOKEN: de eis op de tijdelijke berichtenopslag is BEWUST uitgeschakeld in " +
                        "profiel '$profile' ($samenvatting). De berichten van de ondernemer zijn daarmee " +
                        "onbeschermd op het netwerk; alleen toegestaan bij mesh-mTLS met netwerk-policy of " +
                        "zonder echte persoonsgegevens.",
                )

                return
            }

            throw IllegalStateException(
                "De verbinding met de tijdelijke berichtenopslag voldoet niet in profiel '$profile': $samenvatting. " +
                    "Zet $HOSTS_KEY op een $TLS_SCHEME-adres en $PASSWORD_KEY via de omgeving.",
            )
        }

        /**
         * Elke host apart beoordelen: `$HOSTS_KEY` mag een lijst zijn, en één onversleuteld
         * adres in die lijst is genoeg om de gegevens alsnog plaintext te laten lopen.
         */
        private fun gebrekenIn(hosts: String, password: String): List<String> {
            val adressen = hosts.split(",").map { it.trim() }.filter { it.isNotEmpty() }
            val gebreken = mutableListOf<String>()
            val zonderTls = adressen.count { !it.startsWith(TLS_SCHEME, ignoreCase = true) }

            if (zonderTls > 0) {
                gebreken += "$zonderTls van de ${adressen.size} adressen in $HOSTS_KEY gebruikt geen $TLS_SCHEME"
            }

            // Een wachtwoord in de host-URL telt óók als authenticatie: die vorm is geldig en
            // wordt in het veld gebruikt. Wel als losse secret aanraden — zie de foutmelding.
            if (password.isBlank() && adressen.none { heeftWachtwoordInUrl(it) }) {
                gebreken += "er is geen wachtwoord ingesteld via $PASSWORD_KEY"
            }

            return gebreken
        }

        /** Herkent `scheme://[user]:wachtwoord@host`; een lege userinfo of alleen een gebruiker telt niet. */
        private fun heeftWachtwoordInUrl(adres: String): Boolean {
            val naSchema = adres.substringAfter("://", "")
            val userinfo = naSchema.substringBefore("@", "")

            return userinfo.substringAfter(":", "").isNotEmpty()
        }
    }
}
