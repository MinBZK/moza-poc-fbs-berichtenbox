package nl.rijksoverheid.moz.fbs.magazijnsimulator.beheer

import io.quarkus.runtime.StartupEvent
import jakarta.annotation.PostConstruct
import jakarta.annotation.Priority
import jakarta.enterprise.context.ApplicationScoped
import jakarta.enterprise.event.Observes
import jakarta.interceptor.Interceptor
import org.jboss.logging.Logger

/**
 * Zet bij het opstarten post klaar in de magazijnen waar nog niets staat.
 *
 * Zonder die vulling staat een verse omgeving — een preview, of een deployment waarvan de database
 * opnieuw is aangemaakt — met alle magazijnen op nul berichten terwijl elk magazijn keurig
 * antwoordt. Dat is van een kapotte keten niet te onderscheiden, en het valt pas op wanneer iemand
 * de omgeving voor een demonstratie opent.
 *
 * **Per magazijn, niet over de hele opslag.** Een ronde die halverwege afbreekt laat magazijnen
 * zonder post achter; de volgende start werkt die bij in plaats van te besluiten dat er al gevuld
 * is. Wie een magazijn bewust leeg wil hebben, moet dus met de storingsknoppen werken en niet met
 * de legen-knop: na een herstart staat de post er weer.
 *
 * **Het vullen gebeurt op het opstartpad, vóór het eerste verkeer.** Op demoschaal is dat honderd
 * transacties; `SeedOpSchaalTest` bewaakt dat dat seconden kost en geen minuten. Een schrijffout
 * wordt bewust niet gevangen: dan faalt het starten, en dat is zichtbaar — een simulator die stil
 * doorstart met lege magazijnen is precies het beeld dat deze klasse wegneemt. Een fout in de
 * configuratie slaat al eerder toe, bij [OpstartvullingConfiguratie].
 */
@ApplicationScoped
class Opstartvulling(
    private val config: OpstartvullingConfig,
    private val beheer: BeheerService,
) {

    private val log = Logger.getLogger(Opstartvulling::class.java)

    /** `null` betekent: deze omgeving wil geen opstartvulling. */
    private var gewenst: SeedVerzoek? = null

    /**
     * Toetsen zonder database, en vóór het event: een fout in de configuratie hoort de boot te
     * stoppen ongeacht of de opslag toevallig al gevuld was.
     */
    @PostConstruct
    fun init() {
        gewenst = OpstartvullingConfiguratie.valideer(config)
    }

    /**
     * Ná de magazijn-rijen: `GesimuleerdeMagazijnen` brengt die vanuit hetzelfde event in
     * overeenstemming met de configuratie, en zonder die rijen valt er niets te vullen. De
     * prioriteit legt die volgorde vast in plaats van hem aan de ontdekkingsvolgorde van CDI over
     * te laten.
     */
    fun bijOpstart(@Observes @Priority(NA_DE_MAGAZIJNEN) startup: StartupEvent) {
        vulOntbrekende()
    }

    /**
     * Vult de magazijnen zonder post, en zegt wat het geworden is.
     *
     * De uitkomst draagt de aantallen mee omdat "gevuld" zonder getal niets zegt: nul magazijnen
     * vullen is geen vulling, en dat onderscheid hoort niet alleen in de log te staan.
     */
    fun vulOntbrekende(): Uitkomst {
        val verzoek = gewenst

        if (verzoek == null) {
            // WARN en geen INFO: op een omgeving die deze vulling wél hoort te hebben, is dit de
            // enige aanwijzing dat de magazijnen leeg blijven, en dat merkt anders pas de demo.
            log.warnf(
                "Geen opstartvulling geconfigureerd (%s.ontvangers ontbreekt); de opslag blijft zoals hij is",
                "magazijnsimulator.opstartvulling",
            )

            return Uitkomst.NietGeconfigureerd
        }

        val seed = beheer.seedOntbrekende(verzoek)

        if (seed.magazijnen == 0) {
            log.info("Opstartvulling overgeslagen: elk magazijn heeft al post")

            return Uitkomst.AlGevuld
        }

        log.infof(
            "Opstartvulling geplaatst: %d berichten en %d bijlagen voor %d ontvanger(s) in %d magazijn(en)",
            seed.berichten,
            seed.bijlagen,
            seed.ontvangers,
            seed.magazijnen,
        )

        return Uitkomst.Geplaatst(seed)
    }

    sealed interface Uitkomst {

        /** Er zijn geen ontvangers geconfigureerd; deze omgeving wil deze vulling niet. */
        data object NietGeconfigureerd : Uitkomst

        /** Elk magazijn had al post; die blijft staan, ook als het minder is dan de vulling zou zetten. */
        data object AlGevuld : Uitkomst

        /** De magazijnen zonder post zijn gevuld; [seed] zegt hoeveel het er waren. */
        data class Geplaatst(val seed: SeedUitkomst) : Uitkomst
    }

    private companion object {
        /**
         * Later dan de standaardprioriteit van een observer (`APPLICATION + 500`), en dus later dan
         * `GesimuleerdeMagazijnen.bijOpstart`, die die standaard gebruikt.
         */
        const val NA_DE_MAGAZIJNEN = Interceptor.Priority.APPLICATION + 600
    }
}
