package nl.rijksoverheid.moz.fbs.berichtensessiecache.berichten

import io.quarkus.redis.datasource.ReactiveRedisDataSource
import io.quarkus.redis.datasource.pubsub.ReactivePubSubCommands.ReactiveRedisSubscriber
import io.smallrye.mutiny.Multi
import io.smallrye.mutiny.Uni
import jakarta.annotation.PreDestroy
import jakarta.enterprise.context.ApplicationScoped
import org.jboss.logging.Logger
import java.time.Duration
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
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
 * [Aanmeldingen.actief] belooft.
 *
 * Valt het abonnement weg, dan krijgen alle luisteraars [Aanmeldingen.registreer]'s `opStoring`
 * en vergeet deze pod het abonnement; de volgende [actief] bouwt een nieuw. Opnieuw verbinden is
 * daarmee de taak van de afnemer, die bij dat verbinden de lijst opnieuw leest.
 */
@ApplicationScoped
internal class RedisAanmeldingen(
    private val redis: ReactiveRedisDataSource,
) : Aanmeldingen {

    private val log = Logger.getLogger(RedisAanmeldingen::class.java)

    private val luisteraars = ConcurrentHashMap<String, MutableSet<Luisteraar>>()

    private val abonnement = AtomicReference<Uni<Void>?>(null)

    private val subscriber = AtomicReference<ReactiveRedisSubscriber?>(null)

    private val probes = ConcurrentHashMap<UUID, CompletableFuture<Void>>()

    override fun meld(cacheKey: String, berichtId: UUID): Uni<Void> = publiceer(cacheKey, berichtId)

    override fun registreer(
        cacheKey: String,
        opBericht: (UUID) -> Unit,
        opStoring: (Throwable) -> Unit,
    ): Aanmeldingen.Afmelding {
        val luisteraar = Luisteraar(opBericht, opStoring)
        luisteraars.computeIfAbsent(cacheKey) { ConcurrentHashMap.newKeySet() }.add(luisteraar)

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

    @PreDestroy
    fun stop() {
        abonnement.set(null)
        subscriber.getAndSet(null)?.unsubscribe()?.subscribe()?.with({}, {})
    }

    private fun abonneer(poging: AtomicReference<Uni<Void>>): Uni<Void> {
        val probe = UUID.randomUUID()
        val teruggezien = CompletableFuture<Void>()
        probes[probe] = teruggezien

        redis.pubsub(String::class.java)
            .subscribe(KANAAL, ::verdeel, { wegGevallen(poging.get(), null) }, { fout -> wegGevallen(poging.get(), fout) })
            .subscribe().with({ subscriber.set(it) }, { teruggezien.completeExceptionally(it) })

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
            .onFailure().invoke { fout ->
                log.warnf(fout, "Abonneren op aanmeldingen mislukt; volgende poging bij de volgende verbinding")
                abonnement.compareAndSet(poging.get(), null)
                subscriber.getAndSet(null)?.unsubscribe()?.subscribe()?.with({}, {})
            }
    }

    private fun publiceer(sleutel: String, id: UUID): Uni<Void> =
        redis.pubsub(String::class.java).publish(KANAAL, "$sleutel$SCHEIDING$id")

    private fun verdeel(bericht: String) {
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

        luisteraars[sleutel]?.forEach { it.opBericht(id) }
    }

    private fun wegGevallen(van: Uni<Void>?, fout: Throwable?) {
        if (van == null || !abonnement.compareAndSet(van, null)) return

        val oorzaak = fout ?: IllegalStateException("Abonnement op aanmeldingen beëindigd")
        log.warnf(oorzaak, "Abonnement op aanmeldingen weggevallen; %d gevolgde sessies moeten opnieuw verbinden", luisteraars.size)

        luisteraars.values.flatten().forEach { it.opStoring(oorzaak) }
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
