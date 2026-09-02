package nl.rijksoverheid.moz.fbs.magazijnsimulator.beheer

import io.quarkus.test.junit.QuarkusTest
import io.restassured.RestAssured.given
import io.restassured.http.ContentType
import jakarta.inject.Inject
import nl.rijksoverheid.moz.fbs.magazijnsimulator.MagazijnTestBasis
import nl.rijksoverheid.moz.fbs.magazijnsimulator.magazijn.GesimuleerdeMagazijnen
import org.hamcrest.Matchers.contains
import org.hamcrest.Matchers.equalTo
import org.hamcrest.Matchers.greaterThan
import org.hamcrest.Matchers.hasItem
import org.hamcrest.Matchers.hasSize
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource

/**
 * Het bedieningspaneel: vullen, terugzetten en bijsturen.
 *
 * Een demo die niet herhaalbaar is, is niet te oefenen en niet te vertrouwen. Dat is wat deze tests
 * vastpinnen: dezelfde handeling geeft dezelfde uitgangssituatie, en terugzetten zet écht alles
 * terug — ook het gedrag.
 */
@QuarkusTest
class BeheerTest : MagazijnTestBasis() {

    @Inject
    lateinit var magazijnen: GesimuleerdeMagazijnen

    @AfterEach
    fun herstel() {
        magazijnen.herstelGedrag()
    }

    @Test
    fun `het overzicht toont elk magazijn met zijn huidige gedrag`() {
        given()
            .`when`().get("/beheer/magazijnen")
            .then()
            .statusCode(200)
            .body("oin", hasItem(MAGAZIJN))
            .body("findAll { it.oin == '$MAGAZIJN' }.modus", hasItem("NORMAAL"))
    }

    @Test
    fun `seed zet berichten klaar in elk magazijn, voor elke ontvanger`() {
        val uitkomst = seed(aantal = 3, ontvangers = listOf(ONTVANGER, TWEEDE_ONTVANGER))

        assertEquals(2, uitkomst.getInt("ontvangers"))
        assertEquals(uitkomst.getInt("magazijnen") * 2 * 3, uitkomst.getInt("berichten"))

        given()
            .header(ONTVANGER_HEADER, ONTVANGER)
            .`when`().get("$BASIS/berichten")
            .then()
            .statusCode(200)
            .body("totalElements", equalTo(3))

        given()
            .header(ONTVANGER_HEADER, TWEEDE_ONTVANGER)
            .`when`().get("$BASIS/berichten")
            .then()
            .statusCode(200)
            .body("totalElements", equalTo(3))
    }

    /**
     * Herhaalbaarheid is het punt van dit endpoint. Zou de seed loten, dan is een demo die je oefent
     * niet dezelfde demo als die je geeft.
     */
    @Test
    fun `twee keer seeden na leegmaken geeft dezelfde berichten`() {
        seed(aantal = 5)

        val eerste = berichtIds()

        legen()
        seed(aantal = 5)

        assertEquals(eerste, berichtIds(), "dezelfde handeling hoort dezelfde uitgangssituatie te geven")
    }

    /**
     * Twee magazijnen mogen in werkelijkheid hetzelfde bericht-nummer uitdelen, maar de sessiecache
     * van de uitvraag slaat berichten op zonder magazijn in de sleutel. Zolang dat gebrek openstaat,
     * hoort een demo daar niet per ongeluk overheen te vallen.
     */
    @Test
    fun `de bericht-nummers verschillen over magazijnen heen`() {
        seed(aantal = 5)

        val vanEerste = berichtIds(MAGAZIJN)
        val vanTweede = berichtIds(TWEEDE_MAGAZIJN)

        assertEquals(emptySet<String>(), vanEerste.intersect(vanTweede))
    }

    @Test
    fun `een deel van de berichten krijgt een bijlage`() {
        val uitkomst = seed(aantal = 8, bijlageElke = 4)

        assertEquals(uitkomst.getInt("berichten") / 4, uitkomst.getInt("bijlagen"))

        given()
            .header(ONTVANGER_HEADER, ONTVANGER)
            .`when`().get("$BASIS/berichten")
            .then()
            .statusCode(200)
            .body("berichten.findAll { it.aantalBijlagen > 0 }", hasSize<Any>(2))
    }

    @Test
    fun `zonder bijlagen gevraagd komen er ook geen`() {
        assertEquals(0, seed(aantal = 4, bijlageElke = 0).getInt("bijlagen"))
    }

    @Test
    fun `legen haalt de berichten weg`() {
        seed(aantal = 4)
        legen()

        given()
            .header(ONTVANGER_HEADER, ONTVANGER)
            .`when`().get("$BASIS/berichten")
            .then()
            .statusCode(200)
            .body("totalElements", equalTo(0))
    }

