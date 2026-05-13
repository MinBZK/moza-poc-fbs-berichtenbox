package nl.rijksoverheid.moz.fbs.berichtenmagazijn.ophaal

import jakarta.enterprise.context.ApplicationScoped
import jakarta.transaction.Transactional
import jakarta.ws.rs.ForbiddenException
import jakarta.ws.rs.NotFoundException
import nl.rijksoverheid.moz.fbs.berichtenmagazijn.opslag.Bericht
import nl.rijksoverheid.moz.fbs.berichtenmagazijn.opslag.BerichtRepository
import nl.rijksoverheid.moz.fbs.berichtenmagazijn.opslag.BerichtStatusRepository
import nl.rijksoverheid.moz.fbs.berichtenmagazijn.opslag.Bijlage
import nl.rijksoverheid.moz.fbs.berichtenmagazijn.opslag.BijlageRepository
import nl.rijksoverheid.moz.fbs.berichtenmagazijn.opslag.Identificatienummer
import nl.rijksoverheid.moz.fbs.berichtenmagazijn.opslag.PagedBerichten
import org.jboss.logging.Logger
import java.util.UUID

/**
 * Read-only orkestratie voor de Ophaal-API.
 *
 * Verantwoordelijkheden:
 * 1. Berichten uit het magazijn lezen, gefilterd op ontvanger en soft-delete.
 * 2. PoC-autorisatie: ontvanger in `X-Ontvanger` moet matchen met de ontvanger
 *    op het bericht (single-bericht-GET). Bij lijsten gebeurt dat impliciet via
 *    het WHERE-clause in de repository.
 * 3. Verrijken van een bericht met bijlage-metadata en de leesstatus van de
 *    ontvanger die het opvraagt.
 *
 * De echte AuthZEN PEP/PDP komt in Issue 10; deze service-laag-check is de PoC.
 */
@ApplicationScoped
class BerichtOphaalService(
    private val berichtRepository: BerichtRepository,
    private val bijlageRepository: BijlageRepository,
    private val statusRepository: BerichtStatusRepository,
) {

    private val log = Logger.getLogger(BerichtOphaalService::class.java)

    @Transactional
    fun lijst(
        ontvanger: Identificatienummer,
        afzender: String?,
        page: Int,
        pageSize: Int,
    ): PagedBerichten {
        val pagina = berichtRepository.lijstVoorOntvanger(ontvanger, afzender, page, pageSize)
        // Per bericht de status (gelezen/map) toevoegen.
        val verrijkt = pagina.berichten.map { bericht ->
            bericht.copy(status = statusRepository.findByBerichtId(bericht.berichtId))
        }
        return pagina.copy(berichten = verrijkt)
    }

    @Transactional
    fun haalBerichtOp(berichtId: UUID, ontvanger: Identificatienummer): Bericht {
        val bericht = berichtRepository.findByBerichtId(berichtId)
            ?: throw NotFoundException("Bericht niet gevonden")
        autoriseerOntvanger(bericht, ontvanger)
        return bericht.copy(
            bijlagen = bijlageRepository.metadataVoorBericht(berichtId),
            status = statusRepository.findByBerichtId(berichtId),
        )
    }

    @Transactional
    fun haalBijlageOp(berichtId: UUID, bijlageId: UUID, ontvanger: Identificatienummer): Bijlage {
        // Ontvanger-check via het parent-bericht: zonder geldige toegang tot het
        // bericht is een bijlage onbereikbaar — anders zou een geraden bijlage-UUID
        // tot data-disclosure leiden.
        val bericht = berichtRepository.findByBerichtId(berichtId)
            ?: throw NotFoundException("Bericht niet gevonden")
        autoriseerOntvanger(bericht, ontvanger)
        return bijlageRepository.findByBerichtIdEnBijlageId(berichtId, bijlageId)
            ?: throw NotFoundException("Bijlage niet gevonden")
    }

    private fun autoriseerOntvanger(bericht: Bericht, ontvanger: Identificatienummer) {
        if (bericht.ontvanger.type != ontvanger.type || bericht.ontvanger.waarde != ontvanger.waarde) {
            // Geen ontvanger-waarde in de log (kan BSN zijn): alleen types loggen.
            // De correlation-id in de Problem-response geeft support genoeg context.
            log.warnf(
                "Autorisatie geweigerd: ontvanger-mismatch berichtId=%s berichtOntvangerType=%s headerOntvangerType=%s",
                bericht.berichtId,
                bericht.ontvanger.type,
                ontvanger.type,
            )
            throw ForbiddenException("Geen toegang tot dit bericht")
        }
    }
}
