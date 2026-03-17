package nl.rijksoverheid.moz.berichtensessiecache.berichten

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Test

class RedisSearchEscapeTest {

    // --- escapeRedisSearch tests ---

    @Test
    fun `escapeRedisSearch laat alfanumerieke tekst ongewijzigd`() {
        assertEquals("helloWorld123", RedisBerichtenCache.escapeRedisSearch("helloWorld123"))
    }

    @Test
    fun `escapeRedisSearch escapet spaties`() {
        assertEquals("hello\\ world", RedisBerichtenCache.escapeRedisSearch("hello world"))
    }

    @Test
    fun `escapeRedisSearch escapet pipe karakter`() {
        assertEquals("term1\\|term2", RedisBerichtenCache.escapeRedisSearch("term1|term2"))
    }

    @Test
    fun `escapeRedisSearch escapet alle speciale tekens`() {
        val specialChars = listOf(
            ",", ".", "<", ">", "{", "}", "[", "]", "\"", "'", ":", ";",
            "!", "@", "#", "$", "%", "^", "&", "*", "(", ")", "-", "+",
            "=", "~", "\\", "/", "|", " ",
        )
        for (ch in specialChars) {
            val result = RedisBerichtenCache.escapeRedisSearch(ch)
            assertEquals(
                "\\$ch", result,
                "escapeRedisSearch moet '$ch' escapen naar '\\$ch'"
            )
        }
    }

    @Test
    fun `escapeRedisSearch escapet complexe invoer met meerdere speciale tekens`() {
        assertEquals(
            "test\\@user\\ \\(admin\\|root\\)",
            RedisBerichtenCache.escapeRedisSearch("test@user (admin|root)")
        )
    }

    @Test
    fun `SEARCH_SPECIAL regex matcht pipe karakter`() {
        assertNotNull(
            RedisBerichtenCache.SEARCH_SPECIAL.find("|"),
            "SEARCH_SPECIAL moet het pipe karakter matchen"
        )
    }

    // --- escapeTag tests ---

    @Test
    fun `escapeTag laat alfanumerieke tekst ongewijzigd`() {
        assertEquals("999993653", RedisBerichtenCache.escapeTag("999993653"))
    }

    @Test
    fun `escapeTag escapet spaties`() {
        assertEquals("hello\\ world", RedisBerichtenCache.escapeTag("hello world"))
    }

    @Test
    fun `escapeTag escapet pipe karakter`() {
        assertEquals("user1\\|user2", RedisBerichtenCache.escapeTag("user1|user2"))
    }

    @Test
    fun `escapeTag escapet alle speciale tekens`() {
        val specialChars = listOf(
            ",", ".", "<", ">", "{", "}", "[", "]", "\"", "'", ":", ";",
            "!", "@", "#", "$", "%", "^", "&", "*", "(", ")", "-", "+",
            "=", "~", "\\", "/", "|", " ",
        )
        for (ch in specialChars) {
            val result = RedisBerichtenCache.escapeTag(ch)
            assertEquals(
                "\\$ch", result,
                "escapeTag moet '$ch' escapen naar '\\$ch'"
            )
        }
    }

    @Test
    fun `escapeTag escapet complexe invoer met meerdere speciale tekens`() {
        assertEquals(
            "org\\:dept\\|team",
            RedisBerichtenCache.escapeTag("org:dept|team")
        )
    }

    @Test
    fun `TAG_SPECIAL regex matcht pipe karakter`() {
        assertNotNull(
            RedisBerichtenCache.TAG_SPECIAL.find("|"),
            "TAG_SPECIAL moet het pipe karakter matchen"
        )
    }
}
