package nl.rijksoverheid.moz.fbs.berichtensessiecache.magazijn

import io.quarkus.test.Mock
import jakarta.enterprise.context.ApplicationScoped
import nl.rijksoverheid.moz.fbs.common.profiel.IdentificatieResponse
import nl.rijksoverheid.moz.fbs.common.profiel.PartijResponse
import nl.rijksoverheid.moz.fbs.common.profiel.ProfielServiceClient
import nl.rijksoverheid.moz.fbs.common.profiel.ScopeResponse
import nl.rijksoverheid.moz.fbs.common.profiel.VoorkeurResponse
import org.eclipse.microprofile.rest.client.inject.RegisterRestClient

/**
 * CDI-`@Mock`-bean die de echte REST-client vervangt in tests. Default-respons
 * geeft elke ontvanger toestemming voor beide configured-magazijn-OINs zodat
 * bestaande BerichtenOphalenResourceTest-cases identieke aggregatie houden.
 *
 * WireMockProfielServiceTestProfile schakelt deze mock uit (via
 * `quarkus.arc.exclude-types`) zodat de echte REST-client onder test komt.
 */
@Mock
@ApplicationScoped
@RegisterRestClient(configKey = "profiel-service")
open class MockProfielServiceClient : ProfielServiceClient {
    override fun getPartij(identificatieType: String, identificatieNummer: String): PartijResponse =
        PartijResponse(
            voorkeuren = listOf(
                VoorkeurResponse(
                    voorkeurType = "OntvangViaBerichtenbox",
                    waarde = "true",
                    scopes = listOf(
                        ScopeResponse(partij = IdentificatieResponse("OIN", "00000001003214345000")),
                        ScopeResponse(partij = IdentificatieResponse("OIN", "00000001823288444000")),
                    ),
                ),
            ),
        )
}
