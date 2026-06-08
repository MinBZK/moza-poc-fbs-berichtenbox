package nl.rijksoverheid.moz.fbs.berichtensessiecache.magazijn

import jakarta.enterprise.context.ApplicationScoped
import org.eclipse.microprofile.config.inject.ConfigProperty
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * Per-magazijn circuit breaker voor de aggregatie-calls. Na [drempel] opeenvolgende
 * availability-storingen (timeout/5xx/netwerk — zie [teltAlsStoring]) bij één magazijn
 * "opent" het circuit voor [openSeconds]: vervolg-calls worden direct overgeslagen
 * (snelle fail i.p.v. wachten op de read-/query-timeout), zodat de begrensde
 * aggregatie-pool niet vol blijft lopen met calls naar een dood magazijn en de
 * gebruiker snel een nette "tijdelijk niet beschikbaar" krijgt.
 *
 * Per-magazijn i.p.v. globaal: een storing bij leverancier A mag leverancier B niet
 * uitsluiten. `@CircuitBreaker`-annotatie is hier niet bruikbaar — [MagazijnClient]
 * wordt programmatisch per OIN gebouwd (geen CDI-bean, dus geen interceptor), en de
 * annotatie kent geen per-instance (per-magazijn) state. Vandaar deze expliciete,
 * los testbare state-machine.
 */
@ApplicationScoped
internal class MagazijnCircuitBreaker(
    @param:ConfigProperty(name = "berichtensessiecache.magazijn-circuit.drempel", defaultValue = "3")
    private val drempel: Int,
    @param:ConfigProperty(name = "berichtensessiecache.magazijn-circuit.open-seconds", defaultValue = "30")
    private val openSeconds: Long,
) {
    private val openNanos = TimeUnit.SECONDS.toNanos(openSeconds)

    // Monotone klok (geen wall-clock): immuun voor NTP-sprongen tijdens een open-venster.
    private val klok: () -> Long = System::nanoTime

    private val breakers = ConcurrentHashMap<String, Breaker>()

    private fun breaker(magazijnId: String): Breaker =
        breakers.computeIfAbsent(magazijnId) { Breaker(drempel, openNanos, klok) }

    /** `true` = call mag door; `false` = circuit open, call overslaan. */
    fun toegestaan(magazijnId: String): Boolean = breaker(magazijnId).toegestaan()

    fun meldSucces(magazijnId: String) = breaker(magazijnId).meldSucces()

    fun meldFout(magazijnId: String) = breaker(magazijnId).meldFout()

    /**
     * Wist alle circuit-state. Alleen voor test-isolatie: de breaker is een singleton die over
     * @QuarkusTest-klassen (zelfde profiel/JVM) heen leeft, dus een fout-injecterende test zou
     * anders het circuit-venster van een volgende test vervuilen.
     */
    internal fun herstelAlles() = breakers.clear()
}

/**
 * Pure, thread-veilige state-machine voor één magazijn. Top-level + [klok]-injecteerbaar
 * zodat de open/half-open-overgangen deterministisch (zonder `Thread.sleep`) te pinnen zijn.
 *
 * Toestanden: CLOSED (telt opeenvolgende fouten) → OPEN ([openNanos]) bij ≥ [drempel] fouten
 * → na het venster één HALF-OPEN proef-call; die proef sluit het circuit bij succes of
 * heropent het bij fout.
 */
internal class Breaker(
    private val drempel: Int,
    private val openNanos: Long,
    private val klok: () -> Long,
) {
    private var opeenvolgendeFouten = 0

    // 0 = gesloten; anders het nanoTime-moment waarop het open-venster afloopt.
    private var openTot = 0L

    // True zodra de ene toegestane half-open proef-call is uitgegeven en nog geen
    // uitkomst (succes/fout) heeft gemeld — verhindert een tweede gelijktijdige proef.
    private var halfOpenProefLopend = false

    @Synchronized
    fun toegestaan(): Boolean {
        if (openTot == 0L) return true

        if (klok() < openTot) return false

        // Venster verstreken: precies één half-open proef-call toestaan.
        if (halfOpenProefLopend) return false

        halfOpenProefLopend = true

        return true
    }

    @Synchronized
    fun meldSucces() {
        opeenvolgendeFouten = 0
        openTot = 0L
        halfOpenProefLopend = false
    }

    @Synchronized
    fun meldFout() {
        halfOpenProefLopend = false
        opeenvolgendeFouten++

        if (opeenvolgendeFouten >= drempel) {
            openTot = klok() + openNanos
            // Cap zodat de teller niet onbegrensd doorloopt tijdens een lang open-venster.
            opeenvolgendeFouten = drempel
        }
    }
}
