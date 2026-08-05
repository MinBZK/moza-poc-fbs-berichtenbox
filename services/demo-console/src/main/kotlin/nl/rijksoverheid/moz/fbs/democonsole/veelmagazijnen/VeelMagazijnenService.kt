package nl.rijksoverheid.moz.fbs.democonsole.veelmagazijnen

import jakarta.enterprise.context.ApplicationScoped
import jakarta.ws.rs.core.Response
import org.eclipse.microprofile.config.inject.ConfigProperty
import org.eclipse.microprofile.rest.client.inject.RestClient

/**
 * Zet het live actieve aantal stub-magazijnen. Magazijnen 1..k blijven actief (base-mapping,
 * priority 5); k+1..n krijgen een 503-overlay met vaste id (priority 1 wint). Stateless: de id en
 * het pad zijn deterministisch uit de index, dus toggles hoeven geen mapping-id's bij te houden.
 */
@ApplicationScoped
class VeelMagazijnenService(
    @param:RestClient private val wiremock: WireMockAdminClient,
    @param:ConfigProperty(name = "veel-magazijnen.aantal") private val aantal: Int,
) {

    fun zetActief(k: Int): Map<String, Int> {
        require(k in 0..aantal) { "k moet tussen 0 en $aantal liggen, was $k" }

        for (i in 1..aantal) {

            // Altijd eerst een eventuele bestaande overlay weghalen (idempotent; 404 = er was er
            // geen). Daarna krijgt alleen een inactief magazijn (i > k) een verse 503-overlay. Zo is
            // herhaald schuiven veilig: geen dubbele overlays, geen POST-op-bestaande-id-conflict.
            verwijderStoring(i)

            if (i > k) plaatsStoring(i)
        }

        return mapOf("actief" to k, "totaal" to aantal)
    }

    fun reset(): Map<String, Int> {
        controleer(wiremock.herlaad(), "herladen mappings")

        return mapOf("actief" to aantal, "totaal" to aantal)
    }

    // Idempotent: een 404 betekent dat er geen overlay stond — dat is precies "actief".
    private fun verwijderStoring(i: Int) {
        wiremock.verwijderOverlay(overlayId(i)).close()
    }

    private fun plaatsStoring(i: Int) {
        // Overlay op hetzelfde pad-prefix /mNN als de base-mapping; priority 1 wint van base (5).
        val stub = WireMockStub(overlayId(i), STORING_PRIORITEIT, WireMockRequest("GET", stubPad(i)), WireMockResponse(503))

        controleer(wiremock.voegOverlayToe(stub), "storing zetten op magazijn $i")
    }

    private fun controleer(response: Response, actie: String) {
        response.use {
            check(it.status in 200..299) { "WireMock-fout bij $actie: HTTP ${it.status}" }
        }
    }

    companion object {

        const val STORING_PRIORITEIT = 1

        fun overlayId(i: Int): String = "11111111-0000-0000-0000-%012d".format(i)

        fun stubPad(i: Int): String = "/m%02d/api/v1/berichten".format(i)
    }
}
