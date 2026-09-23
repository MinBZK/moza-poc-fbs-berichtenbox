package nl.rijksoverheid.moz.fbs.berichtenuitvraag.uitvraag

import com.fasterxml.jackson.annotation.JsonInclude
import com.fasterxml.jackson.annotation.JsonPropertyOrder
import com.fasterxml.jackson.annotation.JsonValue
import io.smallrye.common.annotation.Blocking
import io.smallrye.mutiny.Multi
import jakarta.enterprise.context.ApplicationScoped
import jakarta.validation.constraints.NotNull
import jakarta.validation.constraints.Pattern
import jakarta.ws.rs.GET
import jakarta.ws.rs.HeaderParam
import jakarta.ws.rs.Path
import jakarta.ws.rs.Produces
import jakarta.ws.rs.core.MediaType
import jakarta.ws.rs.core.Response
import nl.mijnoverheidzakelijk.ldv.logboekdataverwerking.Logboek
import nl.mijnoverheidzakelijk.ldv.logboekdataverwerking.LogboekContext
import nl.rijksoverheid.moz.fbs.berichtensessiecache.Sessiecache
import nl.rijksoverheid.moz.fbs.berichtensessiecache.berichten.SessieGebeurtenis
import nl.rijksoverheid.moz.fbs.berichtensessiecache.berichten.toSamenvatting
import nl.rijksoverheid.moz.fbs.berichtenuitvraag.ApiInfo
import nl.rijksoverheid.moz.fbs.berichtenuitvraag.ProcessingActivities
import nl.rijksoverheid.moz.fbs.berichtenuitvraag.api.model.BerichtSamenvatting
import nl.rijksoverheid.moz.fbs.common.exception.FbsFoutException
import nl.rijksoverheid.moz.fbs.common.exception.Foutcode
import nl.rijksoverheid.moz.fbs.common.identificatie.Identificatienummer
import org.eclipse.microprofile.config.inject.ConfigProperty
import org.jboss.logging.Logger
import org.jboss.resteasy.reactive.ResponseHeader
import org.jboss.resteasy.reactive.RestStreamElementType
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * SSE-endpoint `GET /berichten/_volgen`: een geopende berichtenbox krijgt hierover elk bericht
 * dat tijdens de sessie binnenkomt. Buiten codegen om dezelfde reden als `_ophalen` (de
 * generator kent geen `Multi<>`); het pad staat wél in de spec als contractbron.
 *
 * Eén connection per open berichtenbox, en die blijft open tot de berichtenbox sluit of de
 * maximale duur (`berichtensessiecache.volg-max-duur`) verstrijkt. Twee
 * plafonds begrenzen wat dat kost aan geheugen en luisteraars: één per pod, en een klein plafond
 * per ontvanger zodat één aanroeper de pod niet voor iedereen kan vullen. Wie erboven valt, krijgt
 * een 503 met `Retry-After` en houdt een bruikbare, alleen niet vanzelf verversende lijst.
 *
 * De plek wordt gereserveerd vóór de cache-lookup, zodat een geweigerde aanvraag geen Redis kost,
 * en teruggegeven als de stream niet tot stand komt of eindigt.
 */
