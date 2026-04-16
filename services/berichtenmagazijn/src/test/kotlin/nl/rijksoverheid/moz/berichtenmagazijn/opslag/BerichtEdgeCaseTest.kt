package nl.rijksoverheid.moz.berichtenmagazijn.opslag

import nl.rijksoverheid.moz.fbs.common.DomainValidationException
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.TestFactory
import java.time.Instant
import java.util.UUID
import kotlin.random.Random

/**
 * Eigenschaps-tests en edge-case tests voor domein-invoer. Niet volledig
 * property-based (dat zou een library als kotest-property vereisen), maar dekt
 * bekende problematische karaktercategorieën: null-bytes, control chars,
 * right-to-left-override, unicode noncharacters, extreem lange strings.
 */
class BerichtEdgeCaseTest {

    private fun bericht(
        afzender: Oin = Oin("00000001003214345000"),
        ontvanger: Identificatienummer = Bsn("999993653"),
        onderwerp: String = "Onderwerp",
        inhoud: String = "Inhoud",
    ) = Bericht(
        berichtId = UUID.randomUUID(),
        afzender = afzender,
        ontvanger = ontvanger,
        onderwerp = onderwerp,
        inhoud = inhoud,
        tijdstip = Instant.now(),
    )

    // Karakters die als "isNotBlank()" doorgaan maar bijzondere implicaties hebben
    // in logging, persistence of downstream systemen.
    private val problematicCharCases = listOf(
        "null-byte" to "onder\u0000werp",
        "bell-control-char" to "onder\u0007werp",
        "right-to-left-override" to "onder\u202Ewerp",
        "unicode-noncharacter-FFFE" to "onder\uFFFEwerp",
        "zero-width-joiner" to "onder\u200Dwerp",
        "line-separator-U+2028" to "onder\u2028werp",
    )

    @TestFactory
    fun `onderwerp met problematische unicode-chars wordt geaccepteerd (niet afgewezen)`() =
        problematicCharCases.map { (naam, waarde) ->
            DynamicTest.dynamicTest("geaccepteerd: $naam") {
                // Deze tests documenteren het huidige gedrag: lage-impact exotic chars
                // worden NIET door Bericht geweerd. Sanering/filtering hoort thuis in
                // een downstream validatielaag of ontvangend systeem, niet hier.
                val b = bericht(onderwerp = waarde)
                assertEquals(waarde, b.onderwerp)
            }
        }

    @TestFactory
    fun `onderwerp boundary-lengtes rond MAX_ONDERWERP_LENGTE`() = listOf(
        Bericht.MAX_ONDERWERP_LENGTE - 1 to true,
        Bericht.MAX_ONDERWERP_LENGTE to true,
        Bericht.MAX_ONDERWERP_LENGTE + 1 to false,
    ).map { (lengte, valid) ->
        DynamicTest.dynamicTest("lengte=$lengte moet ${if (valid) "lukken" else "falen"}") {
            val s = "x".repeat(lengte)
            if (valid) {
                bericht(onderwerp = s)
            } else {
                assertThrows(DomainValidationException::class.java) { bericht(onderwerp = s) }
            }
        }
    }

    @TestFactory
    fun `inhoud boundary-lengtes rond MAX_INHOUD_LENGTE`() = listOf(
        Bericht.MAX_INHOUD_LENGTE to true,
        Bericht.MAX_INHOUD_LENGTE + 1 to false,
    ).map { (lengte, valid) ->
        DynamicTest.dynamicTest("lengte=$lengte moet ${if (valid) "lukken" else "falen"}") {
            val s = "x".repeat(lengte)
            if (valid) {
                bericht(inhoud = s)
            } else {
                assertThrows(DomainValidationException::class.java) { bericht(inhoud = s) }
            }
        }
    }

    @TestFactory
    fun `Bsn elfproef — willekeurige 9-cijfer strings zijn zelden geldig`() =
        listOf(Random(seed = 42L)).map { rng ->
            DynamicTest.dynamicTest("1000 willekeurige 9-cijfer BSNs, alleen geldige elfproef accepteren") {
                var geldig = 0
                var ongeldig = 0
                repeat(1000) {
                    val s = (1..9).map { rng.nextInt(10) }.joinToString("")
                    runCatching { Bsn(s) }
                        .onSuccess { geldig++ }
                        .onFailure { ex ->
                            ongeldig++
                            // Moet specifiek onze domein-exception zijn, niet iets generieks.
                            check(ex is DomainValidationException) { "ex-type: ${ex::class}" }
                        }
                }
                // Ongeveer 1/11e moet elfproef-geldig zijn. Zeer ruime marge.
                check(geldig in 50..200) { "ongeldige ratio: geldig=$geldig ongeldig=$ongeldig" }
            }
        }

    @TestFactory
    fun `Identificatienummer parse — willekeurige numerieke strings van 0 tot 25 cijfers`() =
        listOf(Random(seed = 7L)).map { rng ->
            DynamicTest.dynamicTest("alleen lengtes 8, 9, 20 leveren een type op") {
                repeat(500) {
                    val lengte = rng.nextInt(0, 26)
                    val s = (0 until lengte).map { rng.nextInt(10) }.joinToString("")
                    val result = runCatching { Identificatienummer.parse(s) }
                    when (lengte) {
                        8 -> check(result.isSuccess) { "lengte 8 moet lukken: $s" }
                        9 -> {
                            // 9 cijfers mogelijk via elfproef geldig, mogelijk niet — maar
                            // het type is altijd Bsn (of faalt met DomainValidationException).
                            result.onFailure { check(it is DomainValidationException) }
                        }
                        20 -> check(result.isSuccess) { "lengte 20 moet lukken: $s" }
                        else -> check(result.isFailure) { "lengte $lengte moet falen: $s" }
                    }
                }
            }
        }
}
