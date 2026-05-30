package nl.rijksoverheid.moz.fbs.berichtenuitvraag.uitvraag

import com.github.tomakehurst.wiremock.WireMockServer
import com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig
import io.quarkus.test.common.QuarkusTestResourceLifecycleManager

/**
 * Start twee WireMock-instances (sessiecache + magazijn) en wijst de REST-
 * client-config-keys naar hun URLs. De servers zijn statisch beschikbaar
 * voor tests die per-test stubs willen toevoegen of verifiëren.
 *
 * De `magazijn`-server wordt via `magazijnen.urls.magazijn-a` én `-b` aangewezen
 * (beide naar dezelfde mock); alle uitvraag-paden (bijlage-download, patch,
 * verwijder) routeren via [MagazijnRouter] naar deze mock.
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
            "quarkus.rest-client.sessiecache-sse.url" to s.baseUrl(),
            // Stubs gebruiken `magazijnId="magazijn-a"` (zelfde id als sessiecache-
            // config); beide endpoints routeren in tests naar dezelfde mock.
            "magazijnen.urls.magazijn-a" to m.baseUrl(),
            "magazijnen.urls.magazijn-b" to m.baseUrl(),
        )
    }

    override fun stop() {
        sessiecache?.stop()
        magazijn?.stop()
    }
}
