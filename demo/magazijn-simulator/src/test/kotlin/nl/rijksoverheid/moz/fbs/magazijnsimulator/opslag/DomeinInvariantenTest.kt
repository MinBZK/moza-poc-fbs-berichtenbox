package nl.rijksoverheid.moz.fbs.magazijnsimulator.opslag

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertDoesNotThrow
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import org.junit.jupiter.params.provider.ValueSource
import java.time.Instant
import java.util.UUID

/**
 * De invarianten die de simulator afdwingt bovenop wat de spec al doet.
 *
 * Ze zijn er niet voor de netheid: het echte magazijn weigert deze invoer met 400, en een simulator
 * die hem accepteert laat in een demo een aanlevering slagen die in werkelijkheid faalt. Dat is
 * precies het soort verschil dat pas opvalt als iemand het in het echt probeert.
 */
class DomeinInvariantenTest {

    @ParameterizedTest
    @CsvSource("BSN,999993653", "RSIN,002564440", "KVK,12345678", "OIN,00000001003214345000")
    fun `een geldig nummer per type wordt geaccepteerd`(type: String, waarde: String) {
        assertEquals(waarde, Identificatie(IdentificatieType.valueOf(type), waarde).waarde)
    }

    @ParameterizedTest
    @CsvSource(
        // Elfproef niet doorstaan
        "BSN,123456789",
        "RSIN,123456789",
        // Verkeerde lengte
        "BSN,12345678",
        "KVK,123456789",
        "OIN,1234567890123456789",
        // Geen cijfers
        "BSN,12345678a",
        // Geheel nullen
        "KVK,00000000",
        "OIN,00000000000000000000",
    )
    fun `ongeldige nummers worden geweigerd`(type: String, waarde: String) {
        assertThrows<DomeinFout> { Identificatie(IdentificatieType.valueOf(type), waarde) }
    }

    @Test
    fun `de header wordt gesplitst op type en waarde`() {
        assertEquals(
            Identificatie(IdentificatieType.KVK, "12345678"),
            Identificatie.uitHeader("KVK:12345678"),
        )
    }

    @ParameterizedTest
    @ValueSource(strings = ["kvk:12345678", "Kvk:12345678", "ONBEKEND:12345678", "12345678", ""])
    fun `een onbruikbare header wordt geweigerd`(header: String) {
        assertThrows<DomeinFout> { Identificatie.uitHeader(header) }
    }

    /**
     * BSN en RSIN zijn persoonsgegevens en mogen nooit in een applicatielog belanden; KVK en OIN
     * zijn publiek opvraagbaar en blijven juist leesbaar, want daar is diagnose mee te doen.
     */
    @Test
    fun `alleen bsn en rsin worden gemaskeerd in tekstvorm`() {
        assertEquals("BSN:***", Identificatie(IdentificatieType.BSN, "999993653").toString())
        assertEquals("RSIN:***", Identificatie(IdentificatieType.RSIN, "002564440").toString())
        assertEquals("KVK:12345678", Identificatie(IdentificatieType.KVK, "12345678").toString())
    }

    @Test
    fun `een bericht aan zichzelf wordt geweigerd`() {
        val fout = assertThrows<DomeinFout> { bericht(ontvanger = Identificatie(IdentificatieType.OIN, AFZENDER)) }

        assertEquals("Afzender en ontvanger mogen niet hetzelfde nummer hebben", fout.message)
    }

    @ParameterizedTest
    @ValueSource(strings = ["", " ", "\t"])
    fun `een leeg onderwerp wordt geweigerd`(onderwerp: String) {
        assertThrows<DomeinFout> { bericht(onderwerp = onderwerp) }
    }

    @Test
    fun `een onderwerp op de grens mag, eentje erover niet`() {
        bericht(onderwerp = "a".repeat(Bericht.MAX_ONDERWERP_LENGTE))

        assertThrows<DomeinFout> { bericht(onderwerp = "a".repeat(Bericht.MAX_ONDERWERP_LENGTE + 1)) }
    }

    @Test
    fun `lege inhoud wordt geweigerd`() {
        assertThrows<DomeinFout> { bericht(inhoud = "  ") }
    }

