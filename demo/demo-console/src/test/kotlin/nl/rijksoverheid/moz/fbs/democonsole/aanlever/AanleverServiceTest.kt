package nl.rijksoverheid.moz.fbs.democonsole.aanlever

import io.mockk.every
import io.mockk.justRun
import io.mockk.mockk
import io.mockk.verify
import jakarta.ws.rs.ProcessingException
import jakarta.ws.rs.core.Response
import nl.rijksoverheid.moz.fbs.democonsole.generator.AanleverOpdracht
import nl.rijksoverheid.moz.fbs.democonsole.generator.AanleverVerzoek
import nl.rijksoverheid.moz.fbs.democonsole.generator.OntvangerDto
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource

/**
 * Een vulronde levert een reeks berichten achter elkaar aan, en het paneel heeft knoppen om een
 * magazijn traag of onbereikbaar te maken. Breekt zo'n ronde halverwege af, dan noemt het paneel
 * geen enkel cijfer over wat er al wél is afgeleverd en levert de tweede druk op de knop dubbele
 * berichten op.
 *
 * In de meerstapstests staat de hapering daarom in het míddelste bericht: alleen daar onderscheidt
 * de test of de ronde doorliep, in plaats van of hij toevallig op een geslaagd bericht eindigde.
 *
 * Het magazijn is een MockK-mock en niet een eigen implementatie van [MagazijnAanleverClient]: die
 * interface draagt JAX-RS-annotaties, dus een geïndexeerde klasse die hem implementeert wordt in de
 * Quarkus-testapplicatie van deze module als serverresource geregistreerd en botst daar op
 * `POST /api/v1/aanleveringen`.
 */
class AanleverServiceTest {

    // ------------------------------------------------- een 201 zonder bruikbaar berichtId

    @Test
    fun `een afgekapt antwoord laat de ronde doorlopen`() {
        val magazijn = magazijn(geldig(1), gooitBijLezen(ProcessingException("Unexpected end-of-input")), geldig(3))

        val resultaat = AanleverService(mapOf(OIN_A to magazijn)).leverAan(ronde(3))

        assertEquals(uitkomst(aangeboden = 3, geslaagd = 3, zonderBerichtId = 1), resultaat)
        verify(exactly = 3) { magazijn.leverAan(any()) }
    }

    @Test
    fun `een leeg antwoord laat de ronde doorlopen`() {
        // Een 201 met lege body: de runtime kan hier null teruggeven in plaats van te gooien. Zonder
        // null-check is het uitlezen van berichtId dan een NullPointerException.
        val magazijn = magazijn(geldig(1), { antwoord(201, null) }, geldig(3))

        val resultaat = AanleverService(mapOf(OIN_A to magazijn)).leverAan(ronde(3))

        assertEquals(uitkomst(aangeboden = 3, geslaagd = 3, zonderBerichtId = 1), resultaat)
        verify(exactly = 3) { magazijn.leverAan(any()) }
    }

    @ParameterizedTest(name = "berichtId [{0}]")
    @ValueSource(strings = ["", " ", "\t"])
    fun `een berichtId zonder inhoud telt als afwezig en levert geen markeer-aanroep op`(leeg: String) {
        val magazijn = magazijn(geldig(1), { antwoord(201, AanleverRespons(leeg)) }, geldig(3))

        val resultaat = AanleverService(mapOf(OIN_A to magazijn)).leverAan(ronde(3, gelezen = true))

        assertEquals(
            uitkomst(aangeboden = 3, geslaagd = 3, markeringMislukt = 1, zonderBerichtId = 1),
            resultaat,
        )
        verify(exactly = 2) { magazijn.markeer(any(), any(), any()) }
        verify(exactly = 0) { magazijn.markeer(leeg, any(), any()) }
    }

