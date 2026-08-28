package nl.rijksoverheid.moz.fbs.magazijnsimulator.beheer

import jakarta.enterprise.context.ApplicationScoped
import nl.rijksoverheid.moz.fbs.magazijnsimulator.gedrag.Gedrag
import nl.rijksoverheid.moz.fbs.magazijnsimulator.magazijn.GesimuleerdeMagazijnen
import nl.rijksoverheid.moz.fbs.magazijnsimulator.opslag.BulkBericht
import nl.rijksoverheid.moz.fbs.magazijnsimulator.opslag.BulkOpslag
import nl.rijksoverheid.moz.fbs.magazijnsimulator.opslag.Identificatie
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

        return Gedrag(verzoek.modus, p50, p95, foutkans, foutStatus)
    }

    fun seed(verzoek: SeedVerzoek): SeedUitkomst {
        vereis(verzoek.ontvangers.isNotEmpty()) { "Geef minstens één ontvanger op" }
        vereis(verzoek.berichtenPerMagazijn in 1..SeedVerzoek.MAX_AANTAL) {
            "berichtenPerMagazijn hoort tussen 1 en ${SeedVerzoek.MAX_AANTAL} te liggen " +
                "(kreeg ${verzoek.berichtenPerMagazijn})"
        }
        vereis(verzoek.bijlageElke >= 0) { "bijlageElke mag niet negatief zijn (kreeg ${verzoek.bijlageElke})" }

        // Ontdubbelen: dezelfde ontvanger twee keer in de lijst zou binnen één magazijn twee keer
        // dezelfde bericht-nummers opleveren, en die zijn per magazijn uniek. Een typefout in een
        // JSON-lijst hoort geen serverfout te worden.
        val ontvangers = verzoek.ontvangers.map { Identificatie.uitHeader(it) }.distinct()
        val begin = System.nanoTime()
        val nu = klok.instant()

        // Eén momentopname van de set: `alle()` geeft een levende weergave terug, en dan zou het
        // aantal in het rapport van een ander moment kunnen komen dan de lus die schreef.
        val teVullen = magazijnen.alle().toList()
        var berichten = 0
        var bijlagen = 0
        var overgeslagen = 0

        // Per magazijn één transactie. Alles in één zou bij honderd magazijnen een erg grote worden,
        // en omdat vullen herhaalbaar is, is opnieuw draaien na een mislukking halverwege gewoon de
        // uitweg: wat er al stond wordt overgeslagen.
        teVullen.forEach { magazijn ->
            val teSchrijven = ontvangers.flatMap { ontvanger ->
                DemoBerichten.voor(
                    magazijnOin = magazijn.oin,
                    ontvanger = ontvanger,
                    aantal = verzoek.berichtenPerMagazijn,
                    bijlageElke = verzoek.bijlageElke,
                    nu = nu,
                ).map { BulkBericht(it.bericht, it.bijlagen) }
            }

            val uitkomst = bulk.voegToe(magazijn.dbId, teSchrijven)

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
        magazijnen.herstelGedrag()

        log.infof("Demo geleegd: %d berichten weg, gedrag teruggezet", verwijderd)

        return LeegUitkomst(berichten = verwijderd, magazijnenTeruggezet = magazijnen.alle().size)
    }

    private companion object {
        const val NANOS_PER_MS = 1_000_000

        val FOUT_STATUS_BEREIK = 400..599
    }
}
