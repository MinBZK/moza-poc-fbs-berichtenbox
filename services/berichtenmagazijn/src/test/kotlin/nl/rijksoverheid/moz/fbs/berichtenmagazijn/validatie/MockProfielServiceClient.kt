package nl.rijksoverheid.moz.fbs.berichtenmagazijn.validatie

import io.quarkus.test.Mock
import jakarta.enterprise.context.ApplicationScoped
import org.eclipse.microprofile.rest.client.inject.RestClient

/**
 * Default test-mock voor [ProfielServiceClient]. Vervangt de auto-gegenereerde
 * `@RegisterRestClient`-bean in alle `@QuarkusTest` runs zodat tests niet afhankelijk
 * zijn van een draaiende WireMock-stub.
 *
 * Aanwezig is `@RestClient` qualifier op de mock-class — zonder die qualifier matcht
 * hij de injection-site `@Inject @RestClient ProfielServiceClient` niet en blijft de
 * echte REST-client-bean actief naast de mock.
 *
 * Default geeft een actieve berichtenbox-voorkeur terug voor afzender-OIN `${'$'}{antwoordVoorAfzenderOin}`
 * (default = test-OIN `00000001003214345000`). Tests die afwijkende scenario's verifiëren
 * injecteren de mock en zetten [antwoordSupplier] per test.
 */
@Mock
@ApplicationScoped
@RestClient
class MockProfielServiceClient : ProfielServiceClient {

    /** Lambda die op basis van (type, nummer) een PartijResponse oplevert. Werp een
     *  exception om HTTP-fouten te simuleren (bv. `NotFoundException`). */
    @Volatile
    var antwoordSupplier: (String, String) -> PartijResponse = { _, _ ->
        defaultPartij(afzenderOin = "00000001003214345000")
    }

    override fun getPartij(identificatieType: String, identificatieNummer: String): PartijResponse =
        antwoordSupplier(identificatieType, identificatieNummer)

    companion object {
        fun defaultPartij(afzenderOin: String): PartijResponse = PartijResponse(
            voorkeuren = listOf(
                VoorkeurResponse(
                    voorkeurType = "OntvangViaBerichtenbox",
                    waarde = "true",
                    scopes = listOf(
                        ScopeResponse(
                            partij = IdentificatieResponse(
                                identificatieType = "OIN",
                                identificatieNummer = afzenderOin,
                            ),
                        ),
                    ),
                ),
            ),
        )
    }
}
