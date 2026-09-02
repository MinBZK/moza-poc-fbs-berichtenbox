package nl.rijksoverheid.moz.fbs.magazijnsimulator.berichten

import io.quarkus.test.junit.QuarkusTest
import io.restassured.RestAssured.given
import io.restassured.http.ContentType
import org.hamcrest.Matchers.contains
import org.hamcrest.Matchers.emptyIterable
import org.hamcrest.Matchers.equalTo
import org.hamcrest.Matchers.hasSize
import org.hamcrest.Matchers.notNullValue
import org.hamcrest.Matchers.nullValue
import org.junit.jupiter.api.Test
import nl.rijksoverheid.moz.fbs.magazijnsimulator.MagazijnTestBasis
import io.quarkus.narayana.jta.QuarkusTransaction
import jakarta.inject.Inject
import jakarta.persistence.EntityManager
import java.util.Base64
import java.util.UUID

/**
 * De zes operaties op een magazijn dat echt iets bewaart, en — het punt van deze stap — dat wat de
 * ondernemer doet blijft staan.
 *
 * Twee magazijnen staan hier steeds naast elkaar. Met één magazijn ziet een implementatie die de
 * discriminator vergeet er precies zo uit als een die hem gebruikt, en dan lekt in een demo met
 * honderd magazijnen het ene magazijn in het andere zonder dat iets rood wordt.
 */
@QuarkusTest
class BerichtenKetenTest : MagazijnTestBasis() {

    @Inject
    lateinit var entityManager: EntityManager

    @Test
    fun `een aangeleverd bericht is daarna op te halen`() {
        val berichtId = leverAan(EEN, onderwerp = "Voorlopige aanslag 2026")

        given()
            .header(ONTVANGER_HEADER, ONTVANGER)
            .`when`().get("${basis(EEN)}/berichten/$berichtId")
            .then()
            .statusCode(200)
            .body("berichtId", equalTo(berichtId))
            .body("onderwerp", equalTo("Voorlopige aanslag 2026"))
            .body("inhoud", equalTo(INHOUD))
            .body("afzender", equalTo(EEN))
            .body("ontvanger.type", equalTo("KVK"))
            .body("ontvanger.waarde", equalTo("90000001"))
            .body("tijdstipOntvangst", notNullValue())
            // Zonder publicatietijdstip in de aanlevering valt het magazijn terug op het tijdstip
            // van ontvangst — niet op een tweede klokaflezing.
            .body("publicatietijdstip", equalTo(gelezenVeld(EEN, berichtId, "tijdstipOntvangst")))
            .body("_links.self.href", org.hamcrest.Matchers.endsWith("${basis(EEN)}/berichten/$berichtId"))
    }

    /**
     * De kern van de discriminator: hetzelfde bericht-id bestaat alleen in het magazijn waar het is
     * aangeleverd.
     */
    @Test
    fun `een bericht van het ene magazijn bestaat niet in het andere`() {
        val berichtId = leverAan(EEN)

        given()
            .header(ONTVANGER_HEADER, ONTVANGER)
            .`when`().get("${basis(TWEE)}/berichten/$berichtId")
            .then()
            .statusCode(404)

        given()
            .header(ONTVANGER_HEADER, ONTVANGER)
            .`when`().get("${basis(TWEE)}/berichten")
            .then()
            .statusCode(200)
            .body("berichten", emptyIterable<Any>())
    }

    @Test
    fun `twee magazijnen delen hun berichten niet in de lijst`() {
        leverAan(EEN, onderwerp = "Van magazijn een")
        leverAan(TWEE, onderwerp = "Van magazijn twee")

        given()
            .header(ONTVANGER_HEADER, ONTVANGER)
            .`when`().get("${basis(EEN)}/berichten")
            .then()
            .statusCode(200)
            .body("totalElements", equalTo(1))
            .body("berichten.onderwerp", contains("Van magazijn een"))
    }

    @Test
    fun `een ondernemer ziet de berichten van een andere ondernemer niet`() {
        leverAan(EEN, ontvangerType = "KVK", ontvangerWaarde = "90000001")
        leverAan(EEN, ontvangerType = "KVK", ontvangerWaarde = "90000002")

        given()
            .header(ONTVANGER_HEADER, "KVK:90000002")
            .`when`().get("${basis(EEN)}/berichten")
            .then()
            .statusCode(200)
            .body("totalElements", equalTo(1))
    }

