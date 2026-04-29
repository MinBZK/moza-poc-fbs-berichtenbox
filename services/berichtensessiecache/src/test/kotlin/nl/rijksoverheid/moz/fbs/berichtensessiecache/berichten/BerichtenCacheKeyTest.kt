package nl.rijksoverheid.moz.fbs.berichtensessiecache.berichten

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.UUID

class BerichtenCacheKeyTest {

    @Test
    fun `cacheKey heeft correct prefix`() {
        val key = BerichtenCache.cacheKey("999993653")
        assertTrue(key.startsWith("berichtensessiecache:v1:"))
    }

    @Test
    fun `cacheKey bevat 64 hex characters na prefix`() {
        val key = BerichtenCache.cacheKey("999993653")
        val hash = key.removePrefix("berichtensessiecache:v1:")
        assertEquals(64, hash.length)
        assertTrue(hash.matches(Regex("[0-9a-f]{64}")))
    }

    @Test
    fun `cacheKey is deterministisch`() {
        val key1 = BerichtenCache.cacheKey("999993653")
        val key2 = BerichtenCache.cacheKey("999993653")
        assertEquals(key1, key2)
    }

    @Test
    fun `verschillende ontvangers geven verschillende hashes`() {
        val key1 = BerichtenCache.cacheKey("999993653")
        val key2 = BerichtenCache.cacheKey("123456789")
        assertNotEquals(key1, key2)
    }

    @Test
    fun `berichtKey bevat UUID`() {
        val id = UUID.fromString("11111111-1111-1111-1111-111111111111")
        val key = BerichtenCache.berichtKey(id)
        assertEquals("bericht:v1:11111111-1111-1111-1111-111111111111", key)
    }

    @Test
    fun `berichtKey heeft correct prefix`() {
        val id = UUID.randomUUID()
        val key = BerichtenCache.berichtKey(id)
        assertTrue(key.startsWith(BerichtenCache.BERICHT_PREFIX))
    }
}