    /**
     * De grens is in UTF-8 bytes en niet in tekens. Een emoji van vier bytes hoort dus vier keer zo
     * zwaar te tellen; wie op tekens rekent, laat een bericht van vier megabyte door.
     */
    @Test
    fun `de inhoudgrens telt bytes en geen tekens`() {
        val emoji = "😀"
        val netTeGroot = emoji.repeat(Bericht.MAX_INHOUD_BYTES / 4 + 1)

        assertFalse(netTeGroot.length > Bericht.MAX_INHOUD_BYTES, "de tekenlengte blijft onder de grens")
        assertThrows<DomeinFout> { bericht(inhoud = netTeGroot) }
    }

    @Test
    fun `inhoud precies op de bytegrens mag`() {
        bericht(inhoud = "a".repeat(Bericht.MAX_INHOUD_BYTES))
    }

    @Test
    fun `een afzender die geen OIN is wordt geweigerd`() {
        assertThrows<DomeinFout> { bericht(afzender = Identificatie(IdentificatieType.KVK, "12345678")) }
    }

    @ParameterizedTest
    @ValueSource(strings = ["", " "])
    fun `een lege mapnaam wordt geweigerd`(map: String) {
        assertThrows<DomeinFout> { BerichtStatus(gelezen = true, map = map, gewijzigdOp = Instant.EPOCH) }
    }

    @Test
    fun `een mapnaam op de grens mag, eentje erover niet`() {
        BerichtStatus(true, "a".repeat(BerichtStatus.MAX_MAPNAAM_LENGTE), Instant.EPOCH)

        assertThrows<DomeinFout> {
            BerichtStatus(true, "a".repeat(BerichtStatus.MAX_MAPNAAM_LENGTE + 1), Instant.EPOCH)
        }
    }

    @Test
    fun `geen map is geldig en betekent iets anders dan een lege map`() {
        assertEquals(null, BerichtStatus(gelezen = true, map = null, gewijzigdOp = Instant.EPOCH).map)
    }

    @Test
    fun `een lege bijlage wordt geweigerd`() {
        assertThrows<DomeinFout> { bijlage(inhoud = ByteArray(0)) }
    }

    @Test
    fun `een bijlage boven de grens wordt geweigerd`() {
        assertThrows<DomeinFout> { bijlage(inhoud = ByteArray(Bijlage.MAX_INHOUD_BYTES + 1)) }
    }

    @ParameterizedTest
    @ValueSource(strings = ["", " "])
    fun `een aanlevering zonder naam of mimetype wordt geweigerd`(leeg: String) {
        assertThrows<DomeinFout> { Bijlage.valideerVorm(leeg, "application/pdf") }
        assertThrows<DomeinFout> { Bijlage.valideerVorm("a.pdf", leeg) }
    }

    /** Twee bijlagen met dezelfde bytes horen gelijk te zijn; een kale array vergelijkt op referentie. */
    @Test
    fun `bijlagen vergelijken op inhoud en niet op referentie`() {
        val id = UUID.randomUUID()

        assertEquals(
            Bijlage(id, "a.pdf", "application/pdf", byteArrayOf(1, 2, 3)),
            Bijlage(id, "a.pdf", "application/pdf", byteArrayOf(1, 2, 3)),
        )
    }

    @ParameterizedTest
    @CsvSource("0,20,0", "1,20,1", "20,20,1", "21,20,2", "40,20,2", "41,20,3")
    fun `het aantal paginas rondt naar boven af`(totaal: Long, pageSize: Int, verwacht: Int) {
        assertEquals(verwacht, BerichtenPagina(emptyList(), 0, pageSize, totaal).totalPages)
    }

    @ParameterizedTest
    @CsvSource("-1,20", "0,0", "0,-1")
    fun `een onmogelijke pagina wordt geweigerd`(page: Int, pageSize: Int) {
        assertThrows<IllegalArgumentException> { BerichtenPagina(emptyList(), page, pageSize, 0) }
    }

    private fun bericht(
        afzender: Identificatie = Identificatie(IdentificatieType.OIN, AFZENDER),
        ontvanger: Identificatie = Identificatie(IdentificatieType.KVK, "90000001"),
        onderwerp: String = "Onderwerp",
        inhoud: String = "Inhoud",
    ) = Bericht(
        berichtId = UUID.randomUUID(),
        afzender = afzender,
        ontvanger = ontvanger,
        onderwerp = onderwerp,
        inhoud = inhoud,
        tijdstipOntvangst = Instant.EPOCH,
        publicatietijdstip = Instant.EPOCH,
    )

