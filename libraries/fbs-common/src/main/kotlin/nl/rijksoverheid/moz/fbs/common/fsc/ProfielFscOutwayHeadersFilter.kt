package nl.rijksoverheid.moz.fbs.common.fsc

import jakarta.ws.rs.client.ClientRequestContext
import jakarta.ws.rs.client.ClientRequestFilter
import org.eclipse.microprofile.config.ConfigProvider

/**
 * Zet de FSC-outway-headers op de Profiel-service-call, mits er een grant-hash geconfigureerd
 * is. Zonder grant-hash doet de filter niets, zodat een deployment dat nog direct met de
 * Profiel-service praat ongewijzigd blijft.
 *
 * De grant-hash komt uit config in plaats van uit een constructor-argument (zoals bij
 * [FscOutwayHeadersFilter], waar elke magazijn-inschrijving een eigen hash heeft): deze filter
 * wordt via `@RegisterProvider` door de rest-client-runtime geïnstantieerd, en die kan geen
 * constructor-argument leveren. De no-arg constructor houdt de instantiatie daarmee
 * onafhankelijk van CDI.
 */
class ProfielFscOutwayHeadersFilter internal constructor(
    private val grantHashProvider: () -> String?,
) : ClientRequestFilter {

    constructor() : this({
        ConfigProvider.getConfig().getOptionalValue(CONFIG_KEY, String::class.java).orElse(null)
    })

    // Eén keer lezen: config is boot-statisch, en dit zit op de hot-path van elke Profiel-call.
    private val grantHash: String? by lazy {
        grantHashProvider()?.trim()?.takeIf { it.isNotEmpty() }
    }

    override fun filter(requestContext: ClientRequestContext) {
        grantHash?.let { FscOutwayHeaders.zet(requestContext, it) }
    }

    companion object {
        /**
         * Eigen prefix, niet onder `quarkus.rest-client.profiel-service.*`: dat is
         * Quarkus-eigen namespace en een eigen sleutel daarin bijplaatsen is fragiel.
         */
        const val CONFIG_KEY = "profiel-service.grant-hash"
    }
}
