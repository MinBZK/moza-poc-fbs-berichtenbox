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

    private val luisteraars = ConcurrentHashMap<String, CopyOnWriteArrayList<Pair<(UUID) -> Unit, (Throwable) -> Unit>>>()

    val meldingen = CopyOnWriteArrayList<Pair<String, UUID>>()

    override fun meld(cacheKey: String, berichtId: UUID): Uni<Void> {
        meldingen += cacheKey to berichtId
        luisteraars[cacheKey]?.forEach { (opBericht, _) -> opBericht(berichtId) }

        return Uni.createFrom().voidItem()
    }

    override fun registreer(
        cacheKey: String,
        opBericht: (UUID) -> Unit,
        opStoring: (Throwable) -> Unit,
    ): Aanmeldingen.Afmelding {
        val luisteraar = opBericht to opStoring
        luisteraars.computeIfAbsent(cacheKey) { CopyOnWriteArrayList() } += luisteraar

        return Aanmeldingen.Afmelding { luisteraars[cacheKey]?.remove(luisteraar) }
    }

    /** Laat de activering falen, zoals een pod die zijn abonnement niet rond krijgt. */
    @Volatile
    var actiefFout: Throwable? = null

    override fun actief(): Uni<Void> =
        actiefFout?.let { Uni.createFrom().failure(it) } ?: Uni.createFrom().voidItem()

    /** Laat het doorgeven wegvallen, zoals een verbroken Redis-abonnement. */
    fun valWeg(fout: Throwable) {
        luisteraars.values.flatten().forEach { (_, opStoring) -> opStoring(fout) }
    }

    fun aantalLuisteraars(cacheKey: String): Int = luisteraars[cacheKey]?.size ?: 0

    fun clear() {
        luisteraars.clear()
        meldingen.clear()
    }
}
