package nl.rijksoverheid.moz.fbs.democonsole.aanlever

import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.Runs
import jakarta.ws.rs.ProcessingException
import jakarta.ws.rs.core.Response
import nl.rijksoverheid.moz.fbs.democonsole.generator.AanleverOpdracht
import nl.rijksoverheid.moz.fbs.democonsole.generator.AanleverVerzoek
import nl.rijksoverheid.moz.fbs.democonsole.generator.OntvangerDto
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

/**
 * Wat de bediener van het paneel te zien krijgt als een aanlevering niet aankomt. De clients zijn
 * dubbels: het magazijn draait hier niet, en waar het om gaat is dat elke faalmodus zijn eigen
 * reden oplevert in plaats van alleen een teller.
 */
class AanleverServiceTest {

    private val clients = mockk<MagazijnClients>()
    private val client = mockk<MagazijnAanleverClient>()
    private val service = AanleverService(clients)

    /** Een respons zoals de REST-client hem teruggeeft; `use` sluit hem, dus `close` hoort erbij. */
    private fun respons(status: Int, berichtId: String? = null) = mockk<Response>(relaxed = false).also {
        every { it.status } returns status
        every { it.close() } just Runs

        if (berichtId != null) every { it.readEntity(AanleverRespons::class.java) } returns AanleverRespons(berichtId)
    }

    private fun opdracht(magazijnOin: String = RVO, gelezen: Boolean = false) = AanleverOpdracht(
        magazijnOin,
        AanleverVerzoek(
            afzender = magazijnOin,
            ontvanger = OntvangerDto("BSN", BSN),
            onderwerp = "Demo",
            inhoud = "Demo-inhoud",
            publicatietijdstip = "2026-09-04T10:00:00Z",
        ),
        gelezen,
    )

    private fun magazijnAntwoordt(status: Int, berichtId: String? = null) {
        every { clients[RVO] } returns client
        every { client.leverAan(any()) } returns respons(status, berichtId)
    }

    @Test
    fun `een geslaagde ronde draagt geen reden`() {
        magazijnAntwoordt(201, "b-1")

        val resultaat = service.leverAan(listOf(opdracht(), opdracht()))

        assertEquals(AanleverResultaat(2, 2, 0, 0), resultaat)
        assertNull(resultaat.letOp, "een ronde zonder mislukkingen hoort geen let-op-regel te tonen")
    }

    @Test
    fun `een lege ronde draagt geen reden`() {
        assertNull(service.leverAan(emptyList()).letOp)
    }

    @Test
    fun `een weigering door de organisatie is als weigering te lezen`() {
        magazijnAntwoordt(403)

        val resultaat = service.leverAan(listOf(opdracht()))

        assertEquals(1, resultaat.mislukt)
        assertEquals("Reden: ${Faalreden.vanStatus(RVO, 403)}.", resultaat.letOp)
    }

    @Test
    fun `een onbereikbaar magazijn leest anders dan een weigering`() {
        every { clients[RVO] } returns client
        every { client.leverAan(any()) } throws ProcessingException("Connection refused")

        val onbereikbaar = service.leverAan(listOf(opdracht()))

        magazijnAntwoordt(403)

        val geweigerd = service.leverAan(listOf(opdracht()))

        assertEquals(1, onbereikbaar.mislukt)
        assertNotNull(onbereikbaar.letOp)
        assertNotEquals(onbereikbaar.letOp, geweigerd.letOp)
    }

    @Test
    fun `een ontbrekend magazijn-adres wijst naar de inrichting en niet naar de keten`() {
        every { clients[ONBEKEND] } returns null

        val resultaat = service.leverAan(listOf(opdracht(ONBEKEND)))

        assertEquals(AanleverResultaat(1, 0, 1, 0, "Reden: ${Faalreden.geenMagazijn(ONBEKEND)}."), resultaat)
    }

    @Test
    fun `een afgekeurd bericht leest als afkeuring en niet als storing`() {
        magazijnAntwoordt(400)

        assertEquals("Reden: ${Faalreden.vanStatus(RVO, 400)}.", service.leverAan(listOf(opdracht())).letOp)
    }

    @Test
    fun `een ronde van honderd houdt het bij de meest voorkomende reden`() {
        // De eis uit het issue: honderd mislukkingen mogen geen honderd regels opleveren.
        magazijnAntwoordt(403)

        val resultaat = service.leverAan(List(100) { opdracht() })

        assertEquals(100, resultaat.mislukt)
        assertEquals("Reden: ${Faalreden.vanStatus(RVO, 403)}.", resultaat.letOp)
    }

    @Test
    fun `twee oorzaken door elkaar leveren de meest voorkomende op`() {
        every { clients[RVO] } returns client
        every { clients[ONBEKEND] } returns null
        every { client.leverAan(any()) } returns respons(403)

        val resultaat = service.leverAan(List(3) { opdracht() } + opdracht(ONBEKEND))

        assertEquals(4, resultaat.mislukt)
        assertEquals("Meest voorkomende reden (3 van de 4): ${Faalreden.vanStatus(RVO, 403)}.", resultaat.letOp)
    }

    @Test
    fun `de reden noemt het nummer van de ontvanger niet`() {
        // De melding komt op een scherm waar mensen naar kijken; de organisatie-OIN mag daar staan,
        // het identificatienummer van de ondernemer niet.
        magazijnAntwoordt(403)

        assertFalse(service.leverAan(listOf(opdracht())).letOp!!.contains(BSN))
    }

    @Test
    fun `een bericht dat wel aankwam maar niet op gelezen ging, telt niet als mislukt`() {
        // De grens tussen de twee tellers: het bericht staat in het magazijn, alleen de lees-mix
        // klopt niet — daar hoort geen reden bij die zegt dat er niets aankwam.
        every { clients[RVO] } returns client
        every { client.leverAan(any()) } returns respons(201, "b-1")
        every { client.markeer(any(), any(), any()) } returns respons(500)

        val resultaat = service.leverAan(listOf(opdracht(gelezen = true)))

        assertEquals(AanleverResultaat(1, 1, 0, 1), resultaat)
        assertNull(resultaat.letOp)
    }

    private companion object {

        const val RVO = "00000000000000100000"
        const val ONBEKEND = "00000000000000999999"

        /** Elfproef-geldig en uit de 999-testreeks, net als de demo-persona's. */
        const val BSN = "999993653"
    }
}
