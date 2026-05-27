package nl.rijksoverheid.moz.fbs.berichtensessiecache.magazijn

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import com.fasterxml.jackson.module.kotlin.KotlinModule
import com.github.tomakehurst.wiremock.client.WireMock.aResponse
import com.github.tomakehurst.wiremock.client.WireMock.get
import com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo
import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.junit.TestProfile
import io.restassured.RestAssured.given
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.time.Instant

/**
 * Contract-tests die magazijn-spec-conforme JSON-bodies tegen
 * [MagazijnBericht]/[MagazijnBerichtenResponse] gooien. Voorkomt een
 * regressie waarbij sessiecache NPE't op een veld dat magazijn's spec
 * wél als required markeert maar wat in een DTO non-null staat zonder
 * default. Eerdere whack-a-mole-fixes (inhoud, bijlagen, publicatietijdstip)
 * zijn hier vastgeklikt.
 *
 * De stub-body bevat ALLEEN de required-velden uit `BerichtSamenvatting`
 * (geen optionele `status`, geen extra magazijn-only velden). Een
 * deserialisatie-fout hier betekent dat de DTO strenger is dan het contract.
 */
@QuarkusTest
@TestProfile(WireMockTestProfile::class)
@QuarkusTestResource(WireMockMagazijnResource::class)
class MagazijnContractIntegrationTest {

    private val ontvanger = "999993653"

    private val objectMapper = ObjectMapper()
        .registerModule(JavaTimeModule())
        .registerModule(KotlinModule.Builder().build())

    @BeforeEach
    fun setUp() {
        WireMockMagazijnResource.serverA!!.resetAll()
        WireMockMagazijnResource.serverB!!.resetAll()
    }

    @Test
    fun `minimale spec-conforme BerichtSamenvatting body deserialiseert zonder NPE`() {
        // Bevat exact alle required velden van magazijn-`BerichtSamenvatting`:
        // berichtId, afzender, ontvanger, onderwerp, inhoud, tijdstipOntvangst,
        // publicatietijdstip, aantalBijlagen, bijlagen, _links. Status is bewust
        // weggelaten (optioneel) zodat we ook de nullable-tak testen.
        val json = """
            {
                "berichten": [
                    {
                        "berichtId": "a1b2c3d4-e5f6-7890-abcd-ef1234567890",
                        "afzender": "00000001234567890000",
                        "ontvanger": { "type": "BSN", "waarde": "$ontvanger" },
                        "onderwerp": "Minimaal bericht",
                        "inhoud": "Korte inhoud",
                        "tijdstipOntvangst": "2026-03-10T14:30:05Z",
                        "publicatietijdstip": "2026-03-10T14:30:00Z",
                        "aantalBijlagen": 0,
                        "bijlagen": [],
                        "_links": { "self": { "href": "/api/v1/berichten/a1b2c3d4-e5f6-7890-abcd-ef1234567890" } }
                    }
                ],
                "page": 0,
                "pageSize": 20,
                "totalElements": 1,
                "totalPages": 1,
                "_links": { "self": { "href": "/api/v1/berichten?page=0&pageSize=20" } }
            }
        """.trimIndent()

        val response = objectMapper.readValue(json, MagazijnBerichtenResponse::class.java)

        assertEquals(1, response.berichten.size)
        val bericht = response.berichten.single()
        assertEquals("00000001234567890000", bericht.afzender)
        assertEquals("BSN", bericht.ontvanger.type)
        assertEquals(ontvanger, bericht.ontvanger.waarde)
        assertEquals("Minimaal bericht", bericht.onderwerp)
        assertEquals("Korte inhoud", bericht.inhoud)
        assertEquals(Instant.parse("2026-03-10T14:30:00Z"), bericht.publicatietijdstip)
        assertEquals(0, bericht.aantalBijlagen)
        assertTrue(bericht.bijlagen.isEmpty())
        assertEquals(null, bericht.status)
    }

