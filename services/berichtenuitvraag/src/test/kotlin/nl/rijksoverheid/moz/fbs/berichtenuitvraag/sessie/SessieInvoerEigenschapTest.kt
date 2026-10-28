package nl.rijksoverheid.moz.fbs.berichtenuitvraag.sessie

import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.junit.TestProfile
import io.restassured.RestAssured.given
import io.restassured.http.Header
import io.restassured.http.Headers
import io.restassured.response.Response
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.util.concurrent.atomic.AtomicLong
import kotlin.random.Random

/**
 * Eigenschappen van het sessiefilter over veel willekeurige `X-Sessie`-waarden, tegen een echte
 * Redis: geen enkele waarde die `POST /sessies` niet uitgaf, komt voorbij het filter. Het antwoord is
 * altijd `401 sessie-ongeldig`, of `400 ongeldig-verzoek` als ook de ontvanger niet deugt, nooit een
 * 2xx of een fout van verderop. Een vaste seed, zodat een falend geval te herhalen is; de melding
 * noemt de invoer.
 *
 * Regeleinden en andere stuurtekens vallen buiten deze test: een HTTP-header kan ze niet dragen. Ze
 * zitten wel in de eigenschapstest van de bibliotheek, die de vormregel rechtstreeks krijgt.
 */
@QuarkusTest
@TestProfile(KorteSessieKetenProfile::class)
class SessieInvoerEigenschapTest {

    @Test
    fun `geen enkele niet-uitgegeven X-Sessie komt voorbij het filter`() {
        val random = Random(SEED)

        repeat(GEVALLEN) { i ->
            val ontvangerGeldig = random.nextInt(5) != 0
            val ontvanger = if (ontvangerGeldig) nieuweOntvanger() else ONGELDIGE_ONTVANGERS.random(random)
            val sessie = SessieIds.onjuist(random)
            val (methode, pad) = PADEN.random(random)

            verwachtWeigering("geval $i: $methode $pad, X-Sessie=${zichtbaar(sessie)}, X-Ontvanger=$ontvanger", ontvangerGeldig) {
                given().header("X-Ontvanger", ontvanger).header("X-Sessie", sessie).request(methode, pad)
            }
        }
    }

    @Test
    fun `een herhaalde X-Sessie wordt geweigerd, ook als een van de waarden een lopende sessie is`() {
        val random = Random(SEED + 1)

        repeat(GEVALLEN / 5) { i ->
            val ontvanger = nieuweOntvanger()
            val echt = start(ontvanger)

            val tweede = when (random.nextInt(3)) {
                0 -> echt
                1 -> SessieIds.geldigGevormd(random)
                else -> SessieIds.onjuist(random)
            }

            val waarden = listOf(echt, tweede).shuffled(random)
            val (methode, pad) = PADEN.random(random)

            verwachtWeigering("geval $i: $methode $pad, X-Sessie twee keer", ontvangerGeldig = true) {
                given().header("X-Ontvanger", ontvanger).headers(Headers(waarden.map { Header("X-Sessie", it) })).request(methode, pad)
            }

            // De lopende sessie zelf blijft bruikbaar: weigeren is niet beëindigen. Zonder ophaalronde is dat een 409.
            given().header("X-Ontvanger", ontvanger).header("X-Sessie", echt).get("/api/v1/berichten")
                .then().statusCode(409)
        }
    }

    @Test
    fun `uitloggen met een krom gevormde X-Sessie geeft 401, en wist niets`() {
        // Alleen de vorm telt bij uitloggen; een goed gevormde, onbekende sessie geeft daar bewust 204.
        val random = Random(SEED + 2)
        val ontvanger = nieuweOntvanger()
        val echt = start(ontvanger)

        repeat(GEVALLEN / 5) { i ->
            val sessie = SessieIds.onjuistGevormd(random)

            verwachtWeigering("geval $i: DELETE, X-Sessie=${zichtbaar(sessie)}", ontvangerGeldig = true) {
                given().header("X-Ontvanger", ontvanger).header("X-Sessie", sessie).delete("/api/v1/sessies/huidige")
            }
        }

        given().header("X-Ontvanger", ontvanger).header("X-Sessie", echt).get("/api/v1/berichten")
            .then().statusCode(409)
    }

