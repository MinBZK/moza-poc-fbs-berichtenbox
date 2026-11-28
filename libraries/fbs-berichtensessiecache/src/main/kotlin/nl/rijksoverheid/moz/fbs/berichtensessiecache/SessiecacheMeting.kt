package nl.rijksoverheid.moz.fbs.berichtensessiecache

import io.micrometer.core.instrument.Counter
import io.micrometer.core.instrument.Metrics

/**
 * De teller naast het alert `opruimen_na_schrijven`: gegevens die de vangnet-TTL straks zonder
 * logregel wist. Dezelfde teller als de logboekwaarschuwingen van de dienst die deze library
 * gebruikt; die dienst zet hem bij de start op 0. Publiek, want de dienst noemt de code.
 */
object SessiecacheMeting {

    const val OPRUIMEN_NA_SCHRIJVEN = "opruimen_na_schrijven"

    internal fun opruimenNaSchrijven() {
        Counter.builder("fbs.logboek.waarschuwing")
            .tag("code", OPRUIMEN_NA_SCHRIJVEN)
            .register(Metrics.globalRegistry)
            .increment()
    }
}
