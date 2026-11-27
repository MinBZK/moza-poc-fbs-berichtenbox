package nl.rijksoverheid.moz.fbs.stelselregister.stelseldocument

import io.micrometer.core.instrument.Gauge
import io.micrometer.core.instrument.MeterRegistry
import io.quarkus.runtime.StartupEvent
import jakarta.enterprise.context.ApplicationScoped
import jakarta.enterprise.event.Observes
import nl.rijksoverheid.moz.fbs.magazijnregister.Magazijnregister

/**
 * Meetwaarden over het geldende stelseldocument en de ondertekensleutel, zodat een melding kan
 * volgen vóórdat afnemers een verlopen document weigeren. Tijdstippen in seconden sinds 1970;
 * 0 betekent dat er geen geldend exemplaar is.
 *
 * Elke gauge leest bij de scrape de levende toestand, dus geen eigen bijgehouden kopie die kan
 * achterlopen. Alleen aantallen en tijdstippen: een label per magazijn hangt aan het besluit over
 * labels per organisatie.
 */
@ApplicationScoped
class StelseldocumentMeting(
    private val registry: MeterRegistry,
    private val uitgegeven: UitgegevenStelseldocument,
    private val sleutelbron: Sleutelbron,
    private val register: Magazijnregister,
) {

    fun registreer(@Observes start: StartupEvent) {
        Gauge.builder("fbs.stelseldocument.geldig.tot", uitgegeven) { tijdstip(it.geldend()?.verlooptOp?.epochSecond) }
            .description("Einde van de geldigheid van het geldende stelseldocument (seconden sinds 1970; 0 = geen)")
            .register(registry)
        Gauge.builder("fbs.stelseldocument.uitgegeven.op", uitgegeven) { tijdstip(it.geldend()?.uitgegevenOp?.epochSecond) }
            .description("Uitgifte van het geldende stelseldocument (seconden sinds 1970; 0 = geen)")
            .register(registry)
        Gauge.builder("fbs.stelseldocument.organisaties", uitgegeven) { (it.geldend()?.aantalOrganisaties ?: 0).toDouble() }
            .description("Organisaties in het geldende stelseldocument")
            .register(registry)
        Gauge.builder("fbs.stelseldocument.sleutel.geldig.tot", sleutelbron) { it.sleutel.geldigTot.epochSecond.toDouble() }
            .description("Het vroegste einde van de certificaatketen van de ondertekensleutel (seconden sinds 1970)")
            .tag("herkomst", sleutelbron.sleutel.herkomst.name.lowercase())
            .register(registry)
        Gauge.builder("fbs.magazijnregister.magazijnen", register) { it.alle().size.toDouble() }
            .description("Ingeschreven magazijnen in het register van deze dienst")
            .register(registry)
    }

    private fun tijdstip(epochSeconde: Long?): Double = (epochSeconde ?: 0L).toDouble()
}
