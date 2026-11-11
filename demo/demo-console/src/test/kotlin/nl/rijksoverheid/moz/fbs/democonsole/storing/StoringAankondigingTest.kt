package nl.rijksoverheid.moz.fbs.democonsole.storing

import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import jakarta.ws.rs.ProcessingException
import jakarta.ws.rs.core.Response
import org.junit.jupiter.api.Assertions.assertDoesNotThrow
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.MethodSource
import org.junit.jupiter.params.provider.ValueSource
import java.net.URI
import java.util.Optional
import java.util.logging.Handler
import java.util.logging.LogRecord
import java.util.logging.Logger

class StoringAankondigingTest {

    private val webhook = mockk<MattermostWebhook>()

    private val gebouwdVoor = mutableListOf<URI>()

    private fun config(
        url: String? = "https://chat.example.org/hooks/abc",
        deployment: String? = "test",
    ) = mockk<AankondigingConfig> {
        every { webhookUrl() } returns Optional.ofNullable(url)
        every { deployment() } returns Optional.ofNullable(deployment)
        every { bewaakteDeployment() } returns "test"
        every { afzender() } returns "FBS Demo"
    }

    private fun aankondiging(config: AankondigingConfig = config()) =
        StoringAankondiging(config) { url -> gebouwdVoor += url; webhook }

    private fun respons(code: Int) = mockk<Response>(relaxed = true) { every { status } returns code }

    @Test
    fun `op de bewaakte deployment met een webhook is de Redis-proxy bewaakt`() {
        val aankondiging = aankondiging()

        assertTrue(aankondiging.bewaakt("redis"))
        assertEquals(listOf(URI.create("https://chat.example.org/hooks/abc")), gebouwdVoor)
    }

    @ParameterizedTest
    @ValueSource(strings = ["profiel", "notificatie", "aanmeld", "magazijn-a", "onbekend"])
    fun `een proxy die geen bewaakte readiness raakt, is niet bewaakt`(proxy: String) {
        assertFalse(aankondiging().bewaakt(proxy))
    }

    @Test
    fun `zonder webhook wordt er niets bewaakt en geen client gebouwd`() {
        val aankondiging = aankondiging(config(url = null))

        assertFalse(aankondiging.bewaakt("redis"))
        assertTrue(gebouwdVoor.isEmpty())
    }

    @ParameterizedTest
    @ValueSource(strings = ["pr-7", "fsc-magazijna", "TEST"])
    fun `een preview erft de webhook maar kondigt niets aan`(deployment: String) {
        assertFalse(aankondiging(config(deployment = deployment)).bewaakt("redis"))
    }

    @Test
    fun `zonder deploymentnaam, zoals lokaal, kondigt de console niets aan`() {
        assertFalse(aankondiging(config(deployment = null)).bewaakt("redis"))
    }

    @ParameterizedTest
    @ValueSource(
        strings = [
            "http://chat.example.org/hooks/geheim",
            "https://chat.example.org/hooks/ge heim",
            "chat.example.org/hooks/geheim",
            "https:///hooks/geheim",
        ],
    )
    fun `een onbruikbare webhook zet alleen de aankondiging uit, zonder de url te noemen`(url: String) {
        val log = vangLog { assertFalse(aankondiging(config(url = url)).bewaakt("redis")) }

        assertTrue(gebouwdVoor.isEmpty())
        assertTrue(log.isNotEmpty(), "een uitgeschakelde aankondiging hoort te waarschuwen")
        assertFalse(log.any { it.contains("geheim") }, "de url is een wachtwoord en hoort niet in de log: $log")
    }

    @Test
    fun `een client die niet te bouwen is, zet alleen de aankondiging uit`() {
        val log = vangLog {
            val aankondiging = StoringAankondiging(config()) { throw IllegalArgumentException("https://chat/hooks/geheim") }

            assertFalse(aankondiging.bewaakt("redis"))
        }

        assertFalse(log.any { it.contains("geheim") }, "de url is een wachtwoord en hoort niet in de log: $log")
    }

    @Test
    fun `witruimte rond een geplakte url wordt weggehaald`() {
        assertTrue(aankondiging(config(url = " https://chat.example.org/hooks/abc\n")).bewaakt("redis"))
        assertEquals(listOf(URI.create("https://chat.example.org/hooks/abc")), gebouwdVoor)
    }

