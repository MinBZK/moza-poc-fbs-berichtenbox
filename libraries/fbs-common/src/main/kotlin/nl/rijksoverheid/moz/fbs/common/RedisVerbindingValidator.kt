package nl.rijksoverheid.moz.fbs.common

import io.quarkus.runtime.StartupEvent
import jakarta.enterprise.context.ApplicationScoped
import jakarta.enterprise.event.Observes
import org.eclipse.microprofile.config.Config
import org.eclipse.microprofile.config.ConfigProvider
import org.eclipse.microprofile.config.inject.ConfigProperty
import java.net.URI
import java.net.URISyntaxException
import java.util.logging.Logger

/**
 * Borgt dat de verbinding met de tijdelijke berichtenopslag (Redis) in productie-achtige
 * profielen versleuteld, geverifieerd én geauthenticeerd is. Die opslag bevat berichten van
 * burgers en ondernemers gekoppeld aan hun identificatienummer, en is sinds de sessiecache
 * in-process draait de primaire plek waar die gegevens tijdens een sessie staan — BIO 13.2.1 /
 * AVG art. 32. In `dev` en `test` mag het onversleuteld en zonder wachtwoord, zodat een lokale
 * opstelling zonder certificaten blijft werken.
 *
 * `rediss://` alleen is niet genoeg: Quarkus zet standaard géén hostnaam-verificatie aan
 * (`quarkus.redis.tls.hostname-verification-algorithm` staat op `NONE`) en kent een losse
 * `trust-all`-knop. Zonder die twee mee te nemen versleutelt de verbinding wel, maar staat
 * niet vast dát het de opslag is die aan de andere kant zit.
 *
 * Een dienst zonder Redis-configuratie wordt overgeslagen: dan is er geen verbinding om te
 * beveiligen.
 */
