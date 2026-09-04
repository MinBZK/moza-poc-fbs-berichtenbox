package nl.rijksoverheid.moz.fbs.democonsole

import jakarta.ws.rs.BadRequestException
import jakarta.ws.rs.NotFoundException
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.logging.Handler
import java.util.logging.Level
import java.util.logging.LogRecord
import java.util.logging.Logger

/**
 * De mapper is het enige wat een fout van deze module leesbaar maakt voor het paneel. Twee dingen
 * moeten kloppen: de bediener leest een zin in plaats van een statusnummer, en achteraf is
 * terug te vinden dát er iets geweigerd is.
 */
class DemoFoutMapperTest {

    private val mapper = DemoFoutMapper()

    @Suppress("UNCHECKED_CAST")
    private fun body(fout: Exception): Map<String, String> =
        mapper.toResponse(fout).entity as Map<String, String>

    @Test
    fun `een eigen weigering houdt zijn status en zijn melding`() {
        val respons = mapper.toResponse(BadRequestException("aantal moet tussen 1 en 100 liggen, was: 0"))

        assertEquals(400, respons.status)
        assertEquals("aantal moet tussen 1 en 100 liggen, was: 0", (respons.entity as Map<*, *>)["fout"])
    }

    @Test
    fun `een weigering van het framework noemt het type van zijn oorzaak`() {
        // JAX-RS geeft een niet om te zetten queryparameter de tekst "HTTP 404 Not Found" mee en
        // hangt de echte fout eronder. Zonder aanvulling leest de bediener alleen de status terug.
        val fout = body(NotFoundException(NumberFormatException("""For input string: "abc"""")))

        assertTrue(fout.getValue("fout").contains("(oorzaak: NumberFormatException)"), fout.getValue("fout"))
    }

    @Test
    fun `de ingevoerde waarde uit de oorzaak komt niet mee naar buiten`() {
        // Wat een bediener intypt hoort niet in een applicatielog; alleen het type van de oorzaak.
        val fout = body(NotFoundException(NumberFormatException("""For input string: "999993653"""")))

        assertTrue(!fout.getValue("fout").contains("999993653"), fout.getValue("fout"))
    }

    @Test
    fun `een eigen melding met een oorzaak wordt niet aangevuld`() {
        // De melding is dan al de uitleg; het type van de oorzaak eraan plakken is ruis.
        val fout = body(BadRequestException("aantal moet 0 of hoger zijn", IllegalArgumentException("bound")))

        assertEquals("aantal moet 0 of hoger zijn", fout.getValue("fout"))
    }

    @Test
    fun `een fout zonder melding valt terug op zijn type`() {
        assertEquals("IllegalStateException", body(IllegalStateException()).getValue("fout"))
    }

    @Test
    fun `alles wat geen WebApplicationException is wordt een 500`() {
        assertEquals(500, mapper.toResponse(IllegalStateException("simulator onbereikbaar")).status)
    }

    @Test
    fun `een geweigerde actie laat een logregel achter`() {
        // Zonder deze regel is een weigering achteraf nergens terug te vinden: het paneel toont hem
        // één keer in de meldingsbalk en de volgende actie overschrijft die.
        val regels = vangLogregels { mapper.toResponse(BadRequestException("aantal moet tussen 1 en 100 liggen")) }

        assertEquals(1, regels.size)
        assertEquals(Level.INFO, regels.single().level)
        assertTrue(regels.single().message.contains("HTTP 400"), regels.single().message)
    }

    @Test
    fun `een serverfout wordt als waarschuwing gelogd, mét de oorzaak`() {
        val regels = vangLogregels { mapper.toResponse(IllegalStateException("simulator onbereikbaar")) }

        assertEquals(Level.WARNING, regels.single().level)
        assertEquals("simulator onbereikbaar", regels.single().thrown.message)
    }

    private fun vangLogregels(actie: () -> Unit): List<LogRecord> {
        val logger = Logger.getLogger(DemoFoutMapper::class.java.name)
        val gevangen = mutableListOf<LogRecord>()

        val handler = object : Handler() {
            override fun publish(record: LogRecord) { gevangen += record }

            override fun flush() = Unit

            override fun close() = Unit
        }

        logger.addHandler(handler)

        try {
            actie()
        } finally {
            logger.removeHandler(handler)
        }

        return gevangen
    }
}
