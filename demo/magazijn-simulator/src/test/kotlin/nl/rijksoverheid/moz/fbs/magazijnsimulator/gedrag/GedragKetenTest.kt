package nl.rijksoverheid.moz.fbs.magazijnsimulator.gedrag

import io.quarkus.test.junit.QuarkusTest
import io.restassured.RestAssured.given
import io.restassured.http.ContentType
import jakarta.inject.Inject
import nl.rijksoverheid.moz.fbs.magazijnsimulator.MagazijnTestBasis
import nl.rijksoverheid.moz.fbs.magazijnsimulator.magazijn.GesimuleerdeMagazijnen
import org.hamcrest.Matchers.equalTo
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.system.measureTimeMillis

/**
 * Het gedrag zoals een aanroeper het merkt.
 *
 * Deze tests zetten het gedrag zelf op een eigen magazijn en zetten het achteraf terug, zodat ze de
 * magazijnen van de andere tests met rust laten. De vertragingen zijn opzettelijk kort: waar het om
 * gaat is dát er gewacht wordt en wat er daarna uitkomt, niet hoe lang precies.
 */
@QuarkusTest
class GedragKetenTest : MagazijnTestBasis() {

    @Inject
    lateinit var magazijnen: GesimuleerdeMagazijnen

    @AfterEach
    fun herstel() {
        magazijnen.herstelGedrag()
    }

    @Test
    fun `een kapot magazijn geeft een serverfout op elke leesactie`() {
        zet(Gedrag(GedragModus.STUK, latencyP50Ms = 0, latencyP95Ms = 0))

        given()
            .header(ONTVANGER_HEADER, ONTVANGER)
            .`when`().get("$BASIS/berichten")
            .then()
            .statusCode(503)
            .contentType(PROBLEM_JSON)
            .body("status", equalTo(503))
    }

    /**
     * Het gedrag geldt op álles, dus ook op schrijfacties. Dat is realistisch — in het echte stelsel
     * is een schrijfactie net zo goed een aanroep naar een andere organisatie — en het is de reden
     * dat het beheerpad buiten de simulatie valt: anders is een kapot gezet magazijn niet meer te
     * vullen of te repareren.
     */
    @Test
    fun `een kapot magazijn weigert ook aanleveren, bijwerken en verwijderen`() {
        val berichtId = leverAan()

        zet(Gedrag(GedragModus.STUK, latencyP50Ms = 0, latencyP95Ms = 0))

        given()
            .contentType(ContentType.JSON)
            .body(aanleverBody())
            .`when`().post("$BASIS/aanleveringen")
            .then()
            .statusCode(503)

        given()
            .header(ONTVANGER_HEADER, ONTVANGER)
            .contentType(MERGE_PATCH_JSON)
            .body("""{"gelezen": true}""")
            .`when`().patch("$BASIS/berichten/$berichtId")
            .then()
            .statusCode(503)

        given()
            .header(ONTVANGER_HEADER, ONTVANGER)
            .`when`().delete("$BASIS/berichten/$berichtId")
            .then()
            .statusCode(503)
    }

    @Test
    fun `een weigerend magazijn antwoordt met een 4xx in problem+json`() {
        zet(Gedrag(GedragModus.WEIGERT, latencyP50Ms = 0, latencyP95Ms = 0, foutStatus = Gedrag.WEIGER_STATUS))

        given()
            .header(ONTVANGER_HEADER, ONTVANGER)
            .`when`().get("$BASIS/berichten")
            .then()
            .statusCode(403)
            .contentType(PROBLEM_JSON)
    }

    /**
     * De tak die de Berichtenbox ánders behandelt dan onbereikbaarheid: een antwoord dat binnenkomt
     * maar niet te gebruiken is. Zonder deze modus wordt die tak in een demo nooit geraakt.
     */
    @Test
    fun `een malformed magazijn antwoordt met 200 en een body die het schema schendt`() {
        zet(Gedrag(GedragModus.MALFORMED, latencyP50Ms = 0, latencyP95Ms = 0))

        val body = given()
            .header(ONTVANGER_HEADER, ONTVANGER)
            .`when`().get("$BASIS/berichten")
            .then()
            .statusCode(200)
            .contentType("application/json")
            .extract().asString()

        assertTrue(body.contains("dit is geen lijst"), "verwacht een body die niet als lijst te lezen is")
    }

    @Test
    fun `een traag magazijn antwoordt wel, maar later`() {
        zet(Gedrag(GedragModus.TRAAG, latencyP50Ms = 300, latencyP95Ms = 300))

        val duur = measureTimeMillis {
            given()
                .header(ONTVANGER_HEADER, ONTVANGER)
                .`when`().get("$BASIS/berichten")
                .then()
                .statusCode(200)
        }

        assertTrue(duur >= 300, "verwacht minstens de ingestelde vertraging, was $duur ms")
    }

