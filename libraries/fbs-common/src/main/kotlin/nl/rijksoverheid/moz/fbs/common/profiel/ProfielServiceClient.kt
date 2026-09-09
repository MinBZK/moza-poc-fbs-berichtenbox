package nl.rijksoverheid.moz.fbs.common.profiel

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.core.JsonProcessingException
import jakarta.ws.rs.Consumes
import jakarta.ws.rs.POST
import jakarta.ws.rs.Path
import jakarta.ws.rs.Produces
import jakarta.ws.rs.ProcessingException
import jakarta.ws.rs.core.MediaType
import nl.rijksoverheid.moz.fbs.common.fsc.ProfielFscOutwayHeadersFilter
import org.eclipse.microprofile.faulttolerance.Retry
import org.eclipse.microprofile.rest.client.annotation.RegisterProvider
import org.eclipse.microprofile.rest.client.inject.RegisterRestClient
import java.net.UnknownHostException

/**
 * REST-client naar de MOZA Profiel Service (zie github.com/MinBZK/moza-profiel-service).
 * Gedeelde dependency voor berichtenmagazijn (validatie-flow) en berichtensessiecache
 * (MagazijnResolver-flow); één Profiel-upstream betekent één client-definitie.
 *
 * Het identificatienummer gaat in de request-body, niet in het pad: een webadres wordt
 * onderweg breder vastgelegd (toegangslogs, tussenliggende voorzieningen, foutrapportages)
 * dan de body, en voor een burger is dat nummer het BSN.
 *
 * DTO's zijn een minimale subset van de upstream-schema's; `@JsonIgnoreProperties`
 * negeert velden die we niet gebruiken (createdAt, lastUpdated, contactgegevens, etc.)
 * zodat upstream-veldtoevoegingen onze deserialisatie niet breken.
 *
 * Registratie hier en niet per service via `quarkus.rest-client.profiel-service.providers`:
 * beide consumers delen deze interface, dus één plek voorkomt dat de registratie tussen
 * services uit elkaar loopt. De filter is een no-op zolang er geen grant-hash geconfigureerd is.
 */
@RegisterRestClient(configKey = "profiel-service")
@RegisterProvider(ProfielFscOutwayHeadersFilter::class)
interface ProfielServiceClient {

    /**
     * `@Retry` op transient I/O-fouten zodat een korte hapering of TCP-reset
     * geen 503 naar de aanleveraar veroorzaakt. Alleen `ProcessingException`
     * retryen: dat is het JAX-RS-wrapper-type voor connection-resets,
     * read-timeouts en andere netwerk-fouten waar de upstream geen definitief
     * antwoord op gaf. Een `WebApplicationException` (4xx/5xx) is een
     * deterministisch upstream-antwoord en wordt NIET geretryed — anders zou
     * elke onbekende ontvanger 3x worden opgevraagd (retry-storm) en zou een
     * 401/403 (auth-misser) onnodig pogingen veroorzaken op een upstream die
     * een rate-limit of token-lock kan triggeren.
     *
     * `abortOn`: deterministische fouten waar retry geen waarde toevoegt en alleen
     * upstream-druk genereert:
     * - `JsonProcessingException` — parse-fout = contract-drift, herhaal levert
     *   dezelfde fout en verspilt resources.
     * - `UnknownHostException` — DNS-miss = config-fout (verkeerde hostname of
     *   ontbrekende DNS-record); retry herhaalt dezelfde DNS-lookup en vertraagt
     *   de 503-response zonder zinvolle herstel-kans.
     */
    @POST
    @Path("/api/profielservice/v1/partij")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    // TODO(test): regressie-test voor UnknownHostException-abort vereist DNS-niveau
    // mock (WireMock kan UHE niet gooien — WireMock zelf IS bereikbaar). Mogelijk via
    // %test-profile override naar `.invalid`-TLD + count-assert op effective-1-call.
    @Retry(
        maxRetries = 2,
        delay = 200,
        retryOn = [ProcessingException::class],
        abortOn = [JsonProcessingException::class, UnknownHostException::class],
    )
    fun getPartij(partijRequest: PartijRequest): PartijResponse
}

/**
 * Aanvraag-body voor het ophalen van een profiel. `dienstverlener`/`dienstNaam` uit het
 * upstream-contract laten we weg: dat filtert de voorkeuren aan de bron op scope, terwijl
 * [ProfielVoorkeuren] die filtering al doet op de volledige respons — één plek waar de
 * opt-in-regel leeft, in plaats van twee die uit elkaar kunnen lopen.
 */
data class PartijRequest(
    val identificatieType: String,
    val identificatieNummer: String,
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class PartijResponse(
    val voorkeuren: List<VoorkeurResponse> = emptyList(),
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class VoorkeurResponse(
    val voorkeurType: String,
    val waarde: String? = null,
    val scopes: List<ScopeResponse> = emptyList(),
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class ScopeResponse(
    val partij: IdentificatieResponse? = null,
    val dienst: DienstResponse? = null,
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class IdentificatieResponse(
    val identificatieType: String,
    val identificatieNummer: String,
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class DienstResponse(
    val id: Long? = null,
    val beschrijving: String? = null,
)
