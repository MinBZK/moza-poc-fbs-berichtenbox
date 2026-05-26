package nl.rijksoverheid.moz.fbs.berichtensessiecache.magazijn

import io.quarkus.rest.client.reactive.QuarkusRestClientBuilder
import jakarta.annotation.PostConstruct
import jakarta.enterprise.context.ApplicationScoped
import nl.rijksoverheid.moz.fbs.common.identificatie.Oin
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
    private lateinit var cachedAfzenders: Map<String, Set<Oin>>

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

            require(instance.afzenders().isNotEmpty()) {
                "Magazijn '$id' heeft geen afzenders geconfigureerd (magazijnen.instances.$id.afzenders)"
            }

            val afzendersOins = instance.afzenders().map { oin ->
                runCatching { Oin(oin) }.getOrElse {
                    throw IllegalStateException("Ongeldige afzender-OIN voor magazijn '$id': '$oin'", it)
                }
            }

            clientsBuilder[id] = createClient(instance)
            namenBuilder[id] = instance.naam().orElse(null)
            afzendersBuilder[id] = afzendersOins.toSet()
        }

        cachedClients = clientsBuilder.toMap()
        cachedNamen = namenBuilder.toMap()
        cachedAfzenders = afzendersBuilder.toMap()

        log.infof("Geconfigureerde magazijnen: %s", cachedClients.keys)
    }

    fun getAllClients(): Map<String, MagazijnClient> = cachedClients

    fun getNaam(magazijnId: String): String? = cachedNamen[magazijnId]

    fun getAfzenders(magazijnId: String): Set<Oin> = cachedAfzenders[magazijnId].orEmpty()

    fun getAlleAfzenders(): Map<String, Set<Oin>> = cachedAfzenders

    private fun createClient(instance: MagazijnenConfig.MagazijnInstance): MagazijnClient {
        return QuarkusRestClientBuilder.newBuilder()
            .baseUri(URI.create(instance.url()))
            .build(MagazijnClient::class.java)
    }
}
