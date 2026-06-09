package nl.rijksoverheid.moz.fbs.berichtensessiecache

import io.smallrye.mutiny.Multi
import io.smallrye.mutiny.Uni
import jakarta.enterprise.context.ApplicationScoped
import jakarta.ws.rs.WebApplicationException
import jakarta.ws.rs.core.Response
import nl.rijksoverheid.moz.fbs.berichtensessiecache.berichten.AggregationStatus
import nl.rijksoverheid.moz.fbs.berichtensessiecache.berichten.Bericht
import nl.rijksoverheid.moz.fbs.berichtensessiecache.berichten.BerichtenPagina
import nl.rijksoverheid.moz.fbs.berichtensessiecache.berichten.BerichtensessiecacheService
import nl.rijksoverheid.moz.fbs.berichtensessiecache.berichten.CacheContentieException
import nl.rijksoverheid.moz.fbs.berichtensessiecache.berichten.CacheCorruptedException
import nl.rijksoverheid.moz.fbs.berichtensessiecache.berichten.Leesstatus
import nl.rijksoverheid.moz.fbs.berichtensessiecache.berichten.MagazijnEvent
import nl.rijksoverheid.moz.fbs.berichtensessiecache.berichten.OphalenStatus
import nl.rijksoverheid.moz.fbs.common.identificatie.Identificatienummer
import org.jboss.logging.Logger
import java.time.Duration
import java.util.UUID

/**
 * Synchrone facade over de reactieve [BerichtensessiecacheService]: blokkeert
 * begrensd ([TIMEOUT]) op de Mutiny-pijplijn en vertaalt infrastructuurfouten
 * naar de statuscodes uit het [Sessiecache]-contract. Houdt zelf géén staat —
 * alle sessiestaat (cache, aggregatie-status, lock) leeft in Redis.
 */