    @Test
    fun `een ontkoppelde entity laat de ronde doorlopen`() {
        // Een andere exception dan bij een afgekapte body, met dezelfde uitwerking.
        val ontkoppeld = gooitBijLezen(IllegalStateException("Entity input stream has already been closed"))
        val magazijn = magazijn(geldig(1), ontkoppeld, geldig(3))

        val resultaat = AanleverService(mapOf(OIN_A to magazijn)).leverAan(ronde(3))

        assertEquals(uitkomst(aangeboden = 3, geslaagd = 3, zonderBerichtId = 1), resultaat)
        verify(exactly = 3) { magazijn.leverAan(any()) }
    }

    @Test
    fun `een onbruikbaar antwoord telt ook als mislukte markering wanneer gelezen gevraagd is`() {
        // Het bericht staat in het magazijn — de Aanlever-API belooft dat bij een 201 — dus
        // geslaagd. Alleen het berichtId is weg, en zonder dat kan de leesstatus niet gezet worden.
        val magazijn = magazijn(gooitBijLezen(ProcessingException("kapot")))

        val resultaat = AanleverService(mapOf(OIN_A to magazijn)).leverAan(ronde(1, gelezen = true))

        assertEquals(
            uitkomst(aangeboden = 1, geslaagd = 1, markeringMislukt = 1, zonderBerichtId = 1),
            resultaat,
        )
        verify(exactly = 0) { magazijn.markeer(any(), any(), any()) }
    }

    @Test
    fun `een onbruikbaar antwoord blijft zichtbaar wanneer er niets gemarkeerd hoefde te worden`() {
        // Drie van de vier berichten in de basisvulling staan op niet-gelezen. Telde alleen
        // markeringMislukt dit geval, dan meldde het paneel daar een volledig groene ronde terwijl
        // het magazijn haperde.
        val magazijn = magazijn(gooitBijLezen(ProcessingException("kapot")))

        val resultaat = AanleverService(mapOf(OIN_A to magazijn)).leverAan(ronde(1, gelezen = false))

        assertEquals(uitkomst(aangeboden = 1, geslaagd = 1, zonderBerichtId = 1), resultaat)
        verify(exactly = 0) { magazijn.markeer(any(), any(), any()) }
    }

    // --------------------------------------------------------- het sluiten van het antwoord

    @Test
    fun `een antwoord wordt gesloten ook als het uitlezen gooit`() {
        val kapot = antwoordDatGooit(ProcessingException("kapot"))

        AanleverService(mapOf(OIN_A to magazijn({ kapot }))).leverAan(ronde(1))

        verify { kapot.close() }
    }

    @Test
    fun `een antwoord dat niet te sluiten is laat de ronde doorlopen`() {
        // Response.close() mag zelf gooien, en dat gebeurt juist bij een half afgekapte stream. Zou
        // die fout ontsnappen, dan strandt de ronde alsnog op een bericht dat al is afgeleverd.
        val magazijn = magazijn(geldig(1), gooitBijSluiten(), geldig(3))

        val resultaat = AanleverService(mapOf(OIN_A to magazijn)).leverAan(ronde(3))

        assertEquals(uitkomst(aangeboden = 3, geslaagd = 3), resultaat)
        verify(exactly = 3) { magazijn.leverAan(any()) }
    }

    @Test
    fun `het antwoord op een markering wordt gesloten`() {
        val magazijn = magazijn(geldig(1))
        val markeerAntwoord = antwoord(200, null)

        every { magazijn.markeer(any(), any(), any()) } returns markeerAntwoord

        AanleverService(mapOf(OIN_A to magazijn)).leverAan(ronde(1, gelezen = true))

        verify { markeerAntwoord.close() }
    }

    // ------------------------------------------- een aanlevering die het magazijn niet haalt

    @Test
    fun `een onbereikbaar magazijn laat de ronde doorlopen`() {
        val magazijn = magazijn(geldig(1), { throw ProcessingException("Connection refused") }, geldig(3))

        val resultaat = AanleverService(mapOf(OIN_A to magazijn)).leverAan(ronde(3))

        assertEquals(uitkomst(aangeboden = 3, geslaagd = 2, mislukt = 1), resultaat)
        verify(exactly = 3) { magazijn.leverAan(any()) }
    }

