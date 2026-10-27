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
 * De controles uit "Wat elke controle tegenhoudt" in het ontwerp die deze library uitvoert, elk als
 * afwijzing: één aanpassing aan een verder geldig verzoek, en de reden die de Verifier dan moet
 * geven. Verbruik van de nonce, scheiding per magazijn en de duur van een sessie staan in
 * [VerifierTest].
 */
class AfwijzingTest {

    /** Een scenario krijgt een verse opzet en een challenge, en doet zelf de `complete`. */
    fun interface Scenario {
        fun voerUit(opzet: Opzet, verifier: Verifier, challenge: Opzet.Challenge)
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("scenario's")
    fun `een aangepast verzoek wordt afgewezen met de juiste reden`(scenario: Scenario, reden: Afwijzing) {
        val opzet = Opzet()
        val verifier = opzet.verifier()
        val challenge = opzet.challenge(verifier)

        val fout = assertThrows<AfgewezenException> { scenario.voerUit(opzet, verifier, challenge) }

        assertEquals(reden, fout.reden)
    }

    companion object {

        /** Een punt dat niet op P-256 ligt: (1, 1). */
        val NAAST_DE_CURVE = mapOf(
            "kty" to "EC",
            "crv" to "P-256",
            "x" to TestApp.b64url.encodeToString(ByteArray(31) + BigInteger.ONE.toByteArray()),
            "y" to TestApp.b64url.encodeToString(ByteArray(31) + BigInteger.ONE.toByteArray()),
        )

        private fun metCredential(aanpassing: MutableMap<String, Any>.(Opzet) -> Unit) = Scenario { opzet, verifier, challenge ->
            val claims = opzet.vcClaims().apply { aanpassing(opzet) }

            verifier.voltooiMetCredential(challenge.nonce, opzet.versleuteld(challenge, opzet.presentatie(challenge, opzet.vc(claims))))
        }

        private fun metCertificaat(certificaat: (Opzet) -> String) = Scenario { opzet, verifier, challenge ->
            verifier.voltooiMetCredential(challenge.nonce, opzet.versleuteld(challenge, opzet.presentatie(challenge, certificaat(opzet))))
        }

        private fun metPresentatie(aanpassing: Opzet.(MutableMap<String, Any>, Opzet.Challenge) -> Unit) =
            Scenario { opzet, verifier, challenge ->
                val presentatie = opzet.presentatie(challenge).apply { opzet.aanpassing(this, challenge) }

                verifier.voltooiMetCredential(challenge.nonce, opzet.versleuteld(challenge, presentatie))
            }

        private fun metJwe(jwe: (Opzet, Opzet.Challenge) -> String) = Scenario { opzet, verifier, challenge ->
            verifier.voltooiMetCredential(challenge.nonce, jwe(opzet, challenge))
        }

        private fun selfSigned(soort: String = Verifier.CERTIFICAAT_SELF_SIGNED, aanpassing: Opzet.(MutableMap<String, Any>) -> String) =
            Scenario { opzet, verifier, challenge ->
                val presentatie = opzet.presentatie(challenge, opzet.selfSigned(), soort)

                presentatie["certificate"] = opzet.aanpassing(presentatie)

                verifier.voltooiSelfSigned(challenge.nonce, opzet.versleuteld(challenge, presentatie), Opzet.CLIENT)
            }

        private fun geval(naam: String, reden: Afwijzing, scenario: Scenario): Arguments = arguments(named(naam, scenario), reden)

        private fun vcMetHeader(opzet: Opzet, header: Map<String, Any>): String =
            TestApp.jws(header, opzet.vcClaims(), opzet.appManager.private)

        @JvmStatic
        fun `scenario's`(): List<Arguments> = credential() + binding() + presentatie() + versleuteling() + activeren()

        private fun credential(): List<Arguments> = listOf(
            geval("credential getekend met een andere sleutel", Afwijzing.HANDTEKENING_ONGELDIG, metCertificaat { it.vc(sleutel = TestApp.sleutelpaar().private) }),
            geval("onbekende App Manager", Afwijzing.APP_MANAGER_ONBEKEND, metCredential { put("iss", "00000000000000009999") }),
            geval("onbekende sleutel-id", Afwijzing.APP_MANAGER_ONBEKEND, metCertificaat { it.vc(kid = "andere-sleutel") }),
            geval("andere scope", Afwijzing.SCOPE_ONJUIST, metCredential { put("scope", "nl.vorijk.oauth_scope.blauwe_knop") }),
            geval("verlopen", Afwijzing.CREDENTIAL_NIET_GELDIG, metCredential { put("exp", it.nu.minusSeconds(1).epochSecond) }),
            geval("verloopt binnen een minuut", Afwijzing.CREDENTIAL_NIET_GELDIG, metCredential { put("exp", it.nu.plusSeconds(59).epochSecond) }),
            geval("nog niet geldig", Afwijzing.CREDENTIAL_NIET_GELDIG, metCredential {
                put("iat", it.nu.plusSeconds(61).epochSecond)
                put("nbf", it.nu.plusSeconds(61).epochSecond)
            }),
            geval("nbf vóór iat", Afwijzing.CREDENTIAL_NIET_GELDIG, metCredential { put("nbf", it.nu.minusSeconds(1).epochSecond) }),
            geval("langer dan 24 uur uitgegeven", Afwijzing.CREDENTIAL_NIET_GELDIG, metCredential {
                put("exp", it.nu.plus(Duration.ofHours(24)).plusSeconds(1).epochSecond)
            }),
            geval("exp voorbij het jaar 9999", Afwijzing.CREDENTIAL_NIET_GELDIG, metCredential { put("exp", Long.MAX_VALUE) }),
            geval("iat vóór 1970", Afwijzing.CREDENTIAL_NIET_GELDIG, metCredential { put("iat", Long.MIN_VALUE) }),
            geval("App Manager-sleutel verliep op iat", Afwijzing.APP_MANAGER_SLEUTEL_VERLOPEN, metCredential { it.appManagerGeldigTot = it.nu }),
            geval("App Manager-sleutel verliep vóór iat", Afwijzing.APP_MANAGER_SLEUTEL_VERLOPEN, metCredential {
                put("iat", it.nu.minus(Duration.ofMinutes(30)).epochSecond)
                put("nbf", it.nu.minus(Duration.ofMinutes(30)).epochSecond)
                it.appManagerGeldigTot = it.nu.minus(Duration.ofHours(1))
            }),
            geval("tijd als tekst", Afwijzing.CREDENTIAL_ONVOLLEDIG, metCredential { put("iat", it.nu.epochSecond.toString()) }),
            geval("tijd als breuk", Afwijzing.CREDENTIAL_ONVOLLEDIG, metCredential { put("iat", 1.5e9) }),
            geval("zonder iss", Afwijzing.CREDENTIAL_ONVOLLEDIG, metCredential { remove("iss") }),
            geval("zonder ontvanger", Afwijzing.CREDENTIAL_ONVOLLEDIG, metCredential { remove("ontvanger") }),
            geval("BSN zonder elfproef", Afwijzing.ONTVANGER_ONGELDIG, metCredential { put("ontvanger", "BSN:123456789") }),
            geval("OIN als ontvanger", Afwijzing.ONTVANGER_ONGELDIG, metCredential { put("ontvanger", "OIN:00000001234567890001") }),
            geval("onbekend type ontvanger", Afwijzing.ONTVANGER_ONGELDIG, metCredential { put("ontvanger", "XYZ:1") }),
            geval("zonder cnf", Afwijzing.APPSLEUTEL_ONGELDIG, metCredential { remove("cnf") }),
            geval("cnf.jwk als tekst", Afwijzing.APPSLEUTEL_ONGELDIG, metCredential { put("cnf", mapOf("jwk" to "x")) }),
            geval("cnf met private deel", Afwijzing.APPSLEUTEL_ONGELDIG, metCredential {
                put("cnf", mapOf("jwk" to TestApp.jwk(it.app.public as ECPublicKey).apply { put("d", "AAAA") }))
            }),
            geval("cnf zonder x", Afwijzing.APPSLEUTEL_ONGELDIG, metCredential {
                put("cnf", mapOf("jwk" to TestApp.jwk(it.app.public as ECPublicKey).apply { remove("x") }))
            }),
            geval("cnf met kty oct", Afwijzing.APPSLEUTEL_ONGELDIG, metCredential {
                put("cnf", mapOf("jwk" to TestApp.jwk(it.app.public as ECPublicKey).apply { put("kty", "oct") }))
            }),
            geval("cnf naast de curve", Afwijzing.APPSLEUTEL_ONGELDIG, metCredential { put("cnf", mapOf("jwk" to NAAST_DE_CURVE)) }),
            geval("cnf op P-384", Afwijzing.APPSLEUTEL_ONGELDIG, metCredential {
                val p384 = TestApp.sleutelpaar("secp384r1").public as ECPublicKey

                put("cnf", mapOf("jwk" to TestApp.jwk(p384, crv = "P-384", lengte = 48)))
            }),
            geval("geen JWS als certificaat", Afwijzing.CREDENTIAL_ONVOLLEDIG, metCertificaat { "geen-jws" }),
            geval("sleutel in de header", Afwijzing.HEADER_NIET_TOEGESTAAN, metCertificaat {
                vcMetHeader(it, mapOf("alg" to "ES256", "kid" to Opzet.APP_MANAGER_KID, "jwk" to TestApp.jwk(it.appManager.public as ECPublicKey)))
            }),
            geval("crit in de header", Afwijzing.HEADER_NIET_TOEGESTAAN, metCertificaat {
                vcMetHeader(it, mapOf("alg" to "ES256", "kid" to Opzet.APP_MANAGER_KID, "crit" to listOf("exp")))
            }),
            geval("kid als getal", Afwijzing.HEADER_NIET_TOEGESTAAN, metCertificaat { vcMetHeader(it, mapOf("alg" to "ES256", "kid" to 1)) }),
            geval("alg als getal", Afwijzing.HEADER_NIET_TOEGESTAAN, metCertificaat { vcMetHeader(it, mapOf("alg" to 1, "kid" to Opzet.APP_MANAGER_KID)) }),
            geval("alg ES384", Afwijzing.HANDTEKENING_ONGELDIG, metCertificaat { vcMetHeader(it, mapOf("alg" to "ES384", "kid" to Opzet.APP_MANAGER_KID)) }),
            // none, HS256 en ES384 weigert jose4j ook zelf; de constraint op ES256 is verdediging in de diepte.
            geval("alg none", Afwijzing.HANDTEKENING_ONGELDIG, metCertificaat { TestApp.jwsZonderHandtekening(it.vcClaims(), Opzet.APP_MANAGER_KID) }),
            geval("alg HS256 met de publieke sleutel als geheim", Afwijzing.HANDTEKENING_ONGELDIG, metCertificaat {
                TestApp.jwsHs256(it.vcClaims(), it.appManager.public.encoded, Opzet.APP_MANAGER_KID)
            }),
        )

        private fun binding(): List<Arguments> = listOf(
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
            geval("nonce-handtekening met alg none", Afwijzing.NONCEHANDTEKENING_ONGELDIG, metPresentatie { p, c ->
                p["app_nonce_signature"] = TestApp.jwsZonderHandtekening(mapOf("sub" to "challenge_response", "aud" to Opzet.MAGAZIJN_OIN, "nonce" to c.nonce))
            }),
            geval("nonce-handtekening met HS256", Afwijzing.NONCEHANDTEKENING_ONGELDIG, metPresentatie { p, c ->
                val claims = mapOf("sub" to "challenge_response", "aud" to Opzet.MAGAZIJN_OIN, "nonce" to c.nonce)

                p["app_nonce_signature"] = TestApp.jwsHs256(claims, app.public.encoded)
            }),
        )

        private fun presentatie(): List<Arguments> =
            listOf("app_nonce_signature", "certificate_type", "certificate", "session_aes_key").flatMap { veld ->
                listOf(
                    geval("zonder $veld", Afwijzing.PRESENTATIE_ONVOLLEDIG, metPresentatie { p, _ -> p.remove(veld) }),
                    geval("$veld als getal", Afwijzing.PRESENTATIE_ONVOLLEDIG, metPresentatie { p, _ -> p[veld] = 42 }),
                )
            } + listOf(
                geval("AES-sleutel van 16 bytes", Afwijzing.AES_SLEUTEL_ONGELDIG, metPresentatie { p, _ ->
                    p["session_aes_key"] = Base64.getEncoder().encodeToString(TestApp.aesSleutel(16))
                }),
                geval("AES-sleutel geen base64", Afwijzing.AES_SLEUTEL_ONGELDIG, metPresentatie { p, _ -> p["session_aes_key"] = "geen base64!" }),
                geval("onbekend certificaattype", Afwijzing.CERTIFICAATSOORT_ONJUIST, metPresentatie { p, _ -> p["certificate_type"] = "onbekend" }),
                geval("self-signed certificaat met het label van een credential", Afwijzing.CREDENTIAL_ONVOLLEDIG, metPresentatie { p, _ ->
                    p["certificate"] = selfSigned()
                }),
                geval("onbekende nonce", Afwijzing.NONCE_ONBEKEND, Scenario { opzet, verifier, challenge ->
                    verifier.voltooiMetCredential("b25iZWtlbmRlIG5vbmNl", opzet.versleuteld(challenge, opzet.presentatie(challenge)))
                }),
            )

        private fun versleuteling(): List<Arguments> = listOf(
            geval("versleuteld naar een andere sleutel", Afwijzing.PRESENTATIE_ONLEESBAAR, metJwe { o, c ->
                TestApp.jweEcdh(JsonUtil.toJson(o.presentatie(c)), TestApp.sleutelpaar().public as ECPublicKey)
            }),
            geval("enc A128GCM", Afwijzing.PRESENTATIE_ONLEESBAAR, metJwe { o, c ->
                TestApp.jweEcdh(JsonUtil.toJson(o.presentatie(c)), c.tijdelijk, enc = "A128GCM")
            }),
            geval("alg ECDH-ES+A128KW", Afwijzing.PRESENTATIE_ONLEESBAAR, metJwe { o, c ->
                TestApp.jweEcdh(JsonUtil.toJson(o.presentatie(c)), c.tijdelijk, alg = "ECDH-ES+A128KW")
            }),
            geval("alg als getal", Afwijzing.HEADER_NIET_TOEGESTAAN, metJwe { o, c ->
                TestApp.jweEcdh(JsonUtil.toJson(o.presentatie(c)), c.tijdelijk, mapOf("alg" to 1))
            }),
            geval("enc als getal", Afwijzing.HEADER_NIET_TOEGESTAAN, metJwe { o, c ->
                TestApp.jweEcdh(JsonUtil.toJson(o.presentatie(c)), c.tijdelijk, mapOf("enc" to 1))
            }),
            geval("apu als getal", Afwijzing.HEADER_NIET_TOEGESTAAN, metJwe { o, c ->
                TestApp.jweEcdh(JsonUtil.toJson(o.presentatie(c)), c.tijdelijk, mapOf("apu" to 5))
            }),
            geval("jku in de header", Afwijzing.HEADER_NIET_TOEGESTAAN, metJwe { o, c ->
                TestApp.jweEcdh(JsonUtil.toJson(o.presentatie(c)), c.tijdelijk, mapOf("jku" to "https://aanvaller.example"))
            }),
            geval("zonder epk", Afwijzing.TIJDELIJKE_SLEUTEL_ONGELDIG, metJwe { o, c ->
                TestApp.jweEcdh(JsonUtil.toJson(o.presentatie(c)), c.tijdelijk).let { jwe ->
                    val header = TestApp.header(jwe) - "epk"

                    TestApp.b64url.encodeToString(JsonUtil.toJson(header).toByteArray()) + jwe.substring(jwe.indexOf('.'))
                }
            }),
            geval("epk als tekst", Afwijzing.HEADER_NIET_TOEGESTAAN, metJwe { o, c ->
                TestApp.jweEcdh(JsonUtil.toJson(o.presentatie(c)), c.tijdelijk, mapOf("epk" to "x"))
            }),
            geval("epk naast de curve", Afwijzing.TIJDELIJKE_SLEUTEL_ONGELDIG, metJwe { o, c ->
                TestApp.jweEcdh(JsonUtil.toJson(o.presentatie(c)), c.tijdelijk, mapOf("epk" to NAAST_DE_CURVE))
            }),
            geval("epk op P-384", Afwijzing.TIJDELIJKE_SLEUTEL_ONGELDIG, metJwe { o, c ->
                val p384 = TestApp.sleutelpaar("secp384r1").public as ECPublicKey

                TestApp.jweEcdh(JsonUtil.toJson(o.presentatie(c)), c.tijdelijk, mapOf("epk" to TestApp.jwk(p384, crv = "P-384", lengte = 48)))
            }),
            geval("epk met x5c als tekst", Afwijzing.TIJDELIJKE_SLEUTEL_ONGELDIG, metJwe { o, c ->
                TestApp.jweEcdh(JsonUtil.toJson(o.presentatie(c)), c.tijdelijk, epkExtra = mapOf("x5c" to "abc"))
            }),
            geval("epk met x5c null", Afwijzing.TIJDELIJKE_SLEUTEL_ONGELDIG, metJwe { o, c ->
                TestApp.jweEcdh(JsonUtil.toJson(o.presentatie(c)), c.tijdelijk, epkExtra = mapOf("x5c" to null))
            }),
            geval("epk met x5c [null]", Afwijzing.TIJDELIJKE_SLEUTEL_ONGELDIG, metJwe { o, c ->
                TestApp.jweEcdh(JsonUtil.toJson(o.presentatie(c)), c.tijdelijk, epkExtra = mapOf("x5c" to listOf(null)))
            }),
            geval("epk met kid", Afwijzing.TIJDELIJKE_SLEUTEL_ONGELDIG, metJwe { o, c ->
                TestApp.jweEcdh(JsonUtil.toJson(o.presentatie(c)), c.tijdelijk, epkExtra = mapOf("kid" to "1"))
            }),
            geval("geen JWE", Afwijzing.PRESENTATIE_ONLEESBAAR, metJwe { _, _ -> "geen.jwe" }),
            geval("presentatie geen JSON", Afwijzing.PRESENTATIE_ONLEESBAAR, metJwe { _, c -> TestApp.jweEcdh("geen json", c.tijdelijk) }),
            geval("presentatie te groot", Afwijzing.PRESENTATIE_ONLEESBAAR, metJwe { _, _ -> "a".repeat(Verifier.MAX_PRESENTATIE_TEKENS + 1) }),
            geval("zip in de header", Afwijzing.HEADER_NIET_TOEGESTAAN, metJwe { o, c ->
                TestApp.jweEcdh(JsonUtil.toJson(o.presentatie(c)), c.tijdelijk, mapOf("zip" to "DEF"))
            }),
        )

        private fun activeren(): List<Arguments> = listOf(
            geval("self-signed met andere sub", Afwijzing.HANDTEKENING_ONGELDIG, selfSigned { selfSigned(sub = "iets anders") }),
            geval("self-signed getekend met een andere sleutel", Afwijzing.HANDTEKENING_ONGELDIG, selfSigned {
                TestApp.es256(mapOf("sub" to Verifier.CERTIFICAAT_SELF_SIGNED, "cnf" to mapOf("jwk" to TestApp.jwk(app.public as ECPublicKey))), TestApp.sleutelpaar().private)
            }),
            geval("self-signed zonder cnf", Afwijzing.APPSLEUTEL_ONGELDIG, selfSigned { TestApp.es256(mapOf("sub" to Verifier.CERTIFICAAT_SELF_SIGNED), app.private) }),
            geval("self-signed met een credential als certificaat", Afwijzing.HANDTEKENING_ONGELDIG, selfSigned { vc() }),
            geval("self-signed met een nonce getekend door een andere sleutel", Afwijzing.NONCEHANDTEKENING_ONGELDIG, Scenario { opzet, verifier, challenge ->
                val presentatie = opzet.presentatie(challenge, opzet.selfSigned(), Verifier.CERTIFICAAT_SELF_SIGNED).apply {
                    put("app_nonce_signature", opzet.nonceHandtekening(challenge.nonce, sleutel = TestApp.sleutelpaar().private))
                }

                verifier.voltooiSelfSigned(challenge.nonce, opzet.versleuteld(challenge, presentatie), Opzet.CLIENT)
            }),
            geval("magazijn zonder App Managers", Afwijzing.APP_MANAGER_ONBEKEND, Scenario { opzet, _, _ ->
                val zonder = Verifier(
                    Opzet.MAGAZIJN_OIN,
                    VerifierSleutel(Opzet.MAGAZIJN_KID, opzet.magazijnSleutel.private as java.security.interfaces.ECPrivateKey, opzet.magazijnSleutelGeldigTot),
                    AppManagerSleutels.GEEN,
                    Opzet.SCOPE,
                    opzet.opslag,
                    opzet.opslag,
                    opzet.klok,
                )
                val eigen = opzet.challenge(zonder)

                zonder.voltooiMetCredential(eigen.nonce, opzet.versleuteld(eigen, opzet.presentatie(eigen)))
            }),
            geval("credential bij de App Manager", Afwijzing.CERTIFICAATSOORT_ONJUIST, Scenario { opzet, verifier, challenge ->
                verifier.voltooiSelfSigned(challenge.nonce, opzet.versleuteld(challenge, opzet.presentatie(challenge)), Opzet.CLIENT)
            }),
            geval("self-signed bij een magazijn", Afwijzing.CERTIFICAATSOORT_ONJUIST, Scenario { opzet, verifier, challenge ->
                val presentatie = opzet.presentatie(challenge, opzet.selfSigned(), Verifier.CERTIFICAAT_SELF_SIGNED)

                verifier.voltooiMetCredential(challenge.nonce, opzet.versleuteld(challenge, presentatie))
            }),
        )
    }
}