    /**
     * Een onbereikbaar magazijn antwoordt uiteindelijk wél — het punt is dat het te laat is. De
     * aanroeper hoort in zijn eigen timeout te lopen; dat is wat de Berichtenbox als "onbereikbaar"
     * registreert, en niet een foutcode van dit magazijn.
     */
    @Test
    fun `een onbereikbaar magazijn laat de aanroeper wachten zonder foutcode`() {
        zet(Gedrag(GedragModus.UIT, latencyP50Ms = 400, latencyP95Ms = 400))

        val duur = measureTimeMillis {
            given()
                .header(ONTVANGER_HEADER, ONTVANGER)
                .`when`().get("$BASIS/berichten")
                .then()
                .statusCode(200)
        }

        assertTrue(duur >= 400, "verwacht dat de aanroeper zit te wachten, was $duur ms")
    }

    /**
     * Het wachten mag niet op de event-loop gebeuren: dan zou één traag magazijn álle andere
     * stilzetten, en precies dat is wat een demo met honderd magazijnen moet kunnen laten zien. Twee
     * gelijktijdige verzoeken van 500 ms horen samen ruim onder de seconde te blijven.
     */
    @Test
    fun `een traag magazijn houdt de andere verzoeken niet op`() {
        zet(Gedrag(GedragModus.TRAAG, latencyP50Ms = 500, latencyP95Ms = 500))

        val pool = Executors.newFixedThreadPool(PARALLEL)

        try {
            val duur = measureTimeMillis {
                val taken = (1..PARALLEL).map {
                    pool.submit {
                        given()
                            .header(ONTVANGER_HEADER, ONTVANGER)
                            .`when`().get("$BASIS/berichten")
                            .then()
                            .statusCode(200)
                    }
                }

                taken.forEach { it.get(SECONDEN_GEDULD, TimeUnit.SECONDS) }
            }

            assertTrue(
                duur < PARALLEL * 500,
                "verwacht dat de verzoeken naast elkaar liepen, samen duurden ze $duur ms",
            )
        } finally {
            pool.shutdownNow()
        }
    }

    @Test
    fun `het gedrag terugzetten maakt het magazijn weer bruikbaar`() {
        zet(Gedrag(GedragModus.STUK, latencyP50Ms = 0, latencyP95Ms = 0))

        given().header(ONTVANGER_HEADER, ONTVANGER).`when`().get("$BASIS/berichten").then().statusCode(503)

        magazijnen.herstelGedrag()

        given().header(ONTVANGER_HEADER, ONTVANGER).`when`().get("$BASIS/berichten").then().statusCode(200)
    }

    /** Eén magazijn kapot zetten raakt de andere niet; anders is een demo niet te sturen. */
    @Test
    fun `een storing op het ene magazijn laat het andere ongemoeid`() {
        zet(Gedrag(GedragModus.STUK, latencyP50Ms = 0, latencyP95Ms = 0))

        given()
            .header(ONTVANGER_HEADER, ONTVANGER)
            .`when`().get("/magazijn/$ANDER_MAGAZIJN/api/v1/berichten")
            .then()
            .statusCode(200)
    }

    private fun zet(gedrag: Gedrag) {
        assertTrue(magazijnen.stelGedragBij(MAGAZIJN, gedrag), "magazijn $MAGAZIJN hoort te bestaan")
    }

    private fun leverAan(): String = given()
        .contentType(ContentType.JSON)
        .body(aanleverBody())
        .`when`().post("$BASIS/aanleveringen")
        .then()
        .statusCode(201)
        .extract().path("berichtId")

    private fun aanleverBody(): String = """
        {
          "afzender": "$MAGAZIJN",
          "ontvanger": {"type": "KVK", "waarde": "90000001"},
          "onderwerp": "Demo-bericht",
          "inhoud": "Inhoud van een demo-bericht."
        }
    """.trimIndent()

    private companion object {
        const val ONTVANGER_HEADER = "X-Ontvanger"
        const val ONTVANGER = "KVK:90000001"
        const val PROBLEM_JSON = "application/problem+json"
        const val MERGE_PATCH_JSON = "application/merge-patch+json"

        /** Een eigen magazijn voor deze tests, zodat ze de andere met rust laten. */
        const val MAGAZIJN = "00000009000000000003"
        const val ANDER_MAGAZIJN = "00000009000000000001"
        const val BASIS = "/magazijn/$MAGAZIJN/api/v1"
        const val PARALLEL = 4
        const val SECONDEN_GEDULD = 20L
    }
}
