package nl.rijksoverheid.moz.fbs.berichtenmagazijn.aanlever

import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.junit.QuarkusTestProfile
import io.quarkus.test.junit.TestProfile
import jakarta.inject.Inject
import jakarta.ws.rs.ProcessingException
import nl.rijksoverheid.moz.fbs.berichtenmagazijn.validatie.MockProfielServiceClient
import nl.rijksoverheid.moz.fbs.common.identificatie.IdentificatienummerType
import nl.rijksoverheid.moz.fbs.common.profiel.PartijResponse
import nl.rijksoverheid.moz.fbs.common.profiel.ProfielServiceClient
import nl.rijksoverheid.moz.fbs.common.profiel.ToestemmingGeweigerdException
import org.eclipse.microprofile.faulttolerance.exceptions.CircuitBreakerOpenException
import org.eclipse.microprofile.rest.client.inject.RestClient
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.MethodOrderer
import org.junit.jupiter.api.Order
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestMethodOrder
import java.net.ConnectException

/**
 * Legt de fault-tolerance-grens rond het validatiepad vast.
 *
 * De abonnementscontrole belt de Profiel-service en draait bewust buiten de
 * JTA-transactie van [BerichtOpslagService.slaBerichtOp] — en dus ook buiten diens
 * circuit. Zonder eigen breaker zou een dode Profiel-service elke aanlever-request
 * seconden laten hangen op client-timeouts en `@Retry`, met worker-threads die vollopen.
 *
 * Beide kanten van de grens staan hier vast:
 *  1. **Transportstoring** (`ProcessingException`: connection refused/reset/read-timeout)
 *     telt mee → na de drempel fast-fail met [CircuitBreakerOpenException] (→ 503).
 *  2. **Functionele afwijzing** ([ToestemmingGeweigerdException]) telt niet mee → ook na
 *     tientallen pogingen blijft het circuit dicht. Eén aanleveraar die stelselmatig
 *     ontvangers zonder abonnement aanbiedt, mag legitiem verkeer niet afknijpen.
 *
 * Aanroepen gaan rechtstreeks op de CDI-bean (dus mét interceptor, zonder HTTP): dat
 * isoleert de breaker van de mapper-laag. De statuscode die bij een open circuit hoort
 * staat in [CircuitBreakerOpen503ContractTest].
 *
 * Eigen `TestProfile` — zelfde reden als bij [CircuitBreakerTripTest]: breaker-state is
 * application-scoped en blijft na de trip nog `delay` (5s) open. Een eigen
 * Quarkus-instantie houdt die state weg bij de rest van de suite. Binnen deze klasse doet
 * `@TestMethodOrder` hetzelfde werk: de weiger-test draait vóór de test die het circuit
 * opent.
 */
@QuarkusTest
@TestProfile(ValidatieCircuitBreakerTest.Profile::class)
@TestMethodOrder(MethodOrderer.OrderAnnotation::class)
class ValidatieCircuitBreakerTest {

    class Profile : QuarkusTestProfile

    @Inject
    lateinit var opslagService: BerichtOpslagService

    @Inject
    @RestClient
    lateinit var profielClient: ProfielServiceClient

    private val mock: MockProfielServiceClient
        get() = profielClient as MockProfielServiceClient

    @AfterEach
    fun herstelProfielMock() {
        mock.antwoordSupplier = { _, _ ->
            MockProfielServiceClient.defaultPartij(afzenderOin = AFZENDER)
        }
    }

    private fun valideer() = opslagService.valideerAanlevering(
        afzender = AFZENDER,
        ontvangerType = IdentificatienummerType.BSN,
        ontvangerWaarde = "999993653",
        onderwerp = "Test",
        inhoud = "Inhoud",
    )

    /**
     * Roept [valideer] [aantal] keer aan en geeft per poging het type van de fout terug.
     * 30 pogingen is ruim boven `requestVolumeThreshold=20`, dus als de breaker meetelt,
     * moet hij binnen deze reeks omslaan.
     */
    private fun valideerHerhaald(aantal: Int = 30): List<Class<out Throwable>?> = (1..aantal).map {
        runCatching { valideer() }.exceptionOrNull()?.javaClass
    }

    @Test
    @Order(1)
    fun `herhaalde toestemmingsweigeringen openen het circuit niet`() {
        mock.antwoordSupplier = { _, _ -> PartijResponse(voorkeuren = emptyList()) }

        val fouten = valideerHerhaald()

        assertTrue(
            fouten.all { it == ToestemmingGeweigerdException::class.java },
            "een policy-besluit mag nooit tot fast-fail leiden — gezien: $fouten",
        )
    }

    @Test
    @Order(2)
    fun `een Profiel-storing opent het circuit, dus aanleveren faalt snel`() {
        mock.antwoordSupplier = { _, _ -> throw ProcessingException(ConnectException("connection refused")) }

        val fouten = valideerHerhaald()

        assertTrue(
            fouten.take(20).any { it == ProcessingException::class.java },
            "de eerste pogingen moeten de echte transportfout doorlaten — gezien: $fouten",
        )
        assertTrue(
            fouten.any { it == CircuitBreakerOpenException::class.java },
            "na de drempel moet het circuit openen (fast-fail) — gezien: $fouten",
        )
        assertTrue(
            fouten.takeLast(5).all { it == CircuitBreakerOpenException::class.java },
            "eenmaal open blijft het circuit open tot de delay verstrijkt — gezien: $fouten",
        )
    }

    private companion object {
        const val AFZENDER = "00000001003214345000"
    }
}
