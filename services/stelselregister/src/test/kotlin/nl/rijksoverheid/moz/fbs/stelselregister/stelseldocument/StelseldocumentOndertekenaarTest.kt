package nl.rijksoverheid.moz.fbs.stelselregister.stelseldocument

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Duration
import java.time.Instant
import java.util.Base64

class StelseldocumentOndertekenaarTest {

    private val uitgever = "00000000000000001000"
    private val nu = Instant.now()
    private val sleutel = Ondertekensleutel.uitKeystore(
        Testketens.geldig.pad,
        Testketens.geldig.wachtwoord,
        Wegwerpketen.ALIAS,
        nu,
    )
    private val document = Stelseldocument(
        listOf(Organisatie("00000000000000100000", "RVO", "https://magazijn-a.example")),
    )
    private val ondertekenaar = StelseldocumentOndertekenaar(uitgever, "test", Duration.ofHours(24))
    private val afnemer = Afnemer(Testketens.geldig.root, uitgever)

    @Test
    fun `de header bevat precies alg, typ, kid en x5c`() {
        val header = Afnemer.header(ondertekenaar.onderteken(document, sleutel, nu).jws)

        assertEquals(listOf("alg", "typ", "kid", "x5c"), header.fieldNames().asSequence().toList())
        assertEquals("ES256", header.path("alg").asText())
        assertEquals("stelseldocument+jwt", header.path("typ").asText())
        assertEquals(sleutel.kid, header.path("kid").asText())
        assertEquals(sleutel.x5c, header.path("x5c").map { it.asText() })
    }

    @Test
    fun `de handtekening is R en S achter elkaar, 64 bytes`() {
        val handtekening = ondertekenaar.onderteken(document, sleutel, nu).jws.substringAfterLast('.')

        assertEquals(64, Base64.getUrlDecoder().decode(handtekening).size)
        assertFalse(handtekening.contains('='))
    }

    @Test
    fun `een afnemer met de juiste root accepteert het document`() {
        val uitgifte = ondertekenaar.onderteken(document, sleutel, nu)

        val payload = afnemer.accepteer(uitgifte.jws, nu)

        assertEquals("RVO", payload.path("organizations")[0].path("name").asText())
        assertEquals(uitgifte.versie, payload.path("version").asText())
    }

    @Test
    fun `een leeg register levert een geldig document met nul organisaties`() {
        val uitgifte = ondertekenaar.onderteken(Stelseldocument(emptyList()), sleutel, nu)

        assertEquals(0, afnemer.accepteer(uitgifte.jws, nu).path("organizations").size())
        assertEquals(0, uitgifte.aantalOrganisaties)
    }

    @Test
    fun `een afnemer met een andere root weigert het document`() {
        val uitgifte = ondertekenaar.onderteken(document, sleutel, nu)

        assertThrows(Afnemer.Geweigerd::class.java) { Afnemer(Testketens.ander.root, uitgever).accepteer(uitgifte.jws, nu) }
    }

    @Test
    fun `een gewijzigde payload wordt geweigerd`() {
        val delen = ondertekenaar.onderteken(document, sleutel, nu).jws.split('.')
        val vervalst = String(Base64.getUrlDecoder().decode(delen[1])).replace("magazijn-a.example", "aanvaller.example")
        val jws = listOf(delen[0], Base64.getUrlEncoder().withoutPadding().encodeToString(vervalst.toByteArray()), delen[2])
            .joinToString(".")

        assertThrows(Afnemer.Geweigerd::class.java) { afnemer.accepteer(jws, nu) }
    }

    @Test
    fun `een afnemer die een andere uitgever verwacht weigert het document`() {
        val uitgifte = ondertekenaar.onderteken(document, sleutel, nu)

        assertThrows(Afnemer.Geweigerd::class.java) {
            Afnemer(Testketens.geldig.root, "00000000000000009999").accepteer(uitgifte.jws, nu)
        }
        afnemer.accepteer(uitgifte.jws, nu)
    }

    @Test
    fun `een uitgever die niet in het certificaat staat wordt niet ondertekend`() {
        val andereUitgever = StelseldocumentOndertekenaar("00000000000000009999", "test", Duration.ofHours(24))

        val fout = assertThrows(OngeldigeOndertekensleutelException::class.java) {
            andereUitgever.onderteken(document, sleutel, nu)
        }

        assertTrue(fout.message.orEmpty().contains("subject.serialNumber"))
    }

    // Het scenario waar de band tussen `iss` en het certificaat voor bestaat: een geldige
    // handtekening van een ándere organisatie onder dezelfde root, met de verwachte uitgever in
    // de payload. Met de hand opgebouwd, want de ondertekenaar weigert dit zelf.
    @Test
    fun `een document van een andere organisatie onder dezelfde keten wordt geweigerd`() {
        val vreemd = Ondertekensleutel.uitKeystore(
            Testketens.andereUitgever.pad,
            Testketens.andereUitgever.wachtwoord,
            Wegwerpketen.ALIAS,
            nu,
        )
        val base64url = Base64.getUrlEncoder().withoutPadding()
        val header = Afnemer.JSON.writeValueAsBytes(
            mapOf("alg" to "ES256", "typ" to "stelseldocument+jwt", "kid" to vreemd.kid, "x5c" to vreemd.x5c),
        )
        val payload = document.payload(uitgever, "test", nu, nu.plus(Duration.ofHours(1)))
        val teOndertekenen = base64url.encodeToString(header) + "." + base64url.encodeToString(payload)
        val jws = teOndertekenen + "." + base64url.encodeToString(vreemd.onderteken(teOndertekenen.toByteArray()))

        val fout = assertThrows(Afnemer.Geweigerd::class.java) {
            Afnemer(Testketens.andereUitgever.root, uitgever).accepteer(jws, nu)
        }

        assertEquals("het ondertekencertificaat is niet van de verwachte uitgever", fout.message)
    }

