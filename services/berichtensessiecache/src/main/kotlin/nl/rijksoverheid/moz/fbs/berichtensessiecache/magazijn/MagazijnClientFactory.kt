package nl.rijksoverheid.moz.fbs.berichtensessiecache.magazijn

import io.quarkus.rest.client.reactive.QuarkusRestClientBuilder
import jakarta.annotation.PostConstruct
import jakarta.enterprise.context.ApplicationScoped
import nl.rijksoverheid.moz.fbs.common.OutboundTlsValidator
import nl.rijksoverheid.moz.fbs.common.identificatie.Oin
import org.eclipse.microprofile.config.inject.ConfigProperty
import org.jboss.logging.Logger
import java.net.URI
import java.util.concurrent.TimeUnit

@ApplicationScoped
class MagazijnClientFactory(
    private val config: MagazijnenConfig,
    @param:ConfigProperty(name = "quarkus.profile") private val profile: String,
    // Connect/read-timeout op de magazijn-client: zonder read-timeout blokkeert een hangend
    // magazijn de socket onbegrensd (de Mutiny `ifNoItem` faalt de Uni maar interrupt de
    // geblokkeerde call niet). De invariant read-timeout > query-timeout wordt afgedwongen in
    // BerichtensessiecacheService.valideerTimeouts(); rationale + tuning in application.properties.
    // Eigen prefix (niet `magazijnen.`): SmallRye weigert losse properties onder een
    // @ConfigMapping-prefix (SRCFG00050).
    @param:ConfigProperty(name = "magazijn-client.connect-timeout-ms", defaultValue = "2000")
    private val connectTimeoutMs: Long,
    @param:ConfigProperty(name = READ_TIMEOUT_MS_PROPERTY, defaultValue = READ_TIMEOUT_MS_DEFAULT)
    private val readTimeoutMs: Long,
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
            try {
                URI.create(instance.url())
            } catch (ex: IllegalArgumentException) {
                throw IllegalStateException("Ongeldige URL voor magazijn '$id': ${instance.url()}", ex)
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

            // Specifiek IllegalArgumentException vangen (validatie-fout in Oin-constructor)
            // i.p.v. brede runCatching: een Error (bv. NoClassDefFoundError op cold-start)
            // moet doorvloeien naar de container-startup en niet als config-fout
            // gerapporteerd worden.
            val afzendersOins = instance.afzenders().mapIndexed { index, oin ->
                try {
                    Oin(oin)
                } catch (ex: IllegalArgumentException) {
                    throw IllegalStateException(
                        "Ongeldige afzender-OIN op positie $index voor magazijn '$id': '$oin'",
                        ex,
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
        }.mapValues { (_, magazijnen) -> magazijnen.toSet() }

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
            .connectTimeout(connectTimeoutMs, TimeUnit.MILLISECONDS)
            .readTimeout(readTimeoutMs, TimeUnit.MILLISECONDS)
            .build(MagazijnClient::class.java)
    }

    companion object {
        // Gedeeld met BerichtensessiecacheService.valideerTimeouts(), dat read > query
        // kruisvalideert. Eén bron voor sleutel + default zodat de gevalideerde waarde niet
        // kan afwijken van de waarde die deze factory daadwerkelijk op de socket toepast.
        const val READ_TIMEOUT_MS_PROPERTY = "magazijn-client.read-timeout-ms"
        const val READ_TIMEOUT_MS_DEFAULT = "12000"
    }
}
