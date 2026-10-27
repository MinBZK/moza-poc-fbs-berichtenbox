package nl.rijksoverheid.moz.fbs.rechtstreeks

import org.jose4j.json.JsonUtil
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Named.named
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.Arguments.arguments
import org.junit.jupiter.params.provider.MethodSource
import java.math.BigInteger
import java.security.interfaces.ECPublicKey
import java.time.Duration
import java.util.Base64

/**
 * Elke rij uit "Wat elke controle tegenhoudt" in het ontwerp als afwijzing: één aanpassing aan een
 * verder geldig verzoek, en de reden die de Verifier dan moet geven.
 */
class AfwijzingTest {

    /** Een scenario krijgt een verse opzet en een challenge, en doet zelf de `complete`. */
    fun interface Scenario {
        fun voer(opzet: Opzet, verifier: Verifier, challenge: Opzet.Challenge)
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("scenario's")
    fun `een aangepast verzoek wordt afgewezen met de juiste reden`(scenario: Scenario, reden: Afwijzing) {
        val opzet = Opzet()
        val verifier = opzet.verifier()
        val challenge = opzet.challenge(verifier)

        val fout = assertThrows<AfgewezenException> { scenario.voer(opzet, verifier, challenge) }

        assertEquals(reden, fout.reden)
    }

    companion object {

        private fun metCredential(aanpassing: MutableMap<String, Any>.(Opzet) -> Unit) = Scenario { opzet, verifier, challenge ->
            val claims = opzet.vcClaims().apply { aanpassing(opzet) }

            verifier.voltooiMetCredential(challenge.nonce, opzet.versleuteld(challenge, opzet.presentatie(challenge, opzet.vc(claims))))
        }

        private fun metPresentatie(aanpassing: Opzet.(MutableMap<String, Any>, Opzet.Challenge) -> Unit) =
            Scenario { opzet, verifier, challenge ->
                val presentatie = opzet.presentatie(challenge).apply { opzet.aanpassing(this, challenge) }

                verifier.voltooiMetCredential(challenge.nonce, opzet.versleuteld(challenge, presentatie))
            }

        private fun selfSigned(certificaat: Opzet.() -> String) = Scenario { opzet, verifier, challenge ->
            val presentatie = opzet.presentatie(challenge, opzet.certificaat(), Verifier.CERTIFICAAT_SELF_SIGNED)

            verifier.voltooiSelfSigned(challenge.nonce, opzet.versleuteld(challenge, presentatie))
        }

        private fun geval(naam: String, reden: Afwijzing, scenario: Scenario): Arguments = arguments(named(naam, scenario), reden)

        /** Een punt dat niet op P-256 ligt: (1, 1). */
        private val NAAST_DE_CURVE = mapOf(
            "kty" to "EC",
            "crv" to "P-256",
            "x" to Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(31) + BigInteger.ONE.toByteArray()),
            "y" to Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(31) + BigInteger.ONE.toByteArray()),
        )