    private fun verwachtWeigering(geval: String, ontvangerGeldig: Boolean, verzoek: () -> Response) {
        val antwoord = verzoek()
        val (status, type) = if (ontvangerGeldig) 401 to "urn:fbs:fout:sessie-ongeldig" else 400 to "urn:fbs:fout:ongeldig-verzoek"

        assertEquals(status, antwoord.statusCode, "$geval: ${antwoord.body.asString()}")
        assertEquals(type, antwoord.path<String>("type"), geval)
    }

    private fun start(ontvanger: String): String =
        given().header("X-Ontvanger", ontvanger).contentType("application/json").post("/api/v1/sessies")
            .then().statusCode(201).extract().path("sessieId")

    /** Een leesbare melding: onzichtbare tekens als code, zodat een falend geval te herkennen is. */
    private fun zichtbaar(waarde: String): String =
        waarde.map { if (it.code in 0x21..0x7e) it.toString() else "\\u%04x".format(it.code) }.joinToString("", "\"", "\"")

    private companion object {
        const val SEED = 943L
        const val GEVALLEN = 400
        private val teller = AtomicLong(System.nanoTime())

        // Uniek per geval: de sessies van een ontvanger blijven over de tests heen in Redis lopen.
        fun nieuweOntvanger() = "OIN:" + teller.incrementAndGet().toString().padStart(20, '0').takeLast(20)

        val ONGELDIGE_ONTVANGERS = listOf("", "BSN:123", "BSN:999990018", "FOO:1", "BSN 999990019", "OIN:1:2")

        // Elk pad dat een sessie vereist, in de vorm die de router matcht; anders komt het filter er niet aan te pas.
        val PADEN = listOf(
            "GET" to "/api/v1/berichten",
            "GET" to "/api/v1/berichten/_zoeken?q=bericht",
            "GET" to "/api/v1/berichten/00000000-0000-4000-8000-000000000001?magazijnId=00000001003214345000",
            "GET" to "/api/v1/berichten/_ophalen",
            "GET" to "/api/v1/berichten/_volgen",
        )
    }
}

/** Willekeurige sessie-id's die `POST /sessies` nooit uitgaf, in de vormen waar een vormregel op kan struikelen. */
internal object SessieIds {

    private const val ALFABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_"
    private const val VREEMD = "+/=!.~*%:;,\"'<>()[]{}\\|@#$&? "

    // Lijken op base64url maar zijn het niet: Latijn met accent, Cyrillisch en een full-width cijfer.
    private const val UNICODE = "éßøаеоＡ０ĳ"

    fun geldigGevormd(random: Random): String = String(CharArray(22) { ALFABET[random.nextInt(ALFABET.length)] })

    /** Een geldig gevormd maar nooit uitgegeven id, of een onjuist gevormde waarde. */
    fun onjuist(random: Random): String = if (random.nextInt(4) == 0) geldigGevormd(random) else onjuistGevormd(random)

    fun onjuistGevormd(random: Random): String {
        val basis = geldigGevormd(random)

        return when (random.nextInt(7)) {
            0 -> ""
            1 -> basis.take(random.nextInt(22))
            2 -> basis + basis.repeat(random.nextInt(1, 10)).take(random.nextInt(1, 200))
            3 -> vervang(basis, random, VREEMD)
            4 -> vervang(basis, random, UNICODE)
            5 -> if (random.nextBoolean()) basis.dropLast(1) + "🔑" else "🔑" + basis.drop(2)
            else -> basis.take(random.nextInt(1, 22)) + " " + basis.drop(22 - random.nextInt(1, 22)).take(21)
        }
    }

    private fun vervang(basis: String, random: Random, tekens: String): String {
        val plek = random.nextInt(basis.length)

        return basis.substring(0, plek) + tekens[random.nextInt(tekens.length)] + basis.substring(plek + 1)
    }
}
