package nl.rijksoverheid.moz.fbs.berichtenmagazijn.validatie

import io.quarkus.test.Mock
import jakarta.enterprise.context.ApplicationScoped
import org.eclipse.microprofile.rest.client.inject.RestClient

/**
 * Default test-mock voor [ToestemmingControle]. Vervangt de auto-gegenereerde
 * `@RegisterRestClient`-bean in alle `@QuarkusTest` runs zodat tests niet
 * afhankelijk zijn van een draaiende WireMock-stub.
 *
 * Aanwezig is `@RestClient` qualifier op de mock-class — anders matcht hij de
 * injection-site `@Inject @RestClient ToestemmingControle` niet en blijft de
 * echte REST-client-bean actief naast de mock.
 *
 * Default is `toegestaan = true` (happy path). Tests die toestemming-geweigerd
 * scenario's verifiëren injecteren de mock en zetten [resultaat] per test.
 */
@Mock
@ApplicationScoped
@RestClient
class MockToestemmingControle : ToestemmingControle {

    @Volatile
    var resultaat: ToestemmingAntwoord = ToestemmingAntwoord(toegestaan = true)

    override fun controleer(verzoek: ToestemmingVerzoek): ToestemmingAntwoord = resultaat
}
