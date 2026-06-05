package nl.rijksoverheid.moz.fbs.berichtenuitvraag.uitvraag

import com.github.tomakehurst.wiremock.WireMockServer
import com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig
import io.quarkus.test.common.QuarkusTestResourceLifecycleManager

/**
 * Start twee WireMock-magazijnen en wijst de register-config naar hun URLs. De
 * servers zijn statisch beschikbaar voor tests die per-test stubs willen
 * toevoegen of verifiëren.
 *
 * `magazijn` (= [OIN_A]) is de standaard-mock voor de meeste tests. `magazijn2`
 * (= [OIN_B]) draait op een ándere poort, zodat een test kan bewijzen dat
 * [MagazijnRouter] op het bron-`magazijnId` uit de cache routeert naar de
 * juiste base-URL (de routeringssleutel is de security-grens: een client kan zelf
 * geen magazijn kiezen).
 */
class WireMockBackendsResource : QuarkusTestResourceLifecycleManager {

    companion object {
        // magazijnId == afzender-OIN (register-conventie); gedeeld door tests die
        // patch/delete/bijlage-routes met een magazijnId-parameter aanroepen.
        const val OIN_A = "00000001003214345000"
        const val OIN_B = "00000001823288444000"

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
            // OIN A → `magazijn`, OIN B → een aparte mock op een andere poort,
            // zodat per-magazijn routering aantoonbaar verschilt.
            "magazijnen.\"$OIN_A\".url" to m.baseUrl(),
            "magazijnen.\"$OIN_B\".url" to m2.baseUrl(),
        )
    }

    override fun stop() {
        magazijn?.stop()
        magazijn2?.stop()
    }
}
