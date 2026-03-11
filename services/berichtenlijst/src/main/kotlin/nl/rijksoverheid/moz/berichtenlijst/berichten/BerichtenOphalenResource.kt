package nl.rijksoverheid.moz.berichtenlijst.berichten

import io.smallrye.mutiny.Multi
import io.smallrye.mutiny.Uni
import io.smallrye.mutiny.infrastructure.Infrastructure
import jakarta.ws.rs.GET
import jakarta.ws.rs.Path
import jakarta.ws.rs.Produces
import jakarta.ws.rs.QueryParam
import jakarta.ws.rs.core.MediaType
import nl.rijksoverheid.moz.berichtenlijst.magazijn.MagazijnClientFactory
import nl.rijksoverheid.moz.berichtenlijst.magazijn.MagazijnResult
import org.jboss.logging.Logger
import org.jboss.resteasy.reactive.RestStreamElementType
import java.time.Duration
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

@Path("/api/v1/berichten/ophalen")
class BerichtenOphalenResource(
    private val clientFactory: MagazijnClientFactory,
    private val berichtenCache: BerichtenCache,
) {
    private val log = Logger.getLogger(BerichtenOphalenResource::class.java)

    @GET
    @Produces(MediaType.SERVER_SENT_EVENTS)
    @RestStreamElementType(MediaType.APPLICATION_JSON)
    fun ophalenBerichten(
        @QueryParam("ontvanger") ontvanger: String?,
    ): Multi<MagazijnStatusEvent> {
        val clients = clientFactory.getAllClients()
        val cacheKey = BerichtenCache.cacheKey(ontvanger)

        val alleBerichten = AtomicReference<MutableList<Bericht>>(mutableListOf())
        val geslaagd = AtomicInteger(0)
        val mislukt = AtomicInteger(0)

        // Markeer ophalen als BEZIG
        val bezigStatus = AggregationStatus(
            status = OphalenStatus.BEZIG,
            totaalMagazijnen = clients.size,
        )

        val initStream = berichtenCache.storeAggregationStatus(cacheKey, bezigStatus)
            .replaceWith(Multi.createFrom().empty<MagazijnStatusEvent>())
            .toMulti().flatMap { it }

        val magazijnStreams = clients.map { (magazijnId, client) ->
            val naam = clientFactory.getNaam(magazijnId)

            val bezigEvent = MagazijnStatusEvent(
                event = "magazijn-status",
                magazijnId = magazijnId,
                naam = naam,
                status = "BEZIG",
            )

            val resultUni = Uni.createFrom().item { client }
                .onItem().transform { c -> c.getBerichten(ontvanger, null) }
                .runSubscriptionOn(Infrastructure.getDefaultWorkerPool())
                .ifNoItem().after(Duration.ofSeconds(10)).fail()
                .map<MagazijnResult> { response ->
                    MagazijnResult.Success(magazijnId, naam, response.berichten)
                }
                .onFailure().recoverWithItem { error ->
                    MagazijnResult.Failure(magazijnId, naam, error)
                }

            val resultStream = resultUni.toMulti().map { result ->
                when (result) {
                    is MagazijnResult.Success -> {
                        alleBerichten.get().addAll(result.berichten)
                        geslaagd.incrementAndGet()
                        MagazijnStatusEvent(
                            event = "magazijn-status",
                            magazijnId = magazijnId,
                            naam = naam,
                            status = "OK",
                            aantalBerichten = result.berichten.size,
                        )
                    }
                    is MagazijnResult.Failure -> {
                        mislukt.incrementAndGet()
                        val isTimeout = result.error is io.smallrye.mutiny.TimeoutException
                        MagazijnStatusEvent(
                            event = "magazijn-status",
                            magazijnId = magazijnId,
                            naam = naam,
                            status = if (isTimeout) "TIMEOUT" else "FOUT",
                            foutmelding = result.error.message,
                        )
                    }
                }
            }

            Multi.createBy().concatenating().streams(
                Multi.createFrom().item(bezigEvent),
                resultStream,
            )
        }

        val allMagazijnEvents = Multi.createBy().merging().streams(magazijnStreams)

        return Multi.createBy().concatenating().streams(
            initStream,
            allMagazijnEvents,
            Uni.createFrom().item { Unit }
                .chain { _ ->
                    val berichten = alleBerichten.get().toList()
                    berichtenCache.store(cacheKey, berichten)
                        .chain { _ ->
                            val status = AggregationStatus(
                                status = OphalenStatus.GEREED,
                                totaalMagazijnen = clients.size,
                                geslaagd = geslaagd.get(),
                                mislukt = mislukt.get(),
                            )
                            berichtenCache.storeAggregationStatus(cacheKey, status)
                        }
                        .map { _ ->
                            MagazijnStatusEvent(
                                event = "ophalen-gereed",
                                totaalBerichten = alleBerichten.get().size,
                                geslaagd = geslaagd.get(),
                                mislukt = mislukt.get(),
                                totaalMagazijnen = clients.size,
                            )
                        }
                }
                .toMulti()
        )
    }
}
