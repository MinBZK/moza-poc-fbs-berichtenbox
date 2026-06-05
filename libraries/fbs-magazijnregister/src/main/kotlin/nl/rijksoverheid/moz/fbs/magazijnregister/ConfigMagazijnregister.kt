package nl.rijksoverheid.moz.fbs.magazijnregister

import io.quarkus.runtime.StartupEvent
import jakarta.annotation.PostConstruct
import jakarta.enterprise.context.ApplicationScoped
import jakarta.enterprise.event.Observes
import nl.rijksoverheid.moz.fbs.common.OutboundTlsValidator
import nl.rijksoverheid.moz.fbs.common.identificatie.Oin
import org.eclipse.microprofile.config.inject.ConfigProperty
import org.jboss.logging.Logger
import java.net.URI

/**
 * Config-backed [Magazijnregister]: leest `magazijnen."<OIN>".{url,naam}` en
 * valideert fail-fast bij opstart — een ongeldige OIN-key, ongeldige of
 * niet-versleutelde URL of een leeg register hoort de boot te blokkeren,
 * niet pas een runtime-fout bij het eerste verkeer te veroorzaken.
 */
@ApplicationScoped
internal class ConfigMagazijnregister(
    private val config: MagazijnregisterConfig,
    @param:ConfigProperty(name = "quarkus.profile") private val profile: String,
) : Magazijnregister {

    private val log = Logger.getLogger(ConfigMagazijnregister::class.java)
    private lateinit var inschrijvingen: Map<Oin, Magazijninschrijving>

    @PostConstruct
    fun init() {
        val entries = config.inschrijvingen()

        require(entries.isNotEmpty()) { "Geen magazijnen geconfigureerd (magazijnen.\"<OIN>\".url)" }

        inschrijvingen = entries.entries.associate { (key, entry) ->
            // Specifiek IllegalArgumentException (validatiefout uit de Oin-constructor):
            // een Error (bv. NoClassDefFoundError op cold start) moet doorvloeien naar
            // de container-startup en niet als config-fout gerapporteerd worden.
            val oin = try {
                Oin(key)
            } catch (ex: IllegalArgumentException) {
                throw IllegalStateException(
                    "Ongeldige OIN-key in magazijnregister-config: '$key' (magazijnen.\"$key\".url)",
                    ex,
                )
            }

            oin to Magazijninschrijving(oin = oin, url = parseUrl(oin, entry.url()), naam = entry.naam().orElse(null))
        }
    }

    /**
     * Het observeren van [StartupEvent] dwingt bean-instantiatie — en daarmee de
     * [init]-validatie — af tijdens boot, ook als geen enkele consumer het register
     * bij opstart aanraakt.
     */
    internal fun bijOpstart(@Observes startup: StartupEvent) {
        log.infof("Magazijnregister actief met %d inschrijving(en): %s", inschrijvingen.size, inschrijvingen.keys)
    }

    override fun alle(): Collection<Magazijninschrijving> = inschrijvingen.values

    override fun voorOin(oin: Oin): Magazijninschrijving? = inschrijvingen[oin]

    private fun parseUrl(oin: Oin, url: String): URI {
        val configKey = "magazijnen.\"${oin.waarde}\".url"

        val uri = try {
            URI.create(url)
        } catch (ex: IllegalArgumentException) {
            throw IllegalStateException("$configKey is geen geldige URI: '$url'", ex)
        }

        check(uri.scheme == "http" || uri.scheme == "https") {
            "$configKey moet een http(s)-URL zijn, was: '$url'"
        }

        // Magazijn-verkeer bevat persoonsgegevens (berichten + ontvanger in query-param).
        // BIO 13.2.1 / AVG art. 32 eisen TLS in prod-achtige profielen; dev/test mag
        // http:// voor lokale WireMock-stubs.
        OutboundTlsValidator.requireHttps(profile = profile, endpoint = url, configKey = configKey)

        return uri
    }
}
