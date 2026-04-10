package nl.rijksoverheid.moz.berichtensessiecache.magazijn

import com.github.tomakehurst.wiremock.client.WireMock.aResponse
import com.github.tomakehurst.wiremock.client.WireMock.get
import com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo
import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.junit.TestProfile
import io.restassured.RestAssured.given
import org.hamcrest.CoreMatchers.containsString
import org.hamcrest.CoreMatchers.`is`
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

@QuarkusTest
@TestProfile(WireMockTestProfile::class)
@QuarkusTestResource(WireMockMagazijnResource::class)
class MagazijnClientWireMockTest {

    private val ontvanger = "999993653"

    @BeforeEach
    fun setUp() {
        WireMockMagazijnResource.serverA!!.resetAll()
        WireMockMagazijnResource.serverB!!.resetAll()
    }

    @Test
    fun `succesvolle response met berichten van beide magazijnen`() {
        stubMagazijnSuccess(WireMockMagazijnResource.serverA!!, "magazijn-a")
        stubMagazijnSuccess(WireMockMagazijnResource.serverB!!, "magazijn-b")

        val response = given()
            .header("X-Ontvanger", ontvanger)
            .`when`().get("/api/v1/berichten/_ophalen")
            .then().statusCode(200)
            .extract().body().asString()

        // SSE stream moet ophalen-gereed event bevatten
        org.junit.jupiter.api.Assertions.assertTrue(response.contains("ophalen-gereed"))
    }

    @Test
    fun `HTTP 500 van magazijn resulteert in FOUT status`() {
        WireMockMagazijnResource.serverA!!.stubFor(
            get(urlPathEqualTo("/api/v1/berichten"))
                .willReturn(aResponse().withStatus(500).withBody("Internal Server Error"))
        )
        stubMagazijnSuccess(WireMockMagazijnResource.serverB!!, "magazijn-b")

        val response = given()
            .header("X-Ontvanger", ontvanger)
            .`when`().get("/api/v1/berichten/_ophalen")
            .then().statusCode(200)
            .extract().body().asString()

        // Stream moet FOUT status event bevatten voor het falende magazijn
        org.junit.jupiter.api.Assertions.assertTrue(response.contains("FOUT"))
    }

    @Test
    fun `connection timeout resulteert in TIMEOUT status`() {
        WireMockMagazijnResource.serverA!!.stubFor(
            get(urlPathEqualTo("/api/v1/berichten"))
                .willReturn(aResponse().withFixedDelay(15_000).withStatus(200).withBody("""{"berichten":[]}"""))
        )
        stubMagazijnSuccess(WireMockMagazijnResource.serverB!!, "magazijn-b")

        val response = given()
            .header("X-Ontvanger", ontvanger)
            .`when`().get("/api/v1/berichten/_ophalen")
            .then().statusCode(200)
            .extract().body().asString()

        org.junit.jupiter.api.Assertions.assertTrue(response.contains("TIMEOUT") || response.contains("FOUT"))
    }

    @Test
    fun `malformed JSON response resulteert in FOUT status`() {
        WireMockMagazijnResource.serverA!!.stubFor(
            get(urlPathEqualTo("/api/v1/berichten"))
                .willReturn(
                    aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{invalid json}")
                )
        )
        stubMagazijnSuccess(WireMockMagazijnResource.serverB!!, "magazijn-b")

        val response = given()
            .header("X-Ontvanger", ontvanger)
            .`when`().get("/api/v1/berichten/_ophalen")
            .then().statusCode(200)
            .extract().body().asString()

        org.junit.jupiter.api.Assertions.assertTrue(response.contains("FOUT"))
    }

    @Test
    fun `lege berichtenlijst van alle magazijnen`() {
        stubMagazijnEmpty(WireMockMagazijnResource.serverA!!)
        stubMagazijnEmpty(WireMockMagazijnResource.serverB!!)

        val response = given()
            .header("X-Ontvanger", ontvanger)
            .`when`().get("/api/v1/berichten/_ophalen")
            .then().statusCode(200)
            .extract().body().asString()

        org.junit.jupiter.api.Assertions.assertTrue(response.contains("ophalen-gereed"))
        org.junit.jupiter.api.Assertions.assertTrue(response.contains("\"totaalBerichten\":0"))
    }

    private fun stubMagazijnSuccess(server: com.github.tomakehurst.wiremock.WireMockServer, magazijnId: String) {
        server.stubFor(
            get(urlPathEqualTo("/api/v1/berichten"))
                .willReturn(
                    aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("""
                            {
                                "berichten": [
                                    {
                                        "berichtId": "${java.util.UUID.randomUUID()}",
                                        "afzender": "00000001234567890000",
                                        "ontvanger": "$ontvanger",
                                        "onderwerp": "Test bericht van $magazijnId",
                                        "tijdstip": "2026-03-10T10:00:00Z",
                                        "magazijnId": "$magazijnId"
                                    }
                                ]
                            }
                        """.trimIndent())
                )
        )
    }

    private fun stubMagazijnEmpty(server: com.github.tomakehurst.wiremock.WireMockServer) {
        server.stubFor(
            get(urlPathEqualTo("/api/v1/berichten"))
                .willReturn(
                    aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("""{"berichten":[]}""")
                )
        )
    }
}
