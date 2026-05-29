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
import nl.rijksoverheid.moz.fbs.common.profiel.DienstResponse
import nl.rijksoverheid.moz.fbs.common.profiel.IdentificatieResponse
import nl.rijksoverheid.moz.fbs.common.profiel.PartijResponse
import nl.rijksoverheid.moz.fbs.common.profiel.ProfielServiceClient
import nl.rijksoverheid.moz.fbs.common.profiel.ProfielServiceFoutException
import nl.rijksoverheid.moz.fbs.common.profiel.ScopeResponse
import nl.rijksoverheid.moz.fbs.common.profiel.VoorkeurResponse
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.junit.TestProfile
import nl.rijksoverheid.moz.fbs.berichtensessiecache.berichten.MockedDependenciesProfile
import java.io.IOException
import java.time.Duration
import java.util.Optional

@QuarkusTest
@TestProfile(MockedDependenciesProfile::class)
class ProfielMagazijnResolverTest {

    private val profielClient = mockk<ProfielServiceClient>()

    // Echte factory + stub-config i.p.v. mockk: MockK kan de Oin-value-class niet
    // synthesiseren tijdens matcher-recording (`any<Oin>()` triggert de validerende
    // constructor met dummy-waarden → DomainValidationException). De factory bouwt
    // de reverse-index in init() zelf op, identiek aan productie. Subclass overschrijft
    // createClient zodat geen Quarkus-CDI-context nodig is voor de REST-client-builder.
    private val factory = object : MagazijnClientFactory(
        config = object : MagazijnenConfig {
            override fun instances() = mapOf(
                "magazijn-a" to instance("http://test-a", "00000001003214345000"),
                "magazijn-b" to instance("http://test-b", "00000001823288444000"),
            )
        },
        profile = "test",
        connectTimeoutMs = 2000L,
        readTimeoutMs = 12000L,
    ) {
        override fun createClient(instance: MagazijnenConfig.MagazijnInstance): MagazijnClient = mockk()
    }.also { it.init() }

    // Korte inner-timeout (2s) in unit-tests: prod-defaults zou tests vertragen
    // bij hangende mocks; bestaande `await().atMost(Duration.ofSeconds(2))` matcht.
    // cacheTtlSeconds=0 schakelt de Caffeine-cache effectief uit: per-test isolatie
    // op exact-N-Profiel-calls blijft deterministisch.
    private val resolver = ProfielMagazijnResolver(profielClient, factory, innerTimeoutSeconds = 2L, cacheTtlSeconds = 0L, cacheMaxSize = 100L)

