package nl.rijksoverheid.moz.berichtensessiecache.berichten

import io.smallrye.mutiny.Multi
import io.smallrye.mutiny.Uni
import io.smallrye.mutiny.infrastructure.Infrastructure
import jakarta.inject.Inject
import jakarta.ws.rs.GET
import jakarta.ws.rs.Path
import jakarta.ws.rs.Produces
import jakarta.ws.rs.QueryParam
import jakarta.ws.rs.WebApplicationException
import jakarta.ws.rs.core.MediaType
import jakarta.ws.rs.core.Response
import nl.mijnoverheidzakelijk.ldv.logboekdataverwerking.Logboek
import nl.mijnoverheidzakelijk.ldv.logboekdataverwerking.LogboekContext
import nl.rijksoverheid.moz.berichtensessiecache.magazijn.MagazijnClientFactory
import nl.rijksoverheid.moz.berichtensessiecache.magazijn.MagazijnResult
import org.jboss.logging.Logger
import org.jboss.resteasy.reactive.RestStreamElementType
import java.time.Duration
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.ConcurrentLinkedQueue

@Path("/api/v1/berichten/_ophalen")
class BerichtenOphalenResource(
    private val clientFactory: MagazijnClientFactory,
    private val berichtenCache: BerichtenCache,
) {
    private val log = Logger.getLogger(BerichtenOphalenResource::class.java)

    @Inject
    lateinit var logboekContext: LogboekContext

    @GET
    @Logboek(
        name = "ophalen-berichten-uit-magazijnen",
        processingActivityId = "https://register.example.com/verwerkingen/berichten-ophalen-aggregatie",
    )
    @Produces(MediaType.SERVER_SENT_EVENTS)
    @RestStreamElementType(MediaType.APPLICATION_JSON)
    fun ophalenBerichten(
        @QueryParam("ontvanger") ontvanger: String?,
    ): Multi<MagazijnStatusEvent> {
        if (ontvanger.isNullOrBlank()) {
            throw WebApplicationException("Parameter 'ontvanger' is verplicht.", Response.Status.BAD_REQUEST)
        }

        logboekContext.dataSubjectId = ontvanger
        logboekContext.dataSubjectType = "ontvanger"

        val cacheKey = BerichtenCache.cacheKey(ontvanger)

        val clients = clientFactory.getAllClients()

        val alleBerichten = ConcurrentLinkedQueue<Bericht>()
        val geslaagd = AtomicInteger(0)
        val mislukt = AtomicInteger(0)

        val bezigStatus = AggregationStatus(
            status = OphalenStatus.BEZIG,
            totaalMagazijnen = clients.size,
        )

        // Voorkom concurrent ophalen voor dezelfde ontvanger, dan sla BEZIG status op
        val initStream = berichtenCache.getAggregationStatus(cacheKey)
            .chain { huidigeStatus ->
                if (huidigeStatus?.status == OphalenStatus.BEZIG) {
                    Uni.createFrom().failure(
                        WebApplicationException(
                            "Berichten worden momenteel al opgehaald voor deze ontvanger. Wacht tot het ophalen is afgerond.",
                            409,
                        ),
                    )
                } else {
                    berichtenCache.storeAggregationStatus(cacheKey, bezigStatus)
                }
            }
            .replaceWith(Multi.createFrom().empty<MagazijnStatusEvent>())
            .toMulti().flatMap { it }
            .onFailure(WebApplicationException::class.java).invoke { _ -> /* laat WebApplicationException doorbubbelen */ }
            .onFailure().invoke { e ->
                if (e !is WebApplicationException) {
                    log.errorf(e, "Kon aggregatie-status BEZIG niet opslaan in cache voor key=%s", cacheKey)
                }
            }
            .onFailure { it !is WebApplicationException }.recoverWithCompletion()

        val magazijnStreams = clients.map { (magazijnId, client) ->
            val naam = clientFactory.getNaam(magazijnId)

            val bezigEvent = MagazijnStatusEvent(
                event = EventType.MAGAZIJN_STATUS,
                magazijnId = magazijnId,
                naam = naam,
                status = MagazijnStatus.BEZIG,
            )

            val resultUni = Uni.createFrom().item { client }
                .onItem().transform { c -> c.getBerichten(ontvanger, null) }
                .runSubscriptionOn(Infrastructure.getDefaultWorkerPool())
                .ifNoItem().after(Duration.ofSeconds(10)).fail()
                .map<MagazijnResult> { response ->
                    MagazijnResult.Success(magazijnId, naam, response.berichten)
                }
                .onFailure(Exception::class.java).recoverWithItem { error ->
                    log.errorf(error, "Magazijn %s (%s) ophalen mislukt", magazijnId, naam)
                    MagazijnResult.Failure(magazijnId, naam, error)
                }

            val resultStream = resultUni.toMulti().map { result ->
                when (result) {
                    is MagazijnResult.Success -> {
                        alleBerichten.addAll(result.berichten)
                        geslaagd.incrementAndGet()
                        MagazijnStatusEvent(
                            event = EventType.MAGAZIJN_STATUS,
                            magazijnId = magazijnId,
                            naam = naam,
                            status = MagazijnStatus.OK,
                            aantalBerichten = result.berichten.size,
                        )
                    }
                    is MagazijnResult.Failure -> {
                        mislukt.incrementAndGet()
                        val isTimeout = result.error is io.smallrye.mutiny.TimeoutException
                        MagazijnStatusEvent(
                            event = EventType.MAGAZIJN_STATUS,
                            magazijnId = magazijnId,
                            naam = naam,
                            status = if (isTimeout) MagazijnStatus.TIMEOUT else MagazijnStatus.FOUT,
                            foutmelding = if (isTimeout) "Magazijn reageerde niet binnen de timeout" else "Magazijn tijdelijk niet bereikbaar",
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
                    val berichten = alleBerichten.toList()
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
                            logboekContext.status = if (mislukt.get() == 0) {
                                io.opentelemetry.api.trace.StatusCode.OK
                            } else {
                                io.opentelemetry.api.trace.StatusCode.ERROR
                            }
                            MagazijnStatusEvent(
                                event = EventType.OPHALEN_GEREED,
                                totaalBerichten = alleBerichten.size,
                                geslaagd = geslaagd.get(),
                                mislukt = mislukt.get(),
                                totaalMagazijnen = clients.size,
                            )
                        }
                }
                .onFailure(Exception::class.java).recoverWithItem { error ->
                    log.errorf(error, "Fout bij opslaan in cache na aggregatie")
                    MagazijnStatusEvent(
                        event = EventType.OPHALEN_GEREED,
                        totaalBerichten = alleBerichten.size,
                        geslaagd = geslaagd.get(),
                        mislukt = mislukt.get(),
                        totaalMagazijnen = clients.size,
                        foutmelding = "Interne fout bij opslaan van resultaten",
                    )
                }
                .toMulti()
        )
    }
}
