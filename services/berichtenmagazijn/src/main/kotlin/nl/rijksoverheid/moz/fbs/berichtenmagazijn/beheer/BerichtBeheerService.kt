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
import nl.rijksoverheid.moz.fbs.berichtenmagazijn.opslag.Identificatienummer
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
     * wijzigen" behandeld (zie [BerichtStatusPatch] voor de PoC-beperking).
     */
    @Transactional
    fun wijzigStatus(
        berichtId: UUID,
        ontvanger: Identificatienummer,
        patch: BerichtStatusPatch,
    ): Bericht {
        val bericht = berichtRepository.findByBerichtId(berichtId)
            ?: throw NotFoundException("Bericht niet gevonden")
        BerichtAutorisatie.vereisOntvanger(bericht, ontvanger)

        val nieuweStatus = statusRepository.upsert(
            berichtId = berichtId,
            patch = patch,
            tijdstip = Instant.now(),
        )

        return bericht.copy(
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
            // Race-condition: tussen find en update is een concurrente DELETE
            // door dezelfde ontvanger geslaagd. Resultaat is identiek aan een
            // tweede DELETE — 204 zonder mutatie. Log op WARN zodat een
            // herhaalbaar patroon zichtbaar wordt; waarde blijft uit log.
            log.warnf(
                "Soft-delete-race: tweede update faalde maar bericht is verwijderd. berichtId=%s ontvangerType=%s",
                berichtId,
                ontvanger.type,
            )
        }
    }
}