    @Test
    fun `na de geldigheidsduur weigert een afnemer het document`() {
        val uitgifte = ondertekenaar.onderteken(document, sleutel, nu)

        afnemer.accepteer(uitgifte.jws, nu.plus(Duration.ofHours(23)))
        assertThrows(Afnemer.Geweigerd::class.java) { afnemer.accepteer(uitgifte.jws, nu.plus(Duration.ofHours(24))) }
    }

    @Test
    fun `iat en exp staan op hele seconden en liggen de geldigheidsduur uit elkaar`() {
        val uitgifte = ondertekenaar.onderteken(document, sleutel, nu)
        val payload = Afnemer.payload(uitgifte.jws)

        assertEquals(nu.epochSecond, payload.path("iat").asLong())
        assertEquals(nu.epochSecond + 24 * 3600, payload.path("exp").asLong())
        assertEquals(0, uitgifte.uitgegevenOp.nano)
        assertTrue(uitgifte.isGeldigOp(uitgifte.verlooptOp.minusSeconds(1)))
        assertFalse(uitgifte.isGeldigOp(uitgifte.verlooptOp))
    }

    @Test
    fun `exp loopt nooit voorbij de einddatum van het certificaat`() {
        val einde = Instant.ofEpochSecond(sleutel.certificaat.notAfter.toInstant().epochSecond)
        val vlakVoorHetEinde = einde.minus(Duration.ofHours(2))

        val uitgifte = ondertekenaar.onderteken(document, sleutel, vlakVoorHetEinde)

        assertEquals(einde, uitgifte.verlooptOp)
        assertEquals(einde.epochSecond, Afnemer.payload(uitgifte.jws).path("exp").asLong())
    }

    @Test
    fun `exp loopt nooit voorbij de kortst geldende schakel, ook als dat het tussencertificaat is`() {
        val keten = Testketens.metKortTussencertificaat
        val metTussen = Ondertekensleutel.uitKeystore(keten.pad, keten.wachtwoord, Wegwerpketen.ALIAS, nu)
        val eindeTussen = Instant.ofEpochSecond(keten.certificaat(Testketens.TUSSEN_ALIAS).notAfter.toInstant().epochSecond)

        val uitgifte = ondertekenaar.onderteken(document, metTussen, eindeTussen.minus(Duration.ofHours(2)))

        assertTrue(eindeTussen.isBefore(metTussen.certificaat.notAfter.toInstant()))
        assertEquals(eindeTussen, uitgifte.verlooptOp)
    }

    @Test
    fun `een exemplaar geldt van uitgifte tot verloop, en niet als het uit de toekomst komt`() {
        val uitgifte = ondertekenaar.onderteken(document, sleutel, nu)
        val iat = uitgifte.uitgegevenOp

        assertTrue(uitgifte.isGeldigOp(iat))
        assertTrue(uitgifte.isGeldigOp(iat.minusSeconds(60)))
        assertFalse(uitgifte.isGeldigOp(iat.minusSeconds(61)))
        assertTrue(uitgifte.isGeldigOp(uitgifte.verlooptOp.minusSeconds(1)))
        assertFalse(uitgifte.isGeldigOp(uitgifte.verlooptOp))
    }

    @Test
    fun `met een verlopen certificaat wordt niets uitgegeven`() {
        val einde = sleutel.certificaat.notAfter.toInstant()

        assertThrows(OngeldigeOndertekensleutelException::class.java) { ondertekenaar.onderteken(document, sleutel, einde) }
    }

    @Test
    fun `een ververst exemplaar heeft een latere iat, dezelfde versie en een andere ETag`() {
        val eerste = ondertekenaar.onderteken(document, sleutel, nu)
        val tweede = ondertekenaar.onderteken(document, sleutel, nu.plus(Duration.ofHours(1)))

        assertTrue(tweede.uitgegevenOp.isAfter(eerste.uitgegevenOp))
        assertEquals(eerste.versie, tweede.versie)
        assertNotEquals(eerste.etag, tweede.etag)
        assertNotEquals(eerste.jws, tweede.jws)
    }

    @Test
    fun `de ETag is zwak en verandert met de inhoud`() {
        val eerste = ondertekenaar.onderteken(document, sleutel, nu)
        val andereInhoud = ondertekenaar.onderteken(Stelseldocument(emptyList()), sleutel, nu)

        assertTrue(eerste.etag.startsWith("W/\""))
        assertTrue(eerste.etag.endsWith("\""))
        assertNotEquals(eerste.etag, andereInhoud.etag)
    }
}
