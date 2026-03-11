package nl.rijksoverheid.moz.berichtenlijst.berichten

import io.quarkus.test.Mock
import jakarta.enterprise.context.ApplicationScoped
import nl.rijksoverheid.moz.berichtenlijst.magazijn.MagazijnBerichtenResponse
import nl.rijksoverheid.moz.berichtenlijst.magazijn.MagazijnClient
import nl.rijksoverheid.moz.berichtenlijst.magazijn.MagazijnClientFactory
import nl.rijksoverheid.moz.berichtenlijst.magazijn.MagazijnenConfig
import java.lang.reflect.InvocationHandler
import java.lang.reflect.Method
import java.lang.reflect.Proxy
import java.time.Instant
import java.util.Optional
import java.util.UUID

@Mock
@ApplicationScoped
class MockMagazijnClientFactory : MagazijnClientFactory(MockMagazijnenConfig()) {

    companion object {
        val testBerichten = listOf(
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

        var shouldFail = false
    }

    override fun getAllClients(): Map<String, MagazijnClient> {
        // Use a JDK proxy to avoid Quarkus scanning the implementation as a JAX-RS endpoint
        val handler = InvocationHandler { _, method, args ->
            when (method.name) {
                "getBerichten" -> {
                    if (shouldFail) throw RuntimeException("Magazijn niet beschikbaar")
                    MagazijnBerichtenResponse(
                        berichten = testBerichten,
                        totalElements = testBerichten.size.toLong(),
                        totalPages = 1,
                    )
                }
                "getBerichtById" -> {
                    val berichtId = args[0] as String
                    testBerichten.find { it.berichtId.toString() == berichtId }
                }
                "zoekBerichten" -> {
                    val q = args[0] as String
                    val results = testBerichten.filter { it.onderwerp.contains(q, ignoreCase = true) }
                    MagazijnBerichtenResponse(
                        berichten = results,
                        totalElements = results.size.toLong(),
                        totalPages = 1,
                    )
                }
                else -> throw UnsupportedOperationException("Unexpected method: ${method.name}")
            }
        }
        val proxy = Proxy.newProxyInstance(
            MagazijnClient::class.java.classLoader,
            arrayOf(MagazijnClient::class.java),
            handler,
        ) as MagazijnClient
        return mapOf("magazijn-a" to proxy)
    }

    override fun getNaam(magazijnId: String): String? = "Magazijn A"
}

private class MockMagazijnenConfig : MagazijnenConfig {
    override fun instances(): Map<String, MagazijnenConfig.MagazijnInstance> {
        return mapOf("magazijn-a" to object : MagazijnenConfig.MagazijnInstance {
            override fun url(): String = "http://localhost:8081"
            override fun naam(): Optional<String> = Optional.of("Magazijn A")
        })
    }
}
