package nl.rijksoverheid.moz.berichtensessiecache.magazijn

import io.quarkus.rest.client.reactive.QuarkusRestClientBuilder
import jakarta.annotation.PostConstruct
import jakarta.enterprise.context.ApplicationScoped
import org.jboss.logging.Logger
import java.net.URI
import java.util.concurrent.ConcurrentHashMap

@ApplicationScoped
class MagazijnClientFactory(
    private val config: MagazijnenConfig,
) {
    private val log = Logger.getLogger(MagazijnClientFactory::class.java)
    private lateinit var cachedClients: Map<String, MagazijnClient>
    private lateinit var cachedNamen: Map<String, String?>

    @PostConstruct
    fun init() {
        require(config.instances().isNotEmpty()) { "Geen magazijnen geconfigureerd" }
        cachedClients = config.instances().map { (id, instance) ->
            runCatching { URI.create(instance.url()) }.getOrElse {
                throw IllegalStateException("Ongeldige URL voor magazijn '$id': ${instance.url()}", it)
            }
            id to createClient(instance)
        }.toMap()
        cachedNamen = config.instances().map { (id, instance) ->
            id to instance.naam().orElse(null)
        }.toMap()
        log.infof("Geconfigureerde magazijnen: %s", cachedClients.keys)
    }

    fun getAllClients(): Map<String, MagazijnClient> = cachedClients

    fun getNaam(magazijnId: String): String? = cachedNamen[magazijnId]

    private fun createClient(instance: MagazijnenConfig.MagazijnInstance): MagazijnClient {
        return QuarkusRestClientBuilder.newBuilder()
            .baseUri(URI.create(instance.url()))
            .build(MagazijnClient::class.java)
    }
}
