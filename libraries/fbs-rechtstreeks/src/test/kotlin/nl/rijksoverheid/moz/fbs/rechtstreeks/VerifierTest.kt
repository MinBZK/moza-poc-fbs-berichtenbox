package nl.rijksoverheid.moz.fbs.rechtstreeks

import nl.rijksoverheid.moz.fbs.kern.identificatie.IdentificatienummerType
import org.jose4j.json.JsonUtil
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Named.named
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.Arguments.arguments
import org.junit.jupiter.params.provider.CsvSource
import org.junit.jupiter.params.provider.MethodSource
import java.security.interfaces.ECPrivateKey
import java.security.interfaces.ECPublicKey
import java.time.Duration
import java.util.Base64

class VerifierTest {

    private val opzet = Opzet()
    private val verifier = opzet.verifier()

    private fun expiresIn(uitgegeven: UitgegevenSessie, aes: ByteArray): Long =
        JsonUtil.parseJson(TestApp.ontsleutelDir(uitgegeven.antwoord, aes))["expires_in"] as Long

    @Test
    fun `de challenge is getekend met de sleutel van het magazijn en draagt een verse nonce en tijdelijke sleutel`() {
        val eerste = opzet.challenge(verifier)
        val tweede = opzet.challenge(verifier)

        assertEquals("challenge", eerste.claims["sub"])
        assertEquals(16, Base64.getDecoder().decode(eerste.nonce).size)
        assertEquals(mapOf("alg" to "ES256", "typ" to "jwt", "kid" to Opzet.MAGAZIJN_KID), TestApp.header(eerste.compact))
        assertTrue(Jose.opCurve(eerste.tijdelijk))
        assertNotEquals(eerste.nonce, tweede.nonce)
        assertNotEquals(eerste.tijdelijk, tweede.tijdelijk)
    }

    @Test
    fun `met een verlopen eigen sleutel tekent de Verifier geen challenge`() {
        opzet.magazijnSleutelGeldigTot = opzet.nu

        assertThrows<IllegalStateException> { opzet.verifier().maakChallenge(Opzet.CLIENT) }
    }

