package nl.rijksoverheid.moz.fbs.berichtenuitvraag.uitvraag

import com.github.tomakehurst.wiremock.WireMockServer
import com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig
import io.quarkus.test.common.QuarkusTestResourceLifecycleManager

/**
 * Start twee WireMock-magazijnen en wijst de router-config naar hun URLs. De
 * servers zijn statisch beschikbaar voor tests die per-test stubs willen
 * toevoegen of verifiëren.
 *
 * `magazijn` (= `magazijn-a`) is de standaard-mock voor de meeste tests. `magazijn2`
 * (= `magazijn-b`) draait op een ándere poort, zodat een test kan bewijzen dat
 * [MagazijnRouter] op het bron-`magazijnId` uit de cache routeert naar de
 * juiste base-URL (de routeringssleutel is de security-grens: een client kan zelf
 * geen magazijn kiezen).
 */
class WireMockBackendsResource : QuarkusTestResourceLifecycleManager {

    companion object {
        var magazijn: WireMockServer? = null
        var magazijn2: WireMockServer? = null
    }

    override fun start(): Map<String, String> {
        val m = WireMockServer(wireMockConfig().dynamicPort())
        val m2 = WireMockServer(wireMockConfig().dynamicPort())
        m.start()
        m2.start()
        magazijn = m
        magazijn2 = m2

        return mapOf(
            // magazijn-a → `magazijn`, magazijn-b → een aparte mock op een andere
            // poort, zodat per-magazijn routering aantoonbaar verschilt.
            "magazijnen.urls.magazijn-a" to m.baseUrl(),
            "magazijnen.urls.magazijn-b" to m2.baseUrl(),
        )
    }

    override fun stop() {
        magazijn?.stop()
        magazijn2?.stop()
    }
}
