package nl.rijksoverheid.moz.fbs.berichtensessiecache.magazijn

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.util.Optional

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
        val factory = MagazijnClientFactory(makeConfig(emptyMap()))

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
        )

        val ex = assertThrows<IllegalStateException> { factory.init() }

        assertTrue(ex.message!!.contains("Ongeldige afzender-OIN"), "Bericht was: ${ex.message}")
    }

    @Test
    fun `init met afzender bestaande uit geheel nullen faalt met 'Ongeldige afzender-OIN'`() {
        val factory = MagazijnClientFactory(
            makeConfig(
                mapOf("mag-a" to makeInstance(url = "http://localhost:8081", afzenders = listOf("00000000000000000000"))),
            ),
        )

        val ex = assertThrows<IllegalStateException> { factory.init() }

        assertTrue(ex.message!!.contains("Ongeldige afzender-OIN"), "Bericht was: ${ex.message}")
    }
}
