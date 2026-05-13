package nl.rijksoverheid.moz.fbs.berichtenmagazijn.ophaal

import jakarta.ws.rs.core.UriBuilder
import nl.rijksoverheid.moz.fbs.berichtenmagazijn.api.model.Identificatienummer
import nl.rijksoverheid.moz.fbs.berichtenmagazijn.opslag.Bericht
import nl.rijksoverheid.moz.fbs.berichtenmagazijn.opslag.BerichtStatus
import nl.rijksoverheid.moz.fbs.berichtenmagazijn.opslag.BijlageMetadata
import nl.rijksoverheid.moz.fbs.berichtenmagazijn.opslag.Bsn
import nl.rijksoverheid.moz.fbs.berichtenmagazijn.opslag.Kvk
import nl.rijksoverheid.moz.fbs.berichtenmagazijn.opslag.Oin
import nl.rijksoverheid.moz.fbs.berichtenmagazijn.opslag.PagedBerichten
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID

class BerichtDtoMapperTest {

    private fun baseUri() = UriBuilder.fromUri("https://magazijn.example.com")

    private fun bericht(
        bijlagen: List<BijlageMetadata> = emptyList(),
        status: BerichtStatus? = null,
    ): Bericht = Bericht(
        berichtId = UUID.fromString("11111111-1111-1111-1111-111111111111"),
        afzender = Oin("00000001003214345000"),
        ontvanger = Bsn("999993653"),
        onderwerp = "Aanslag",
        inhoud = "Tekst",
        tijdstipOntvangst = Instant.parse("2026-05-13T10:00:00Z"),
        bijlagen = bijlagen,
        status = status,
    )

    @Test
    fun `toBerichtMetInhoud zet alle velden en self-link`() {
        val bijlageId = UUID.fromString("22222222-2222-2222-2222-222222222222")
        val dto = BerichtDtoMapper.toBerichtMetInhoud(
            bericht(
                bijlagen = listOf(BijlageMetadata(bijlageId, "doc.pdf", "application/pdf")),
                status = BerichtStatus(gelezen = true, map = "archief", gewijzigdOp = Instant.now()),
            ),
            baseUri(),
        )

        assertEquals(UUID.fromString("11111111-1111-1111-1111-111111111111"), dto.berichtId)
        assertEquals("00000001003214345000", dto.afzender)
        assertEquals(Identificatienummer.TypeEnum.BSN, dto.ontvanger.type)
        assertEquals("999993653", dto.ontvanger.waarde)
        assertEquals("Aanslag", dto.onderwerp)
        assertEquals("Tekst", dto.inhoud)
        assertEquals(1, dto.bijlagen.size)
        assertEquals(bijlageId, dto.bijlagen[0].bijlageId)
        assertEquals(true, dto.status.gelezen)
        assertEquals("archief", dto.status.map)
        assertNotNull(dto.links.self.href)
        assertTrue(dto.links.self.href.toString().endsWith("/api/v1/berichten/11111111-1111-1111-1111-111111111111"))
        assertTrue(
            dto.bijlagen[0].links.self.href.toString()
                .contains("/berichten/11111111-1111-1111-1111-111111111111/bijlagen/22222222-2222-2222-2222-222222222222"),
        )
    }

    @Test
    fun `toBerichtMetInhoud zonder status geeft null terug`() {
        val dto = BerichtDtoMapper.toBerichtMetInhoud(bericht(), baseUri())
        assertNull(dto.status)
    }

    @Test
    fun `toBerichtenLijst maakt pagina-links inclusief next en prev`() {
        val pagina = PagedBerichten(
            berichten = listOf(bericht(), bericht()),
            page = 1,
            pageSize = 10,
            totalElements = 35L,
        )
        val dto = BerichtDtoMapper.toBerichtenLijst(pagina, Bsn("999993653"), afzender = null, baseUri())

        assertEquals(1, dto.page)
        assertEquals(10, dto.pageSize)
        assertEquals(35L, dto.totalElements)
        assertEquals(4, dto.totalPages) // ceil(35/10) = 4
        assertEquals(2, dto.berichten.size)
        val links = dto.links
        assertTrue(links.self.href.toString().contains("page=1"))
        assertTrue(links.first.href.toString().contains("page=0"))
        assertTrue(links.last.href.toString().contains("page=3"))
        assertTrue(links.prev.href.toString().contains("page=0"))
        assertTrue(links.next.href.toString().contains("page=2"))
    }

    @Test
    fun `toBerichtenLijst zonder volgende pagina laat next weg`() {
        val pagina = PagedBerichten(
            berichten = listOf(bericht()),
            page = 0,
            pageSize = 10,
            totalElements = 1L,
        )
        val dto = BerichtDtoMapper.toBerichtenLijst(pagina, Bsn("999993653"), afzender = null, baseUri())
        assertNull(dto.links.next)
        assertNull(dto.links.prev)
    }

    @Test
    fun `toBerichtenLijst voegt afzender query-param toe als die is gegeven`() {
        val pagina = PagedBerichten(berichten = emptyList(), page = 0, pageSize = 10, totalElements = 0L)
        val dto = BerichtDtoMapper.toBerichtenLijst(pagina, Bsn("999993653"), afzender = "00000001003214345000", baseUri())
        assertTrue(dto.links.self.href.toString().contains("afzender=00000001003214345000"))
    }

    @Test
    fun `mapper ondersteunt elk Identificatienummer-type voor ontvanger`() {
        val dto = BerichtDtoMapper.toBerichtMetInhoud(
            bericht().copy(ontvanger = Kvk("12345678")),
            baseUri(),
        )
        assertEquals(Identificatienummer.TypeEnum.KVK, dto.ontvanger.type)
        assertEquals("12345678", dto.ontvanger.waarde)
    }
}
