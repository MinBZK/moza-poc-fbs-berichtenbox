package nl.rijksoverheid.moz.fbs.common.fuzzing

import com.code_intelligence.jazzer.api.FuzzedDataProvider
import nl.rijksoverheid.moz.fbs.common.exception.DomainValidationException
import nl.rijksoverheid.moz.fbs.common.identificatie.Identificatienummer
import nl.rijksoverheid.moz.fbs.common.identificatie.IdentificatienummerType

/**
 * Fuzz `Identificatienummer.fromHeader(X-Ontvanger)`. De header is attacker-
 * controlled en wordt door zowel het berichtenmagazijn als de berichtenuitvraag-
 * dienst gebruikt; de parser MOET ofwel een geldig getypeerd object teruggeven
 * ofwel [DomainValidationException] werpen. Geen ongedefinieerd gedrag, geen
 * runtime-exception buiten die boom, geen null. Vangt regressies waarbij iemand
 * een ander exception-type doorlaat (bv. een rauwe IllegalArgumentException uit
 * een value-class init-blok) — dat zou de mapper-pipeline omzeilen en als 500
 * i.p.v. 400 eindigen.
 *
 * Co-located met de geteste code in `fbs-common`: de fuzzer hoort bij de gedeelde
 * validatie, niet bij een toevallige consument ervan.
 */
object IdentificatienummerFromHeaderFuzzer {

    @JvmStatic
    fun fuzzerTestOneInput(data: FuzzedDataProvider) {
        val header = data.consumeString(64)

        val result: Identificatienummer = try {
            Identificatienummer.fromHeader(header)
        } catch (_: DomainValidationException) {
            return
        }

        // Bij succes: invarianten moeten kloppen — `waarde` numeriek + cijfer-lengte
        // per type, en toCanonicalString() reversibel terug naar dezelfde
        // Identificatienummer.
        check(result.waarde.all { it.isDigit() }) { "waarde mag alleen cijfers bevatten" }

        val verwachteLengte = when (result.type) {
            IdentificatienummerType.BSN, IdentificatienummerType.RSIN -> 9
            IdentificatienummerType.KVK -> 8
            IdentificatienummerType.OIN -> 20
        }

        check(result.waarde.length == verwachteLengte) {
            "waarde-lengte ${result.waarde.length} matcht niet met type ${result.type} (verwacht $verwachteLengte)"
        }

        val roundtrip = Identificatienummer.fromHeader(result.toCanonicalString())

        check(roundtrip.type == result.type) { "roundtrip type drift" }
        check(roundtrip.waarde == result.waarde) { "roundtrip waarde drift" }
    }
}
