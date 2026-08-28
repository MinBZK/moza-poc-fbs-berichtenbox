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
        val standaard = Gedrag.standaardVoor(verzoek.modus)
        val gedrag = Gedrag(
            modus = verzoek.modus,
            latencyP50Ms = verzoek.latencyP50Ms ?: standaard.latencyP50Ms,
            latencyP95Ms = verzoek.latencyP95Ms ?: standaard.latencyP95Ms,
            foutkans = verzoek.foutkans ?: standaard.foutkans,
            foutStatus = verzoek.foutStatus ?: standaard.foutStatus,
        )

        if (!magazijnen.stelGedragBij(oin, gedrag)) return null

        log.infof("Gedrag van magazijn %s gezet op %s", oin, gedrag.modus)

        return overzicht().firstOrNull { it.oin == oin }
    }

    fun seed(verzoek: SeedVerzoek): SeedUitkomst {
        vereis(verzoek.ontvangers.isNotEmpty()) { "Geef minstens één ontvanger op" }
        vereis(verzoek.berichtenPerMagazijn in 1..SeedVerzoek.MAX_AANTAL) {
            "berichtenPerMagazijn hoort tussen 1 en ${SeedVerzoek.MAX_AANTAL} te liggen " +
                "(kreeg ${verzoek.berichtenPerMagazijn})"
        }
        vereis(verzoek.bijlageElke >= 0) { "bijlageElke mag niet negatief zijn (kreeg ${verzoek.bijlageElke})" }

        val ontvangers = verzoek.ontvangers.map { Identificatie.uitHeader(it) }
        val begin = System.nanoTime()
        val nu = klok.instant()
        var berichten = 0
        var bijlagen = 0

        // Per magazijn één transactie. Alles in één transactie zou bij honderd magazijnen een erg
        // grote worden, en per magazijn afronden betekent dat een mislukking halverwege een
        // begrijpelijke toestand achterlaat in plaats van een halve demo.
        magazijnen.alle().forEach { magazijn ->
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
        }

        val duurMs = (System.nanoTime() - begin) / NANOS_PER_MS

        log.infof(
            "Demo gevuld: %d berichten en %d bijlagen over %d magazijnen in %d ms",
            berichten,
            bijlagen,
            magazijnen.alle().size,
            duurMs,
        )

        return SeedUitkomst(
            magazijnen = magazijnen.alle().size,
            ontvangers = ontvangers.size,
            berichten = berichten,
            bijlagen = bijlagen,
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
    }
}
