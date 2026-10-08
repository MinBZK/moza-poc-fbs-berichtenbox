package nl.rijksoverheid.moz.fbs.stelselregister.stelseldocument

import com.fasterxml.jackson.databind.node.ObjectNode
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import java.time.Duration
import java.time.Instant
import java.util.Base64

/**
 * De verificatie die een afnemer hoort te doen, stap voor stap tegengeproefd. Elke test wijzigt
 * precies één ding aan een document dat verder klopt — opnieuw ondertekend met een geldige sleutel
 * waar dat kan, zodat de weigering uit de bedoelde stap komt en niet uit de handtekening — en
 * controleert de reden.
 */
class AfnemerTest {

    private val uitgever = Wegwerpketen.STANDAARD_OIN
    private val nu = Instant.now()
    private val base64url = Base64.getUrlEncoder().withoutPadding()

    private fun sleutel(keten: Testketens.Keten) =
        Ondertekensleutel.uitKeystore(keten.pad, keten.wachtwoord, Wegwerpketen.ALIAS, nu)

    private val sleutel = sleutel(Testketens.geldig)
    private val document = Stelseldocument(listOf(Organisatie("00000000000000100000", "RVO", "https://a.example")))

    private fun afnemer(keten: Testketens.Keten = Testketens.geldig) = Afnemer(keten.root, uitgever)

    /** Een JWS met de gegeven header en payload, ondertekend met [ondertekenaar]. */
    private fun jws(
        ondertekenaar: Ondertekensleutel = sleutel,
        header: (ObjectNode) -> Unit = {},
        payload: (ObjectNode) -> Unit = {},
    ): String {
        val kop = Afnemer.JSON.createObjectNode()
            .put("alg", "ES256")
            .put("typ", "stelseldocument+jwt")
            .put("kid", ondertekenaar.kid)

        kop.putArray("x5c").also { lijst -> ondertekenaar.x5c.forEach(lijst::add) }
        header(kop)

        val inhoud = Afnemer.JSON.readTree(document.payload(uitgever, "test", nu, nu.plus(Duration.ofHours(24)))) as ObjectNode

        payload(inhoud)

        val teOndertekenen = base64url.encodeToString(Afnemer.JSON.writeValueAsBytes(kop)) + "." +
            base64url.encodeToString(Afnemer.JSON.writeValueAsBytes(inhoud))

        return teOndertekenen + "." + base64url.encodeToString(ondertekenaar.onderteken(teOndertekenen.toByteArray()))
    }

    private fun reden(jws: String, afnemer: Afnemer = afnemer(), moment: Instant = nu): String =
        assertThrows(Afnemer.Geweigerd::class.java) { afnemer.accepteer(jws, moment) }.message.orEmpty()

    @Test
    fun `een document dat klopt wordt geaccepteerd`() {
        assertEquals(1, afnemer().accepteer(jws(), nu).path("organizations").size())
    }

    // De spec nummert de stappen; komt er een bij of valt er een weg, dan hoort Afnemer mee te gaan.
    @Test
    fun `de spec beschrijft de zes stappen die Afnemer uitvoert`() {
        val spec = checkNotNull(javaClass.classLoader.getResourceAsStream("openapi/stelselregister-api.yaml"))
            .bufferedReader().use { it.readText() }
        val verificatie = spec.substringAfter("## Het document verifiëren").substringBefore("\n    ## ")

        assertEquals(listOf("1", "2", "3", "4", "5", "6"), Regex("""(?m)^ {4}(\d)\. """).findAll(verificatie).map { it.groupValues[1] }.toList())
    }

    @Test
    fun `stap 1 - iets anders dan drie delen is geen document`() {
        assertEquals("geen compacte JWS", reden(jws().substringBeforeLast('.')))
    }

    @ParameterizedTest(name = "header {0}={1}")
    @CsvSource(
        "alg, none, alg is niet ES256",
        "alg, HS256, alg is niet ES256",
        "alg, ES384, alg is niet ES256",
        "typ, JWT, typ klopt niet",
        "jku, https://aanvaller.example/jwks.json, onverwachte headervelden",
        "x5u, https://aanvaller.example/cert.pem, onverwachte headervelden",
        "crit, exp, onverwachte headervelden",
    )
    fun `stap 1 - een ander algoritme, type of een extra headerveld wordt geweigerd`(veld: String, waarde: String, verwacht: String) {
        assertEquals(verwacht, reden(jws(header = { it.put(veld, waarde) })))
    }

    @Test
    fun `stap 1 - een header zonder kid wordt geweigerd`() {
        assertEquals("onverwachte headervelden", reden(jws(header = { it.remove("kid") })))
    }

    @Test
    fun `stap 2 - een lege x5c wordt geweigerd`() {
        assertEquals("geen x5c", reden(jws(header = { it.putArray("x5c") })))
    }

    @Test
    fun `stap 2 - een andere root wordt geweigerd`() {
        assertEquals("keten leidt niet naar de vastgelegde root", reden(jws(), afnemer(Testketens.ander)))
    }

