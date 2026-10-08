package nl.rijksoverheid.moz.fbs.stelselregister.stelseldocument

import io.quarkus.runtime.StartupEvent
import io.quarkus.scheduler.Scheduled
import jakarta.enterprise.context.ApplicationScoped
import jakarta.enterprise.event.Observes
import nl.rijksoverheid.moz.fbs.common.identificatie.Oin
import nl.rijksoverheid.moz.fbs.magazijnregister.Magazijnregister
import org.jboss.logging.Logger
import java.time.Clock
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

        geefUit()
    }

    /**
     * Mislukt een latere uitgifte — in de praktijk: het certificaat is verlopen — dan blijft het
     * vorige exemplaar staan tot het zelf verloopt. Daarna meldt de dienst zich niet meer gereed.
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
            log.errorf(e, "Het stelseldocument is niet ververst; het vorige exemplaar geldt tot %s", huidige.get()?.verlooptOp)
        }
    }

    /** Het exemplaar dat nu uitgeleverd mag worden, of `null` als er geen onverlopen exemplaar is. */
    fun geldend(): Uitgifte? = huidige.get()?.takeIf { it.isGeldigOp(clock.instant()) }

    private fun geefUit() {
        val sleutel = sleutelbron.sleutel
        val uitgifte = ondertekenaar.onderteken(Stelseldocument.uit(register), sleutel, clock.instant())

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
}
