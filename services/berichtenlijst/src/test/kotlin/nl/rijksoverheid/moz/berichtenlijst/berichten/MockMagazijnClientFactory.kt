package nl.rijksoverheid.moz.berichtenlijst.berichten

import io.quarkus.test.Mock
import jakarta.enterprise.context.ApplicationScoped
import nl.rijksoverheid.moz.berichtenlijst.magazijn.MagazijnBerichtenResponse
import nl.rijksoverheid.moz.berichtenlijst.magazijn.MagazijnClient
import nl.rijksoverheid.moz.berichtenlijst.magazijn.MagazijnClientFactory
import nl.rijksoverheid.moz.berichtenlijst.magazijn.MagazijnenConfig
import java.lang.reflect.InvocationHandler
import java.lang.reflect.Proxy
import java.time.Instant
import java.util.Optional
import java.util.UUID

@Mock
@ApplicationScoped
class MockMagazijnClientFactory : MagazijnClientFactory(MockMagazijnenConfig()) {

    companion object {
        val testBerichtenA = listOf(
            Bericht(
                berichtId = UUID.fromString("11111111-1111-1111-1111-111111111111"),
                afzender = "00000001234567890000",
                ontvanger = "999993653",
                onderwerp = "Test bericht 1",
                tijdstip = Instant.parse("2026-03-10T10:00:00Z"),
                magazijnId = "magazijn-a",
            ),
            Bericht(
                berichtId = UUID.fromString("22222222-2222-2222-2222-222222222222"),
                afzender = "00000001234567890000",
                ontvanger = "999993653",
                onderwerp = "Test bericht 2",
                tijdstip = Instant.parse("2026-03-10T11:00:00Z"),
                magazijnId = "magazijn-a",
            ),
            Bericht(
                berichtId = UUID.fromString("33333333-3333-3333-3333-333333333333"),
                afzender = "00000009876543210000",
                ontvanger = "999993653",
                onderwerp = "Test bericht 3",
                tijdstip = Instant.parse("2026-03-10T12:00:00Z"),
                magazijnId = "magazijn-a",
            ),
        )

        val testBerichtenB = listOf(
            Bericht(
                berichtId = UUID.fromString("44444444-4444-4444-4444-444444444444"),
                afzender = "00000005555555550000",
                ontvanger = "999993653",
                onderwerp = "Test bericht 4",
                tijdstip = Instant.parse("2026-03-10T13:00:00Z"),
                magazijnId = "magazijn-b",
            ),
        )

        var shouldFailA = false
        var shouldFailB = false

        // Backwards-compatible alias
        var shouldFail: Boolean
            get() = shouldFailA
            set(value) { shouldFailA = value }
    }

    override fun getAllClients(): Map<String, MagazijnClient> {
        return mapOf(
            "magazijn-a" to createProxy(testBerichtenA, { shouldFailA }),
            "magazijn-b" to createProxy(testBerichtenB, { shouldFailB }),
        )
    }

    override fun getNaam(magazijnId: String): String? = when (magazijnId) {
        "magazijn-a" -> "Magazijn A"
        "magazijn-b" -> "Magazijn B"
        else -> null
    }

    private fun createProxy(berichten: List<Bericht>, shouldFail: () -> Boolean): MagazijnClient {
        val handler = InvocationHandler { _, method, args ->
            when (method.name) {
                "getBerichten" -> {
                    if (shouldFail()) throw RuntimeException("Magazijn niet beschikbaar")
                    MagazijnBerichtenResponse(
                        berichten = berichten,
                        totalElements = berichten.size.toLong(),
                        totalPages = 1,
                    )
                }
                "getBerichtById" -> {
                    if (shouldFail()) throw RuntimeException("Magazijn niet beschikbaar")
                    val berichtId = args[0] as String
                    berichten.find { it.berichtId.toString() == berichtId }
                }
                "zoekBerichten" -> {
                    if (shouldFail()) throw RuntimeException("Magazijn niet beschikbaar")
                    val q = args[0] as String
                    val results = berichten.filter { it.onderwerp.contains(q, ignoreCase = true) }
                    MagazijnBerichtenResponse(
                        berichten = results,
                        totalElements = results.size.toLong(),
                        totalPages = 1,
                    )
                }
                else -> throw UnsupportedOperationException("Unexpected method: ${method.name}")
            }
        }
        return Proxy.newProxyInstance(
            MagazijnClient::class.java.classLoader,
            arrayOf(MagazijnClient::class.java),
            handler,
        ) as MagazijnClient
    }
}

private class MockMagazijnenConfig : MagazijnenConfig {
    override fun instances(): Map<String, MagazijnenConfig.MagazijnInstance> {
        return mapOf(
            "magazijn-a" to object : MagazijnenConfig.MagazijnInstance {
                override fun url(): String = "http://localhost:8081"
                override fun naam(): Optional<String> = Optional.of("Magazijn A")
            },
            "magazijn-b" to object : MagazijnenConfig.MagazijnInstance {
                override fun url(): String = "http://localhost:8082"
                override fun naam(): Optional<String> = Optional.of("Magazijn B")
            },
        )
    }
}
