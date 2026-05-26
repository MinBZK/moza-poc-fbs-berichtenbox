package nl.rijksoverheid.moz.fbs.berichtensessiecache.magazijn

import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import jakarta.ws.rs.ProcessingException
import jakarta.ws.rs.WebApplicationException
import jakarta.ws.rs.core.Response
import nl.rijksoverheid.moz.fbs.common.identificatie.Bsn
import nl.rijksoverheid.moz.fbs.common.identificatie.Kvk
import nl.rijksoverheid.moz.fbs.common.identificatie.Oin
import nl.rijksoverheid.moz.fbs.common.identificatie.Rsin
import nl.rijksoverheid.moz.fbs.common.profiel.IdentificatieResponse
import nl.rijksoverheid.moz.fbs.common.profiel.PartijResponse
import nl.rijksoverheid.moz.fbs.common.profiel.ProfielServiceClient
import nl.rijksoverheid.moz.fbs.common.profiel.ProfielServiceFoutException
import nl.rijksoverheid.moz.fbs.common.profiel.ScopeResponse
import nl.rijksoverheid.moz.fbs.common.profiel.VoorkeurResponse
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import java.time.Duration

class ProfielMagazijnResolverTest {

    private val profielClient = mockk<ProfielServiceClient>()
    private val factory = mockk<MagazijnClientFactory>(relaxed = true).also {
        every { it.getAllClients() } returns mapOf(
            "magazijn-a" to mockk(),
            "magazijn-b" to mockk(),
        )
        every { it.getAlleAfzenders() } returns mapOf(
            "magazijn-a" to setOf(Oin("00000001003214345000")),
            "magazijn-b" to setOf(Oin("00000001823288444000")),
        )
    }
    private val resolver = ProfielMagazijnResolver(profielClient, factory)

    @Test
    fun `BSN met 1 OIN-match levert 1 magazijn`() {
        every { profielClient.getPartij("BSN", "999993653") } returns PartijResponse(
            voorkeuren = listOf(
                VoorkeurResponse(
                    voorkeurType = "OntvangViaBerichtenbox",
                    waarde = "true",
                    scopes = listOf(ScopeResponse(partij = IdentificatieResponse("OIN", "00000001003214345000"))),
                ),
            ),
        )
        val result = resolver.resolve(Bsn("999993653")).await().atMost(Duration.ofSeconds(2))
        assertEquals(setOf("magazijn-a"), result)
    }

    @Test
    fun `BSN met 2 OIN-matches levert 2 magazijnen`() {
        every { profielClient.getPartij("BSN", "999993653") } returns PartijResponse(
            voorkeuren = listOf(
                VoorkeurResponse(
                    "OntvangViaBerichtenbox", "true",
                    scopes = listOf(
                        ScopeResponse(partij = IdentificatieResponse("OIN", "00000001003214345000")),
                        ScopeResponse(partij = IdentificatieResponse("OIN", "00000001823288444000")),
                    ),
                ),
            ),
        )
        val result = resolver.resolve(Bsn("999993653")).await().atMost(Duration.ofSeconds(2))
        assertEquals(setOf("magazijn-a", "magazijn-b"), result)
    }

    @Test
    fun `BSN met scope-OIN buiten config-lijst levert lege set`() {
        every { profielClient.getPartij("BSN", "999993653") } returns PartijResponse(
            voorkeuren = listOf(
                VoorkeurResponse(
                    "OntvangViaBerichtenbox", "true",
                    scopes = listOf(ScopeResponse(partij = IdentificatieResponse("OIN", "99999999999999999999"))),
                ),
            ),
        )
        val result = resolver.resolve(Bsn("999993653")).await().atMost(Duration.ofSeconds(2))
        assertEquals(emptySet<String>(), result)
    }

    @Test
    fun `BSN met voorkeur false levert lege set`() {
        every { profielClient.getPartij("BSN", "999993653") } returns PartijResponse(
            voorkeuren = listOf(
                VoorkeurResponse(
                    "OntvangViaBerichtenbox", "false",
                    scopes = listOf(ScopeResponse(partij = IdentificatieResponse("OIN", "00000001003214345000"))),
                ),
            ),
        )
        val result = resolver.resolve(Bsn("999993653")).await().atMost(Duration.ofSeconds(2))
        assertEquals(emptySet<String>(), result)
    }

    @Test
    fun `BSN met case-variant TRUE en Ja is opt-in`() {
        every { profielClient.getPartij("BSN", "999993653") } returns PartijResponse(
            voorkeuren = listOf(
                VoorkeurResponse(
                    "OntvangViaBerichtenbox", "TRUE",
                    scopes = listOf(ScopeResponse(partij = IdentificatieResponse("OIN", "00000001003214345000"))),
                ),
                VoorkeurResponse(
                    "OntvangViaBerichtenbox", "Ja",
                    scopes = listOf(ScopeResponse(partij = IdentificatieResponse("OIN", "00000001823288444000"))),
                ),
            ),
        )
        val result = resolver.resolve(Bsn("999993653")).await().atMost(Duration.ofSeconds(2))
        assertEquals(setOf("magazijn-a", "magazijn-b"), result)
    }

