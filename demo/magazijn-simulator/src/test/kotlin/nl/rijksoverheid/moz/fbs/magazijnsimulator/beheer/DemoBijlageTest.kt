package nl.rijksoverheid.moz.fbs.magazijnsimulator.beheer

import nl.rijksoverheid.moz.fbs.magazijnsimulator.opslag.Identificatie
import nl.rijksoverheid.moz.fbs.magazijnsimulator.opslag.IdentificatieType
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Instant

/**
 * De bijlage wordt in een demo geopend. Een PDF die een viewer weigert of leeg toont, laat de kijker
 * denken dat het downloadpad kapot is terwijl dat juist het onderdeel is dat we laten zien.
 */
class DemoBijlageTest {

    private val ontvanger = Identificatie(IdentificatieType.BSN, "999993653")

    private fun eersteBijlage() = DemoBerichten
        .voor("00000009000000000001", ontvanger, aantal = 4, bijlageElke = 4, nu = Instant.parse("2026-01-01T12:00:00Z"))
        .flatMap { it.bijlagen }
        .first()

    @Test
    fun `de bijlage draagt de standaardtekst`() {
        val pdf = eersteBijlage().inhoud.toString(Charsets.ISO_8859_1)

        assertTrue("(Demonstratiemateriaal) Tj" in pdf, "de kop hoort als tekstopdracht in de stroom te staan")
        assertTrue("Federatief Berichtenstelsel" in pdf, "de toelichting hoort in de stroom te staan")
        assertTrue("geen echte gegevens in" in pdf, "de waarschuwing hoort in de stroom te staan")
    }

    @Test
    fun `de bijlage is een PDF met een kloppende kruisverwijzingstabel`() {
        val bytes = eersteBijlage().inhoud
        val pdf = bytes.toString(Charsets.ISO_8859_1)

        assertTrue(pdf.startsWith("%PDF-"), "een PDF begint met %PDF-")
        assertTrue(pdf.trimEnd().endsWith("%%EOF"), "een PDF eindigt met %%EOF")

        // Elke positie uit de tabel moet op het bijbehorende object wijzen. Wijst er één ernaast,
        // dan weigert een strenge viewer het bestand — en dat merk je pas op een andere machine.
        val tabel = pdf.substringAfter("xref\n").substringBefore("trailer").trim().lines()
        // De eerste twee regels zijn de kop ("0 7") en het verplichte vrije object 0.
        val posities = tabel.drop(2).map { it.take(10).toInt() }

        assertEquals(6, posities.size, "zes objecten, dus zes posities")

        posities.forEachIndexed { index, positie ->
            assertTrue(
                pdf.startsWith("${index + 1} 0 obj", positie),
                "positie $positie hoort naar object ${index + 1} te wijzen",
            )
        }

        val startxref = pdf.substringAfter("startxref\n").substringBefore("\n").trim().toInt()

        assertTrue(pdf.startsWith("xref", startxref), "startxref hoort naar de tabel te wijzen")
    }

    @Test
    fun `de bijlage bevat geen tekens die de opbouw stukmaken`() {
        val bytes = eersteBijlage().inhoud

        // Eén teken is één byte, anders lopen de getelde posities en de werkelijke uiteen.
        assertEquals(bytes.size, bytes.toString(Charsets.ISO_8859_1).length)
        assertTrue(bytes.all { it.toInt() in 0..127 }, "de PDF hoort volledig uit ASCII te bestaan")
    }

    @Test
    fun `elke bijlage draagt dezelfde tekst`() {
        val bijlagen = DemoBerichten
            .voor("00000009000000000002", ontvanger, aantal = 8, bijlageElke = 2, nu = Instant.parse("2026-01-01T12:00:00Z"))
            .flatMap { it.bijlagen }

        assertEquals(4, bijlagen.size)
        assertTrue(bijlagen.all { it.inhoud.contentEquals(bijlagen.first().inhoud) })
    }
}