    @Test
    fun `minimale BerichtSamenvatting met bijlagen en status deserialiseert volledig`() {
        // Volledige variant: status + bijlagen aanwezig. Bewaakt dat de optionele
        // velden óók nog correct binnenkomen en niet door @JsonIgnoreProperties
        // worden opgegeten.
        val json = """
            {
                "berichten": [
                    {
                        "berichtId": "b2c3d4e5-f6a7-8901-bcde-f12345678901",
                        "afzender": "00000005555555550000",
                        "ontvanger": { "type": "BSN", "waarde": "$ontvanger" },
                        "onderwerp": "Met bijlage",
                        "inhoud": "Tekst",
                        "tijdstipOntvangst": "2026-03-09T09:15:02Z",
                        "publicatietijdstip": "2026-03-09T09:15:00Z",
                        "aantalBijlagen": 1,
                        "bijlagen": [
                            { "bijlageId": "aaaaaaaa-1111-2222-3333-aaaaaaaaaaaa", "naam": "doc.pdf" }
                        ],
                        "status": { "gelezen": true, "map": "archief", "gewijzigdOp": "2026-03-09T10:00:00Z" },
                        "_links": { "self": { "href": "/api/v1/berichten/b2c3d4e5-f6a7-8901-bcde-f12345678901" } }
                    }
                ],
                "page": 0,
                "pageSize": 20,
                "totalElements": 1,
                "totalPages": 1,
                "_links": { "self": { "href": "/api/v1/berichten?page=0&pageSize=20" } }
            }
        """.trimIndent()

        val response = objectMapper.readValue(json, MagazijnBerichtenResponse::class.java)
        val bericht = response.berichten.single()

        assertEquals(1, bericht.aantalBijlagen)
        assertEquals(1, bericht.bijlagen.size)
        assertEquals("doc.pdf", bericht.bijlagen.single().naam)
        val status = bericht.status
        assertNotNull(status)
        assertEquals(true, status!!.gelezen)
        assertEquals("archief", status.map)
    }

    @Test
    fun `keten met minimale spec-conforme magazijn-bodies levert geen FOUT op`() {
        // Echte end-to-end aanroep over WireMock met dezelfde minimale body.
        // Dit zou bij een DTO/spec-mismatch een FOUT-event in de SSE-stream
        // opleveren (deserialisatie-fout); succes betekent dat de keten zonder
        // NPE doorrolt.
        stubMinimaal(WireMockMagazijnResource.serverA!!, "a1b2c3d4-e5f6-7890-abcd-ef1234567890")
        stubMinimaal(WireMockMagazijnResource.serverB!!, "b2c3d4e5-f6a7-8901-bcde-f12345678901")

        val response = given()
            .header("X-Ontvanger", ontvanger)
            .`when`().get("/api/v1/berichten/_ophalen")
            .then().statusCode(200)
            .extract().body().asString()

        assertTrue(response.contains("ophalen-gereed"))
        assertFalse(
            response.contains("FOUT"),
            "Verwacht geen FOUT-event bij minimale spec-conforme magazijn-body, maar kreeg: $response",
        )
    }

    private fun stubMinimaal(server: com.github.tomakehurst.wiremock.WireMockServer, berichtId: String) {
        server.stubFor(
            get(urlPathEqualTo("/api/v1/berichten"))
                .willReturn(
                    aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody(
                            """
                            {
                                "berichten": [
                                    {
                                        "berichtId": "$berichtId",
                                        "afzender": "00000001234567890000",
                                        "ontvanger": { "type": "BSN", "waarde": "$ontvanger" },
                                        "onderwerp": "Minimaal",
                                        "inhoud": "x",
                                        "tijdstipOntvangst": "2026-03-10T14:30:05Z",
                                        "publicatietijdstip": "2026-03-10T14:30:00Z",
                                        "aantalBijlagen": 0,
                                        "bijlagen": [],
                                        "_links": { "self": { "href": "/api/v1/berichten/$berichtId" } }
                                    }
                                ],
                                "page": 0,
                                "pageSize": 20,
                                "totalElements": 1,
                                "totalPages": 1,
                                "_links": { "self": { "href": "/api/v1/berichten?page=0&pageSize=20" } }
                            }
                            """.trimIndent()
                        )
                )
        )
    }
}