    /**
     * Het onderscheid dat mappen en leesstatus mogelijk maakt: zolang de ontvanger niets heeft
     * gezet, laat de spec `status` weg. `gelezen: false` zou iets anders betekenen — dan heeft
     * iemand het bericht wél aangeraakt.
     */
    @Test
    fun `een onaangeraakt bericht heeft geen status`() {
        val berichtId = leverAan(EEN)

        given()
            .header(ONTVANGER_HEADER, ONTVANGER)
            .`when`().get("${basis(EEN)}/berichten/$berichtId")
            .then()
            .statusCode(200)
            .body("status", nullValue())
    }

    @Test
    fun `gelezen markeren blijft staan, ook bij opnieuw ophalen`() {
        val berichtId = leverAan(EEN)

        patch(EEN, berichtId, """{"gelezen": true}""")
            .then()
            .statusCode(200)
            .body("status.gelezen", equalTo(true))
            .body("status.gewijzigdOp", notNullValue())

        given()
            .header(ONTVANGER_HEADER, ONTVANGER)
            .`when`().get("${basis(EEN)}/berichten/$berichtId")
            .then()
            .statusCode(200)
            .body("status.gelezen", equalTo(true))
    }

    @Test
    fun `een map blijft staan en komt ook in de lijst terug`() {
        val berichtId = leverAan(EEN)

        patch(EEN, berichtId, """{"map": "Archief"}""").then().statusCode(200)

        given()
            .header(ONTVANGER_HEADER, ONTVANGER)
            .`when`().get("${basis(EEN)}/berichten")
            .then()
            .statusCode(200)
            .body("berichten[0].status.map", equalTo("Archief"))
    }

    /**
     * Merge-patch: een veld dat ontbreekt of `null` is, blijft ongewijzigd. Dat geldt voor allebei
     * de velden, en het is de reden dat een map wel te overschrijven maar niet te wissen is — precies
     * zoals bij het echte magazijn.
     */
    @Test
    fun `een ontbrekend veld laat de andere waarde ongemoeid`() {
        val berichtId = leverAan(EEN)

        patch(EEN, berichtId, """{"gelezen": true, "map": "Archief"}""").then().statusCode(200)
        patch(EEN, berichtId, """{"gelezen": false}""")
            .then()
            .statusCode(200)
            .body("status.gelezen", equalTo(false))
            .body("status.map", equalTo("Archief"))
    }

    @Test
    fun `een expliciete null wijzigt niets, net als een ontbrekend veld`() {
        val berichtId = leverAan(EEN)

        patch(EEN, berichtId, """{"map": "Archief"}""").then().statusCode(200)
        patch(EEN, berichtId, """{"gelezen": true, "map": null}""")
            .then()
            .statusCode(200)
            .body("status.map", equalTo("Archief"))
    }

    @Test
    fun `een map is te overschrijven met een andere`() {
        val berichtId = leverAan(EEN)

        patch(EEN, berichtId, """{"map": "Archief"}""").then().statusCode(200)
        patch(EEN, berichtId, """{"map": "Belangrijk"}""")
            .then()
            .statusCode(200)
            .body("status.map", equalTo("Belangrijk"))
    }

    @Test
    fun `een lege patch is een clientfout en geen stille no-op`() {
        val berichtId = leverAan(EEN)

        patch(EEN, berichtId, "{}")
            .then()
            .statusCode(400)
            .contentType(PROBLEM_JSON)
    }

    @Test
    fun `een verwijderd bericht is weg voor de ondernemer maar niet onherstelbaar gewist`() {
        val berichtId = leverAan(EEN)

        verwijder(EEN, berichtId).then().statusCode(204)

        given()
            .header(ONTVANGER_HEADER, ONTVANGER)
            .`when`().get("${basis(EEN)}/berichten/$berichtId")
            .then()
            .statusCode(404)

        given()
            .header(ONTVANGER_HEADER, ONTVANGER)
            .`when`().get("${basis(EEN)}/berichten")
            .then()
            .statusCode(200)
            .body("berichten", emptyIterable<Any>())

        // De rij staat er fysiek nog; soft-delete betekent onzichtbaar, niet gewist.
        assertRijenBestaan(1)
    }

    /** RFC 9110 §9.3.5: `DELETE` is idempotent, dus een tweede keer slaagt gewoon opnieuw. */
    @Test
    fun `een tweede verwijdering slaagt opnieuw`() {
        val berichtId = leverAan(EEN)

        verwijder(EEN, berichtId).then().statusCode(204)
        verwijder(EEN, berichtId).then().statusCode(204)
    }

