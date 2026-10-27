package nl.rijksoverheid.moz.fbs.rechtstreeks

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.fail
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import kotlin.random.Random

/**
 * Eigenschap: één verminkt teken in een versleutelde of getekende invoer levert nooit een sessie of
 * een geldig verzoek op, en altijd een [AfgewezenException]; nooit een andere exception die bij de
 * aanroeper als `500` zou eindigen. Faalt een geval, dan staat de verminkte invoer in de melding.
 */
class VerminkingTest {

    /**
     * Alle willekeur uit één zaad: sleutels, nonce, IV en handtekeningen van app en Verifier. Zo is
     * een gefaald geval met dezelfde seed te herhalen.
     */
    private fun opzet(seed: Long): Opzet {
        val bron = java.security.SecureRandom.getInstance("SHA1PRNG").apply { setSeed(seed) }

        TestApp.random = bron

        return Opzet(bron)
    }

    @AfterEach
    fun herstelWillekeur() {
        TestApp.random = java.security.SecureRandom()
    }

    private fun verminkt(tekst: String, random: Random): String {
        // Niet het laatste teken van een base64url-deel: daarin zitten ongebruikte bits, dus een
        // ander teken kan dezelfde bytes opleveren en dan is er niets verminkt.
        val plekken = tekst.indices.filter { tekst[it] != '.' && it + 1 < tekst.length && tekst[it + 1] != '.' }
        val plek = plekken.random(random)
        val alfabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_."
        val vervanging = alfabet.filter { it != tekst[plek] }.random(random)

        return tekst.substring(0, plek) + vervanging + tekst.substring(plek + 1)
    }

    private fun verwachtAfwijzing(invoer: String, actie: (String) -> Unit) {
        try {
            actie(invoer)
            fail<Unit>("verminkte invoer werd aangenomen: $invoer")
        } catch (e: AfgewezenException) {
            // Zo hoort het.
        } catch (e: RuntimeException) {
            fail<Unit>("verminkte invoer gaf ${e.javaClass.name} in plaats van een afwijzing: $invoer", e)
        }
    }

    @ParameterizedTest(name = "seed {0}")
    @ValueSource(longs = [1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16, 17, 18, 19, 20])
    fun `een verminkte versleutelde presentatie wordt afgewezen`(seed: Long) {
        val opzet = opzet(seed)
        val verifier = opzet.verifier()
        val challenge = opzet.challenge(verifier)
        val versleuteld = opzet.versleuteld(challenge, opzet.presentatie(challenge))

        verwachtAfwijzing(verminkt(versleuteld, Random(seed))) { verifier.voltooiMetCredential(challenge.nonce, it) }
    }

    @ParameterizedTest(name = "seed {0}")
    @ValueSource(longs = [1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16, 17, 18, 19, 20])
    fun `een verminkt credential of verminkte nonce-handtekening wordt afgewezen`(seed: Long) {
        val opzet = opzet(seed)
        val verifier = opzet.verifier()
        val challenge = opzet.challenge(verifier)
        val random = Random(seed)
        val presentatie = opzet.presentatie(challenge)
        val veld = if (random.nextBoolean()) "certificate" else "app_nonce_signature"

        presentatie[veld] = verminkt(presentatie[veld] as String, random)

        verwachtAfwijzing(presentatie[veld] as String) {
            verifier.voltooiMetCredential(challenge.nonce, opzet.versleuteld(challenge, presentatie))
        }
    }

    @ParameterizedTest(name = "seed {0}")
    @ValueSource(longs = [1, 2, 3, 4, 5, 6, 7, 8, 9, 10])
    fun `een verminkt self-signed certificaat wordt afgewezen`(seed: Long) {
        val opzet = opzet(seed)
        val verifier = opzet.verifier()
        val challenge = opzet.challenge(verifier)
        val presentatie = opzet.presentatie(challenge, verminkt(opzet.selfSigned(), Random(seed)), Verifier.CERTIFICAAT_SELF_SIGNED)

        verwachtAfwijzing(presentatie["certificate"] as String) {
            verifier.voltooiSelfSigned(challenge.nonce, opzet.versleuteld(challenge, presentatie), Opzet.CLIENT)
        }
    }

    @ParameterizedTest(name = "seed {0}")
    @ValueSource(longs = [1, 2, 3, 4, 5, 6, 7, 8, 9, 10])
    fun `een verminkt verzoek binnen de sessie wordt afgewezen`(seed: Long) {
        val opzet = opzet(seed)
        val verifier = opzet.verifier()
        val (uitgegeven, aes) = opzet.sessie(verifier)

        verwachtAfwijzing(verminkt(TestApp.jweDir("""{"page":0}""", aes), Random(seed))) { verifier.ontsleutel(uitgegeven.sessie, it) }
    }
}
