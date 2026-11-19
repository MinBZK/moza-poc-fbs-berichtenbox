package nl.rijksoverheid.moz.fbs.berichtenapp

import com.fasterxml.jackson.databind.ObjectMapper
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.name

/**
 * De meegeleverde `jose` hoort bij `package-lock.json`. Dependabot ziet alleen de lockfile; zonder
 * deze test zou een bump groen worden terwijl de app nog de oude kopie laadt. Een PR van Dependabot
 * blijft zo rood tot iemand `npm ci --ignore-scripts && npm run vendor` draait.
 */
class VendorJoseTest {

    private val json = ObjectMapper()
    private val vendor = Path.of("src/main/resources/META-INF/resources/vendor")

    @Test
    fun `vendor-jose is de versie en integrity uit de lockfile`() {
        val lock = json.readTree(Path.of("package-lock.json").toFile()).path("packages").path("node_modules/jose")
        val bron = json.readTree(vendor.resolve("jose/BRON.json").toFile())

        assertTrue(lock.path("version").isTextual, "jose ontbreekt in package-lock.json")
        assertEquals(lock.path("version").asText(), bron.path("versie").asText(), "draai npm ci --ignore-scripts && npm run vendor")
        assertEquals(lock.path("integrity").asText(), bron.path("integrity").asText(), "draai npm ci --ignore-scripts && npm run vendor")
    }

    @Test
    fun `er staat maar één kopie van jose in vendor`() {
        val kopieen = Files.list(vendor).use { stream -> stream.map { it.name }.filter { it.startsWith("jose") }.toList() }

        assertEquals(listOf("jose"), kopieen)
    }
}
