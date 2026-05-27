package nl.rijksoverheid.moz.fbs.berichtenuitvraag.uitvraag

import com.github.tomakehurst.wiremock.WireMockServer
import com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig
import io.quarkus.test.common.QuarkusTestResourceLifecycleManager

/**
 * Start twee WireMock-instances (sessiecache + magazijn) en wijst de REST-
 * client-config-keys naar hun URLs. De servers zijn statisch beschikbaar
 * voor tests die per-test stubs willen toevoegen of verifiëren.
 *
 * De `magazijn`-server wordt zowel als `magazijnen.urls.default` (voor
 * routering via [MagazijnRouter]) als als statische `MagazijnClient`-URL
 * (voor [BerichtBeheerService]) geconfigureerd — zo bedienen beide paden
 * dezelfde mock in de huidige tests.
 */
class WireMockBackendsResource : QuarkusTestResourceLifecycleManager {

    companion object {
        var sessiecache: WireMockServer? = null
        var magazijn: WireMockServer? = null
    }

    override fun start(): Map<String, String> {
        val s = WireMockServer(wireMockConfig().dynamicPort())
        val m = WireMockServer(wireMockConfig().dynamicPort())
        s.start()
        m.start()
        sessiecache = s
        magazijn = m

        return mapOf(
            "quarkus.rest-client.\"nl.rijksoverheid.moz.fbs.berichtenuitvraag.uitvraag.SessiecacheClient\".url" to s.baseUrl(),
            "quarkus.rest-client.\"nl.rijksoverheid.moz.fbs.berichtenuitvraag.uitvraag.MagazijnClient\".url" to m.baseUrl(),
            "quarkus.rest-client.sessiecache-sse.url" to s.baseUrl(),
            "magazijnen.urls.default" to m.baseUrl(),
        )
    }

    override fun stop() {
        sessiecache?.stop()
        magazijn?.stop()
    }
}
