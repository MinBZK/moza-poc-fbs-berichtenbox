package nl.rijksoverheid.moz.fbs.democonsole.sessie

import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import io.mockk.verifyOrder
import io.quarkus.redis.datasource.RedisDataSource
import io.quarkus.redis.datasource.keys.KeyCommands
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource

class SessieServiceTest {

    private val keyCommands = mockk<KeyCommands<String>>()
    private val redis = mockk<RedisDataSource> { every { key() } returns keyCommands }
    private val beheer = mockk<SessieBeheer> { every { telLopende() } returns 0 }
    private val service = SessieService(redis, beheer)

    @Test
    fun `verlopen wist de gevonden cache-keys en geeft het aantal terug`() {
        val gevonden = listOf("berichtensessiecache:v2:abc:status", "berichtensessiecache:v2:abc:list")

        every { keyCommands.keys("berichtensessiecache:*") } returns gevonden
        every { keyCommands.keys("bericht:*") } returns emptyList()
        every { keyCommands.del("berichtensessiecache:v2:abc:status", "berichtensessiecache:v2:abc:list") } returns 2

        assertEquals(2, service.laatSessiesVerlopen().gewisteKeys)

        verify { keyCommands.del("berichtensessiecache:v2:abc:status", "berichtensessiecache:v2:abc:list") }
    }

    @Test
    fun `verlopen wist ook de berichthashes buiten de sessie-prefix`() {
        val sessieKeys = listOf("berichtensessiecache:v4:abc:status", "berichtensessiecache:v4:verloop")
        val hashes = listOf("bericht:v4:00000000000000100000:b1", "bericht:v4:00000001823288444000:b2")
        val alles = (sessieKeys + hashes).toTypedArray()

        every { keyCommands.keys("berichtensessiecache:*") } returns sessieKeys
        every { keyCommands.keys("bericht:*") } returns hashes
        every { keyCommands.del(*alles) } returns 4

        assertEquals(4, service.laatSessiesVerlopen().gewisteKeys)

        verify { keyCommands.del(*alles) }
    }

    @Test
    fun `verlopen wist berichthashes ook als er geen sessie-keys meer zijn`() {
        every { keyCommands.keys("berichtensessiecache:*") } returns emptyList()
        every { keyCommands.keys("bericht:*") } returns listOf("bericht:v4:00000000000000100000:b1")
        every { keyCommands.del("bericht:v4:00000000000000100000:b1") } returns 1

        assertEquals(1, service.laatSessiesVerlopen().gewisteKeys)
    }

    @Test
    fun `verlopen bij lege keyspace geeft 0 en roept del niet aan`() {
        every { keyCommands.keys(any()) } returns emptyList()

        assertEquals(0, service.laatSessiesVerlopen().gewisteKeys)

        verify(exactly = 0) { keyCommands.del(*anyVararg<String>()) }
    }

    @ParameterizedTest(name = "{0} lopende sessie(s)")
    @ValueSource(ints = [0, 1, 3])
    fun `verlopen telt de lopende sessies vóór het wissen`(lopend: Int) {
        // Het aantal keys zegt niets over het aantal sessies: alle sessies van een ontvanger zijn
        // leden van één sleutel. Ná het wissen is er niets meer te tellen.
        every { beheer.telLopende() } returns lopend
        every { keyCommands.keys("berichtensessiecache:*") } returns listOf("berichtensessiecache:v4:abc:sessies")
        every { keyCommands.keys("bericht:*") } returns emptyList()
        every { keyCommands.del("berichtensessiecache:v4:abc:sessies") } returns 1

        assertEquals(VerlopenSessiecache(gewisteKeys = 1, beeindigdeSessies = lopend), service.laatSessiesVerlopen())

        verifyOrder {
            beheer.telLopende()
            keyCommands.del("berichtensessiecache:v4:abc:sessies")
        }
    }

    @Test
    fun `zo mogelijk wissen telt geen sessies`() {
        // Legen en herstellen melden alleen de gewiste keys; een telling die faalt, mag die knoppen
        // niet alsnog laten mislukken.
        every { keyCommands.keys(any()) } returns emptyList()

        service.laatSessiesVerlopenZoMogelijk()

        verify(exactly = 0) { beheer.telLopende() }
    }

    @Test
    fun `zo mogelijk wissen geeft het aantal door als het lukt`() {
        every { keyCommands.keys("berichtensessiecache:*") } returns listOf("berichtensessiecache:v3:abc:status")
        every { keyCommands.keys("bericht:*") } returns emptyList()
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