    @Test
    fun `een bijlage is echt op te halen, met het juiste soort bestand`() {
        val inhoud = "%PDF-1.7 nep-pdf voor de demo".toByteArray()
        val berichtId = leverAan(EEN, bijlage = Bijlagegegevens("aanslag.pdf", "application/pdf", inhoud))

        val bijlageId = given()
            .header(ONTVANGER_HEADER, ONTVANGER)
            .`when`().get("${basis(EEN)}/berichten/$berichtId")
            .then()
            .statusCode(200)
            .body("bijlagen", hasSize<Any>(1))
            .body("bijlagen[0].naam", equalTo("aanslag.pdf"))
            .body("bijlagen[0].mimeType", equalTo("application/pdf"))
            .extract().path<String>("bijlagen[0].bijlageId")

        val bytes = given()
            .header(ONTVANGER_HEADER, ONTVANGER)
            .`when`().get("${basis(EEN)}/berichten/$berichtId/bijlagen/$bijlageId")
            .then()
            .statusCode(200)
            .contentType("application/pdf")
            // Nooit inline renderen: een aangeleverde `text/html`-bijlage zou anders onder onze
            // origin kunnen draaien.
            .header("Content-Disposition", "attachment")
            .extract().asByteArray()

        org.junit.jupiter.api.Assertions.assertArrayEquals(inhoud, bytes)
    }

    @Test
    fun `de lijst toont per bericht hoeveel bijlagen er zijn`() {
        leverAan(EEN, bijlage = Bijlagegegevens("aanslag.pdf", "application/pdf", "pdf".toByteArray()))

        given()
            .header(ONTVANGER_HEADER, ONTVANGER)
            .`when`().get("${basis(EEN)}/berichten")
            .then()
            .statusCode(200)
            .body("berichten[0].aantalBijlagen", equalTo(1))
            .body("berichten[0].bijlagen[0].naam", equalTo("aanslag.pdf"))
    }

    /**
     * Eén bijlage per bericht laat de groepering ongemoeid: een implementatie die alle bijlagen op
     * één hoop gooit, ziet er dan precies zo uit als een die per bericht bijhoudt. Twee bijlagen bij
     * één bericht en twee berichten die elk hun eigen bijlagen houden, sluiten dat af.
     */
    @Test
    fun `een bericht kan meerdere bijlagen hebben, in de volgorde van aanlevering`() {
        val berichtId = leverAan(
            EEN,
            bijlagen = listOf(
                Bijlagegegevens("eerst.pdf", "application/pdf", "een".toByteArray()),
                Bijlagegegevens("daarna.png", "image/png", "twee".toByteArray()),
            ),
        )

        given()
            .header(ONTVANGER_HEADER, ONTVANGER)
            .`when`().get("${basis(EEN)}/berichten/$berichtId")
            .then()
            .statusCode(200)
            .body("bijlagen", hasSize<Any>(2))
            .body("bijlagen.naam", contains("eerst.pdf", "daarna.png"))
            .body("bijlagen.mimeType", contains("application/pdf", "image/png"))
    }

    @Test
    fun `twee berichten in één lijst houden elk hun eigen bijlagen`() {
        leverAan(EEN, onderwerp = "Eerst", bijlage = Bijlagegegevens("van-eerst.pdf", "application/pdf", "a".toByteArray()))
        leverAan(
            EEN,
            onderwerp = "Daarna",
            bijlagen = listOf(
                Bijlagegegevens("van-daarna-1.pdf", "application/pdf", "b".toByteArray()),
                Bijlagegegevens("van-daarna-2.pdf", "application/pdf", "c".toByteArray()),
            ),
        )

        given()
            .header(ONTVANGER_HEADER, ONTVANGER)
            .`when`().get("${basis(EEN)}/berichten")
            .then()
            .statusCode(200)
            // Nieuwste eerst, dus "Daarna" staat bovenaan.
            .body("berichten[0].aantalBijlagen", equalTo(2))
            .body("berichten[0].bijlagen.naam", contains("van-daarna-1.pdf", "van-daarna-2.pdf"))
            .body("berichten[1].aantalBijlagen", equalTo(1))
            .body("berichten[1].bijlagen.naam", contains("van-eerst.pdf"))
    }

