package nl.rijksoverheid.moz.fbs.berichtensessiecache.berichten

import io.mockk.every
import io.mockk.mockk
import jakarta.enterprise.context.ApplicationScoped
import jakarta.enterprise.inject.Alternative
import jakarta.ws.rs.ProcessingException
import jakarta.ws.rs.WebApplicationException
import nl.rijksoverheid.moz.fbs.berichtensessiecache.magazijn.MagazijnBericht
import nl.rijksoverheid.moz.fbs.berichtensessiecache.magazijn.MagazijnBerichtenResponse
import nl.rijksoverheid.moz.fbs.berichtensessiecache.magazijn.MagazijnClient
import nl.rijksoverheid.moz.fbs.berichtensessiecache.magazijn.MagazijnClientFactory
import nl.rijksoverheid.moz.fbs.berichtensessiecache.magazijn.MagazijnenConfig
import java.time.Instant
import java.util.Optional
import java.util.UUID

@Alternative
@ApplicationScoped
internal class MockMagazijnClientFactory : MagazijnClientFactory(
    MockMagazijnenConfig(),
    profile = "test",
    connectTimeoutMs = 2000L,
    readTimeoutMs = 12000L,
) {

    companion object {
        val testBerichtenA = listOf(
            Bericht(
                berichtId = UUID.fromString("11111111-1111-1111-1111-111111111111"),
                afzender = "00000001234567890000",
                ontvanger = "999993653",
                onderwerp = "Test bericht 1",
                inhoud = "Inhoud van test bericht 1",
                publicatietijdstip = Instant.parse("2026-03-10T10:00:00Z"),
                magazijnId = "magazijn-a",
                aantalBijlagen = 0,
                map = "werk",
            ),
            Bericht(
                berichtId = UUID.fromString("22222222-2222-2222-2222-222222222222"),
                afzender = "00000001234567890000",
                ontvanger = "999993653",
                onderwerp = "Test bericht 2",
                inhoud = "Inhoud van test bericht 2",
                publicatietijdstip = Instant.parse("2026-03-10T11:00:00Z"),
                magazijnId = "magazijn-a",
                aantalBijlagen = 1,
                map = "werk",
            ),
            Bericht(
                berichtId = UUID.fromString("33333333-3333-3333-3333-333333333333"),
                afzender = "00000009876543210000",
                ontvanger = "999993653",
                onderwerp = "Test bericht 3",
                inhoud = "Inhoud van test bericht 3",
                publicatietijdstip = Instant.parse("2026-03-10T12:00:00Z"),
                magazijnId = "magazijn-a",
                aantalBijlagen = 2,
                map = "prive",
            ),
        )

        val testBerichtenB = listOf(
            Bericht(
                berichtId = UUID.fromString("44444444-4444-4444-4444-444444444444"),
                afzender = "00000005555555550000",
                ontvanger = "999993653",
                onderwerp = "Test bericht 4",
                inhoud = "Inhoud van test bericht 4",
                publicatietijdstip = Instant.parse("2026-03-10T13:00:00Z"),
                magazijnId = "magazijn-b",
                aantalBijlagen = 0,
                map = "werk",
            ),
        )

        var shouldFailA = false
        var shouldFailB = false
        var shouldTimeoutA = false
        var shouldTimeoutB = false
        var shouldHttpFailA: Int? = null
        var shouldHttpFailB: Int? = null
    }

    override fun getAllClients(): Map<String, MagazijnClient> {
        return mapOf(
            "magazijn-a" to magazijnClient(testBerichtenA, { shouldFailA }, { shouldTimeoutA }, { shouldHttpFailA }),
            "magazijn-b" to magazijnClient(testBerichtenB, { shouldFailB }, { shouldTimeoutB }, { shouldHttpFailB }),
        )
    }

    override fun getNaam(magazijnId: String): String? = when (magazijnId) {
        "magazijn-a" -> "Magazijn A"
        "magazijn-b" -> "Magazijn B"
        else -> null
    }

    private fun magazijnClient(
        berichten: List<Bericht>,
        shouldFail: () -> Boolean,
        shouldTimeout: () -> Boolean,
        httpFailStatus: () -> Int? = { null },
    ): MagazijnClient = mockk<MagazijnClient>().also { client ->
        val magazijnBerichten = berichten.map { it.toMagazijnBericht() }

        every { client.getBerichten(any(), any()) } answers {
            if (shouldTimeout()) Thread.sleep(15_000)
            if (shouldFail()) throw ProcessingException("Magazijn niet beschikbaar")
            httpFailStatus()?.let { status -> throw WebApplicationException(status) }
            MagazijnBerichtenResponse(berichten = magazijnBerichten)
        }

        every { client.getBerichtById(any()) } answers {
            if (shouldFail()) throw ProcessingException("Magazijn niet beschikbaar")
            val berichtId = firstArg<String>()
            magazijnBerichten.find { it.berichtId.toString() == berichtId }
        }
    }

    // Test-fixtures zijn cache-`Bericht` (plain string ontvanger) zodat assertions over de
    // cache-inhoud direct werken. Magazijn-client levert echter MagazijnBericht (object-vorm
    // ontvanger), dus we wrappen de waarde hier in een neutrale BSN-Identificatienummer —
    // de service vlakt het type weer weg via `MagazijnBericht.toBericht`.
    private fun Bericht.toMagazijnBericht(): MagazijnBericht = MagazijnBericht(
        berichtId = berichtId,
        afzender = afzender,
        ontvanger = MagazijnBericht.Identificatienummer(type = "BSN", waarde = ontvanger),
        onderwerp = onderwerp,
        inhoud = inhoud,
        publicatietijdstip = publicatietijdstip,
        aantalBijlagen = aantalBijlagen,
        // Magazijn levert de map via het status-object; `toBericht` leest hem daar weer uit.
        // Zonder dit zou de fixture-`map` verloren gaan in de MagazijnBericht→Bericht-roundtrip.
        status = map?.let { MagazijnBericht.MagazijnBerichtStatus(map = it) },
    )
}

private class MockMagazijnenConfig : MagazijnenConfig {
    override fun instances(): Map<String, MagazijnenConfig.MagazijnInstance> {
        return mapOf(
            "magazijn-a" to object : MagazijnenConfig.MagazijnInstance {
                override fun url(): String = "http://localhost:8081"
                override fun naam(): Optional<String> = Optional.of("Magazijn A")
                override fun afzenders(): List<String> = listOf("00000001003214345000")
            },
            "magazijn-b" to object : MagazijnenConfig.MagazijnInstance {
                override fun url(): String = "http://localhost:8082"
                override fun naam(): Optional<String> = Optional.of("Magazijn B")
                override fun afzenders(): List<String> = listOf("00000001823288444000")
            },
        )
    }
}
