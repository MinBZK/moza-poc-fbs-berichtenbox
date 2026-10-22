package nl.rijksoverheid.moz.fbs.berichtensessiecache.berichten

import io.quarkus.runtime.Startup
import io.smallrye.mutiny.Multi
import io.smallrye.mutiny.Uni
import io.smallrye.mutiny.subscription.Cancellable
import io.smallrye.mutiny.subscription.MultiEmitter
import jakarta.enterprise.context.ApplicationScoped
import nl.rijksoverheid.moz.fbs.berichtensessiecache.sessie.SessieConfig
import nl.rijksoverheid.moz.fbs.berichtensessiecache.sessie.SessieRegister
import nl.rijksoverheid.moz.fbs.berichtensessiecache.sessie.valideer
import nl.rijksoverheid.moz.fbs.kern.identificatie.Identificatienummer
import org.eclipse.microprofile.config.inject.ConfigProperty
import org.jboss.logging.Logger
import java.time.Duration
import java.util.concurrent.atomic.AtomicReference

/**
 * Houdt een open berichtenbox bij: stuurt elk in de sessie aangemeld bericht door en raakt de
 * inlogsessie op elke hartslag, zodat een bezoeker die alleen kijkt zijn sessie niet verliest.
 *
 * De volgorde bij het starten is wat "niets missen" waarmaakt: eerst de luisteraar registreren,
 * dan wachten tot deze pod aanmeldingen ontvangt, en pas dan [SessieGebeurtenis.VolgenGestart].
 * Wat vóór dat moment binnenkwam, leest de afnemer uit de lijst; wat erna komt, krijgt hij hier.
 * Wat in het overlappende stukje valt, krijgt hij twee keer — daarom ontdubbelt hij op
 * (`magazijnId`, `berichtId`).
 *
 * `@Startup` zodat een verkeerd ingestelde hartslag de pod bij het opstarten tegenhoudt en niet
 * pas bij de eerste bezoeker.
 */
