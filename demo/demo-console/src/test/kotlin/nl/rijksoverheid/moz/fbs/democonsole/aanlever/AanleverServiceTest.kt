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

/**
 * Een vulronde levert tot honderd berichten aan en het paneel heeft knoppen om een magazijn traag of
 * onbereikbaar te maken. Breekt zo'n ronde halverwege af, dan noemt het paneel geen enkel cijfer over
 * wat er al wél is afgeleverd en levert de tweede druk op de knop dubbele berichten op.
 *
 * De hapering staat in elke rondetest daarom in het míddelste bericht: alleen dan onderscheidt de
 * test of de ronde doorliep, in plaats van of hij toevallig op een geslaagd bericht eindigde.
 *
 * Het magazijn is een MockK-mock en niet een eigen implementatie van [MagazijnAanleverClient]: die
 * interface draagt JAX-RS-annotaties, dus een geïndexeerde klasse die hem implementeert wordt in de
 * `@QuarkusTest` van deze module als serverresource geregistreerd en botst daar op `POST /api/v1`.
 */
class AanleverServiceTest {

    // --------------------------------------------------------- een onbruikbaar antwoord op een 201

    @Test
    fun `een afgekapt antwoord laat de ronde doorlopen`() {
        val magazijn = magazijn(::geldig, gooitBijLezen(ProcessingException("Unexpected end-of-input")), ::geldig)

        val resultaat = AanleverService(mapOf(OIN to magazijn)).leverAan(ronde(3))

        assertEquals(AanleverResultaat(aangeboden = 3, geslaagd = 3, mislukt = 0, markeringMislukt = 0), resultaat)
        verify(exactly = 3) { magazijn.leverAan(any()) }
    }

    @Test
    fun `een leeg antwoord laat de ronde doorlopen`() {
        // Een 201 met lege body: de runtime kan hier null teruggeven in plaats van te gooien, en dan
        // is het `.berichtId` erachter de crash.
        val magazijn = magazijn(::geldig, { antwoord(201, null) }, ::geldig)

        val resultaat = AanleverService(mapOf(OIN to magazijn)).leverAan(ronde(3))

        assertEquals(AanleverResultaat(aangeboden = 3, geslaagd = 3, mislukt = 0, markeringMislukt = 0), resultaat)
        verify(exactly = 3) { magazijn.leverAan(any()) }
    }

    @Test
    fun `een blanco berichtId laat de ronde doorlopen en levert geen markeer-aanroep op`() {
        val magazijn = magazijn(::geldig, { antwoord(201, AanleverRespons(berichtId = "")) }, ::geldig)

        val resultaat = AanleverService(mapOf(OIN to magazijn)).leverAan(ronde(3, gelezen = true))

        assertEquals(AanleverResultaat(aangeboden = 3, geslaagd = 3, mislukt = 0, markeringMislukt = 1), resultaat)
        verify(exactly = 2) { magazijn.markeer(BERICHT_ID, ONTVANGER_HEADER, StatusPatch(gelezen = true)) }
        verify(exactly = 2) { magazijn.markeer(any(), any(), any()) }
    }

    @Test
    fun `een ontkoppelde entity laat de ronde doorlopen`() {
        // readEntity gooit IllegalStateException zodra de entity niet (meer) door een stream wordt
        // gedragen — een andere exception dan bij een afgekapte body, met dezelfde uitwerking.
        val ontkoppeld = gooitBijLezen(IllegalStateException("Entity input stream has already been closed"))
        val magazijn = magazijn(::geldig, ontkoppeld, ::geldig)

        val resultaat = AanleverService(mapOf(OIN to magazijn)).leverAan(ronde(3))

        assertEquals(AanleverResultaat(aangeboden = 3, geslaagd = 3, mislukt = 0, markeringMislukt = 0), resultaat)
        verify(exactly = 3) { magazijn.leverAan(any()) }
    }

    @Test
    fun `een onbruikbaar antwoord telt als geslaagd en als mislukte markering wanneer gelezen gevraagd is`() {
        // Het bericht staat in het magazijn — de 201 komt er pas ná het opslaan — dus geslaagd.
        // Alleen het berichtId is weg, en zonder dat kan de leesstatus niet gezet worden.
        val magazijn = magazijn(gooitBijLezen(ProcessingException("kapot")))

        val resultaat = AanleverService(mapOf(OIN to magazijn)).leverAan(ronde(1, gelezen = true))

        assertEquals(AanleverResultaat(aangeboden = 1, geslaagd = 1, mislukt = 0, markeringMislukt = 1), resultaat)
        verify(exactly = 0) { magazijn.markeer(any(), any(), any()) }
    }

    @Test
    fun `een onbruikbaar antwoord telt niet als mislukte markering wanneer er niets gemarkeerd hoefde te worden`() {
        val magazijn = magazijn(gooitBijLezen(ProcessingException("kapot")))

        val resultaat = AanleverService(mapOf(OIN to magazijn)).leverAan(ronde(1, gelezen = false))

        assertEquals(AanleverResultaat(aangeboden = 1, geslaagd = 1, mislukt = 0, markeringMislukt = 0), resultaat)
    }

    @Test
    fun `een antwoord wordt gesloten ook als het uitlezen gooit`() {
        val kapot = antwoordDatGooit(ProcessingException("kapot"))

        AanleverService(mapOf(OIN to magazijn({ kapot }))).leverAan(ronde(1))

        verify { kapot.close() }
    }

    // ------------------------------------------------------------------ de al bestaande uitkomsten

