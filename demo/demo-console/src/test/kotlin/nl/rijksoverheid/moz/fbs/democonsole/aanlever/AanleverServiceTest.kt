package nl.rijksoverheid.moz.fbs.democonsole.aanlever

import io.mockk.Runs
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.verify
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
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource

/**
 * Wat de bediener van het paneel te zien krijgt als een aanlevering niet aankomt. De clients zijn
 * dubbels: het magazijn draait hier niet, en waar het om gaat is dat elke faalmodus zijn eigen
 * reden oplevert in plaats van alleen een teller.
 */
class AanleverServiceTest {

    private val clients = mockk<MagazijnClients>()
    private val client = mockk<MagazijnAanleverClient>()
    private val tweedeClient = mockk<MagazijnAanleverClient>()
    private val service = AanleverService(clients)

    /** Een respons zoals de REST-client hem teruggeeft; `use` sluit hem, dus `close` hoort erbij. */
    private fun respons(status: Int, berichtId: String? = null, detail: String? = null) = mockk<Response>().also {
        every { it.status } returns status
        every { it.close() } just Runs
        every { it.hasEntity() } returns (detail != null)

        if (detail != null) every { it.readEntity(Problem::class.java) } returns Problem(detail)

        if (berichtId != null) every { it.readEntity(AanleverRespons::class.java) } returns AanleverRespons(berichtId)
    }

    private fun opdracht(magazijnOin: String = RVO, gelezen: Boolean = false, type: String = "BSN") = AanleverOpdracht(
        magazijnOin,
        AanleverVerzoek(
            afzender = magazijnOin,
            ontvanger = OntvangerDto(type, ONTVANGER),
            onderwerp = "Demo",
            inhoud = "Demo-inhoud",
            publicatietijdstip = "2026-09-04T10:00:00Z",
        ),
        gelezen,
    )

    private fun magazijnAntwoordt(status: Int, berichtId: String? = null, detail: String? = null) {
        every { clients[RVO] } returns client
        every { client.leverAan(any()) } returns respons(status, berichtId, detail)
    }

    private fun magazijnGooit(fout: Throwable) {
        every { clients[RVO] } returns client
        every { client.leverAan(any()) } throws fout
    }

