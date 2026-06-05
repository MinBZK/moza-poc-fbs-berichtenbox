package nl.rijksoverheid.moz.fbs.berichtensessiecache.magazijn

import com.github.tomakehurst.wiremock.WireMockServer
import com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig
import io.quarkus.test.common.QuarkusTestResourceLifecycleManager

class WireMockProfielServiceResource : QuarkusTestResourceLifecycleManager {

    companion object {
        var server: WireMockServer? = null
    }

    override fun start(): Map<String, String> {
        val s = WireMockServer(wireMockConfig().dynamicPort())
        s.start()
        server = s
        return mapOf("quarkus.rest-client.profiel-service.url" to s.baseUrl())
    }

    override fun stop() {
        server?.stop()
        server = null
    }
}
