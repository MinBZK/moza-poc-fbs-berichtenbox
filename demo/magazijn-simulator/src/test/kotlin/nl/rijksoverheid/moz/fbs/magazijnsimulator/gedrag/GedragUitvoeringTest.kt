package nl.rijksoverheid.moz.fbs.magazijnsimulator.gedrag

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

/**
 * Het loten zelf: hoe lang een aanroep duurt en of hij omvalt.
 *
 * Het punt van deze tests is herhaalbaarheid. Een demo die je oefent moet zich de volgende keer
 * hetzelfde gedragen, anders is hij niet te repeteren en is een bevinding niet na te spelen — maar
 * binnen één ronde moet een haperend magazijn wél afwisselen, anders hapert hij niet.
 */
class GedragUitvoeringTest {

    @Test
    fun `een normaal magazijn wacht steeds even lang`() {
        val uitvoering = GedragUitvoering()

        repeat(5) {
            assertEquals(
                Gedrag.NORMALE_LATENCY_MS.toLong(),
                uitvoering.vertragingMs(OIN, Gedrag.NORMAAL),
            )
        }
    }

    @Test
    fun `een traag magazijn spreidt rond de mediaan met een lange staart`() {
        val uitvoering = GedragUitvoering()
        val gedrag = Gedrag(GedragModus.TRAAG, latencyP50Ms = 1_000, latencyP95Ms = 4_000)

        val trekkingen = List(200) { uitvoering.vertragingMs(OIN, gedrag) }
        val boven = trekkingen.count { it > gedrag.latencyP50Ms }

        // Rond de mediaan hoort ongeveer de helft erboven te zitten; ruime marges, want dit toetst
        // de vórm van de verdeling en niet een exacte trekking.
        assertTrue(boven in 70..130, "verwacht ongeveer de helft boven de mediaan, was $boven van 200")
        assertTrue(trekkingen.any { it > gedrag.latencyP50Ms * 2 }, "verwacht uitschieters in de staart")
    }

    /** Onbegrensd is geen demonstratie maar een vastloper: de staart wordt afgekapt. */
    @Test
    fun `een uitschieter blijft binnen een veelvoud van het 95e percentiel`() {
        val uitvoering = GedragUitvoering()
        val gedrag = Gedrag(GedragModus.TRAAG, latencyP50Ms = 100, latencyP95Ms = 400)

        assertTrue(List(500) { uitvoering.vertragingMs(OIN, gedrag) }.all { it <= 400 * 3 })
    }

    @Test
    fun `dezelfde OIN levert twee keer dezelfde reeks op`() {
        val gedrag = Gedrag(GedragModus.TRAAG, latencyP50Ms = 1_000, latencyP95Ms = 4_000)

        val eerste = GedragUitvoering().let { u -> List(20) { u.vertragingMs(OIN, gedrag) } }
        val tweede = GedragUitvoering().let { u -> List(20) { u.vertragingMs(OIN, gedrag) } }

        assertEquals(eerste, tweede)
    }

    @Test
    fun `twee magazijnen loten onafhankelijk van elkaar`() {
        val uitvoering = GedragUitvoering()
        val gedrag = Gedrag(GedragModus.TRAAG, latencyP50Ms = 1_000, latencyP95Ms = 4_000)

        val eerste = List(20) { uitvoering.vertragingMs(OIN, gedrag) }
        val tweede = List(20) { uitvoering.vertragingMs(ANDER_OIN, gedrag) }

        assertTrue(eerste != tweede, "twee magazijnen horen niet dezelfde reeks te draaien")
    }

    /**
     * De OIN's van de gesimuleerde magazijnen verschillen alleen in hun laatste vier cijfers. Wordt
     * de startwaarde daar rechtstreeks uit genomen, dan liggen de eerste trekkingen van alle
     * magazijnen vlak bij elkaar: haperende magazijnen vallen dan op hun eerste verzoek tegelijk om
     * en trage magazijnen antwoorden bijna allemaal sneller dan hun mediaan. De eerste ophaalronde
     * van een demo ziet er dan systematisch anders uit dan de volgende, en dat is precies het soort
     * verschil dat een demo ongeloofwaardig maakt.
     */
    @Test
    fun `de eerste trekking is over de hele set gespreid en niet geklonterd`() {
        val uitvoering = GedragUitvoering()
        val gedrag = Gedrag(GedragModus.HAPERT, foutkans = 0.5)

        val eersteUitkomsten = (1..AANTAL_GESIMULEERD).map {
            uitvoering.valtOm("0000000900000000%04d".format(it), gedrag)
        }
        val omgevallen = eersteUitkomsten.count { it }

        assertTrue(
            omgevallen in AANTAL_GESIMULEERD / 4..AANTAL_GESIMULEERD * 3 / 4,
            "verwacht ongeveer de helft op het eerste verzoek, was $omgevallen van $AANTAL_GESIMULEERD",
        )
    }

    @Test
    fun `het terugzetten laat de reeks opnieuw beginnen`() {
        val uitvoering = GedragUitvoering()
        val gedrag = Gedrag(GedragModus.TRAAG, latencyP50Ms = 1_000, latencyP95Ms = 4_000)

        val eerste = List(10) { uitvoering.vertragingMs(OIN, gedrag) }

        uitvoering.herstel()

        assertEquals(eerste, List(10) { uitvoering.vertragingMs(OIN, gedrag) })
    }

    @Test
    fun `een kapot magazijn valt altijd om en een normaal nooit`() {
        val uitvoering = GedragUitvoering()

        assertTrue(List(20) { uitvoering.valtOm(OIN, Gedrag.standaardVoor(GedragModus.STUK)) }.all { it })
        assertTrue(List(20) { uitvoering.valtOm(OIN, Gedrag.standaardVoor(GedragModus.WEIGERT)) }.all { it })
        assertTrue(List(20) { uitvoering.valtOm(OIN, Gedrag.NORMAAL) }.none { it })
    }

    /** Haperen is per definitie afwisselen: altijd goed of altijd fout is geen hapering. */
    @Test
    fun `een haperend magazijn valt soms om en soms niet`() {
        val uitvoering = GedragUitvoering()
        val gedrag = Gedrag(GedragModus.HAPERT, foutkans = 0.5)

        val uitkomsten = List(100) { uitvoering.valtOm(OIN, gedrag) }

        assertTrue(uitkomsten.any { it } && uitkomsten.any { !it }, "verwacht een mengeling")
        assertTrue(uitkomsten.count { it } in 30..70, "verwacht rond de helft, was ${uitkomsten.count { it }}")
    }

    @Test
    fun `foutkans een betekent altijd omvallen`() {
        val uitvoering = GedragUitvoering()

        assertTrue(List(50) { uitvoering.valtOm(OIN, Gedrag(GedragModus.HAPERT, foutkans = 1.0)) }.all { it })
    }

    /**
     * De andere grens bestaat niet meer als gedrag: een haperend magazijn met foutkans nul hapert
     * nooit, terwijl het overzicht "hapert" blijft melden. Dat is geen instelling maar een leugen,
     * en het type laat hem daarom niet meer maken.
     */
    @Test
    fun `haperen zonder foutkans is geen geldig gedrag`() {
        assertThrows<IllegalArgumentException> { Gedrag(GedragModus.HAPERT, foutkans = 0.0) }
    }

    private companion object {
        const val OIN = "00000009000000000005"
        const val ANDER_OIN = "00000009000000000010"
        const val AANTAL_GESIMULEERD = 98
    }
}
