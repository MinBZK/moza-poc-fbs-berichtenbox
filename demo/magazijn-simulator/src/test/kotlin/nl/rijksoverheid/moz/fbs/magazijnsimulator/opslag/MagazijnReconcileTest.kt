package nl.rijksoverheid.moz.fbs.magazijnsimulator.opslag

import io.quarkus.test.junit.QuarkusTest
import jakarta.inject.Inject
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import nl.rijksoverheid.moz.fbs.magazijnsimulator.gedrag.Gedrag
import nl.rijksoverheid.moz.fbs.magazijnsimulator.magazijn.MagazijnInstelling
import nl.rijksoverheid.moz.fbs.magazijnsimulator.gedrag.GedragModus
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
        reconcile(EEN to "Demo-magazijn 1", TWEE to "Demo-magazijn 2", DRIE to "Demo-magazijn 3")
    }

    @Test
    fun `twee keer dezelfde configuratie levert dezelfde rijen op`() {
        val eerste = reconcile(EEN to "Demo-magazijn 1")
        val tweede = reconcile(EEN to "Demo-magazijn 1")

        assertEquals(eerste[EEN]?.dbId, tweede[EEN]?.dbId, "de database-id hoort niet per start te verspringen")
    }

    @Test
    fun `een nieuw magazijn wordt aangemaakt en krijgt een eigen id`() {
        val rijen = reconcile(EEN to "Demo-magazijn 1", EXTRA to "Demo-magazijn 42")

        assertNotNull(rijen[EXTRA])
        assertTrue(rijen[EXTRA]?.dbId != rijen[EEN]?.dbId, "elk magazijn hoort zijn eigen rij te hebben")
    }

    @Test
    fun `een gewijzigde naam wordt bijgewerkt zonder een tweede rij te maken`() {
        val voor = reconcile(EXTRA to "Oude naam")
        val na = reconcile(EXTRA to "Nieuwe naam")

        assertEquals(voor[EXTRA]?.dbId, na[EXTRA]?.dbId, "dezelfde OIN hoort dezelfde rij te houden")
        assertEquals("Nieuwe naam", naamVan(EXTRA))
    }

    /**
     * De configuratie is de bron, ook voor het gedrag: een storing die tijdens een demo is aangezet
     * hoort na een herstart weer weg te zijn. Anders is "terug naar de begintoestand" een halve
     * waarheid.
     */
    @Test
    fun `een bijgesteld gedrag wordt bij het opnieuw inlezen teruggezet`() {
        reconcile(EXTRA to "Demo-magazijn 42")
        repository.zetGedrag(EXTRA, Gedrag.standaardVoor(GedragModus.STUK))

        val na = reconcile(EXTRA to "Demo-magazijn 42")

        assertEquals(GedragModus.NORMAAL, na[EXTRA]?.gedrag?.modus)
    }

    /**
     * Een magazijn dat uit de configuratie verdwijnt, blijft in de database staan: zijn berichten
     * hangen eraan met een RESTRICT-FK. Het valt wel uit het antwoord, want het pad-filter laat het
     * toch niet meer door — anders zou de applicatie een magazijn aanbieden dat niemand meer kent.
     */
    @Test
    fun `een verdwenen magazijn blijft in de database maar valt uit de set`() {
        reconcile(EEN to "Demo-magazijn 1", EXTRA to "Demo-magazijn 42")

        val na = reconcile(EEN to "Demo-magazijn 1")

        assertEquals(setOf(EEN), na.keys)
        assertNotNull(naamVan(EXTRA), "de rij zelf hoort te blijven staan")
    }

    /**
     * Een magazijn dat uit de configuratie is gehaald, hoort ook uit de live set te verdwijnen. De
     * rij blijft staan — daar hangen berichten aan — maar het pad-filter mag hem niet meer
     * doorlaten, want het register van de uitvraag kent hem niet meer.
     */
    @Test
    fun `een verdwenen magazijn valt uit de set die het pad-filter raadpleegt`() {
        reconcile(EEN to "Een", EXTRA to "Extra")

        assertNotNull(repository.find("oin", EXTRA).firstResult(), "de rij hoort er te zijn")

        val na = reconcile(EEN to "Een")

        assertEquals(setOf(EEN), na.keys, "alleen wat geconfigureerd is komt terug")
        assertNotNull(repository.find("oin", EXTRA).firstResult(), "de rij zelf blijft staan")
    }

    private fun reconcile(vararg entries: Pair<String, String>): Map<String, MagazijnRij> =
        repository
            .brengInOvereenstemming(
                entries.toMap().mapValues { (_, naam) -> MagazijnInstelling(naam, Gedrag.NORMAAL) },
            )
            .associateBy { it.oin }

    private fun naamVan(oin: String): String? =
        repository.find("oin", oin).firstResult()?.naam

    private companion object {
        const val EEN = "00000009000000000001"
        const val TWEE = "00000009000000000002"
        const val DRIE = "00000009000000000003"

        /** Een OIN die niet in de configuratie staat, zodat deze tests de andere niet in de weg zitten. */
        const val EXTRA = "00000009000000000042"
    }
}
