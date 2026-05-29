package nl.rijksoverheid.moz.fbs.berichtenmagazijn.beheer

import jakarta.enterprise.context.ApplicationScoped
import jakarta.transaction.Transactional
import jakarta.ws.rs.NotFoundException
import nl.rijksoverheid.moz.fbs.berichtenmagazijn.opslag.Bericht
import nl.rijksoverheid.moz.fbs.berichtenmagazijn.opslag.BerichtAutorisatie
import nl.rijksoverheid.moz.fbs.berichtenmagazijn.opslag.BerichtRepository
import nl.rijksoverheid.moz.fbs.berichtenmagazijn.opslag.BerichtStatusPatch
import nl.rijksoverheid.moz.fbs.berichtenmagazijn.opslag.BerichtStatusRepository
import nl.rijksoverheid.moz.fbs.berichtenmagazijn.opslag.BijlageRepository
import nl.rijksoverheid.moz.fbs.common.identificatie.Identificatienummer
import org.jboss.logging.Logger
import java.time.Instant
import java.util.UUID

/**
 * Mutaties op een bericht voor de eigenaar (ontvanger): status (PATCH) en
 * soft-delete (DELETE).
 *
 * Voor consistentie met [nl.rijksoverheid.moz.fbs.berichtenmagazijn.ophaal.BerichtOphaalService]:
 * 404 als het bericht niet bestaat of al verwijderd is; 403 als het wel bestaat
 * maar bij een andere ontvanger hoort.
 */
@ApplicationScoped
class BerichtBeheerService(
    private val berichtRepository: BerichtRepository,
    private val statusRepository: BerichtStatusRepository,
    private val bijlageRepository: BijlageRepository,
) {

    private val log = Logger.getLogger(BerichtBeheerService::class.java)

    /**
     * Patcht de leesstatus (gelezen, map) van een bericht voor de opgegeven
     * ontvanger en retourneert het bijgewerkte bericht inclusief bijlage-metadata
     * en de nieuwe status. Patch-velden die `null` zijn worden als "niet
     * wijzigen" behandeld (zie [BerichtStatusPatch] voor de semantiek).
     */
    @Transactional
    fun wijzigStatus(
        berichtId: UUID,
        ontvanger: Identificatienummer,
        patch: BerichtStatusPatch,
    ): Bericht {
        // findIncludingDeleted ipv findByBerichtId: bestaan + ontvanger-check
        // worden geëvalueerd vóór de soft-delete-check. Daardoor geeft PATCH op
        // andermans soft-deleted bericht 403 (zelfde als DELETE), niet 404 —
        // anders zou een UUID-rader uit het verschil 404 vs 403 het bestaan van
        // andermans (verwijderde) bericht kunnen afleiden.
        val record = berichtRepository.findIncludingDeleted(berichtId)
            ?: throw NotFoundException("Bericht niet gevonden")
        BerichtAutorisatie.vereisOntvanger(record.bericht, ontvanger)
        if (record.isVerwijderd) throw NotFoundException("Bericht niet gevonden")

        val nieuweStatus = statusRepository.upsert(
            berichtId = berichtId,
            patch = patch,
            tijdstip = Instant.now(),
        )

        return record.bericht.copy(
            bijlagen = bijlageRepository.metadataVoorBericht(berichtId),
            status = nieuweStatus,
        )
    }

    /**
     * Idempotente soft-delete conform RFC 9110 §9.3.5. Tweede DELETE door
     * dezelfde rechtmatige ontvanger geeft 204 zonder mutatie, zodat een client
     * die na een netwerk-fout de call herhaalt geen verwarrende 404 krijgt.
     *
     * Mapping:
     * - bericht onbekend → 404
     * - bericht bestaat, andere ontvanger → 403 (geen onthulling van bestaan
     *   via 200/404, ongeacht soft-delete-staat)
     * - bericht bestaat, juiste ontvanger, al verwijderd → no-op (204)
     * - bericht bestaat, juiste ontvanger, actief → soft-delete (204)
     */
    @Transactional
    fun verwijder(berichtId: UUID, ontvanger: Identificatienummer) {
        val record = berichtRepository.findIncludingDeleted(berichtId)
            ?: throw NotFoundException("Bericht niet gevonden")
        BerichtAutorisatie.vereisOntvanger(record.bericht, ontvanger)
        if (record.isVerwijderd) return

        val ok = berichtRepository.softDelete(berichtId, ontvanger, Instant.now())
        if (!ok) {
            // softDelete=false kan minstens vier scenario's omvatten (zie de
            // KDoc op softDelete). De interessante happy-race is "concurrente
            // DELETE door dezelfde ontvanger". Verifieer expliciet: bericht
            // moet nu nog steeds bestaan, bij dezelfde ontvanger horen, én
            // verwijderd zijn. Elke andere combinatie wijst op
            // datacorruptie/autorisatie-mismatch en moet hard falen — anders
            // zou een silente 204 een gestolen DELETE kunnen maskeren.
            val nu = berichtRepository.findIncludingDeleted(berichtId)
                ?: throw IllegalStateException(
                    "softDelete=false maar bericht is niet meer aanwezig — onverwachte hard-delete?",
                )
            BerichtAutorisatie.vereisOntvanger(nu.bericht, ontvanger)
            check(nu.isVerwijderd) {
                "softDelete=false maar bericht is niet verwijderd — onverwachte state voor berichtId=$berichtId"
            }
            log.warnf(
                "Soft-delete-race: tweede update faalde maar bericht is verwijderd. berichtId=%s ontvangerType=%s verwijderdOp=%s",
                berichtId,
                ontvanger.type,
                nu.verwijderdOp,
            )
        }
    }
}