    @Test
    fun `ook een fout buiten ProcessingException laat de ronde doorlopen`() {
        // Welk type de REST-client precies gooit is een implementatiedetail dat met een upgrade kan
        // verschuiven; de garantie dat de ronde doorloopt mag daar niet aan hangen.
        val magazijn = magazijn(geldig(1), { throw IllegalStateException("blocking niet toegestaan") }, geldig(3))

        val resultaat = AanleverService(mapOf(OIN_A to magazijn)).leverAan(ronde(3))

        assertEquals(uitkomst(aangeboden = 3, geslaagd = 2, mislukt = 1), resultaat)
        verify(exactly = 3) { magazijn.leverAan(any()) }
    }

    @Test
    fun `een geweigerde aanlevering telt als mislukt en het antwoord wordt niet uitgelezen`() {
        val geweigerd = antwoord(400, null)
        val magazijn = magazijn(geldig(1), { geweigerd }, geldig(3))

        val resultaat = AanleverService(mapOf(OIN_A to magazijn)).leverAan(ronde(3))

        assertEquals(uitkomst(aangeboden = 3, geslaagd = 2, mislukt = 1), resultaat)
        verify(exactly = 0) { geweigerd.readEntity(AanleverRespons::class.java) }
        verify { geweigerd.close() }
    }

    // ------------------------------------------------------------------------- de routering

    @Test
    fun `een opdracht voor een onbekend magazijn telt als mislukt en laat de rest doorlopen`() {
        val magazijn = magazijn(geldig(1), geldig(3))
        val opdrachten = listOf(opdracht(1), opdracht(2).copy(magazijnOin = ONBEKENDE_OIN), opdracht(3))

        val resultaat = AanleverService(mapOf(OIN_A to magazijn)).leverAan(opdrachten)

        assertEquals(uitkomst(aangeboden = 3, geslaagd = 2, mislukt = 1), resultaat)
        verify(exactly = 2) { magazijn.leverAan(any()) }
    }

    @Test
    fun `elke opdracht gaat naar het magazijn van zijn eigen OIN`() {
        // Met één magazijn in de map is niet te zien of de service op OIN discrimineert of gewoon
        // het enige magazijn pakt — terwijl de demo er twee heeft.
        val magazijnA = magazijn(geldig(1), geldig(3))
        val magazijnB = magazijn(geldig(2))
        val opdrachten = listOf(opdracht(1), opdracht(2).copy(magazijnOin = OIN_B), opdracht(3))

        val resultaat = AanleverService(mapOf(OIN_A to magazijnA, OIN_B to magazijnB))
            .leverAan(opdrachten)

        assertEquals(uitkomst(aangeboden = 3, geslaagd = 3), resultaat)
        assertEquals(
            listOf("Onderwerp 1", "Onderwerp 3"),
            verzoekenVan(magazijnA).map { it.onderwerp },
        )
        assertEquals(listOf("Onderwerp 2"), verzoekenVan(magazijnB).map { it.onderwerp })
    }

    @Test
    fun `een haperend magazijn sleept het gezonde magazijn niet mee`() {
        val magazijnA = magazijn(gooitBijLezen(ProcessingException("Unexpected end-of-input")))
        val magazijnB = magazijn(geldig(2), geldig(3))
        val opdrachten = listOf(opdracht(1), opdracht(2).copy(magazijnOin = OIN_B), opdracht(3).copy(magazijnOin = OIN_B))

        val resultaat = AanleverService(mapOf(OIN_A to magazijnA, OIN_B to magazijnB))
            .leverAan(opdrachten)

        assertEquals(uitkomst(aangeboden = 3, geslaagd = 3, zonderBerichtId = 1), resultaat)
        verify(exactly = 2) { magazijnB.leverAan(any()) }
    }

