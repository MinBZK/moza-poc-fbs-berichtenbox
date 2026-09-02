package nl.rijksoverheid.moz.fbs.magazijnsimulator.beheer

import nl.rijksoverheid.moz.fbs.magazijnsimulator.opslag.Bericht
import nl.rijksoverheid.moz.fbs.magazijnsimulator.opslag.Bijlage
import nl.rijksoverheid.moz.fbs.magazijnsimulator.opslag.BulkBericht
import nl.rijksoverheid.moz.fbs.magazijnsimulator.opslag.Identificatie
import nl.rijksoverheid.moz.fbs.magazijnsimulator.opslag.IdentificatieType
import java.time.Duration
import java.time.Instant
import java.util.Locale
import java.util.UUID

/**
 * Verzint de berichten waarmee een demo gevuld wordt.
 *
 * **Alles is afgeleid, niets is geloot.** Dezelfde aanroep levert dezelfde berichten op, tot en met
 * de bericht-id's: die komen uit een hash van magazijn, ontvanger en volgnummer. Een demo die je
 * oefent is daarmee dezelfde demo als je hem geeft, en een bevinding is na te spelen.
 *
 * De ontvanger hoort in die hash. Zonder hem zouden twee ondernemers binnen hetzelfde magazijn
 * dezelfde nummers krijgen, en die zijn per magazijn uniek — de tweede ondernemer zou er dan gewoon
 * geen berichten bij krijgen.
 *
 * De id's verschillen wél over magazijnen heen. Twee magazijnen mogen in werkelijkheid hetzelfde
 * nummer uitdelen, en de simulator laat dat toe, maar de sessiecache van de uitvraag slaat berichten
 * op zonder magazijn in de sleutel en zou dan het ene bericht door het andere verdringen. Zolang dat
 * gebrek openstaat (MinBZK/MijnOverheidZakelijk#1004) valt een demo daar niet per ongeluk over.
 */
object DemoBerichten {

    /** A4 in punten, met een marge van een inch. */
    private const val PDF_BREEDTE = 595
    private const val PDF_HOOGTE = 842
    private const val PDF_MARGE = 72

    private const val PDF_KOPGROOTTE = 16
    private const val PDF_TEKSTGROOTTE = 11
    private const val PDF_KOPRUIMTE = 40
    private const val PDF_REGELHOOGTE = 18

    private val ONDERWERPEN = listOf(
        "Uw aanvraag is ontvangen",
        "Herinnering: aangifte omzetbelasting",
        "Wijziging in uw inschrijving",
        "Beslissing op uw subsidieaanvraag",
        "Controle aangekondigd",
        "Uw jaaropgave staat klaar",
        "Vergunning verleend",
        "Verzoek om aanvullende gegevens",
    )

    /** Waar ASCII ophoudt; zie de eis op [PDF_REGELS]. */
    private const val EERSTE_NIET_ASCII = 0x80

    private const val PDF_KOP = "Demonstratiemateriaal"

    /**
     * De tekst die in elke demobijlage staat. Eén vaste tekst en geen variatie per bericht: wie hem
     * openslaat moet in één oogopslag zien waar hij naar kijkt, en die vraag is bij elk bericht
     * dezelfde.
     *
     * **Houd deze tekst ASCII.** De pagina gebruikt Helvetica zonder eigen codering, en dan geldt de
     * standaardcodering van PDF: byte 0xE9 is daarin geen `é`. Een accent levert dus stilzwijgend
     * een ander letterteken op, en een gedachtestreepje of euroteken wordt een vraagteken. Een teken
     * buiten het basisvlak (een emoji) is erger: dat telt als twee tekens en één byte, en verschuift
     * daarmee elke positie in de kruisverwijzingstabel — dan opent het bestand niet meer.
     */
    private val PDF_REGELS: List<String> = listOf(
        "Deze bijlage komt uit een gesimuleerd berichtenmagazijn van MijnOverheid",
        "Zakelijk. Ze hoort bij een demo van het Federatief Berichtenstelsel.",
        "",
        "Er staan geen echte gegevens in - niet in dit document, en niet in het",
        "bericht waar het bij hoort. Alles wat u in deze demo ziet is verzonnen.",
    )