    /**
     * "Terug naar de begintoestand" hoort ook het gedrag te omvatten. Zonder dat staat een magazijn
     * dat tijdens de vorige demo op storing is gezet er de volgende keer nog zo bij.
     */
    @Test
    fun `legen zet ook het gedrag terug`() {
        zetGedrag(MAGAZIJN, "STUK")

        given().header(ONTVANGER_HEADER, ONTVANGER).`when`().get("$BASIS/berichten").then().statusCode(503)

        legen()

        given().header(ONTVANGER_HEADER, ONTVANGER).`when`().get("$BASIS/berichten").then().statusCode(200)
    }

    /**
     * Niet alleen het magazijn dat je toevallig bevraagt, maar de hele set — en niet alleen wat het
     * verkeer doet, maar ook wat het overzicht toont. Dat is de knop "toon magazijnen en hun gedrag"
     * op het bedieningspaneel, en als die de oude stand laat zien lijkt het terugzetten mislukt
     * terwijl de keten allang weer normaal doet.
     *
     * Een eerdere versie bracht bij het terugzetten alleen ontbrekende rijen aan en liet bestaande
     * staan; dan blijft precies dit zichtbaar hangen.
     */
    @Test
    fun `legen zet het gedrag van de hele set terug, ook in het overzicht`() {
        zetGedrag(MAGAZIJN, "UIT")
        zetGedrag(TWEEDE_MAGAZIJN, "STUK")
        zetGedrag(DERDE_MAGAZIJN, "HAPERT")

        given().`when`().get("/beheer/magazijnen")
            .then().statusCode(200)
            .body("modus", contains("UIT", "STUK", "HAPERT"))

        legen()

        // De testconfiguratie geeft geen van de drie een volgnummer, dus "terug naar de configuratie"
        // is voor alle drie NORMAAL.
        given().`when`().get("/beheer/magazijnen")
            .then().statusCode(200)
            .body("modus", contains("NORMAAL", "NORMAAL", "NORMAAL"))
    }

    @Test
    fun `het gedrag is tijdens een demo bij te sturen zonder iets opnieuw te starten`() {
        zetGedrag(MAGAZIJN, "STUK")

        given().header(ONTVANGER_HEADER, ONTVANGER).`when`().get("$BASIS/berichten").then().statusCode(503)

        zetGedrag(MAGAZIJN, "NORMAAL")

        given().header(ONTVANGER_HEADER, ONTVANGER).`when`().get("$BASIS/berichten").then().statusCode(200)
    }

    @Test
    fun `wat het verzoek weglaat komt uit de standaardwaardes van die modus`() {
        given()
            .contentType(ContentType.JSON)
            .body("""{"modus": "TRAAG"}""")
            .`when`().put("/beheer/magazijnen/$MAGAZIJN/gedrag")
            .then()
            .statusCode(200)
            .body("modus", equalTo("TRAAG"))
            .body("latencyP95Ms", greaterThan(0))
    }

    /**
     * Eén aanroep voor een hele reeks. Een bedieningspaneel dat "zet er k van de honderd op storing"
     * aanbiedt, zou anders bij elke klik honderd verzoeken doen — en dan is de knop trager dan de
     * demo die hij moet ondersteunen.
     */
    @Test
    fun `het gedrag van meerdere magazijnen is in één aanroep bij te stellen`() {
        given()
            .contentType(ContentType.JSON)
            .body(
                """
                {"aanpassingen": [
                  {"oin": "$MAGAZIJN", "modus": "STUK"},
                  {"oin": "$TWEEDE_MAGAZIJN", "modus": "TRAAG"}
                ]}
                """.trimIndent(),
            )
            .`when`().put("/beheer/gedrag")
            .then()
            .statusCode(200)
            .body("aangepast", equalTo(2))
            .body("onbekend", hasSize<Any>(0))

        given().header(ONTVANGER_HEADER, ONTVANGER).`when`().get("$BASIS/berichten").then().statusCode(503)
    }

    /**
     * Doorgaan en melden wat er niet kon, in plaats van stoppen bij de eerste. Bij een lijst van
     * honderd is een aanroep die halverwege afbreekt onbruikbaarder dan een die zegt welke twee hij
     * niet kende.
     */
    @Test
    fun `een onbekende OIN in een bulk-aanpassing wordt gemeld en houdt de rest niet tegen`() {
        given()
            .contentType(ContentType.JSON)
            .body(
                """
                {"aanpassingen": [
                  {"oin": "$MAGAZIJN", "modus": "TRAAG"},
                  {"oin": "00000009000000009999", "modus": "STUK"}
                ]}
                """.trimIndent(),
            )
            .`when`().put("/beheer/gedrag")
            .then()
            .statusCode(200)
            .body("aangepast", equalTo(1))
            .body("onbekend", contains("00000009000000009999"))
    }