        @JvmStatic
        fun `scenario's`(): List<Arguments> = listOf(
            // Het credential zelf.
            geval("credential getekend met een andere sleutel", Afwijzing.HANDTEKENING_ONGELDIG, Scenario { opzet, verifier, challenge ->
                val vc = opzet.vc(sleutel = TestApp.sleutelpaar().private)

                verifier.voltooiMetCredential(challenge.nonce, opzet.versleuteld(challenge, opzet.presentatie(challenge, vc)))
            }),
            geval("onbekende App Manager", Afwijzing.UITGEVER_ONBEKEND, metCredential { put("iss", "00000000000000009999") }),
            geval("onbekende sleutel-id", Afwijzing.UITGEVER_ONBEKEND, Scenario { opzet, verifier, challenge ->
                val vc = opzet.vc(kid = "andere-sleutel")

                verifier.voltooiMetCredential(challenge.nonce, opzet.versleuteld(challenge, opzet.presentatie(challenge, vc)))
            }),
            geval("andere scope", Afwijzing.SCOPE_ONJUIST, metCredential { put("scope", "nl.vorijk.oauth_scope.blauwe_knop") }),
            geval("verlopen", Afwijzing.CREDENTIAL_NIET_GELDIG, metCredential { put("exp", it.nu.minusSeconds(1).epochSecond) }),
            geval("verloopt binnen een minuut", Afwijzing.CREDENTIAL_NIET_GELDIG, metCredential { put("exp", it.nu.plusSeconds(59).epochSecond) }),
            geval("nog niet geldig", Afwijzing.CREDENTIAL_NIET_GELDIG, metCredential { put("nbf", it.nu.plusSeconds(61).epochSecond) }),
            geval("iat in de toekomst", Afwijzing.CREDENTIAL_NIET_GELDIG, metCredential {
                put("iat", it.nu.plusSeconds(61).epochSecond)
                put("nbf", it.nu.plusSeconds(61).epochSecond)
            }),
            geval("nbf vóór iat", Afwijzing.CREDENTIAL_NIET_GELDIG, metCredential { put("nbf", it.nu.minusSeconds(1).epochSecond) }),
            geval("langer dan 24 uur uitgegeven", Afwijzing.CREDENTIAL_NIET_GELDIG, metCredential {
                put("exp", it.nu.plus(Duration.ofHours(24)).plusSeconds(1).epochSecond)
            }),
            geval("uitgegeven nadat de sleutel van de App Manager verliep", Afwijzing.UITGEVERSLEUTEL_VERLOPEN, metCredential {
                it.appManagerGeldigTot = it.nu
            }),
            geval("tijd als tekst", Afwijzing.CREDENTIAL_ONVOLLEDIG, metCredential { put("iat", it.nu.epochSecond.toString()) }),
            geval("zonder iss", Afwijzing.CREDENTIAL_ONVOLLEDIG, metCredential { remove("iss") }),
            geval("zonder ontvanger", Afwijzing.CREDENTIAL_ONVOLLEDIG, metCredential { remove("ontvanger") }),
            geval("BSN zonder elfproef", Afwijzing.ONTVANGER_ONGELDIG, metCredential { put("ontvanger", "BSN:123456789") }),
            geval("OIN als ontvanger", Afwijzing.ONTVANGER_ONGELDIG, metCredential { put("ontvanger", "OIN:00000001234567890001") }),
            geval("zonder cnf", Afwijzing.APPSLEUTEL_ONGELDIG, metCredential { remove("cnf") }),
            geval("cnf met private deel", Afwijzing.APPSLEUTEL_ONGELDIG, metCredential {
                put("cnf", mapOf("jwk" to TestApp.jwk(it.app.public as ECPublicKey).apply { put("d", "AAAA") }))
            }),
            geval("cnf naast de curve", Afwijzing.APPSLEUTEL_ONGELDIG, metCredential { put("cnf", mapOf("jwk" to NAAST_DE_CURVE)) }),
            geval("cnf op P-384", Afwijzing.APPSLEUTEL_ONGELDIG, metCredential {
                val p384 = TestApp.sleutelpaar("secp384r1").public as ECPublicKey

                put("cnf", mapOf("jwk" to org.jose4j.jwk.EllipticCurveJsonWebKey(p384).toParams(org.jose4j.jwk.JsonWebKey.OutputControlLevel.PUBLIC_ONLY)))
            }),
            geval("sleutel in de header van het credential", Afwijzing.HEADER_NIET_TOEGESTAAN, Scenario { opzet, verifier, challenge ->
                val header = mapOf("alg" to "ES256", "kid" to Opzet.APP_MANAGER_KID, "jwk" to TestApp.jwk(opzet.appManager.public as ECPublicKey))
                val vc = TestApp.jws(header, opzet.vcClaims(), opzet.appManager.private)

                verifier.voltooiMetCredential(challenge.nonce, opzet.versleuteld(challenge, opzet.presentatie(challenge, vc)))
            }),
            geval("crit in de header van het credential", Afwijzing.HEADER_NIET_TOEGESTAAN, Scenario { opzet, verifier, challenge ->
                val header = mapOf("alg" to "ES256", "kid" to Opzet.APP_MANAGER_KID, "crit" to listOf("exp"))
                val vc = TestApp.jws(header, opzet.vcClaims(), opzet.appManager.private)

                verifier.voltooiMetCredential(challenge.nonce, opzet.versleuteld(challenge, opzet.presentatie(challenge, vc)))
            }),
            geval("ander algoritme in de header van het credential", Afwijzing.HANDTEKENING_ONGELDIG, Scenario { opzet, verifier, challenge ->
                val header = mapOf("alg" to "ES384", "kid" to Opzet.APP_MANAGER_KID)
                val vc = TestApp.jws(header, opzet.vcClaims(), opzet.appManager.private)

                verifier.voltooiMetCredential(challenge.nonce, opzet.versleuteld(challenge, opzet.presentatie(challenge, vc)))
            }),

            // De binding aan de sleutel van de app en aan dit magazijn.
            geval("nonce getekend met een andere sleutel dan in het credential", Afwijzing.NONCEHANDTEKENING_ONGELDIG, metPresentatie { p, c ->
                p["app_nonce_signature"] = nonceHandtekening(c.nonce, sleutel = TestApp.sleutelpaar().private)
            }),
            geval("aud van een ander magazijn", Afwijzing.NONCEHANDTEKENING_ONGELDIG, metPresentatie { p, c ->
                p["app_nonce_signature"] = nonceHandtekening(c.nonce, aud = Opzet.ANDER_MAGAZIJN_OIN)
            }),
            geval("andere nonce getekend", Afwijzing.NONCEHANDTEKENING_ONGELDIG, metPresentatie { p, _ ->
                p["app_nonce_signature"] = nonceHandtekening("ZWVuIGFuZGVyZSBub25jZQ==")
            }),
            geval("andere sub in de nonce-handtekening", Afwijzing.NONCEHANDTEKENING_ONGELDIG, metPresentatie { p, c ->
                p["app_nonce_signature"] = nonceHandtekening(c.nonce, sub = "challenge")
            }),

            // De presentatie.
            geval("AES-sleutel van 16 bytes", Afwijzing.AES_SLEUTEL_ONGELDIG, metPresentatie { p, _ ->
                p["session_aes_key"] = Base64.getEncoder().encodeToString(TestApp.aesSleutel(16))
            }),
            geval("AES-sleutel geen base64", Afwijzing.AES_SLEUTEL_ONGELDIG, metPresentatie { p, _ -> p["session_aes_key"] = "geen base64!" }),
            geval("zonder certificaat", Afwijzing.PRESENTATIE_ONVOLLEDIG, metPresentatie { p, _ -> p.remove("certificate") }),
            geval("certificaat geen tekst", Afwijzing.PRESENTATIE_ONVOLLEDIG, metPresentatie { p, _ -> p["certificate"] = 42 }),
            geval("onbekende nonce", Afwijzing.NONCE_ONBEKEND, Scenario { opzet, verifier, challenge ->
                verifier.voltooiMetCredential("b25iZWtlbmRlIG5vbmNl", opzet.versleuteld(challenge, opzet.presentatie(challenge)))
            }),
            geval("versleuteld naar een andere sleutel", Afwijzing.PRESENTATIE_ONLEESBAAR, Scenario { opzet, verifier, challenge ->
                val ander = TestApp.sleutelpaar().public as ECPublicKey

                verifier.voltooiMetCredential(challenge.nonce, TestApp.jweEcdh(JsonUtil.toJson(opzet.presentatie(challenge)), ander))
            }),
            geval("tijdelijke sleutel van de app naast de curve", Afwijzing.PRESENTATIE_ONLEESBAAR, Scenario { opzet, verifier, challenge ->
                val jwe = TestApp.jweEcdh(JsonUtil.toJson(opzet.presentatie(challenge)), challenge.tijdelijk, epk = NAAST_DE_CURVE)

                verifier.voltooiMetCredential(challenge.nonce, jwe)
            }),
            geval("jku in de header van de JWE", Afwijzing.HEADER_NIET_TOEGESTAAN, Scenario { opzet, verifier, challenge ->
                val jwe = TestApp.jweEcdh(JsonUtil.toJson(opzet.presentatie(challenge)), challenge.tijdelijk, mapOf("jku" to "https://aanvaller.example"))

                verifier.voltooiMetCredential(challenge.nonce, jwe)
            }),
            geval("geen JWE", Afwijzing.PRESENTATIE_ONLEESBAAR, Scenario { _, verifier, challenge ->
                verifier.voltooiMetCredential(challenge.nonce, "geen.jwe")
            }),
            geval("presentatie geen JSON", Afwijzing.PRESENTATIE_ONLEESBAAR, Scenario { _, verifier, challenge ->
                verifier.voltooiMetCredential(challenge.nonce, TestApp.jweEcdh("geen json", challenge.tijdelijk))
            }),

            // Het self-signed certificaat van het activeren.
            geval("self-signed met andere sub", Afwijzing.HANDTEKENING_ONGELDIG, selfSigned { selfSigned(sub = "iets anders") }),
            geval("self-signed getekend met een andere sleutel", Afwijzing.HANDTEKENING_ONGELDIG, selfSigned {
                TestApp.es256(mapOf("sub" to Verifier.CERTIFICAAT_SELF_SIGNED, "cnf" to mapOf("jwk" to TestApp.jwk(app.public as ECPublicKey))), TestApp.sleutelpaar().private)
            }),
            geval("self-signed zonder cnf", Afwijzing.APPSLEUTEL_ONGELDIG, selfSigned {
                TestApp.es256(mapOf("sub" to Verifier.CERTIFICAAT_SELF_SIGNED), app.private)
            }),
        )
    }
}
