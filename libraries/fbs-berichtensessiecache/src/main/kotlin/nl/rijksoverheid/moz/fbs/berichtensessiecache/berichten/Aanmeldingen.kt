package nl.rijksoverheid.moz.fbs.berichtensessiecache.berichten

import io.quarkus.redis.datasource.ReactiveRedisDataSource
import io.quarkus.redis.datasource.pubsub.ReactivePubSubCommands.ReactiveRedisSubscriber
import io.smallrye.mutiny.Multi
import io.smallrye.mutiny.Uni
import io.smallrye.mutiny.subscription.Cancellable
import jakarta.annotation.PreDestroy
import jakarta.enterprise.context.ApplicationScoped
import org.eclipse.microprofile.config.inject.ConfigProperty
import org.jboss.logging.Logger
import java.time.Duration
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/**
 * Brengt een aanmelding van de pod die hem verwerkte naar elke pod waar die sessie gevolgd wordt.
 * Een aanmelding en de open berichtenbox van dezelfde ontvanger landen niet op dezelfde pod, dus
 * een lokale seintjeslijst volstaat niet.
 */
internal interface Aanmeldingen {

    /** Meldt dat [berichtId] in de sessie onder [cacheKey] is bijgeschreven. */
    fun meld(cacheKey: String, berichtId: UUID): Uni<Void>

    /**
     * Registreert een luisteraar voor de sessie onder [cacheKey], direct en synchroon. Berichten
     * komen pas door zodra [actief] geslaagd is. [opStoring] valt wanneer het doorgeven zelf
     * wegvalt; [opEinde] wanneer deze pod stopt. In beide gevallen komt wat daarna wordt aangemeld
     * hier niet meer door. Is de pod al gestopt, dan valt [opEinde] meteen, binnen deze aanroep.
     */
    fun registreer(
        cacheKey: String,
        opBericht: (UUID) -> Unit,
        opStoring: (Throwable) -> Unit,
        opEinde: () -> Unit,
    ): Afmelding

    /**
     * Slaagt zodra deze pod aanmeldingen ontvangt; bouwt het abonnement zo nodig op. Faalt met
     * [AbonnementGesloten] als de pod stopt of de poging onderweg gesloten werd.
     */
    fun actief(): Uni<Void>

    fun interface Afmelding {
        fun afmelden()
    }
}

/**
 * Waarmee [Aanmeldingen.actief] faalt als het abonnement dicht ging omdat de pod stopt of omdat het
 * wegviel; dat laatste is op dat moment al gelogd. Geen nieuwe storing, dus geen error per stream.
 */
internal class AbonnementGesloten(melding: String) : IllegalStateException(melding)

/**
 * Redis pub/sub met één abonnement per pod en de verdeling naar luisteraars in het geheugen.
 * Een abonnement per open berichtenbox zou er één Redis-connection per bezoeker kosten: de
 * Quarkus-client opent voor elke `subscribe` een eigen, langlevende connection.
 *
 * Het kanaalbericht is de `cacheKey` plus het `berichtId`; de inhoud leest de ontvangende pod
 * zelf uit de cache. De `cacheKey` is een SHA-256 zonder geheim over het identificatienummer en
 * dus een pseudoniem, geen anonimisering: een BSN is eruit terug te rekenen. Het kanaal verdient
 * daarom dezelfde bescherming als de sessie-keys (TLS en authenticatie op Redis).
 *
 * Of het abonnement werkt, bewijst een eigen probe over het kanaal, niet de bevestiging van de
 * client: die komt niet altijd aan (Vert.x meldt dan `No handler waiting for message: [subscribe,
 * …]`), terwijl de berichten wél binnenkomen. Een probe die terugkomt, is precies de garantie die
 * [Aanmeldingen.actief] belooft. Daarna herhaalt de pod die probe elke
 * `berichtensessiecache.aanmeldingen-controle`, want een connection kan ook stil wegvallen.
 *
 * Valt het abonnement weg, dan krijgen alle luisteraars [Aanmeldingen.registreer]'s `opStoring`
 * en vergeet deze pod het abonnement; de volgende [actief] bouwt een nieuw. Opnieuw verbinden is
 * daarmee de taak van de afnemer, die bij dat verbinden de lijst opnieuw leest.
 */
