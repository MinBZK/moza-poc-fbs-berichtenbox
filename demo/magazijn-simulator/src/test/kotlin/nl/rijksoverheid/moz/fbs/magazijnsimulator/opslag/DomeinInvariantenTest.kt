package nl.rijksoverheid.moz.fbs.magazijnsimulator.opslag

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Test
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
    fun `een bijlage zonder naam of mimetype wordt geweigerd`(leeg: String) {
        assertThrows<DomeinFout> { bijlage(naam = leeg) }
        assertThrows<DomeinFout> { bijlage(mimeType = leeg) }
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
}
