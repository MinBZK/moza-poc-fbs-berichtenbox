package nl.rijksoverheid.moz.fbs.democonsole.aanlever

import com.fasterxml.jackson.databind.ObjectMapper
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class FoutieveAanleverServiceTest {

    private val mapper = ObjectMapper()

    @Test
    fun `ongeldige payload faalt uitsluitend op de elfproef-ongeldige BSN`() {
        val json = mapper.readTree(FoutieveAanleverService.ongeldigePayload())

        // Verplichte velden zijn aanwezig en geldig, zodat alleen de BSN de 400 uitlokt.
        assertEquals("00000001003214345000", json["afzender"].asText())
        assertEquals("BSN", json["ontvanger"]["type"].asText())
        assertEquals("111111111", json["ontvanger"]["waarde"].asText())
        assertTrue(json["onderwerp"].asText().isNotBlank())
        assertTrue(json["inhoud"].asText().isNotBlank())
    }
}