@ApplicationScoped
internal class RedisAanmeldingen(
    private val redis: ReactiveRedisDataSource,
    // Hoe vaak een staand abonnement zich opnieuw bewijst. Een connection die stil wegvalt, zonder
    // dat de client het merkt, levert anders een stream op die hartslagen blijft geven maar geen
    // enkel bericht meer. Eén PUBLISH per pod per tussenpoos.
    @param:ConfigProperty(name = "berichtensessiecache.aanmeldingen-controle", defaultValue = "PT30S")
    private val controleInterval: Duration,
) : Aanmeldingen {

    private val log = Logger.getLogger(RedisAanmeldingen::class.java)

    private val luisteraars = ConcurrentHashMap<String, MutableSet<Luisteraar>>()

    private val huidig = AtomicReference<Abonnement?>(null)

    private val probes = ConcurrentHashMap<UUID, CompletableFuture<Void>>()

    private val gestopt = AtomicBoolean(false)

    init {
        require(controleInterval.isPositive) {
            "berichtensessiecache.aanmeldingen-controle ($controleInterval) moet groter zijn dan 0"
        }
    }

    override fun meld(cacheKey: String, berichtId: UUID): Uni<Void> = publiceer(cacheKey, berichtId)

    override fun registreer(
        cacheKey: String,
        opBericht: (UUID) -> Unit,
        opStoring: (Throwable) -> Unit,
        opEinde: () -> Unit,
    ): Aanmeldingen.Afmelding {
        val luisteraar = Luisteraar(opBericht, opStoring, opEinde)

        // Toevoegen binnen `compute`, niet op wat `computeIfAbsent` teruggeeft: dan kan het afmelden
        // van de laatste andere luisteraar de set er net tussenuit halen, en belandt deze in een set
        // die nergens meer aan hangt — een stream die nooit meer een aanmelding krijgt.
        luisteraars.compute(cacheKey) { _, set -> (set ?: ConcurrentHashMap.newKeySet()).apply { add(luisteraar) } }

        // Een stream die tijdens het stoppen nog opent, heeft [stop] net gemist.
        if (gestopt.get()) beeindig(luisteraar)

        return Aanmeldingen.Afmelding {
            luisteraars.computeIfPresent(cacheKey) { _, set ->
                set.remove(luisteraar)
                set.ifEmpty { null }
            }
        }
    }

    override fun actief(): Uni<Void> {
        if (gestopt.get()) return Uni.createFrom().failure(AbonnementGesloten("Deze pod stopt"))

        huidig.get()?.let { return it.gereed }

        // `gereed` is lui: een poging die de CAS hieronder verliest, abonneert nooit.
        val nieuw = Abonnement()

        // Verloren van een gelijktijdige eerste aanroep: die van de winnaar gebruiken. Is die intussen
        // al weer weg, dan opnieuw beginnen, en niet een eigen poging starten die niemand bijhoudt.
        if (!huidig.compareAndSet(null, nieuw)) return huidig.get()?.gereed ?: actief()

        // [stop] kan tussen de controle bovenaan en de CAS hebben gelopen; dan zag hij dit
        // abonnement niet. Gesloten faalt `gereed` zelf, zonder te abonneren.
        if (gestopt.get() && huidig.compareAndSet(nieuw, null)) nieuw.sluit()

        return nieuw.gereed
    }

    /**
     * Sluit elke open stream meteen af. Een open stream is voor de server een lopend verzoek, dus
     * tijdens een rolling update blijft hij anders bestaan tot de afsluit-termijn verstrijkt — met
     * een kanaal dat al dood is. Zo verbinden de berichtenboxen meteen opnieuw, bij een pod die nog
     * leeft, in plaats van in dat venster aanmeldingen te missen.
     *
     * Het afmelden van het abonnement komt via zijn eigen callback nog in [wegGevallen] terecht,
     * maar geeft daar geen storing: alleen wie een abonnement daadwerkelijk sluit, licht de
     * luisteraars in, en dat heeft deze methode dan al gedaan. De luisteraars krijgen dus alleen
     * [Luisteraar.opEinde].
     */
    @PreDestroy
    fun stop() {
        gestopt.set(true)
        huidig.getAndSet(null)?.sluit()

        val aantal = openStreams()

        // Een gewone uitrol: één regel voor de hele pod, geen storing per stream.
        if (aantal > 0) log.infof("Pod stopt; %d open streams verbinden opnieuw", aantal)

        luisteraars.values.flatten().forEach(::beeindig)
    }

    private fun beeindig(luisteraar: Luisteraar) {
        try {
            luisteraar.opEinde()
        } catch (fout: RuntimeException) {
            log.warnf(fout, "Een gevolgde sessie kon niet over het stoppen ingelicht worden")
        }
    }

    private fun openStreams(): Int = luisteraars.values.sumOf { it.size }

    /** Best-effort, maar niet stil: een afmelding die mislukt laat een connection open staan. */
    private fun meldAf(oud: ReactiveRedisSubscriber) {
        oud.unsubscribe().subscribe().with(
            {},
            { fout -> log.warnf(fout, "Afmelden van het aanmeldkanaal mislukt; de connection kan open blijven staan") },
        )
    }

    private fun publiceer(sleutel: String, id: UUID): Uni<Void> =
        redis.pubsub(String::class.java).publish(KANAAL, "$sleutel$SCHEIDING$id")

    // `internal` zodat een test de verdeling onder gelijktijdig registreren en afmelden kan toetsen
    // zonder een Redis-bericht te hoeven afwachten.
    internal fun verdeel(bericht: String) {
        val scheiding = bericht.lastIndexOf(SCHEIDING)
        val id = runCatching { UUID.fromString(bericht.substring(scheiding + 1)) }.getOrNull()

        if (scheiding <= 0 || id == null) {
            // Ons eigen kanaal, dus een onleesbaar bericht is een defect. De inhoud blijft uit de
            // log: een cacheKey is een pseudoniem van de ontvanger.
            log.warn("Onleesbaar bericht op het aanmeldkanaal genegeerd")

            return
        }

        val sleutel = bericht.substring(0, scheiding)

        if (sleutel == PROBE) {
            // Ook de probes van andere pods komen hier langs; die staan niet in de map.
            probes[id]?.complete(null)

            return
        }

        // Per luisteraar afgeschermd: gooit er één, dan horen de andere tabbladen van dezelfde
        // ontvanger het bericht toch.
        luisteraars[sleutel]?.forEach { luisteraar ->
            try {
                luisteraar.opBericht(id)
            } catch (fout: RuntimeException) {
                log.warnf(fout, "Aanmelding niet aan een gevolgde sessie doorgegeven; de andere gaan door")
            }
        }
    }

    /** Eén probe heen en terug over het kanaal. */
    private fun bewijs(): Uni<Void> {
        val probe = UUID.randomUUID()
        val terug = CompletableFuture<Void>()
        probes[probe] = terug

        return publiceer(PROBE, probe)
            .chain { _ -> Uni.createFrom().completionStage(terug) }
            .ifNoItem().after(PROBE_TIMEOUT)
            .failWith { TimeoutException("probe op het aanmeldkanaal kwam niet terug binnen $PROBE_TIMEOUT") }
            .onTermination().invoke { -> probes.remove(probe) }
    }

    private fun wegGevallen(abonnement: Abonnement, fout: Throwable?) {
        val wasHuidig = huidig.compareAndSet(abonnement, null)

        // Alleen wie het abonnement zelf sluit, en alleen als het nog het huidige was, licht de
        // luisteraars in; een opgegeven of vervangen poging heeft geen luisteraars meer.
        if (!abonnement.sluit() || !wasHuidig) return

        val oorzaak = fout ?: IllegalStateException("Abonnement op aanmeldingen beëindigd")
        log.warnf(oorzaak, "Abonnement op aanmeldingen weggevallen; %d open streams moeten opnieuw verbinden", openStreams())

        luisteraars.values.flatten().forEach { luisteraar ->
            try {
                luisteraar.opStoring(oorzaak)
            } catch (ingelicht: RuntimeException) {
                log.warnf(ingelicht, "Een gevolgde sessie kon niet over de storing ingelicht worden")
            }
        }
    }

    /**
     * Eén poging om te abonneren, met alles wat daarbij hoort. Per poging en niet in velden van de
     * pod: een subscriber of controle die laat binnenkomt, hoort bij de poging die hem startte. Zo
     * kan een opgegeven poging het abonnement van een latere niet overschrijven of afmelden.
     *
     * Na [sluit] ruimt de poging alles op wat nog binnenkomt.
     */
    private inner class Abonnement {

        val gereed: Uni<Void> = Uni.createFrom().deferred { activeer() }.memoize().indefinitely()

        private val subscriber = AtomicReference<ReactiveRedisSubscriber?>(null)

        private val controle = AtomicReference<Cancellable?>(null)

        private val gesloten = AtomicBoolean(false)

        /** Idempotent; geeft `true` aan de aanroeper die daadwerkelijk sloot. */
        fun sluit(): Boolean {
            if (!gesloten.compareAndSet(false, true)) return false

            controle.getAndSet(null)?.cancel()
            subscriber.getAndSet(null)?.let(::meldAf)

            return true
        }

        private fun activeer(): Uni<Void> {
            // Gesloten vóór iemand op [gereed] wachtte: niet alsnog abonneren.
            if (gesloten.get()) return Uni.createFrom().failure(AbonnementGesloten("Abonnement al gesloten"))

            val probe = UUID.randomUUID()
            val teruggezien = CompletableFuture<Void>()
            probes[probe] = teruggezien

            redis.pubsub(String::class.java)
                .subscribe(KANAAL, ::verdeel, { wegGevallen(this, null) }, { fout -> wegGevallen(this, fout) })
                .subscribe().with(::opgeleverd) { teruggezien.completeExceptionally(it) }

            val zender = Multi.createFrom().ticks().every(PROBE_INTERVAL)
                .onOverflow().drop()
                .onItem().transformToUniAndConcatenate { _ -> publiceer(PROBE, probe) }
                .subscribe().with({}, { fout -> teruggezien.completeExceptionally(fout) })

            return Uni.createFrom().completionStage(teruggezien)
                .ifNoItem().after(PROBE_TIMEOUT).fail()
                .onTermination().invoke { ->
                    zender.cancel()
                    probes.remove(probe)
                }
                .onItem().transformToUni { _ ->
                    // Gesloten terwijl de probe onderweg was: dit abonnement is niet meer actief.
                    if (gesloten.get()) {
                        Uni.createFrom().failure(AbonnementGesloten("Abonnement gesloten tijdens het activeren"))
                    } else {
                        startControle()
                        Uni.createFrom().voidItem()
                    }
                }
                .onFailure().invoke { fout ->
                    // Een poging die al gesloten was, is geen storing: de pod stopte of verving haar.
                    if (!gesloten.get()) log.warnf(fout, "Abonneren op aanmeldingen mislukt; volgende poging bij de volgende connection")

                    huidig.compareAndSet(this, null)
                    sluit()
                }
        }

        private fun opgeleverd(nieuw: ReactiveRedisSubscriber) {
            subscriber.set(nieuw)

            // De poging kan al gesloten zijn: de activering liep af, of de pod stopte, voordat de
            // client het abonnement opleverde. Dan zou het een connection openhouden tot de pod stopt.
            if (gesloten.get() && subscriber.compareAndSet(nieuw, null)) {
                log.info("Abonnement op aanmeldingen kwam binnen na het sluiten van de poging; meteen afgemeld")
                meldAf(nieuw)
            }
        }

        /**
         * Laat een staand abonnement zich periodiek bewijzen. De Redis-client meldt het wegvallen van
         * een connection niet altijd; een probe die niet terugkomt wel. Pas na [MAX_GEMISTE_PROBES]
         * op rij geldt het abonnement als weg: één trage Redis-reactie zou anders elke open stream
         * van deze pod tegelijk afbreken.
         */
        private fun startControle() {
            val gemist = AtomicInteger(0)
            val nieuw = Multi.createFrom().ticks().startingAfter(controleInterval).every(controleInterval)
                .onOverflow().drop()
                .onItem().transformToUniAndConcatenate { _ ->
                    bewijs()
                        .onItem().invoke { _ -> gemist.set(0) }
                        .onFailure { gemist.incrementAndGet() < MAX_GEMISTE_PROBES }.recoverWithUni { fout ->
                            log.infof("Probe op het aanmeldkanaal gemist (%s); het abonnement krijgt nog een kans", fout.message)
                            Uni.createFrom().voidItem()
                        }
                }
                .subscribe().with({}, { fout -> wegGevallen(this, fout) })

            controle.set(nieuw)

            // Gesloten na de gesloten-check in `activeer`: sluit() zag deze controle nog niet.
            if (gesloten.get()) controle.getAndSet(null)?.cancel()
        }
    }

    private class Luisteraar(val opBericht: (UUID) -> Unit, val opStoring: (Throwable) -> Unit, val opEinde: () -> Unit)

    companion object {
        // Zelfde versie als de sessie-keys: de cacheKey in het bericht heeft die vorm.
        const val KANAAL = "berichtensessiecache:v3:aanmeldingen"
        private const val SCHEIDING = ' '

        // Kan nooit een cacheKey zijn: die begint met `berichtensessiecache:`.
        private const val PROBE = "probe"
        private val PROBE_INTERVAL: Duration = Duration.ofMillis(200)
        // `internal` zodat de tests hun pauzes hiervan afleiden en niet stil ophouden iets te bewijzen.
        internal val PROBE_TIMEOUT: Duration = Duration.ofSeconds(5)
        internal const val MAX_GEMISTE_PROBES = 2
    }
}
