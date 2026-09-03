package nl.rijksoverheid.moz.fbs.democonsole.dataset

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import nl.rijksoverheid.moz.fbs.democonsole.generator.AanleverOpdracht
import nl.rijksoverheid.moz.fbs.democonsole.generator.AanleverVerzoek
import nl.rijksoverheid.moz.fbs.democonsole.generator.OntvangerDto
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import kotlin.random.Random

/**
 * De beginsituatie van een demo. De variatie die de dataset zelf niet draagt — bijlagen en de
 * lees-mix — komt uit [Basisdataset], en die mix hoort in elke berichtenbak te vallen.
 *
 * Dat ging eerder mis: de regel telde over de vlakke lijst terwijl basis.json de bakken in een vast
 * patroon herhaalt, en met "elk vierde op gelezen" stond één bak volledig op gelezen en een andere
 * helemaal niet. Zichtbaar pas in de berichtenbox, tijdens een demo.
 */
class BasisdatasetTest {

    private val dataset = Basisdataset(ObjectMapper().registerKotlinModule())

    private val opdrachten = dataset.laad()

    private fun perBak(): Map<String, List<AanleverOpdracht>> =
        opdrachten.groupBy { "${it.magazijnOin}/${it.verzoek.ontvanger.type}:${it.verzoek.ontvanger.waarde}" }

    /**
     * Welke bakken er horen te zijn, bewaakt `DemoDatasetConsistentieTest` tegen de persona-lijst;
     * hier gaat het om hun omvang. Vier is een redactionele ondergrens — daaronder leest een map in
     * de berichtenbox niet als lijst — en niet de grens van de mix hieronder: die haalt het al bij
     * twee berichten, dus zij bewaakt deze eis niet.
     */
    @Test
    fun `elke berichtenbak is groot genoeg om te variëren`() {
        val bakken = perBak()

        assertTrue(bakken.isNotEmpty(), "zonder bakken toetst deze test niets")
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
     * De volgorde van basis.json mag de mix per bak niet bepalen: een redactie die de dataset
     * groepeert of herschikt hoort dezelfde beginsituatie op te leveren. Daarvoor moet de
     * toekenning zélf opnieuw draaien op een herschikte invoer — de uitkomst van [Basisdataset.laad]
     * hergroeperen bewijst niets, want die vlaggen staan dan al vast.
     *
     * Wélk bericht de vlag krijgt verschuift wel mee met de volgorde; dat is geen belofte van de
     * beginsituatie, de aantallen per bak wel.
     */
    @Test
    fun `de mix per bak hangt niet aan de volgorde van de dataset`() {
        val ruw = dataset.ruw()
        val geschud = ruw.shuffled(Random(7))

        assertTrue(geschud != ruw, "de schudbeurt leverde dezelfde volgorde op; dan toetst deze test niets")
        assertEquals(telPerBak(dataset.metVariatie(ruw)), telPerBak(dataset.metVariatie(geschud)))
    }

    /**
     * De mix-regels op elke bakgrootte die een redactie kan opleveren, los van wat basis.json nu
     * toevallig bevat. Vanaf twee berichten hoort een bak zowel gelezen als ongelezen te bevatten,
     * en zowel met als zonder bijlage; daaronder kan dat niet, en dan hoort de toekenning niet
     * alsnog iets te verzinnen.
     */
    @ParameterizedTest
    @ValueSource(ints = [0, 1, 2, 3, 4, 5, 10])
    fun `vanaf twee berichten valt de mix in elke bakgrootte`(aantal: Int) {
        val uitkomst = dataset.metVariatie((1..aantal).map { opdrachtVoor("Bericht $it") })
        val gelezen = uitkomst.count { it.gelezen }
        val metBijlage = uitkomst.count { it.verzoek.bijlagen?.isNotEmpty() == true }

        assertEquals(aantal, uitkomst.size, "de toekenning hoort geen bericht toe te voegen of weg te laten")

        if (aantal < 2) {
            assertEquals(0, gelezen, "onder de twee berichten valt er niets te mengen")

            return
        }

        assertTrue(gelezen in 1 until aantal, "bak van $aantal heeft geen lees-mix: $gelezen gelezen")
        assertTrue(metBijlage in 1 until aantal, "bak van $aantal heeft geen bijlage-mix: $metBijlage met bijlage")
    }

    /** Per bak het paar (gelezen, met bijlage) — wat de beginsituatie belooft, los van welk bericht. */
    private fun telPerBak(opdrachten: List<AanleverOpdracht>): Map<String, Pair<Int, Int>> =
        opdrachten.groupBy { "${it.magazijnOin}/${it.verzoek.ontvanger.type}:${it.verzoek.ontvanger.waarde}" }
            .mapValues { (_, inhoud) ->
                inhoud.count { it.gelezen } to inhoud.count { it.verzoek.bijlagen?.isNotEmpty() == true }
            }

    /** Eén bericht in dezelfde bak; alleen het onderwerp verschilt, want daarop rust de mix niet. */
    private fun opdrachtVoor(onderwerp: String): AanleverOpdracht =
        AanleverOpdracht(
            magazijnOin = MAGAZIJN,
            verzoek = AanleverVerzoek(
                afzender = MAGAZIJN,
                ontvanger = OntvangerDto(type = "KVK", waarde = "90000011"),
                onderwerp = onderwerp,
                inhoud = "Inhoud van $onderwerp",
                publicatietijdstip = "2026-01-03T08:15:00Z",
            ),
        )

    private companion object {
        const val MAGAZIJN = "00000000000000100000"
    }
}
