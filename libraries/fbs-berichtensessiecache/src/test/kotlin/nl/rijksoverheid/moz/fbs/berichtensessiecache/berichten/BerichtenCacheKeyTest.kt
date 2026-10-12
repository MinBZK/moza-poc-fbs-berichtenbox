package nl.rijksoverheid.moz.fbs.berichtensessiecache.berichten

import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.junit.TestProfile
import nl.rijksoverheid.moz.fbs.common.identificatie.Bsn
import nl.rijksoverheid.moz.fbs.common.identificatie.Oin
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.UUID

@QuarkusTest
@TestProfile(MockedDependenciesProfile::class)
class BerichtenCacheKeyTest {

    @Test
    fun `cacheKey heeft correct prefix`() {
        val key = BerichtenCache.cacheKey(Bsn("999993653"))
        assertTrue(key.startsWith("berichtensessiecache:v4:"))
    }

    @Test
    fun `cacheKey bevat 64 hex characters na prefix`() {
        val key = BerichtenCache.cacheKey(Bsn("999993653"))
        val hash = key.removePrefix("berichtensessiecache:v4:")
        assertEquals(64, hash.length)
        assertTrue(hash.matches(Regex("[0-9a-f]{64}")))
    }

    @Test
    fun `cacheKey is deterministisch`() {
        val key1 = BerichtenCache.cacheKey(Bsn("999993653"))
        val key2 = BerichtenCache.cacheKey(Bsn("999993653"))
        assertEquals(key1, key2)
    }

    @Test
    fun `verschillende ontvangers geven verschillende hashes`() {
        val key1 = BerichtenCache.cacheKey(Bsn("999993653"))
        val key2 = BerichtenCache.cacheKey(Bsn("999991401"))
        assertNotEquals(key1, key2)
    }

    @Test
    fun `BSN en OIN met zelfde waarde geven verschillende hashes`() {
        // Canonical form verschilt: "BSN:..." vs "OIN:..." — type is deel van de hash-input.
        val key1 = BerichtenCache.cacheKey(Bsn("999993653"))
        val key2 = BerichtenCache.cacheKey(Oin("00000001003214345000"))
        assertNotEquals(key1, key2)
    }

    @Test
    fun `berichtKey bevat magazijn en berichtnummer`() {
        val id = UUID.fromString("11111111-1111-1111-1111-111111111111")
        val key = BerichtenCache.berichtKey(BerichtSleutel(MAGAZIJN_A, id))
        assertEquals("bericht:v4:$MAGAZIJN_A:11111111-1111-1111-1111-111111111111", key)
    }

    @Test
    fun `berichtKey heeft correct prefix`() {
        val key = BerichtenCache.berichtKey(BerichtSleutel(MAGAZIJN_A, UUID.randomUUID()))
        assertTrue(key.startsWith(BerichtenCache.BERICHT_PREFIX))
    }

    @Test
    fun `zelfde berichtnummer bij twee magazijnen geeft twee sleutels`() {
        val id = UUID.randomUUID()

        assertNotEquals(
            BerichtenCache.berichtKey(BerichtSleutel(MAGAZIJN_A, id)),
            BerichtenCache.berichtKey(BerichtSleutel(MAGAZIJN_B, id)),
        )
    }

    @Test
    fun `tombstone-sleutel onderscheidt magazijnen met hetzelfde berichtnummer`() {
        val id = UUID.randomUUID()
        val ontvanger = Bsn("999993653")

        assertNotEquals(
            BerichtenCache.verwijderdKey(ontvanger, BerichtSleutel(MAGAZIJN_A, id)),
            BerichtenCache.verwijderdKey(ontvanger, BerichtSleutel(MAGAZIJN_B, id)),
        )
    }

    private companion object {
        const val MAGAZIJN_A = "00000001000000000001"
        const val MAGAZIJN_B = "00000001000000000002"
    }
}
