package nl.rijksoverheid.moz.fbs.stelselregister.stelseldocument

import io.quarkus.runtime.StartupEvent
import io.quarkus.scheduler.Scheduled
import jakarta.enterprise.context.ApplicationScoped
import jakarta.enterprise.event.Observes
import nl.rijksoverheid.moz.fbs.common.identificatie.Oin
import nl.rijksoverheid.moz.fbs.magazijnregister.Magazijnregister
import org.jboss.logging.Logger
import java.time.Clock
import java.time.Duration
import java.util.concurrent.atomic.AtomicReference

/**
 * Het geldende exemplaar van het stelseldocument. Ondertekenen gebeurt hier, bij de start en
 * daarna op een vast interval; een verzoek krijgt de klaarstaande bytes en kost geen cryptografie.
 */
@ApplicationScoped
class UitgegevenStelseldocument(
    private val register: Magazijnregister,
    private val sleutelbron: Sleutelbron,
    private val config: StelseldocumentConfig,
    private val clock: Clock,
) {

    private val log = Logger.getLogger(UitgegevenStelseldocument::class.java)
    private val huidige = AtomicReference<Uitgifte?>()

    private val ondertekenaar by lazy {
        // Via de Oin-constructor: een uitgever die geen OIN is, hoort de start te blokkeren.
        StelseldocumentOndertekenaar(Oin(config.uitgeverOin()).waarde, config.omgeving(), config.geldigheid())
    }

    /**
     * Zonder eerste exemplaar start de dienst niet: een ontbrekende of onbruikbare sleutel moet
     * bij de uitrol opvallen en niet pas bij het eerste verzoek van een afnemer.
     */
    fun bijOpstart(@Observes startup: StartupEvent) {
        require(config.geldigheid() > config.verversen()) {
            "stelseldocument.geldigheid (${config.geldigheid()}) moet langer zijn dan stelseldocument.verversen " +
                "(${config.verversen()}); anders verloopt elk exemplaar vóór het volgende er is"
        }

        require(config.geldigheid() <= MAX_GELDIGHEID) {
            "stelseldocument.geldigheid (${config.geldigheid()}) mag niet langer zijn dan $MAX_GELDIGHEID: " +
                "een afnemer weigert een document dat langer geldt"
        }

        val onversleuteld = onversleuteld(Stelseldocument.uit(register))

        if (onversleuteld.isNotEmpty()) {
            log.warnf(
                "Het stelseldocument wijst voor %d organisatie(s) naar een adres zonder TLS: %s",
                onversleuteld.size,
                onversleuteld,
            )
        }

        geefUit()
    }

    /**
     * Mislukt een latere uitgifte, dan blijft het vorige exemplaar staan tot het zelf verloopt en
     * meldt de dienst zich daarna niet meer gereed. Is de oorzaak een verlopen keten, dan is er
     * geen uitloop: de geldigheid van elk exemplaar eindigt uiterlijk op hetzelfde moment als de
     * keten. De waarschuwing bij elke uitgifte is daarom het signaal om op te sturen.
     */
    @Scheduled(
        every = "{stelseldocument.verversen}",
        delayed = "{stelseldocument.verversen}",
        concurrentExecution = Scheduled.ConcurrentExecution.SKIP,
    )
    fun ververs() {
        try {
            geefUit()
        } catch (e: OngeldigeOndertekensleutelException) {
            val vorige = geldend()

            if (vorige == null) {
                log.errorf(e, "Het stelseldocument is niet ververst en er is geen geldend exemplaar meer; de dienst geeft 503")
            } else {
                log.errorf(e, "Het stelseldocument is niet ververst; het vorige exemplaar geldt nog tot %s", vorige.verlooptOp)
            }
        }
    }

    /** Het exemplaar dat nu uitgeleverd mag worden, of `null` als er geen is dat een afnemer nu accepteert. */
    fun geldend(): Uitgifte? = huidige.get()?.takeIf { it.isGeldigOp(clock.instant()) }

    private fun geefUit() {
        val sleutel = sleutelbron.sleutel
        val nu = clock.instant()

        sleutelbron.waarschuwBijNaderendVerloop(nu)

        val uitgifte = ondertekenaar.onderteken(Stelseldocument.uit(register), sleutel, nu)
        val vorige = huidige.get()

        if (vorige != null && uitgifte.uitgegevenOp.isBefore(vorige.uitgegevenOp)) {
            // Een afnemer weigert een exemplaar met een oudere `iat` dan het laatste dat hij
            // accepteerde. Loopt de klok een stukje terug, dan blijft het bestaande exemplaar
            // dus staan tot de klok het inhaalt.
            if (!vorige.isUitDeToekomst(nu)) {
                log.warnf(
                    "De klok staat vóór de vorige uitgifte (%s); het exemplaar van %s blijft staan",
                    uitgifte.uitgegevenOp,
                    vorige.uitgegevenOp,
                )

                return
            }

            // Dat geldt niet voor een exemplaar uit de toekomst: dat heeft geen afnemer ooit
            // geaccepteerd, en vasthouden zou de dienst stilzetten tot de tijd het inhaalt.
            log.errorf(
                "Het vorige exemplaar is uitgegeven op %s, in de toekomst: de klok stond bij die uitgifte vooruit. " +
                    "Het wordt vervangen",
                vorige.uitgegevenOp,
            )
        }

        huidige.set(uitgifte)
        log.infof(
            "Stelseldocument uitgegeven: kid=%s version=%s iat=%s exp=%s organisaties=%d sleutel=%s",
            uitgifte.kid,
            uitgifte.versie,
            uitgifte.uitgegevenOp,
            uitgifte.verlooptOp,
            uitgifte.aantalOrganisaties,
            sleutel.herkomst,
        )
    }

    companion object {
        /** De langste geldigheid die een afnemer accepteert. */
        val MAX_GELDIGHEID: Duration = Duration.ofHours(24)

        /**
         * De adressen in het document die geen `https` zijn. Het register weigert die buiten de
         * profielen dev en test; een uitgerolde dienst onder zo'n profiel publiceert ze wel, en
         * dan hoort dat in de log te staan.
         */
        internal fun onversleuteld(document: Stelseldocument): List<String> =
            document.organisaties.map { it.magazijnUrl }.filterNot { it.startsWith("https://", ignoreCase = true) }
    }
}
