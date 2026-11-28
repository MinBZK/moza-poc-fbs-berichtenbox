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
 * Elke meetwaarde die het dashboard of een alertregel van deze dienst noemt, bestaat ook: hij staat
 * in wat de beheerpoort levert, met de labels en de letterlijke labelwaarden waarop gekozen wordt.
 * Een paneel op een hernoemde meter of een verkeerd gespelde labelwaarde blijft anders leeg, en een
 * regel erop meldt nooit, zonder dat iemand het ziet.
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
    fun `elke meetwaarde uit het dashboard en de regels bestaat, met de labels waarop gekozen wordt`() {
        val reeksen = scrape()
        val fouten = mutableListOf<String>()

        for (selector in expressies().flatMap(::selectors)) {
            if (selector.naam in PAS_NA_VERKEER || selector.naam in PROMETHEUS_EIGEN) continue

            val kandidaten = reeksen.filter { it.naam == selector.naam }

            if (kandidaten.isEmpty()) {
                fouten += "${selector.naam}: geen reeks"

                continue
            }

            for (matcher in selector.matchers) {
                if (matcher.label in DOOR_PROMETHEUS) continue

                if (kandidaten.none { matcher.label in it.labels }) {
                    fouten += "${selector.naam}: geen label ${matcher.label}"
                } else if (matcher.letterlijk && "${selector.naam}{${matcher.label}=${matcher.waarde}}" !in ELDERS &&
                    kandidaten.none { it.labels[matcher.label] == matcher.waarde }
                ) {
                    fouten += "${selector.naam}{${matcher.label}=\"${matcher.waarde}\"}: geen reeks met die waarde"
                }
            }
        }

        assertEquals(emptyList<String>(), fouten.distinct(), "genoemd maar niet geleverd door de dienst")
    }

    // Een meetwaarde zonder `{...}` ontsnapt aan de labeltoets hierboven, en `__name__` aan de naamtoets.
    @Test
    fun `elke meetwaarde wordt als selector genoemd`() {
        val zonderSelector = expressies().flatMap { expressie ->
            val selectorNamen = selectors(expressie).map { it.naam }.toSet()

            BEKENDE_NAAM.findAll(zonderLabelwaarden(expressie)).map { it.value }.filter { it !in selectorNamen }.toList()
        }

        assertEquals(emptyList<String>(), zonderSelector.distinct(), "noem een meetwaarde altijd met {...}")
        assertTrue(expressies().none { "__name__" in it }, "__name__ ontsnapt aan de toets")
    }

    // Een regel die een andere dienst of ook de previews meet, meldt over iets anders dan hij zegt.
    @Test
    fun `elke selector in de regels kiest deze dienst en sluit previews uit`() {
        val fouten = regelExpressies().flatMap(::selectors)
            .filter { it.naam !in PROMETHEUS_EIGEN }
            .filterNot { s ->
                s.matchers.any { it.label == "dienst" && it.operator == "=" && it.waarde == DIENST } &&
                    s.matchers.any { it.label == "omgeving" && it.operator == "!~" && it.waarde == "pr-.*" }
            }
            .map { it.naam }

        assertEquals(emptyList<String>(), fouten, "selector zonder dienst=\"$DIENST\" en omgeving!~\"pr-.*\"")
    }

    // De dashboards en regels kiezen op deze labels; zonder ze tellen alle diensten en omgevingen door elkaar.
    @Test
    fun `elke reeks draagt de labels van de dienst`() {
        val zonder = scrape().filterNot { reeks ->
            EIGEN_LABELS.all { (label, patroon) -> reeks.labels[label]?.let(Regex(patroon)::matches) == true }
        }

        assertEquals(emptyList<Reeks>(), zonder, "reeksen zonder ${EIGEN_LABELS.keys}")
    }

    // Een vrijstelling die niemand meer gebruikt, verbergt anders een hernoemde meter bij de volgende.
    @Test
    fun `elke vrijstelling wordt gebruikt`() {
        val selectoren = expressies().flatMap(::selectors)
        val namen = selectoren.map { it.naam }.toSet()
        val waarden = selectoren.flatMap { s -> s.matchers.map { "${s.naam}{${it.label}=${it.waarde}}" } }.toSet()

        assertEquals(emptySet<String>(), PAS_NA_VERKEER.keys - namen, "vrijgesteld maar nergens genoemd")
        assertEquals(emptySet<String>(), ELDERS.keys - waarden, "vrijgesteld maar nergens genoemd")
    }

    @Test
    fun `elke regel noemt een ernst, een samenvatting en de handleiding`() {
        val blokken = regels.readText().split(Regex("""(?m)^\s*- alert: """)).drop(1)

        assertTrue(blokken.isNotEmpty(), "geen regels in $regels")

        for (blok in blokken) {
            val naam = blok.lineSequence().first().trim()

            // De gangbare waarden (kube-prometheus, Grafana), waarop routeringsregels meestal kiezen.
            assertTrue(Regex("""(?m)^\s+severity: (critical|warning)$""").containsMatchIn(blok), "$naam: severity")
            assertTrue(Regex("""(?m)^\s+summary: \S""").containsMatchIn(blok), "$naam: summary")
            assertTrue(Regex("""(?m)^\s+runbook_url: \S*docs/operator-handleiding""").containsMatchIn(blok), "$naam: runbook_url")
            assertTrue(Regex("""(?m)^\s+for: \d+[smh]$""").containsMatchIn(blok), "$naam: for")
        }
    }

    private fun scrape(): List<Reeks> {
        // Eén verzoek, zodat de HTTP-meters van de dienst er staan.
        given().`when`().get(OPWARMEN).then().statusCode(200)

        val tekst = given().`when`().get(meetgegevens).then().statusCode(200).extract().asString()
        File("target/meetgegevens-scrape.txt").writeText(tekst)

        val reeksen = tekst.lines().filter { it.isNotBlank() && !it.startsWith("#") }.map { regel ->
            val naam = regel.substringBefore('{').substringBefore(' ')
            val labels = if ('{' in regel) LABEL.findAll(regel.substringAfter('{').substringBeforeLast('}')) else emptySequence()

            Reeks(naam, labels.associate { it.groupValues[1] to it.groupValues[2] })
        }

        assertTrue(reeksen.isNotEmpty(), "geen reeksen; leest de test de beheerpoort wel?")

        return reeksen
    }

    private fun expressies(): List<String> {
        val uitDashboard = mutableListOf<String>()

        verzamel(ObjectMapper().readTree(dashboard), uitDashboard)

        assertTrue(uitDashboard.isNotEmpty(), "geen expressies in $dashboard")

        return uitDashboard + regelExpressies()
    }

    // Eén tekstregel per expressie, zonder aanhalingstekens eromheen: een `expr: |`-blok, een YAML-string
    // met `\"` erin of een kale expr die op de volgende regel doorloopt, zou deze lezer anders missen.
    private fun regelExpressies(): List<String> {
        val tekst = regels.readText()

        assertTrue(!Regex("""(?m)^\s+expr:\s*[|>"']""").containsMatchIn(tekst), "$regels: expr hoort kaal op één regel")
        assertTrue(
            !Regex("""(?m)^\s+expr:.*\n(?!\s+(?:for|labels|annotations):)\s+\S""").containsMatchIn(tekst),
            "$regels: na een expr hoort for, labels of annotations; een vervolgregel ontsnapt aan de toets",
        )

        val uitRegels = Regex("""(?m)^\s+expr:\s*(.+)$""").findAll(tekst).map { it.groupValues[1] }.toList()

        assertTrue(uitRegels.isNotEmpty(), "geen expressies in $regels")

        return uitRegels
    }

    // `expr` in panelen, `query` in templatevariabelen (`label_values(<selector>, ...)`).
    private fun verzamel(knoop: JsonNode, uit: MutableList<String>) {
        knoop.properties().forEach { (sleutel, waarde) ->
            if ((sleutel == "expr" || sleutel == "query") && waarde.isTextual) uit += waarde.asText()

            verzamel(waarde, uit)
        }

        if (knoop.isArray) knoop.forEach { verzamel(it, uit) }
    }

    private data class Reeks(val naam: String, val labels: Map<String, String>)

    private data class Matcher(val label: String, val operator: String, val waarde: String) {
        /** Een letterlijke gelijkheid, geen regex en geen dashboardvariabele: die waarde hoort te bestaan. */
        val letterlijk = operator == "=" && '$' !in waarde
    }

    private data class Selector(val naam: String, val matchers: List<Matcher>)

    private companion object {
        const val DIENST = "berichtenuitvraag"

        const val OPWARMEN = "/api/v1/openapi.json"

        /** De labels van de dienst op elke reeks, als patroon. */
        val EIGEN_LABELS = mapOf("dienst" to DIENST, "omgeving" to "lokaal")

        private val SELECTOR = Regex("""([a-zA-Z_:][a-zA-Z0-9_:]*)\s*\{([^}]*)}""")
        private val MATCHER = Regex("""([a-zA-Z_][a-zA-Z0-9_]*)\s*(=~|!~|!=|=)\s*(?:"([^"]*)"|'([^']*)')""")
        private val LABEL = Regex("""([a-zA-Z_][a-zA-Z0-9_]*)="((?:[^"\\]|\\.)*)"""")

        /** De bekende voorvoegsels, voor de toets dat een meetwaarde nooit zonder selector staat. */
        private val BEKENDE_NAAM = Regex("""\b(?:fbs|http|jvm|process|system|agroal|redis|ft|worker|netty)_[a-z0-9_]+\b""")

        fun zonderLabelwaarden(expressie: String) = expressie.replace(Regex(""""[^"]*""""), "\"\"")

        fun selectors(expressie: String): List<Selector> =
            SELECTOR.findAll(expressie).map { s ->
                Selector(s.groupValues[1], MATCHER.findAll(s.groupValues[2]).map { Matcher(it.groupValues[1], it.groupValues[2], it.groupValues[3] + it.groupValues[4]) }.toList())
            }.toList()

        /** Wat Prometheus zelf per target maakt; de dienst levert het niet. */
        val PROMETHEUS_EIGEN = setOf("up")

        /** Labels die de scraper toevoegt, niet de dienst. */
        val DOOR_PROMETHEUS = setOf("instance", "job")

        /** Meters die de dienst pas bij het eerste geval registreert, met de reden. */
        val PAS_NA_VERKEER = mapOf(
            "http_client_requests_seconds_count" to
                "Quarkus registreert de client-meters bij de eerste uitgaande aanroep; hier is de sessiecache gemockt",
            "http_client_requests_seconds_sum" to "idem",
        )

        /** Letterlijke labelwaarden die in deze test niet ontstaan, met de reden. */
        val ELDERS = emptyMap<String, String>()
    }
}
