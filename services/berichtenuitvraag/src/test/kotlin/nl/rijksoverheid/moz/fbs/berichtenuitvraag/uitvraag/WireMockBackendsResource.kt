package nl.rijksoverheid.moz.fbs.berichtenuitvraag.uitvraag

import com.github.tomakehurst.wiremock.WireMockServer
import com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig
import io.quarkus.test.common.QuarkusTestResourceLifecycleManager

/**
 * Eén gedeelde WireMock-fixture voor de tests die echte WireMock-backends nodig hebben
 * (keten-E2E + endpoint-/routerings-tests): een Profiel-service en twee magazijnen
 * ([OIN_A], [OIN_B]). Eén plek die de bijbehorende config-sleutels
 * zet (`quarkus.rest-client.profiel-service.url` en `magazijnen."<OIN>".url`), zodat
 * géén twee test-resources dezelfde sleutels claimen. Dat laatste veroorzaakte eerder
 * volgorde-afhankelijke CI-flakiness: een tweede resource overschreef dezelfde sleutels
 * JVM-breed, waardoor een testklasse andere WireMock-servers stubde dan de applicatie
 * raakte → HTTP 404 → 0 opgehaalde berichten.
 *
 * `magazijnA` (= [OIN_A]) is de standaard-mock. `magazijnB` (= [OIN_B]) draait op een
 * ándere poort, zodat een test kan bewijzen dat [MagazijnRouter] op het bron-`magazijnId`
 * uit de cache naar de juiste base-URL routeert (de routeringssleutel is de security-grens:
 * een client kan zelf geen magazijn kiezen). `profiel` wordt alleen door de echte-keten-
 * E2E gebruikt; de mock-sessiecache-tests laten hem ongestubd (zij bevragen Profiel niet).
 *
 * Haak altijd aan met `restrictToAnnotatedClass = true`, zodat enkel de klassen die de
 * fixture echt gebruiken de servers + config-override krijgen.
 */
class WireMockBackendsResource : QuarkusTestResourceLifecycleManager {

    companion object {
        // magazijnId == afzender-OIN (register-conventie). Eén bron, zodat de stub-OIN's,
        // de geïnjecteerde register-config-sleutels en de magazijnId-route-parameters
        // gegarandeerd dezelfde waarden delen.
        const val OIN_A = "00000001003214345000"
        const val OIN_B = "00000001823288444000"

        // lateinit i.p.v. nullable: Quarkus roept altijd start() vóór de tests, dus de
        // call-sites hoeven niet te `!!`-en. Toegang vóór start() faalt expliciet
        // (UninitializedPropertyAccessException).
        lateinit var profiel: WireMockServer
        lateinit var magazijnA: WireMockServer
        lateinit var magazijnB: WireMockServer
    }

    override fun start(): Map<String, String> {
        val p = WireMockServer(wireMockConfig().dynamicPort())
        val a = WireMockServer(wireMockConfig().dynamicPort())
        val b = WireMockServer(wireMockConfig().dynamicPort())
        p.start()
        a.start()
        b.start()
        profiel = p
        magazijnA = a
        magazijnB = b

        return mapOf(
            "quarkus.rest-client.profiel-service.url" to p.baseUrl(),
            "magazijnen.\"$OIN_A\".url" to a.baseUrl(),
            "magazijnen.\"$OIN_B\".url" to b.baseUrl(),
        )
    }

    override fun stop() {
        profiel.stop()
        magazijnA.stop()
        magazijnB.stop()
    }
}