    private fun instance(url: String, afzender: String): MagazijnenConfig.MagazijnInstance =
        object : MagazijnenConfig.MagazijnInstance {
            override fun url() = url
            override fun naam() = Optional.empty<String>()
            override fun afzenders() = listOf(afzender)
        }

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
    fun `BSN met scope-OIN buiten config-lijst gooit configDrift exception`() {
        // 100% drift: alle opt-in OINs zijn onbekend bij magazijn-config → CONFIG_DRIFT.
        // Caller (service) emit dan OPHALEN_FOUT i.p.v. silent empty-result.
        every { profielClient.getPartij("BSN", "999993653") } returns PartijResponse(
            voorkeuren = listOf(
                VoorkeurResponse(
                    "OntvangViaBerichtenbox", "true",
                    scopes = listOf(ScopeResponse(partij = IdentificatieResponse("OIN", "99999999999999999999"))),
                ),
            ),
        )
        val ex = assertThrows(ProfielServiceFoutException::class.java) {
            resolver.resolve(Bsn("999993653")).await().atMost(Duration.ofSeconds(2))
        }
        assertEquals(ProfielServiceFoutException.Categorie.CONFIG_DRIFT, ex.categorie)
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
    fun `500 van Profiel werpt UPSTREAM_ERROR met httpStatus`() {
        every { profielClient.getPartij("BSN", "999993653") } throws WebApplicationException(Response.status(500).build())

        val ex = assertThrows(ProfielServiceFoutException::class.java) {
            resolver.resolve(Bsn("999993653")).await().atMost(Duration.ofSeconds(2))
        }

        assertEquals(ProfielServiceFoutException.Categorie.UPSTREAM_ERROR, ex.categorie)
        assertEquals(500, ex.httpStatus)
    }

    @Test
    fun `403 van Profiel werpt UPSTREAM_ERROR met httpStatus (niet-404 = infra-fout, niet lege set)`() {
        // Regressie-guard: 401/403/405 mogen NOOIT in het 404-emptySet-pad vallen (silent
        // failure: auth-fout zou dan "geen voorkeuren" worden). Assert dus expliciet de
        // categorie + httpStatus, niet alleen het exception-type.
        every { profielClient.getPartij("BSN", "999993653") } throws WebApplicationException(Response.status(403).build())

        val ex = assertThrows(ProfielServiceFoutException::class.java) {
            resolver.resolve(Bsn("999993653")).await().atMost(Duration.ofSeconds(2))
        }

        assertEquals(ProfielServiceFoutException.Categorie.UPSTREAM_ERROR, ex.categorie)
        assertEquals(403, ex.httpStatus)
    }

    @Test
    fun `401 van Profiel werpt UPSTREAM_ERROR met httpStatus (auth-misser)`() {
        every { profielClient.getPartij("BSN", "999993653") } throws WebApplicationException(Response.status(401).build())

        val ex = assertThrows(ProfielServiceFoutException::class.java) {
            resolver.resolve(Bsn("999993653")).await().atMost(Duration.ofSeconds(2))
        }

        assertEquals(ProfielServiceFoutException.Categorie.UPSTREAM_ERROR, ex.categorie)
        assertEquals(401, ex.httpStatus)
    }

    @Test
    fun `ProcessingException van Profiel werpt ProfielServiceFoutException`() {
        every { profielClient.getPartij("BSN", "999993653") } throws ProcessingException("connection reset")
        assertThrows(ProfielServiceFoutException::class.java) {
            resolver.resolve(Bsn("999993653")).await().atMost(Duration.ofSeconds(2))
        }
    }

    @Test
    fun `Mutiny TimeoutException wordt gemapt op ProfielServiceFoutException timeout`() {
        // Het ifNoItem(18s)-pad in de resolver levert deze TimeoutException op
        // bij een hangende upstream — de happy path daarvoor zelf testen vergt
        // een 18s WireMock-delay (te duur in CI). Hier valideren we alleen dat
        // de mapping naar `timeout()` werkt; de timing zelf wordt door
        // bestaande Mutiny-tests gedekt.
        every { profielClient.getPartij("BSN", "999993653") } throws io.smallrye.mutiny.TimeoutException()

        val ex = assertThrows(ProfielServiceFoutException::class.java) {
            resolver.resolve(Bsn("999993653")).await().atMost(Duration.ofSeconds(2))
        }

        assertTrue(ex.message!!.contains("timeout"), "Was: ${ex.message}")
        assertTrue(ex.cause is io.smallrye.mutiny.TimeoutException, "Cause: ${ex.cause}")
    }

    @Test
    fun `NullPointerException uit client wraps als ProfielServiceFoutException onverwacht`() {
        every { profielClient.getPartij("BSN", "999993653") } throws NullPointerException("client interne NPE")

        val ex = assertThrows(ProfielServiceFoutException::class.java) {
            resolver.resolve(Bsn("999993653")).await().atMost(Duration.ofSeconds(2))
        }

        assertNotNull(ex.cause)
        assertTrue(ex.cause is NullPointerException, "Cause moet NPE zijn: ${ex.cause}")
        assertTrue(ex.message!!.contains("onverwacht"), "Was: ${ex.message}")
    }

    @Test
    fun `ProcessingException met JsonProcessingException cause routes naar malformed`() {
        val cause = com.fasterxml.jackson.core.JsonParseException(null, "bad json")
        every { profielClient.getPartij("BSN", "999993653") } throws jakarta.ws.rs.ProcessingException(cause)

        val ex = assertThrows(ProfielServiceFoutException::class.java) {
            resolver.resolve(Bsn("999993653")).await().atMost(Duration.ofSeconds(2))
        }

        assertTrue(ex.message!!.contains("onleesbare JSON-respons"), "Was: ${ex.message}")
    }

    @Test
    fun `ProcessingException met IOException cause routes naar netwerk`() {
        every { profielClient.getPartij("BSN", "999993653") } throws jakarta.ws.rs.ProcessingException(IOException("conn reset"))

        val ex = assertThrows(ProfielServiceFoutException::class.java) {
            resolver.resolve(Bsn("999993653")).await().atMost(Duration.ofSeconds(2))
        }

        assertTrue(ex.message!!.contains("netwerkfout"), "Was: ${ex.message}")
    }

    @Test
    fun `scope met alleen dienst (partij null) wordt genegeerd`() {
        every { profielClient.getPartij("BSN", "999993653") } returns PartijResponse(
            voorkeuren = listOf(
                VoorkeurResponse(
                    voorkeurType = "OntvangViaBerichtenbox",
                    waarde = "true",
                    scopes = listOf(
                        ScopeResponse(partij = null, dienst = DienstResponse(id = 42, beschrijving = "X")),
                    ),
                ),
            ),
        )
        val result = resolver.resolve(Bsn("999993653")).await().atMost(Duration.ofSeconds(2))
        assertEquals(emptySet<String>(), result)
    }

    @Test
    fun `OIN die door beide magazijnen wordt geserveerd levert beide magazijnen op`() {
        // Reverse-index overlap-case: zelfde afzender-OIN op magazijn-a EN magazijn-b.
        // De resolver moet dan beide magazijnen leveren (set-union via reverse-index).
        val gedeeldeOin = "00000001003214345000"
        val overlapFactory = object : MagazijnClientFactory(
            config = object : MagazijnenConfig {
                override fun instances() = mapOf(
                    "magazijn-a" to instance("http://test-a", gedeeldeOin),
                    "magazijn-b" to instance("http://test-b", gedeeldeOin),
                )
            },
            profile = "test",
            connectTimeoutMs = 2000L,
            readTimeoutMs = 12000L,
        ) {
            override fun createClient(instance: MagazijnenConfig.MagazijnInstance): MagazijnClient = mockk()
        }.also { it.init() }

        val overlapResolver = ProfielMagazijnResolver(profielClient, overlapFactory, innerTimeoutSeconds = 2L, cacheTtlSeconds = 0L, cacheMaxSize = 100L)

        every { profielClient.getPartij("BSN", "999993653") } returns PartijResponse(
            voorkeuren = listOf(
                VoorkeurResponse(
                    voorkeurType = "OntvangViaBerichtenbox",
                    waarde = "true",
                    scopes = listOf(ScopeResponse(partij = IdentificatieResponse("OIN", gedeeldeOin))),
                ),
            ),
        )

        val result = overlapResolver.resolve(Bsn("999993653")).await().atMost(Duration.ofSeconds(2))
        assertEquals(setOf("magazijn-a", "magazijn-b"), result)
    }

    @Test
    fun `onleesbare upstream-OIN wordt defensief overgeslagen zonder exception`() {
        every { profielClient.getPartij("BSN", "999993653") } returns PartijResponse(
            voorkeuren = listOf(
                VoorkeurResponse(
                    voorkeurType = "OntvangViaBerichtenbox",
                    waarde = "true",
                    scopes = listOf(
                        ScopeResponse(partij = IdentificatieResponse("OIN", "NOT-AN-OIN")),
                        ScopeResponse(partij = IdentificatieResponse("OIN", "00000001003214345000")),
                    ),
                ),
            ),
        )
        val result = resolver.resolve(Bsn("999993653")).await().atMost(Duration.ofSeconds(2))
        assertEquals(setOf("magazijn-a"), result)
    }

    @Test
    fun `geldige onbekende OIN naast geldige match levert alleen het bekende magazijn (drift-skip zonder exception)`() {
        // Eén opt-in OIN is geldig van formaat maar onbekend bij magazijn-config (driftSkip),
        // de andere matcht magazijn-a. result is niet leeg → géén CONFIG_DRIFT-exception;
        // alleen het bekende magazijn wordt geleverd. Pint de partiële-skip-tak vast — die
        // verschilt van zowel 100%-drift (alles onbekend → exception) als van het ongeldig-
        // formaat-pad (`NOT-AN-OIN`, andere teller).
        every { profielClient.getPartij("BSN", "999993653") } returns PartijResponse(
            voorkeuren = listOf(
                VoorkeurResponse(
                    voorkeurType = "OntvangViaBerichtenbox", waarde = "true",
                    scopes = listOf(
                        ScopeResponse(partij = IdentificatieResponse("OIN", "99999999999999999999")),
                        ScopeResponse(partij = IdentificatieResponse("OIN", "00000001003214345000")),
                    ),
                ),
            ),
        )
        val result = resolver.resolve(Bsn("999993653")).await().atMost(Duration.ofSeconds(2))
        assertEquals(setOf("magazijn-a"), result)
    }

    @Test
    fun `alle opt-in OINs ongeldig formaat gooit CONFIG_DRIFT (upstream-data-issue pad)`() {
        // Tellers-pad: ongeldig=N, driftSkips=0 → exception én log-tekst "Upstream-
        // data-issue" (niet "Config-drift"). Onderscheid is alleen in log; productie
        // moet wel CONFIG_DRIFT-categorie gooien zodat caller eenduidig kan handelen.
        every { profielClient.getPartij("BSN", "999993653") } returns PartijResponse(
            voorkeuren = listOf(
                VoorkeurResponse(
                    voorkeurType = "OntvangViaBerichtenbox", waarde = "true",
                    scopes = listOf(
                        ScopeResponse(partij = IdentificatieResponse("OIN", "BAD-1")),
                        ScopeResponse(partij = IdentificatieResponse("OIN", "BAD-2")),
                    ),
                ),
            ),
        )
        val ex = assertThrows(ProfielServiceFoutException::class.java) {
            resolver.resolve(Bsn("999993653")).await().atMost(Duration.ofSeconds(2))
        }
        assertEquals(ProfielServiceFoutException.Categorie.CONFIG_DRIFT, ex.categorie)
    }

    @Test
    fun `mixed pad (ongeldig + driftSkip + 0 valid) gooit CONFIG_DRIFT`() {
        // Combinatie van beide skip-redenen → totaal>0, result.isEmpty() → exception.
        // Triggert eerste tak (driftSkips>0) met ongeldig-counter in log-format.
        every { profielClient.getPartij("BSN", "999993653") } returns PartijResponse(
            voorkeuren = listOf(
                VoorkeurResponse(
                    voorkeurType = "OntvangViaBerichtenbox", waarde = "true",
                    scopes = listOf(
                        ScopeResponse(partij = IdentificatieResponse("OIN", "BAD-FORMAT")),
                        ScopeResponse(partij = IdentificatieResponse("OIN", "99999999999999999999")),
                    ),
                ),
            ),
        )
        val ex = assertThrows(ProfielServiceFoutException::class.java) {
            resolver.resolve(Bsn("999993653")).await().atMost(Duration.ofSeconds(2))
        }
        assertEquals(ProfielServiceFoutException.Categorie.CONFIG_DRIFT, ex.categorie)
    }
}
