package nl.rijksoverheid.moz.fbs.demopersonas

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Over de ingerichte lijst zelf, niet over de logica die hem leest. Een opt-in van nul magazijnen
 * is een geldige inrichting — Grootbedrijf en Landelijk Concern halen op bij de gesimuleerde
 * magazijnen — dus een `magazijnen`-regel die wegvalt of verkeerd gespeld raakt, is aan het gedrag
 * niet te onderscheiden van die bedoelde nul: de boot slaagt, de generator slaat de persona over,
 * en tijdens de demo blijft één berichtenbox leeg zonder dat iets faalt.
 *
 * Daarom staat hier wát er ingericht hoort te zijn. De lijst hoort mee te bewegen als de demo
 * verandert; rood worden is dan het punt, niet de hinder.
 */
class IngerichtePersonasTest {

    private val ingericht = TestPersonas.uitConfiguratie()

    @Test
    fun `deze persona's krijgen berichten aangeleverd bij de echte magazijnen`() {
        assertEquals(
            listOf("bakkerij", "proeftuin-een", "proeftuin-twee", "proeftuin-drie", "vandijk", "pietersen"),
            ingericht.metMagazijnen().map { it.id },
        )
    }

    @Test
    fun `deze persona's halen alleen op, en horen dus geen opt-in te hebben`() {
        assertEquals(
            listOf("grootbedrijf", "concern"),
            ingericht.alle().filter { it.magazijnen.isEmpty() }.map { it.id },
        )
    }

    @Test
    fun `elke opt-in wijst naar een magazijn waar de demo daadwerkelijk voor aanlevert`() {
        // Een OIN dat hier niet in staat levert geen foutmelding op — deze dienst kent de
        // magazijn-inrichting niet — maar wel een aanlevering die nergens aankomt.
        val onbekend = ingericht.alle()
            .flatMap { persona -> persona.magazijnen.map { persona.id to it } }
            .filterNot { (_, oin) -> oin in TestPersonas.MAGAZIJNEN }

        assertTrue(onbekend.isEmpty(), "opt-in naar een magazijn buiten de demo: $onbekend")
    }
}
