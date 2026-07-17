package nl.rijksoverheid.moz.fbs.magazijnregister

import io.smallrye.config.PropertiesConfigSource
import io.smallrye.config.SmallRyeConfigBuilder
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Roundtrip-test van de `@ConfigMapping`-binding zelf: borgt dat de
 * properties-notatie `magazijnen."<OIN>".{url,naam}` daadwerkelijk op de
 * map-onder-prefix landt (en niet stilletjes op een andere structuur na een
 * refactor van de mapping-interface).
 */
class MagazijnregisterConfigMappingTest {

    private val oinA = "00000001003214345000"
    private val oinB = "00000001823288444000"

    @Test
    fun `properties met OIN-keys binden op de map onder de magazijnen-prefix`() {
        val mapping = mapping(
            "magazijnen.\"$oinA\".url" to "http://localhost:8081",
            "magazijnen.\"$oinA\".naam" to "Belastingdienst",
            "magazijnen.\"$oinB\".url" to "http://localhost:8082",
        )

        assertEquals(setOf(oinA, oinB), mapping.inschrijvingen().keys)
        assertEquals("http://localhost:8081", mapping.inschrijvingen()[oinA]!!.url())
        assertEquals("Belastingdienst", mapping.inschrijvingen()[oinA]!!.naam().get())
    }

    @Test
    fun `naam is optioneel in de properties-notatie`() {
        val mapping = mapping("magazijnen.\"$oinA\".url" to "http://localhost:8081")

        assertTrue(mapping.inschrijvingen()[oinA]!!.naam().isEmpty)
    }

    @Test
    fun `grantHash bindt op de map onder de magazijnen-prefix`() {
        val mapping = mapping(
            "magazijnen.\"$oinA\".url" to "http://localhost:8081",
            "magazijnen.\"$oinA\".grantHash" to "abc123",
        )

        assertEquals("abc123", mapping.inschrijvingen()[oinA]!!.grantHash().get())
    }

    @Test
    fun `grantHash is optioneel in de properties-notatie`() {
        val mapping = mapping("magazijnen.\"$oinA\".url" to "http://localhost:8081")

        assertTrue(mapping.inschrijvingen()[oinA]!!.grantHash().isEmpty)
    }

    private fun mapping(vararg properties: Pair<String, String>): MagazijnregisterConfig =
        SmallRyeConfigBuilder()
            .withMapping(MagazijnregisterConfig::class.java)
            .withSources(PropertiesConfigSource(mapOf(*properties), "test", 100))
            .build()
            .getConfigMapping(MagazijnregisterConfig::class.java)
}
