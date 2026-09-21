package nl.rijksoverheid.moz.fbs.berichtensessiecache.berichten

import io.quarkus.runtime.Startup
import io.smallrye.mutiny.Multi
import io.smallrye.mutiny.Uni
import io.smallrye.mutiny.subscription.Cancellable
import io.smallrye.mutiny.subscription.MultiEmitter
import jakarta.enterprise.context.ApplicationScoped
import nl.rijksoverheid.moz.fbs.common.identificatie.Identificatienummer
import org.eclipse.microprofile.config.inject.ConfigProperty
import org.jboss.logging.Logger
import java.time.Duration
import java.util.UUID
import java.util.concurrent.atomic.AtomicReference

/**
 * Houdt een open berichtenbox bij: stuurt elk in de sessie aangemeld bericht door en verlengt de
 * sessie op elke hartslag, zodat een bezoeker die alleen kijkt zijn sessie niet verliest.
 *
 * De volgorde bij het starten is wat "niets missen" waarmaakt: eerst de luisteraar registreren,
 * dan wachten tot deze pod aanmeldingen ontvangt, en pas dan [SessieGebeurtenis.VolgenGestart].
 * Wat vóór dat moment binnenkwam, leest de afnemer uit de lijst; wat erna komt, krijgt hij hier.
 * Wat in het overlappende stukje valt, krijgt hij twee keer — daarom ontdubbelt hij op
 * `berichtId`.
 *
 * `@Startup` zodat een verkeerd ingestelde hartslag de pod bij het opstarten tegenhoudt en niet
 * pas bij de eerste bezoeker.
 */
@Startup
@ApplicationScoped
internal class SessieVolger(
    private val berichtenCache: BerichtenCache,
    private val aanmeldingen: Aanmeldingen,
    // Onder de 30 s waarop een OpenShift-route een stille connection standaard afbreekt, en ruim
    // onder de 60 s van een nginx-proxy.
    @param:ConfigProperty(name = "berichtensessiecache.volg-hartslag", defaultValue = "PT20S")
    private val hartslag: Duration,
    // Een open stream verlengt de sessie zolang hij loopt. Deze grens dwingt periodiek een nieuwe
    // aanvraag af, zodat een vergeten tabblad de sessie niet eindeloos vasthoudt en de toegang bij
    // elke nieuwe connection opnieuw gecontroleerd wordt.
    @param:ConfigProperty(name = "berichtensessiecache.volg-max-duur", defaultValue = "PT1H")
    private val maxDuur: Duration,
    @ConfigProperty(name = "berichtensessiecache.ttl", defaultValue = "PT12H")
    ttl: Duration,
) {
    private val log = Logger.getLogger(SessieVolger::class.java)

    init {
        require(hartslag.isPositive) {
            "berichtensessiecache.volg-hartslag ($hartslag) moet groter zijn dan 0"
        }

        require(maxDuur > hartslag) {
            "berichtensessiecache.volg-max-duur ($maxDuur) moet groter zijn dan berichtensessiecache.volg-hartslag ($hartslag)"
        }

        // De cache verlengt pas als de helft van de sessieduur verstreken is; een tragere hartslag
        // laat een gevolgde sessie tussen twee slagen in verlopen.
        require(hartslag < ttl.dividedBy(2)) {
            "berichtensessiecache.volg-hartslag ($hartslag) moet kleiner zijn dan de helft van " +
                "berichtensessiecache.ttl ($ttl)"
        }
    }

    fun meldAan(ontvanger: Identificatienummer, berichtId: UUID): Uni<Void> =
        aanmeldingen.meld(BerichtenCache.cacheKey(ontvanger), berichtId)

    fun volg(ontvanger: Identificatienummer): Multi<SessieGebeurtenis> {
        val cacheKey = BerichtenCache.cacheKey(ontvanger)

        return Multi.createFrom().emitter { emitter ->
            val hartslagen = AtomicReference<Cancellable?>(null)
            // Eindigt zonder SessieVerlopen: de sessie loopt nog, de afnemer verbindt gewoon opnieuw.
            val einde = Uni.createFrom().voidItem().onItem().delayIt().by(maxDuur)
                .subscribe().with { emitter.complete() }
            val afmelding = aanmeldingen.registreer(
                cacheKey,
                opBericht = { berichtId -> stuurDoor(berichtId, ontvanger, emitter) },
                opStoring = emitter::fail,
            )

            emitter.onTermination {
                afmelding.afmelden()
                einde.cancel()
                hartslagen.getAndSet(null)?.cancel()
            }

            aanmeldingen.actief()
                .chain { _ -> berichtenCache.verlengSessie(cacheKey) }
                .subscribe().with(
                    { loopt ->
                        if (loopt) {
                            emitter.emit(SessieGebeurtenis.VolgenGestart)
                            hartslagen.set(startHartslag(cacheKey, emitter))

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

    private fun startHartslag(cacheKey: String, emitter: MultiEmitter<in SessieGebeurtenis>): Cancellable =
        Multi.createFrom().ticks().every(hartslag)
            .onOverflow().drop()
            .onItem().transformToUniAndConcatenate { _ -> berichtenCache.verlengSessie(cacheKey) }
            .subscribe().with(
                { loopt -> if (loopt) emitter.emit(SessieGebeurtenis.Hartslag) else beeindig(emitter) },
                emitter::fail,
            )

    /**
     * Leest het bericht uit de cache in plaats van het mee te sturen over het kanaal: zo gaat er
     * geen berichtgegeven over pub/sub, en controleert [BerichtenCache.getById] meteen dat het
     * van deze ontvanger is. Een leesfout breekt de stream af; de afnemer verbindt opnieuw en
     * leest de lijst, zodat het bericht alsnog verschijnt.
     */
    private fun stuurDoor(berichtId: UUID, ontvanger: Identificatienummer, emitter: MultiEmitter<in SessieGebeurtenis>) {
        berichtenCache.getById(berichtId, ontvanger).subscribe().with(
            { bericht -> bericht?.let { emitter.emit(SessieGebeurtenis.BerichtBijgekomen(it)) } },
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
