package nl.rijksoverheid.moz.fbs.berichtenuitvraag.uitvraag

import nl.rijksoverheid.moz.fbs.berichtensessiecache.berichten.MagazijnBevraging
import nl.rijksoverheid.moz.fbs.berichtensessiecache.berichten.MagazijnBevragingGeslaagd
import nl.rijksoverheid.moz.fbs.berichtensessiecache.berichten.MagazijnBevragingGestart
import nl.rijksoverheid.moz.fbs.berichtensessiecache.berichten.MagazijnBevragingMislukt
import nl.rijksoverheid.moz.fbs.berichtensessiecache.berichten.MagazijnEvent
import nl.rijksoverheid.moz.fbs.berichtensessiecache.berichten.MagazijnFoutStatus
import nl.rijksoverheid.moz.fbs.berichtensessiecache.berichten.OphalenFout
import nl.rijksoverheid.moz.fbs.berichtensessiecache.berichten.OphalenGereed
import nl.rijksoverheid.moz.fbs.berichtensessiecache.berichten.OphalenMisluktNaBevraging
import nl.rijksoverheid.moz.fbs.berichtensessiecache.berichten.OphalenMisluktVoorBevraging
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource
import org.junit.jupiter.params.provider.ValueSource
import kotlin.reflect.KClass

/**
 * Pint per event-type of een ophaalronde als mislukt in het verwerkingenlogboek komt (AVG art.
 * 30-audittrail): een refactor van de SSE-resource mag een mislukte of deels mislukte ophaling niet
 * stil als geslaagd laten staan.
 */
class OphaalMislukkingTest {

    companion object {
        /** Alle soorten die geen uitkomst bepalen — alleen het slotevent bepaalt of de ronde mislukt is. */
        @JvmStatic
        fun tussentijdseEvents(): List<MagazijnEvent> = listOf(
            MagazijnBevragingGestart(magazijnId = "00000001001234567890", naam = "Magazijn A"),
            MagazijnBevragingGestart(magazijnId = "00000001001234567890", naam = "Magazijn A"),
            MagazijnBevragingGeslaagd(magazijnId = "00000001001234567890", naam = "Magazijn A", aantalBerichten = 3),
            MagazijnBevragingMislukt(
                magazijnId = "00000001001234567890",
                naam = "Magazijn A",
                fout = MagazijnFoutStatus.FOUT,
                foutmelding = "Magazijn tijdelijk niet bereikbaar",
            ),
            MagazijnBevragingMislukt(
                magazijnId = "00000001001234567890",
                naam = "Magazijn A",
                fout = MagazijnFoutStatus.TIMEOUT,
                foutmelding = "Magazijn reageerde niet binnen de timeout",
            ),
        )

        /** Elk fout-eindbericht is een mislukking, ongeacht of het ophalen of het opslaan strandde. */
        @JvmStatic
        fun fouteindEvents(): List<MagazijnEvent> = listOf(
            OphalenMisluktVoorBevraging(foutmelding = "Interne fout bij opslaan resultaten (ref: abc)", referentie = "abc"),
            OphalenMisluktNaBevraging(
                foutmelding = "Resultaten konden niet worden opgeslagen (ref: abc)",
                geslaagd = 1,
                mislukt = 1,
                nietOpgehaald = 0,
                totaalMagazijnen = 2,
                referentie = "abc",
            ),
        )
    }

    @Test
    fun `volledig geslaagde ophaling is geen mislukking`() {
        val gereed = OphalenGereed(totaalBerichten = 2, geslaagd = 2, mislukt = 0, nietOpgehaald = 0, totaalMagazijnen = 2)

        assertNull(mislukkingVan(gereed))
    }

    @Test
    fun `ophaling zonder magazijnen is geen mislukking`() {
        val leeg = OphalenGereed(totaalBerichten = 0, geslaagd = 0, mislukt = 0, nietOpgehaald = 0, totaalMagazijnen = 0)

        assertNull(mislukkingVan(leeg))
    }

