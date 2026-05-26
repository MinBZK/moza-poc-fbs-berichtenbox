package nl.rijksoverheid.moz.fbs.berichtensessiecache.magazijn

import io.quarkus.rest.client.reactive.QuarkusRestClientBuilder
import jakarta.annotation.PostConstruct
import jakarta.enterprise.context.ApplicationScoped
import nl.rijksoverheid.moz.fbs.common.OutboundTlsValidator
import nl.rijksoverheid.moz.fbs.common.identificatie.Oin
import org.eclipse.microprofile.config.inject.ConfigProperty
import org.jboss.logging.Logger
import java.net.URI

@ApplicationScoped
class MagazijnClientFactory(
    private val config: MagazijnenConfig,
    @param:ConfigProperty(name = "quarkus.profile") private val profile: String,
) {
    private val log = Logger.getLogger(MagazijnClientFactory::class.java)
    private lateinit var cachedClients: Map<String, MagazijnClient>
    private lateinit var cachedNamen: Map<String, String?>
    private lateinit var cachedAfzenders: Map<String, Set<Oin>>

    // Reverse-index OIN → magazijn-IDs die deze afzender serveren. Vervangt de O(N×M)-scan
    // door O(M) lookups in MagazijnResolver.bepaalMagazijnen (M = opted-in OIN's).
    private lateinit var cachedOinToMagazijnen: Map<Oin, Set<String>>

    @PostConstruct
    fun init() {
        val instances = config.instances()

        require(instances.isNotEmpty()) { "Geen magazijnen geconfigureerd" }

        val clientsBuilder = mutableMapOf<String, MagazijnClient>()
        val namenBuilder = mutableMapOf<String, String?>()
        val afzendersBuilder = mutableMapOf<String, Set<Oin>>()

        instances.forEach { (id, instance) ->
            runCatching { URI.create(instance.url()) }.getOrElse {
                throw IllegalStateException("Ongeldige URL voor magazijn '$id': ${instance.url()}", it)
            }

            // Magazijn-responses bevatten persoonsgegevens (berichten + ontvanger in
            // query-param). BIO 13.2.1 / AVG art. 32 eisen TLS in prod-achtige profielen;
            // dev/test mag http:// voor lokale WireMock-stubs.
            OutboundTlsValidator.requireHttps(
                profile = profile,
                endpoint = instance.url(),
                configKey = "magazijnen.instances.$id.url",
            )

            require(instance.afzenders().isNotEmpty()) {
                "Magazijn '$id' heeft geen afzenders geconfigureerd (magazijnen.instances.$id.afzenders)"
            }

            val afzendersOins = instance.afzenders().mapIndexed { index, oin ->
                runCatching { Oin(oin) }.getOrElse {
                    throw IllegalStateException(
                        "Ongeldige afzender-OIN op positie $index voor magazijn '$id': '$oin'",
                        it,
                    )
                }
            }

            clientsBuilder[id] = createClient(instance)
            namenBuilder[id] = instance.naam().orElse(null)
            afzendersBuilder[id] = afzendersOins.toSet()
        }

        cachedClients = clientsBuilder.toMap()
        cachedNamen = namenBuilder.toMap()
        cachedAfzenders = afzendersBuilder.toMap()

        cachedOinToMagazijnen = buildMap<Oin, MutableSet<String>> {
            cachedAfzenders.forEach { (id, oins) ->
                oins.forEach { oin -> getOrPut(oin) { mutableSetOf() }.add(id) }
            }
        }

        log.infof("Geconfigureerde magazijnen: %s", cachedClients.keys)
    }

    fun getAllClients(): Map<String, MagazijnClient> = cachedClients

    fun getNaam(magazijnId: String): String? = cachedNamen[magazijnId]

    fun getAfzenders(magazijnId: String): Set<Oin> = cachedAfzenders[magazijnId].orEmpty()

    fun getAlleAfzenders(): Map<String, Set<Oin>> = cachedAfzenders

    /**
     * Reverse-index lookup: voor een opted-in afzender-OIN levert deze de magazijn-IDs
     * die die afzender serveren. Lege set als de OIN niet bij een geconfigureerd magazijn
     * hoort.
     */
    fun magazijnenVoorAfzender(oin: Oin): Set<String> = cachedOinToMagazijnen[oin].orEmpty()

    // `protected open` zodat unit-tests een subclass kunnen maken die niet de Quarkus-CDI
    // REST-client-builder triggert (die buiten @QuarkusTest ArC-context faalt).
    protected open fun createClient(instance: MagazijnenConfig.MagazijnInstance): MagazijnClient {
        return QuarkusRestClientBuilder.newBuilder()
            .baseUri(URI.create(instance.url()))
            .build(MagazijnClient::class.java)
    }
}
