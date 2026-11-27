package nl.rijksoverheid.moz.fbs.stelselregister.specificatie

import io.smallrye.openapi.runtime.io.Format
import io.quarkus.smallrye.openapi.runtime.OpenApiDocumentService
import jakarta.enterprise.context.ApplicationScoped
import jakarta.ws.rs.core.MediaType
import jakarta.ws.rs.core.Response
import nl.rijksoverheid.moz.fbs.stelselregister.api.SpecificatieApi

/**
 * Levert het OpenAPI-document ook onder het basisadres, op `/api/v1/openapi.json`, waar de API
 * Design Rules het zoeken. De root (`/openapi.json`, uit `quarkus.smallrye-openapi.path`) blijft
 * voor Swagger UI en bestaande afnemers; beide paden leveren hetzelfde document uit dezelfde bron.
 *
 * Een resource, geen Vert.x-reroute of verplaatst SmallRye-pad: die geven op een niet-ondersteunde
 * methode 404 in plaats van 405, en dan is niet meer te toetsen dat dit pad uit de spec een route
 * heeft.
 *
 * [OpenApiDocumentService] is een runtime-bean van Quarkus en geen gedocumenteerde API. Een
 * onbekende documentnaam levert stil een leeg document op; de test die beide paden vergelijkt,
 * vangt een wijziging daarin.
 *
 * Anders dan in het magazijn en de uitvraag zet deze resource zelf geen CORS-header: het
 * stelselregister is als geheel publiek, en de CORS-configuratie van de dienst geldt ook hier.
 */
@ApplicationScoped
class SpecificatieResource(private val documenten: OpenApiDocumentService) : SpecificatieApi {

    override fun getOpenApiDocument(): Response =
        Response.ok(documenten.getDocument(STANDAARD_DOCUMENT, Format.JSON), MediaType.APPLICATION_JSON_TYPE).build()

    private companion object {

        /** De naam waaronder Quarkus het enige, niet-benoemde document bewaart. */
        const val STANDAARD_DOCUMENT = "<default>"
    }
}