    @Test
    fun `partial failure (mislukt groter dan 0) is een mislukking met de aantallen`() {
        val deelsMislukt = OphalenGereed(totaalBerichten = 1, geslaagd = 1, mislukt = 1, nietOpgehaald = 0, totaalMagazijnen = 2)

        assertEquals("1 van 2 organisaties mislukt", mislukkingVan(deelsMislukt)?.message)
    }

    @Test
    fun `volledig mislukte ophaling is een mislukking met de aantallen`() {
        val allesMislukt = OphalenGereed(totaalBerichten = 0, geslaagd = 0, mislukt = 2, nietOpgehaald = 0, totaalMagazijnen = 2)

        assertEquals("2 van 2 organisaties mislukt", mislukkingVan(allesMislukt)?.message)
    }

    @ParameterizedTest
    @ValueSource(ints = [1, 2])
    fun `niet-opgehaalde organisaties zijn geen mislukking zolang er niets misging`(nietOpgehaald: Int) {
        val gedeeltelijk = OphalenGereed(
            totaalBerichten = 3,
            geslaagd = 2,
            mislukt = 0,
            nietOpgehaald = nietOpgehaald,
            totaalMagazijnen = 2 + nietOpgehaald,
        )

        assertNull(mislukkingVan(gedeeltelijk))
    }

    /** Een echte mislukking blijft een mislukking, ook als er daarnaast overgeslagen organisaties zijn. */
    @Test
    fun `mislukking naast niet-opgehaalde organisaties is een mislukking`() {
        val gemengd = OphalenGereed(totaalBerichten = 1, geslaagd = 1, mislukt = 1, nietOpgehaald = 1, totaalMagazijnen = 3)

        assertNotNull(mislukkingVan(gemengd))
    }

    @ParameterizedTest
    @MethodSource("fouteindEvents")
    fun `elk fout-eindbericht is een mislukking met alleen de soort`(event: MagazijnEvent) {
        val mislukking = mislukkingVan(event)

        assertEquals(event.javaClass.simpleName, mislukking?.message)

        // De samenvatting gaat het logboek in en bij een inzageverzoek naar buiten; de foutmelding
        // en referentie uit de keten horen daar niet in.
        val samenvatting = mislukking?.message.orEmpty()

        assertFalse("abc" in samenvatting || "opslaan" in samenvatting, "de samenvatting draagt de foutmelding mee: $samenvatting")
    }

    @ParameterizedTest
    @MethodSource("tussentijdseEvents")
    fun `tussentijdse events zijn geen mislukking`(event: MagazijnEvent) {
        assertNull(mislukkingVan(event))
    }

    /**
     * De `when` in [mislukkingVan] vangt alle bevragings-events in één tak af, dus een
     * nieuw soort daaronder compileert zonder dat iemand een keuze maakt. Deze guard dwingt
     * af dat elk nieuw bevragings-type hierboven expliciet als "bepaalt de uitkomst niet"
     * wordt opgevoerd.
     */
    @Test
    fun `elk soort bevragings-event staat in de tussentijdse lijst`() {
        val gedekt = tussentijdseEvents().map { it.javaClass }.toSet()

        assertEquals(bladtypen(MagazijnBevraging::class), gedekt, "Ongedekt bevragings-event")
    }

    /** Dezelfde guard voor de andere samenvattende tak: `is OphalenFout` vangt óók meerdere typen. */
    @Test
    fun `elk soort fout-eindbericht staat in de foutlijst`() {
        val gedekt = fouteindEvents().map { it.javaClass }.toSet()

        assertEquals(bladtypen(OphalenFout::class), gedekt, "Ongedekt fout-eindbericht")
    }
}

private fun bladtypen(type: KClass<*>): Set<Class<*>> =
    type.sealedSubclasses.flatMap { sub -> if (sub.isSealed) bladtypen(sub) else setOf(sub.java) }.toSet()
