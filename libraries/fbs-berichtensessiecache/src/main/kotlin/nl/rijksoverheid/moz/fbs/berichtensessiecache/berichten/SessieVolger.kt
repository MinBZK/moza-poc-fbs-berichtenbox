package nl.rijksoverheid.moz.fbs.berichtensessiecache.berichten

import io.quarkus.runtime.Startup
import io.smallrye.mutiny.Multi
import io.smallrye.mutiny.Uni
import io.smallrye.mutiny.operators.multi.processors.UnicastProcessor
import io.smallrye.mutiny.subscription.Cancellable
import io.smallrye.mutiny.subscription.MultiEmitter
import jakarta.enterprise.context.ApplicationScoped
import nl.rijksoverheid.moz.fbs.berichtensessiecache.sessie.SessieRegister
import nl.rijksoverheid.moz.fbs.kern.identificatie.Identificatienummer
import org.eclipse.microprofile.config.inject.ConfigProperty
import org.jboss.logging.Logger
import java.time.Duration
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicReference

/**
 * Houdt een open berichtenbox bij: stuurt elk in de sessie aangemeld, bijgewerkt of verwijderd
 * bericht door en controleert op elke hartslag of de inlogsessie nog loopt. Alleen lezen: een open
 * connection is geen activiteit van de gebruiker en houdt de sessie dus niet in leven. Verloopt de
 * sessie, dan merkt de stream dat hoogstens een hartslag later en sluit hij met
 * [SessieGebeurtenis.SessieVerlopen].
 *
 * De volgorde bij het starten is wat "niets missen" waarmaakt: eerst de luisteraar registreren,
 * dan wachten tot deze pod aanmeldingen ontvangt, en pas dan [SessieGebeurtenis.VolgenGestart].
 * Wat vóór dat moment binnenkwam, leest de afnemer uit de lijst; wat erna komt, krijgt hij hier.
 * Wat in het overlappende stukje valt, krijgt hij twee keer — daarom ontdubbelt hij op
 * (`magazijnId`, `berichtId`).
 *
 * Per stream gaan de meldingen één voor één door, in de volgorde waarin ze binnenkwamen. Een
 * bijwerking leest de huidige waarden uit de cache, dus anders komt een trage lezing van de eerste
 * van twee snelle bijwerkingen ná de tweede aan, en blijft het scherm op de oude waarde staan. Een
 * lezing die niet binnen `berichtensessiecache.volg-lees-timeout` antwoordt, breekt de stream af:
 * de Redis-client begrenst een reactief commando zelf niet, en één hangende lezing zou anders alle
 * latere meldingen van die stream ophouden terwijl de hartslag gewoon doorloopt.
 *
 * Volgt een verwijdering snel op een bijwerking, dan kan de lezing voor die bijwerking het bericht
 * al kwijt zijn; de bijwerking valt dan weg, de verwijdering komt wel door.
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
    // Gelijk aan de standaardtimeout van de facade: een lezing per melding, net als een GET.
    @param:ConfigProperty(name = "berichtensessiecache.volg-lees-timeout", defaultValue = "PT5S")
    private val leesTimeout: Duration,
) {
    private val log = Logger.getLogger(SessieVolger::class.java)

    init {
        require(hartslag.isPositive) {
            "berichtensessiecache.volg-hartslag ($hartslag) moet groter zijn dan 0"
        }

        require(maxDuur > hartslag) {
            "berichtensessiecache.volg-max-duur ($maxDuur) moet groter zijn dan berichtensessiecache.volg-hartslag ($hartslag)"
        }

        require(leesTimeout.isPositive) {
            "berichtensessiecache.volg-lees-timeout ($leesTimeout) moet groter zijn dan 0"
        }
    }

    fun meld(ontvanger: Identificatienummer, sleutel: BerichtSleutel, wijziging: Berichtwijziging): Uni<Void> =
        aanmeldingen.meld(BerichtenCache.cacheKey(ontvanger), sleutel, wijziging)

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
            val meldingen = UnicastProcessor.create<Pair<BerichtSleutel, Berichtwijziging>>()
            // Meldingen kunnen van meer dan één thread komen, en een processor verwacht ze één voor één.
            val invoer = meldingen.serialized()
            val doorgifte = meldingen
                .onItem().transformToUniAndConcatenate { (sleutel, wijziging) -> naarGebeurtenis(sleutel, wijziging, ontvanger) }
                .subscribe().with(
                    { gebeurtenis -> gebeurtenis?.let { emitter.emit(it) } },
                    { fout ->
                        log.warnf(fout, "Gemeld bericht niet te lezen; gevolgde sessie afgebroken")
                        emitter.fail(fout)
                    },
                )

            val afmelding = aanmeldingen.registreer(
                cacheKey,
                opMelding ={ sleutel, wijziging -> invoer.onNext(sleutel to wijziging) },
                opStoring = emitter::fail,
                // Een stoppende pod is geen storing: zelfde einde als na de maximale duur.
                opEinde = emitter::complete,
            )

            emitter.onTermination {
                afmelding.afmelden()
                doorgifte.cancel()
                einde.cancel()
                hartslagen.getAndSet(null)?.cancel()
            }

            aanmeldingen.actief()
                // De facade controleerde de sessie al; dit vangt een einde tussen die controle en
                // VolgenGestart, zodat SessieVerlopen dan het enige item is.
                .chain { _ -> sessies.lees(sessieId, ontvanger) }
                .subscribe().with(
                    { geldigheid ->
                        if (geldigheid != null) {
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
            // Naar een Boolean: een Multi laat een null-item vallen, en dan zou het einde van de sessie
            // nooit aankomen.
            .onItem().transformToUniAndConcatenate { _ -> sessies.lees(sessieId, ontvanger).map { it != null } }
            .subscribe().with(
                { loopt -> if (loopt) emitter.emit(SessieGebeurtenis.Hartslag) else beeindig(emitter) },
                { fout ->
                    log.warnf(fout, "Hartslag van een gevolgde sessie mislukt; stream afgebroken")
                    emitter.fail(fout)
                },
            )

    /**
     * Leest een nieuw of bijgewerkt bericht uit de cache in plaats van het mee te sturen over het
     * kanaal: zo gaat er geen berichtgegeven over pub/sub, en controleert [BerichtenCache.getById]
     * meteen dat het van deze ontvanger is. Een leesfout breekt de stream af; de afnemer verbindt
     * opnieuw en leest de lijst, zodat het bericht alsnog klopt.
     *
     * Een verwijdering heeft niets te lezen en controleert dus geen eigenaar: alleen de sleutel gaat
     * door. Dat is veilig omdat de luisteraars gekozen worden op de cacheKey van de eigen ontvanger,
     * en alleen een geslaagde verwijdering door die ontvanger meldt; de sleutel (OIN en berichtnummer)
     * verraadt verder niets over het bericht.
     *
     * Geeft de cache niets terug, dan staat het bericht er niet (meer): de melding wordt
     * overgeslagen en de lijst blijft de waarheid. `null` laat de doorgifte het item overslaan.
     */
    private fun naarGebeurtenis(
        sleutel: BerichtSleutel,
        wijziging: Berichtwijziging,
        ontvanger: Identificatienummer,
    ): Uni<SessieGebeurtenis?> {
        if (wijziging == Berichtwijziging.VERWIJDERD) {
            return Uni.createFrom().item(SessieGebeurtenis.BerichtVerwijderd(sleutel.magazijnId, sleutel.berichtId))
        }

        return berichtenCache.getById(sleutel, ontvanger)
            .ifNoItem().after(leesTimeout)
            .failWith { TimeoutException("lezing van een gemeld bericht kwam niet terug binnen $leesTimeout") }
            .map { bericht ->
                when {
                    bericht == null -> {
                        // De sleutel is geen persoonsgegeven; dit spoor verbindt een gemist bericht
                        // op het scherm met een hash die eerder verliep dan zijn lijst-entry.
                        log.debugf("Gemeld bericht %s niet in de cache; overgeslagen", sleutel)
                        null
                    }
                    wijziging == Berichtwijziging.BIJGEKOMEN -> SessieGebeurtenis.BerichtBijgekomen(bericht)
                    else -> SessieGebeurtenis.BerichtGewijzigd(bericht.magazijnId, bericht.berichtId, bericht.status, bericht.map)
                }
            }
    }

    private fun beeindig(emitter: MultiEmitter<in SessieGebeurtenis>) {
        emitter.emit(SessieGebeurtenis.SessieVerlopen)
        emitter.complete()
    }
}
