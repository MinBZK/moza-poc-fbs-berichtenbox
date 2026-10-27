package nl.rijksoverheid.moz.fbs.rechtstreeks

import nl.rijksoverheid.moz.fbs.kern.identificatie.IdentificatienummerType
import org.jose4j.json.JsonUtil
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.security.interfaces.ECPublicKey
import java.time.Duration
import java.util.Base64

class VerifierTest {

    private val opzet = Opzet()
    private val verifier = opzet.verifier()

    @Test
    fun `de challenge is getekend met de sleutel van het magazijn en draagt een verse nonce en tijdelijke sleutel`() {
        val eerste = opzet.challenge(verifier)
        val tweede = opzet.challenge(verifier)

        assertEquals("challenge", eerste.claims["sub"])
        assertEquals(16, Base64.getDecoder().decode(eerste.nonce).size)
        assertEquals(mapOf("alg" to "ES256", "typ" to "jwt", "kid" to Opzet.MAGAZIJN_KID), TestApp.header(eerste.compact))
        assertTrue(Jose.opCurve(eerste.tijdelijk))
        assertFalse(eerste.nonce == tweede.nonce)
        assertFalse(eerste.tijdelijk == tweede.tijdelijk)
    }

    @Test
    fun `een geldig credential levert een sessie voor de ontvanger, met het token versleuteld onder de AES-sleutel`() {
        val challenge = opzet.challenge(verifier)
        val aes = TestApp.aesSleutel()

        val uitgegeven = verifier.voltooiMetCredential(challenge.nonce, opzet.versleuteld(challenge, opzet.presentatie(challenge, aesSleutel = aes)))

        val antwoord = JsonUtil.parseJson(TestApp.ontsleutelDir(uitgegeven.antwoord, aes))
        val token = antwoord["session_token"] as String

        assertTrue(token.matches(Regex("[A-Za-z0-9]{32}")))
        assertEquals(900L, antwoord["expires_in"])

        val sessie = verifier.sessie(token)
        val identiteit = sessie.identiteit as Identiteit.Ontvanger

        assertEquals(IdentificatienummerType.KVK, identiteit.ontvanger.type)
        assertEquals("55667788", identiteit.ontvanger.waarde)
        assertEquals(opzet.nu.plus(Duration.ofMinutes(15)), sessie.verlooptOp)
    }

    @Test
    fun `een sessie duurt niet langer dan het credential`() {
        val claims = opzet.vcClaims().apply { put("exp", opzet.nu.plusSeconds(300).epochSecond) }
        val challenge = opzet.challenge(verifier)
        val aes = TestApp.aesSleutel()

        val uitgegeven = verifier.voltooiMetCredential(
            challenge.nonce,
            opzet.versleuteld(challenge, opzet.presentatie(challenge, opzet.vc(claims), aesSleutel = aes)),
        )

        assertEquals(300L, JsonUtil.parseJson(TestApp.ontsleutelDir(uitgegeven.antwoord, aes))["expires_in"])
    }

    @Test
    fun `een self-signed presentatie levert een sessie voor de sleutel van de app`() {
        val challenge = opzet.challenge(verifier)
        val presentatie = opzet.presentatie(challenge, opzet.selfSigned(), Verifier.CERTIFICAAT_SELF_SIGNED)

        val uitgegeven = verifier.voltooiSelfSigned(challenge.nonce, opzet.versleuteld(challenge, presentatie))

        val identiteit = uitgegeven.sessie.identiteit as Identiteit.AppSleutel

        assertEquals(TestApp.thumbprint(opzet.app.public as ECPublicKey), identiteit.thumbprint)
        assertEquals(opzet.app.public, identiteit.publicKey)
    }

    @Test
    fun `antwoorden en verzoeken gaan versleuteld onder de sessiesleutel`() {
        val challenge = opzet.challenge(verifier)
        val aes = TestApp.aesSleutel()
        val sessie = verifier.voltooiMetCredential(
            challenge.nonce,
            opzet.versleuteld(challenge, opzet.presentatie(challenge, aesSleutel = aes)),
        ).sessie

        assertEquals("""{"berichten":[]}""", TestApp.ontsleutelDir(verifier.versleutel(sessie, """{"berichten":[]}"""), aes))
        assertEquals("verzoek", verifier.ontsleutel(sessie, TestApp.jweDir("verzoek", aes)))
    }

    @Test
    fun `een verminkt verzoek onder de sessiesleutel wordt afgewezen`() {
        val challenge = opzet.challenge(verifier)
        val aes = TestApp.aesSleutel()
        val sessie = verifier.voltooiMetCredential(
            challenge.nonce,
            opzet.versleuteld(challenge, opzet.presentatie(challenge, aesSleutel = aes)),
        ).sessie
        val anderSleutel = TestApp.jweDir("verzoek", TestApp.aesSleutel())

        assertEquals(Afwijzing.VERSLEUTELING_ONGELDIG, assertThrows<AfgewezenException> { verifier.ontsleutel(sessie, anderSleutel) }.reden)
    }

