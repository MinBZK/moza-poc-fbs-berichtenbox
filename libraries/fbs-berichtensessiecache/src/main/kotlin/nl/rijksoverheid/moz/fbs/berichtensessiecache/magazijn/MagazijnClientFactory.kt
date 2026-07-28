package nl.rijksoverheid.moz.fbs.berichtensessiecache.magazijn

import io.quarkus.rest.client.reactive.QuarkusRestClientBuilder
import jakarta.annotation.PostConstruct
import jakarta.enterprise.context.ApplicationScoped
import nl.rijksoverheid.moz.fbs.common.fsc.FscOutwayHeadersFilter
import nl.rijksoverheid.moz.fbs.common.identificatie.Oin
import nl.rijksoverheid.moz.fbs.magazijnregister.Magazijninschrijving
import nl.rijksoverheid.moz.fbs.magazijnregister.Magazijnregister
import org.eclipse.microprofile.config.inject.ConfigProperty
import org.jboss.logging.Logger
import java.util.concurrent.TimeUnit

@ApplicationScoped
internal class MagazijnClientFactory(
    private val register: Magazijnregister,
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

    @PostConstruct
    fun init() {
        // Het register valideert zelf fail-fast bij boot (OIN-keys, URL-geldigheid, TLS,
        // leeg register); hier resteert het bouwen van een REST-client per inschrijving.
        // magazijnId == oin.waarde — de identiteitsconventie uit het register.
        val inschrijvingen = register.alle()

        cachedClients = inschrijvingen.associate { it.oin.waarde to createClient(it) }
        cachedNamen = inschrijvingen.associate { it.oin.waarde to it.naam }

        log.infof("Geconfigureerde magazijnen: %s", cachedClients.keys)
    }

    fun getAllClients(): Map<String, MagazijnClient> = cachedClients

    fun getNaam(magazijnId: String): String? = cachedNamen[magazijnId]

    /**
     * Magazijn-set voor een opted-in afzender-OIN. Door de 1:1-koppeling OIN↔magazijn
     * is dit een register-lookup: bekend → singleton-set met de OIN-waarde als
     * magazijn-id, onbekend (drift) → lege set.
     */
    fun magazijnenVoorAfzender(oin: Oin): Set<String> =
        if (register.voorOin(oin) != null) setOf(oin.waarde) else emptySet()

    // `protected open` zodat unit-tests een subclass kunnen maken die niet de Quarkus-CDI
    // REST-client-builder triggert (die buiten @QuarkusTest ArC-context faalt).
    protected open fun createClient(inschrijving: Magazijninschrijving): MagazijnClient {
        val builder = QuarkusRestClientBuilder.newBuilder()
            .baseUri(inschrijving.url)
            .connectTimeout(connectTimeoutMs, TimeUnit.MILLISECONDS)
            .readTimeout(readTimeoutMs, TimeUnit.MILLISECONDS)

        fscFilterVoor(inschrijving)?.let { builder.register(it) }

        return builder.build(MagazijnClient::class.java)
    }

    companion object {
        // Gedeeld met BerichtensessiecacheService.valideerTimeouts(), dat read > query
        // kruisvalideert. Eén bron voor sleutel + default zodat de gevalideerde waarde niet
        // kan afwijken van de waarde die deze factory daadwerkelijk op de socket toepast.
        const val READ_TIMEOUT_MS_PROPERTY = "magazijn-client.read-timeout-ms"
        const val READ_TIMEOUT_MS_DEFAULT = "12000"

        // Losgetrokken van createClient() zodat de registratie-beslissing unit-testbaar is
        // zonder de Quarkus-REST-client-builder (die buiten @QuarkusTest ArC-context faalt).
        // Niet elk magazijn draait al achter een FSC-outway; grantHash blijft optioneel
        // totdat de volledige federatie is overgestapt.
        internal fun fscFilterVoor(inschrijving: Magazijninschrijving): FscOutwayHeadersFilter? =
            inschrijving.grantHash?.let { FscOutwayHeadersFilter(it) }
    }
}
