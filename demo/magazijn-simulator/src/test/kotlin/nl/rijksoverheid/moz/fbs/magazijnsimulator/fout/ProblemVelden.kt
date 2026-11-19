package nl.rijksoverheid.moz.fbs.magazijnsimulator.fout

import io.restassured.response.Response
import nl.rijksoverheid.moz.fbs.magazijnsimulator.ApiInfo
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue

/** Wat er van `detail` verwacht wordt in een foutantwoord. */
sealed interface DetailVerwachting {

    /** Geen `detail`-veld. */
    data object Afwezig : DetailVerwachting

    /** Een `detail`-veld waarvan de inhoud hier niet vastligt. */
    data object Aanwezig : DetailVerwachting

    /** Precies deze tekst. */
    data class Precies(val tekst: String) : DetailVerwachting

    /** Een eigen melding die dit stuk bevat. */
    data class Bevat(val stuk: String) : DetailVerwachting
}

/**
 * Toetst de exacte set velden van een `problem+json`-antwoord. Een onbekend extra veld laat de
 * toets falen: het schema staat extension members toe, dus een contracttest merkt een veld als
 * `"empty"` niet op.
 */
fun assertProblemVelden(antwoord: Response, status: Int, foutcode: Foutcode, detail: DetailVerwachting) {
    assertEquals(status, antwoord.statusCode, antwoord.asString())
    assertEquals("application/problem+json", antwoord.contentType.substringBefore(';'))
    // De spec maakt de header niet verplicht, dus de validator vangt een ontbrekende niet.
    assertEquals(ApiInfo.SPEC_VERSION, antwoord.header("API-Version"))

    val velden = antwoord.jsonPath().getMap<String, Any?>("$")
    val verwacht = setOf("type", "title", "status", "instance") +
        if (detail is DetailVerwachting.Afwezig) emptySet() else setOf("detail")

    assertEquals(verwacht, velden.keys, "velden van ${antwoord.asString()}")
    assertEquals(foutcode.uri.toString(), velden["type"])
    assertEquals(status, velden["status"])
    assertTrue((velden["instance"] as String).startsWith("urn:uuid:"), "instance: ${velden["instance"]}")

    when (detail) {
        DetailVerwachting.Afwezig, DetailVerwachting.Aanwezig -> Unit
        is DetailVerwachting.Precies -> assertEquals(detail.tekst, velden["detail"])
        is DetailVerwachting.Bevat -> assertTrue(
            (velden["detail"] as String).contains(detail.stuk),
            "detail '${velden["detail"]}' hoort '${detail.stuk}' te bevatten",
        )
    }
}
