package nl.rijksoverheid.moz.fbs.berichtensessiecache.magazijn

import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.junit.TestProfile
import nl.rijksoverheid.moz.fbs.berichtensessiecache.berichten.MockedDependenciesProfile
import nl.rijksoverheid.moz.fbs.common.identificatie.Oin
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.util.Optional

@QuarkusTest
@TestProfile(MockedDependenciesProfile::class)
class MagazijnClientFactoryInitTest {

    private fun makeConfig(instances: Map<String, MagazijnenConfig.MagazijnInstance>): MagazijnenConfig =
        object : MagazijnenConfig {
            override fun instances() = instances
        }

    private fun makeInstance(
        url: String,
        naam: String? = null,
        afzenders: List<String> = emptyList(),
    ): MagazijnenConfig.MagazijnInstance =
        object : MagazijnenConfig.MagazijnInstance {
            override fun url() = url
            override fun naam() = Optional.ofNullable(naam)
            override fun afzenders() = afzenders
        }

    @Test
    fun `init met lege instances faalt met 'Geen magazijnen geconfigureerd'`() {
        val factory = MagazijnClientFactory(makeConfig(emptyMap()), profile = "test")

        val ex = assertThrows<IllegalArgumentException> { factory.init() }

        assertTrue(ex.message!!.contains("Geen magazijnen"), "Bericht was: ${ex.message}")
    }

    @Test
    fun `init met ongeldige URL faalt met 'Ongeldige URL voor magazijn'`() {
        // Spaties in het host-deel maken de URI illegaal volgens RFC 2396 / java.net.URI.
        val factory = MagazijnClientFactory(
            makeConfig(
                mapOf("mag-a" to makeInstance(url = "http://host met spaties/pad", afzenders = listOf("00000001003214345000"))),
            ),
            profile = "test",
        )

        val ex = assertThrows<IllegalStateException> { factory.init() }

        assertTrue(ex.message!!.contains("Ongeldige URL"), "Bericht was: ${ex.message}")
    }

    @Test
    fun `init met lege afzenders-lijst faalt met 'geen afzenders geconfigureerd'`() {
        val factory = MagazijnClientFactory(
            makeConfig(
                mapOf("mag-a" to makeInstance(url = "http://localhost:8081", afzenders = emptyList())),
            ),
            profile = "test",
        )

        val ex = assertThrows<IllegalArgumentException> { factory.init() }

        assertTrue(ex.message!!.contains("afzenders"), "Bericht was: ${ex.message}")
    }

    @Test
    fun `init met ongeldige OIN (geen 20 cijfers) faalt met 'Ongeldige afzender-OIN'`() {
        val factory = MagazijnClientFactory(
            makeConfig(
                mapOf("mag-a" to makeInstance(url = "http://localhost:8081", afzenders = listOf("12345"))),
            ),
            profile = "test",
        )

        val ex = assertThrows<IllegalStateException> { factory.init() }

        assertTrue(ex.message!!.contains("Ongeldige afzender-OIN"), "Bericht was: ${ex.message}")
    }

    @Test
    fun `init met http-URL in prod-profiel faalt met TLS-verplichting`() {
        val factory = MagazijnClientFactory(
            makeConfig(
                mapOf("mag-a" to makeInstance(url = "http://magazijn-a.prod.local", afzenders = listOf("00000001003214345000"))),
            ),
            profile = "prod",
        )

        val ex = assertThrows<IllegalArgumentException> { factory.init() }

        assertTrue(ex.message!!.contains("https://"), "Bericht was: ${ex.message}")
        assertTrue(ex.message!!.contains("magazijnen.instances.mag-a.url"), "Bericht was: ${ex.message}")
    }

    @Test
    fun `init met meerdere afzenders per magazijn bouwt reverse-index correct`() {
        // Regressie-vangnet: cachedOinToMagazijnen-buildMap moet alle OINs van een
        // multi-afzender-magazijn correct indexeren. Zonder test kan een refactor van
        // de `forEach { oin -> getOrPut(oin) { mutableSetOf() }.add(id) }`-loop stil
        // alleen de eerste OIN registreren en de andere droppen.
        val oinA = "00000001003214345000"
        val oinB = "00000001003214345001"
        val factory = MagazijnClientFactory(
            makeConfig(
                mapOf("mag-a" to makeInstance(url = "http://localhost:8081", afzenders = listOf(oinA, oinB))),
            ),
            profile = "test",
        )

        factory.init()

        assertEquals(setOf("mag-a"), factory.magazijnenVoorAfzender(Oin(oinA)))
        assertEquals(setOf("mag-a"), factory.magazijnenVoorAfzender(Oin(oinB)))
        assertEquals(setOf(Oin(oinA), Oin(oinB)), factory.getAfzenders("mag-a"))
    }

    @Test
    fun `init met overlappende OIN over meerdere magazijnen merget reverse-index sets`() {
        // Zelfde OIN bij twee magazijnen → set-union; reverse-index moet beide IDs bevatten.
        val gedeeld = "00000001003214345000"
        val factory = MagazijnClientFactory(
            makeConfig(
                mapOf(
                    "mag-a" to makeInstance(url = "http://localhost:8081", afzenders = listOf(gedeeld)),
                    "mag-b" to makeInstance(url = "http://localhost:8082", afzenders = listOf(gedeeld)),
                ),
            ),
            profile = "test",
        )

        factory.init()

        assertEquals(setOf("mag-a", "mag-b"), factory.magazijnenVoorAfzender(Oin(gedeeld)))
    }

    @Test
    fun `init met afzender bestaande uit geheel nullen faalt met 'Ongeldige afzender-OIN'`() {
        val factory = MagazijnClientFactory(
            makeConfig(
                mapOf("mag-a" to makeInstance(url = "http://localhost:8081", afzenders = listOf("00000000000000000000"))),
            ),
            profile = "test",
        )

        val ex = assertThrows<IllegalStateException> { factory.init() }

        assertTrue(ex.message!!.contains("Ongeldige afzender-OIN"), "Bericht was: ${ex.message}")
    }
}
