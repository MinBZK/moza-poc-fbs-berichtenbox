package nl.rijksoverheid.moz.fbs.magazijnsimulator.beheer

import nl.rijksoverheid.moz.fbs.magazijnsimulator.opslag.Bericht
import nl.rijksoverheid.moz.fbs.magazijnsimulator.opslag.Bijlage
import nl.rijksoverheid.moz.fbs.magazijnsimulator.opslag.Identificatie
import nl.rijksoverheid.moz.fbs.magazijnsimulator.opslag.IdentificatieType
import java.time.Duration
import java.time.Instant
import java.util.UUID

/** Een bericht plus zijn bijlagen, zoals de seed het aanmaakt. */
data class DemoBericht(val bericht: Bericht, val bijlagen: List<Bijlage>)

/**
 * Verzint de berichten waarmee een demo gevuld wordt.
 *
 * **Alles is afgeleid, niets is geloot.** Dezelfde aanroep levert dezelfde berichten op, tot en met
 * de bericht-id's: die komen uit een hash van magazijn en volgnummer. Een demo die je oefent is
 * daarmee dezelfde demo als je hem geeft, en een bevinding is na te spelen.
 *
 * De id's verschillen wél over magazijnen heen. Twee magazijnen mogen in werkelijkheid hetzelfde
 * nummer uitdelen, en de simulator laat dat toe, maar de sessiecache van de uitvraag slaat berichten
 * op zonder magazijn in de sleutel en zou dan het ene bericht door het andere verdringen. Zolang dat
 * gebrek openstaat (MinBZK/MijnOverheidZakelijk#1004) valt een demo daar niet per ongeluk over.
 */
object DemoBerichten {

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

    /** Een minimale maar geldige PDF, zodat een bijlage in een viewer ook echt opent. */
    private val PDF_BYTES = (
        "%PDF-1.4\n1 0 obj<</Type/Catalog/Pages 2 0 R>>endobj\n" +
            "2 0 obj<</Type/Pages/Kids[3 0 R]/Count 1>>endobj\n" +
            "3 0 obj<</Type/Page/Parent 2 0 R/MediaBox[0 0 200 200]>>endobj\n" +
            "trailer<</Root 1 0 R>>\n%%EOF\n"
        ).toByteArray()

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
    ): List<DemoBericht> = (1..aantal).map { volgnummer ->
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
                    inhoud = PDF_BYTES,
                ),
            )
        } else {
            emptyList()
        }

        DemoBericht(bericht.copy(bijlagen = bijlagen.map { it.metadata() }), bijlagen)
    }

    private fun inhoud(magazijnOin: String, volgnummer: Int): String =
        "Dit is demonstratiemateriaal uit het gesimuleerde magazijn $magazijnOin. " +
            "Het is bericht $volgnummer in de reeks en bevat geen echte gegevens."
}
