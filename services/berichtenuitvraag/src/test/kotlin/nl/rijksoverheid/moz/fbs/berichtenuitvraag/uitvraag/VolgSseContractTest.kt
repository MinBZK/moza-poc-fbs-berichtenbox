package nl.rijksoverheid.moz.fbs.berichtenuitvraag.uitvraag

import com.fasterxml.jackson.databind.ObjectMapper
import io.swagger.v3.oas.models.OpenAPI
import io.swagger.v3.oas.models.PathItem
import io.swagger.v3.parser.OpenAPIV3Parser
import nl.rijksoverheid.moz.fbs.berichtenuitvraag.api.model.BerichtSamenvatting
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import java.time.Instant
import java.util.UUID

/**
 * Houdt de gepubliceerde beschrijving van `GET /berichten/_volgen` tegen wat [VolgGebeurtenis]
 * werkelijk stuurt, in beide richtingen; zie `SseContractTest` voor waarom geen bestaande gate
 * dat doet bij een SSE-body.
 */
class VolgSseContractTest {

    companion object {
        private const val SPEC = "openapi/berichtenuitvraag-api.yaml"

        private val spec: OpenAPI =
            requireNotNull(OpenAPIV3Parser().read(SPEC)) { "Spec $SPEC niet gevonden op het test-classpath" }

        private val mapper = ObjectMapper().findAndRegisterModules()

        private fun discriminatorMapping(): Map<String, String> =
            requireNotNull(spec.components.schemas["VolgEvent"]?.discriminator?.mapping) {
                "VolgEvent heeft geen discriminator-mapping"
            }

        private fun schemaVoor(type: VolgEventType): String =
            requireNotNull(discriminatorMapping()[type.value]) { "De mapping kent '${type.value}' niet" }
                .substringAfterLast('/')

        /** Elk soort zoals de resource het bouwt: alleen `bericht-bijgekomen` draagt een bericht. */
        private fun voorbeeld(type: VolgEventType) = VolgGebeurtenis(
            type,
            if (type == VolgEventType.BERICHT_BIJGEKOMEN) samenvatting() else null,
        )

        private fun samenvatting() = BerichtSamenvatting().apply {
            berichtId = UUID.randomUUID()
            onderwerp = "Nieuw"
            afzenderNaam = "Magazijn A"
            publicatietijdstip = Instant.parse("2026-09-21T10:00:00Z")
            aantalBijlagen = 0
            magazijnId = "00000001003214345000"
        }

        private fun velden(gebeurtenis: VolgGebeurtenis): Set<String> =
            mapper.readTree(mapper.writeValueAsString(gebeurtenis)).fieldNames().asSequence().toSet()
    }

    @Test
    fun `de discriminator kent precies de soorten van de code`() {
        assertEquals(VolgEventType.entries.map { it.value }.toSet(), discriminatorMapping().keys)
    }

    @ParameterizedTest
    @EnumSource(VolgEventType::class)
    fun `wat de code stuurt staat in het schema, en wat het schema eist stuurt de code`(type: VolgEventType) {
        val schema = spec.components.schemas[schemaVoor(type)]!!
        val gestuurd = velden(voorbeeld(type))

        assertEquals(schema.properties.keys, gestuurd, "${schema.name ?: type.value} loopt uit de pas met de code")
        assertTrue(gestuurd.containsAll(schema.required.orEmpty()))
    }

    @Test
    fun `het volg-endpoint verwijst naar het event-schema`() {
        val volgen = spec.paths["/berichten/_volgen"]?.readOperationsMap()?.get(PathItem.HttpMethod.GET)
        val sse = volgen?.responses?.get("200")?.content?.get("text/event-stream")

        assertEquals("#/components/schemas/VolgEvent", sse?.schema?.`$ref`)
    }
}