    @Test
    fun `het gedrag van een onbekend magazijn bijstellen levert 404 problem+json`() {
        given()
            .contentType(ContentType.JSON)
            .body("""{"modus": "STUK"}""")
            .`when`().put("/beheer/magazijnen/00000009000000009999/gedrag")
            .then()
            .statusCode(404)
            .contentType("application/problem+json")
    }

    @Test
    fun `een onbekende modus is een clientfout en geen serverfout`() {
        given()
            .contentType(ContentType.JSON)
            .body("""{"modus": "SOMS"}""")
            .`when`().put("/beheer/magazijnen/$MAGAZIJN/gedrag")
            .then()
            .statusCode(400)
            .contentType("application/problem+json")
    }

    /**
     * Getallen die de gevraagde modus tegenspreken, worden geweigerd in plaats van stilzwijgend
     * gehoorzaamd. Zonder deze grens is een magazijn in te stellen dat in het overzicht als
     * haperend of weigerend staat en zich in werkelijkheid anders gedraagt — en dan wijst de demo
     * de schuld toe aan het stelsel in plaats van aan de knop.
     */
    @ParameterizedTest
    @ValueSource(
        strings = [
            """{"modus": "HAPERT", "foutkans": 0}""",
            """{"modus": "WEIGERT", "foutStatus": 503}""",
            """{"modus": "STUK", "foutStatus": 403}""",
            """{"modus": "TRAAG", "latencyP50Ms": 0, "latencyP95Ms": 0}""",
        ],
    )
    fun `getallen die niet bij de modus passen zijn een clientfout`(body: String) {
        given()
            .contentType(ContentType.JSON)
            .body(body)
            .`when`().put("/beheer/magazijnen/$MAGAZIJN/gedrag")
            .then()
            .statusCode(400)
            .contentType("application/problem+json")
    }

    /**
     * Dezelfde grens op het bulkpad, en met een geldige regel ernaast: de KDoc van
     * `zetGedragInBulk` belooft dat een ongeldige regel de hele lijst tegenhoudt in plaats van hem
     * half door te voeren. Zonder de na-controle zou een implementatie die regel voor regel
     * toepast er precies zo uitzien.
     */
    @Test
    fun `een ongeldige regel houdt de hele bulk tegen`() {
        val voorAf = modusVan(TWEEDE_MAGAZIJN)

        given()
            .contentType(ContentType.JSON)
            .body(
                """
                {"aanpassingen": [
                  {"oin": "$TWEEDE_MAGAZIJN", "modus": "STUK"},
                  {"oin": "$MAGAZIJN", "modus": "HAPERT", "foutkans": 0}
                ]}
                """.trimIndent(),
            )
            .`when`().put("/beheer/gedrag")
            .then()
            .statusCode(400)
            .contentType("application/problem+json")

        assertEquals(voorAf, modusVan(TWEEDE_MAGAZIJN), "de geldige regel ervóór hoort niet toegepast te zijn")
    }

    private fun modusVan(oin: String): String = given()
        .`when`().get("/beheer/magazijnen")
        .then()
        .statusCode(200)
        .extract().path("find { it.oin == '$oin' }.modus")

    /**
     * Twee keer vullen zonder ertussen te legen. De bericht-nummers zijn afgeleid, dus de tweede
     * ronde biedt exact dezelfde rijen aan; zonder opvang zou dat een 500 zijn waarin niets staat
     * over de oorzaak of de uitweg. Wie tijdens de voorbereiding besluit dat twintig berichten te
     * weinig zijn, draait gewoon opnieuw.
     */
    @Test
    fun `twee keer vullen zonder legen slaat over wat er al staat`() {
        val eerste = seed(aantal = 3)

        assertEquals(0, eerste.getInt("overgeslagen"))

        val tweede = seed(aantal = 3)

        assertEquals(0, tweede.getInt("berichten"))
        assertEquals(eerste.getInt("berichten"), tweede.getInt("overgeslagen"))

        given()
            .header(ONTVANGER_HEADER, ONTVANGER)
            .`when`().get("$BASIS/berichten")
            .then()
            .statusCode(200)
            .body("totalElements", equalTo(3))
    }

    @Test
    fun `meer berichten vragen vult aan in plaats van te knallen`() {
        seed(aantal = 3)

        val uitgebreid = seed(aantal = 5)

        assertEquals(uitgebreid.getInt("magazijnen") * 2, uitgebreid.getInt("berichten"))

        given()
            .header(ONTVANGER_HEADER, ONTVANGER)
            .`when`().get("$BASIS/berichten")
            .then()
            .statusCode(200)
            .body("totalElements", equalTo(5))
    }

