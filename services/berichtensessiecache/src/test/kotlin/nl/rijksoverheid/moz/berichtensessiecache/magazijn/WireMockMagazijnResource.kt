package nl.rijksoverheid.moz.berichtensessiecache.magazijn

import com.github.tomakehurst.wiremock.WireMockServer
import com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig
import io.quarkus.test.common.QuarkusTestResourceLifecycleManager

class WireMockMagazijnResource : QuarkusTestResourceLifecycleManager {

    companion object {
        var serverA: WireMockServer? = null
        var serverB: WireMockServer? = null
    }

    override fun start(): Map<String, String> {
        val a = WireMockServer(wireMockConfig().dynamicPort())
        val b = WireMockServer(wireMockConfig().dynamicPort())
        a.start()
        b.start()
        serverA = a
        serverB = b

        return mapOf(
            "magazijnen.instances.magazijn-a.url" to a.baseUrl(),
            "magazijnen.instances.magazijn-a.naam" to "WireMock Magazijn A",
            "magazijnen.instances.magazijn-b.url" to b.baseUrl(),
            "magazijnen.instances.magazijn-b.naam" to "WireMock Magazijn B",
        )
    }

    override fun stop() {
        serverA?.stop()
        serverB?.stop()
    }
}
