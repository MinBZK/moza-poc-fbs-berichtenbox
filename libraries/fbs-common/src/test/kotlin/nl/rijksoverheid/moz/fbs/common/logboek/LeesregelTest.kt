package nl.rijksoverheid.moz.fbs.common.logboek

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import java.time.Instant

class LeesregelTest {

    @Test
    fun `geen rijen geeft geen verwerkingen`() {
        assertEquals(emptyList<Verwerking>(), Leesregel.pasToe(emptyList()))
    }

    @Test
    fun `een logregel op UNSET zonder ERROR-child is geslaagd`() {
        val verwerking = Leesregel.pasToe(listOf(rij("a"))).single()

        assertEquals(Uitkomst.GESLAAGD, verwerking.uitkomst)
        assertNull(verwerking.foutcategorie)
    }

    @Test
    fun `een ERROR-child maakt de logregel mislukt en is zelf geen verwerking`() {
        val logregel = rij("a", doel = "default", berichtId = "b-1", poging = 2)
        val child = rij(
            "a-fout", parentSpanId = "a", status = "ERROR",
            uitkomst = "mislukt", foutMessage = "NetwerkFout",
        )

        val verwerking = Leesregel.pasToe(listOf(logregel, child)).single()

        assertEquals(Uitkomst.MISLUKT, verwerking.uitkomst)
        assertEquals("NetwerkFout", verwerking.foutcategorie)
        assertEquals("default", verwerking.verstrektAan)
        assertEquals("b-1", verwerking.berichtId)
        assertEquals(2L, verwerking.poging)
    }

    @Test
    fun `een logregel op ERROR toont nooit zijn ruwe exception-message`() {
        val verwerking = Leesregel.pasToe(
            listOf(rij("a", status = "ERROR", foutType = "nl.Fout", foutMessage = "Failing row contains 999900109")),
        ).single()

        assertEquals(Uitkomst.MISLUKT, verwerking.uitkomst)
        assertEquals("nl.Fout", verwerking.foutcategorie)
    }

    @Test
    fun `een publicatiefout gaat voor het exception-type`() {
        val verwerking = Leesregel.pasToe(
            listOf(rij("a", status = "ERROR", publicatieFout = "bericht-niet-gevonden", foutType = "nl.Fout")),
        ).single()

        assertEquals("bericht-niet-gevonden", verwerking.foutcategorie)
    }

    @Test
    fun `een BERICHT_ID_ONLY-regel draagt het bericht-id uit het subject`() {
        val verwerking = Leesregel.pasToe(
            listOf(rij("a", status = "ERROR", subjectType = "BERICHT_ID_ONLY", subjectId = "b-9")),
        ).single()

        assertEquals("b-9", verwerking.berichtId)
    }

    @Test
    fun `een ERROR-child zonder logregel in het resultaat blijft zichtbaar als mislukt`() {
        val wees = rij(
            "x", parentSpanId = "elders", status = "ERROR",
            uitkomst = "mislukt", foutType = "nl.Samenvatting",
        )

        val verwerking = Leesregel.pasToe(listOf(wees)).single()

        assertEquals(Uitkomst.MISLUKT, verwerking.uitkomst)
        assertEquals("nl.Samenvatting", verwerking.foutcategorie)
    }

    @Test
    fun `meerdere verwerkingen staan nieuwste eerst, en een child koppelt alleen aan zijn eigen logregel`() {
        val rijen = listOf(
            rij("oud", start = 1),
            rij("nieuw", start = 3),
            rij("midden", start = 2),
            rij("fout", parentSpanId = "midden", status = "ERROR", uitkomst = "mislukt", start = 2),
        )

        val verwerkingen = Leesregel.pasToe(rijen)

        assertEquals(listOf(3L, 2L, 1L), verwerkingen.map { it.tijdstip.toEpochMilli() })
        assertEquals(
            listOf(Uitkomst.GESLAAGD, Uitkomst.MISLUKT, Uitkomst.GESLAAGD),
            verwerkingen.map { it.uitkomst },
        )
    }

    @Suppress("LongParameterList")
    private fun rij(
        spanId: String,
        parentSpanId: String? = null,
        status: String = "UNSET",
        uitkomst: String? = null,
        foutType: String? = null,
        foutMessage: String? = null,
        publicatieFout: String? = null,
        subjectType: String = "BSN",
        subjectId: String = "999900109",
        doel: String? = null,
        berichtId: String? = null,
        poging: Long? = null,
        start: Long = 0,
    ) = LogboekRij(
        traceId = "t",
        spanId = spanId,
        parentSpanId = parentSpanId,
        status = status,
        naam = "handeling",
        start = Instant.ofEpochMilli(start),
        activiteit = "urn:activiteit",
        subjectType = subjectType,
        subjectId = subjectId,
        uitkomstAttribuut = uitkomst,
        berichtId = berichtId,
        doel = doel,
        poging = poging,
        publicatieFout = publicatieFout,
        foutType = foutType,
        foutMessage = foutMessage,
    )
}
