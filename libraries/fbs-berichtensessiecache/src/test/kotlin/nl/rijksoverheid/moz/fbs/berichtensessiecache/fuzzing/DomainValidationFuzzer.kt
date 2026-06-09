package nl.rijksoverheid.moz.fbs.berichtensessiecache.fuzzing

import com.code_intelligence.jazzer.api.FuzzedDataProvider
import nl.rijksoverheid.moz.fbs.berichtensessiecache.berichten.AggregationStatus
import nl.rijksoverheid.moz.fbs.berichtensessiecache.berichten.Bericht
import nl.rijksoverheid.moz.fbs.berichtensessiecache.berichten.BerichtenPagina
import nl.rijksoverheid.moz.fbs.berichtensessiecache.berichten.EventType
import nl.rijksoverheid.moz.fbs.berichtensessiecache.berichten.MagazijnStatus
import nl.rijksoverheid.moz.fbs.berichtensessiecache.berichten.MagazijnEvent
import nl.rijksoverheid.moz.fbs.berichtensessiecache.berichten.OphalenStatus
import nl.rijksoverheid.moz.fbs.common.identificatie.Bsn
import java.time.Instant
import java.util.UUID

object DomainValidationFuzzer {

    private val targets = arrayOf(
        ::fuzzBericht,
        ::fuzzAggregationStatus,
        ::fuzzBerichtenPagina,
        ::fuzzMagazijnEventDomain,
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
                // ontvanger is een getypeerd Identificatienummer; de waarde-invarianten worden
                // door dat type afgedwongen en elders gefuzzd. Hier vast zodat de overige
                // Bericht-init-invarianten (afzender/onderwerp/magazijnId/...) gefuzzd worden.
                ontvanger = Bsn("999993653"),
                onderwerp = data.consumeString(200),
                inhoud = data.consumeString(500),
                publicatietijdstip = Instant.now(),
                magazijnId = data.consumeString(200),
                aantalBijlagen = data.consumeInt(),
                map = if (data.consumeBoolean()) data.consumeString(100) else null,
            )
        } catch (_: IllegalArgumentException) {
            return
        }
        check(bericht.afzender.isNotBlank()) { "afzender moet niet-blank zijn na constructie" }
        check(bericht.onderwerp.isNotBlank()) { "onderwerp moet niet-blank zijn na constructie" }
        check(bericht.magazijnId.isNotBlank()) { "magazijnId moet niet-blank zijn na constructie" }
        check(bericht.aantalBijlagen >= 0) { "aantalBijlagen moet niet-negatief zijn na constructie" }
        bericht.map?.let {
            check(it.isNotBlank()) { "mapnaam moet niet-blank zijn na constructie" }
            check(it.length <= Bericht.MAX_MAPNAAM_LENGTE) { "mapnaam-lengte ongeldig na constructie" }
        }
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

    private fun fuzzBerichtenPagina(data: FuzzedDataProvider) {
        val page = try {
            BerichtenPagina(
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