    /**
     * Twee `PATCH`-verzoeken tegelijk op een bericht zonder status-rij. Zouden die allebei een rij
     * willen aanmaken, dan loopt de tweede tegen de unique-constraint en eindigt hij als 500 —
     * terwijl het echte magazijn daar gewoon 200 geeft.
     */
    @Test
    fun `twee gelijktijdige status-wijzigingen leveren allebei een geldig antwoord op`() {
        val berichtId = leverAan(EEN)
        val pool = java.util.concurrent.Executors.newFixedThreadPool(2)

        try {
            val taken = listOf("""{"gelezen": true}""", """{"map": "Archief"}""").map { body ->
                pool.submit<Int> { patch(EEN, berichtId, body).then().extract().statusCode() }
            }

            taken.forEach { taak ->
                org.junit.jupiter.api.Assertions.assertEquals(
                    200,
                    taak.get(10, java.util.concurrent.TimeUnit.SECONDS),
                )
            }
        } finally {
            pool.shutdownNow()
        }

        given()
            .header(ONTVANGER_HEADER, ONTVANGER)
            .`when`().get("${basis(EEN)}/berichten/$berichtId")
            .then()
            .statusCode(200)
            .body("status", notNullValue())
    }

    /**
     * De discriminator zit op élke query, niet alleen op de leespaden. `softDelete`, `wijzigStatus`
     * en `zoekBijlage` dragen hem elk apart; valt hij bij één van de drie weg, dan raakt een
     * ondernemer met één aanroep een bericht in een magazijn waar hij niet was.
     *
     * De na-controle is het punt van deze test. Alleen de 404 asserteren zou ook groen zijn bij een
     * implementatie die het bericht in het ándere magazijn wél aanraakt.
     */
    @Test
    fun `verwijderen via het verkeerde magazijn raakt het bericht niet`() {
        val berichtId = leverAan(EEN)

        verwijder(TWEE, berichtId).then().statusCode(404)

        given()
            .header(ONTVANGER_HEADER, ONTVANGER)
            .`when`().get("${basis(EEN)}/berichten/$berichtId")
            .then()
            .statusCode(200)
    }

    @Test
    fun `status bijwerken via het verkeerde magazijn raakt het bericht niet`() {
        val berichtId = leverAan(EEN)

        patch(TWEE, berichtId, """{"gelezen": true}""").then().statusCode(404)

        given()
            .header(ONTVANGER_HEADER, ONTVANGER)
            .`when`().get("${basis(EEN)}/berichten/$berichtId")
            .then()
            .statusCode(200)
            .body("status", nullValue())
    }

    @Test
    fun `een bijlage is niet op te halen via het verkeerde magazijn`() {
        val berichtId = leverAan(EEN, bijlage = Bijlagegegevens("aanslag.pdf", "application/pdf", "pdf".toByteArray()))

        val bijlageId = given()
            .header(ONTVANGER_HEADER, ONTVANGER)
            .`when`().get("${basis(EEN)}/berichten/$berichtId")
            .then()
            .extract().path<String>("bijlagen[0].bijlageId")

        given()
            .header(ONTVANGER_HEADER, ONTVANGER)
            .`when`().get("${basis(TWEE)}/berichten/$berichtId/bijlagen/$bijlageId")
            .then()
            .statusCode(404)

        given()
            .header(ONTVANGER_HEADER, ONTVANGER)
            .`when`().get("${basis(EEN)}/berichten/$berichtId/bijlagen/$bijlageId")
            .then()
            .statusCode(200)
    }

    /**
     * De simulator neemt de pdf-only-regel van het echte magazijn bewust niet over: die staat niet
     * in de spec, en een demo waarin alleen PDF's bestaan laat het bijlage-pad maar half zien.
     */
    @Test
    fun `een bijlage mag ook een ander soort bestand zijn dan pdf`() {
        val berichtId = leverAan(EEN, bijlage = Bijlagegegevens("plattegrond.png", "image/png", "png".toByteArray()))

        given()
            .header(ONTVANGER_HEADER, ONTVANGER)
            .`when`().get("${basis(EEN)}/berichten/$berichtId")
            .then()
            .statusCode(200)
            .body("bijlagen[0].mimeType", equalTo("image/png"))
    }

