package nl.rijksoverheid.moz.berichtensessiecache.berichten

import io.smallrye.mutiny.Uni
import jakarta.enterprise.context.ApplicationScoped
import jakarta.ws.rs.ProcessingException
import jakarta.ws.rs.WebApplicationException
import nl.rijksoverheid.moz.berichtensessiecache.magazijn.MagazijnClientFactory
import org.jboss.logging.Logger
import java.time.Duration
import java.util.UUID

@ApplicationScoped
class BerichtensessiecacheService(
    private val berichtenCache: BerichtenCache,
    private val clientFactory: MagazijnClientFactory,
) {
    private val log = Logger.getLogger(BerichtensessiecacheService::class.java)

    companion object {
        private val CACHE_TIMEOUT = Duration.ofSeconds(5)
    }

    fun getBerichten(page: Int, pageSize: Int, ontvanger: String?, afzender: String?): Uni<BerichtenPage> {
        log.debugf("Ophalen berichten uit cache: page=%d, pageSize=%d, ontvanger=%s", page, pageSize, ontvanger)
        val key = BerichtenCache.cacheKey(ontvanger)
        return berichtenCache.getPage(key, page, pageSize)
            .map { it ?: BerichtenPage(emptyList(), page, pageSize, 0L, 0) }
    }

    fun getAggregationStatus(ontvanger: String?): Uni<AggregationStatus?> {
        val key = BerichtenCache.cacheKey(ontvanger)
        return berichtenCache.getAggregationStatus(key)
    }

    fun getBerichtById(berichtId: UUID): BerichtLookupResult {
        log.debugf("Ophalen bericht: %s", berichtId)

        // Probeer eerst uit de cache — bij fouten fallback naar magazijnen
        val cached = try {
            berichtenCache.getById(berichtId).await().atMost(CACHE_TIMEOUT)
        } catch (e: Exception) {
            log.warnf(e, "Cache-lookup mislukt voor %s, fallback naar magazijnen", berichtId)
            null
        }
        if (cached != null) {
            log.debugf("Bericht %s gevonden in cache", berichtId)
            return BerichtLookupResult.Found(cached)
        }

        // Fallback: bevraag de magazijnen
        log.debugf("Bericht %s niet in cache, bevraag magazijnen", berichtId)
        val clients = clientFactory.getAllClients()
        var heeftSuccessvolAntwoord = false
        for ((magazijnId, client) in clients) {
            try {
                val bericht = client.getBerichtById(berichtId.toString())
                heeftSuccessvolAntwoord = true
                if (bericht != null) return BerichtLookupResult.Found(bericht)
            } catch (e: WebApplicationException) {
                if (e.response?.status == 404) {
                    heeftSuccessvolAntwoord = true
                } else {
                    log.errorf(e, "Magazijn %s gaf HTTP %d voor bericht %s", magazijnId, e.response?.status, berichtId)
                }
            } catch (e: ProcessingException) {
                log.errorf(e, "Magazijn %s niet bereikbaar voor bericht %s", magazijnId, berichtId)
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
                throw e
            }
        }
        if (!heeftSuccessvolAntwoord) {
            log.errorf("Alle %d magazijnen onbereikbaar voor bericht %s", clients.size, berichtId)
        }
        return if (heeftSuccessvolAntwoord) BerichtLookupResult.NotFound else BerichtLookupResult.AllMagazijnenFailed
    }

    fun zoekBerichten(q: String, page: Int, pageSize: Int, ontvanger: String?, afzender: String?): Uni<BerichtenPage> {
        log.debugf("Zoeken berichten uit cache: q=%s, page=%d, pageSize=%d", q, page, pageSize)
        val key = BerichtenCache.cacheKey(ontvanger)
        return berichtenCache.getAll(key)
            .map { berichten ->
                val gefilterd = berichten.filter {
                    it.onderwerp.contains(q, ignoreCase = true) ||
                        it.afzender.contains(q, ignoreCase = true)
                }
                val start = page * pageSize
                val slice = gefilterd.drop(start).take(pageSize)
                val totalPages = if (gefilterd.isEmpty()) 0 else (gefilterd.size + pageSize - 1) / pageSize
                BerichtenPage(slice, page, pageSize, gefilterd.size.toLong(), totalPages)
            }
    }
}

sealed class BerichtLookupResult {
    data class Found(val bericht: Bericht) : BerichtLookupResult()
    data object NotFound : BerichtLookupResult()
    data object AllMagazijnenFailed : BerichtLookupResult()
}

data class BerichtenPage(
    val berichten: List<Bericht>,
    val page: Int,
    val pageSize: Int,
    val totalElements: Long,
    val totalPages: Int,
) {
    init {
        require(page >= 0) { "page mag niet negatief zijn" }
        require(pageSize > 0) { "pageSize moet positief zijn" }
        require(totalElements >= 0) { "totalElements mag niet negatief zijn" }
        require(totalPages >= 0) { "totalPages mag niet negatief zijn" }
    }
}
