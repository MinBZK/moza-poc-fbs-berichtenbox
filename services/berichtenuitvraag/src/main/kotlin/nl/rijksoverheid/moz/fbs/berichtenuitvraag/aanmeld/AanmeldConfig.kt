package nl.rijksoverheid.moz.fbs.berichtenuitvraag.aanmeld

import io.smallrye.config.ConfigMapping
import io.smallrye.config.WithDefault
import java.time.Duration

@ConfigMapping(prefix = "aanmeld")
interface AanmeldConfig {

    fun deduplicatie(): Deduplicatie

    interface Deduplicatie {
        /**
         * Bewaartijd van een idempotentie-marker. Moet ruim boven het retry-venster
         * van de publicatie-stream liggen (magazijn-backoff-plafond PT1H) zodat een
         * her-aflevering binnen dat venster als duplicaat herkend wordt.
         */
        @WithDefault("PT24H")
        fun ttl(): Duration

        /**
         * Begrensde blocking-await op de Redis-idempotentie-operaties (NX-write/delete).
         * Lokale Redis-latency, dus dezelfde orde als de overige cache-awaits; overschrijden
         * → 503 (transient) zodat de publicatie-stream opnieuw aflevert. Moet positief zijn —
         * `atMost(ZERO)` wacht onbegrensd en zou de bescherming stil uitschakelen.
         */
        @WithDefault("PT2S")
        fun redisAwaitTimeout(): Duration
    }
}
