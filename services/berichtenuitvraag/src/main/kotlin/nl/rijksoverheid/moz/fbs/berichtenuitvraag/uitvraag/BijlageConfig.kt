package nl.rijksoverheid.moz.fbs.berichtenuitvraag.uitvraag

import io.smallrye.config.ConfigMapping
import io.smallrye.config.WithDefault
import java.time.Duration

/**
 * Grenzen op een gestreamde bijlage-download: een bijlage van [BijlageInhoud.BEGIN_BYTES] of meer
 * houdt een worker-thread en een magazijn-connection vast zolang de afnemer leest. Gevalideerd bij
 * het opstarten door [Bijlagestreams].
 */
@ConfigMapping(prefix = "berichtenuitvraag.bijlage")
interface BijlageConfig {

    /**
     * Hoe lang een overdracht mag stilliggen, aan beide kanten. Aan de kant van het magazijn sluit de
     * bijlage-client een connection die zo lang niets ontvangt; de read-timeout stopt namelijk zodra
     * de headers binnen zijn. Aan de kant van de afnemer reset de uitvraag de download als één write
     * zo lang op de afnemer wacht. Boven 100 s helpt een hogere waarde niet tegen een stilgevallen
     * magazijn: de REST-client breekt een read dan al af.
     */
    @WithDefault("PT60S")
    fun maxStilstand(): Duration

    /** Gelijktijdig gestreamde downloads op deze pod. */
    @WithDefault("20")
    fun maxStreams(): Int

    /** Gelijktijdig gestreamde downloads per ontvanger op deze pod. */
    @WithDefault("3")
    fun maxStreamsPerOntvanger(): Int
}