    // ------------------------------------------------------------------------- de markering

    @Test
    fun `elk bericht wordt gemarkeerd met zijn eigen berichtId en ontvanger`() {
        val magazijn = magazijn(geldig(1), geldig(2), geldig(3))
        val opdrachten = listOf(opdracht(1, gelezen = true), opdracht(2), opdracht(3, gelezen = true))

        val resultaat = AanleverService(mapOf(OIN_A to magazijn)).leverAan(opdrachten)

        assertEquals(uitkomst(aangeboden = 3, geslaagd = 3), resultaat)
        verify(exactly = 1) { magazijn.markeer(berichtId(1), header(1), StatusPatch(gelezen = true)) }
        verify(exactly = 1) { magazijn.markeer(berichtId(3), header(3), StatusPatch(gelezen = true)) }
        verify(exactly = 2) { magazijn.markeer(any(), any(), any()) }
    }

    @Test
    fun `een geweigerde markering telt als mislukte markering en niet als mislukt bericht`() {
        val magazijn = magazijn(geldig(1))

        every { magazijn.markeer(any(), any(), any()) } returns antwoord(500, null)

        val resultaat = AanleverService(mapOf(OIN_A to magazijn)).leverAan(ronde(1, gelezen = true))

        assertEquals(uitkomst(aangeboden = 1, geslaagd = 1, markeringMislukt = 1), resultaat)
    }

    @Test
    fun `een onbereikbaar magazijn bij het markeren telt als mislukte markering`() {
        val magazijn = magazijn(geldig(1))

        every { magazijn.markeer(any(), any(), any()) } throws ProcessingException("Connection refused")

        val resultaat = AanleverService(mapOf(OIN_A to magazijn)).leverAan(ronde(1, gelezen = true))

        assertEquals(uitkomst(aangeboden = 1, geslaagd = 1, markeringMislukt = 1), resultaat)
    }

    // ------------------------------------------------------------- alles door elkaar heen

    @Test
    fun `een ronde met vier soorten hapering telt ze alle vier apart`() {
        // Een haperend magazijn levert in werkelijkheid een mix binnen één ronde; los getoetste
        // tellers verbergen dat ze elkaar in de weg zitten.
        val magazijn = magazijn(
            geldig(1),
            { throw ProcessingException("Connection refused") },
            { antwoord(400, null) },
            gooitBijLezen(ProcessingException("Unexpected end-of-input")),
            geldig(5),
        )

        every { magazijn.markeer(any(), any(), any()) } returns antwoord(500, null)

        val opdrachten = listOf(
            opdracht(1),
            opdracht(2),
            opdracht(3),
            opdracht(4, gelezen = true),
            opdracht(5, gelezen = true),
        )

        val resultaat = AanleverService(mapOf(OIN_A to magazijn)).leverAan(opdrachten)

        assertEquals(
            uitkomst(aangeboden = 5, geslaagd = 3, mislukt = 2, markeringMislukt = 2, zonderBerichtId = 1),
            resultaat,
        )
        verify(exactly = 1) { magazijn.markeer(berichtId(5), header(5), StatusPatch(gelezen = true)) }
        verify(exactly = 1) { magazijn.markeer(any(), any(), any()) }
    }

    // ---------------------------------------------------------------------- de cardinaliteiten

    @Test
    fun `een lege ronde levert een lege uitkomst`() {
        val magazijn = magazijn()

        val resultaat = AanleverService(mapOf(OIN_A to magazijn)).leverAan(emptyList())

        assertEquals(uitkomst(aangeboden = 0), resultaat)
        verify(exactly = 0) { magazijn.leverAan(any()) }
    }

