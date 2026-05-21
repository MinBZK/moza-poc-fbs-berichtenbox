package nl.rijksoverheid.moz.fbs.berichtenmagazijn.publicatie

import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

/**
 * Begrenst hoe vaak een log-regel per sleutel mag vuren binnen een
 * cooldown-venster. Doel: tijdens config-removal-migraties of vergelijkbare
 * config-drift voorkomen dat een per-claim warn-log een storm produceert
 * (één log per pollronde × elke openstaande claim voor het verdwenen doel).
 *
 * Atomicity: gebruikt [ConcurrentHashMap.compute] zodat twee threads die
 * gelijktijdig de eerste warn proberen te emit'en deterministisch tot
 * exact één emit komen — `compute` synchroniseert per key. Bij de huidige
 * `Scheduled.ConcurrentExecution.SKIP`-scheduler is gelijktijdigheid
 * onwaarschijnlijk; deze atomiciteit hardt tegen toekomstige refactors
 * naar `PROCEED` of multi-thread aanroepen.
 *
 * **Bounded cardinaliteit verwacht**: de interne map groeit monotoon met
 * unieke `K`-waarden en kent geen eviction. Gebruik dit type alleen voor
 * sleutels met een kleine, eindige domeinruimte (bv. `Publicatiedoel`,
 * config-driven downstream-keys). Voor high-cardinality keys (UUID,
 * client-IP, request-path) zou deze map onbegrensd groeien — wrap dan
 * in een size-bounded cache (Caffeine, etc.) i.p.v. dit type.
 *
 * @property cooldown minimaal interval tussen twee emits voor dezelfde key.
 *   Negatieve of nul-Duration zou `magEmitten` altijd `true` laten retourneren
 *   (cooldown gepasseerd vóór de eerste call) — vandaar de fail-fast `require`.
 * @property clock klok-bron voor de cooldown-timing (testbaar via fixed-Clock).
 *
 * Type-parameter `K : Any` lift de `ConcurrentHashMap`-null-key-restrictie
 * van runtime naar compile-time, en accepteert elk identifier-type met
 * value-semantiek (`Publicatiedoel` in deze codebase) — zo voorkomt de
 * map-key-discipline dat een toekomstige refactor per ongeluk een rauwe
 * String of unrelated identifier doorgeeft.
 */
class LogStormLimiter<K : Any>(
    private val cooldown: Duration,
    private val clock: Clock,
) {

    init {
        require(!cooldown.isNegative && !cooldown.isZero) {
            "cooldown moet positief zijn (negatief/nul zou magEmitten altijd true laten retourneren); was=$cooldown"
        }
    }

    private val laatsteEmit = ConcurrentHashMap<K, Instant>()

    /**
     * Retourneert `true` als de caller mag loggen voor [sleutel]; in dat geval
     * is de emit-timestamp atomair bijgewerkt zodat een gelijktijdige tweede
     * caller `false` krijgt.
     */
    fun magEmitten(sleutel: K): Boolean {
        val nu = clock.instant()
        var mag = false
        laatsteEmit.compute(sleutel) { _, vorige ->
            // compute-lambda draait synchroon en is per-key atomair binnen
            // ConcurrentHashMap: parallelle eerste-call-callers krijgen
            // deterministisch exact één `mag = true`.
            // We kunnen niet op Instant-equality vertrouwen (twee
            // `clock.instant()`-calls met dezelfde fixed-time geven gelijke
            // Instants); daarom signaleren we via een side-effect flag.
            if (vorige == null || Duration.between(vorige, nu) >= cooldown) {
                mag = true
                nu
            } else {
                vorige
            }
        }
        return mag
    }
}