@ApplicationScoped
class RedisVerbindingValidator(
    @param:ConfigProperty(name = "quarkus.profile") private val profile: String,
    // BEWUST ONVEILIG; rationale en voorwaarden bij [Companion.valideerAlleClients].
    @param:ConfigProperty(name = UNSAFE_KEY, defaultValue = "false")
    private val unsafeAllowPlaintext: Boolean,
) {

    fun onStartup(@Observes event: StartupEvent) {
        valideerAlleClients(profile, ConfigProvider.getConfig(), unsafeAllowPlaintext)
    }

    companion object {
        const val UNSAFE_KEY = "fbs.redis.unsafe-allow-plaintext"

        /**
         * Stabiel, greppable token vooraan de waarschuwing bij een bewust onversleutelde
         * verbinding. Ops koppelt hier een alert-regel aan; de waarde mag nooit wijzigen
         * zonder die regel mee te verhuizen, anders valt de detectie stil.
         */
        const val ONBEVEILIGD_ALERT_TOKEN = "REDIS_UNPROTECTED"

        private val log = Logger.getLogger(RedisVerbindingValidator::class.java.name)

        /** Redis over TLS. Het enkelvoudige `redis://` is de onversleutelde variant. */
        private const val TLS_SCHEME = "rediss://"

        /** `quarkus.redis.hosts` (default client) en `quarkus.redis.<naam>.hosts` (named clients). */
        // De clientnaam mag zelf punten bevatten: een env-var als QUARKUS_REDIS__CACHE_A__HOSTS
        // komt als `quarkus.redis."cache.a".hosts` in de configuratie terecht. Een patroon dat
        // punten verbiedt zou zo'n client niet ontdekken en hem dus ongecontroleerd laten.
        private val HOSTS_PATROON = Regex("""^quarkus\.redis\.(?:(.+?)\.)?hosts$""")

        /** Zelfde vorm voor de programmatische variant; die bestaat óók per client. */
        private val PROVIDER_PATROON = Regex("""^quarkus\.redis\.(?:(.+?)\.)?hosts-provider-name$""")

        /**
         * Beoordeelt elke geconfigureerde Redis-client. Named clients zijn vandaag ongebruikt,
         * maar een garantie die alleen voor de default client geldt is er geen: een tweede
         * client naar dezelfde opslag zou de eis stilzwijgend omzeilen.
         */
        fun valideerAlleClients(profile: String, config: Config, unsafeAllowPlaintext: Boolean = false) {
            if (profile in PROFIELEN_ZONDER_TLS_EIS) {
                log.info("Verbindingscheck op de berichtenopslag overgeslagen voor profiel '$profile' (dev/test mag onbeschermd)")

                return
            }

            val clients = config.propertyNames.mapNotNull { HOSTS_PATROON.find(it)?.groupValues?.get(1) }.toSortedSet()

            // Een client kan ook zonder `hosts` bestaan, via een programmatische hosts-provider —
            // per client, dus ook op een named client. Die waarde is hier niet te beoordelen, dus
            // hij telt als gebrek in plaats van als "geen opslag": anders zwijgt de check over een
            // verbinding die wél bestaat.
            // Een provider telt alleen als de client er ook echt op terugvalt: `hosts` gaat voor,
            // en een lege waarde is geen provider. Anders zou een achtergebleven configmap-key de
            // start blokkeren terwijl de client hem negeert.
            val providers = config.propertyNames
                .filter { PROVIDER_PATROON.matches(it) }
                .filter { config.getOptionalValue(it, String::class.java).orElse("").isNotBlank() }
                .filterNot { sleutel ->
                    val naam = PROVIDER_PATROON.find(sleutel)?.groupValues?.get(1).orEmpty()

                    adresVan(config, prefixVan(naam)).isNotBlank()
                }

            if (providers.isNotEmpty()) {
                throw IllegalStateException(
                    "${providers.sorted()} levert het opslag-adres buiten de configuratie om in profiel " +
                        "'$profile'; de verbinding is daarmee niet te beoordelen op versleuteling en authenticatie.",
                )
            }

            if (clients.isEmpty()) {
                log.info("Geen berichtenopslag geconfigureerd; verbindingscheck niet van toepassing")

                return
            }

            clients.forEach { naam -> valideerClient(profile, config, naam, unsafeAllowPlaintext) }
        }

        private fun prefixVan(naam: String) = if (naam.isEmpty()) "quarkus.redis" else "quarkus.redis.$naam"

        private fun adresVan(config: Config, prefix: String) =
            config.getOptionalValue("$prefix.hosts", String::class.java).orElse("")

        private fun valideerClient(profile: String, config: Config, naam: String, unsafeAllowPlaintext: Boolean) {
            val prefix = prefixVan(naam)

            // Een benoemde TLS-configuratie vervangt het hele `<prefix>.tls.*`-blok: de client
            // leest dan die bucket. De legacy-keys beoordelen zou een garantie geven over
            // instellingen die niet gebruikt worden.
            val tlsNaam = config.getOptionalValue("$prefix.tls-configuration-name", String::class.java).orElse("")
            val tlsPrefix = if (tlsNaam.isEmpty()) "$prefix.tls" else "quarkus.tls.$tlsNaam"

            validate(
                profile = profile,
                hosts = config.getOptionalValue("$prefix.hosts", String::class.java).orElse(""),
                password = config.getOptionalValue("$prefix.password", String::class.java).orElse(""),
                // De globale registry-knop telt mee: de client zet trust-all zodra één van beide
                // aanstaat. Alleen de client-eigen knop lezen laat `quarkus.tls.trust-all=true`
                // ongemerkt elk certificaat accepteren.
                trustAll = leesBoolean(config, "$tlsPrefix.trust-all") ||
                    leesBoolean(config, "quarkus.tls.trust-all"),
                tlsPrefix = tlsPrefix,
                hostnameVerificatie = config
                    .getOptionalValue("$tlsPrefix.hostname-verification-algorithm", String::class.java)
                    .orElse("NONE"),
                // `tls.enabled` geldt alleen zónder benoemde TLS-configuratie. Met een naam leest
                // de client die sleutel niet en bepaalt uitsluitend het schema of er versleuteld
                // wordt — hem daar tóch honoreren zou een `redis://`-adres als versleuteld laten
                // doorgaan terwijl de verbinding plaintext is.
                tlsIngeschakeld = tlsNaam.isEmpty() && leesBoolean(config, "$prefix.tls.enabled"),
                unsafeAllowPlaintext = unsafeAllowPlaintext,
                configPrefix = prefix,
            )
        }

        private fun leesBoolean(config: Config, key: String) =
            config.getOptionalValue(key, Boolean::class.java).orElse(false)

        /**
         * [unsafeAllowPlaintext] zet uitsluitend de **transport**-eisen uit: versleuteling en
         * peer-verificatie. Alleen verantwoord wanneer het netwerk die zelf levert (mesh-mTLS).
         * De authenticatie-eis blijft altijd staan — binnen een mesh zónder wachtwoord kan elke
         * pod die het netwerk bereikt alle berichten van alle gebruikers lezen, en daar helpt
         * mesh-mTLS niet tegen. Bij gebruik volgt bij elke boot een waarschuwing met
         * [ONBEVEILIGD_ALERT_TOKEN]; ops MOET daar een alert-regel op zetten — zonder die regel
         * scrolt de waarschuwing weg en blijft een onbedoeld open opslag onopgemerkt.
         *
         * @throws IllegalStateException als het profiel de eisen stelt en de verbinding er niet
         *   aan voldoet.
         */
        @Suppress("LongParameterList")
        fun validate(
            profile: String,
            hosts: String,
            password: String = "",
            trustAll: Boolean = false,
            hostnameVerificatie: String = "NONE",
            tlsIngeschakeld: Boolean = false,
            unsafeAllowPlaintext: Boolean = false,
            configPrefix: String = "quarkus.redis",
            // Waar de TLS-instellingen vandaan komen: het client-eigen blok, of de bucket van een
            // benoemde TLS-configuratie. De melding moet de sleutel noemen die de client écht
            // leest — anders zet de operator de andere op false en blijft de boot falen.
            tlsPrefix: String = "$configPrefix.tls",
        ) {
            if (profile in PROFIELEN_ZONDER_TLS_EIS) return

            // Een lege waarde op een client die wél geconfigureerd is: de Redis-client weigert
            // hier zelf ("Redis host not configured"), maar stil overslaan zou deze check laten
            // zwijgen over een client die hij nota bene zelf ontdekt heeft.
            if (hosts.isBlank()) {
                throw IllegalStateException(meldingVoor(profile, listOf("$configPrefix.hosts is leeg")))
            }

            val adressen = hosts.split(",").map { it.trim() }.filter { it.isNotEmpty() }

            // Een waarde die alleen uit scheidingstekens bestaat is niet blank, maar levert nul
            // adressen op. Zonder deze afslag zouden alle per-adres-checks leeg-waar zijn en zou
            // een onbeoordeelde configuratie als "voldoet" door de guard komen.
            if (adressen.isEmpty()) {
                throw IllegalStateException(
                    meldingVoor(profile, listOf("$configPrefix.hosts bevat geen enkel adres")),
                )
            }

            val transportGebreken =
                transportGebrekenIn(adressen, trustAll, hostnameVerificatie, tlsIngeschakeld, configPrefix, tlsPrefix)
            val authGebreken = authGebrekenIn(adressen, password, configPrefix)

            // De hosts-waarde zelf komt hier nooit in: hij mag een wachtwoord in de userinfo
            // dragen, en deze tekst belandt in de opstartlog.
            if (authGebreken.isNotEmpty()) {
                // Transport-gebreken alleen noemen als ze nog gelden: staat de klep open, dan
                // zou "zet hosts op rediss://" de operator naar het verkeerde probleem sturen.
                val teMelden = if (unsafeAllowPlaintext) authGebreken else authGebreken + transportGebreken

                throw IllegalStateException(meldingVoor(profile, teMelden))
            }

            if (transportGebreken.isEmpty()) {
                log.info(
                    "Verbinding met de berichtenopslag ($configPrefix) is versleuteld, geverifieerd en " +
                        "geauthenticeerd in profiel '$profile'",
                )

                return
            }

            if (!unsafeAllowPlaintext) {
                throw IllegalStateException(meldingVoor(profile, transportGebreken))
            }

            log.warning(
                "$ONBEVEILIGD_ALERT_TOKEN: de transport-eis op de tijdelijke berichtenopslag ($configPrefix) is " +
                    "BEWUST uitgeschakeld in profiel '$profile' (${transportGebreken.joinToString("; ")}). De " +
                    "berichten van de ondernemer zijn daarmee onbeschermd op het netwerk; alleen toegestaan " +
                    "wanneer het netwerk zelf transport-security levert (mesh-mTLS).",
            )
        }

        // Geen algemene slotzin met een advies: elk gebrek noemt zelf de sleutel die aangepast
        // moet worden. Een vaste zin zou ook om TLS vragen wanneer alleen het wachtwoord
        // ontbreekt, en dan stuurt de melding de operator naar het verkeerde probleem.
        private fun meldingVoor(profile: String, gebreken: List<String>) =
            "De verbinding met de tijdelijke berichtenopslag voldoet niet in profiel '$profile': " +
                gebreken.joinToString("; ") + "."

        /**
         * Versleuteling én peer-verificatie. `rediss://` zonder hostnaam-verificatie laat elke
         * partij met een willekeurig geldig certificaat zich als de opslag voordoen; met
         * `trust-all` volstaat élk certificaat.
         */
        @Suppress("LongParameterList")
        private fun transportGebrekenIn(
            adressen: List<String>,
            trustAll: Boolean,
            hostnameVerificatie: String,
            tlsIngeschakeld: Boolean,
            configPrefix: String,
            tlsPrefix: String,
        ): List<String> {
            val gebreken = mutableListOf<String>()
            // Hoofdlettergevoelig, gelijk aan wat Vert.x accepteert: `REDISS://` weigert de
            // runtime alsnog, dus hier toelaten zou een garantie suggereren die hier niet ligt.
            // De losse `tls.enabled`-schakelaar zet TLS voor álle adressen aan; dan zegt het
            // schema niets meer en zou weigeren een werkende opstelling blokkeren.
            val zonderTls = if (tlsIngeschakeld) 0 else adressen.count { !it.startsWith(TLS_SCHEME) }

            if (zonderTls > 0) {
                gebreken += "$zonderTls van de ${adressen.size} adressen in $configPrefix.hosts gebruikt geen $TLS_SCHEME"
            }

            if (trustAll) {
                gebreken += "$tlsPrefix.trust-all (of quarkus.tls.trust-all) staat aan, " +
                    "waardoor elk certificaat wordt geaccepteerd"
            }

            if (hostnameVerificatie.equals("NONE", ignoreCase = true)) {
                gebreken += "$tlsPrefix.hostname-verification-algorithm staat op NONE, " +
                    "waardoor niet wordt gecontroleerd of het certificaat bij de opslag hoort"
            }

            return gebreken
        }

        /**
         * Authenticatie hoort uit een losse secret te komen. Een wachtwoord in het adres is
         * technisch geldig, maar dan is het geen secret-veld meer: het staat in de
         * deployment-omgeving, is zichtbaar in pod-beschrijvingen, en de Redis-client logt de
         * volledige URI inclusief inloggegevens wanneer hij hem niet kan herbouwen.
         */
        private fun authGebrekenIn(adressen: List<String>, password: String, configPrefix: String): List<String> {
            val gebreken = mutableListOf<String>()
            val metWachtwoordInAdres = adressen.count { heeftWachtwoordInUrl(it) }

            if (metWachtwoordInAdres > 0) {
                gebreken += "$metWachtwoordInAdres van de ${adressen.size} adressen draagt inloggegevens in de URL; " +
                    "gebruik $configPrefix.password"
            }

            if (password.isBlank() && metWachtwoordInAdres < adressen.size) {
                gebreken += "er is geen wachtwoord ingesteld via $configPrefix.password"
            }

            return gebreken
        }

        /**
         * Parseert als URI in plaats van op `@` te splitsen: een `@` in een query-parameter
         * (`?client=a@b`, een ondersteunde vorm) zou anders als inloggegevens tellen. Een adres
         * dat niet als URI te lezen is, telt niet als geauthenticeerd — de runtime weigert het
         * even goed.
         */
        private fun heeftWachtwoordInUrl(adres: String): Boolean = try {
            URI(adres).userInfo?.substringAfter(":", "")?.isNotEmpty() ?: false
        } catch (_: URISyntaxException) {
            false
        }
    }
}
