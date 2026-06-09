package nl.rijksoverheid.moz.fbs.berichtensessiecache.magazijn

import jakarta.annotation.PostConstruct
import jakarta.enterprise.context.ApplicationScoped
import org.eclipse.microprofile.config.inject.ConfigProperty
import org.jboss.logging.Logger
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * Per-magazijn circuit breaker voor de aggregatie-calls. Na [drempel] opeenvolgende
 * availability-storingen (timeout/5xx/netwerk — zie [teltAlsStoring]) bij één magazijn
 * "opent" het circuit voor [openSeconds]: vervolg-calls worden direct overgeslagen
 * (snelle fail i.p.v. wachten op de read-/query-timeout), zodat het aggregatie-bulkhead
 * niet onnodig permits bezet houdt met calls naar een dood magazijn en de
 * gebruiker snel een nette "tijdelijk niet beschikbaar" krijgt.
 *
 * Per-magazijn i.p.v. globaal: een storing bij leverancier A mag leverancier B niet
 * uitsluiten. `@CircuitBreaker`-annotatie is hier niet bruikbaar — [MagazijnClient]
 * wordt programmatisch per OIN gebouwd (geen CDI-bean, dus geen interceptor), en de
 * annotatie kent geen per-instance (per-magazijn) state. Vandaar deze expliciete,
 * los testbare state-machine.
 *
 * De drempel telt over álle ontvangers/sessies heen (per-magazijn state, niet per-ontvanger),
 * dus het circuit opent al na [drempel] storingen systeem-breed — sneller dan per-ontvanger.
 * Bekende beperking: de eerste [drempel] getroffen ophaalsessies wachten nog de volledige
 * magazijn-query-timeout af vóór het circuit opent; pas daarna krijgen volgende sessies de
 * directe "tijdelijk niet beschikbaar". Dat is de bedoelde degradatie t.o.v. de oude situatie
 * waarin élke sessie op de timeout wachtte én de gedeelde worker-pool liet vollopen.
 */
