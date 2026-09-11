package nl.rijksoverheid.moz.fbs.democonsole.opstartvulling

import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import nl.rijksoverheid.moz.fbs.democonsole.aanlever.AanleverResultaat
import nl.rijksoverheid.moz.fbs.democonsole.aanlever.AanleverService
import nl.rijksoverheid.moz.fbs.democonsole.aanlever.Faalreden
import nl.rijksoverheid.moz.fbs.democonsole.dataset.Basisdataset
import nl.rijksoverheid.moz.fbs.democonsole.generator.AanleverOpdracht
import nl.rijksoverheid.moz.fbs.democonsole.generator.AanleverVerzoek
import nl.rijksoverheid.moz.fbs.democonsole.generator.OntvangerDto
import nl.rijksoverheid.moz.fbs.democonsole.legen.MagazijnDatabase
import nl.rijksoverheid.moz.fbs.democonsole.opstartvulling.MagazijnOpstartvulling.Uitkomst
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.sql.SQLException
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset

private class TestKlok(var nu: Instant = Instant.parse("2026-09-11T10:00:00Z")) : Clock() {

    override fun getZone(): ZoneId = ZoneOffset.UTC

    override fun withZone(zone: ZoneId): Clock = this

    override fun instant(): Instant = nu
}

/**
 * De magazijnen en de aanlevering zijn hier een klein nagebootst geheel: een geslaagde aanlevering
 * telt op bij het magazijn, zodat "al gevuld" en "dubbel gevuld" aan de telling af te lezen zijn en
 * niet alleen aan aanroepen.
 */
class MagazijnOpstartvullingTest {

    private val config = mockk<OpstartvullingConfig>()
    private val database = mockk<MagazijnDatabase>()
    private val basisdataset = mockk<Basisdataset>()
    private val aanleverService = mockk<AanleverService>()
    private val klok = TestKlok()

    /** Berichten per magazijn; een ontbrekende OIN is een database die nog niet te lezen is. */
    private val aantallen = mutableMapOf(RVO to 0, BELASTINGDIENST to 0)

    private val dataset = mutableListOf<AanleverOpdracht>()

    /** Welke opdrachten het magazijn weigert. */
    private var weigert: (AanleverOpdracht) -> Boolean = { false }

    /** Elke aanroep van de aanlevering, met de opdrachten die erin gingen. */
    private val aanroepen = mutableListOf<List<AanleverOpdracht>>()

    private val vulling = MagazijnOpstartvulling(config, database, basisdataset, aanleverService, klok)

    init {
        every { config.actief() } returns true
        every { config.interval() } returns Duration.ofSeconds(20)
        every { config.opgevenNa() } returns OPGEVEN_NA
        every { basisdataset.laad() } answers { dataset.toList() }

        every { database.aantalVoor(any()) } answers {
            aantallen[firstArg<String>()] ?: throw SQLException("relation \"berichten\" does not exist")
        }

        every { aanleverService.leverAan(any()) } answers {
            val opdrachten = firstArg<List<AanleverOpdracht>>()
            val (geweigerd, aangekomen) = opdrachten.partition(weigert)

            aanroepen.add(opdrachten)
            aangekomen.forEach { aantallen.merge(it.magazijnOin, 1, Int::plus) }

            AanleverResultaat.van(
                aangeboden = opdrachten.size,
                geslaagd = aangekomen.size,
                markeringMislukt = 0,
                zonderBerichtId = 0,
                redenen = geweigerd.map { Faalreden.onbereikbaar(it.magazijnOin) },
            )
        }
    }

    @Test
    fun `een lege omgeving krijgt in elk magazijn zijn eigen deel van de basisdataset`() {
        // Door elkaar, zoals basis.json de bakken ook herhaalt: een vulling die niet per magazijn
        // filtert zet dan het ene magazijn in het andere.
        dataset += opdrachten(RVO, 3).zip(opdrachten(BELASTINGDIENST, 3)).flatMap { it.toList() }.dropLast(1)

        val uitkomsten = vulling.ronde()

        assertEquals(mapOf(RVO to Uitkomst.Geplaatst(3, 3), BELASTINGDIENST to Uitkomst.Geplaatst(2, 2)), uitkomsten)
        assertEquals(mapOf(RVO to 3, BELASTINGDIENST to 2), aantallen)
        aanroepen.forEach { aanroep -> assertTrue(aanroep.map { it.magazijnOin }.distinct().size <= 1, "gemengd: $aanroep") }
    }

    @ParameterizedTest
    @ValueSource(ints = [1, 2, 5])
    fun `het eerste bericht gaat alleen, en de rest pas als het aankwam`(aantal: Int) {
        aantallen[BELASTINGDIENST] = 1
        dataset += opdrachten(RVO, aantal)

        vulling.ronde()

        assertEquals(1, aanroepen.first().size)
        assertEquals(aantal, aanroepen.sumOf { it.size })
        assertEquals(aantal, aantallen[RVO])
    }

    @Test
    fun `een magazijn met berichten blijft ongemoeid terwijl het lege naast hem gevuld wordt`() {
        aantallen[RVO] = 7
        dataset += opdrachten(RVO, 3) + opdrachten(BELASTINGDIENST, 2)

        val uitkomsten = vulling.ronde()

        assertEquals(mapOf(RVO to Uitkomst.AlGevuld(7), BELASTINGDIENST to Uitkomst.Geplaatst(2, 2)), uitkomsten)
        assertEquals(7, aantallen[RVO])
        assertTrue(aanroepen.flatten().none { it.magazijnOin == RVO })
    }

