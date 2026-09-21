package nl.rijksoverheid.moz.fbs.berichtenuitvraag.uitvraag

import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.junit.QuarkusTestProfile
import io.quarkus.test.junit.TestProfile
import io.restassured.RestAssured
import io.smallrye.mutiny.Multi
import jakarta.inject.Inject
import nl.rijksoverheid.moz.fbs.berichtensessiecache.SessiecacheException
import nl.rijksoverheid.moz.fbs.berichtensessiecache.berichten.SessieGebeurtenis
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.net.HttpURLConnection
import java.net.URI
import java.time.Duration
import java.time.Instant

/**
 * De plafonds op open streams: twee per pod en één per ontvanger in dit profiel. Een gesloten
 * connection geeft haar plek terug, en een aanvraag die vóór de stream strandt ook.
 */
@QuarkusTest
@TestProfile(VolgenPlafondTest.KleinePlafondsProfile::class)
class VolgenPlafondTest {

    @Inject
    lateinit var sessiecache: MockSessiecache

    @BeforeEach
    fun openBlijvendeStream() {
        sessiecache.reset()
        sessiecache.volgGebeurtenissen = Multi.createBy().concatenating().streams(
            Multi.createFrom().item(SessieGebeurtenis.VolgenGestart),
            Multi.createFrom().nothing(),
        )
    }

    @Test
    fun `een tweede berichtenbox van dezelfde ontvanger krijgt een 503, een andere ontvanger niet`() {
        val eerste = open(A)

        try {
            assertEquals(200, eerste.responseCode)
            assertWeigering(open(A))

            val ander = open(B)

            assertEquals(200, ander.responseCode)
            ander.disconnect()
        } finally {
            eerste.disconnect()
        }

        assertEquals(200, wachtOpStatus(A, 200))
    }

    @Test
    fun `boven het pod-plafond krijgt ook een nieuwe ontvanger een 503`() {
        val eerste = open(A)
        val tweede = open(B)

        try {
            assertEquals(200, eerste.responseCode)
            assertEquals(200, tweede.responseCode)
            assertWeigering(open(C))
        } finally {
            eerste.disconnect()
            tweede.disconnect()
        }

        assertEquals(200, wachtOpStatus(C, 200))
    }

    @Test
    fun `een aanvraag die vóór de stream strandt, houdt geen plek bezet`() {
        sessiecache.volgFout = SessiecacheException.NogNietGevuld("nog niet")

        repeat(3) { assertEquals(409, statusVan(A)) }

        sessiecache.volgFout = null

        assertEquals(200, wachtOpStatus(A, 200))
    }

    private fun assertWeigering(connection: HttpURLConnection) {
        try {
            assertEquals(503, connection.responseCode)
            assertEquals("30", connection.getHeaderField("Retry-After"))
            assertTrue(connection.contentType.startsWith("application/problem+json"))
        } finally {
            connection.disconnect()
        }
    }

    private fun statusVan(ontvanger: String): Int {
        val connection = open(ontvanger)

        return connection.responseCode.also { connection.disconnect() }
    }

    /** Het sluiten komt asynchroon bij de server aan; wacht tot de plek vrij is. */
    private fun wachtOpStatus(ontvanger: String, verwacht: Int): Int {
        val deadline = Instant.now().plus(Duration.ofSeconds(5))
        var status: Int

        do {
            status = statusVan(ontvanger)

            if (status != verwacht) Thread.sleep(100)
        } while (status != verwacht && Instant.now().isBefore(deadline))

        return status
    }

    private fun open(ontvanger: String): HttpURLConnection =
        (URI("http://localhost:${RestAssured.port}/api/v1/berichten/_volgen").toURL().openConnection() as HttpURLConnection).apply {
            setRequestProperty("X-Ontvanger", ontvanger)
            setRequestProperty("Accept", "text/event-stream")
            connectTimeout = 2000
            readTimeout = 2000
        }

    class KleinePlafondsProfile : QuarkusTestProfile {
        private val basis = MockSessiecacheProfile()

        override fun getEnabledAlternatives(): Set<Class<*>> = basis.enabledAlternatives

        override fun getConfigOverrides(): Map<String, String> = basis.configOverrides + mapOf(
            "berichtenuitvraag.volgen.max-connections" to "2",
            "berichtenuitvraag.volgen.max-connections-per-ontvanger" to "1",
        )
    }

    private companion object {
        const val A = "BSN:999990019"
        const val B = "BSN:999993653"
        const val C = "KVK:90000011"
    }
}
