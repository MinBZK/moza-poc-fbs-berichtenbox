package nl.rijksoverheid.moz.fbs.magazijnsimulator.fout

import com.fasterxml.jackson.core.JsonGenerationException
import jakarta.ws.rs.WebApplicationException
import nl.rijksoverheid.moz.fbs.magazijnsimulator.Logvanger
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.IOException
import java.sql.SQLException

/**
 * Een serverfout logt de stack, en een logger drukt daarbij de melding van de fout en van elke cause
 * af. Een database-driver zet daar de volledige rij in, met de ontvanger. De log houdt de typen en
 * de stack, nooit een melding.
 */
class ServerfoutLogTest {

    private val keten = IllegalStateException(
        "opslaan mislukt voor BSN 999993653",
        SQLException("Failing row contains (BSN, 999993653, Onderwerp)", "23502"),
    )

    @Test
    fun `het vangnet logt de typen van de keten, geen melding`() {
        val log = metLog { UncaughtExceptionMapper().toResponse(keten) }

        assertAlleenTypen(log, IllegalStateException::class.java.name, SQLException::class.java.name)
    }

    @Test
    fun `een 5xx van elders logt de typen van de keten, geen melding`() {
        val log = metLog { ProblemExceptionMapper().toResponse(WebApplicationException("BSN 999993653", keten, 503)) }

        assertAlleenTypen(log, WebApplicationException::class.java.name, SQLException::class.java.name)
    }

    @Test
    fun `een antwoord dat niet te serialiseren is logt de typen, geen melding`() {
        val fout = JsonGenerationException("veld met 999993653", IOException("schrijven van 999993653"), null)

        val log = metLog { JsonFoutMapper().toResponse(fout) }

        assertAlleenTypen(log, JsonGenerationException::class.java.name, IOException::class.java.name)
    }

    private fun metLog(aanroep: () -> Unit): String = Logvanger().use { logvanger ->
        aanroep()
        logvanger.tekst()
    }

    private fun assertAlleenTypen(log: String, vararg typen: String) {
        assertFalse(log.contains("99999"), "geen stuk van het BSN in de log: $log")

        typen.forEach { type -> assertTrue(log.contains(type), "'$type' hoort in de log: $log") }
    }
}
