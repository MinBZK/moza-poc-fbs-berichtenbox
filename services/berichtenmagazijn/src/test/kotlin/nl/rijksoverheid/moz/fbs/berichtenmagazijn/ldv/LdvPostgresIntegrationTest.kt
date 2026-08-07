package nl.rijksoverheid.moz.fbs.berichtenmagazijn.ldv

import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.junit.QuarkusTestProfile
import io.quarkus.test.junit.TestProfile
import io.restassured.RestAssured.given
import io.restassured.http.ContentType
import jakarta.inject.Inject
import javax.sql.DataSource
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Borgt dat de LDV-spans daadwerkelijk in PostgreSQL landen: schema-aanmaak, de insert
 * zelf en de jsonb-kolommen. De overige tests draaien met LDV uit, dus zonder deze test
 * wordt onze backend-configuratie pas in de demo of op ZAD voor het eerst uitgevoerd.
 */
@QuarkusTest
@TestProfile(LdvPostgresIntegrationTest.LdvAanProfile::class)
class LdvPostgresIntegrationTest {

    @Inject
    lateinit var dataSource: DataSource

    /**
     * LDV krijgt dezelfde database als de service. De waarden komen uit
     * `quarkus.datasource.*`, die Dev Services pas bij het opstarten invult; de
     * expressie wordt bij uitlezen geëxpandeerd, dus dit volgt de container vanzelf.
     */
    class LdvAanProfile : QuarkusTestProfile {
        override fun getConfigOverrides(): Map<String, String> = mapOf(
            "logboekdataverwerking.enabled" to "true",
            "logboekdataverwerking.dbms" to "postgresql",
            "logboekdataverwerking.span-processor" to "simple",
            "logboekdataverwerking.write-failure-policy" to "fail-closed",
            "logboekdataverwerking.postgresql.url" to "\${quarkus.datasource.jdbc.url}",
            "logboekdataverwerking.postgresql.username" to "\${quarkus.datasource.username}",
            "logboekdataverwerking.postgresql.password" to "\${quarkus.datasource.password}",
            "logboekdataverwerking.postgresql.table" to "logboek_dataverwerkingen",
        )
    }

    @Test
    fun `een aanlevering schrijft een logregel in PostgreSQL`() {
        val ontvanger = "999993653"

        given()
            .contentType(ContentType.JSON)
            .body(
                """
                {
                  "afzender": "00000001003214345000",
                  "ontvanger": { "type": "BSN", "waarde": "$ontvanger" },
                  "onderwerp": "LDV-integratietest",
                  "inhoud": "Inhoud",
                  "publicatietijdstip": "2026-08-06T10:00:00Z"
                }
                """.trimIndent(),
            )
            .post("/api/v1/berichten")
            .then()
            .statusCode(201)

        dataSource.connection.use { connectie ->
            connectie.prepareStatement(
                """
                SELECT name, status, attributes->>'dpl.core.processing_activity_id' AS activiteit,
                       attributes->>'dpl.core.data_subject_id_type' AS subject_type,
                       trace_id, span_id
                  FROM logboek_dataverwerkingen
                 WHERE name = 'aanleveren-bericht'
                """.trimIndent(),
            ).executeQuery().use { rijen ->
                assertTrue(rijen.next(), "er moet een logregel voor aanleveren-bericht zijn")
                // UNSET, niet ERROR: de logregel gaat vóór de opslag de deur uit en legt
                // dus het voornemen vast, niet de uitkomst. ERROR is voorbehouden aan een
                // verwerking waarvan op schrijfmoment al vaststaat dat ze niet doorgaat.
                assertEquals("UNSET", rijen.getString("status"))
                assertEquals("BSN", rijen.getString("subject_type"))
                assertTrue(
                    rijen.getString("activiteit").startsWith("http"),
                    "processing_activity_id moet een absolute URI zijn",
                )
                assertTrue(rijen.getString("trace_id").isNotBlank(), "trace_id moet gevuld zijn")
                assertTrue(rijen.getString("span_id").isNotBlank(), "span_id moet gevuld zijn")
            }
        }
    }
}
