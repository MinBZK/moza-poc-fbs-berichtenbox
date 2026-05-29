package nl.rijksoverheid.moz.fbs.berichtensessiecache.fuzzing

import com.code_intelligence.jazzer.api.FuzzedDataProvider
import nl.rijksoverheid.moz.fbs.berichtensessiecache.berichten.AggregationStatus
import nl.rijksoverheid.moz.fbs.berichtensessiecache.berichten.Bericht
import nl.rijksoverheid.moz.fbs.berichtensessiecache.berichten.BerichtenPage
import nl.rijksoverheid.moz.fbs.berichtensessiecache.berichten.EventType
import nl.rijksoverheid.moz.fbs.berichtensessiecache.berichten.MagazijnStatus
import nl.rijksoverheid.moz.fbs.berichtensessiecache.berichten.MagazijnEvent
import nl.rijksoverheid.moz.fbs.berichtensessiecache.berichten.OphalenStatus
import nl.rijksoverheid.moz.fbs.common.exception.DomainValidationException
import nl.rijksoverheid.moz.fbs.common.identificatie.Identificatienummer
import nl.rijksoverheid.moz.fbs.common.identificatie.IdentificatienummerType
import java.time.Instant
import java.util.UUID

object DomainValidationFuzzer {

    private val targets = arrayOf(
        ::fuzzBericht,
        ::fuzzAggregationStatus,
        ::fuzzBerichtenPage,
        ::fuzzMagazijnEventDomain,
        ::fuzzIdentificatienummerFromHeader,
    )

    @JvmStatic
    fun fuzzerTestOneInput(data: FuzzedDataProvider) {
        data.pickValue(targets).invoke(data)
    }

    private fun fuzzBericht(data: FuzzedDataProvider) {
        val bericht = try {
            Bericht(
                berichtId = UUID.randomUUID(),
                afzender = data.consumeString(200),
                ontvanger = data.consumeString(200),
                onderwerp = data.consumeString(200),
                tijdstip = Instant.now(),
                magazijnId = data.consumeString(200),
            )
        } catch (_: IllegalArgumentException) {
            return
        }
        check(bericht.afzender.isNotBlank()) { "afzender moet niet-blank zijn na constructie" }
        check(bericht.ontvanger.isNotBlank()) { "ontvanger moet niet-blank zijn na constructie" }
        check(bericht.onderwerp.isNotBlank()) { "onderwerp moet niet-blank zijn na constructie" }
        check(bericht.magazijnId.isNotBlank()) { "magazijnId moet niet-blank zijn na constructie" }
    }

    private fun fuzzAggregationStatus(data: FuzzedDataProvider) {
        val status = try {
            AggregationStatus(
                status = data.pickValue(OphalenStatus.entries.toTypedArray()),
                totaalMagazijnen = data.consumeInt(),
                geslaagd = data.consumeInt(),
                mislukt = data.consumeInt(),
            )
        } catch (_: IllegalArgumentException) {
            return
        }
        check(status.totaalMagazijnen >= 0) { "totaalMagazijnen moet niet-negatief zijn" }
        check(status.geslaagd >= 0) { "geslaagd moet niet-negatief zijn" }
        check(status.mislukt >= 0) { "mislukt moet niet-negatief zijn" }
        check(status.geslaagd + status.mislukt <= status.totaalMagazijnen) {
            "geslaagd + mislukt mag niet groter zijn dan totaalMagazijnen"
        }
    }

    private fun fuzzBerichtenPage(data: FuzzedDataProvider) {
        val page = try {
            BerichtenPage(
                berichten = emptyList(),
                page = data.consumeInt(),
                pageSize = data.consumeInt(),
                totalElements = data.consumeLong(),
                totalPages = data.consumeInt(),
            )
        } catch (_: IllegalArgumentException) {
            return
        }
        check(page.page >= 0) { "page moet niet-negatief zijn" }
        check(page.pageSize > 0) { "pageSize moet positief zijn" }
        check(page.totalElements >= 0) { "totalElements moet niet-negatief zijn" }
        check(page.totalPages >= 0) { "totalPages moet niet-negatief zijn" }
    }

    /**
     * Fuzz `Identificatienummer.fromHeader(X-Ontvanger)`: header is attacker-
     * controlled, parser MOET ofwel een geldig getypeerd object teruggeven
     * ofwel `DomainValidationException` werpen. Geen ongedefinieerd gedrag,
     * geen runtime-exception buiten die boom, geen null. Vangt regressies
     * waarbij iemand een ander exception-type doorlaat (bv. raw
     * IllegalArgumentException uit value-class init blok) — dat zou de
     * mapper-pipeline omzeilen en als 500 i.p.v. 400 eindigen.
     */
    private fun fuzzIdentificatienummerFromHeader(data: FuzzedDataProvider) {
        val header = data.consumeString(64)

        val result: Identificatienummer = try {
            Identificatienummer.fromHeader(header)
        } catch (_: DomainValidationException) {
            return
        }

        // Bij succes: invarianten moeten kloppen — `type` consistent met de
        // parse-prefix, `waarde` numeriek + cijfer-lengte per type, en
        // toCanonicalString() reversibel terug naar dezelfde Identificatienummer.
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

    private fun fuzzMagazijnEventDomain(data: FuzzedDataProvider) {
        try {
            MagazijnEvent(
                event = data.pickValue(EventType.entries.toTypedArray()),
                magazijnId = if (data.consumeBoolean()) data.consumeString(100) else null,
                naam = if (data.consumeBoolean()) data.consumeString(100) else null,
                status = if (data.consumeBoolean()) data.pickValue(MagazijnStatus.entries.toTypedArray()) else null,
                aantalBerichten = if (data.consumeBoolean()) data.consumeInt() else null,
                foutmelding = if (data.consumeBoolean()) data.consumeString(200) else null,
                totaalBerichten = if (data.consumeBoolean()) data.consumeInt() else null,
                geslaagd = if (data.consumeBoolean()) data.consumeInt() else null,
                mislukt = if (data.consumeBoolean()) data.consumeInt() else null,
                totaalMagazijnen = if (data.consumeBoolean()) data.consumeInt() else null,
            )
        } catch (_: IllegalArgumentException) {
            // Verwacht bij ongeldige combinatie EventType + nullable velden
        }
    }
}
