package nl.rijksoverheid.moz.fbs.berichtensessiecache

import io.smallrye.mutiny.Multi
import io.smallrye.mutiny.Uni
import jakarta.enterprise.context.ApplicationScoped
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
import org.eclipse.microprofile.config.inject.ConfigProperty
import org.jboss.logging.Logger
import java.time.Duration
import java.util.UUID

/**
 * Synchrone facade over de reactieve [BerichtensessiecacheService]: blokkeert
 * begrensd ([timeout]) op de Mutiny-pijplijn en vertaalt infrastructuurfouten
 * naar de gesloten [SessiecacheException]-hiërarchie uit het [Sessiecache]-contract.
 * Houdt zelf géén staat — alle sessiestaat (cache, aggregatie-status, lock) leeft in Redis.
 */
@ApplicationScoped
internal class BlockingSessiecache(
    private val service: BerichtensessiecacheService,
    // Begrenzing op de blocking-await van de facade over élk lees-/schrijfpad van het
    // Sessiecache-contract (de paden raken een lokale/naast-de-pod Redis, geen remote
    // magazijn-RTT — vandaar dezelfde 5s-ondergrens als cache-await-timeout-seconds).
    // Overschrijden → 503. Moet > 0: Mutiny's `atMost(ZERO)` wacht onbegrensd en zou de
    // bescherming stil uitschakelen.
    @param:ConfigProperty(name = "berichtensessiecache.facade-await-timeout-seconds", defaultValue = "5")
    private val facadeAwaitTimeoutSeconds: Long,
) : Sessiecache {

    private val log = Logger.getLogger(BlockingSessiecache::class.java)

    private val timeout: Duration = Duration.ofSeconds(facadeAwaitTimeoutSeconds)

    init {
        require(facadeAwaitTimeoutSeconds > 0) {
            "berichtensessiecache.facade-await-timeout-seconds ($facadeAwaitTimeoutSeconds) moet groter zijn dan 0"
        }
    }

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
            throw SessiecacheException.OngeldigeInvoer(
                "Minimaal één van 'status' of 'map' is vereist (geen geldige waarde meegegeven).",
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
        // Vergelijk op .waarde: Bericht.ontvanger slaat de raw identificatiewaarde op
        // (geen type-prefix), zodat JSON-serialisatie en upstream-contracten ongewijzigd blijven.
        if (bericht.ontvanger != ontvanger.waarde) {
            throw SessiecacheException.OngeldigeInvoer(
                "Ontvanger in bericht komt niet overeen met de opgegeven ontvanger.",
            )
        }

        // Aanmeld-pad mag alleen bestaande, actieve sessies bijwerken: als er geen
        // aggregatie heeft plaatsgevonden voor deze ontvanger, hoort er ook geen cache te zijn.
        awaitOrServiceUnavailable { service.getAggregationStatus(ontvanger) }
            ?: throw SessiecacheException.GeenActieveSessie(
                "Geen actieve sessie voor deze ontvanger; bericht niet toegevoegd.",
            )

        return try {
            awaitOrServiceUnavailable { service.createBericht(bericht, ontvanger) }
        } catch (e: IllegalArgumentException) {
            // Defensieve limiet overschreden (BerichtValidator): invoerfout van de
            // aanleverende caller, geen infrastructuurfout.
            throw SessiecacheException.OngeldigeInvoer(e.message ?: "Ongeldig bericht.", e)
        }
    }

    /**
     * Lees- en zoekpaden vereisen een afgeronde ophaling: zonder status is de cache
     * (mogelijk) leeg of verouderd en zou een lege lijst onterecht "geen berichten"
     * suggereren. De caller krijgt [SessiecacheException.NogNietGevuld] (nog niet gestart)
     * resp. [SessiecacheException.OphalenBezig] (loopt nog) en moet eerst [Sessiecache.ophalen]
     * aanroepen; een mislukte ophaling levert [SessiecacheException.OphalenMislukt].
     */
    private fun requireGereedStatus(ontvanger: Identificatienummer): AggregationStatus {
        val aggregation = awaitOrServiceUnavailable { service.getAggregationStatus(ontvanger) }
            ?: throw SessiecacheException.NogNietGevuld(
                "Berichten zijn nog niet opgehaald. Start eerst het ophalen van berichten.",
            )

        return when (aggregation.status) {
            OphalenStatus.GEREED -> aggregation
            OphalenStatus.BEZIG -> throw SessiecacheException.OphalenBezig(
                "Berichten worden momenteel opgehaald. Wacht tot het ophalen is afgerond.",
            )
            OphalenStatus.FOUT -> throw SessiecacheException.OphalenMislukt(
                "Het ophalen van berichten is mislukt. Start het ophalen van berichten opnieuw.",
            )
        }
    }

    private fun effectieveGrootte(paginaGrootte: Int?): Int = (paginaGrootte ?: 20).coerceAtMost(100)

    private fun <T> awaitOrServiceUnavailable(block: () -> Uni<T>): T {
        try {
            return block().await().atMost(timeout)
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
        // Cache-operatie hing langer dan de facade-await-timeout — log warn met cause zodat
        // operations niet alleen het onbereikbaar-pad ziet maar ook welke await het was. Silent
        // discard verbergt prestatie-regressies in Redis.
        is java.util.concurrent.TimeoutException -> {
            log.warnf(e, "Cache-operatie overschreed timeout van %s; onbereikbaar naar caller", timeout)
            SessiecacheException.Onbereikbaar("Cache niet bereikbaar. Probeer het later opnieuw", e)
        }
        // Transiente schrijf-contentie: bron logt al op warn. Retriable onbereikbaar
        // i.p.v. de misleidende not-found die een null-resultaat zou opleveren.
        is CacheContentieException ->
            SessiecacheException.Onbereikbaar("Cache tijdelijk niet bij te werken. Probeer het later opnieuw", e)
        // Invoervalidatie (BerichtValidator, Leesstatus.fromWire): caller-fout,
        // geen infrastructuurfout — laat schrijfBericht dit naar OngeldigeInvoer vertalen.
        is IllegalArgumentException -> e
        // Cache-deserialisatie-fout = data-integriteit-issue (verkeerde versie, corrupte hash),
        // niet "Redis onbereikbaar". Onleesbaar zonder de verkeerde infrastructuur-diagnose.
        is com.fasterxml.jackson.core.JsonProcessingException -> {
            log.errorf(e, "Cache-data niet deserialiseerbaar (corruptie of schema-drift)")
            SessiecacheException.Onleesbaar("Cache-data niet leesbaar.", e)
        }
        // Hash-velden ontbreken of onleesbaar → eigen data-issue, niet bereikbaarheids-issue.
        is CacheCorruptedException -> {
            log.errorf(e, "Cache-hash corrupt")
            SessiecacheException.Onleesbaar("Cache-data niet leesbaar.", e)
        }
        else -> {
            log.errorf(e, "Cache-operatie mislukt")
            SessiecacheException.Onbereikbaar("Cache niet bereikbaar.", e)
        }
    }
}
