package nl.rijksoverheid.moz.fbs.democonsole.sessie

import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import io.quarkus.redis.datasource.RedisDataSource
import io.quarkus.redis.datasource.keys.KeyCommands
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class SessieServiceTest {

    private val keyCommands = mockk<KeyCommands<String>>()
    private val redis = mockk<RedisDataSource> { every { key() } returns keyCommands }
    private val service = SessieService(redis)

    @Test
    fun `verlopen wist de gevonden sessie-keys en geeft het aantal terug`() {
        val gevonden = listOf("berichtensessiecache:v2:abc:status", "berichtensessiecache:v2:abc:list")

        every { keyCommands.keys("berichtensessiecache:*") } returns gevonden
        every { keyCommands.del("berichtensessiecache:v2:abc:status", "berichtensessiecache:v2:abc:list") } returns 2

        assertEquals(2, service.laatSessiesVerlopen())

        verify { keyCommands.del("berichtensessiecache:v2:abc:status", "berichtensessiecache:v2:abc:list") }
    }

    @Test
    fun `verlopen bij lege keyspace geeft 0 en roept del niet aan`() {
        every { keyCommands.keys(any()) } returns emptyList()

        assertEquals(0, service.laatSessiesVerlopen())

        verify(exactly = 0) { keyCommands.del(*anyVararg<String>()) }
    }

    @Test
    fun `zo mogelijk wissen geeft het aantal door als het lukt`() {
        every { keyCommands.keys(any()) } returns listOf("berichtensessiecache:v3:abc:status")
        every { keyCommands.del("berichtensessiecache:v3:abc:status") } returns 1

        assertEquals(1, service.laatSessiesVerlopenZoMogelijk())
    }

    @Test
    fun `zo mogelijk wissen geeft null in plaats van een exception als Redis weg is`() {
        // Het legen van de magazijnen is dan al gebeurd; die knop mag hier niet alsnog op falen.
        every { keyCommands.keys(any()) } throws IllegalStateException("redis weg")

        assertNull(service.laatSessiesVerlopenZoMogelijk())
    }
}
