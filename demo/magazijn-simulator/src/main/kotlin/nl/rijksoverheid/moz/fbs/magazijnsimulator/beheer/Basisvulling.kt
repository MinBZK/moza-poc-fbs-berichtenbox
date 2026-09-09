package nl.rijksoverheid.moz.fbs.magazijnsimulator.beheer

import io.quarkus.runtime.StartupEvent
import jakarta.annotation.Priority
import jakarta.enterprise.context.ApplicationScoped
import jakarta.enterprise.event.Observes
import jakarta.interceptor.Interceptor
import nl.rijksoverheid.moz.fbs.magazijnsimulator.opslag.BulkOpslag
import org.jboss.logging.Logger

/**
 * Zet bij het opstarten post klaar wanneer de opslag nog leeg is.
 *
 * Een verse omgeving — een preview, of een deployment waarvan de database opnieuw is aangemaakt —
 * stond tot nu toe met alle magazijnen op nul berichten terwijl elk magazijn keurig antwoordde. Dat
 * is van een kapotte keten niet te onderscheiden, en het viel pas op wanneer iemand de omgeving
 * voor een demonstratie opende. Vullen bij het starten haalt die handeling weg bij de mens die er
 * op dat moment niet aan denkt.
 *
 * **"Leeg" is de hele opslag, niet per magazijn.** Wie tijdens een demo bewust leegt en daarna een
 * herstart krijgt, heeft zijn lege omgeving weer vol. Dat is de prijs voor een omgeving die
 * zichzelf herstelt; het alternatief is een "ooit gevuld"-vlag in de database, en dat is meer
 * machinerie dan het zeldzame geval rechtvaardigt.
 */
@ApplicationScoped
class Basisvulling(
    private val config: BasisvullingConfig,
    private val bulk: BulkOpslag,
    private val beheer: BeheerService,
) {

    private val log = Logger.getLogger(Basisvulling::class.java)

    /**
     * Ná de magazijn-rijen: die worden door een andere waarnemer van hetzelfde event uit de
     * configuratie bijgewerkt, en zonder die rijen valt er niets te vullen. De prioriteit legt die
     * volgorde vast in plaats van hem aan de toevallige ontdekkingsvolgorde van CDI over te laten.
     */
    fun bijOpstart(@Observes @Priority(NA_DE_MAGAZIJNEN) startup: StartupEvent) {
        vulIndienLeeg()
    }

    /**
     * Vult wanneer de opslag leeg is, en zegt wat het geworden is.
     *
     * De uitkomst is er voor de aanroeper die het verschil moet kunnen zien: "niets gedaan omdat het
     * niet gevraagd is" en "niets gedaan omdat het al gevuld was" zijn twee verschillende
     * antwoorden, en alleen de tweede betekent dat er post klaarstaat.
     */
    fun vulIndienLeeg(): Uitkomst {
        val ontvangers = config.ontvangers().orElse(emptyList())

        if (ontvangers.isEmpty()) {
            log.info("Geen basisvulling geconfigureerd; de opslag blijft zoals hij is")

            return Uitkomst.NIET_GECONFIGUREERD
        }

        if (bulk.ergensBerichten()) {
            log.info("Basisvulling overgeslagen: er staan al berichten in de opslag")

            return Uitkomst.AL_GEVULD
        }

        val uitkomst = beheer.seed(
            SeedVerzoek(
                ontvangers = ontvangers,
                berichtenPerMagazijn = config.berichtenPerMagazijn().orElse(SeedVerzoek.STANDAARD_AANTAL),
                bijlageElke = config.bijlageElke().orElse(SeedVerzoek.STANDAARD_BIJLAGE_ELKE),
            ),
        )

        log.infof(
            "Basisvulling geplaatst: %d berichten voor %d ontvanger(s) over %d magazijnen",
            uitkomst.berichten,
            uitkomst.ontvangers,
            uitkomst.magazijnen,
        )

        return Uitkomst.GEPLAATST
    }

    /** Wat een ronde opleverde. */
    enum class Uitkomst {
        /** Er zijn geen ontvangers geconfigureerd; deze omgeving wil deze vulling niet. */
        NIET_GECONFIGUREERD,

        /** Er stond al post; die blijft staan, ook als het minder is dan de basisvulling zou zetten. */
        AL_GEVULD,

        /** De opslag was leeg en is gevuld. */
        GEPLAATST,
    }

    private companion object {
        /** Later dan de standaardprioriteit van een waarnemer, en dus later dan de magazijn-rijen. */
        const val NA_DE_MAGAZIJNEN = Interceptor.Priority.APPLICATION + 600
    }
}