    @Test
    fun `een geldig credential levert een sessie voor de ontvanger, met het token versleuteld onder de AES-sleutel`() {
        val (uitgegeven, aes) = opzet.sessie(verifier)

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

    @ParameterizedTest(name = "{0}")
    @CsvSource("KVK:55667788,KVK", "BSN:999993653,BSN", "RSIN:111222333,RSIN")
    fun `elk ontvangertype van een ondernemer levert een sessie met dat type`(ontvanger: String, type: IdentificatienummerType) {
        val claims = opzet.vcClaims().apply { put("ontvanger", ontvanger) }

        val identiteit = opzet.sessie(verifier, claims).first.sessie.identiteit as Identiteit.Ontvanger

        assertEquals(type, identiteit.ontvanger.type)
        assertEquals(ontvanger.substringAfter(':'), identiteit.ontvanger.waarde)
    }

    @Test
    fun `een cnf zoals WebCrypto hem exporteert, met key_ops en ext, wordt aangenomen`() {
        val jwk = TestApp.jwk(opzet.app.public as ECPublicKey).apply {
            put("key_ops", listOf("verify"))
            put("ext", true)
        }
        val claims = opzet.vcClaims().apply { put("cnf", mapOf("jwk" to jwk)) }

        opzet.sessie(verifier, claims)
    }

    /** Een grens die nog net binnen valt, met de verwachte `expires_in`. */
    fun interface Grensgeval {
        fun pasToe(opzet: Opzet, claims: MutableMap<String, Any>)
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("grensgevallen")
    fun `een credential op de grens levert nog een sessie`(geval: Grensgeval, expiresIn: Long) {
        val claims = opzet.vcClaims()

        geval.pasToe(opzet, claims)

        val (uitgegeven, aes) = opzet.sessie(verifier, claims)

        assertEquals(expiresIn, expiresIn(uitgegeven, aes))
    }

    @Test
    fun `een nonce werkt tot zestig seconden na de challenge`() {
        val challenge = opzet.challenge(verifier)

        opzet.nu = opzet.nu.plusSeconds(59)

        verifier.voltooiMetCredential(challenge.nonce, opzet.versleuteld(challenge, opzet.presentatie(challenge)), Opzet.CLIENT)
    }

    @Test
    fun `een sessie werkt vijftien minuten, en daarna niet meer`() {
        val token = opzet.sessie(verifier).first.sessie.token

        opzet.nu = opzet.nu.plusSeconds(899)

        verifier.sessie(token)

        opzet.nu = opzet.nu.plusSeconds(1)

        assertEquals(Afwijzing.SESSIE_ONBEKEND, assertThrows<AfgewezenException> { verifier.sessie(token) }.reden)
    }

    @Test
    fun `een self-signed presentatie levert een sessie voor de sleutel van de app`() {
        val challenge = opzet.challenge(verifier)
        val presentatie = opzet.presentatie(challenge, opzet.selfSigned(), Verifier.CERTIFICAAT_SELF_SIGNED)

        val uitgegeven = verifier.voltooiSelfSigned(challenge.nonce, opzet.versleuteld(challenge, presentatie), Opzet.CLIENT)

        val identiteit = uitgegeven.sessie.identiteit as Identiteit.AppSleutel

        assertEquals(TestApp.thumbprint(opzet.app.public as ECPublicKey), identiteit.thumbprint)
        assertEquals(opzet.app.public, identiteit.publicKey)
    }

    @Test
    fun `antwoorden en verzoeken gaan versleuteld onder de sessiesleutel`() {
        val (uitgegeven, aes) = opzet.sessie(verifier)

        assertEquals("""{"berichten":[]}""", TestApp.ontsleutelDir(verifier.versleutel(uitgegeven.sessie, """{"berichten":[]}"""), aes))
        assertEquals("verzoek", verifier.ontsleutel(uitgegeven.sessie, TestApp.jweDir("verzoek", aes)))
    }

    @Test
    fun `een verzoek onder een andere sleutel, of te groot, wordt afgewezen`() {
        val sessie = opzet.sessie(verifier).first.sessie
        val anderSleutel = TestApp.jweDir("verzoek", TestApp.aesSleutel())
        val teGroot = "a".repeat(Verifier.MAX_VERZOEK_TEKENS + 1)

        assertEquals(Afwijzing.VERSLEUTELING_ONGELDIG, assertThrows<AfgewezenException> { verifier.ontsleutel(sessie, anderSleutel) }.reden)
        assertEquals(Afwijzing.VERSLEUTELING_ONGELDIG, assertThrows<AfgewezenException> { verifier.ontsleutel(sessie, teGroot) }.reden)
        assertEquals(Afwijzing.VERSLEUTELING_ONGELDIG, assertThrows<AfgewezenException> { verifier.ontsleutel(sessie, "geen.jwe") }.reden)
    }

    @Test
    fun `een token van het ene magazijn werkt niet bij een ander, ook niet met een gedeelde opslag`() {
        val ander = opzet.verifier(Opzet.ANDER_MAGAZIJN_OIN)
        val token = opzet.sessie(verifier).first.sessie.token

        assertEquals(Afwijzing.SESSIE_ONBEKEND, assertThrows<AfgewezenException> { ander.sessie(token) }.reden)
    }

    @Test
    fun `een nonce van het ene magazijn is bij een ander onbekend`() {
        val ander = opzet.verifier(Opzet.ANDER_MAGAZIJN_OIN)
        val challenge = opzet.challenge(verifier)

        val presentatie = opzet.presentatie(challenge).apply {
            put("app_nonce_signature", opzet.nonceHandtekening(challenge.nonce, aud = Opzet.ANDER_MAGAZIJN_OIN))
        }

        val fout = assertThrows<AfgewezenException> {
            ander.voltooiMetCredential(challenge.nonce, opzet.versleuteld(challenge, presentatie), Opzet.CLIENT)
        }

        assertEquals(Afwijzing.NONCE_ONBEKEND, fout.reden)
    }

    @ParameterizedTest(name = "eerste poging: {0}")
    @MethodSource("eerstePogingen")
    fun `een nonce werkt één keer, hoe de eerste poging ook afliep`(eerste: (Opzet, Opzet.Challenge) -> String) {
        val challenge = opzet.challenge(verifier)

        runCatching { verifier.voltooiMetCredential(challenge.nonce, eerste(opzet, challenge), Opzet.CLIENT) }

        val tweede = assertThrows<AfgewezenException> {
            verifier.voltooiMetCredential(challenge.nonce, opzet.versleuteld(challenge, opzet.presentatie(challenge)), Opzet.CLIENT)
        }

        assertEquals(Afwijzing.NONCE_ONBEKEND, tweede.reden)
    }

    @Test
    fun `een nonce verloopt na zestig seconden`() {
        val challenge = opzet.challenge(verifier)

        opzet.nu = opzet.nu.plusSeconds(60)

        val fout = assertThrows<AfgewezenException> {
            verifier.voltooiMetCredential(challenge.nonce, opzet.versleuteld(challenge, opzet.presentatie(challenge)), Opzet.CLIENT)
        }

        assertEquals(Afwijzing.NONCE_ONBEKEND, fout.reden)
    }

    @Test
    fun `de reden van een afwijzing staat in de melding, de inhoud van het verzoek nergens in de stacktrace`() {
        val challenge = opzet.challenge(verifier)
        val kapot = TestApp.jweEcdh("""{"ontvanger": BSN:999993653}""", challenge.tijdelijk)

        val fout = assertThrows<AfgewezenException> { verifier.voltooiMetCredential(challenge.nonce, kapot, Opzet.CLIENT) }

        assertEquals("PRESENTATIE_ONLEESBAAR", fout.message)
        assertFalse(fout.stackTraceToString().contains("999993653"))
    }

    @Test
    fun `toString van sessie en sleutel laat token, AES-sleutel en ontvanger weg`() {
        val sessie = opzet.sessie(verifier).first.sessie
        val sleutel = VerifierSleutel(Opzet.MAGAZIJN_KID, opzet.magazijnSleutel.private as ECPrivateKey, opzet.nu)

        assertFalse(sessie.toString().contains(sessie.token))
        assertFalse(sessie.toString().contains("55667788"))
        assertTrue(sessie.toString().contains("type=KVK"))
        assertEquals("VerifierSleutel(kid=${Opzet.MAGAZIJN_KID}, geldigTot=${opzet.nu})", sleutel.toString())
    }

    @Test
    fun `een Verifier-sleutel buiten P-256 wordt geweigerd`() {
        val p384 = TestApp.sleutelpaar("secp384r1").private as ECPrivateKey

        assertThrows<IllegalArgumentException> { VerifierSleutel("k", p384, opzet.nu) }
    }

    @Test
    fun `een Verifier-sleutel zonder kid wordt geweigerd`() {
        assertThrows<IllegalArgumentException> { VerifierSleutel(" ", opzet.magazijnSleutel.private as ECPrivateKey, opzet.nu) }
    }

    companion object {

        private fun grens(naam: String, expiresIn: Long, geval: Grensgeval): Arguments = arguments(named(naam, geval), expiresIn)

        @JvmStatic
        fun grensgevallen(): List<Arguments> = listOf(
            grens("iat en nbf precies zestig seconden vooruit", 900, Grensgeval { o, c ->
                c["iat"] = o.nu.plusSeconds(60).epochSecond
                c["nbf"] = o.nu.plusSeconds(60).epochSecond
            }),
            grens("exp precies zestig seconden vooruit", 60, Grensgeval { o, c -> c["exp"] = o.nu.plusSeconds(60).epochSecond }),
            grens("exp precies 24 uur na iat", 900, Grensgeval { o, c -> c["exp"] = o.nu.plus(Duration.ofHours(24)).epochSecond }),
            grens("exp precies vijftien minuten vooruit", 900, Grensgeval { o, c -> c["exp"] = o.nu.plusSeconds(900).epochSecond }),
            grens("exp een seconde na vijftien minuten", 900, Grensgeval { o, c -> c["exp"] = o.nu.plusSeconds(901).epochSecond }),
            grens("App Manager-sleutel verloopt een seconde na iat", 900, Grensgeval { o, _ -> o.appManagerGeldigTot = o.nu.plusSeconds(1) }),
            grens("App Manager-sleutel inmiddels verlopen, maar geldig op iat", 900, Grensgeval { o, c ->
                c["iat"] = o.nu.minus(Duration.ofHours(2)).epochSecond
                c["nbf"] = o.nu.minus(Duration.ofHours(2)).epochSecond
                o.appManagerGeldigTot = o.nu.minus(Duration.ofHours(1))
            }),
        )

        @JvmStatic
        fun eerstePogingen(): List<Arguments> = listOf(
            arguments(named("geen JWE", { _: Opzet, _: Opzet.Challenge -> "geen.jwe" })),
            arguments(named("epk naast de curve", { _: Opzet, c: Opzet.Challenge ->
                TestApp.jweEcdh("{}", c.tijdelijk, mapOf("epk" to AfwijzingTest.NAAST_DE_CURVE))
            })),
            arguments(named("AES-sleutel te kort", { o: Opzet, c: Opzet.Challenge ->
                o.versleuteld(c, o.presentatie(c).apply { put("session_aes_key", "kort") })
            })),
            arguments(named("geslaagd", { o: Opzet, c: Opzet.Challenge -> o.versleuteld(c, o.presentatie(c)) })),
        )
    }
}