@Startup
@ApplicationScoped
internal class SessieVolger(
    private val berichtenCache: BerichtenCache,
    private val aanmeldingen: Aanmeldingen,
    private val sessies: SessieRegister,
    // Onder de 30 s waarop een OpenShift-route een stille connection standaard afbreekt, en ruim
    // onder de 60 s van een nginx-proxy.
    @param:ConfigProperty(name = "berichtensessiecache.volg-hartslag", defaultValue = "PT20S")
    private val hartslag: Duration,
    // Een open stream verlengt de sessie zolang hij loopt. Deze grens dwingt periodiek een nieuwe
    // aanvraag af, zodat een vergeten tabblad de sessie niet eindeloos vasthoudt.
    @param:ConfigProperty(name = "berichtensessiecache.volg-max-duur", defaultValue = "PT1H")
    private val maxDuur: Duration,
    sessieConfig: SessieConfig,
) {
    private val log = Logger.getLogger(SessieVolger::class.java)

    init {
        sessieConfig.valideer()

        require(hartslag.isPositive) {
            "berichtensessiecache.volg-hartslag ($hartslag) moet groter zijn dan 0"
        }

        require(maxDuur > hartslag) {
            "berichtensessiecache.volg-max-duur ($maxDuur) moet groter zijn dan berichtensessiecache.volg-hartslag ($hartslag)"
        }

        // Zo laat één gemiste of vertraagde slag een gevolgde sessie niet verlopen.
        val inactiviteit = sessieConfig.inactiviteit()

        require(hartslag < inactiviteit.dividedBy(2)) {
            "berichtensessiecache.volg-hartslag ($hartslag) moet kleiner zijn dan de helft van " +
                "berichtensessiecache.sessie.inactiviteit ($inactiviteit)"
        }
    }

    fun meldAan(ontvanger: Identificatienummer, sleutel: BerichtSleutel): Uni<Void> =
        aanmeldingen.meld(BerichtenCache.cacheKey(ontvanger), sleutel)

    /**
     * Slaagt zodra deze pod aanmeldingen ontvangt. De facade wacht hierop vóórdat hij de stream
     * teruggeeft: een mislukking daarna valt op een al geopende stream en kan de afnemer alleen nog
     * als een lege, sluitende connection zien, niet als een storing met een reden.
     */
    fun actief(): Uni<Void> = aanmeldingen.actief()

    fun volg(ontvanger: Identificatienummer, sessieId: String): Multi<SessieGebeurtenis> {
        val cacheKey = BerichtenCache.cacheKey(ontvanger)

        return Multi.createFrom().emitter { emitter ->
            val hartslagen = AtomicReference<Cancellable?>(null)
            val einde = Uni.createFrom().voidItem().onItem().delayIt().by(maxDuur)
                .subscribe().with { emitter.complete() }
            val afmelding = aanmeldingen.registreer(
                cacheKey,
                opBericht = { sleutel -> stuurDoor(sleutel, ontvanger, emitter) },
                opStoring = emitter::fail,
                // Een stoppende pod is geen storing: zelfde einde als na de maximale duur.
                opEinde = emitter::complete,
            )

            emitter.onTermination {
                afmelding.afmelden()
                einde.cancel()
                hartslagen.getAndSet(null)?.cancel()
            }

            aanmeldingen.actief()
                // De facade controleerde de sessie al; dit vangt een einde tussen die controle en
                // VolgenGestart, zodat SessieVerlopen dan het enige item is.
                .chain { _ -> sessies.raak(sessieId, ontvanger) }
                .subscribe().with(
                    { loopt ->
                        if (loopt) {
                            emitter.emit(SessieGebeurtenis.VolgenGestart)
                            hartslagen.set(startHartslag(sessieId, ontvanger, emitter))

                            // Afgebroken terwijl de hartslag nog niet stond: onTermination zag hem niet.
                            if (emitter.isCancelled) hartslagen.getAndSet(null)?.cancel()
                        } else {
                            beeindig(emitter)
                        }
                    },
                    emitter::fail,
                )
        }
    }

    /**
     * Een mislukte aanraking breekt de stream af en meldt geen [SessieGebeurtenis.SessieVerlopen]:
     * een storing in Redis is geen einde van de sessie, en de afnemer die opnieuw verbindt, krijgt
     * een eerlijke 503 in plaats van een uitlog.
     */
    private fun startHartslag(
        sessieId: String,
        ontvanger: Identificatienummer,
        emitter: MultiEmitter<in SessieGebeurtenis>,
    ): Cancellable =
        Multi.createFrom().ticks().every(hartslag)
            .onOverflow().drop()
            .onItem().transformToUniAndConcatenate { _ -> sessies.raak(sessieId, ontvanger) }
            .subscribe().with(
                { loopt -> if (loopt) emitter.emit(SessieGebeurtenis.Hartslag) else beeindig(emitter) },
                { fout ->
                    log.warnf(fout, "Hartslag van een gevolgde sessie mislukt; stream afgebroken")
                    emitter.fail(fout)
                },
            )

    /**
     * Leest het bericht uit de cache in plaats van het mee te sturen over het kanaal: zo gaat er
     * geen berichtgegeven over pub/sub, en controleert [BerichtenCache.getById] meteen dat het
     * van deze ontvanger is. Een leesfout breekt de stream af; de afnemer verbindt opnieuw en
     * leest de lijst, zodat het bericht alsnog verschijnt.
     *
     * Geeft de cache niets terug, dan staat het bericht er niet (meer): de aanmelding wordt
     * overgeslagen en de lijst blijft de waarheid.
     */
    private fun stuurDoor(sleutel: BerichtSleutel, ontvanger: Identificatienummer, emitter: MultiEmitter<in SessieGebeurtenis>) {
        berichtenCache.getById(sleutel, ontvanger).subscribe().with(
            { bericht ->
                if (bericht == null) {
                    // De sleutel is geen persoonsgegeven; dit spoor verbindt een gemist bericht
                    // op het scherm met een hash die eerder verliep dan zijn lijst-entry.
                    log.debugf("Aangemeld bericht %s niet in de cache; overgeslagen", sleutel)
                } else {
                    emitter.emit(SessieGebeurtenis.BerichtBijgekomen(bericht))
                }
            },
            { fout ->
                log.warnf(fout, "Aangemeld bericht niet te lezen; gevolgde sessie afgebroken")
                emitter.fail(fout)
            },
        )
    }

    private fun beeindig(emitter: MultiEmitter<in SessieGebeurtenis>) {
        emitter.emit(SessieGebeurtenis.SessieVerlopen)
        emitter.complete()
    }
}
