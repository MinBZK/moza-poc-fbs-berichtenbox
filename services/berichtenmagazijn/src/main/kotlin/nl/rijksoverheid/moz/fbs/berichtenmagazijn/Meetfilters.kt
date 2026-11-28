package nl.rijksoverheid.moz.fbs.berichtenmagazijn

import io.micrometer.core.instrument.Meter
import io.micrometer.core.instrument.Tag
import io.micrometer.core.instrument.Tags
import io.micrometer.core.instrument.config.MeterFilter
import jakarta.enterprise.inject.Produces
import jakarta.inject.Singleton
import org.eclipse.microprofile.config.inject.ConfigProperty

/**
 * Een `OPTIONS`-verzoek krijgt antwoord zonder endpoint-template, en met een 200: van de
 * automatische OPTIONS-afhandeling van RESTEasy, of van de CORS-handler bij een preflight. Quarkus
 * valt dan terug op het ruwe pad als `uri`-label, en elke aanroeper kan zo met willekeurige
 * bericht-id's onbeperkt reeksen aanmaken. Alle OPTIONS-verzoeken tellen daarom als één reeks.
 *
 * De OPTIONS-filter is in de andere stelseldiensten identiek en moet dat blijven; [herkomst] zet hier
 * ook `magazijn`. Om dezelfde reden als bij `SecurityHeadersRegistratie` staat dit niet in fbs-common.
 */
class Meetfilters {

    @Produces
    @Singleton
    fun optionsZonderPad(): MeterFilter = object : MeterFilter {
        override fun map(id: Meter.Id): Meter.Id {
            if (id.name != HTTP_SERVER_REQUESTS || id.getTag("method") != "OPTIONS") return id

            return id.replaceTags(id.tags.map { if (it.key == "uri") Tag.of("uri", OPTIONS_URI) else it })
        }
    }

    /**
     * Elke reeks draagt de dienst, de omgeving en het magazijn. De dashboards en alertregels in
     * `beheer/` kiezen daarop, en zo zijn ze niet afhankelijk van de labels die een scraper zelf
     * toevoegt: die verschillen per platform, en op ZAD liggen ze niet bij ons. `omgeving` is de
     * deploymentnaam (`DEPLOYMENT_NAME` op ZAD: `test` of `pr-<n>`), zodat een regel previews kan
     * uitsluiten.
     *
     * `magazijn` is de eigen OIN: twee magazijnen in één omgeving hebben elk een eigen database, en
     * zonder dit label zou een regel die per omgeving het minimum of maximum neemt, een vastgelopen
     * magazijn achter een gezond magazijn verstoppen. Eén waarde per pod, en publiek.
     */
    @Produces
    @Singleton
    fun herkomst(
        @ConfigProperty(name = "fbs.meetgegevens.omgeving") omgeving: String,
        @ConfigProperty(name = "magazijn.publicatie.organisatie.oin") oin: String,
    ): MeterFilter =
        MeterFilter.commonTags(Tags.of(Tag.of("dienst", DIENST), Tag.of("omgeving", omgeving), Tag.of("magazijn", oin)))

    companion object {
        private const val HTTP_SERVER_REQUESTS = "http.server.requests"

        /** De waarde van het label `dienst`; de dashboards en regels in `beheer/` noemen hem letterlijk. */
        const val DIENST = "berichtenmagazijn"

        const val OPTIONS_URI = "OPTIONS"
    }
}