    @Test
    fun `een geslaagde ronde draagt geen reden`() {
        magazijnAntwoordt(201, "b-1")

        val resultaat = service.leverAan(listOf(opdracht(), opdracht()))

        assertEquals(AanleverResultaat.van(2, 2, 0, emptyList()), resultaat)
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
    fun `de reden die het magazijn zelf gaf komt in de melding`() {
        // Achter één 403 zitten "ontvanger onbekend" en "geen actieve voorkeur voor deze afzender",
        // met een ander vervolg; alleen het magazijn weet welke van de twee het was.
        magazijnAntwoordt(403, detail = "Ontvanger heeft geen actieve berichtenbox-voorkeur voor deze afzender.")

        assertTrue(service.leverAan(listOf(opdracht())).letOp!!.contains("geen actieve berichtenbox-voorkeur"))
    }

    @Test
    fun `bij een serverfout wordt de reden van het magazijn niet eens gelezen`() {
        // `vanStatus` negeert hem daar toch, en elke leespoging die misgaat kost een waarschuwing:
        // een storing tijdens een ronde van honderd berichten gaf er zo honderd over een body die
        // niemand had willen lezen.
        val respons = respons(503, detail = "De toestemmingscontrole kon niet uitgevoerd worden.")

        every { clients[RVO] } returns client
        every { client.leverAan(any()) } returns respons

        service.leverAan(listOf(opdracht()))

        verify(exactly = 0) { respons.readEntity(Problem::class.java) }
    }

    @Test
    fun `een onleesbaar foutantwoord verbergt de afwijzing niet`() {
        // Een foutpagina in plaats van problem+json: de status blijft, de eigen zin blijft.
        every { clients[RVO] } returns client
        every { client.leverAan(any()) } returns mockk<Response>().also {
            every { it.status } returns 403
            every { it.close() } just Runs
            every { it.hasEntity() } returns true
            every { it.readEntity(Problem::class.java) } throws ProcessingException("geen JSON")
        }

        assertEquals("Reden: ${Faalreden.vanStatus(RVO, 403)}.", service.leverAan(listOf(opdracht())).letOp)
    }

    @Test
    fun `een onbereikbaar magazijn leest anders dan een weigering`() {
        every { clients[RVO] } returns client
        every { client.leverAan(any()) } throws ProcessingException("Connection refused")

        val onbereikbaar = service.leverAan(listOf(opdracht()))

        magazijnAntwoordt(403)

        val geweigerd = service.leverAan(listOf(opdracht()))

        assertEquals(1, onbereikbaar.mislukt)
        // Op identiteit en niet alleen op "anders dan": zonder de smalle catch in `lever` zou het
        // brede vangnet eromheen "brak onverwacht af" opleveren — óók een andere zin, maar zonder
        // het woord dat de bediener naar de storingsknop stuurt.
        assertEquals("Reden: ${Faalreden.onbereikbaar(RVO)}.", onbereikbaar.letOp)
        assertNotEquals(onbereikbaar.letOp, geweigerd.letOp)
    }

    @Test
    fun `een ontbrekend magazijn-adres wijst naar de inrichting en niet naar de keten`() {
        every { clients[ONBEKEND] } returns null

        val resultaat = service.leverAan(listOf(opdracht(ONBEKEND)))

        assertEquals(AanleverResultaat.van(1, 0, 0, listOf(Faalreden.geenMagazijn(ONBEKEND))), resultaat)
        assertTrue(resultaat.letOp!!.contains(ONBEKEND), "de melding hoort de organisatie te noemen")
    }

    @Test
    fun `een afgekeurd bericht leest als afkeuring en niet als storing`() {
        magazijnAntwoordt(400)

        assertEquals("Reden: ${Faalreden.vanStatus(RVO, 400)}.", service.leverAan(listOf(opdracht())).letOp)
    }

    @Test
    fun `de melding wijst het magazijn aan waar het misging`() {
        // Met één magazijn in de test zou een hardgecodeerde OIN in de service niet opvallen, en
        // dan stuurt de melding de bediener tijdens een demo naar de verkeerde organisatie.
        every { clients[RVO] } returns client
        every { clients[BELASTINGDIENST] } returns tweedeClient
        every { client.leverAan(any()) } returns respons(403)
        every { tweedeClient.leverAan(any()) } throws ProcessingException("Connection refused")

        val viaRvo = service.leverAan(listOf(opdracht(RVO))).letOp!!
        val viaBelastingdienst = service.leverAan(listOf(opdracht(BELASTINGDIENST))).letOp!!

        assertTrue(viaRvo.contains(RVO), viaRvo)
        assertTrue(viaBelastingdienst.contains(BELASTINGDIENST), viaBelastingdienst)
    }

    @Test
    fun `een ronde van honderd houdt het bij één regel`() {
        // Honderd mislukkingen mogen geen honderd regels opleveren.
        magazijnAntwoordt(403)

        val resultaat = service.leverAan(List(100) { opdracht() })

        assertEquals(100, resultaat.mislukt)
        assertEquals("Reden: ${Faalreden.vanStatus(RVO, 403)}.", resultaat.letOp)
    }

    @Test
    fun `een ronde die deels slaagt telt allebei en meldt alleen de mislukking`() {
        // Het geval uit de praktijk: "39 van de 40 aangeleverd, 1 mislukt" — dan hoort de reden bij
        // die ene te horen en niet bij de negenendertig die het wél haalden.
        every { clients[RVO] } returns client
        every { clients[BELASTINGDIENST] } returns tweedeClient
        every { client.leverAan(any()) } returns respons(201, "b-1")
        every { tweedeClient.leverAan(any()) } returns respons(403)

        val resultaat = service.leverAan(List(3) { opdracht(RVO) } + opdracht(BELASTINGDIENST))

        val verwacht = AanleverResultaat.van(4, 3, 0, listOf(Faalreden.vanStatus(BELASTINGDIENST, 403)))

        assertEquals(verwacht, resultaat)
    }

    @Test
    fun `twee oorzaken door elkaar leveren de meest voorkomende op`() {
        every { clients[RVO] } returns client
        every { clients[ONBEKEND] } returns null
        every { client.leverAan(any()) } returns respons(403)

        val resultaat = service.leverAan(List(3) { opdracht() } + opdracht(ONBEKEND))

        assertEquals(4, resultaat.mislukt)
        assertEquals(
            "Meest voorkomende van 2 redenen (3 van de 4): ${Faalreden.vanStatus(RVO, 403)}.",
            resultaat.letOp,
        )
    }

    /**
     * De melding komt op een scherm waar mensen naar kijken: de organisatie-OIN mag daar staan, het
     * identificatienummer van de ondernemer niet. Over alle faalmodi, zodat de grens ook staat als
     * er later een reden bij komt.
     */
    @ParameterizedTest
    @ValueSource(strings = ["geen adres", "onbereikbaar", "geweigerd", "afgekeurd", "serverfout"])
    fun `geen enkele reden noemt het nummer van de ontvanger`(faalmodus: String) {
        when (faalmodus) {
            "geen adres" -> every { clients[RVO] } returns null
            "onbereikbaar" -> magazijnGooit(ProcessingException("Connection refused"))
            // Mét detail: dat is de enige weg waarlangs tekst van buiten in de melding komt.
            "geweigerd" -> magazijnAntwoordt(403, detail = "Ontvanger heeft geen actieve voorkeur.")
            "afgekeurd" -> magazijnAntwoordt(400)
            else -> magazijnAntwoordt(500)
        }

        listOf("BSN", "KVK").forEach { type ->
            val letOp = service.leverAan(listOf(opdracht(type = type))).letOp!!

            assertFalse(letOp.contains(ONTVANGER), "reden noemt de ontvanger: $letOp")
        }
    }

    @Test
    fun `een bericht dat wel aankwam maar niet op gelezen ging, telt niet als mislukt`() {
        // De grens tussen de twee tellers: het bericht staat in het magazijn, alleen de lees-mix
        // klopt niet — daar hoort geen reden bij die zegt dat er niets aankwam.
        every { clients[RVO] } returns client
        every { client.leverAan(any()) } returns respons(201, "b-1")
        every { client.markeer(any(), any(), any()) } returns respons(500)

        val resultaat = service.leverAan(listOf(opdracht(gelezen = true)))

        assertEquals(AanleverResultaat.van(1, 1, markeringMislukt = 1, redenen = emptyList()), resultaat)
        assertNull(resultaat.letOp)
    }

    @Test
    fun `een onbereikbaar magazijn bij het markeren breekt de ronde niet af`() {
        // De storingsknop midden in een ronde: het bericht ligt er al, alleen de PATCH komt niet aan.
        every { clients[RVO] } returns client
        every { client.leverAan(any()) } returns respons(201, "b-1")
        every { client.markeer(any(), any(), any()) } throws ProcessingException("Connection refused")

        val resultaat = service.leverAan(List(2) { opdracht(gelezen = true) })

        assertEquals(AanleverResultaat.van(2, 2, markeringMislukt = 2, redenen = emptyList()), resultaat)
    }

    @Test
    fun `alleen de berichten die op gelezen moesten tellen mee in markeringMislukt`() {
        // Alle tellers gelijk zetten zou verbergen dat deze teller aan `gelezen` hangt en niet aan
        // het aantal afgeleverde berichten.
        every { clients[RVO] } returns client
        every { client.leverAan(any()) } returns respons(201, "b-1")
        every { client.markeer(any(), any(), any()) } returns respons(500)

        val resultaat = service.leverAan(List(2) { opdracht() } + opdracht(gelezen = true))

        assertEquals(AanleverResultaat.van(3, 3, markeringMislukt = 1, redenen = emptyList()), resultaat)
    }

    @Test
    fun `een onverwachte fout in één opdracht laat de rest van de ronde staan`() {
        // Zonder deze grens meldt de console niets over wat al wél is afgeleverd, en levert een
        // tweede poging dubbele berichten op. Een 201 zonder berichtId is zo'n geval.
        every { clients[RVO] } returns client
        every { clients[BELASTINGDIENST] } returns tweedeClient
        every { client.leverAan(any()) } returns respons(201, "b-1")
        every { tweedeClient.leverAan(any()) } returns mockk<Response>().also {
            every { it.status } returns 201
            every { it.close() } just Runs
            every { it.readEntity(AanleverRespons::class.java) } throws IllegalStateException("geen berichtId")
        }

        val resultaat = service.leverAan(listOf(opdracht(BELASTINGDIENST)) + List(2) { opdracht(RVO) })

        assertEquals(2, resultaat.geslaagd)
        assertEquals(1, resultaat.mislukt)
        // De faalmodus erbij: elke reden noemt het magazijn, dus alleen daarop asserteren zou niet
        // onderscheiden of dit als onbereikbaar, geweigerd of onverwacht gemeld werd.
        assertEquals("Reden: ${Faalreden.onverwacht(BELASTINGDIENST, IllegalStateException())}.", resultaat.letOp)
    }

    @Test
    fun `elke respons wordt gesloten, ook op het faalpad`() {
        val geweigerd = respons(403)

        every { clients[RVO] } returns client
        every { client.leverAan(any()) } returns geweigerd

        service.leverAan(listOf(opdracht()))

        verify { geweigerd.close() }
    }

    private companion object {

        const val RVO = "00000000000000100000"
        const val BELASTINGDIENST = "00000001823288444000"
        const val ONBEKEND = "00000000000000999999"

        /** Elfproef-geldig en uit de 999-testreeks, net als de demo-persona's. */
        const val ONTVANGER = "999993653"
    }
}
