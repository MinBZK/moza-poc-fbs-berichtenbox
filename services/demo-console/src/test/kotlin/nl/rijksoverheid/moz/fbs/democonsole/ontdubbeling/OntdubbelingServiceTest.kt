package nl.rijksoverheid.moz.fbs.democonsole.ontdubbeling

import com.fasterxml.jackson.databind.ObjectMapper
import io.mockk.every
import io.mockk.mockk
import jakarta.ws.rs.core.Response
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class OntdubbelingServiceTest {

    private val client = mockk<AanmeldWebhookClient>()
    private val service = OntdubbelingService(client, ObjectMapper())

    private fun respons(code: Int) = mockk<Response>(relaxed = true) { every { status } returns code }

    @Test
    fun `beide aanmeldingen dragen exact dezelfde payload (zelfde CloudEvent-id)`() {
        val payloads = mutableListOf<String>()

        every { client.meldAan(capture(payloads)) } returns respons(202)

        val resultaat = service.demonstreer("999993653")

        assertEquals(2, payloads.size)
        assertEquals(payloads[0], payloads[1])

        val event = ObjectMapper().readTree(payloads[0])
        assertEquals(resultaat.eventId, event["id"].asText())
        assertEquals("nl.rijksoverheid.fbs.bericht.gepubliceerd", event["type"].asText())
        assertEquals("1.0", event["specversion"].asText())
        assertEquals("00000001003214345000", event["data"]["afzender"].asText())
        assertEquals("999993653", event["data"]["ontvanger"]["waarde"].asText())
        assertEquals(202, resultaat.eersteStatus)
        assertEquals(202, resultaat.tweedeStatus)
    }
}