    /**
     * Een rij die met de hand is aangepast; via een aanlevering komt zo'n waarde er niet meer in.
     * Het antwoord draagt `problem+json` met een correlatie-id en geen enkel spoor van de
     * opgeslagen waarde. Dat de domeingrens hem al bij het teruglezen tegenhoudt, in plaats van pas
     * bij het omzetten naar een `Content-Type`, is precies waar die grens voor is.
     */
    @Test
    fun `een onbruikbaar MIME-type in de opslag lekt niets naar buiten`() {
        val berichtId = leverAan(EEN, bijlage = Bijlagegegevens("aanslag.pdf", "application/pdf", "pdf".toByteArray()))

        val bijlageId = given()
            .header(ONTVANGER_HEADER, ONTVANGER)
            .`when`().get("${basis(EEN)}/berichten/$berichtId")
            .then()
            .extract().path<String>("bijlagen[0].bijlageId")

        QuarkusTransaction.requiringNew().run {
            entityManager
                .createNativeQuery("UPDATE bijlage SET mime_type = 'kaas' WHERE bijlage_id = :id")
                .setParameter("id", UUID.fromString(bijlageId))
                .executeUpdate()
        }

        val antwoord = given()
            .header(ONTVANGER_HEADER, ONTVANGER)
            .`when`().get("${basis(EEN)}/berichten/$berichtId/bijlagen/$bijlageId")
            .then()
            .statusCode(400)
            .contentType(PROBLEM_JSON)
            .body("instance", org.hamcrest.Matchers.startsWith("urn:uuid:"))
            .extract().asString()

        listOf("kaas", "at ", ".kt:", "nl.rijksoverheid").forEach { spoor ->
            org.junit.jupiter.api.Assertions.assertFalse(
                antwoord.contains(spoor),
                "de foutbody hoort geen '$spoor' te bevatten",
            )
        }
    }

    private fun assertRijenBestaan(verwacht: Int) {
        org.junit.jupiter.api.Assertions.assertEquals(verwacht, berichten.count().toInt())
    }

    private fun gelezenVeld(magazijn: String, berichtId: String, veld: String): String =
        given()
            .header(ONTVANGER_HEADER, ONTVANGER)
            .`when`().get("${basis(magazijn)}/berichten/$berichtId")
            .then()
            .statusCode(200)
            .extract().path(veld)

    private fun patch(magazijn: String, berichtId: String, body: String) =
        given()
            .header(ONTVANGER_HEADER, ONTVANGER)
            .contentType(MERGE_PATCH_JSON)
            .body(body)
            .`when`().patch("${basis(magazijn)}/berichten/$berichtId")

    private fun verwijder(magazijn: String, berichtId: String) =
        given()
            .header(ONTVANGER_HEADER, ONTVANGER)
            .`when`().delete("${basis(magazijn)}/berichten/$berichtId")

    private fun leverAan(
        magazijn: String,
        onderwerp: String = "Demo-bericht",
        ontvangerType: String = "KVK",
        ontvangerWaarde: String = "90000001",
        bijlage: Bijlagegegevens? = null,
        bijlagen: List<Bijlagegegevens> = listOfNotNull(bijlage),
    ): String {
        val bijlagenJson = if (bijlagen.isEmpty()) {
            ""
        } else {
            bijlagen.joinToString(prefix = """, "bijlagen": [""", postfix = "]") {
                """{"naam": "${it.naam}", "mimeType": "${it.mimeType}",
                   "inhoud": "${Base64.getEncoder().encodeToString(it.inhoud)}"}"""
            }
        }

        return given()
            .contentType(ContentType.JSON)
            .body(
                """
                {
                  "afzender": "$magazijn",
                  "ontvanger": {"type": "$ontvangerType", "waarde": "$ontvangerWaarde"},
                  "onderwerp": "$onderwerp",
                  "inhoud": "$INHOUD"$bijlagenJson
                }
                """.trimIndent(),
            )
            .`when`().post("${basis(magazijn)}/aanleveringen")
            .then()
            .statusCode(201)
            .extract().path("berichtId")
    }

    private data class Bijlagegegevens(val naam: String, val mimeType: String, val inhoud: ByteArray)

    private companion object {
        const val ONTVANGER_HEADER = "X-Ontvanger"
        const val ONTVANGER = "KVK:90000001"
        const val PROBLEM_JSON = "application/problem+json"
        const val MERGE_PATCH_JSON = "application/merge-patch+json"
        const val EEN = "00000009000000000001"
        const val TWEE = "00000009000000000002"
        const val INHOUD = "Inhoud van een demo-bericht."

        fun basis(magazijn: String) = "/magazijn/$magazijn/api/v1"
    }
}
