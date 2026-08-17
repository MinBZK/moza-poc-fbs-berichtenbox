package nl.rijksoverheid.moz.fbs.berichtenmagazijn.ldv

import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.junit.QuarkusTestProfile
import io.quarkus.test.junit.TestProfile
import io.restassured.RestAssured.given
import io.restassured.http.ContentType
import jakarta.inject.Inject
import java.sql.Connection
import javax.sql.DataSource
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * Meerdere magazijnen delen in productie één database en één DB-user; alleen het schema
 * scheidt ze. De wrapper kwalificeert zijn `CREATE TABLE` en `INSERT` niet en de LDV-URL
 * zet geen currentSchema, dus die scheiding hangt volledig aan een schema-prefix in de
 * tabelnaam. Deze test borgt dat de wrapper zo'n prefix accepteert en dat élke logregel in
 * dát schema landt — zonder dat blijft er één gedeeld logboek over waarin de verwerkingen
 * van alle organisaties door elkaar staan.
 *
 * Het schema komt hier van Flyway, net als in productie (`%prod.quarkus.flyway.schemas`).
 * Dat test de ordening waar het echt op aankomt: het schema moet bestaan vóórdat de wrapper
 * zijn tabel aanmaakt. De indeling wijkt wel af — in productie landen de migraties zelf óók
 * in het magazijnschema, hier blijft `public` het eerste schema en dus de plek voor de
 * migraties en `flyway_schema_history`.
 *
 * Dat de configuratie van de service zélf een prefix oplevert, borgt [LdvTabelnaamConfigTest];
 * deze test toont aan dat de prefix vervolgens ook werkt.
 */
@QuarkusTest
@TestProfile(LdvSchemaGekwalificeerdTest.GekwalificeerdeTabelProfile::class)
class LdvSchemaGekwalificeerdTest {

    @Inject
    lateinit var dataSource: DataSource

    class GekwalificeerdeTabelProfile : QuarkusTestProfile {
        override fun getConfigOverrides(): Map<String, String> = mapOf(
            "quarkus.flyway.schemas" to "public,$LDV_SCHEMA",
            "logboekdataverwerking.enabled" to "true",
            "logboekdataverwerking.dbms" to "postgresql",
            "logboekdataverwerking.span-processor" to "simple",
            "logboekdataverwerking.write-failure-policy" to "fail-closed",
            "logboekdataverwerking.postgresql.url" to "\${quarkus.datasource.jdbc.url}",
            "logboekdataverwerking.postgresql.username" to "\${quarkus.datasource.username}",
            "logboekdataverwerking.postgresql.password" to "\${quarkus.datasource.password}",
            "logboekdataverwerking.postgresql.table" to "$LDV_SCHEMA.$LDV_TABEL",
        )
    }

    /**
     * Alles wordt als verschil gemeten in plaats van absoluut. Een eigen TestProfile levert
     * vandaag een verse database op, maar die afspraak is nergens vastgelegd; zodra een
     * database hergebruikt wordt, zeggen absolute tellingen niets meer over wat déze test
     * heeft veroorzaakt.
     */
    @Test
    fun `elke logregel landt in het schema uit de tabelnaam en nergens anders`() {
        val gekwalificeerdVooraf = aantalLogregels("$LDV_SCHEMA.$LDV_TABEL")
        val standaardVooraf = aantalLogregels(LDV_TABEL)

        leverAan("Eerste aanlevering")
        assertEquals(
            gekwalificeerdVooraf + 1,
            aantalLogregels("$LDV_SCHEMA.$LDV_TABEL"),
            "een aanlevering hoort precies één logregel in $LDV_SCHEMA op te leveren",
        )

        leverAan("Tweede aanlevering")
        assertEquals(
            gekwalificeerdVooraf + 2,
            aantalLogregels("$LDV_SCHEMA.$LDV_TABEL"),
            "ook de tweede aanlevering hoort in $LDV_SCHEMA te landen, niet alleen de eerste",
        )

        // Zonder deze controle zou een genegeerde prefix onopgemerkt blijven wanneer de
        // wrapper daarnaast óók een ongekwalificeerde tabel zou vullen.
        assertEquals(
            standaardVooraf,
            aantalLogregels(LDV_TABEL),
            "er hoort niets bij te komen in het default-schema",
        )
    }

    private fun leverAan(onderwerp: String) {
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
            .statusCode(201)
        // De 201 impliceert een bevestigde logregel: span-processor=simple exporteert
        // synchroon op de request-thread en fail-closed maakt er anders een 500 van.
    }

    /** Telt de logregels van het aanleverpad, of 0 als de tabel nog niet bestaat. */
    private fun aantalLogregels(tabel: String): Int = dataSource.connection.use { connectie ->
        if (!bestaat(connectie, tabel)) return 0

        // Een tabelnaam is niet parameteriseerbaar in een FROM-clausule; de waarde komt uit
        // constanten in deze klasse, niet uit invoer.
        connectie.prepareStatement(
            "SELECT count(*) AS aantal FROM $tabel WHERE name = 'aanleveren-bericht'",
        ).use { telling ->
            telling.executeQuery().use { rijen ->
                rijen.next()

                return rijen.getInt("aantal")
            }
        }
    }

    private fun bestaat(connectie: Connection, tabel: String): Boolean =
        connectie.prepareStatement("SELECT to_regclass(?) IS NOT NULL AS bestaat").use { bestaan ->
            bestaan.setString(1, tabel)
            bestaan.executeQuery().use { rijen ->
                rijen.next()

                rijen.getBoolean("bestaat")
            }
        }

    companion object {
        const val LDV_SCHEMA = "magazijnschema"
        const val LDV_TABEL = "logboek_dataverwerkingen"
    }
}
