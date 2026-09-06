package nl.rijksoverheid.moz.fbs.democonsole.aanlever

import io.mockk.every
import io.mockk.justRun
import io.mockk.mockk
import io.mockk.verify
import jakarta.ws.rs.ProcessingException
import jakarta.ws.rs.WebApplicationException
import jakarta.ws.rs.core.Response
import nl.rijksoverheid.moz.fbs.democonsole.generator.AanleverOpdracht
import nl.rijksoverheid.moz.fbs.democonsole.generator.AanleverVerzoek
import nl.rijksoverheid.moz.fbs.democonsole.generator.OntvangerDto
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.util.logging.Handler
import java.util.logging.Level
import java.util.logging.LogRecord
import java.util.logging.Logger

/**
 * Een vulronde levert een reeks berichten achter elkaar aan, en het paneel heeft knoppen om een
 * magazijn traag of onbereikbaar te maken. Breekt zo'n ronde halverwege af, dan noemt het paneel
 * geen enkel cijfer over wat er al wél is afgeleverd en levert de tweede druk op de knop dubbele
 * berichten op.
 *
 * Waar één hapering wordt getoetst staat die daarom in het míddelste bericht: alleen daar
 * onderscheidt de test of de ronde doorliep, in plaats van of hij toevallig op een geslaagd bericht
 * eindigde.
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

        assertEquals(uitkomst(aangeboden = 3, geslaagd = 3, zonderBerichtId = 1), resultaat)
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
    fun `een onbruikbaar antwoord telt één keer, ook wanneer om gelezen gevraagd was`() {
        // Het bericht staat in het magazijn — de Aanlever-API belooft dat bij een 201 — dus
        // geslaagd. Het berichtId is weg, en zonder dat valt er niets te markeren; dat telt als één
        // ding en niet als twee, anders leest één bericht in het paneel als twee problemen.
        //
        // Het telt bovendien los van `gelezen`: drie van de vier berichten in de basisvulling staan
        // op niet-gelezen, en zonder eigen teller meldde het paneel daar een volledig groene ronde.
        val magazijn = magazijn(gooitBijLezen(ProcessingException("kapot")))

        val resultaat = AanleverService(mapOf(OIN_A to magazijn)).leverAan(ronde(1, gelezen = true))

        assertEquals(uitkomst(aangeboden = 1, geslaagd = 1, zonderBerichtId = 1), resultaat)
        verify(exactly = 0) { magazijn.markeer(any(), any(), any()) }
    }

    // --------------------------------------------------------- het sluiten van het antwoord

    @Test
    fun `een antwoord wordt gesloten ook als het uitlezen gooit`() {
        val kapot = antwoordDatGooit(ProcessingException("kapot"))

        AanleverService(mapOf(OIN_A to magazijn({ kapot }))).leverAan(ronde(1))

        verify(exactly = 1) { kapot.close() }
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
    fun `een antwoord wordt ook gesloten als alles goed gaat`() {
        // Het succespad is het pad dat een vulronde honderd keer loopt; lekt het daar, dan raakt de
        // connection-pool leeg en strandt de ronde alsnog.
        val geslaagd = antwoord(201, AanleverRespons(berichtId(1)))

        AanleverService(mapOf(OIN_A to magazijn({ geslaagd }))).leverAan(ronde(1))

        verify(exactly = 1) { geslaagd.close() }
    }

    @Test
    fun `het antwoord op een geweigerde markering wordt gesloten`() {
        val magazijn = magazijn(geldig(1))
        val geweigerd = antwoord(500, null)

        every { magazijn.markeer(any(), any(), any()) } returns geweigerd

        AanleverService(mapOf(OIN_A to magazijn)).leverAan(ronde(1, gelezen = true))

        verify(exactly = 1) { geweigerd.close() }
    }

    @Test
    fun `een markeer-antwoord dat niet te sluiten is laat de ronde doorlopen`() {
        // Het lever-pad is hierop gedekt; het markeer-pad sluit zijn antwoord met dezelfde reden, en
        // een fout die daar ontsnapt strandt de ronde net zo goed.
        val magazijn = magazijn(geldig(1), geldig(2), geldig(3))
        val onsluitbaar = mockk<Response>()

        every { onsluitbaar.status } returns 200
        every { onsluitbaar.close() } throws ProcessingException("stream niet af te ronden")
        every { magazijn.markeer(any(), any(), any()) } returns onsluitbaar

        val resultaat = AanleverService(mapOf(OIN_A to magazijn)).leverAan(ronde(3, gelezen = true))

        assertEquals(uitkomst(aangeboden = 3, geslaagd = 3), resultaat)
        verify(exactly = 3) { magazijn.markeer(any(), any(), any()) }
    }

    @Test
    fun `het antwoord op een markering wordt gesloten`() {
        val magazijn = magazijn(geldig(1))
        val markeerAntwoord = antwoord(200, null)

        every { magazijn.markeer(any(), any(), any()) } returns markeerAntwoord

        AanleverService(mapOf(OIN_A to magazijn)).leverAan(ronde(1, gelezen = true))

        verify(exactly = 1) { markeerAntwoord.close() }
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
        verify(exactly = 1) { geweigerd.close() }
    }

    // ------------------------------------------------------------------------- de routering

    @Test
    fun `een andere 2xx dan 201 telt als mislukt`() {
        // Het aanlevercontract kent maar één succesvorm. Een 200 draagt geen berichtId en betekent
        // niet dat het bericht is opgeslagen, dus hem meetellen zou een aflevering verzinnen.
        val magazijn = magazijn(geldig(1), { antwoord(200, null) }, geldig(3))

        val resultaat = AanleverService(mapOf(OIN_A to magazijn)).leverAan(ronde(3))

        assertEquals(uitkomst(aangeboden = 3, geslaagd = 2, mislukt = 1), resultaat)
    }

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
        val opdrachten = listOf(
            opdracht(1),
            opdracht(2).copy(magazijnOin = OIN_B),
            opdracht(3).copy(magazijnOin = OIN_B),
        )

        val resultaat = AanleverService(mapOf(OIN_A to magazijnA, OIN_B to magazijnB))
            .leverAan(opdrachten)

        assertEquals(uitkomst(aangeboden = 3, geslaagd = 3, zonderBerichtId = 1), resultaat)
        verify(exactly = 2) { magazijnB.leverAan(any()) }
    }

    // ------------------------------------------------------------------------- de markering

    @Test
    fun `elk bericht wordt gemarkeerd met zijn eigen berichtId en ontvanger`() {
        // Eén BSN- en één KVK-ontvanger: staat het type vast in plaats van uit de opdracht te komen,
        // dan ketst het magazijn de markering af voor het merendeel van de demo.
        val magazijn = magazijn(geldig(1), geldig(2), geldig(3))
        val opdrachten = listOf(opdracht(1, gelezen = true), opdracht(2, gelezen = true), opdracht(3))

        val resultaat = AanleverService(mapOf(OIN_A to magazijn)).leverAan(opdrachten)

        assertEquals(uitkomst(aangeboden = 3, geslaagd = 3), resultaat)
        assertEquals(listOf("BSN", "KVK"), listOf(1, 2).map { ontvanger(it).type }, "de fixtures dekken beide typen")
        verify(exactly = 1) { magazijn.markeer(berichtId(1), header(1), StatusPatch(gelezen = true)) }
        verify(exactly = 1) { magazijn.markeer(berichtId(2), header(2), StatusPatch(gelezen = true)) }
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
    fun `alleen HTTP 200 telt als geslaagde markering`() {
        // Het magazijn beantwoordt de status-PATCH met 200; een andere 2xx betekent iets anders en
        // zegt niet dat de leesstatus is gezet.
        val magazijn = magazijn(geldig(1))

        every { magazijn.markeer(any(), any(), any()) } returns antwoord(204, null)

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
            uitkomst(aangeboden = 5, geslaagd = 3, mislukt = 2, markeringMislukt = 1, zonderBerichtId = 1),
            resultaat,
        )
        verify(exactly = 1) { magazijn.markeer(berichtId(5), header(5), StatusPatch(gelezen = true)) }
        verify(exactly = 1) { magazijn.markeer(any(), any(), any()) }
    }

    @Test
    fun `twee onbruikbare antwoorden in één ronde tellen allebei`() {
        // Met alleen rondes waarin de teller op 1 uitkomt, is een teller die blijft staan niet van
        // een teller die telt te onderscheiden — en "1 zonder bevestigd berichtnummer" na honderd
        // haperingen leest geloofwaardiger dan de waarheid.
        val magazijn = magazijn(
            gooitBijLezen(ProcessingException("Unexpected end-of-input")),
            geldig(2),
            { antwoord(201, null) },
        )

        val resultaat = AanleverService(mapOf(OIN_A to magazijn)).leverAan(ronde(3))

        assertEquals(uitkomst(aangeboden = 3, geslaagd = 3, zonderBerichtId = 2), resultaat)
    }

    // -------------------------------------------------------------------------------- de logregels

    @Test
    fun `geen enkele logregel draagt de waarde van de ontvanger`() {
        // Een BSN hoort niet in een applicatielog. De ontvanger reist mee in de request-body en in
        // de X-Ontvanger-header, dus elke onderdrukte fout is een kans om hem alsnog weg te
        // schrijven — en dat valt zonder deze test nergens op.
        // Elke plek waar een fout wordt onderdrukt komt hier langs; een regel die dit scenario niet
        // raakt, is een regel waar een identificatienummer ongemerkt in kan blijven staan.
        val magazijn = magazijn(
            { antwoord(400, null) },
            gooitBijLezen(ProcessingException("body bevat 999999011")),
            { throw ProcessingException("header BSN:999999011 geweigerd") },
            // Landt op de luide tak mét throwable: die voegt de oorzaakketen én het stackframe samen,
            // en juist daar is een melding het makkelijkst alsnog binnen te smokkelen.
            gooitBijLezen(NullPointerException("geen reader voor BSN:999999011")),
            { antwoord(201, null) },
            gooitBijSluiten(ProcessingException("stream van BSN:999999011 niet af te ronden")),
            geldig(7),
            geldig(8),
        )
        val markeerAntwoorden = ArrayDeque(
            listOf<() -> Response>(
                { antwoord(500, null) },
                { throw ProcessingException("BSN:999999011 onbekend") },
            ),
        )

        every { magazijn.markeer(any(), any(), any()) } answers { markeerAntwoorden.removeFirst()() }

        val opdrachten = ronde(6) + opdracht(7, gelezen = true) + opdracht(8, gelezen = true) +
            opdracht(9).copy(magazijnOin = ONBEKENDE_OIN)

        val regels = vangLogregels { AanleverService(mapOf(OIN_A to magazijn)).leverAan(opdrachten) }

        assertEquals(9, regels.size, "elke onderdrukte fout hoort een regel op te leveren")
        regels.forEach { regel ->
            // Niet alleen de melding: een LogRecord draagt ook zijn throwable en parameters, en de
            // idiomatische JUL-vorm `log.log(niveau, melding, fout)` zet de exception dáár neer.
            val alles = regel.message + uitFout(regel.thrown) + regel.parameters.orEmpty().joinToString()

            WAARDEN.forEach { waarde ->
                assertTrue(waarde !in alles, "logregel draagt de ontvangerwaarde: $alles")
            }

            assertTrue(
                OIN_A in regel.message || ONBEKENDE_OIN in regel.message,
                "logregel wijst het magazijn niet aan: ${regel.message}",
            )
        }
    }

    @Test
    fun `de logregel noemt waarom het berichtId ontbrak`() {
        // "201 zonder berichtId" is de uitkomst; zonder de oorzaak erbij weet niemand of het
        // antwoord afgekapt was of gewoon geen id droeg, en dat scheelt bij het uitzoeken alles.
        val afgekapt = magazijn(gooitBijLezen(ProcessingException("Unexpected end-of-input")))
        val leeg = magazijn({ antwoord(201, null) })

        val bijAfgekapt = vangLogregels { AanleverService(mapOf(OIN_A to afgekapt)).leverAan(ronde(1)) }
        val bijLeeg = vangLogregels { AanleverService(mapOf(OIN_A to leeg)).leverAan(ronde(1)) }

        assertTrue(
            "ProcessingException" in bijAfgekapt.single().message,
            "de oorzaak ontbreekt: ${bijAfgekapt.single().message}",
        )
        assertTrue(
            "het antwoord droeg er geen" in bijLeeg.single().message,
            "een leeg antwoord hoort als zodanig te lezen: ${bijLeeg.single().message}",
        )
        // Een antwoord zonder berichtId is het magazijn dat zijn eigen contract niet nakomt, geen
        // fout in de console — dus dezelfde toon als een hapering.
        assertEquals(Level.WARNING, bijLeeg.single().level)
        assertEquals(Level.WARNING, bijAfgekapt.single().level)
    }

    @Test
    fun `de logregel draagt de hele oorzaakketen en niet alleen de bovenste fout`() {
        // De bovenste fout is bijna altijd de wrapper van de client; de diagnose zit in wat eronder
        // zit. toString() laat juist dat weg, dus de keten wordt zelf opgebouwd.
        val kern = ArithmeticException("kern")
        val magazijn = magazijn({ throw ProcessingException("wrapper", IllegalStateException("tussen", kern)) })

        val regels = vangLogregels { AanleverService(mapOf(OIN_A to magazijn)).leverAan(ronde(1)) }

        assertTrue(
            "ProcessingException <- IllegalStateException <- ArithmeticException" in regels.single().message,
            "de oorzaakketen ontbreekt of stopt te vroeg: ${regels.single().message}",
        )
    }

    @Test
    fun `een fout die zichzelf als oorzaak noemt levert één naam op`() {
        // Throwable.initCause weigert een zelfverwijzing, maar een subklasse die getCause overschrijft
        // kan het wél — en dan zou de keten zichzelf tot de dieptegrens herhalen.
        val regels = vangLogregels {
            AanleverService(mapOf(OIN_A to magazijn({ throw ZichzelfAlsOorzaak() }))).leverAan(ronde(1))
        }

        assertFalse(
            " <- " in regels.single().message,
            "de keten herhaalt zichzelf: ${regels.single().message}",
        )
    }

    @Test
    fun `een antwoord dat niet te sluiten is levert een logregel op`() {
        // Het bericht is afgeleverd, dus geen teller — maar herhaald lekken put de connection-pool
        // uit, en dan strandt een latere ronde zonder dat iets naar deze oorzaak wijst.
        val magazijn = magazijn(gooitBijSluiten(ProcessingException("stream niet af te ronden")))

        val regels = vangLogregels { AanleverService(mapOf(OIN_A to magazijn)).leverAan(ronde(1)) }

        assertEquals(Level.WARNING, regels.single().level)
        assertTrue(OIN_A in regels.single().message, "de regel wijst het magazijn niet aan")
    }

    @Test
    fun `een geweigerde markering klinkt luider dan een magazijn dat het even niet aankan`() {
        val geweigerd = magazijn(geldig(1))
        val overbelast = magazijn(geldig(1))
        val kwijt = magazijn(geldig(1))

        every { geweigerd.markeer(any(), any(), any()) } returns antwoord(400, null)
        every { overbelast.markeer(any(), any(), any()) } returns antwoord(503, null)
        // Het magazijn is het bericht kwijt dat het één aanroep eerder zelf bevestigde: de overkant.
        every { kwijt.markeer(any(), any(), any()) } returns antwoord(404, null)

        val bijGeweigerd = vangLogregels { markeerRonde(geweigerd) }
        val bijOverbelast = vangLogregels { markeerRonde(overbelast) }
        val bijKwijt = vangLogregels { markeerRonde(kwijt) }

        assertEquals(Level.SEVERE, bijGeweigerd.single().level)
        assertEquals(Level.WARNING, bijOverbelast.single().level)
        assertEquals(Level.WARNING, bijKwijt.single().level)
        assertTrue(OIN_A in bijGeweigerd.single().message, "de regel wijst het magazijn niet aan")
    }

    @Test
    fun `een fout die geen magazijnstoring is klinkt luider`() {
        // Het paneel toont een bedradingsfout in de console net zo als een magazijn dat uit staat.
        // De log is dan het enige dat de bediener bij de goede oorzaak brengt.
        val storing = magazijn({ throw ProcessingException("Connection refused") })
        val bedrading = magazijn({ throw IllegalStateException("blocking op de event-loop") })

        val storingsregels = vangLogregels { AanleverService(mapOf(OIN_A to storing)).leverAan(ronde(1)) }
        val bedradingsregels = vangLogregels { AanleverService(mapOf(OIN_A to bedrading)).leverAan(ronde(1)) }

        assertEquals(Level.WARNING, storingsregels.single().level)
        assertEquals(Level.SEVERE, bedradingsregels.single().level)
    }

    @Test
    fun `een afgekapte stream is een hapering, een antwoord dat al gelezen was niet`() {
        // De client wikkelt alles wat er onderweg misgaat in een ProcessingException, ook een body die
        // halverwege wegvalt. Een IllegalStateException komt daar dus niet vandaan: die betekent dat
        // de console het antwoord al had gelezen, en dat is onze fout.
        val afgekapt = magazijn(gooitBijLezen(ProcessingException("Unexpected end-of-input")))
        val algelezen = magazijn(gooitBijLezen(IllegalStateException("Response has been closed")))

        val bijAfgekapt = vangLogregels { AanleverService(mapOf(OIN_A to afgekapt)).leverAan(ronde(1)) }
        val bijAlGelezen = vangLogregels { AanleverService(mapOf(OIN_A to algelezen)).leverAan(ronde(1)) }

        assertEquals(Level.WARNING, bijAfgekapt.single().level)
        assertEquals(Level.SEVERE, bijAlGelezen.single().level)
    }

    @Test
    fun `een geweigerde aanlevering klinkt luider dan een magazijn dat het even niet aankan`() {
        // Een 4xx betekent dat de console iets ongeldigs stuurde; dat herstelt zichzelf niet en
        // treft elk bericht van de ronde. Een 5xx is het magazijn.
        val geweigerd = magazijn({ antwoord(400, null) })
        val overbelast = magazijn({ antwoord(503, null) })

        val bijGeweigerd = vangLogregels { AanleverService(mapOf(OIN_A to geweigerd)).leverAan(ronde(1)) }
        val bijOverbelast = vangLogregels { AanleverService(mapOf(OIN_A to overbelast)).leverAan(ronde(1)) }

        assertEquals(Level.SEVERE, bijGeweigerd.single().level)
        assertEquals(Level.WARNING, bijOverbelast.single().level)
    }

    @ParameterizedTest(name = "HTTP {0}")
    @ValueSource(ints = [408, 429, 500, 503, 599])
    fun `een magazijn dat het niet aankan of om uitstel vraagt klinkt als storing`(status: Int) {
        // 408 en 429 zitten in de 4xx-reeks maar zeggen "later nog eens proberen" — dat is het
        // magazijn of de ingress ervoor, niet een verzoek dat wij verkeerd bouwden.
        val magazijn = magazijn({ antwoord(status, null) })

        val regels = vangLogregels { AanleverService(mapOf(OIN_A to magazijn)).leverAan(ronde(1)) }

        assertEquals(Level.WARNING, regels.single().level)
    }

    @ParameterizedTest(name = "HTTP {0}")
    @ValueSource(ints = [200, 302, 400, 409, 425, 499])
    fun `alles buiten een 5xx en de wachtcodes komt van onze kant`(status: Int) {
        // Een 4xx zegt dat we iets ongeldigs stuurden; een 2xx of 3xx die hier belandt is een gebroken
        // contract. Allebei treffen ze elk bericht van de ronde, en allebei herstellen ze zichzelf
        // niet — dus allebei horen ze boven het geruis van een haperend magazijn uit te komen.
        val magazijn = magazijn({ antwoord(status, null) })

        val regels = vangLogregels { AanleverService(mapOf(OIN_A to magazijn)).leverAan(ronde(1)) }

        assertEquals(Level.SEVERE, regels.single().level)
    }

    @Test
    fun `een statuscode van het magazijn in een exception wordt op zijn status beoordeeld`() {
        // Zet iemand de default-exception-mapper weer aan, dan komt de status als exception binnen in
        // plaats van als antwoord. Het oordeel hoort dan hetzelfde te zijn.
        val overbelast = magazijn({ throw WebApplicationException(Response.status(503).build()) })
        val geweigerd = magazijn({ throw WebApplicationException(Response.status(400).build()) })

        val bijOverbelast = vangLogregels { AanleverService(mapOf(OIN_A to overbelast)).leverAan(ronde(1)) }
        val bijGeweigerd = vangLogregels { AanleverService(mapOf(OIN_A to geweigerd)).leverAan(ronde(1)) }

        assertEquals(Level.WARNING, bijOverbelast.single().level)
        assertEquals(Level.SEVERE, bijGeweigerd.single().level)
    }

    @Test
    fun `een luide logregel wijst aan waar de fout ontstond`() {
        // Op de luide tak is een klassenaam alléén te weinig; het bovenste stackframe draagt namen
        // en regelnummers, en dus geen gegevens.
        val magazijn = magazijn({ throw IllegalStateException("blocking op de event-loop") })

        val regels = vangLogregels { AanleverService(mapOf(OIN_A to magazijn)).leverAan(ronde(1)) }

        // Het bovenste frame en niet het onderste: het onderste wijst naar de testrunner, en dat is
        // op elke fout hetzelfde.
        assertTrue(
            Regex(""" @ \S*AanleverServiceTest\S*\.\S+:\d+""").containsMatchIn(regels.single().message),
            "de regel wijst niet naar waar de fout ontstond: ${regels.single().message}",
        )
    }

    @Test
    fun `een opdracht voor een onbekend magazijn klinkt als inrichtingsfout`() {
        val regels = vangLogregels {
            AanleverService(mapOf(OIN_A to magazijn())).leverAan(listOf(opdracht(1).copy(magazijnOin = ONBEKENDE_OIN)))
        }

        assertEquals(Level.SEVERE, regels.single().level)
        assertTrue(ONBEKENDE_OIN in regels.single().message, "de regel noemt de OIN niet die ontbreekt")
    }

    @Test
    fun `elke logregel wijst aan waar hij over gaat`() {
        // Een ronde van honderd berichten levert honderd regels op; zonder magazijn, ontvanger-type of
        // berichtId erin is er geen beginnen aan om ze uit elkaar te houden.
        // De tweede opdracht draagt een KVK-ontvanger, zodat een vast "BSN" in de melding opvalt.
        val magazijn = magazijn(geldig(1), { antwoord(409, null) }, geldig(3), geldig(4))
        val markeerAntwoorden = ArrayDeque(
            listOf<() -> Response>({ antwoord(500, null) }, { throw ProcessingException("weg") }),
        )

        every { magazijn.markeer(any(), any(), any()) } answers { markeerAntwoorden.removeFirst()() }

        val opdrachten = listOf(opdracht(1), opdracht(2), opdracht(3, gelezen = true), opdracht(4, gelezen = true))
        val regels = vangLogregels { AanleverService(mapOf(OIN_A to magazijn)).leverAan(opdrachten) }

        assertTrue(OIN_A in regels[0].message, "de aanlever-regel noemt het magazijn niet")
        assertTrue("KVK" in regels[0].message, "de aanlever-regel noemt het ontvanger-type niet")
        assertTrue("409" in regels[0].message, "de aanlever-regel noemt de statuscode niet")
        assertTrue(berichtId(3) in regels[1].message, "de markeer-statusregel noemt het bericht niet")
        assertTrue("500" in regels[1].message, "de markeer-statusregel noemt de statuscode niet")
        assertTrue(berichtId(4) in regels[2].message, "de markeer-foutregel noemt het bericht niet")
    }

    @Test
    fun `een fout zonder eenvoudige naam wordt alsnog benoemd`() {
        // Een anonieme of lambda-klasse heeft een lege simpleName; zonder terugval staat er dan niets.
        val naamloos = object : RuntimeException("naamloos") {}

        val regels = vangLogregels {
            AanleverService(mapOf(OIN_A to magazijn({ throw naamloos }))).leverAan(ronde(1))
        }

        assertTrue(
            naamloos.javaClass.name in regels.single().message,
            "de regel benoemt de fout niet: ${regels.single().message}",
        )
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

    /** Een fout die zichzelf als oorzaak opgeeft; met `initCause` is dat niet te bouwen. */
    private class ZichzelfAlsOorzaak : RuntimeException("kringloop") {

        override val cause: Throwable get() = this
    }

    private companion object {

        const val OIN_A = "00000000000000100000"
        const val OIN_B = "00000001823288444000"
        const val ONBEKENDE_OIN = "00000000000000000000"

        /** De identificatienummers uit [ontvanger]; deze mogen nergens in een logregel opduiken. */
        val WAARDEN = listOf("999999011", "90000001")

        /**
         * Magazijn dat de opgegeven antwoorden op volgorde afwerkt: één per aanlevering, zodat een
         * hapering precies bij het n-de bericht van de ronde valt. Markeren slaagt tenzij een test
         * dat opnieuw instelt.
         */
        fun magazijn(vararg antwoorden: () -> Response): MagazijnAanleverClient {
            val client = mockk<MagazijnAanleverClient>()
            val rij = ArrayDeque(antwoorden.toList())

            // Luid falen in plaats van NoSuchElementException: die zou door het vangnet van de
            // service worden opgeslokt en als één mislukt bericht lezen.
            every { client.leverAan(any()) } answers {
                val volgende = rij.removeFirstOrNull() ?: error("magazijn kreeg meer aanleveringen dan antwoorden")

                volgende()
            }
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

        fun gooitBijSluiten(fout: Throwable = ProcessingException("stream niet af te ronden")): () -> Response = {
            val response = mockk<Response>()

            every { response.status } returns 201
            every { response.readEntity(AanleverRespons::class.java) } returns AanleverRespons(berichtId(2))
            every { response.close() } throws fout

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

        /**
         * De meldingen door de cause-keten van een gelogde throwable; begrensd tegen kringlopen.
         *
         * Vandaag altijd leeg: `meld` geeft de throwable niet aan JUL mee. Het staat er als vangnet
         * voor de idiomatische vorm `log.log(niveau, melding, fout)`, die de melding wél meestuurt.
         */
        fun uitFout(fout: Throwable?): String =
            generateSequence(fout) { it.cause?.takeIf { oorzaak -> oorzaak !== it } }
                .take(10)
                .joinToString { it.message.orEmpty() }

        /** Eén ronde van één bericht dat op gelezen gezet moet worden. */
        fun markeerRonde(magazijn: MagazijnAanleverClient) =
            AanleverService(mapOf(OIN_A to magazijn)).leverAan(ronde(1, gelezen = true))

        fun vangLogregels(actie: () -> Unit): List<LogRecord> {
            val logger = Logger.getLogger(AanleverService::class.java.name)
            val gevangen = mutableListOf<LogRecord>()

            val handler = object : Handler() {
                override fun publish(record: LogRecord) { gevangen += record }

                override fun flush() = Unit

                override fun close() = Unit
            }

            logger.addHandler(handler)

            try {
                actie()
            } finally {
                logger.removeHandler(handler)
            }

            return gevangen
        }

        fun uitkomst(
            aangeboden: Int,
            geslaagd: Int = 0,
            mislukt: Int = 0,
            markeringMislukt: Int = 0,
            zonderBerichtId: Int = 0,
        ) = AanleverResultaat(aangeboden, geslaagd, mislukt, markeringMislukt, zonderBerichtId)
    }
}
