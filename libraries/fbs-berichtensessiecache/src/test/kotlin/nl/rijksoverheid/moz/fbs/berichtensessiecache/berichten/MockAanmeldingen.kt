package nl.rijksoverheid.moz.fbs.berichtensessiecache.berichten

import io.smallrye.mutiny.Uni
import jakarta.enterprise.context.ApplicationScoped
import jakarta.enterprise.inject.Alternative
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

/**
 * In-memory [Aanmeldingen] voor profielen zonder Redis: verdeelt een melding meteen onder de
 * luisteraars van dezelfde pod. Het verdelen over pods heen dekt de Redis-integratietest.
 */
@Alternative
@ApplicationScoped
internal class MockAanmeldingen : Aanmeldingen {

    private class Luisteraar(val opBericht: (UUID) -> Unit, val opStoring: (Throwable) -> Unit, val opEinde: () -> Unit)

    private val luisteraars = ConcurrentHashMap<String, CopyOnWriteArrayList<Luisteraar>>()

    val meldingen = CopyOnWriteArrayList<Pair<String, UUID>>()

    override fun meld(cacheKey: String, berichtId: UUID): Uni<Void> {
        meldingen += cacheKey to berichtId
        luisteraars[cacheKey]?.forEach { it.opBericht(berichtId) }

        return Uni.createFrom().voidItem()
    }

    override fun registreer(
        cacheKey: String,
        opBericht: (UUID) -> Unit,
        opStoring: (Throwable) -> Unit,
        opEinde: () -> Unit,
    ): Aanmeldingen.Afmelding {
        val luisteraar = Luisteraar(opBericht, opStoring, opEinde)
        luisteraars.computeIfAbsent(cacheKey) { CopyOnWriteArrayList() } += luisteraar

        if (gestopt) opEinde()

        return Aanmeldingen.Afmelding { luisteraars[cacheKey]?.remove(luisteraar) }
    }

    /** Laat de activering falen, zoals een pod die zijn abonnement niet rond krijgt. */
    @Volatile
    var actiefFout: Throwable? = null

    /** Laat de activering nooit afronden, zoals een abonnement dat nog onderweg is. */
    @Volatile
    var actiefHangt = false

    override fun actief(): Uni<Void> = when {
        gestopt -> Uni.createFrom().failure(RedisAanmeldingen.AbonnementGesloten("Deze pod stopt"))
        actiefHangt -> Uni.createFrom().nothing()
        else -> actiefFout?.let { Uni.createFrom().failure(it) } ?: Uni.createFrom().voidItem()
    }

    /** Laat het doorgeven wegvallen, zoals een verbroken Redis-abonnement. */
    fun valWeg(fout: Throwable) {
        luisteraars.values.flatten().forEach { it.opStoring(fout) }
    }

    @Volatile
    private var gestopt = false

    /** Laat de pod stoppen, zoals bij een rolling update; daarna gedraagt hij zich als de echte. */
    fun stop() {
        gestopt = true
        luisteraars.values.flatten().forEach { it.opEinde() }
    }

    fun aantalLuisteraars(cacheKey: String): Int = luisteraars[cacheKey]?.size ?: 0

    fun clear() {
        luisteraars.clear()
        meldingen.clear()
    }
}