    @Test
    fun `een sessie verloopt na vijftien minuten`() {
        val challenge = opzet.challenge(verifier)
        val token = verifier.voltooiMetCredential(challenge.nonce, opzet.versleuteld(challenge, opzet.presentatie(challenge))).sessie.token

        opzet.nu = opzet.nu.plus(Duration.ofMinutes(15))

        assertEquals(Afwijzing.SESSIE_ONBEKEND, assertThrows<AfgewezenException> { verifier.sessie(token) }.reden)
    }

    @Test
    fun `een token van het ene magazijn werkt niet bij een ander, ook niet met een gedeelde opslag`() {
        val ander = opzet.verifier(Opzet.ANDER_MAGAZIJN_OIN)
        val challenge = opzet.challenge(verifier)
        val token = verifier.voltooiMetCredential(challenge.nonce, opzet.versleuteld(challenge, opzet.presentatie(challenge))).sessie.token

        assertEquals(Afwijzing.SESSIE_ONBEKEND, assertThrows<AfgewezenException> { ander.sessie(token) }.reden)
    }

    @Test
    fun `een nonce van het ene magazijn is bij een ander onbekend`() {
        val ander = opzet.verifier(Opzet.ANDER_MAGAZIJN_OIN)
        val challenge = opzet.challenge(verifier)
        val presentatie = opzet.presentatie(challenge).apply {
            put("app_nonce_signature", opzet.nonceHandtekening(challenge.nonce, aud = Opzet.ANDER_MAGAZIJN_OIN))
        }

        val fout = assertThrows<AfgewezenException> { ander.voltooiMetCredential(challenge.nonce, opzet.versleuteld(challenge, presentatie)) }

        assertEquals(Afwijzing.NONCE_ONBEKEND, fout.reden)
    }

    @Test
    fun `een nonce werkt één keer, ook als de eerste poging faalde`() {
        val challenge = opzet.challenge(verifier)
        val fout = opzet.presentatie(challenge).apply { put("session_aes_key", "kort") }

        assertThrows<AfgewezenException> { verifier.voltooiMetCredential(challenge.nonce, opzet.versleuteld(challenge, fout)) }

        val tweede = assertThrows<AfgewezenException> {
            verifier.voltooiMetCredential(challenge.nonce, opzet.versleuteld(challenge, opzet.presentatie(challenge)))
        }

        assertEquals(Afwijzing.NONCE_ONBEKEND, tweede.reden)
    }

    @Test
    fun `een nonce verloopt na zestig seconden`() {
        val challenge = opzet.challenge(verifier)

        opzet.nu = opzet.nu.plusSeconds(60)

        val fout = assertThrows<AfgewezenException> {
            verifier.voltooiMetCredential(challenge.nonce, opzet.versleuteld(challenge, opzet.presentatie(challenge)))
        }

        assertEquals(Afwijzing.NONCE_ONBEKEND, fout.reden)
    }

    @Test
    fun `een magazijn neemt geen self-signed presentatie aan, en de App Manager geen credential`() {
        val eerste = opzet.challenge(verifier)
        val selfSigned = opzet.presentatie(eerste, opzet.selfSigned(), Verifier.CERTIFICAAT_SELF_SIGNED)
        val tweede = opzet.challenge(verifier)
        val credential = opzet.presentatie(tweede)

        val bijMagazijn = assertThrows<AfgewezenException> { verifier.voltooiMetCredential(eerste.nonce, opzet.versleuteld(eerste, selfSigned)) }
        val bijAppManager = assertThrows<AfgewezenException> { verifier.voltooiSelfSigned(tweede.nonce, opzet.versleuteld(tweede, credential)) }

        assertEquals(Afwijzing.CERTIFICAATSOORT_ONJUIST, bijMagazijn.reden)
        assertEquals(Afwijzing.CERTIFICAATSOORT_ONJUIST, bijAppManager.reden)
    }

    @Test
    fun `de reden van een afwijzing staat in de melding, de inhoud van het verzoek niet`() {
        val claims = opzet.vcClaims().apply { put("ontvanger", "BSN:123456789") }
        val challenge = opzet.challenge(verifier)

        val fout = assertThrows<AfgewezenException> {
            verifier.voltooiMetCredential(challenge.nonce, opzet.versleuteld(challenge, opzet.presentatie(challenge, opzet.vc(claims))))
        }

        assertEquals("ONTVANGER_ONGELDIG", fout.message)
        assertFalse(fout.toString().contains("123456789"))
    }

    @Test
    fun `toString van sessie en sleutel laat token, AES-sleutel en ontvanger weg`() {
        val challenge = opzet.challenge(verifier)
        val sessie = verifier.voltooiMetCredential(challenge.nonce, opzet.versleuteld(challenge, opzet.presentatie(challenge))).sessie

        assertFalse(sessie.toString().contains(sessie.token))
        assertFalse(sessie.toString().contains("55667788"))
        assertTrue(sessie.toString().contains("type=KVK"))
        assertEquals("VerifierSleutel(kid=${Opzet.MAGAZIJN_KID})", VerifierSleutel(Opzet.MAGAZIJN_KID, opzet.magazijnSleutel.private as java.security.interfaces.ECPrivateKey).toString())
    }
}
