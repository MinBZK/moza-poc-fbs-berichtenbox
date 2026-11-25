package nl.rijksoverheid.moz.fbs.berichtenuitvraag.uitvraag

import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.junit.QuarkusTestProfile
import io.quarkus.test.junit.TestProfile
import io.restassured.RestAssured
import io.smallrye.mutiny.Multi
import jakarta.inject.Inject
import nl.rijksoverheid.moz.fbs.berichtensessiecache.SessiecacheException
import nl.rijksoverheid.moz.fbs.berichtensessiecache.berichten.SessieGebeurtenis
import org.awaitility.Awaitility.await
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.net.HttpURLConnection
import java.net.URI
import java.time.Duration
import java.util.concurrent.atomic.AtomicInteger

/**
 * De plafonds op open streams: twee per pod en één per ontvanger in dit profiel. Een gesloten
 * connection geeft haar plek terug, en een aanvraag die vóór de stream strandt ook.
 *
 * Een `disconnect()` komt pas asynchroon bij de server aan. De tests wachten daarom tot de server
 * elke stream heeft geannuleerd, en niet tot een nieuwe stream weer opengaat: die houdt zelf een
 * plek bezet, en die kan bij de start van de volgende test nog niet terug zijn.
 */
@QuarkusTest
@TestProfile(VolgenPlafondTest.KleinePlafondsProfile::class)
class VolgenPlafondTest {

    @Inject
    lateinit var sessiecache: MockSessiecache

    /** Per ontvanger één sessie; het plafond telt open streams, niet sessies. */
    private val sessies = mutableMapOf<String, String>()

    /**
     * Streams van de sessiecache die de server heeft geannuleerd. De resource geeft zijn plek terug
     * in een `onTermination` downstream van deze teller, en een annulering passeert die eerst (zie de
     * test over de volgorde van een annulering). Is elke afgegeven stream geannuleerd, dan is dus
     * ook elke plek terug.
     */
    private val geannuleerd = AtomicInteger()

    @BeforeEach
    fun openBlijvendeStream() {
        sessiecache.reset()
        sessiecache.volgGebeurtenissen = Multi.createBy().concatenating()
            .streams<SessieGebeurtenis>(
                Multi.createFrom().item(SessieGebeurtenis.VolgenGestart),
                Multi.createFrom().nothing(),
            )
            .onCancellation().invoke { -> geannuleerd.incrementAndGet() }
    }

    /**
     * Neemt het hele pod-plafond in: met één stream valt een weggelekte plek niet op. Een fout
     * hier staat als `Suppressed` onder die van de test zelf.
     */
    @AfterEach
    fun plafondWeerHeel() {
        wachtTotAlleStreamsGeannuleerd()

        val streams = listOf(open(A), open(C))

        try {
            streams.forEach { assertEquals(200, it.responseCode, "na de test hoort het hele pod-plafond vrij te zijn") }
        } finally {
            streams.forEach { it.disconnect() }
        }

        wachtTotAlleStreamsGeannuleerd()
    }

    @Test
    fun `een tweede berichtenbox van dezelfde ontvanger krijgt een 503, een andere ontvanger niet`() {
        val eerste = open(A)

        try {
            assertEquals(200, eerste.responseCode)
            // Twee keer: een weigering mag de telling van de stream die openstaat niet wissen.
            repeat(2) { assertWeigering(open(A)) }
            assertEquals(200, statusVan(B))
        } finally {
            eerste.disconnect()
        }

        wachtTotAlleStreamsGeannuleerd()

        assertEquals(200, statusVan(A), "de gesloten stream hoort zijn plek per ontvanger terug te geven")
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

        wachtTotAlleStreamsGeannuleerd()

        assertEquals(200, statusVan(C), "de gesloten streams horen hun plek op de pod terug te geven")
    }

    @Test
    fun `een aanvraag die vóór de stream strandt, houdt geen plek bezet`() {
        sessiecache.volgFout = SessiecacheException.NogNietGevuld("nog niet")

        // De plek gaat terug voordat de 409 vertrekt; hier valt niets af te wachten.
        repeat(3) { assertEquals(409, statusVan(A)) }

        sessiecache.volgFout = null

        assertEquals(200, statusVan(A), "een aanvraag die vóór de stream strandde, hield een plek bezet")
    }

    @Test
    fun `een annulering loopt door onTermination downstream voordat de stream upstream hem ziet`() {
        val volgorde = mutableListOf<String>()

        Multi.createFrom().nothing<Int>()
            .onCancellation().invoke { -> volgorde += "sessiecache" }
            .map { it }
            .onTermination().invoke { -> volgorde += "resource" }
            .subscribe().with { }
            .cancel()

        assertEquals(listOf("resource", "sessiecache"), volgorde)
    }

    private fun wachtTotAlleStreamsGeannuleerd() {
        await("elke afgegeven stream door de server geannuleerd")
            .atMost(VANGNET)
            .untilAsserted { assertEquals(sessiecache.volgAfgegeven.get(), geannuleerd.get()) }
    }

    private fun assertWeigering(connection: HttpURLConnection) {
        try {
            assertEquals(503, connection.responseCode)
            assertEquals("30", connection.getHeaderField("Retry-After"))
            assertTrue(connection.contentType.startsWith("application/problem+json"))
            // Een eigen kenmerk, niet dat van een storing: anders verbindt een afnemer steeds opnieuw
            // en neemt hij zelf een plek in van hetzelfde plafond.
            assertTrue(
                connection.errorStream.bufferedReader().readText().contains("urn:fbs:fout:te-veel-open-berichtenboxen"),
                "verwacht het kenmerk te-veel-open-berichtenboxen",
            )
        } finally {
            connection.disconnect()
        }
    }

    private fun statusVan(ontvanger: String): Int {
        val connection = open(ontvanger)

        try {
            return connection.responseCode
        } finally {
            connection.disconnect()
        }
    }


    private fun open(ontvanger: String): HttpURLConnection =
        (URI("http://localhost:${RestAssured.port}/api/v1/berichten/_volgen").toURL().openConnection() as HttpURLConnection).apply {
            setRequestProperty("X-Ontvanger", ontvanger)
            setRequestProperty("X-Sessie", sessies.getOrPut(ontvanger) { sessiecache.sessieVoor(ontvanger) })
            setRequestProperty("Accept", "text/event-stream")
            connectTimeout = 2000
            // Alleen een vangnet tegen een server die nooit antwoordt; de tests meten geen snelheid. De
            // eerste weigering na een start doet eenmalig werk dat op een krappe runner rond 2 s kost.
            readTimeout = VANGNET.toMillis().toInt()
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

        /** Hoe lang een test op de server wacht voor hij hem opgeeft. */
        val VANGNET: Duration = Duration.ofSeconds(10)
    }
}
