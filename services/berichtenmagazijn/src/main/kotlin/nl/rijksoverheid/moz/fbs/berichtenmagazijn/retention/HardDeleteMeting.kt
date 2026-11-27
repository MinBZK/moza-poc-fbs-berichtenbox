package nl.rijksoverheid.moz.fbs.berichtenmagazijn.retention

import io.micrometer.core.instrument.Counter
import io.micrometer.core.instrument.Gauge
import io.micrometer.core.instrument.MeterRegistry
import io.quarkus.runtime.Startup
import jakarta.enterprise.context.ApplicationScoped
import java.time.Instant
import java.util.concurrent.atomic.AtomicLong

/**
 * Meetwaarden van het definitief wissen, zodat een stilgevallen of falende ronde opvalt: anders
 * blijven verwijderde berichten langer staan dan de bewaartermijn, en merkt niemand het.
 *
 * Alleen tellingen en tijdstippen, geen labels met bericht- of ontvangergegevens. De gauges gelden
 * per pod; bij meerdere pods geeft `max()` over de pods het laatste moment van het magazijn.
 *
 * `@Startup`, zodat elke reeks vanaf de start op 0 staat. Anders ontstaat de bean pas bij de eerste
 * ronde, en ziet `increase()` de fouten van die ronde niet.
 */
@Startup
@ApplicationScoped
class HardDeleteMeting(registry: MeterRegistry) {

    private val laatsteRonde = AtomicLong()

    private val laatsteGeslaagdeRonde = AtomicLong()

    private val wachtend = AtomicLong()

    private val verwijderd: Counter = Counter.builder("fbs.retentie.verwijderd")
        .description("Berichten die het definitief wissen heeft verwijderd")
        .register(registry)

    private val fouten: Map<Fout, Counter> = Fout.entries.associateWith { fout ->
        Counter.builder("fbs.retentie.fouten")
            .description("Fouten bij het definitief wissen, per soort")
            .tag("soort", fout.label)
            .register(registry)
    }

    init {
        Gauge.builder("fbs.retentie.laatste.ronde", laatsteRonde) { it.get().toDouble() }
            .description("Einde van de laatste ronde, ook met fouten, sinds 1970 (0 = nog geen)")
            .baseUnit(SECONDEN)
            .register(registry)
        Gauge.builder("fbs.retentie.laatste.geslaagde.ronde", laatsteGeslaagdeRonde) { it.get().toDouble() }
            .description("Einde van de laatste ronde zonder fouten, sinds 1970 (0 = nog geen)")
            .baseUnit(SECONDEN)
            .register(registry)
        Gauge.builder("fbs.retentie.wachtend", wachtend) { it.get().toDouble() }
            .description("Berichten die na de laatste ronde nog aan beide drempels voldeden")
            .register(registry)
    }

    // Per bericht en niet aan het eind van de ronde: breekt die halverwege af, dan blijft wat al
    // gewist was, en welke logboekregistraties mislukten, toch in de meting.
    fun verwijderd() = verwijderd.increment()

    fun wisfout() = fouten.getValue(Fout.WISSEN).increment()

    fun logboekfout() = fouten.getValue(Fout.LOGBOEK).increment()

    /** [wachtendNa] is `null` als de telling na de ronde zelf faalde; de vorige waarde blijft dan staan. */
    fun ronde(resultaat: HardDeleteService.RunResultaat, einde: Instant, wachtendNa: Long?) {
        laatsteRonde.set(einde.epochSecond)

        if (resultaat.fouten == 0 && resultaat.ldvFouten == 0) laatsteGeslaagdeRonde.set(einde.epochSecond)

        if (wachtendNa != null) wachtend.set(wachtendNa)
    }

    /** Een ronde die als geheel afbrak, bijvoorbeeld omdat de database onbereikbaar was. */
    fun rondeAfgebroken() {
        fouten.getValue(Fout.RONDE).increment()
    }

    private companion object {
        /** Prometheus plakt de eenheid achter de naam: `..._laatste_ronde_seconds`. */
        const val SECONDEN = "seconds"
    }

    private enum class Fout(val label: String) {
        WISSEN("wissen"),
        LOGBOEK("logboek"),
        RONDE("ronde"),
    }
}
