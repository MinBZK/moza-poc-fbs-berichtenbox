package nl.rijksoverheid.moz.fbs.magazijnsimulator.opslag

import io.quarkus.test.junit.QuarkusTest
import jakarta.inject.Inject
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Het in overeenstemming brengen van de magazijn-tabel met de configuratie.
 *
 * Bij het opstarten draait alleen het aanmaak-pad, en dat is precies waarom deze tests bestaan: het
 * generatiescript maakt de set opnieuw, dus een tweede start met een gewijzigde naam of een
 * verdwenen OIN is de normale gang van zaken en niet een randgeval. Loopt daar iets mis, dan valt
 * dat pas op als een demo de verkeerde magazijnnaam toont of een magazijn onbereikbaar blijkt.
 */
@QuarkusTest
class MagazijnReconcileTest {

    @Inject
    lateinit var repository: MagazijnRepository

    /**
     * De set uit de configuratie terugzetten, zodat de andere tests hun magazijnen terugvinden. De
     * rij van [EXTRA] blijft staan en dat mag: hij komt in geen enkele configuratie voor, dus het
     * pad-filter laat hem niet door.
     */
    @AfterEach
    fun herstel() {
        repository.brengInOvereenstemming(mapOf(EEN to "Demo-magazijn 1", TWEE to "Demo-magazijn 2"))
    }

    @Test
    fun `twee keer dezelfde configuratie levert dezelfde rijen op`() {
        val eerste = repository.brengInOvereenstemming(mapOf(EEN to "Demo-magazijn 1"))
        val tweede = repository.brengInOvereenstemming(mapOf(EEN to "Demo-magazijn 1"))

        assertEquals(eerste[EEN], tweede[EEN], "de database-id hoort niet per start te verspringen")
    }

    @Test
    fun `een nieuw magazijn wordt aangemaakt en krijgt een eigen id`() {
        val rijen = repository.brengInOvereenstemming(
            mapOf(EEN to "Demo-magazijn 1", EXTRA to "Demo-magazijn 42"),
        )

        assertNotNull(rijen[EXTRA])
        assertTrue(rijen[EXTRA] != rijen[EEN], "elk magazijn hoort zijn eigen rij te hebben")
    }

    @Test
    fun `een gewijzigde naam wordt bijgewerkt zonder een tweede rij te maken`() {
        val voor = repository.brengInOvereenstemming(mapOf(EXTRA to "Oude naam"))
        val na = repository.brengInOvereenstemming(mapOf(EXTRA to "Nieuwe naam"))

        assertEquals(voor[EXTRA], na[EXTRA], "dezelfde OIN hoort dezelfde rij te houden")
        assertEquals("Nieuwe naam", naamVan(EXTRA))
    }

    /**
     * Een magazijn dat uit de configuratie verdwijnt, blijft in de database staan: zijn berichten
     * hangen eraan met een RESTRICT-FK. Het valt wel uit het antwoord, want het pad-filter laat het
     * toch niet meer door — anders zou de applicatie een magazijn aanbieden dat niemand meer kent.
     */
    @Test
    fun `een verdwenen magazijn blijft in de database maar valt uit de set`() {
        repository.brengInOvereenstemming(mapOf(EEN to "Demo-magazijn 1", EXTRA to "Demo-magazijn 42"))

        val na = repository.brengInOvereenstemming(mapOf(EEN to "Demo-magazijn 1"))

        assertEquals(setOf(EEN), na.keys)
        assertNotNull(naamVan(EXTRA), "de rij zelf hoort te blijven staan")
    }

    private fun naamVan(oin: String): String? =
        repository.find("oin", oin).firstResult()?.naam

    private companion object {
        const val EEN = "00000009000000000001"
        const val TWEE = "00000009000000000002"

        /** Een OIN die niet in de configuratie staat, zodat deze tests de andere niet in de weg zitten. */
        const val EXTRA = "00000009000000000042"
    }
}
