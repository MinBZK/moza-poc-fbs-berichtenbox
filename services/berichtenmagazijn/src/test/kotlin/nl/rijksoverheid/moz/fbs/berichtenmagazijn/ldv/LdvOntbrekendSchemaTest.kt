package nl.rijksoverheid.moz.fbs.berichtenmagazijn.ldv

import io.micrometer.core.instrument.MeterRegistry
import io.quarkus.narayana.jta.QuarkusTransaction
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.junit.QuarkusTestProfile
import io.quarkus.test.junit.TestProfile
import io.restassured.RestAssured.given
import io.restassured.http.ContentType
import jakarta.inject.Inject
import java.time.Instant
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import java.util.logging.Handler
import java.util.logging.LogRecord
import java.util.logging.Logger
import javax.sql.DataSource
import nl.rijksoverheid.moz.fbs.berichtenmagazijn.opslag.Bericht
import nl.rijksoverheid.moz.fbs.berichtenmagazijn.opslag.BerichtRepository
import nl.rijksoverheid.moz.fbs.common.exception.UncaughtExceptionMapper
import nl.rijksoverheid.moz.fbs.kern.identificatie.Bsn
import nl.rijksoverheid.moz.fbs.kern.identificatie.Oin
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
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

    @Inject
    lateinit var registry: MeterRegistry

    @Inject
    lateinit var berichten: BerichtRepository

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
        val geweigerdVooraf = registry.get("fbs.logboek.geweigerd").counter().count()
        // Welke exceptie het vangnet kreeg: telt de teller niet, dan zegt de melding waarom.
        val gevangen = CopyOnWriteArrayList<String>()
        val vanger = object : Handler() {
            override fun publish(record: LogRecord) {
                gevangen += String.format(record.message, *(record.parameters ?: emptyArray()))
            }

            override fun flush() = Unit

            override fun close() = Unit
        }
        val vangnetLogger = Logger.getLogger(UncaughtExceptionMapper::class.java.name)

        vangnetLogger.addHandler(vanger)

        // Elke code bestaat vanaf de start, ook zonder één geval: anders mist increase() het eerste.
        LogboekMeting.Waarschuwing.entries.forEach { soort ->
            assertNotNull(registry.find("fbs.logboek.waarschuwing").tag("code", soort.code).counter(), soort.code)
        }

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
        // De weigering moet als zodanig herkenbaar zijn, niet alleen als een 500 tussen de andere.
        vangnetLogger.removeHandler(vanger)
        assertEquals(
            geweigerdVooraf + 1,
            registry.get("fbs.logboek.geweigerd").counter().count(),
            "geweigerd niet geteld; het vangnet logde: $gevangen",
        )
    }

    // Bij een @Logboek-methode schrijft de wrapper pas ná de methode: het bericht is dan al
    // verwijderd. Dat is geen weigering, maar een verwerking zonder logregel.
    @Test
    fun `een verwijdering zonder logregel telt als afwijking, niet als weigering`() {
        val berichtId = UUID.randomUUID()

        QuarkusTransaction.requiringNew().run {
            berichten.save(
                Bericht(
                    berichtId = berichtId,
                    afzender = Oin("00000001003214345000"),
                    ontvanger = Bsn("999993653"),
                    onderwerp = "Verwijderen zonder logboekschema",
                    inhoud = "Inhoud",
                    tijdstipOntvangst = Instant.now(),
                    publicatietijdstip = Instant.now(),
                ),
            )
        }

        val code = LogboekMeting.ZONDER_LOGREGEL_TOKEN
        val zonderVooraf = registry.get("fbs.logboek.waarschuwing").tag("code", code).counter().count()
        val geweigerdVooraf = registry.get("fbs.logboek.geweigerd").counter().count()

        given()
            .header("X-Ontvanger", "BSN:999993653")
            .`when`()
            .delete("/api/v1/berichten/$berichtId")
            .then()
            .statusCode(500)

        assertEquals(zonderVooraf + 1, registry.get("fbs.logboek.waarschuwing").tag("code", code).counter().count())
        assertEquals(geweigerdVooraf, registry.get("fbs.logboek.geweigerd").counter().count())
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
