package nl.rijksoverheid.moz.fbs.berichtenmagazijn.publicatie

import com.fasterxml.jackson.databind.ObjectMapper
import io.mockk.every
import io.mockk.mockk
import io.opentelemetry.api.OpenTelemetry
import jakarta.enterprise.inject.Instance
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.MethodSource
import java.io.IOException
import java.net.ConnectException
import java.net.http.HttpConnectTimeoutException
import java.net.http.HttpTimeoutException
import java.time.Duration
import java.util.Optional
import javax.net.ssl.SSLHandshakeException
import javax.net.ssl.SSLProtocolException

/**
 * Borgt welke leverfouten vaststellen dat de afnemer het bericht niet kreeg. Alleen die
 * krijgen in het logboek een ERROR-child; bij een onzekere levering blijft de verstrekking
 * als geslaagd staan. Een fout aan de verkeerde kant van de grens geeft onder-rapportage:
 * het logboek zegt "niet verstrekt" terwijl de afnemer het bericht mogelijk heeft.
 *
 * Dit toetst de indeling per exceptietype; dat de JDK-client een onbekende host of een
 * geweigerde verbinding ook echt als [ConnectException] aflevert, borgt [DownstreamClientTest]
 * met een echte verzending.
 */
class DownstreamVerzendzekerheidTest {

    private val config = mockk<PublicatieConfig>().apply {
        every { downstreams() } returns emptyMap()
        every { client() } returns mockk {
            every { connectTimeout() } returns Duration.ofSeconds(5)
            every { requestTimeout() } returns Duration.ofSeconds(10)
        }
        every { outway() } returns object : PublicatieConfig.Outway {
            override fun host(): Optional<String> = Optional.empty()
        }
    }
    private val openTelemetry = mockk<Instance<OpenTelemetry>>().apply {
        every { isResolvable } returns false
    }
    private val client = DownstreamClient(config, ObjectMapper(), openTelemetry, "prod")

    @AfterEach
    fun stop() {
        client.stop()
    }

    @ParameterizedTest(name = "{0} → zeker niet verzonden: {1}")
    @MethodSource("fouten")
    fun `alleen een fout voor of tijdens het verbinden telt als zeker niet verzonden`(
        fout: IOException,
        zekerNietVerzonden: Boolean,
    ) {
        val resultaat = client.mapDeliveryException(fout, Publicatiedoel("aanmeld"))

        assertEquals(zekerNietVerzonden, (resultaat as DownstreamResultaat.Mislukt).zekerNietVerzonden)
    }

    companion object {
        @JvmStatic
        fun fouten(): List<Arguments> = listOf(
            Arguments.of(HttpConnectTimeoutException("connect timed out"), true),
            Arguments.of(ConnectException("Connection refused"), true),
            Arguments.of(SSLHandshakeException("Unable to find valid certification path"), true),
            Arguments.of(HttpTimeoutException("request timed out"), false),
            Arguments.of(IOException("Connection reset"), false),
            Arguments.of(SSLProtocolException("Connection reset during read"), false),
        )
    }
}
