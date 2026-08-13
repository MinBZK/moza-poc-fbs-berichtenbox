package nl.rijksoverheid.moz.fbs.common.fuzzing

import com.code_intelligence.jazzer.api.FuzzedDataProvider

/**
 * WEGWERP-DOEL — HOORT NIET IN MAIN. Bewijsmateriaal bij PR #190, die de
 * `bad-build-check` van de PR- en batch-fuzzronde uitzet.
 *
 * Dit doel dekt de tweede faalklasse: het compileert en levert een geldig
 * fuzz-doel op, maar crasht in `fuzzerInitialize` — dus vóór er ook maar één
 * input gefuzzd is. Precies het geval waarvoor de `bad-build-check` bestond: er
 * ontstaat geen finding, want er is nooit een testcase geweest.
 *
 * Als *Run Fuzzers* hier groen blijft, vangt de PR-ronde deze klasse niet meer en
 * moet `bad-build-check` daar aan blijven staan (of het gat expliciet
 * geaccepteerd worden).
 */
object Pr190CrashBijOpstartFuzzer {

    @JvmStatic
    fun fuzzerInitialize() {
        error("wegwerp-doel PR #190: crasht opzettelijk vóór de eerste input")
    }

    @JvmStatic
    fun fuzzerTestOneInput(data: FuzzedDataProvider) {
        data.consumeString(8)
    }
}