    @Test
    fun `een ronde van één bericht slaagt`() {
        val magazijn = magazijn(geldig(1))

        val resultaat = AanleverService(mapOf(OIN_A to magazijn)).leverAan(ronde(1))

        assertEquals(uitkomst(aangeboden = 1, geslaagd = 1), resultaat)
        verify(exactly = 0) { magazijn.markeer(any(), any(), any()) }
    }

    // ---------------------------------------------------------------------------- gereedschap

    private companion object {

        const val OIN_A = "00000000000000100000"
        const val OIN_B = "00000001823288444000"
        const val ONBEKENDE_OIN = "00000000000000000000"

        /**
         * Magazijn dat de opgegeven antwoorden op volgorde afwerkt: één per aanlevering, zodat een
         * hapering precies bij het n-de bericht van de ronde valt. Markeren slaagt tenzij een test
         * dat opnieuw instelt.
         */
        fun magazijn(vararg antwoorden: () -> Response): MagazijnAanleverClient {
            val client = mockk<MagazijnAanleverClient>()
            val rij = ArrayDeque(antwoorden.toList())

            every { client.leverAan(any()) } answers { rij.removeFirst()() }
            every { client.markeer(any(), any(), any()) } answers { antwoord(200, null) }

            return client
        }

        /** De verzoeken die dit magazijn kreeg, op volgorde van aanroep. */
        fun verzoekenVan(client: MagazijnAanleverClient): List<AanleverVerzoek> {
            val verzoeken = mutableListOf<AanleverVerzoek>()

            verify { client.leverAan(capture(verzoeken)) }

            return verzoeken
        }

        fun antwoord(status: Int, entity: AanleverRespons?): Response {
            val response = mockk<Response>()

            every { response.status } returns status
            every { response.readEntity(AanleverRespons::class.java) } returns entity
            justRun { response.close() }

            return response
        }

        fun antwoordDatGooit(fout: Throwable): Response {
            val response = mockk<Response>()

            every { response.status } returns 201
            every { response.readEntity(AanleverRespons::class.java) } throws fout
            justRun { response.close() }

            return response
        }

        fun gooitBijLezen(fout: Throwable): () -> Response = { antwoordDatGooit(fout) }

        fun gooitBijSluiten(): () -> Response = {
            val response = mockk<Response>()

            every { response.status } returns 201
            every { response.readEntity(AanleverRespons::class.java) } returns AanleverRespons(berichtId(2))
            every { response.close() } throws ProcessingException("stream niet af te ronden")

            response
        }

        fun berichtId(nummer: Int) = "11111111-2222-3333-4444-00000000000$nummer"

        fun geldig(nummer: Int): () -> Response = { antwoord(201, AanleverRespons(berichtId(nummer))) }

        /** Twee ontvangertypen door elkaar, zodat de X-Ontvanger-header per bericht verschilt. */
        fun ontvanger(nummer: Int) =
            if (nummer % 2 == 1) OntvangerDto("BSN", "999999011") else OntvangerDto("KVK", "90000001")

        fun header(nummer: Int) = ontvanger(nummer).let { "${it.type}:${it.waarde}" }

        fun opdracht(nummer: Int, gelezen: Boolean = false) = AanleverOpdracht(
            magazijnOin = OIN_A,
            verzoek = AanleverVerzoek(
                afzender = OIN_A,
                ontvanger = ontvanger(nummer),
                onderwerp = "Onderwerp $nummer",
                inhoud = "Inhoud $nummer",
                publicatietijdstip = "2026-09-06T10:00:00Z",
            ),
            gelezen = gelezen,
        )

        fun ronde(aantal: Int, gelezen: Boolean = false) = (1..aantal).map { opdracht(it, gelezen) }

        fun uitkomst(
            aangeboden: Int,
            geslaagd: Int = 0,
            mislukt: Int = 0,
            markeringMislukt: Int = 0,
            zonderBerichtId: Int = 0,
        ) = AanleverResultaat(aangeboden, geslaagd, mislukt, markeringMislukt, zonderBerichtId)
    }
}
