package nl.rijksoverheid.moz.fbs.berichtensessiecache.berichten

import io.quarkus.runtime.Startup
import io.smallrye.mutiny.Multi
import io.smallrye.mutiny.Uni
import io.smallrye.mutiny.subscription.Cancellable
import io.smallrye.mutiny.subscription.MultiEmitter
import jakarta.enterprise.context.ApplicationScoped
import nl.rijksoverheid.moz.fbs.berichtensessiecache.sessie.SessieRegister
import nl.rijksoverheid.moz.fbs.kern.identificatie.Identificatienummer
import org.eclipse.microprofile.config.inject.ConfigProperty
import org.jboss.logging.Logger
import java.time.Duration
import java.util.concurrent.atomic.AtomicReference

/**
 * Houdt een open berichtenbox bij: stuurt elk in de sessie aangemeld bericht door en controleert op
 * elke hartslag of de inlogsessie nog loopt. Eindigt de sessie, dan merkt de stream dat hoogstens
 * een hartslag later en sluit hij met [SessieGebeurtenis.SessieVerlopen]. Zolang ze loopt, verlengt
 * de hartslag de gegevens van de ontvanger, zodat een bezoeker die alleen kijkt ze niet verliest.
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
    // Begrenst hoe lang één connection openstaat, zodat een stream die zijn einde mist (een
    // half-open connection achter een proxy) geen luisteraar en hartslag eindeloos vasthoudt.
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

        // De cache verlengt pas als er minder dan dit deel van de bewaartermijn rest; een tragere
        // hartslag laat de gegevens van een gevolgde sessie tussen twee slagen in verlopen.
        require(hartslag < ttl.dividedBy(BerichtenCache.VERLENG_DEEL)) {
            "berichtensessiecache.volg-hartslag ($hartslag) moet kleiner zijn dan " +
                "1/${BerichtenCache.VERLENG_DEEL} van berichtensessiecache.ttl ($ttl)"
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
                .chain { _ -> controleer(sessieId, ontvanger) }
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
     * Een mislukte controle breekt de stream af en meldt geen [SessieGebeurtenis.SessieVerlopen]:
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
            .onItem().transformToUniAndConcatenate { _ -> controleer(sessieId, ontvanger) }
            .subscribe().with(
                { loopt -> if (loopt) emitter.emit(SessieGebeurtenis.Hartslag) else beeindig(emitter) },
                { fout ->
                    log.warnf(fout, "Hartslag van een gevolgde sessie mislukt; stream afgebroken")
                    emitter.fail(fout)
                },
            )

    /**
     * `true` als de sessie loopt; dan zijn ook de gegevens van de ontvanger verlengd. Of die er nog
     * staan, beslist niet over de stream: dat doet alleen de sessie.
     */
    private fun controleer(sessieId: String, ontvanger: Identificatienummer): Uni<Boolean> =
        sessies.lees(sessieId, ontvanger).call { loopt ->
            if (loopt) berichtenCache.verlengSessie(BerichtenCache.cacheKey(ontvanger)) else Uni.createFrom().voidItem()
        }

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
