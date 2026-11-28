package nl.rijksoverheid.moz.fbs.beheerconsole.specificatie

import io.quarkus.smallrye.openapi.runtime.OpenApiDocumentService
import io.smallrye.openapi.runtime.io.Format
import jakarta.enterprise.context.ApplicationScoped
import jakarta.ws.rs.core.MediaType
import jakarta.ws.rs.core.Response
import nl.rijksoverheid.moz.fbs.beheerconsole.api.SpecificatieApi

/**
 * Levert het OpenAPI-document ook onder het basisadres, op `/api/v1/openapi.json`, waar de API
 * Design Rules het zoeken. De root (`/openapi.json`, uit `quarkus.smallrye-openapi.path`) blijft
 * voor bestaande afnemers en, in dev-mode, Swagger UI; beide paden leveren hetzelfde document uit
 * dezelfde bron.
 *
 * Een resource, geen Vert.x-reroute of verplaatst SmallRye-pad: die geven op een niet-ondersteunde
 * methode 404 in plaats van 405, en dan is niet meer te toetsen dat dit pad uit de spec een route
 * heeft.
 *
 * [OpenApiDocumentService] is een runtime-bean van Quarkus en geen gedocumenteerde API. Een
 * onbekende documentnaam levert stil een leeg document op; de test die beide paden vergelijkt,
 * vangt een wijziging daarin. Gelijk aan de tegenhanger in het magazijn en de uitvraag.
 */
@ApplicationScoped
class SpecificatieResource(private val documenten: OpenApiDocumentService) : SpecificatieApi {

    override fun getOpenApiDocument(): Any =
        Response.ok(documenten.getDocument(STANDAARD_DOCUMENT, Format.JSON), MediaType.APPLICATION_JSON_TYPE)
            // Alleen op dit pad: het document is openbaar en tooling in de browser moet het kunnen
            // lezen. De rest van de API houdt CORS dicht.
            .header("Access-Control-Allow-Origin", "*")
            .build()

    private companion object {

        /** De naam waaronder Quarkus het enige, niet-benoemde document bewaart. */
        const val STANDAARD_DOCUMENT = "<default>"
    }
}
