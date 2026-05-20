package nl.rijksoverheid.moz.fbs.berichtenmagazijn.validatie

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import jakarta.ws.rs.GET
import jakarta.ws.rs.NotFoundException
import jakarta.ws.rs.Path
import jakarta.ws.rs.PathParam
import jakarta.ws.rs.Produces
import jakarta.ws.rs.core.MediaType
import org.eclipse.microprofile.faulttolerance.Retry
import org.eclipse.microprofile.rest.client.inject.RegisterRestClient

/**
 * REST-client naar de MOZA Profiel Service (zie github.com/MinBZK/moza-profiel-service).
 * We halen het volledige profiel van de ontvanger op en bepalen client-side of er een
 * voorkeur "OntvangViaBerichtenbox" bestaat met een scope die naar de afzender wijst.
 *
 * De profiel-service zet identificatie in pad-parameters — afwijkend van onze interne
 * PII-richtlijn ("BSN nooit in URL"). We zijn hier gebonden aan het externe contract;
 * binnen de berichtenmagazijn-service zelf gaat BSN niet in URL.
 *
 * DTO's zijn een minimale subset van de upstream-schema's; `@JsonIgnoreProperties`
 * negeert velden die we niet gebruiken (createdAt, lastUpdated, contactgegevens, etc.)
 * zodat upstream-veldtoevoegingen onze deserialisatie niet breken.
 */
@RegisterRestClient(configKey = "profiel-service")
interface ProfielServiceClient {

    /**
     * `@Retry` op transient I/O-fouten zodat een korte hapering of TCP-reset
     * geen 503 naar de aanleveraar veroorzaakt. `NotFoundException` (404) is
     * een legitiem antwoord ("partij onbekend") en wordt afgehandeld door
     * [BerichtValidatieService] — die wordt geabort, niet retried.
     */
    @GET
    @Path("/api/profielservice/v1/{identificatieType}/{identificatieNummer}")
    @Produces(MediaType.APPLICATION_JSON)
    @Retry(maxRetries = 2, delay = 200, abortOn = [NotFoundException::class])
    fun getPartij(
        @PathParam("identificatieType") identificatieType: String,
        @PathParam("identificatieNummer") identificatieNummer: String,
    ): PartijResponse
}

@JsonIgnoreProperties(ignoreUnknown = true)
data class PartijResponse(
    val partijId: Long? = null,
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
