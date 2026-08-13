package nl.rijksoverheid.moz.fbs.common.fuzzing

import com.code_intelligence.jazzer.api.FuzzedDataProvider

/**
 * WEGWERP-DOEL — HOORT NIET IN MAIN. Bewijsmateriaal bij PR #190, die de
 * `bad-build-check` van de PR- en batch-fuzzronde uitzet. Die check startte elk
 * fuzz-doel apart op; de claim is dat `run_fuzzers` een kapot doel daarna alsnog
 * laat vallen, waardoor de check overbodig is.
 *
 * Dit doel dekt de eerste van twee faalklassen: het compileert, maar crasht op de
 * eerste input. Verwachting: Jazzer rapporteert binnen een seconde een finding en
 * de stap *Run Fuzzers* wordt rood.
 *
 * De tweede klasse — een doel dat crasht vóór de eerste input — staat in een
 * losse commit op deze branch, omdat een finding uit dit doel dat gedrag zou
 * maskeren.
 */
object Pr190CrashBijEersteInputFuzzer {

    @JvmStatic
    fun fuzzerTestOneInput(data: FuzzedDataProvider) {
        data.consumeString(8)

        error("wegwerp-doel PR #190: crasht opzettelijk op de eerste input")
    }
}
