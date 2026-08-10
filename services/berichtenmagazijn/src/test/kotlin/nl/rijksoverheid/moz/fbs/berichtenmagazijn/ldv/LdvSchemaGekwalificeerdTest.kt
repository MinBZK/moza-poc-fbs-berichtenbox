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
 * Meerdere magazijnen delen in productie één database en één DB-user; alleen het schema
 * scheidt ze. De wrapper kwalificeert zijn `CREATE TABLE`/`INSERT` niet en de LDV-URL zet
 * geen currentSchema, dus de scheiding hangt volledig aan een schema-prefix in de
 * tabelnaam. Deze test borgt dat de wrapper zo'n prefix accepteert en de logregel
 * daadwerkelijk in dát schema landt — zonder dat blijft er één gedeeld logboek over
 * waarin de verwerkingen van alle organisaties door elkaar staan.
 */
@QuarkusTest
@TestProfile(LdvSchemaGekwalificeerdTest.GekwalificeerdeTabelProfile::class)
class LdvSchemaGekwalificeerdTest {

    @Inject
    lateinit var dataSource: DataSource

    class GekwalificeerdeTabelProfile : QuarkusTestProfile {
        override fun getConfigOverrides(): Map<String, String> = mapOf(
            // Eigen container voor deze test: het init-script maakt het schema aan dat de
            // exporter bij constructie nodig heeft.
            "quarkus.datasource.devservices.init-script-path" to "ldv-schema-init.sql",
            "logboekdataverwerking.enabled" to "true",
            "logboekdataverwerking.dbms" to "postgresql",
            "logboekdataverwerking.span-processor" to "simple",
            "logboekdataverwerking.write-failure-policy" to "fail-closed",
            "logboekdataverwerking.postgresql.url" to "\${quarkus.datasource.jdbc.url}",
            "logboekdataverwerking.postgresql.username" to "\${quarkus.datasource.username}",
            "logboekdataverwerking.postgresql.password" to "\${quarkus.datasource.password}",
            "logboekdataverwerking.postgresql.table" to "magazijnschema.logboek_dataverwerkingen",
        )
    }

    @Test
    fun `de logregel landt in het schema uit de tabelnaam en niet in het default-schema`() {
        given()
            .contentType(ContentType.JSON)
            .body(
                """
                {
                  "afzender": "00000001003214345000",
                  "ontvanger": { "type": "BSN", "waarde": "999993653" },
                  "onderwerp": "LDV-schematest",
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
                SELECT count(*) AS aantal
                  FROM magazijnschema.logboek_dataverwerkingen
                 WHERE name = 'aanleveren-bericht'
                """.trimIndent(),
            ).executeQuery().use { rijen ->
                assertTrue(rijen.next(), "de query moet een telling opleveren")
                assertTrue(
                    rijen.getInt("aantal") >= 1,
                    "de logregel hoort in het opgegeven schema te staan",
                )
            }

            // Zonder deze controle zou een genegeerde prefix onopgemerkt blijven: de tabel
            // in het default-schema vult zich dan, terwijl de assertie hierboven op een
            // toevallig gevulde tabel zou kunnen slagen.
            connectie.prepareStatement("SELECT to_regclass('public.logboek_dataverwerkingen') AS tabel")
                .executeQuery().use { rijen ->
                    assertTrue(rijen.next(), "de query moet een rij opleveren")
                    assertEquals(
                        null,
                        rijen.getString("tabel"),
                        "er hoort geen logboektabel in het default-schema aangemaakt te zijn",
                    )
                }
        }
    }
}
