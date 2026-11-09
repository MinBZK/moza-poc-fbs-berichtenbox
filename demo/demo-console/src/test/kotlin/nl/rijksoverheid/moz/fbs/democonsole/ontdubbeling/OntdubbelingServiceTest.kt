package nl.rijksoverheid.moz.fbs.democonsole.ontdubbeling

import com.fasterxml.jackson.databind.ObjectMapper
import io.mockk.every
import io.mockk.mockk
import jakarta.ws.rs.core.Response
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource

class OntdubbelingServiceTest {

    private val client = mockk<AanmeldWebhookClient>()
    private val service = OntdubbelingService(client, ObjectMapper())

    private fun respons(code: Int) = mockk<Response>(relaxed = true) { every { status } returns code }

    @Test
    fun `beide aanmeldingen dragen exact dezelfde payload (zelfde CloudEvent-id)`() {
        val payloads = mutableListOf<String>()

        every { client.meldAan(capture(payloads)) } returns respons(202)

        val resultaat = service.demonstreer(Ontvanger(type = "BSN", waarde = "999993653"))

        assertEquals(2, payloads.size)
        assertEquals(payloads[0], payloads[1])

        val event = ObjectMapper().readTree(payloads[0])
        assertEquals(resultaat.eventId, event["id"].asText())
        assertEquals("nl.rijksoverheid.fbs.bericht.gepubliceerd", event["type"].asText())
        assertEquals("1.0", event["specversion"].asText())
        assertEquals("00000000000000100000", event["data"]["afzender"].asText())
        assertEquals(202, resultaat.eersteStatus)
        assertEquals(202, resultaat.tweedeStatus)
    }

    /**
     * Het type komt van de persona en niet uit de service: een berichtenbox biedt ook persona's met
     * een KVK-nummer aan, en een vast type stuurt hun nummer als BSN naar de uitvraag.
     */
    @ParameterizedTest
    @CsvSource("BSN,999993653", "KVK,90000014", "RSIN,999996666")
    fun `het event draagt de ontvanger met zijn eigen type`(type: String, waarde: String) {
        val payloads = mutableListOf<String>()

        every { client.meldAan(capture(payloads)) } returns respons(202)

        service.demonstreer(Ontvanger(type = type, waarde = waarde))

        val ontvanger = ObjectMapper().readTree(payloads[0])["data"]["ontvanger"]

        assertEquals(type, ontvanger["type"].asText())
        assertEquals(waarde, ontvanger["waarde"].asText())
    }
}
