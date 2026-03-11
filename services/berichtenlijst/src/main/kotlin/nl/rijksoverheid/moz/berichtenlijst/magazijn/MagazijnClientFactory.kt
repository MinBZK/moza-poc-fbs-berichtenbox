package nl.rijksoverheid.moz.berichtenlijst.magazijn

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
    private val clients = ConcurrentHashMap<String, MagazijnClient>()

    @PostConstruct
    fun init() {
        require(config.instances().isNotEmpty()) { "Geen magazijnen geconfigureerd" }
        log.infof("Geconfigureerde magazijnen: %s", config.instances().keys)
    }

    fun getAllClients(): Map<String, MagazijnClient> {
        return config.instances().map { (id, instance) ->
            id to clients.computeIfAbsent(id) { createClient(instance) }
        }.toMap()
    }

    fun getNaam(magazijnId: String): String? =
        config.instances()[magazijnId]?.naam()?.orElse(null)

    private fun createClient(instance: MagazijnenConfig.MagazijnInstance): MagazijnClient {
        return QuarkusRestClientBuilder.newBuilder()
            .baseUri(URI.create(instance.url()))
            .build(MagazijnClient::class.java)
    }
}
