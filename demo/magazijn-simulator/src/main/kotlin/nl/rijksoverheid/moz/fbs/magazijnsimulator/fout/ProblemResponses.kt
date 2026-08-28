package nl.rijksoverheid.moz.fbs.magazijnsimulator.fout

import jakarta.ws.rs.core.MediaType
import jakarta.ws.rs.core.Response
import nl.rijksoverheid.moz.fbs.magazijnsimulator.api.model.Problem
import java.net.URI
import java.util.UUID

/** RFC 9457-mediatype, zoals de spec het voor elke foutresponse voorschrijft. */
private val PROBLEM_JSON: MediaType = MediaType.valueOf("application/problem+json")

/** Bovengrens op vrije tekst in `detail`; onbegrensde invoer echoën is nooit nodig. */
private const val MAX_DETAIL_LENGTE = 500

/**
 * Bouwt een foutresponse met het `Problem`-model uit de gedeelde spec. Bewust dát model en geen
 * eigen data class: de belofte van de simulator is dat zijn antwoorden — ook de foutantwoorden —
 * dezelfde vorm hebben als die van een echt magazijn, en met het gegenereerde model kan die vorm
 * niet uit de pas lopen zonder dat de build breekt.
 *
 * Elke foutresponse draagt een correlatie-id in `instance`, net als het echte magazijn. De spec
 * beschrijft dat als gedrag en niet als optie, en het is precies het veld waarmee support een
 * melding terugvindt; hem weglaten zou de simulator op zijn foutpad herkenbaar maken.
 *
 * `detail` mag weg blijven: RFC 9457 vereist alleen `type`, `title` en `status`, en een detail dat
 * niets toevoegt aan de titel is ruis. Een `null` belandt niet als veld in de JSON — dat regelt
 * `quarkus.jackson.serialization-inclusion=non-null`, en zónder dat zou het schema van de spec
 * geschonden worden.
 */
fun problemResponse(
    status: Int,
    title: String,
    detail: String? = null,
    foutId: UUID = UUID.randomUUID(),
): Response = Response.status(status)
    .type(PROBLEM_JSON)
    .entity(
        Problem(URI.create("about:blank"), title, status).apply {
            this.detail = detail?.take(MAX_DETAIL_LENGTE)?.ifBlank { null }
            this.instance = "urn:uuid:$foutId"
        },
    )
    .build()