@Path("/berichten/_volgen")
@ApplicationScoped
class VolgenSseResource(
    private val sessiecache: Sessiecache,
    private val afzendernamen: Afzendernamen,
    private val logboekContext: LogboekContext,
    @param:ConfigProperty(name = "berichtenuitvraag.volgen.max-connections", defaultValue = "2000")
    private val maxConnections: Int,
    // Een paar tabbladen en apparaten tegelijk, geen honderden.
    @param:ConfigProperty(name = "berichtenuitvraag.volgen.max-connections-per-ontvanger", defaultValue = "5")
    private val maxConnectionsPerOntvanger: Int,
) {
    private val log = Logger.getLogger(VolgenSseResource::class.java)

    private val openConnections = AtomicInteger()

    private val openPerOntvanger = ConcurrentHashMap<Identificatienummer, Int>()

    init {
        require(maxConnections > 0) {
            "berichtenuitvraag.volgen.max-connections ($maxConnections) moet groter zijn dan 0"
        }

        require(maxConnectionsPerOntvanger in 1..maxConnections) {
            "berichtenuitvraag.volgen.max-connections-per-ontvanger ($maxConnectionsPerOntvanger) " +
                "moet tussen 1 en berichtenuitvraag.volgen.max-connections ($maxConnections) liggen"
        }
    }

    @GET
    @Blocking
    @Logboek(name = "uitvraag-volgen-sse", processingActivityId = ProcessingActivities.UITVRAAG_LEZEN)
    @Produces(MediaType.SERVER_SENT_EVENTS)
    // De ApiVersionFilter (een ContainerResponseFilter) komt bij een streaming Multi niet aan de
    // beurt: de headers zijn dan al verstuurd. Hier staat de waarde er vóór de eerste byte.
    @ResponseHeader(name = "API-Version", value = [ApiInfo.API_VERSION])
    @RestStreamElementType(MediaType.APPLICATION_JSON)
    fun volgen(
        @HeaderParam("X-Ontvanger")
        @NotNull
        @Pattern(regexp = ONTVANGER_PATTERN)
        xOntvanger: String,
    ): Multi<VolgGebeurtenis> {
        registreerLdvSubject(logboekContext, xOntvanger)

        val ontvanger = Identificatienummer.fromHeader(xOntvanger)

        reserveer(ontvanger)

        val stream = try {
            leesUitCache(log, "cache-volgen") { sessiecache.volg(ontvanger) }
        } catch (e: Exception) {
            geefVrij(ontvanger)

            throw e
        }

        return stream
            .map { naarVolgGebeurtenis(it) }
            .onFailure().invoke { fout -> meldAfgebroken(fout, ontvanger) }
            .onTermination().invoke { -> geefVrij(ontvanger) }
    }

    /**
     * Ná het openen is de status al 200, dus zonder deze regel verdwijnt de oorzaak uit de log. Het
     * logboek ziet hem evenmin: dat sluit zijn registratie af zodra de stream is teruggegeven (zie
     * het open punt over LDV bij lange streams in het ontwerp). Zelfde vorm als bij `_ophalen`: een
     * `errorId` om de regel aan een melding te koppelen, en alleen klassenamen, want de cause-keten
     * kan een URL met de ontvanger bevatten.
     *
     * Op `warn` en niet op `error` zoals daar: valt het abonnement van een pod weg, dan breken al
     * zijn streams tegelijk af, en die oorzaak logt de sessiecache al één keer per pod. Per stream
     * een error-regel zou één incident als honderden laten lezen.
     */
    private fun meldAfgebroken(fout: Throwable, ontvanger: Identificatienummer) {
        log.warnf(
            "(errorId=%s) Gevolgde sessie afgebroken (ontvanger.type=%s, fout=%s, oorzaak=%s)",
            UUID.randomUUID(),
            ontvanger.type,
            fout.javaClass.simpleName,
            fout.cause?.javaClass?.simpleName ?: "geen",
        )
    }

    private fun reserveer(ontvanger: Identificatienummer) {
        if (openConnections.incrementAndGet() > maxConnections) {
            openConnections.decrementAndGet()
            log.warnf("Plafond van %d gevolgde sessies op deze pod bereikt; verbinding geweigerd", maxConnections)

            throw teVeel("Te veel open berichtenboxen tegelijk. Probeer het straks opnieuw.")
        }

        // `merge` is in Java nullable, maar met een waarde en een optelling komt er nooit null uit.
        val eigen = checkNotNull(openPerOntvanger.merge(ontvanger, 1, Int::plus))

        if (eigen > maxConnectionsPerOntvanger) {
            geefVrij(ontvanger)
            log.infof("Plafond van %d open berichtenboxen per ontvanger bereikt (ontvanger.type=%s)", maxConnectionsPerOntvanger, ontvanger.type)

            throw teVeel("Deze berichtenbox staat al te vaak tegelijk open. Sluit een ander venster of probeer het straks opnieuw.")
        }
    }

    private fun geefVrij(ontvanger: Identificatienummer) {
        openConnections.decrementAndGet()
        openPerOntvanger.computeIfPresent(ontvanger) { _, aantal -> (aantal - 1).takeIf { it > 0 } }
    }

    private fun teVeel(melding: String) = FbsFoutException(
        Foutcode.TE_VEEL_OPEN_BERICHTENBOXEN,
        Response.Status.SERVICE_UNAVAILABLE,
        melding,
        retryAfterSeconden = RETRY_AFTER_SECONDEN,
    )

    private fun naarVolgGebeurtenis(gebeurtenis: SessieGebeurtenis): VolgGebeurtenis = when (gebeurtenis) {
        SessieGebeurtenis.VolgenGestart -> VolgGebeurtenis.gestart()
        SessieGebeurtenis.Hartslag -> VolgGebeurtenis.hartslag()
        SessieGebeurtenis.SessieVerlopen -> VolgGebeurtenis.verlopen()
        is SessieGebeurtenis.BerichtBijgekomen -> VolgGebeurtenis.bijgekomen(
            UitvraagDtoMapper.toApiSamenvatting(
                gebeurtenis.bericht.toSamenvatting(),
                afzendernamen.naamVoor(gebeurtenis.bericht),
            ),
        )
    }

    private companion object {
        // Gelijk aan de overige 503's van deze dienst.
        const val RETRY_AFTER_SECONDEN = 30
    }
}

/** Soort volg-bericht; op de lijn de discriminator in het `event`-veld (spec: `VolgEvent`). */
enum class VolgEventType(@get:JsonValue val value: String) {
    VOLGEN_GESTART("volgen-gestart"),
    BERICHT_BIJGEKOMEN("bericht-bijgekomen"),
    HARTSLAG("hartslag"),
    SESSIE_VERLOPEN("sessie-verlopen"),
}

/**
 * Wire-vorm van één volg-bericht. Alleen `bericht-bijgekomen` draagt een `bericht`, en dat is hier
 * afgedwongen en niet alleen beschreven: de constructor is privé, en de fabrieken laten geen soort
 * zonder bericht toe waar de spec er een eist, of mét een waar hij er geen kent. `NON_NULL` zorgt
 * dat het ontbrekende veld wegblijft in plaats van als `null` op de lijn te komen.
 *
 * Geen `data class`: die geeft een publieke `copy()`, en daarmee zou precies die combinatie weer te
 * bouwen zijn.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonPropertyOrder("event", "bericht")
class VolgGebeurtenis private constructor(
    val event: VolgEventType,
    val bericht: BerichtSamenvatting?,
) {
    companion object {
        fun gestart() = VolgGebeurtenis(VolgEventType.VOLGEN_GESTART, null)

        fun bijgekomen(bericht: BerichtSamenvatting) = VolgGebeurtenis(VolgEventType.BERICHT_BIJGEKOMEN, bericht)

        fun hartslag() = VolgGebeurtenis(VolgEventType.HARTSLAG, null)

        fun verlopen() = VolgGebeurtenis(VolgEventType.SESSIE_VERLOPEN, null)
    }
}