    /**
     * De bijlage die aan elk zoveelste demobericht hangt: één A4 met [PDF_KOP] en [PDF_REGELS].
     *
     * Een handvol bytes dat toevallig met `%PDF` begint zou de spec ook halen, maar in een demo
     * wordt zo'n bijlage geopend. Een viewer die een leeg vel toont, laat de kijker denken dat het
     * downloadpad kapot is terwijl dat juist het onderdeel is dat we laten zien.
     */
    private val PDF_BYTES = demoPdf()


    /**
     * De berichten voor één magazijn en één ontvanger.
     *
     * [bijlageElke] bepaalt hoe vaak er een bijlage bij zit. Zonder bijlagen blijft het
     * download-pad van de Berichtenbox in een demo ongebruikt, en dat was juist een van de redenen
     * om geen antwoordmachine meer te gebruiken.
     */
    fun voor(
        magazijnOin: String,
        ontvanger: Identificatie,
        aantal: Int,
        bijlageElke: Int,
        nu: Instant,
    ): List<BulkBericht> = (1..aantal).map { volgnummer ->
        val sleutel = "$magazijnOin:${ontvanger.type}:${ontvanger.waarde}:$volgnummer"
        // Nieuwste bovenaan: het eerste bericht is het oudste, dus de tijdstippen lopen terug.
        val ontvangen = nu.minus(Duration.ofHours((aantal - volgnummer).toLong()))
        val bericht = Bericht(
            berichtId = UUID.nameUUIDFromBytes(sleutel.toByteArray()),
            afzender = Identificatie(IdentificatieType.OIN, magazijnOin),
            ontvanger = ontvanger,
            onderwerp = "${ONDERWERPEN[volgnummer % ONDERWERPEN.size]} ($volgnummer)",
            inhoud = inhoud(magazijnOin, volgnummer),
            tijdstipOntvangst = ontvangen,
            publicatietijdstip = ontvangen,
        )

        val bijlagen = if (bijlageElke > 0 && volgnummer % bijlageElke == 0) {
            listOf(
                Bijlage(
                    bijlageId = UUID.nameUUIDFromBytes("$sleutel:bijlage".toByteArray()),
                    naam = "bijlage-$volgnummer.pdf",
                    mimeType = "application/pdf",
                    // Elke demobijlage deelt deze bytes. Dat mag omdat niets ze muteert — de
                    // opslag schrijft ze weg en de download leest ze — en kopiëren zou bij honderd
                    // magazijnen met twintig berichten elk hetzelfde document duizenden keren in het
                    // geheugen zetten.
                    inhoud = PDF_BYTES,
                ),
            )
        } else {
            emptyList()
        }

        // De bijlagen gaan apart mee: de bulk-opslag schrijft kolommen en kijkt niet naar
        // `Bericht.bijlagen`, dus ze daar óók in zetten zou dood werk zijn.
        BulkBericht(bericht, bijlagen)
    }

    private fun inhoud(magazijnOin: String, volgnummer: Int): String =
        "Dit is demonstratiemateriaal uit het gesimuleerde magazijn $magazijnOin. " +
            "Het is bericht $volgnummer in de reeks en bevat geen echte gegevens."