    private fun bijlage(
        naam: String = "a.pdf",
        mimeType: String = "application/pdf",
        inhoud: ByteArray = byteArrayOf(1),
    ) = Bijlage(UUID.randomUUID(), naam, mimeType, inhoud)

    private companion object {
        const val AFZENDER = "00000009000000000001"
    }

    /**
     * `split(limit = 2)` betekent dat alles ná de eerste dubbele punt de waarde is. Een header met
     * een extra scheidingsteken hoort dus op de vorm van de waarde te stranden, niet op het type —
     * en een leeg deel hoort geen `IndexOutOfBounds` te geven.
     */
    @ParameterizedTest
    @ValueSource(strings = ["KVK:", "BSN:999993653:extra", ":90000001", "KVK"])
    fun `een misvormde X-Ontvanger levert een domeinfout`(header: String) {
        assertThrows<DomeinFout> { Identificatie.uitHeader(header) }
    }

    /**
     * De melding mag de aangeboden waarde niet echoën: op het beheerpad komt deze functie langs een
     * lijst die geen spec-regex heeft gepasseerd, en een omgekeerd getypte `123456782:BSN` zou
     * anders een BSN in een foutantwoord zetten.
     */
    @Test
    fun `de melding bij een onbekend type bevat de aangeboden waarde niet`() {
        val fout = assertThrows<DomeinFout> { Identificatie.uitHeader("123456782:BSN") }

        assertFalse(fout.message.orEmpty().contains("123456782"), "de melding hoort geen invoer te echoën")
    }

    /** OIN is een publiek organisatienummer; alleen BSN en RSIN worden gemaskeerd. */
    @Test
    fun `een OIN blijft in toString leesbaar`() {
        assertEquals(
            "OIN:00000009000000000001",
            Identificatie(IdentificatieType.OIN, "00000009000000000001").toString(),
        )
    }

    /**
     * De vorm van een MIME-type wordt bij het aanleveren getoetst en niet pas bij het downloaden.
     * Zonder die controle slaagt een aanlevering met 201 en is de bijlage daarna onophaalbaar.
     */
    @ParameterizedTest
    @ValueSource(strings = ["kaas", "application", "application/", "/pdf", "application/pdf;", "application/pdf; ="])
    fun `een bijlage met een onbruikbaar MIME-type wordt geweigerd`(mimeType: String) {
        assertThrows<DomeinFout> { Bijlage.valideerVorm("bijlage.pdf", mimeType) }
    }

    /**
     * Parameters horen bij een mediatype. Het echte magazijn accepteert `text/plain; charset=utf-8`
     * en de spec kent alleen een lengtegrens, dus een simulator die het weigert is op zijn
     * aanleverpad van buiten te herkennen — precies wat deze module moet uitsluiten.
     */
    @ParameterizedTest
    @ValueSource(
        strings = [
            "application/pdf",
            "text/plain; charset=utf-8",
            "text/plain;charset=utf-8",
            "application/vnd.api+json; version=1; charset=utf-8",
            "application/pdf; name=\"twee woorden\"",
        ],
    )
    fun `een bijlage met een geldig MIME-type wordt aanvaard`(mimeType: String) {
        assertDoesNotThrow { Bijlage.valideerVorm("bijlage.pdf", mimeType) }
    }

    /**
     * Het leespad toetst de vorm níét — niet op de projectie en niet op de bijlage met bytes. Een rij
     * die een latere regel niet haalt is geen invoerfout van wie de lijst opvraagt; hem bij het
     * teruglezen afkeuren zou van één kapotte bijlage een 400 voor de hele pagina maken. Het
     * download-pad verdedigt zich daar zelf tegen, met een 500.
     */
    @Test
    fun `een bijlage uit de opslag mag een vorm hebben die bij het aanleveren geweigerd zou zijn`() {
        assertDoesNotThrow { BijlageMetadata(UUID.randomUUID(), "  ", "kaas") }
        assertDoesNotThrow { Bijlage(UUID.randomUUID(), "  ", "kaas", "pdf".toByteArray()) }
    }

    /**
     * De mapnaam-regel geldt aan beide kanten. Stond hij alleen op de opgeslagen status, dan zou een
     * lege mapnaam eerst worden weggeschreven en pas bij het teruglezen stuklopen.
     */
    @Test
    fun `een lege mapnaam wordt al bij de wijziging geweigerd`() {
        assertThrows<DomeinFout> { BerichtStatusWijziging(gelezen = null, map = "   ") }
    }
}