    @Test
    fun `een beoordeeld magazijn komt niet terug, ook niet als het daarna geleegd wordt`() {
        // Bewust legen tijdens een demo hoort niet een ronde later ongedaan gemaakt te worden.
        dataset += opdrachten(RVO, 2) + opdrachten(BELASTINGDIENST, 2)
        vulling.ronde()
        aantallen.replaceAll { _, _ -> 0 }

        val tweede = vulling.ronde()

        assertEquals(emptyMap<String, Uitkomst>(), tweede)
        assertEquals(mapOf(RVO to 0, BELASTINGDIENST to 0), aantallen)
    }

    @Test
    fun `een database die nog niet te lezen is komt de volgende ronde terug, en alleen die`() {
        aantallen.remove(RVO)
        dataset += opdrachten(RVO, 2) + opdrachten(BELASTINGDIENST, 2)

        val eerste = vulling.ronde()

        assertTrue(eerste[RVO] is Uitkomst.NogNietKlaar, "kreeg $eerste")
        assertEquals(Uitkomst.Geplaatst(2, 2), eerste[BELASTINGDIENST])

        aantallen[RVO] = 0

        assertEquals(mapOf(RVO to Uitkomst.Geplaatst(2, 2)), vulling.ronde())
        assertEquals(mapOf(RVO to 2, BELASTINGDIENST to 2), aantallen)
    }

    @Test
    fun `een magazijn dat het eerste bericht weigert krijgt de rest niet, en de volgende ronde alles`() {
        aantallen[BELASTINGDIENST] = 1
        dataset += opdrachten(RVO, 4)
        weigert = { true }

        assertTrue(vulling.ronde()[RVO] is Uitkomst.NogNietKlaar)
        assertEquals(listOf(1), aanroepen.map { it.size })

        weigert = { false }

        assertEquals(mapOf(RVO to Uitkomst.Geplaatst(4, 4)), vulling.ronde())
        assertEquals(4, aantallen[RVO])
    }

    @Test
    fun `een deels mislukte vulling wordt niet herhaald, want wat aankwam zou dubbel komen`() {
        aantallen[BELASTINGDIENST] = 1
        dataset += opdrachten(RVO, 4)
        weigert = { it.verzoek.onderwerp == "onderwerp 2" }

        assertEquals(Uitkomst.Geplaatst(4, 3), vulling.ronde()[RVO])
        assertEquals(emptyMap<String, Uitkomst>(), vulling.ronde())
        assertEquals(3, aantallen[RVO])
    }

    @Test
    fun `zonder berichten in de basisdataset valt er voor dat magazijn niets te vullen`() {
        dataset += opdrachten(BELASTINGDIENST, 2)

        assertEquals(Uitkomst.NietsTeVullen, vulling.ronde()[RVO])
        assertEquals(emptyMap<String, Uitkomst>(), vulling.ronde())
    }

    @Test
    fun `uitgeschakeld raakt de console geen magazijn aan`() {
        every { config.actief() } returns false
        dataset += opdrachten(RVO, 2)

        assertEquals(emptyMap<String, Uitkomst>(), vulling.ronde())
        assertEquals(emptyMap<String, Uitkomst>(), vulling.ronde())

        verify(exactly = 0) { database.aantalVoor(any()) }
        verify(exactly = 0) { aanleverService.leverAan(any()) }
    }

    @Test
    fun `precies op de termijn wordt nog geprobeerd, daarna niet meer`() {
        aantallen.remove(RVO)
        aantallen[BELASTINGDIENST] = 1
        dataset += opdrachten(RVO, 2)

        klok.nu = klok.nu.plus(OPGEVEN_NA)

        assertTrue(vulling.ronde()[RVO] is Uitkomst.NogNietKlaar)

        klok.nu = klok.nu.plusSeconds(1)
        aantallen[RVO] = 0

        assertEquals(emptyMap<String, Uitkomst>(), vulling.ronde())

        klok.nu = klok.nu.plusSeconds(20)

        assertEquals(emptyMap<String, Uitkomst>(), vulling.ronde())
        assertEquals(0, aantallen[RVO])
    }

    private fun opdrachten(oin: String, aantal: Int): List<AanleverOpdracht> = List(aantal) { volgnummer ->
        AanleverOpdracht(
            magazijnOin = oin,
            verzoek = AanleverVerzoek(
                afzender = oin,
                ontvanger = OntvangerDto("KVK", "90000001"),
                onderwerp = "onderwerp $volgnummer",
                inhoud = "inhoud",
                publicatietijdstip = "2026-09-11T10:00:00Z",
            ),
        )
    }

    private companion object {

        val RVO = MagazijnDatabase.MAGAZIJN_PER_OIN.entries.first { it.value == "magazijn-a" }.key
        val BELASTINGDIENST = MagazijnDatabase.MAGAZIJN_PER_OIN.entries.first { it.value == "magazijn-b" }.key

        val OPGEVEN_NA: Duration = Duration.ofMinutes(30)
    }
}