    /** Een typefout in een JSON-lijst hoort geen serverfout te worden. */
    @Test
    fun `dezelfde ontvanger twee keer opgeven levert geen dubbele berichten op`() {
        val uitkomst = seed(aantal = 3, ontvangers = listOf(ONTVANGER, ONTVANGER))

        assertEquals(1, uitkomst.getInt("ontvangers"))

        given()
            .header(ONTVANGER_HEADER, ONTVANGER)
            .`when`().get("$BASIS/berichten")
            .then()
            .statusCode(200)
            .body("totalElements", equalTo(3))
    }

    /**
     * De grenzen van het gedrag komen hier uit een JSON-body en zijn dus invoer. Een negatieve
     * latency of een foutkans van twee hoort een 400 te zijn die zegt wat er mis is, geen 500 die de
     * aanroeper naar een niet-bestaande supportafdeling stuurt.
     */
    @ParameterizedTest
    @ValueSource(
        strings = [
            """{"modus": "TRAAG", "latencyP50Ms": -5}""",
            """{"modus": "TRAAG", "latencyP50Ms": 900, "latencyP95Ms": 10}""",
            """{"modus": "HAPERT", "foutkans": 2.0}""",
            """{"modus": "HAPERT", "foutkans": -0.1}""",
            """{"modus": "STUK", "foutStatus": 99}""",
            """{"modus": "STUK", "foutStatus": 600}""",
        ],
    )
    fun `een gedragswaarde buiten het bereik is een clientfout`(body: String) {
        given()
            .contentType(ContentType.JSON)
            .body(body)
            .`when`().put("/beheer/magazijnen/$MAGAZIJN/gedrag")
            .then()
            .statusCode(400)
            .contentType("application/problem+json")
    }

    @Test
    fun `seeden zonder ontvangers is een clientfout`() {
        given()
            .contentType(ContentType.JSON)
            .body("""{"ontvangers": []}""")
            .`when`().post("/beheer/seed")
            .then()
            .statusCode(400)
            .contentType("application/problem+json")
    }

    @Test
    fun `een onmogelijk aantal berichten is een clientfout`() {
        given()
            .contentType(ContentType.JSON)
            .body("""{"ontvangers": ["$ONTVANGER"], "berichtenPerMagazijn": 0}""")
            .`when`().post("/beheer/seed")
            .then()
            .statusCode(400)
    }

    /**
     * Het beheerpad valt buiten de simulatie. Zou het gedrag er wél op gelden, dan is een magazijn
     * dat je kapot hebt gezet niet meer te repareren of te vullen — en dan is een demo na één druk op
     * de knop onbruikbaar.
     */
    @Test
    fun `een kapot gezet magazijn blijft via het beheerpad bereikbaar`() {
        zetGedrag(MAGAZIJN, "STUK")

        given().`when`().get("/beheer/magazijnen").then().statusCode(200)
        given().`when`().post("/beheer/legen").then().statusCode(200)
    }

    private fun seed(
        aantal: Int,
        ontvangers: List<String> = listOf(ONTVANGER),
        bijlageElke: Int = 0,
    ) = given()
        .contentType(ContentType.JSON)
        .body(
            """
            {
              "ontvangers": [${ontvangers.joinToString(", ") { "\"$it\"" }}],
              "berichtenPerMagazijn": $aantal,
              "bijlageElke": $bijlageElke
            }
            """.trimIndent(),
        )
        .`when`().post("/beheer/seed")
        .then()
        .statusCode(200)
        .extract().jsonPath()

    private fun legen() {
        given().`when`().post("/beheer/legen").then().statusCode(200)
    }

    private fun zetGedrag(oin: String, modus: String) {
        given()
            .contentType(ContentType.JSON)
            .body("""{"modus": "$modus"}""")
            .`when`().put("/beheer/magazijnen/$oin/gedrag")
            .then()
            .statusCode(200)
    }

    private fun berichtIds(magazijn: String = MAGAZIJN): Set<String> = given()
        .header(ONTVANGER_HEADER, ONTVANGER)
        .queryParam("pageSize", 100)
        .`when`().get("/magazijn/$magazijn/api/v1/berichten")
        .then()
        .statusCode(200)
        .extract().jsonPath()
        .getList<String>("berichten.berichtId")
        .toSet()

    private companion object {
        const val ONTVANGER_HEADER = "X-Ontvanger"
        const val ONTVANGER = "KVK:90000001"
        const val TWEEDE_ONTVANGER = "KVK:90000002"
        const val MAGAZIJN = "00000009000000000001"
        const val TWEEDE_MAGAZIJN = "00000009000000000002"
        const val DERDE_MAGAZIJN = "00000009000000000003"
        const val BASIS = "/magazijn/$MAGAZIJN/api/v1"
    }
}
