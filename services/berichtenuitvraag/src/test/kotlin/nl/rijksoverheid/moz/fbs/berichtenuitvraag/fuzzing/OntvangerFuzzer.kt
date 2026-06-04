package nl.rijksoverheid.moz.fbs.berichtenuitvraag.fuzzing

import com.code_intelligence.jazzer.api.FuzzedDataProvider
import nl.rijksoverheid.moz.fbs.berichtenuitvraag.uitvraag.ONTVANGER_PATTERN
import nl.rijksoverheid.moz.fbs.berichtenuitvraag.uitvraag.splitOntvanger

/**
 * Fuzz de `X-Ontvanger`-parser. Twee security-invarianten worden bewaakt op
 * willekeurige input (de header is de bron voor het LDV-`dataSubjectId`, AVG art.
 * 30): [splitOntvanger] mag nóóit gooien, en een non-null resultaat moet exact het
 * gedeelde [ONTVANGER_PATTERN] respecteren én verliesvrij terug te vouwen zijn tot
 * de oorspronkelijke header. Zo kan een afgekeurde waarde nooit alsnog een
 * dataSubject opleveren en kunnen parser en validator niet divergeren.
 */
object OntvangerFuzzer {

    private val pattern = Regex(ONTVANGER_PATTERN)
    private val toegestaneTypes = setOf("BSN", "RSIN", "KVK", "OIN")

    @JvmStatic
    fun fuzzerTestOneInput(data: FuzzedDataProvider) {
        val raw = data.consumeRemainingAsString()

        val (type, waarde) = splitOntvanger(raw) ?: return

        check(type in toegestaneTypes) { "onverwacht type '$type' uit '$raw'" }
        check(waarde.isNotEmpty() && waarde.all { it in '0'..'9' }) { "niet-numerieke waarde '$waarde' uit '$raw'" }
        check(pattern.matches(raw)) { "split gaf non-null maar raw matcht ONTVANGER_PATTERN niet: '$raw'" }
        check("$type:$waarde" == raw) { "round-trip-mismatch: '$type:$waarde' != '$raw'" }
    }
}