    /**
     * Stelt de PDF samen: catalogus, paginaboom, één pagina, twee standaardlettertypen en de
     * tekststroom, met een kruisverwijzingstabel die naar de bytepositie van elk object wijst.
     *
     * Die tabel is het werk. Zonder correcte posities openen coulante viewers het bestand nog wel
     * door de objecten zelf te zoeken, maar strengere weigeren het — en dan gaat de bijlage stuk op
     * de machine van iemand anders dan die hem bouwde.
     *
     * Helvetica en Helvetica-Bold hoeven niet ingesloten te worden: elke viewer heeft de veertien
     * standaardlettertypen. Dat scheelt een ingesloten font van tonnen bytes per bijlage.
     */
    private fun demoPdf(): ByteArray {
        // De ASCII-eis hierboven is een invariant en geen verzoek: één accent levert een bestand op
        // dat niet meer opent, en dat merkt niemand tot iemand tijdens een demo op de bijlage klikt.
        // Hier valt het bij het starten om, wat een typefout van een minuut maakt.
        (PDF_REGELS + PDF_KOP).forEach { regel ->
            check(regel.all { teken -> teken.code < EERSTE_NIET_ASCII }) {
                "PDF-tekst hoort ASCII te zijn; deze regel is het niet: $regel"
            }
        }

        val stroom = tekststroom()
        val objecten = listOf(
            "<</Type/Catalog/Pages 2 0 R>>",
            "<</Type/Pages/Kids[3 0 R]/Count 1>>",
            "<</Type/Page/Parent 2 0 R/MediaBox[0 0 $PDF_BREEDTE $PDF_HOOGTE]" +
                "/Resources<</Font<</F1 4 0 R/F2 5 0 R>>>>/Contents 6 0 R>>",
            "<</Type/Font/Subtype/Type1/BaseFont/Helvetica>>",
            "<</Type/Font/Subtype/Type1/BaseFont/Helvetica-Bold>>",
            "<</Length ${stroom.length}>>stream\n$stroom\nendstream",
        )

        val pdf = StringBuilder("%PDF-1.4\n")

        val posities = objecten.mapIndexed { index, definitie ->
            val positie = pdf.length

            pdf.append(index + 1).append(" 0 obj\n").append(definitie).append("\nendobj\n")

            positie
        }

        val kruisverwijzing = pdf.length

        pdf.append("xref\n0 ${objecten.size + 1}\n0000000000 65535 f \n")
        // Locale.ROOT is hier geen formaliteit: `format` volgt anders de standaard-locale, en een
        // machine die op Thaise of Arabische cijfers staat schrijft tekens buiten Latin-1. Die
        // worden bij het coderen `?`, de regels houden hun breedte, en het bestand oogt gaaf terwijl
        // geen enkele positie meer ergens naar wijst.
        posities.forEach { pdf.append(String.format(Locale.ROOT, "%010d 00000 n \n", it)) }
        pdf.append("trailer<</Size ${objecten.size + 1}/Root 1 0 R>>\n")
        pdf.append("startxref\n$kruisverwijzing\n%%EOF\n")

        // Latin-1 en niet UTF-8: één teken is dan één byte, en alleen zo kloppen de posities die
        // hierboven op tekenlengte zijn geteld. Alles wat hier ingaat is ASCII, dus er gaat niets
        // verloren — zie de eis bij PDF_REGELS.
        return pdf.toString().toByteArray(Charsets.ISO_8859_1)
    }

    /** De tekst als PDF-tekenopdrachten: de kop vet, daaronder de regels op vaste regelafstand. */
    private fun tekststroom(): String {
        val opdrachten = StringBuilder()
        val bovenkant = PDF_HOOGTE - PDF_MARGE

        opdrachten.append(regel("F2", PDF_KOPGROOTTE, bovenkant, PDF_KOP))

        PDF_REGELS.forEachIndexed { index, tekst ->
            if (tekst.isNotEmpty()) {
                val hoogte = bovenkant - PDF_KOPRUIMTE - index * PDF_REGELHOOGTE

                opdrachten.append(regel("F1", PDF_TEKSTGROOTTE, hoogte, tekst))
            }
        }

        return opdrachten.toString()
    }

    private fun regel(lettertype: String, grootte: Int, hoogte: Int, tekst: String): String =
        "BT /$lettertype $grootte Tf 1 0 0 1 $PDF_MARGE $hoogte Tm (${ontsnapt(tekst)}) Tj ET\n"

    /**
     * Een haakje of backslash in de tekst zou de string in de PDF vroegtijdig sluiten en de rest van
     * het bestand tot onzin maken.
     */
    private fun ontsnapt(tekst: String): String =
        tekst.replace("\\", "\\\\").replace("(", "\\(").replace(")", "\\)")
}
