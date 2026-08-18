package nl.rijksoverheid.moz.fbs.berichtenmagazijn.ldv

import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.junit.QuarkusTestProfile
import io.quarkus.test.junit.TestProfile
import io.restassured.RestAssured.given
import io.restassured.http.ContentType
import jakarta.inject.Inject
import javax.sql.DataSource
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * Legt vast wat er gebeurt als de tabelnaam naar een schema wijst dat niet bestaat — het
 * gevolg van een verkeerd gezette `DB_SCHEMA`.
 *
 * De wrapper bouwt zijn repository in een `@ApplicationScoped`-bean met `@PostConstruct` en
 * zonder `@Startup`, dus lazy: de service komt op, haalt de health checks, en pas het
 * eerste verzoek loopt tegen het ontbrekende schema aan. Fail-closed doet dan zijn werk —
 * er wordt niets opgeslagen en de aanleveraar krijgt een 500 — maar een deploy die er
 * geslaagd uitziet is functioneel dood.
 *
 * Daarom controleert `LdvTabelnaamValidator` de vórm van de naam al bij het opstarten. Een
 * ontbrekend schema is daarmee niet af te vangen (dat is een toestand van de database, geen
 * configuratiefout), dus dit gedrag blijft bestaan; deze test houdt het zichtbaar in plaats
 * van dat het pas in productie opvalt.
 */
@QuarkusTest
@TestProfile(LdvOntbrekendSchemaTest.OntbrekendSchemaProfile::class)
class LdvOntbrekendSchemaTest {

    @Inject
    lateinit var dataSource: DataSource

    class OntbrekendSchemaProfile : QuarkusTestProfile {
        override fun getConfigOverrides(): Map<String, String> = mapOf(
            "logboekdataverwerking.enabled" to "true",
            "logboekdataverwerking.dbms" to "postgresql",
            "logboekdataverwerking.span-processor" to "simple",
            "logboekdataverwerking.write-failure-policy" to "fail-closed",
            "logboekdataverwerking.postgresql.url" to "\${quarkus.datasource.jdbc.url}",
            "logboekdataverwerking.postgresql.username" to "\${quarkus.datasource.username}",
            "logboekdataverwerking.postgresql.password" to "\${quarkus.datasource.password}",
            // Vormt een geldige tabelnaam, maar het schema wordt nergens aangemaakt.
            "logboekdataverwerking.postgresql.table" to "ditschemabestaatniet.logboek_dataverwerkingen",
        )
    }

    @Test
    fun `een aanlevering faalt zichtbaar als het logboekschema ontbreekt`() {
        val onderwerp = "Aanlevering zonder logboekschema"
        val berichtenVooraf = aantalBerichten()

        given()
            .contentType(ContentType.JSON)
            .body(
                """
                {
                  "afzender": "00000001003214345000",
                  "ontvanger": { "type": "BSN", "waarde": "999993653" },
                  "onderwerp": "$onderwerp",
                  "inhoud": "Inhoud",
                  "publicatietijdstip": "2026-08-06T10:00:00Z"
                }
                """.trimIndent(),
            )
            .post("/api/v1/aanleveringen")
            .then()
            // Geen 201: zonder logregel telt de verwerking niet als uitgevoerd.
            .statusCode(500)

        // Dit is de eigenlijke fail-closed-eis. Zonder deze controle zou de test ook slagen
        // bij een 500 uit een heel andere hoek, terwijl het bericht wél was opgeslagen.
        assertEquals(
            berichtenVooraf,
            aantalBerichten(),
            "een aanlevering die niet in het logboek kwam, mag geen bericht achterlaten",
        )
    }

    private fun aantalBerichten(): Int = dataSource.connection.use { connectie ->
        connectie.prepareStatement("SELECT count(*) AS aantal FROM berichten").use { telling ->
            telling.executeQuery().use { rijen ->
                rijen.next()

                rijen.getInt("aantal")
            }
        }
    }
}
