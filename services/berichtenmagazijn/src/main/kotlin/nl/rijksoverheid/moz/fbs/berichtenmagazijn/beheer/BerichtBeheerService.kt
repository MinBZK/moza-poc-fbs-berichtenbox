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
     * Soft-delete: zet `verwijderdOp` op het bericht. Idempotent in de zin dat
     * een tweede DELETE op hetzelfde bericht 404 oplevert (het bericht is dan
     * vanuit de Ophaal-API-bril niet meer zichtbaar).
     */
    @Transactional
    fun verwijder(berichtId: UUID, ontvanger: Identificatienummer) {
        val bericht = berichtRepository.findByBerichtId(berichtId)
            ?: throw NotFoundException("Bericht niet gevonden")
        BerichtAutorisatie.vereisOntvanger(bericht, ontvanger)

        val ok = berichtRepository.softDelete(berichtId, ontvanger, Instant.now())
        if (!ok) {
            // Race-condition: tussen find en update is iets anders gebeurd. Behandel
            // als "niet meer aanwezig" — 404 is dan de consistente respons.
            throw NotFoundException("Bericht niet gevonden")
        }
    }
}
