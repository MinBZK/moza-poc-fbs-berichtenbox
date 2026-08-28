package nl.rijksoverheid.moz.fbs.magazijnsimulator.fout

import jakarta.ws.rs.core.MediaType
import jakarta.ws.rs.core.Response
import nl.rijksoverheid.moz.fbs.magazijnsimulator.api.model.Problem
import java.net.URI

/** RFC 9457-mediatype, zoals de spec het voor elke foutresponse voorschrijft. */
private val PROBLEM_JSON: MediaType = MediaType.valueOf("application/problem+json")

/**
 * Bouwt een foutresponse met het `Problem`-model uit de gedeelde spec. Bewust dát model en geen
 * eigen data class: de belofte van de simulator is dat zijn antwoorden — ook de foutantwoorden —
 * dezelfde vorm hebben als die van een echt magazijn, en met het gegenereerde model kan die vorm
 * niet uit de pas lopen zonder dat de build breekt.
 *
 * `detail` mag weg blijven: RFC 9457 vereist alleen `type`, `title` en `status`, en een detail dat
 * niets toevoegt aan de titel is ruis. Een `null` belandt niet als veld in de JSON — dat regelt
 * `quarkus.jackson.serialization-inclusion=non-null`, en zónder dat zou het schema van de spec
 * geschonden worden.
 */
fun problemResponse(status: Int, title: String, detail: String? = null): Response =
    Response.status(status)
        .type(PROBLEM_JSON)
        .entity(
            Problem(URI.create("about:blank"), title, status).apply {
                this.detail = detail
            },
        )
        .build()