    @Test
    fun `stap 2 - een keten met een tussencertificaat wordt geaccepteerd, in omgekeerde volgorde niet`() {
        val keten = Testketens.metTussencertificaat
        val metTussen = sleutel(keten)

        afnemer(keten).accepteer(jws(metTussen), nu)

        val omgekeerd = jws(metTussen, header = { kop ->
            kop.putArray("x5c").also { lijst -> metTussen.x5c.reversed().forEach(lijst::add) }
        })

        assertEquals("keten leidt niet naar de vastgelegde root", reden(omgekeerd, afnemer(keten)))
    }

    // De dienst weigert zo'n keystore zelf; een aanvaller niet. Daarom met de hand opgebouwd: het
    // ondertekencertificaat is uitgegeven door een eindcertificaat, en elke handtekening klopt.
    @Test
    fun `stap 2 - een keten via een certificaat dat geen CA is wordt geweigerd`() {
        val keten = Testketens.viaNietCa
        val x5c = listOf(keten.certificaat, keten.certificaat(Testketens.TUSSEN_ALIAS))
            .map { Base64.getEncoder().encodeToString(it.encoded) }
        val kop = Afnemer.JSON.createObjectNode().put("alg", "ES256").put("typ", "stelseldocument+jwt").put("kid", "x")

        kop.putArray("x5c").also { lijst -> x5c.forEach(lijst::add) }

        val teOndertekenen = base64url.encodeToString(Afnemer.JSON.writeValueAsBytes(kop)) + "." +
            base64url.encodeToString(document.payload(uitgever, "test", nu, nu.plus(Duration.ofHours(24))))
        val handtekening = java.security.Signature.getInstance("SHA256withECDSAinP1363Format").run {
            initSign(keten.sleutel)
            update(teOndertekenen.toByteArray())
            sign()
        }

        assertEquals(
            "keten leidt niet naar de vastgelegde root",
            reden(teOndertekenen + "." + base64url.encodeToString(handtekening), afnemer(keten)),
        )
    }

    @Test
    fun `stap 2 - na het verlopen van het certificaat wordt het document geweigerd`() {
        val naVerloop = Testketens.geldig.certificaat.notAfter.toInstant().plusSeconds(1)

        assertEquals("keten leidt niet naar de vastgelegde root", reden(jws(), moment = naVerloop))
    }

    @Test
    fun `stap 3 - een gewijzigde payload wordt geweigerd`() {
        val delen = jws().split('.')
        val vervalst = String(Base64.getUrlDecoder().decode(delen[1])).replace("a.example", "aanvaller.example")

        assertEquals(
            "handtekening klopt niet",
            reden(listOf(delen[0], base64url.encodeToString(vervalst.toByteArray()), delen[2]).joinToString(".")),
        )
    }

    @Test
    fun `stap 4 - een certificaat van een andere organisatie wordt geweigerd`() {
        val vreemd = sleutel(Testketens.andereUitgever)

        assertEquals(
            "het ondertekencertificaat is niet van de verwachte uitgever",
            reden(jws(vreemd), afnemer(Testketens.andereUitgever)),
        )
    }

    @Test
    fun `stap 4 - een andere iss onder het juiste certificaat wordt geweigerd`() {
        assertEquals("onverwachte uitgever", reden(jws(payload = { it.put("iss", "00000000000000009999") })))
    }

    @Test
    fun `stap 5 - een document uit een andere omgeving wordt geweigerd`() {
        assertEquals("onverwachte omgeving", reden(jws(payload = { it.put("environment", "pr-123") })))
    }

    @Test
    fun `stap 6 - een uitgifte in de toekomst wordt geweigerd, binnen het klokverschil niet`() {
        val straks = nu.epochSecond + 61

        assertEquals("uitgegeven in de toekomst", reden(jws(payload = { it.put("iat", straks).put("exp", straks + 3600) })))
        afnemer().accepteer(jws(payload = { it.put("iat", nu.epochSecond + 60) }), nu)
    }

    @Test
    fun `stap 6 - een geldigheid langer dan 24 uur wordt geweigerd`() {
        val teLang = jws(payload = { it.put("exp", nu.epochSecond + 24 * 3600 + 1) })

        assertEquals("geldigheid langer dan het profiel toestaat", reden(teLang))
    }

    @Test
    fun `stap 6 - op het moment van exp is het document verlopen, een seconde eerder niet`() {
        val document = jws()
        val exp = Instant.ofEpochSecond(nu.epochSecond).plus(Duration.ofHours(24))

        afnemer().accepteer(document, exp.minusSeconds(1))
        assertEquals("document is verlopen", reden(document, moment = exp))
    }

    @Test
    fun `stap 6 - een ouder exemplaar na een nieuwer wordt geweigerd, hetzelfde exemplaar niet`() {
        val app = afnemer()
        val ouder = jws(payload = { it.put("iat", nu.epochSecond - 3600).put("exp", nu.epochSecond + 3600) })
        val nieuwer = jws()

        app.accepteer(ouder, nu)
        app.accepteer(nieuwer, nu)
        app.accepteer(nieuwer, nu)

        assertEquals("ouder dan het laatst geaccepteerde exemplaar", reden(ouder, app))
    }
}