@ApplicationScoped
internal class MagazijnCircuitBreaker(
    @param:ConfigProperty(name = "berichtensessiecache.magazijn-circuit.drempel", defaultValue = "3")
    private val drempel: Int,
    @param:ConfigProperty(name = "berichtensessiecache.magazijn-circuit.open-seconds", defaultValue = "30")
    private val openSeconds: Long,
) {
    private val log = Logger.getLogger(MagazijnCircuitBreaker::class.java)

    private val openNanos = TimeUnit.SECONDS.toNanos(openSeconds)

    // Monotone klok (geen wall-clock): immuun voor NTP-sprongen tijdens een open-venster.
    private val klok: () -> Long = System::nanoTime

    private val breakers = ConcurrentHashMap<String, Breaker>()

    @PostConstruct
    fun valideerConfig() {
        // Fail-fast bij boot (vgl. de timeout-checks in BerichtensessiecacheService): een
        // niet-positieve drempel zou het circuit bij de eerste storing openen, en een
        // niet-positief open-venster maakt openTot altijd-verleden → het circuit gaat nooit
        // echt dicht maar laat permanent half-open proeven door (stille uitschakeling).
        require(drempel > 0) { "berichtensessiecache.magazijn-circuit.drempel ($drempel) moet groter zijn dan 0" }

        require(openSeconds > 0) {
            "berichtensessiecache.magazijn-circuit.open-seconds ($openSeconds) moet groter zijn dan 0"
        }
    }

    private fun breaker(magazijnId: String): Breaker =
        breakers.computeIfAbsent(magazijnId) { Breaker(drempel, openNanos, klok) }

    /** `true` = call mag door; `false` = circuit open, call overslaan. */
    fun toegestaan(magazijnId: String): Boolean = breaker(magazijnId).toegestaan()

    fun meldSucces(magazijnId: String) {
        // Log alleen de daadwerkelijke open→dicht-overgang (herstel), niet elke geslaagde call.
        if (breaker(magazijnId).meldSucces()) {
            log.infof("Circuit gesloten voor magazijn %s: hersteld na storing", magazijnId)
        }
    }

    fun meldFout(magazijnId: String) {
        // Log de dicht→open-overgang op warn: het uitsluiten van een magazijn is het alertbare
        // veerkracht-event. OIN is publiek (geen PII), dus mag voluit in de log.
        if (breaker(magazijnId).meldFout()) {
            log.warnf("Circuit geopend voor magazijn %s na %d opeenvolgende storingen; %ds overslaan", magazijnId, drempel, openSeconds)
        }
    }

    /** Probe afgerond zonder uitspraak over het magazijn (bv. bulkhead-OVERBELAST): geef de
     *  half-open probe vrij zonder de fouten-teller te raken. */
    fun meldOnbeslist(magazijnId: String) = breaker(magazijnId).meldOnbeslist()

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
 * → na het venster één HALF-OPEN probe-call. Die probe heeft drie terminale uitkomsten:
 * succes ([meldSucces]) sluit het circuit, een availability-storing ([meldFout]) heropent het,
 * en een probe-zonder-uitspraak ([meldOnbeslist], bv. bulkhead-OVERBELAST: magazijn niet bereikt)
 * geeft de probe enkel vrij zonder de toestand te wijzigen.
 *
 * De `meld*`-methoden retourneren of déze melding een dicht↔open-grensovergang veroorzaakte,
 * zodat de [MagazijnCircuitBreaker]-wrapper alleen die overgangen logt (niet elke call).
 */
internal class Breaker(
    private val drempel: Int,
    private val openNanos: Long,
    private val klok: () -> Long,
) {
    private var opeenvolgendeFouten = 0

    // 0 = gesloten; anders het nanoTime-moment waarop het open-venster afloopt.
    private var openTot = 0L

    // True zodra de ene toegestane half-open probe-call is uitgegeven en nog geen
    // uitkomst (succes/fout) heeft gemeld — verhindert een tweede gelijktijdige probe.
    private var halfOpenProbeLopend = false

    @Synchronized
    fun toegestaan(): Boolean {
        if (openTot == 0L) return true

        if (klok() < openTot) return false

        // Venster verstreken: precies één half-open probe-call toestaan.
        if (halfOpenProbeLopend) return false

        halfOpenProbeLopend = true

        return true
    }

    /** @return `true` als deze melding een open circuit sloot (herstel-overgang). */
    @Synchronized
    fun meldSucces(): Boolean {
        val wasOpen = openTot != 0L

        opeenvolgendeFouten = 0
        openTot = 0L
        halfOpenProbeLopend = false

        return wasOpen
    }

    /**
     * Een toegestane (half-open) probe-call is afgerond zónder uitspraak over het magazijn —
     * geef alleen de probe vrij. De fouten-teller en het open-venster blijven ongemoeid; omdat
     * het venster al verstreken is, wordt de eerstvolgende call meteen opnieuw als half-open
     * probe toegelaten (geen nieuw [openNanos]-venster). Zonder deze afronding zou een probe die
     * het magazijn niet bereikte (bulkhead-overbelast) het circuit permanent open laten staan.
     */
    @Synchronized
    fun meldOnbeslist() {
        halfOpenProbeLopend = false
    }

    /** @return `true` als deze melding het circuit zojuist opende (dicht→open-overgang). */
    @Synchronized
    fun meldFout(): Boolean {
        halfOpenProbeLopend = false
        opeenvolgendeFouten++

        if (opeenvolgendeFouten < drempel) return false

        val wasGesloten = openTot == 0L
        openTot = klok() + openNanos
        // Cap zodat de teller niet onbegrensd doorloopt tijdens een lang open-venster.
        opeenvolgendeFouten = drempel

        return wasGesloten
    }
}
