package nl.rijksoverheid.moz.fbs.berichtensessiecache.fuzzing

import com.code_intelligence.jazzer.api.FuzzedDataProvider
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import com.fasterxml.jackson.module.kotlin.KotlinModule
import nl.rijksoverheid.moz.fbs.berichtensessiecache.berichten.AggregationStatus
import nl.rijksoverheid.moz.fbs.berichtensessiecache.berichten.Bericht
import nl.rijksoverheid.moz.fbs.berichtensessiecache.berichten.BerichtenPagina
import nl.rijksoverheid.moz.fbs.berichtensessiecache.berichten.EventType
import nl.rijksoverheid.moz.fbs.berichtensessiecache.berichten.MagazijnBevragingGeslaagd
import nl.rijksoverheid.moz.fbs.berichtensessiecache.berichten.MagazijnBevragingGestart
import nl.rijksoverheid.moz.fbs.berichtensessiecache.berichten.MagazijnBevragingMislukt
import nl.rijksoverheid.moz.fbs.berichtensessiecache.berichten.MagazijnEvent
import nl.rijksoverheid.moz.fbs.berichtensessiecache.berichten.MagazijnFoutStatus
import nl.rijksoverheid.moz.fbs.berichtensessiecache.berichten.OphalenGereed
import nl.rijksoverheid.moz.fbs.berichtensessiecache.berichten.OphalenMislukt
import nl.rijksoverheid.moz.fbs.berichtensessiecache.berichten.OphalenStatus
import nl.rijksoverheid.moz.fbs.berichtensessiecache.berichten.OpslaanMislukt
import nl.rijksoverheid.moz.fbs.common.identificatie.Bsn
import java.time.Instant
import java.util.UUID

object DomainValidationFuzzer {

    private val objectMapper = ObjectMapper()
        .registerModule(JavaTimeModule())
        .registerModule(KotlinModule.Builder().build())

    private val targets = arrayOf(
        ::fuzzBericht,
        ::fuzzAggregationStatus,
        ::fuzzBerichtenPagina,
        ::fuzzMagazijnEventWire,
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

    /**
     * De veldcombinaties per soort voortgangsbericht liggen sinds de sealed hiërarchie vast in
     * het typesysteem; er valt geen ongeldig event meer te construeren. Wat wél fuzzbaar blijft
     * is de serialisatie: magazijnnaam en foutmelding komen uit externe bronnen en gaan
     * ongefilterd de SSE-stroom op. Deze target bewaakt daarom de wire-invarianten onder
     * willekeurige tekst — inclusief control-characters, quotes en unicode.
     */
    private fun fuzzMagazijnEventWire(data: FuzzedDataProvider) {
        val event: MagazijnEvent = when (data.pickValue(EventType.entries.toTypedArray())) {
            EventType.MAGAZIJN_BEVRAGING_GESTART -> MagazijnBevragingGestart(
                magazijnId = data.consumeString(100),
                naam = if (data.consumeBoolean()) data.consumeString(100) else null,
            )
            EventType.MAGAZIJN_BEVRAGING_VOLTOOID -> if (data.consumeBoolean()) {
                MagazijnBevragingGeslaagd(
                    magazijnId = data.consumeString(100),
                    naam = if (data.consumeBoolean()) data.consumeString(100) else null,
                    aantalBerichten = data.consumeInt(),
                )
            } else {
                MagazijnBevragingMislukt(
                    magazijnId = data.consumeString(100),
                    naam = if (data.consumeBoolean()) data.consumeString(100) else null,
                    status = data.pickValue(MagazijnFoutStatus.entries.toTypedArray()),
                    foutmelding = data.consumeString(200),
                )
            }
            EventType.OPHALEN_GEREED -> OphalenGereed(
                totaalBerichten = data.consumeInt(),
                geslaagd = data.consumeInt(),
                mislukt = data.consumeInt(),
                totaalMagazijnen = data.consumeInt(),
            )
            EventType.OPHALEN_FOUT -> if (data.consumeBoolean()) {
                OphalenMislukt(foutmelding = data.consumeString(200), referentie = data.consumeString(50))
            } else {
                OpslaanMislukt(
                    foutmelding = data.consumeString(200),
                    geslaagd = data.consumeInt(),
                    mislukt = data.consumeInt(),
                    totaalMagazijnen = data.consumeInt(),
                    referentie = data.consumeString(50),
                )
            }
        }

        val json = objectMapper.writeValueAsString(event)
        val heringelezen = objectMapper.readTree(json)

        check(heringelezen.get("event")?.asText() == event.event.value) {
            "event-discriminator ontbreekt of wijkt af in: $json"
        }
        check(heringelezen.properties().none { (_, waarde) -> waarde.isNull }) {
            "voortgangsbericht mag geen null-velden op de lijn zetten: $json"
        }
    }
}