    @Test
    fun `een onbereikbaar magazijn laat de ronde doorlopen`() {
        val magazijn = magazijn(::geldig, { throw ProcessingException("Connection refused") }, ::geldig)

        val resultaat = AanleverService(mapOf(OIN to magazijn)).leverAan(ronde(3))

        assertEquals(AanleverResultaat(aangeboden = 3, geslaagd = 2, mislukt = 1, markeringMislukt = 0), resultaat)
        verify(exactly = 3) { magazijn.leverAan(any()) }
    }

    @Test
    fun `een geweigerde aanlevering telt als mislukt en het antwoord wordt niet uitgelezen`() {
        val geweigerd = antwoord(400, null)
        val magazijn = magazijn(::geldig, { geweigerd }, ::geldig)

        val resultaat = AanleverService(mapOf(OIN to magazijn)).leverAan(ronde(3))

        assertEquals(AanleverResultaat(aangeboden = 3, geslaagd = 2, mislukt = 1, markeringMislukt = 0), resultaat)
        verify(exactly = 0) { geweigerd.readEntity(AanleverRespons::class.java) }
    }

    @Test
    fun `een opdracht voor een onbekend magazijn telt als mislukt en laat de rest doorlopen`() {
        val magazijn = magazijn(::geldig, ::geldig)
        val opdrachten = listOf(opdracht(1), opdracht(2).copy(magazijnOin = ONBEKENDE_OIN), opdracht(3))

        val resultaat = AanleverService(mapOf(OIN to magazijn)).leverAan(opdrachten)

        assertEquals(AanleverResultaat(aangeboden = 3, geslaagd = 2, mislukt = 1, markeringMislukt = 0), resultaat)
        verify(exactly = 2) { magazijn.leverAan(any()) }
    }

    @Test
    fun `een geslaagde ronde markeert precies de berichten waarvoor gelezen gevraagd is`() {
        val magazijn = magazijn(::geldig, ::geldig)
        val opdrachten = listOf(opdracht(1, gelezen = true), opdracht(2, gelezen = false))

        val resultaat = AanleverService(mapOf(OIN to magazijn)).leverAan(opdrachten)

        assertEquals(AanleverResultaat(aangeboden = 2, geslaagd = 2, mislukt = 0, markeringMislukt = 0), resultaat)
        verify(exactly = 1) { magazijn.markeer(BERICHT_ID, ONTVANGER_HEADER, StatusPatch(gelezen = true)) }
        verify(exactly = 1) { magazijn.markeer(any(), any(), any()) }
    }

    @Test
    fun `een geweigerde markering telt als mislukte markering en niet als mislukt bericht`() {
        val magazijn = magazijn(::geldig)

        every { magazijn.markeer(any(), any(), any()) } returns antwoord(500, null)

        val resultaat = AanleverService(mapOf(OIN to magazijn)).leverAan(ronde(1, gelezen = true))

        assertEquals(AanleverResultaat(aangeboden = 1, geslaagd = 1, mislukt = 0, markeringMislukt = 1), resultaat)
    }

    @Test
    fun `een onbereikbaar magazijn bij het markeren telt als mislukte markering`() {
        val magazijn = magazijn(::geldig)

        every { magazijn.markeer(any(), any(), any()) } throws ProcessingException("Connection refused")

        val resultaat = AanleverService(mapOf(OIN to magazijn)).leverAan(ronde(1, gelezen = true))

        assertEquals(AanleverResultaat(aangeboden = 1, geslaagd = 1, mislukt = 0, markeringMislukt = 1), resultaat)
    }

    // -------------------------------------------------------------------------- de cardinaliteiten

    @Test
    fun `een lege ronde levert een lege uitkomst`() {
        val magazijn = magazijn()

        val resultaat = AanleverService(mapOf(OIN to magazijn)).leverAan(emptyList())

        assertEquals(AanleverResultaat(aangeboden = 0, geslaagd = 0, mislukt = 0, markeringMislukt = 0), resultaat)
        verify(exactly = 0) { magazijn.leverAan(any()) }
    }

    @Test
    fun `een ronde van één bericht slaagt`() {
        val magazijn = magazijn(::geldig)

        val resultaat = AanleverService(mapOf(OIN to magazijn)).leverAan(ronde(1))

        assertEquals(AanleverResultaat(aangeboden = 1, geslaagd = 1, mislukt = 0, markeringMislukt = 0), resultaat)
    }

    // ---------------------------------------------------------------------------------- gereedschap

    private companion object {

        const val OIN = "00000000000000100000"
        const val ONBEKENDE_OIN = "00000000000000000000"
        const val BERICHT_ID = "11111111-2222-3333-4444-555555555555"
        const val ONTVANGER_HEADER = "BSN:999999011"

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

        fun geldig(): Response = antwoord(201, AanleverRespons(BERICHT_ID))

        fun gooitBijLezen(fout: Throwable): () -> Response = { antwoordDatGooit(fout) }

        fun opdracht(nummer: Int, gelezen: Boolean = false) = AanleverOpdracht(
            magazijnOin = OIN,
            verzoek = AanleverVerzoek(
                afzender = OIN,
                ontvanger = OntvangerDto("BSN", "999999011"),
                onderwerp = "Onderwerp $nummer",
                inhoud = "Inhoud $nummer",
                publicatietijdstip = "2026-09-06T10:00:00Z",
            ),
            gelezen = gelezen,
        )

        fun ronde(aantal: Int, gelezen: Boolean = false) = (1..aantal).map { opdracht(it, gelezen) }
    }
}
