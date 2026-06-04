package nl.rijksoverheid.moz.fbs.berichtensessiecache

import nl.rijksoverheid.moz.fbs.berichtensessiecache.berichten.BerichtensessiecacheService
import nl.rijksoverheid.moz.fbs.berichtensessiecache.berichten.RedisBerichtenCache
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.fail
import java.lang.reflect.Modifier
import java.util.concurrent.ConcurrentMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/**
 * Borgt dat de facade-keten staatloos blijft: alle sessiestaat hoort in Redis
 * (gedeelde store), zodat meerdere pods dezelfde sessies kunnen bedienen en een
 * pod-restart geen sessiegedrag verandert. Een veld dat muteerbare staat in de
 * JVM introduceert (`var`, atomics, concurrent collections) zou die garantie
 * stil breken — deze guard laat zo'n wijziging hard falen met een verwijzing
 * naar de ontwerpkeuze.
 *
 * Buiten scope: [nl.rijksoverheid.moz.fbs.berichtensessiecache.magazijn.ProfielMagazijnResolver]
 * heeft bewust een per-pod Caffeine-cache (absorbeert Profiel-load; geen
 * sessiestaat — zie de KDoc daar) en
 * [nl.rijksoverheid.moz.fbs.berichtensessiecache.magazijn.MagazijnClientFactory]
 * bouwt zijn client-maps eenmalig uit config (`lateinit`, immutable na init).
 */
class StaatloosheidGuardTest {

    private val bewaakteKlassen = listOf(
        SessiecacheImpl::class.java,
        BerichtensessiecacheService::class.java,
        RedisBerichtenCache::class.java,
    )

    private val muteerbareHouders = listOf(
        AtomicInteger::class.java,
        AtomicLong::class.java,
        AtomicBoolean::class.java,
        AtomicReference::class.java,
        ConcurrentMap::class.java,
        java.util.concurrent.ConcurrentLinkedQueue::class.java,
    )

    @Test
    fun `facade-keten heeft geen muteerbare instance-velden`() {
        val schendingen = buildList {
            bewaakteKlassen.forEach { klasse ->
                klasse.declaredFields
                    .filterNot { Modifier.isStatic(it.modifiers) }
                    // Kotlin synthetics (bv. $jacocoData bij instrumentatie) zijn geen eigen staat.
                    .filterNot { it.isSynthetic }
                    .forEach { veld ->
                        if (!Modifier.isFinal(veld.modifiers)) {
                            add("${klasse.simpleName}.${veld.name} is niet final (var of lateinit)")
                        }

                        muteerbareHouders.firstOrNull { it.isAssignableFrom(veld.type) }?.let {
                            add("${klasse.simpleName}.${veld.name} is een muteerbare houder (${veld.type.simpleName})")
                        }
                    }
            }
        }

        if (schendingen.isNotEmpty()) {
            fail(
                "In-JVM mutable state gevonden in de sessiecache-facade-keten; alle sessiestaat hoort " +
                    "in Redis (multi-pod-correctheid):\n - " + schendingen.joinToString("\n - "),
            )
        }
    }
}
