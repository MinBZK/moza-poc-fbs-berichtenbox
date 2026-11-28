package nl.rijksoverheid.moz.fbs.berichtenuitvraag.uitvraag

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import io.quarkus.test.common.http.TestHTTPResource
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.junit.TestProfile
import io.restassured.RestAssured.given
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File
import java.net.URL

/**
 * Elke meetwaarde die het dashboard of een meldingsregel van deze dienst noemt, bestaat ook: hij
 * staat in wat de beheerpoort levert. Een paneel op een hernoemde of nooit geregistreerde meter
 * blijft anders leeg, en een regel erop meldt nooit, zonder dat iemand het ziet.
 *
 * De bestanden staan in `beheer/` in de root van de repository; ze horen bij het product, niet bij
 * deze module. Wat een dienst pas na verkeer registreert, staat in [PAS_NA_VERKEER] met de reden.
 */
@QuarkusTest
@TestProfile(MockSessiecacheProfile::class)
class BeheerbestandenTest {

    // Relatief aan het beheer-root-pad (`/q`), dus `/q/metrics`.
    @TestHTTPResource(value = "/metrics", management = true)
    lateinit var meetgegevens: URL

    private val dashboard = File("../../beheer/dashboards/$DIENST.json")
    private val regels = File("../../beheer/meldingen/$DIENST.yml")

    @Test
    fun `elke meetwaarde uit het dashboard en de regels bestaat`() {
        // Eén verzoek, zodat de HTTP-meters van de dienst er staan.
        given().`when`().get(OPWARMEN).then().statusCode(200)

        val scrape = given().`when`().get(meetgegevens).then().statusCode(200).extract().asString()
        File("target/meetgegevens-scrape.txt").writeText(scrape)

        val aanwezig = scrape.lines()
            .filter { it.isNotBlank() && !it.startsWith("#") }
            .map { it.substringBefore('{').substringBefore(' ') }
            .toSet()

        val genoemd = expressies().flatMap(::meetwaarden).toSet()
        val ontbrekend = genoemd - aanwezig - PAS_NA_VERKEER.keys - PROMETHEUS_EIGEN

        assertTrue(genoemd.isNotEmpty(), "geen enkele meetwaarde gevonden; leest de test de bestanden wel?")
        assertEquals(emptySet<String>(), ontbrekend, "genoemd maar niet geleverd door de dienst")
    }

    // De dashboards en regels kiezen op deze twee labels; zonder ze tellen alle diensten en omgevingen door elkaar.
    @Test
    fun `elke reeks draagt de dienst en de omgeving`() {
        given().`when`().get(OPWARMEN).then().statusCode(200)

        val reeksen = given().`when`().get(meetgegevens).then().statusCode(200).extract().asString()
            .lines()
            .filter { it.isNotBlank() && !it.startsWith("#") }
        val zonder = reeksen.filterNot { "dienst=\"$DIENST\"" in it && "omgeving=\"lokaal\"" in it }

        assertTrue(reeksen.isNotEmpty(), "geen reeksen")
        assertEquals(emptyList<String>(), zonder, "reeksen zonder dienst of omgeving")
    }

    // Een vrijstelling die niemand meer gebruikt, verbergt anders een hernoemde meter bij de volgende.
    @Test
    fun `elke vrijstelling wordt gebruikt`() {
        val genoemd = expressies().flatMap(::meetwaarden).toSet()

        assertEquals(emptySet<String>(), PAS_NA_VERKEER.keys - genoemd, "vrijgesteld maar nergens genoemd")
    }

    @Test
    fun `elke regel noemt een ernst, een samenvatting en de handleiding`() {
        val blokken = regels.readText().split(Regex("""(?m)^\s*- alert: """)).drop(1)

        assertTrue(blokken.isNotEmpty(), "geen regels in $regels")

        for (blok in blokken) {
            val naam = blok.lineSequence().first().trim()

            assertTrue(Regex("""(?m)^\s+severity: (waarschuwing|kritiek)$""").containsMatchIn(blok), "$naam: severity")
            assertTrue(Regex("""(?m)^\s+summary: \S""").containsMatchIn(blok), "$naam: summary")
            assertTrue(Regex("""(?m)^\s+runbook_url: \S*docs/operator-handleiding""").containsMatchIn(blok), "$naam: runbook_url")
            assertTrue(Regex("""(?m)^\s+for: \d+[smh]$""").containsMatchIn(blok), "$naam: for")
        }
    }

    private fun expressies(): List<String> {
        val uitDashboard = mutableListOf<String>()

        verzamel(ObjectMapper().readTree(dashboard), uitDashboard)

        // Eén regel per expressie: een `expr: |`-blok zou deze lezer missen, dus dat mag niet.
        val tekst = regels.readText()
        assertTrue(!Regex("""(?m)^\s+expr:\s*[|>]""").containsMatchIn(tekst), "$regels: expr hoort op één regel")

        val uitRegels = Regex("""(?m)^\s+expr:\s*(.+)$""").findAll(tekst).map { it.groupValues[1] }.toList()

        assertTrue(uitDashboard.isNotEmpty(), "geen expressies in $dashboard")
        assertTrue(uitRegels.isNotEmpty(), "geen expressies in $regels")

        return uitDashboard + uitRegels
    }

    // `expr` in panelen, `query` in templatevariabelen (`label_values(<meetwaarde>, ...)`).
    private fun verzamel(knoop: JsonNode, uit: MutableList<String>) {
        knoop.fields().forEach { (sleutel, waarde) ->
            if ((sleutel == "expr" || sleutel == "query") && waarde.isTextual) uit += waarde.asText()

            verzamel(waarde, uit)
        }

        if (knoop.isArray) knoop.forEach { verzamel(it, uit) }
    }

    private companion object {
        const val DIENST = "berichtenuitvraag"

        const val OPWARMEN = "/api/v1/openapi.json"

        /** De namen van de reeksen, zonder wat tussen aanhalingstekens staat (labelwaarden). */
        fun meetwaarden(expressie: String): List<String> =
            NAAM.findAll(expressie.replace(Regex(""""[^"]*""""), "\"\"")).map { it.value }.toList()

        private val NAAM = Regex("""\b(?:fbs|http|jvm|process|system|agroal|redis|ft|up)(?:_[a-z0-9_]+)?\b(?!\s*\()""")

        /** Wat Prometheus zelf per target maakt; de dienst levert het niet. */
        val PROMETHEUS_EIGEN = setOf("up")

        /** Meters die de dienst pas bij het eerste geval registreert, met de reden. */
        val PAS_NA_VERKEER = mapOf(
            "http_client_requests_seconds_count" to
                "Quarkus registreert de client-meters bij de eerste uitgaande aanroep; hier is de sessiecache gemockt",
            "http_client_requests_seconds_sum" to "idem",
        )
    }
}