    // De client wordt in de constructor gebouwd; de aankondiging moet dus binnen het blok ontstaan.
    private fun vangLog(blok: () -> Unit): List<String> {
        val logger = Logger.getLogger(StoringAankondiging::class.java.name)
        val regels = mutableListOf<String>()
        val handler = object : Handler() {
            override fun publish(record: LogRecord) {
                regels += record.message
            }

            override fun flush() = Unit

            override fun close() = Unit
        }

        logger.addHandler(handler)

        try {
            blok()
        } finally {
            logger.removeHandler(handler)
        }

        return regels
    }

    @ParameterizedTest
    @MethodSource("aanmeldingen")
    fun `de overgang naar een storing wordt aangekondigd`(voor: Storingstoestand, na: Storingstoestand) {
        val bericht = slot<MattermostBericht>()
        every { webhook.post(capture(bericht)) } returns respons(200)

        aankondiging().overgang("redis", voor, na)

        assertEquals("FBS Demo", bericht.captured.username)
        assertTrue(bericht.captured.text.contains("op ${na.waarde}"), bericht.captured.text)
        assertTrue(bericht.captured.text.contains("de uitvraag"), bericht.captured.text)
        assertTrue(bericht.captured.text.contains("`test`"), bericht.captured.text)
    }

    @ParameterizedTest
    @MethodSource("opheffingen")
    fun `het opheffen van een storing wordt aangekondigd`(voor: Storingstoestand) {
        val bericht = slot<MattermostBericht>()
        every { webhook.post(capture(bericht)) } returns respons(200)

        aankondiging().overgang("redis", voor, Storingstoestand.NORMAAL)

        assertTrue(bericht.captured.text.contains("opgeheven"), bericht.captured.text)
    }

    @ParameterizedTest
    @MethodSource("stilteOvergangen")
    fun `een overgang zonder verandering van wel of geen storing blijft stil`(
        voor: Storingstoestand,
        na: Storingstoestand,
    ) {
        aankondiging().overgang("redis", voor, na)

        verify(exactly = 0) { webhook.post(any()) }
    }

    @Test
    fun `een proxy die niet bewaakt is, levert geen bericht op`() {
        aankondiging().overgang("profiel", Storingstoestand.NORMAAL, Storingstoestand.UIT)

        verify(exactly = 0) { webhook.post(any()) }
    }

    @Test
    fun `zonder webhook verstuurt een overgang niets`() {
        aankondiging(config(url = null)).overgang("redis", Storingstoestand.NORMAAL, Storingstoestand.UIT)

        verify(exactly = 0) { webhook.post(any()) }
    }

    @Test
    fun `een webhook die niet antwoordt, laat de overgang niet falen`() {
        every { webhook.post(any()) } throws ProcessingException("connect timed out")

        assertDoesNotThrow { aankondiging().overgang("redis", Storingstoestand.NORMAAL, Storingstoestand.UIT) }
    }

    @ParameterizedTest
    @ValueSource(ints = [400, 404, 500])
    fun `een foutstatus van de webhook laat de overgang niet falen`(status: Int) {
        every { webhook.post(any()) } returns respons(status)

        assertDoesNotThrow { aankondiging().overgang("redis", Storingstoestand.NORMAAL, Storingstoestand.UIT) }
    }

    companion object {

        @JvmStatic
        fun aanmeldingen() = listOf(
            Arguments.of(Storingstoestand.NORMAAL, Storingstoestand.UIT),
            Arguments.of(Storingstoestand.NORMAAL, Storingstoestand.TRAAG),
            // Onbekend vooraf: liever een aankondiging te veel dan een melding die niemand verwacht.
            Arguments.of(Storingstoestand.ONBEKEND, Storingstoestand.UIT),
        )

        @JvmStatic
        fun opheffingen() = listOf(Storingstoestand.UIT, Storingstoestand.TRAAG)

        @JvmStatic
        fun stilteOvergangen() = listOf(
            Arguments.of(Storingstoestand.TRAAG, Storingstoestand.UIT),
            Arguments.of(Storingstoestand.UIT, Storingstoestand.UIT),
            Arguments.of(Storingstoestand.NORMAAL, Storingstoestand.NORMAAL),
            Arguments.of(Storingstoestand.ONBEKEND, Storingstoestand.NORMAAL),
            Arguments.of(Storingstoestand.UIT, Storingstoestand.ONBEKEND),
        )
    }
}
