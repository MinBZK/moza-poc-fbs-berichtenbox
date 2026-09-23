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
     * wegvalt; wat daarna wordt aangemeld komt niet meer door.
     */
    fun registreer(cacheKey: String, opBericht: (UUID) -> Unit, opStoring: (Throwable) -> Unit): Afmelding

    /** Slaagt zodra deze pod aanmeldingen ontvangt; bouwt het abonnement zo nodig op. */
    fun actief(): Uni<Void>

    fun interface Afmelding {
        fun afmelden()
    }
}

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
 * `berichtensessiecache.aanmeldingen-controle`, want een verbinding kan ook stil wegvallen.
 *
 * Valt het abonnement weg, dan krijgen alle luisteraars [Aanmeldingen.registreer]'s `opStoring`
 * en vergeet deze pod het abonnement; de volgende [actief] bouwt een nieuw. Opnieuw verbinden is
 * daarmee de taak van de afnemer, die bij dat verbinden de lijst opnieuw leest.
 */
@ApplicationScoped
internal class RedisAanmeldingen(
    private val redis: ReactiveRedisDataSource,
    // Hoe vaak een staand abonnement zich opnieuw bewijst. Een verbinding die stil wegvalt, zonder
    // dat de client het merkt, levert anders een stream op die hartslagen blijft geven maar geen
    // enkel bericht meer. Eén PUBLISH per pod per tussenpoos.
    @param:ConfigProperty(name = "berichtensessiecache.aanmeldingen-controle", defaultValue = "PT30S")
    private val controleInterval: Duration,
) : Aanmeldingen {

    private val log = Logger.getLogger(RedisAanmeldingen::class.java)

    private val luisteraars = ConcurrentHashMap<String, MutableSet<Luisteraar>>()

    private val abonnement = AtomicReference<Uni<Void>?>(null)

    private val subscriber = AtomicReference<ReactiveRedisSubscriber?>(null)

    private val probes = ConcurrentHashMap<UUID, CompletableFuture<Void>>()

    private val controle = AtomicReference<Cancellable?>(null)

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
    ): Aanmeldingen.Afmelding {
        val luisteraar = Luisteraar(opBericht, opStoring)

        // Toevoegen binnen `compute`, niet op wat `computeIfAbsent` teruggeeft: dan kan het afmelden
        // van de laatste andere luisteraar de set er net tussenuit halen, en belandt deze in een set
        // die nergens meer aan hangt — een stream die nooit meer een aanmelding krijgt.
        luisteraars.compute(cacheKey) { _, set -> (set ?: ConcurrentHashMap.newKeySet()).apply { add(luisteraar) } }

        return Aanmeldingen.Afmelding {
            luisteraars.computeIfPresent(cacheKey) { _, set ->
                set.remove(luisteraar)
                set.ifEmpty { null }
            }
        }
    }

    override fun actief(): Uni<Void> {
        abonnement.get()?.let { return it }

        val nieuw = AtomicReference<Uni<Void>>()
        val poging = Uni.createFrom().deferred { abonneer(nieuw) }.memoize().indefinitely()
        nieuw.set(poging)

        // Twee gelijktijdige eerste connections: de verliezer gebruikt het abonnement van de
        // winnaar en abonneert zelf nooit, want `poging` is lui.
        return if (abonnement.compareAndSet(null, poging)) poging else abonnement.get() ?: poging
    }

    /**
     * Eerst de luisteraars, dan pas afmelden. Een open stream is voor de server een lopend verzoek,
     * dus tijdens een rolling update blijft hij bestaan tot de afsluit-termijn verstrijkt — met een
     * kanaal dat al dood is. Zo verbinden de berichtenboxen meteen opnieuw, bij een pod die nog
     * leeft, in plaats van in dat venster aanmeldingen te missen.
     */
    @PreDestroy
    fun stop() {
        abonnement.set(null)
        controle.getAndSet(null)?.cancel()
        waarschuwLuisteraars(IllegalStateException("Deze pod stopt; verbind opnieuw"))
        subscriber.getAndSet(null)?.let(::meldAf)
    }

    private fun abonneer(poging: AtomicReference<Uni<Void>>): Uni<Void> {
        val probe = UUID.randomUUID()
        val teruggezien = CompletableFuture<Void>()
        val opgegeven = AtomicBoolean(false)
        probes[probe] = teruggezien

        redis.pubsub(String::class.java)
            .subscribe(KANAAL, ::verdeel, { wegGevallen(poging.get(), null) }, { fout -> wegGevallen(poging.get(), fout) })
            .subscribe().with(
                { nieuw ->
                    subscriber.set(nieuw)

                    // De poging kan al opgegeven zijn: de activering liep af voordat de client het
                    // abonnement opleverde. Dan hoort dit abonnement bij niemand meer en zou het een
                    // connection openhouden tot de pod stopt.
                    if (opgegeven.get() && subscriber.compareAndSet(nieuw, null)) {
                        log.warn("Abonnement op aanmeldingen kwam binnen na het opgeven van de poging; meteen afgemeld")
                        meldAf(nieuw)
                    }
                },
                { teruggezien.completeExceptionally(it) },
            )

        val zender = Multi.createFrom().ticks().every(PROBE_INTERVAL)
            .onOverflow().drop()
            .onItem().transformToUniAndConcatenate { _ -> publiceer(PROBE, probe) }
            .subscribe().with({}, { fout -> teruggezien.completeExceptionally(fout) })

        return Uni.createFrom().completionStage(teruggezien)
            .ifNoItem().after(ACTIVERING_TIMEOUT).fail()
            .onTermination().invoke { ->
                zender.cancel()
                probes.remove(probe)
            }
            .onItem().invoke { _ -> startControle(poging) }
            .onFailure().invoke { fout ->
                log.warnf(fout, "Abonneren op aanmeldingen mislukt; volgende poging bij de volgende verbinding")
                opgegeven.set(true)
                abonnement.compareAndSet(poging.get(), null)
                subscriber.getAndSet(null)?.let(::meldAf)
            }
    }

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

    /**
     * Laat een staand abonnement zich periodiek bewijzen. De Redis-client meldt het wegvallen van een
     * verbinding niet altijd; een probe die niet terugkomt wel.
     */
    private fun startControle(poging: AtomicReference<Uni<Void>>) {
        val nieuw = Multi.createFrom().ticks().startingAfter(controleInterval).every(controleInterval)
            .onOverflow().drop()
            .onItem().transformToUniAndConcatenate { _ -> bewijs() }
            .subscribe().with(
                {},
                { fout ->
                    // Een verbinding die zelf niets meldde, staat mogelijk nog half open.
                    wegGevallen(poging.get(), fout)
                    subscriber.getAndSet(null)?.let(::meldAf)
                },
            )

        controle.getAndSet(nieuw)?.cancel()
    }

    /** Eén probe heen en terug over het kanaal. */
    private fun bewijs(): Uni<Void> {
        val probe = UUID.randomUUID()
        val terug = CompletableFuture<Void>()
        probes[probe] = terug

        return publiceer(PROBE, probe)
            .chain { _ -> Uni.createFrom().completionStage(terug) }
            .ifNoItem().after(ACTIVERING_TIMEOUT)
            .failWith { TimeoutException("probe op het aanmeldkanaal kwam niet terug binnen $ACTIVERING_TIMEOUT") }
            .onTermination().invoke { -> probes.remove(probe) }
    }

    private fun wegGevallen(van: Uni<Void>?, fout: Throwable?) {
        if (van == null || !abonnement.compareAndSet(van, null)) return

        controle.getAndSet(null)?.cancel()

        val oorzaak = fout ?: IllegalStateException("Abonnement op aanmeldingen beëindigd")
        log.warnf(oorzaak, "Abonnement op aanmeldingen weggevallen; %d gevolgde sessies moeten opnieuw verbinden", luisteraars.size)

        waarschuwLuisteraars(oorzaak)
    }

    private fun waarschuwLuisteraars(oorzaak: Throwable) {
        luisteraars.values.flatten().forEach { luisteraar ->
            try {
                luisteraar.opStoring(oorzaak)
            } catch (fout: RuntimeException) {
                log.warnf(fout, "Een gevolgde sessie kon niet over de storing ingelicht worden")
            }
        }
    }

    private class Luisteraar(val opBericht: (UUID) -> Unit, val opStoring: (Throwable) -> Unit)

    companion object {
        // Zelfde versie als de sessie-keys: de cacheKey in het bericht heeft die vorm.
        const val KANAAL = "berichtensessiecache:v3:aanmeldingen"
        private const val SCHEIDING = ' '

        // Kan nooit een cacheKey zijn: die begint met `berichtensessiecache:`.
        private const val PROBE = "probe"
        private val PROBE_INTERVAL: Duration = Duration.ofMillis(200)
        private val ACTIVERING_TIMEOUT: Duration = Duration.ofSeconds(5)
    }
}