    @Test
    fun `BSN met YES is geen opt-in`() {
        every { profielClient.getPartij("BSN", "999993653") } returns PartijResponse(
            voorkeuren = listOf(
                VoorkeurResponse(
                    "OntvangViaBerichtenbox", "YES",
                    scopes = listOf(ScopeResponse(partij = IdentificatieResponse("OIN", "00000001003214345000"))),
                ),
            ),
        )
        val result = resolver.resolve(Bsn("999993653")).await().atMost(Duration.ofSeconds(2))
        assertEquals(emptySet<String>(), result)
    }

    @Test
    fun `BSN met lege voorkeuren levert lege set`() {
        every { profielClient.getPartij("BSN", "999993653") } returns PartijResponse(voorkeuren = emptyList())
        val result = resolver.resolve(Bsn("999993653")).await().atMost(Duration.ofSeconds(2))
        assertEquals(emptySet<String>(), result)
    }

    @Test
    fun `BSN met andere voorkeurType wordt genegeerd`() {
        every { profielClient.getPartij("BSN", "999993653") } returns PartijResponse(
            voorkeuren = listOf(
                VoorkeurResponse(
                    "WebsiteTaal", "nl",
                    scopes = listOf(ScopeResponse(partij = IdentificatieResponse("OIN", "00000001003214345000"))),
                ),
            ),
        )
        val result = resolver.resolve(Bsn("999993653")).await().atMost(Duration.ofSeconds(2))
        assertEquals(emptySet<String>(), result)
    }

    @Test
    fun `BSN met scope-identificatieType KVK wordt genegeerd`() {
        every { profielClient.getPartij("BSN", "999993653") } returns PartijResponse(
            voorkeuren = listOf(
                VoorkeurResponse(
                    "OntvangViaBerichtenbox", "true",
                    scopes = listOf(ScopeResponse(partij = IdentificatieResponse("KVK", "12345678"))),
                ),
            ),
        )
        val result = resolver.resolve(Bsn("999993653")).await().atMost(Duration.ofSeconds(2))
        assertEquals(emptySet<String>(), result)
    }

    @Test
    fun `OIN-ontvanger skipt Profiel-call en levert alle magazijnen`() {
        val result = resolver.resolve(Oin("00000001003214345000")).await().atMost(Duration.ofSeconds(2))
        assertEquals(setOf("magazijn-a", "magazijn-b"), result)
        verify(exactly = 0) { profielClient.getPartij(any(), any()) }
    }

    @Test
    fun `RSIN-ontvanger gebruikt RSIN-pad in Profiel-call`() {
        every { profielClient.getPartij("RSIN", "002564440") } returns PartijResponse(voorkeuren = emptyList())
        resolver.resolve(Rsin("002564440")).await().atMost(Duration.ofSeconds(2))
        verify(exactly = 1) { profielClient.getPartij("RSIN", "002564440") }
    }

    @Test
    fun `KVK-ontvanger gebruikt KVK-pad in Profiel-call`() {
        every { profielClient.getPartij("KVK", "12345678") } returns PartijResponse(voorkeuren = emptyList())
        resolver.resolve(Kvk("12345678")).await().atMost(Duration.ofSeconds(2))
        verify(exactly = 1) { profielClient.getPartij("KVK", "12345678") }
    }

    @Test
    fun `404 van Profiel levert lege set zonder fout`() {
        every { profielClient.getPartij("BSN", "999993653") } throws WebApplicationException(Response.status(404).build())
        val result = resolver.resolve(Bsn("999993653")).await().atMost(Duration.ofSeconds(2))
        assertEquals(emptySet<String>(), result)
    }

    @Test
    fun `500 van Profiel werpt ProfielServiceFoutException`() {
        every { profielClient.getPartij("BSN", "999993653") } throws WebApplicationException(Response.status(500).build())
        assertThrows(ProfielServiceFoutException::class.java) {
            resolver.resolve(Bsn("999993653")).await().atMost(Duration.ofSeconds(2))
        }
    }

    @Test
    fun `403 van Profiel werpt ProfielServiceFoutException (niet-404 = infra-fout)`() {
        every { profielClient.getPartij("BSN", "999993653") } throws WebApplicationException(Response.status(403).build())
        assertThrows(ProfielServiceFoutException::class.java) {
            resolver.resolve(Bsn("999993653")).await().atMost(Duration.ofSeconds(2))
        }
    }

    @Test
    fun `ProcessingException van Profiel werpt ProfielServiceFoutException`() {
        every { profielClient.getPartij("BSN", "999993653") } throws ProcessingException("connection reset")
        assertThrows(ProfielServiceFoutException::class.java) {
            resolver.resolve(Bsn("999993653")).await().atMost(Duration.ofSeconds(2))
        }
    }
}
