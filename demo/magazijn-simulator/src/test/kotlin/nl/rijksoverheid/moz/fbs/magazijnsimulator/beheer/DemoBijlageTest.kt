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
        // Niet trimmen: elke regel eindigt op een spatie die meetelt in de vaste breedte hieronder.
        // De laatste `lines()`-waarde is leeg (de regelovergang vóór `trailer`) en valt af, net als
        // de kop ("0 7"); wat overblijft zijn het verplichte vrije object 0 en de zes objecten.
        val ingangen = pdf.substringAfter("xref\n").substringBefore("trailer").lines().dropLast(1)
        val posities = ingangen.drop(2).map { it.take(10).toInt() }

        assertEquals(6, posities.size, "zes objecten, dus zes posities")

        posities.forEachIndexed { index, positie ->
            assertTrue(
                pdf.startsWith("${index + 1} 0 obj", positie),
                "positie $positie hoort naar object ${index + 1} te wijzen",
            )
        }

        // Elke regel in de tabel moet exact twintig bytes zijn, de regelovergang meegerekend: een
        // parser mag rechtstreeks naar de n-de regel springen. Eén spatie minder en het bestand is
        // stuk voor precies die parsers, terwijl de posities zelf nog kloppen.
        ingangen.drop(1).forEach {
            assertEquals(20, it.length + 1, "xref-regel '$it' hoort 20 bytes te zijn, regelovergang meegerekend")
        }

        val startxref = pdf.substringAfter("startxref\n").substringBefore("\n").trim().toInt()

        assertTrue(pdf.startsWith("xref", startxref), "startxref hoort naar de tabel te wijzen")
    }

    @Test
    fun `de opgegeven lengte dekt de tekststroom precies`() {
        val pdf = eersteBijlage().inhoud.toString(Charsets.ISO_8859_1)
        val opgegeven = pdf.substringAfter("<</Length ").substringBefore(">>").toInt()
        val begin = pdf.indexOf("stream\n") + "stream\n".length

        // Een verkeerde lengte laat een parser de stroom afkappen of te ver doorlezen. Dat het nu
        // klopt hangt aan de regelovergang vlak vóór `endstream`, die volgens de spec niet meetelt;
        // wie de opbouw van de stroom aanpast, verschuift dat ongemerkt.
        assertEquals(opgegeven, pdf.indexOf("\nendstream") - begin)
    }

    @Test
    fun `de bijlage bevat geen tekens die de opbouw stukmaken`() {
        // De posities in de tabel worden op tekenlengte geteld en daarna als bytes weggeschreven.
        // Zolang alles ASCII is vallen die twee samen; een accent geeft een ander letterteken, en
        // een teken buiten het basisvlak verschuift de posities. Een cijfer uit een ander schrift —
        // wat een verkeerd opgemaakte positie zou opleveren — komt er als vraagteken uit en valt
        // hier dus ook door de mand.
        assertTrue(
            eersteBijlage().inhoud.all { it.toInt() in 0..127 },
            "de PDF hoort volledig uit ASCII te bestaan",
        )
    }

    @Test
    fun `elke bijlage draagt dezelfde tekst`() {
        val bijlagen = DemoBerichten
            .voor("00000009000000000002", ontvanger, aantal = 8, bijlageElke = 2, nu = Instant.parse("2026-01-01T12:00:00Z"))
            .flatMap { it.bijlagen }

        assertEquals(4, bijlagen.size)

        // Op de tekst zelf toetsen en niet de arrays onderling vergelijken: ze delen één instantie,
        // dus zo'n vergelijking is altijd waar en zou een variant per bericht niet opmerken.
        assertTrue(
            bijlagen.all { "(Demonstratiemateriaal) Tj" in it.inhoud.toString(Charsets.ISO_8859_1) },
            "elke bijlage hoort dezelfde standaardtekst te dragen",
        )
    }
}
