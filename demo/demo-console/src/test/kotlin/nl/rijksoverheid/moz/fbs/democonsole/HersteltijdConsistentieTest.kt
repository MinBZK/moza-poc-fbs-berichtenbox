package nl.rijksoverheid.moz.fbs.democonsole

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File
import java.util.Properties

/**
 * [HERSTELTIJD_MELDING] zegt "na drie storingen" en "een halve minuut". Die twee getallen zijn geen
 * stijlfiguur: ze staan als `drempel` en `open-seconds` in de uitvraag, waar deze module geen
 * afhankelijkheid op heeft.
 *
 * Faalscenario zonder deze test: iemand zet `open-seconds` op 120 — één regel in een andere service.
 * Het paneel blijft dan na élke reset, elk herstel en elk legen melden dat wachten volstaat en dat
 * het een halve minuut duurt. De demonstrateur wacht dertig seconden, ziet de organisatie nog steeds
 * als "tijdelijk niet beschikbaar", en gaat zoeken naar een magazijn dat niet stuk is — precies wat
 * deze melding moet voorkomen.
 *
 * De tekst wordt bewust niet uit die configuratie opgebouwd: dat maakt een user-facing zin
 * afhankelijk van twee properties uit een andere service. Deze test wijst de schrijver naar de zin
 * die dan bijgewerkt moet worden.
 */
class HersteltijdConsistentieTest {

    private val uitvraag = Properties().apply {
        val bestand = File(WORTEL, "services/berichtenuitvraag/src/main/resources/application.properties")

        assertTrue(bestand.isFile, "uitvraag-configuratie niet gevonden op ${bestand.absolutePath}")
        bestand.inputStream().use { load(it) }
    }

    @Test
    fun `de melding noemt hetzelfde aantal storingen als de uitvraag hanteert`() {
        assertEquals("3", waarde("berichtensessiecache.magazijn-circuit.drempel"))
        assertTrue(
            "drie storingen" in HERSTELTIJD_MELDING,
            "de drempel is 3; werk de melding bij als dat verandert",
        )
    }

    @Test
    fun `de melding noemt hetzelfde venster als de uitvraag hanteert`() {
        assertEquals("30", waarde("berichtensessiecache.magazijn-circuit.open-seconds"))
        assertTrue(
            "halve minuut" in HERSTELTIJD_MELDING,
            "het venster is 30 seconden; werk de melding bij als dat verandert",
        )
    }

    private fun waarde(sleutel: String): String =
        uitvraag.getProperty(sleutel) ?: throw AssertionError("$sleutel ontbreekt in de uitvraag-configuratie")

    private companion object {
        /** De module draait vanuit `demo/demo-console`; de repository-wortel ligt twee mappen hoger. */
        val WORTEL: File = File("../..").canonicalFile
    }
}