@ApplicationScoped
internal class BlockingSessiecache(
    private val service: BerichtensessiecacheService,
) : Sessiecache {

    private val log = Logger.getLogger(BlockingSessiecache::class.java)

    override fun lijst(
        ontvanger: Identificatienummer,
        pagina: Int?,
        paginaGrootte: Int?,
        afzender: String?,
        map: String?,
    ): BerichtenPagina {
        requireGereedStatus(ontvanger)

        return awaitOrServiceUnavailable {
            service.getBerichten(pagina ?: 0, effectieveGrootte(paginaGrootte), ontvanger, afzender, map)
        }
    }

    override fun zoek(
        ontvanger: Identificatienummer,
        q: String,
        pagina: Int?,
        paginaGrootte: Int?,
        afzender: String?,
        map: String?,
    ): BerichtenPagina {
        requireGereedStatus(ontvanger)

        return awaitOrServiceUnavailable {
            service.zoekBerichten(q, pagina ?: 0, effectieveGrootte(paginaGrootte), ontvanger, afzender, map)
        }
    }

    override fun bericht(ontvanger: Identificatienummer, berichtId: UUID): Bericht? {
        requireGereedStatus(ontvanger)

        return awaitOrServiceUnavailable { service.getBerichtById(berichtId, ontvanger) }
    }

    override fun werkBerichtBij(
        ontvanger: Identificatienummer,
        berichtId: UUID,
        status: Leesstatus?,
        map: String?,
    ): Bericht? {
        // Een lege patch zou als no-op-succes ogen; expliciet afwijzen zodat de
        // caller (en diens client) weet dat er niets gewijzigd is.
        if (status == null && map == null) {
            throw WebApplicationException(
                "Minimaal één van 'status' of 'map' is vereist (geen geldige waarde meegegeven).",
                Response.Status.BAD_REQUEST,
            )
        }

        return awaitOrServiceUnavailable {
            service.updateBerichtMetadata(berichtId, ontvanger, status?.wire, map)
        }
    }

    override fun verwijder(ontvanger: Identificatienummer, berichtId: UUID) {
        awaitOrServiceUnavailable { service.verwijderBericht(berichtId, ontvanger) }
    }

    override fun ophalen(ontvanger: Identificatienummer): Multi<MagazijnEvent> = service.haalBerichtenOp(ontvanger)

    override fun schrijfBericht(ontvanger: Identificatienummer, bericht: Bericht): Bericht {
        if (bericht.ontvanger != ontvanger) {
            throw WebApplicationException(
                "Ontvanger in bericht komt niet overeen met de opgegeven ontvanger.",
                Response.Status.BAD_REQUEST,
            )
        }

        // Aanmeld-pad mag alleen bestaande, actieve sessies bijwerken: als er geen
        // aggregatie heeft plaatsgevonden voor deze ontvanger, hoort er ook geen cache te zijn.
        awaitOrServiceUnavailable { service.getAggregationStatus(ontvanger) }
            ?: throw WebApplicationException(
                "Geen actieve sessie voor deze ontvanger; bericht niet toegevoegd.",
                Response.Status.NOT_FOUND,
            )

        return try {
            awaitOrServiceUnavailable { service.createBericht(bericht, ontvanger) }
        } catch (e: IllegalArgumentException) {
            // Defensieve limiet overschreden (BerichtValidator): invoerfout van de
            // aanleverende caller, geen infrastructuurfout.
            throw WebApplicationException(e.message, e, Response.Status.BAD_REQUEST)
        }
    }

    /**
     * Lees- en zoekpaden vereisen een afgeronde ophaling: zonder status is de cache
     * (mogelijk) leeg of verouderd en zou een lege lijst onterecht "geen berichten"
     * suggereren. De caller krijgt 409 om eerst [Sessiecache.ophalen] aan te roepen.
     */
    private fun requireGereedStatus(ontvanger: Identificatienummer): AggregationStatus {
        val aggregation = awaitOrServiceUnavailable { service.getAggregationStatus(ontvanger) }
            ?: throw WebApplicationException(
                "Berichten zijn nog niet opgehaald. Start eerst het ophalen van berichten.",
                Response.Status.CONFLICT,
            )

        return when (aggregation.status) {
            OphalenStatus.GEREED -> aggregation
            OphalenStatus.BEZIG -> throw WebApplicationException(
                "Berichten worden momenteel opgehaald. Wacht tot het ophalen is afgerond.",
                Response.Status.CONFLICT,
            )
            OphalenStatus.FOUT -> throw WebApplicationException(
                "Het ophalen van berichten is mislukt. Start het ophalen van berichten opnieuw.",
                Response.Status.INTERNAL_SERVER_ERROR,
            )
        }
    }

    private fun effectieveGrootte(paginaGrootte: Int?): Int = (paginaGrootte ?: 20).coerceAtMost(100)

    private fun <T> awaitOrServiceUnavailable(block: () -> Uni<T>): T {
        try {
            return block().await().atMost(TIMEOUT)
        } catch (e: java.util.concurrent.CompletionException) {
            // Mutiny's blocking await wrapt checked failures (bv. Jackson's
            // JsonProcessingException) in een CompletionException; classificeer op de
            // cause, anders degradeert elk data-integriteit-issue tot een generieke 503.
            throw vertaalCacheFout(e.cause ?: e)
        } catch (e: Exception) {
            throw vertaalCacheFout(e)
        }
    }

    private fun vertaalCacheFout(e: Throwable): Exception = when (e) {
        // Cache-operatie hing langer dan TIMEOUT — log warn met cause zodat operations
        // niet alleen het 503-pad ziet maar ook welke await het was. Silent discard
        // verbergt prestatie-regressies in Redis.
        is java.util.concurrent.TimeoutException -> {
            log.warnf(e, "Cache-operatie overschreed timeout van %s; 503 naar caller", TIMEOUT)
            WebApplicationException("Cache niet bereikbaar. Probeer het later opnieuw", 503)
        }
        // Transiente schrijf-contentie: bron logt al op warn. Retriable 503
        // i.p.v. de misleidende 404 die een null-resultaat zou opleveren.
        is CacheContentieException ->
            WebApplicationException("Cache tijdelijk niet bij te werken. Probeer het later opnieuw", 503)
        is WebApplicationException -> e
        // Invoervalidatie (BerichtValidator, Leesstatus.fromWire): caller-fout,
        // geen infrastructuurfout — laat de caller dit naar 400 vertalen.
        is IllegalArgumentException -> e
        // Cache-deserialisatie-fout = data-integriteit-issue (verkeerde versie, corrupte hash),
        // niet "Redis onbereikbaar". 500 zonder de verkeerde infrastructuur-diagnose te suggereren.
        is com.fasterxml.jackson.core.JsonProcessingException -> {
            log.errorf(e, "Cache-data niet deserialiseerbaar (corruptie of schema-drift)")
            WebApplicationException("Cache-data niet leesbaar.", 500)
        }
        // Hash-velden ontbreken of onleesbaar → eigen data-issue, niet bereikbaarheids-issue.
        is CacheCorruptedException -> {
            log.errorf(e, "Cache-hash corrupt")
            WebApplicationException("Cache-data niet leesbaar.", 500)
        }
        else -> {
            log.errorf(e, "Cache-operatie mislukt")
            WebApplicationException("Cache niet bereikbaar.", 503)
        }
    }

    companion object {
        private val TIMEOUT = Duration.ofSeconds(5)
    }
}
