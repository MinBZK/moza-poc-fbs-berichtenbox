package nl.rijksoverheid.moz.fbs.beheerconsole.stelsel

import io.quarkus.test.Mock
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.junit.QuarkusTestProfile
import io.quarkus.test.junit.TestProfile
import io.restassured.RestAssured.given
import jakarta.enterprise.context.ApplicationScoped
import jakarta.ws.rs.ProcessingException
import jakarta.ws.rs.WebApplicationException
import org.eclipse.microprofile.rest.client.inject.RestClient
import org.hamcrest.Matchers.equalTo
import org.hamcrest.Matchers.hasSize
import org.hamcrest.Matchers.notNullValue
import org.hamcrest.Matchers.nullValue
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import java.time.Instant

/** Het stelselregister vervangen door een stub; de verificatie zelf draait echt. */
@Mock
@ApplicationScoped
@RestClient
class StubStelselregister : StelselregisterClient {
    override fun stelseldocument(): String = antwoord()

    companion object {
        var antwoord: () -> String = { error("geen antwoord ingesteld") }
    }
}

class StelselProfiel : QuarkusTestProfile {
    override fun getConfigOverrides(): Map<String, String> = mapOf(
        "beheerconsole.stelseldocument.root" to Testketen.geldig.rootPem,
        "beheerconsole.stelseldocument.uitgever" to Testketen.OIN,
        "beheerconsole.stelseldocument.omgeving" to Testketen.OMGEVING,
        "quarkus.rest-client.stelselregister.url" to "http://localhost:1",
    )
}

@QuarkusTest
@TestProfile(StelselProfiel::class)
class StelselResourceTest {

    @AfterEach
    fun herstel() {
        StubStelselregister.antwoord = { error("geen antwoord ingesteld") }
    }

    @Test
    fun `een geldig document wordt een overzicht`() {
        StubStelselregister.antwoord = { Testketen.geldig.document(Instant.now()) }

        given().`when`().get("/api/v1/stelsel").then()
            .statusCode(200)
            .header("API-Version", "0.1.0")
            .body("verificatie.status", equalTo("GELDIG"))
            .body("organisaties", hasSize<Any>(1))
            .body("organisaties[0].naam", equalTo("Organisatie A"))
            .body("document.uitgever", equalTo(Testketen.OIN))
            .body("ondertekening.certificaatGeldigTot", notNullValue())
            .body("_links.self.href", equalTo("/api/v1/stelsel"))
    }

    @Test
    fun `een document onder een andere root wordt getoond, als ongeldig`() {
        StubStelselregister.antwoord = { Testketen.ander.document(Instant.now()) }

        given().`when`().get("/api/v1/stelsel").then()
            .statusCode(200)
            .body("verificatie.status", equalTo("ONGELDIG"))
            .body("verificatie.reden", notNullValue())
            .body("organisaties", hasSize<Any>(1))
    }

    @Test
    fun `onleesbare inhoud geeft alleen de reden`() {
        StubStelselregister.antwoord = { "geen-jws" }

        given().`when`().get("/api/v1/stelsel").then()
            .statusCode(200)
            .body("verificatie.status", equalTo("ONGELDIG"))
            .body("document", nullValue())
            .body("organisaties", hasSize<Any>(0))
    }

    @Test
    fun `een foutantwoord van het stelselregister is een ketenfout`() {
        StubStelselregister.antwoord = { throw WebApplicationException(503) }

        given().`when`().get("/api/v1/stelsel").then()
            .statusCode(502)
            .contentType("application/problem+json")
            .body("type", equalTo("urn:fbs:fout:keten-fout"))
    }

    @Test
    fun `een onbereikbaar stelselregister is een ketenfout`() {
        StubStelselregister.antwoord = { throw ProcessingException("verbinding geweigerd") }

        given().`when`().get("/api/v1/stelsel").then()
            .statusCode(502)
            .body("type", equalTo("urn:fbs:fout:keten-fout"))
    }

    @Test
    fun `de spec staat op openapi json`() {
        given().`when`().get("/openapi.json").then().statusCode(200).body("info.title", equalTo("FBS Beheerconsole API"))
    }
}
