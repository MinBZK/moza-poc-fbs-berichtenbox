package nl.rijksoverheid.moz.fbs.democonsole.dataset

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import nl.rijksoverheid.moz.fbs.democonsole.generator.AanleverOpdracht
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * De beginsituatie van een demo. De variatie die de dataset zelf niet draagt — bijlagen en de
 * lees-mix — komt uit [Basisdataset], en die mix hoort in elke berichtenbak te vallen.
 *
 * Dat ging eerder mis: de regel telde over de vlakke lijst, basis.json staat in een cyclus van vier
 * bakken, en met "elk vierde op gelezen" stond precies één bak volledig op gelezen en de andere drie
 * helemaal niet. Zichtbaar pas in de berichtenbox, tijdens een demo.
 */
class BasisdatasetTest {

    private val opdrachten = Basisdataset(ObjectMapper().registerKotlinModule()).laad()

    private fun perBak(): Map<String, List<AanleverOpdracht>> =
        opdrachten.groupBy { "${it.magazijnOin}/${it.verzoek.ontvanger.type}:${it.verzoek.ontvanger.waarde}" }

    @Test
    fun `de dataset vult vier berichtenbakken`() {
        val bakken = perBak()

        assertEquals(4, bakken.size, "verwachtte vier bakken, kreeg ${bakken.keys}")
        bakken.forEach { (bak, inhoud) -> assertTrue(inhoud.size >= 4, "bak $bak is te klein om te variëren: ${inhoud.size}") }
    }

    @Test
    fun `elke bak heeft zowel gelezen als ongelezen berichten`() {
        perBak().forEach { (bak, inhoud) ->
            val gelezen = inhoud.count { it.gelezen }

            assertTrue(gelezen > 0, "bak $bak heeft geen enkel gelezen bericht")
            assertTrue(gelezen < inhoud.size, "bak $bak staat volledig op gelezen ($gelezen van ${inhoud.size})")
        }
    }

    @Test
    fun `elke bak heeft berichten met en zonder bijlage`() {
        perBak().forEach { (bak, inhoud) ->
            val metBijlage = inhoud.count { it.verzoek.bijlagen?.isNotEmpty() == true }

            assertTrue(metBijlage > 0, "bak $bak heeft geen enkele bijlage voor de download-demo")
            assertTrue(metBijlage < inhoud.size, "bak $bak heeft aan elk bericht een bijlage")
        }
    }

    /**
     * De volgorde van basis.json mag de mix niet bepalen. Gegroepeerd per bak aangeboden — wat een
     * redactie van de dataset zomaar kan opleveren — hoort er hetzelfde uit te komen.
     */
    @Test
    fun `de mix hangt niet aan de volgorde van de dataset`() {
        val gegroepeerd = opdrachten.sortedBy { "${it.magazijnOin}${it.verzoek.ontvanger.waarde}" }
        val perBakGegroepeerd = gegroepeerd.groupBy { "${it.magazijnOin}/${it.verzoek.ontvanger.waarde}" }

        perBakGegroepeerd.forEach { (bak, inhoud) ->
            val gelezen = inhoud.count { it.gelezen }

            assertTrue(gelezen in 1 until inhoud.size, "bak $bak heeft geen mix: $gelezen van ${inhoud.size}")
        }
    }
}
