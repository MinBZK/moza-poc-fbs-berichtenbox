package nl.rijksoverheid.moz.fbs.magazijnsimulator.beheer

import jakarta.enterprise.context.ApplicationScoped
import nl.rijksoverheid.moz.fbs.magazijnsimulator.gedrag.Gedrag
import nl.rijksoverheid.moz.fbs.magazijnsimulator.magazijn.GesimuleerdMagazijn
import nl.rijksoverheid.moz.fbs.magazijnsimulator.magazijn.GesimuleerdeMagazijnen
import nl.rijksoverheid.moz.fbs.magazijnsimulator.opslag.BulkBericht
import nl.rijksoverheid.moz.fbs.magazijnsimulator.opslag.BulkOpslag
import nl.rijksoverheid.moz.fbs.magazijnsimulator.opslag.Identificatie
import nl.rijksoverheid.moz.fbs.magazijnsimulator.opslag.IdentificatieType
import nl.rijksoverheid.moz.fbs.magazijnsimulator.opslag.vereis
import org.jboss.logging.Logger
import java.time.Clock

/** Voert de drie beheer-handelingen uit: vullen, terugzetten en gedrag bijstellen. */
@ApplicationScoped
class BeheerService(
    private val magazijnen: GesimuleerdeMagazijnen,
    private val bulk: BulkOpslag,
    private val klok: Clock,
) {

    private val log = Logger.getLogger(BeheerService::class.java)

    fun overzicht(): List<MagazijnOverzicht> = magazijnen.alle()
        .sortedBy { it.oin }
        .map { magazijn ->
            MagazijnOverzicht(
                oin = magazijn.oin,
                naam = magazijn.naam,
                modus = magazijn.gedrag.modus,
                latencyP50Ms = magazijn.gedrag.latencyP50Ms,
                latencyP95Ms = magazijn.gedrag.latencyP95Ms,
                foutkans = magazijn.gedrag.foutkans,
                foutStatus = magazijn.gedrag.foutStatus,
            )
        }

    /**
     * Stelt het gedrag bij. Wat het verzoek weglaat, komt uit de standaardwaardes van de gevraagde
     * modus — zo hoeft wie alleen "zet hem op stuk" bedoelt, geen latency en foutkans mee te sturen.
     */
    fun zetGedrag(oin: String, verzoek: GedragVerzoek): MagazijnOverzicht? {
        val gedrag = bouwGedrag(verzoek)

        if (!magazijnen.stelGedragBij(oin, gedrag)) return null

        log.infof("Gedrag van magazijn %s gezet op %s", oin, gedrag.modus)

        return overzicht().firstOrNull { it.oin == oin }
    }

    /**
     * Stelt het gedrag van een reeks magazijnen bij. Onbekende OIN's worden overgeslagen en
     * teruggemeld in plaats van de hele aanroep te laten falen: bij een lijst van honderd is
     * doorgaan-en-zeggen-wat-er-niet-kon bruikbaarder dan stoppen bij de eerste.
     *
     * De waardes worden wél allemaal vooraf getoetst. Een ongeldige regel halverwege zou anders een
     * half doorgevoerde lijst achterlaten, en dan weet de aanroeper niet meer wat er staat.
     */
    fun zetGedragInBulk(verzoek: BulkGedragVerzoek): BulkGedragUitkomst {
        // Twee regels voor dezelfde OIN spreken elkaar tegen: de laatste zou winnen en het antwoord
        // zou er twee tellen. Anders dan bij een dubbele ontvanger in de seed — die levert exact
        // dezelfde berichten op en is dus onschadelijk — valt hier niet te raden wat bedoeld is.
        val dubbel = verzoek.aanpassingen.groupingBy { it.oin }.eachCount().filterValues { it > 1 }.keys

        vereis(dubbel.isEmpty()) { "Elke OIN mag maar één keer in de lijst staan; dubbel: ${dubbel.sorted()}" }

        val gewenst = verzoek.aanpassingen.map { aanpassing -> aanpassing.oin to bouwGedrag(aanpassing.gedrag()) }

        val onbekend = mutableListOf<String>()
        var aangepast = 0

        gewenst.forEach { (oin, gedrag) ->
            if (magazijnen.stelGedragBij(oin, gedrag)) aangepast++ else onbekend += oin
        }

        log.infof("Gedrag van %d magazijn(en) bijgesteld; %d onbekend", aangepast, onbekend.size)

        return BulkGedragUitkomst(aangepast = aangepast, onbekend = onbekend)
    }

    /**
     * Bouwt het gevraagde gedrag, met de standaardwaardes van de modus voor wat het verzoek weglaat.
     *
     * De grenzen worden hier als invoer getoetst en niet aan [Gedrag] overgelaten. Dat type bewaakt
     * zichzelf met `require`, en dat is de goede keuze voor interne consistentie — maar op deze weg
     * komt de waarde uit een JSON-body, en dan hoort een negatieve latency of een foutkans van twee
     * een 400 te zijn die zegt wat er mis is, geen 500 die de aanroeper naar een niet-bestaande
     * supportafdeling stuurt.
     */
    private fun bouwGedrag(verzoek: GedragVerzoek): Gedrag {
        val standaard = Gedrag.standaardVoor(verzoek.modus)
        val p50 = verzoek.latencyP50Ms ?: standaard.latencyP50Ms
        val p95 = verzoek.latencyP95Ms ?: standaard.latencyP95Ms
        val foutkans = verzoek.foutkans ?: standaard.foutkans
        val foutStatus = verzoek.foutStatus ?: standaard.foutStatus

        vereis(p50 >= 0) { "latencyP50Ms mag niet negatief zijn (kreeg $p50)" }
        vereis(p95 >= p50) { "latencyP95Ms ($p95) hoort niet onder latencyP50Ms ($p50) te liggen" }
        vereis(foutkans in 0.0..1.0) { "foutkans hoort tussen 0 en 1 te liggen (kreeg $foutkans)" }
        vereis(foutStatus in FOUT_STATUS_BEREIK) {
            "foutStatus hoort een HTTP-foutcode te zijn, tussen ${FOUT_STATUS_BEREIK.first} en " +
                "${FOUT_STATUS_BEREIK.last} (kreeg $foutStatus)"
        }

        // De getallen moeten ook bij de gevraagde modus passen. Zonder deze toets is een magazijn
        // in te stellen dat volgens het overzicht onbereikbaar is en ondertussen gewoon antwoordt —
        // en dan wijst een demo de schuld toe aan het stelsel in plaats van aan de knop.
        val bezwaar = Gedrag.bezwaarTegenModus(verzoek.modus, p50, foutkans, foutStatus)

        vereis(bezwaar == null) { "Dit past niet bij modus ${verzoek.modus}: ${bezwaar.orEmpty()}" }

        return Gedrag(verzoek.modus, p50, p95, foutkans, foutStatus)
    }

    /**
     * Toetst het verzoek vóórdat er ook maar iets geschreven is, en levert de ontdubbelde ontvangers.
     *
     * Ontdubbelen: dezelfde ontvanger twee keer in de lijst zou binnen één magazijn twee keer
     * dezelfde bericht-nummers opleveren, en die zijn per magazijn uniek. Een typefout in een
     * JSON-lijst hoort geen serverfout te worden.
     *
     * De botsingscontrole hoort hier en niet in de schrijflus: een bericht van een magazijn aan
     * zichzelf schendt een domein-invariant die pas tijdens het schrijven toeslaat, en dan staan de
     * magazijnen die eerder aan de beurt waren al vol terwijl de aanroeper een 400 krijgt die daar
     * niets over zegt.
     */
    private fun valideer(verzoek: SeedVerzoek, teVullen: List<GesimuleerdMagazijn>): List<Identificatie> {
        vereis(verzoek.ontvangers.isNotEmpty()) { "Geef minstens één ontvanger op" }
        vereis(verzoek.berichtenPerMagazijn in 1..SeedVerzoek.MAX_AANTAL) {
            "berichtenPerMagazijn hoort tussen 1 en ${SeedVerzoek.MAX_AANTAL} te liggen " +
                "(kreeg ${verzoek.berichtenPerMagazijn})"
        }
        vereis(verzoek.bijlageElke >= 0) { "bijlageElke mag niet negatief zijn (kreeg ${verzoek.bijlageElke})" }

        val ontvangers = verzoek.ontvangers.map { Identificatie.uitHeader(it) }.distinct()
        val eigenOins = teVullen.map { it.oin }.toSet()
        val botsend = ontvangers.filter { it.type == IdentificatieType.OIN && it.waarde in eigenOins }

        vereis(botsend.isEmpty()) {
            "Een magazijn kan geen bericht aan zichzelf sturen; deze ontvangers zijn zelf een " +
                "gesimuleerd magazijn: ${botsend.map { it.waarde }.sorted()}"
        }

        return ontvangers
    }

    fun seed(verzoek: SeedVerzoek): SeedUitkomst {
        val begin = System.nanoTime()
        val nu = klok.instant()
        val teVullen = magazijnen.alle()
        val ontvangers = valideer(verzoek, teVullen)

        var berichten = 0
        var bijlagen = 0
        var overgeslagen = 0

        // Per magazijn één transactie. Alles in één zou bij honderd magazijnen een erg grote worden,
        // en omdat vullen herhaalbaar is, is opnieuw draaien na een mislukking halverwege gewoon de
        // uitweg: wat er al stond wordt overgeslagen.
        teVullen.forEachIndexed { index, magazijn ->
            val teSchrijven = ontvangers.flatMap { ontvanger ->
                DemoBerichten.voor(
                    magazijnOin = magazijn.oin,
                    ontvanger = ontvanger,
                    aantal = verzoek.berichtenPerMagazijn,
                    bijlageElke = verzoek.bijlageElke,
                    nu = nu,
                )
            }

            val uitkomst = try {
                bulk.voegToe(magazijn.dbId, teSchrijven)
            } catch (ex: RuntimeException) {
                // Welk magazijn en hoever: zonder die twee is een half gevulde demo niet te
                // onderscheiden van een lege, en dan weet niemand of opnieuw draaien veilig is.
                // (Dat is het — wat er staat wordt overgeslagen.)
                log.errorf(
                    ex,
                    "Vullen faalde op magazijn %s (%d van %d); %d berichten stonden er al",
                    magazijn.oin,
                    index + 1,
                    teVullen.size,
                    berichten,
                )

                throw ex
            }

            berichten += uitkomst.berichten
            bijlagen += uitkomst.bijlagen
            overgeslagen += uitkomst.overgeslagen
        }

        val duurMs = (System.nanoTime() - begin) / NANOS_PER_MS

        log.infof(
            "Demo gevuld: %d berichten en %d bijlagen over %d magazijnen in %d ms (%d stonden er al)",
            berichten,
            bijlagen,
            teVullen.size,
            duurMs,
            overgeslagen,
        )

        return SeedUitkomst(
            magazijnen = teVullen.size,
            ontvangers = ontvangers.size,
            berichten = berichten,
            bijlagen = bijlagen,
            overgeslagen = overgeslagen,
            duurMs = duurMs,
        )
    }

    fun legen(): LeegUitkomst {
        val verwijderd = bulk.leegAlleBerichten()

        // Ook het gedrag terug. Een magazijn dat tijdens de vorige demo op storing is gezet, zou er
        // anders de volgende keer nog zo bij staan.
        try {
            magazijnen.herstelGedrag()
        } catch (ex: RuntimeException) {
            // De berichten zijn hier al weg. Wie alleen de 500 ziet, denkt dat er niets gebeurd is
            // en gaat op zoek naar berichten die er niet meer zijn.
            log.errorf(ex, "Berichten zijn geleegd (%d), maar het gedrag terugzetten faalde", verwijderd)

            throw ex
        }

        log.infof("Demo geleegd: %d berichten weg, gedrag teruggezet", verwijderd)

        return LeegUitkomst(berichten = verwijderd, magazijnenTeruggezet = magazijnen.alle().size)
    }

    private companion object {
        const val NANOS_PER_MS = 1_000_000

        val FOUT_STATUS_BEREIK = 400..599
    }
}
