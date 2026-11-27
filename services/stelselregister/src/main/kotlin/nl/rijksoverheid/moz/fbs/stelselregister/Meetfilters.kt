package nl.rijksoverheid.moz.fbs.stelselregister

import io.micrometer.core.instrument.Meter
import io.micrometer.core.instrument.Tag
import io.micrometer.core.instrument.config.MeterFilter
import jakarta.enterprise.inject.Produces
import jakarta.inject.Singleton

/**
 * Een CORS-preflight wordt beantwoord vóór de routering, dus zonder endpoint-template, en met een
 * 200. Quarkus valt dan terug op het ruwe pad als `uri`-label: elke aanroeper kan zo met
 * willekeurige paden onbeperkt reeksen aanmaken. Alle preflights tellen daarom als één reeks.
 *
 * De tegenhangers in de andere stelseldiensten zijn identiek en moeten dat blijven; om dezelfde
 * reden als bij `SecurityHeadersRegistratie` staat dit niet in fbs-common.
 */
class Meetfilters {

    @Produces
    @Singleton
    fun preflightZonderPad(): MeterFilter = object : MeterFilter {
        override fun map(id: Meter.Id): Meter.Id {
            if (id.name != HTTP_SERVER_REQUESTS || id.getTag("method") != "OPTIONS") return id

            return id.replaceTags(id.tags.map { if (it.key == "uri") Tag.of("uri", PREFLIGHT_URI) else it })
        }
    }

    companion object {
        private const val HTTP_SERVER_REQUESTS = "http.server.requests"

        const val PREFLIGHT_URI = "CORS_PREFLIGHT"
    }
}
