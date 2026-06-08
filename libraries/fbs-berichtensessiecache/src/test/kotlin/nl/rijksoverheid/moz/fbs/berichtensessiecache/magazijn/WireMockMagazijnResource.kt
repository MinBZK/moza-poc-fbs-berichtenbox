package nl.rijksoverheid.moz.fbs.berichtensessiecache.magazijn

import com.github.tomakehurst.wiremock.WireMockServer
import com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig
import io.quarkus.test.common.QuarkusTestResourceLifecycleManager

class WireMockMagazijnResource : QuarkusTestResourceLifecycleManager {

    companion object {
        // magazijnId == afzender-OIN (register-conventie); gedeeld door tests die
        // resolver-resultaten of client-map-keys asserten.
        const val OIN_A = "00000001003214345000"
        const val OIN_B = "00000001823288444000"

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
            "magazijnen.\"$OIN_A\".url" to a.baseUrl(),
            "magazijnen.\"$OIN_A\".naam" to "WireMock Magazijn A",
            "magazijnen.\"$OIN_B\".url" to b.baseUrl(),
            "magazijnen.\"$OIN_B\".naam" to "WireMock Magazijn B",
        )
    }

    override fun stop() {
        serverA?.stop()
        serverB?.stop()
    }
}
