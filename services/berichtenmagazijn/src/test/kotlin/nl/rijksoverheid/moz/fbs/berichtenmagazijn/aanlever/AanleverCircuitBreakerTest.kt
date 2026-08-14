package nl.rijksoverheid.moz.fbs.berichtenmagazijn.aanlever

import io.mockk.every
import io.mockk.mockk
import io.quarkus.test.junit.QuarkusMock
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.junit.QuarkusTestProfile
import io.quarkus.test.junit.TestProfile
import io.restassured.RestAssured.given
import io.restassured.http.ContentType
import jakarta.inject.Inject
import jakarta.persistence.PersistenceException
import jakarta.ws.rs.ProcessingException
import nl.rijksoverheid.moz.fbs.berichtenmagazijn.opslag.Bericht
import nl.rijksoverheid.moz.fbs.berichtenmagazijn.opslag.BerichtRepository
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
 * Legt de twee circuits rond het aanleverpad vast: dat van [BerichtOpslagService.slaBerichtOp]
 * (opslagstoring) en dat van de abonnementscontrole (Profiel-storing).
 *
 * **Opslagcircuit** — bewijst dat het circuit daadwerkelijk opent zodra de service herhaaldelijk
 * faalt met een exception die níét in `skipOn` zit. Zonder deze test zou een per ongeluk
 * toegevoegd exception-type in skipOn, of een verhoging van `requestVolumeThreshold` /
 * `failureRatio`, ongemerkt de breaker effectief uitzetten. Thresholds uit de
 * `@CircuitBreaker`-annotatie: requestVolumeThreshold=20, failureRatio=0.5 — na 10+ fails in de
 * eerste 20 requests moet het circuit open zijn en minstens één volgende request 503 geven.
 *
 * **Validatiecircuit** — de abonnementscontrole belt de Profiel-service en draait bewust buiten
 * de JTA-transactie van [BerichtOpslagService.slaBerichtOp], en dus ook buiten diens circuit.
 * Zonder eigen breaker zou een dode Profiel-service elke aanlever-request seconden laten hangen
 * op client-timeouts en `@Retry`, met worker-threads die vollopen. Beide kanten van die grens
 * staan hier vast:
 *  1. **Transportstoring** (`ProcessingException`: connection refused/reset/read-timeout) telt
 *     mee → na de drempel fast-fail met [CircuitBreakerOpenException] (→ 503).
 *  2. **Functionele afwijzing** ([ToestemmingGeweigerdException]) telt niet mee → ook na
 *     tientallen pogingen blijft het circuit dicht. Eén aanleveraar die stelselmatig ontvangers
 *     zonder abonnement aanbiedt, mag legitiem verkeer niet afknijpen.
 *
 * De validatie-aanroepen gaan rechtstreeks op de CDI-bean (dus mét interceptor, zonder HTTP):
 * dat isoleert de breaker van de mapper-laag. De statuscode die bij een open circuit hoort staat
 * in [CircuitBreakerOpen503ContractTest].
 *
 * **Volgorde is functioneel, niet cosmetisch.** Breaker-state is application-scoped en blijft na
 * een trip nog `delay` (5s) open. De opslagtest gaat over HTTP en valideert dus vóór het
 * opslaan; draait die ná de storingstest, dan fast-failt hij op het open validatiecircuit en
 * wordt hij groen zonder het opslagcircuit ooit te raken. Daarom: opslag eerst, dan de
 * weigering (opent niets), en als laatste de storing die het validatiecircuit opent.
 *
 * Die storingstest staat op `Int.MAX_VALUE` en niet op 3: JUnit geeft een methode zónder
 * `@Order` de waarde `Integer.MAX_VALUE / 2`, dus een nieuwe test waarbij de annotatie vergeten
 * wordt, valt vóór de storing in plaats van erna. Zonder die keuze zou zo'n test tegen een
 * geopend circuit draaien en een verkeerd gedrag meten dat er groen uitziet.
 */
@QuarkusTest
@TestProfile(AanleverCircuitBreakerTest.Profile::class)
@TestMethodOrder(MethodOrderer.OrderAnnotation::class)
class AanleverCircuitBreakerTest {

    /**
     * Leeg profiel, puur om een eigen applicatie-instantie af te dwingen: de open-circuit-state
     * hoort niet door te lekken naar de rest van de suite. Beide circuits delen die ene
     * instantie — een profiel per testklasse kostte een tweede applicatie-start plus
     * database-container voor dezelfde isolatie-eis.
     */
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

    @Test
    @Order(1)
    fun `PersistenceException opent circuit na threshold en volgende requests krijgen 503`() {
        val failingRepo = mockk<BerichtRepository>(relaxed = true)
        every { failingRepo.save(any<Bericht>()) } throws PersistenceException("infra fout")
        QuarkusMock.installMockForType(failingRepo, BerichtRepository::class.java)

        val statusses = (1..30).map {
            given()
                .contentType(ContentType.JSON)
                .body(payload())
                .`when`().post("/api/v1/berichten")
                .then()
                .extract().statusCode()
        }

        // Zonder deze voorwaarde slaagt de test ook als álle responses 503 zijn doordat een
        // ánder circuit al openstond — dan is het opslagcircuit nooit geraakt.
        assertTrue(
            statusses.take(20).any { it != 503 },
            "het opslagcircuit moet bij aanvang dicht zijn; alleen-503 betekent dat een ander circuit al open stond — reeks = $statusses",
        )

        val aantal503 = statusses.count { it == 503 }

        assertTrue(
            aantal503 > 0,
            "Circuit breaker moet openen na herhaalde fouten; aantal 503 = $aantal503, reeks = $statusses",
        )
    }

    @Test
    @Order(2)
    fun `herhaalde toestemmingsweigeringen openen het circuit niet`() {
        mock.antwoordSupplier = { _, _ -> PartijResponse(voorkeuren = emptyList()) }

        val fouten = valideerHerhaald()

        assertTrue(
            fouten.all { it == ToestemmingGeweigerdException::class.java },
            "een policy-besluit mag nooit tot fast-fail leiden — gezien: $fouten",
        )
    }

    @Test
    @Order(Int.MAX_VALUE)
    fun `een Profiel-storing opent het circuit, dus validatie fast-failt`() {
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

    private fun payload() = """
        {
          "afzender": "$AFZENDER",
          "ontvanger": {"type": "BSN", "waarde": "999993653"},
          "onderwerp": "Test",
          "inhoud": "Test"
        }
    """.trimIndent()

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

    private companion object {
        const val AFZENDER = "00000001003214345000"
    }
}
