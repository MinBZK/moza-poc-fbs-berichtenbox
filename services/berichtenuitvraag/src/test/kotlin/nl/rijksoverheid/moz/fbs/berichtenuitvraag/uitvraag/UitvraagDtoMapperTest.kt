package nl.rijksoverheid.moz.fbs.berichtenuitvraag.uitvraag

import nl.rijksoverheid.moz.fbs.berichtensessiecache.berichten.Bericht
import nl.rijksoverheid.moz.fbs.berichtensessiecache.berichten.BerichtSamenvatting
import nl.rijksoverheid.moz.fbs.berichtensessiecache.berichten.BijlageSamenvatting
import nl.rijksoverheid.moz.fbs.berichtensessiecache.berichten.Leesstatus
import nl.rijksoverheid.moz.fbs.berichtenuitvraag.api.model.BerichtPatch
import nl.rijksoverheid.moz.fbs.berichtenuitvraag.api.model.BerichtStatus
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID

class UitvraagDtoMapperTest {

    @Test
    fun `status gelezen mapt naar magazijn-gelezen-true`() {
        val patch = BerichtPatch().apply { status = BerichtStatus.GELEZEN }

        val magazijnPatch = UitvraagDtoMapper.toMagazijnPatch(patch)

        assertEquals(true, magazijnPatch.gelezen)
        assertNull(magazijnPatch.map)
    }

    @Test
    fun `status ongelezen mapt naar magazijn-gelezen-false`() {
        val patch = BerichtPatch().apply { status = BerichtStatus.ONGELEZEN }

        val magazijnPatch = UitvraagDtoMapper.toMagazijnPatch(patch)

        assertEquals(false, magazijnPatch.gelezen)
    }

    @Test
    fun `alleen map zonder status laat gelezen leeg`() {
        val patch = BerichtPatch().apply { map = "archief" }

        val magazijnPatch = UitvraagDtoMapper.toMagazijnPatch(patch)

        assertNull(magazijnPatch.gelezen)
        assertEquals("archief", magazijnPatch.map)
    }

    @Test
    fun `status en map samen mappen allebei door`() {
        val patch = BerichtPatch().apply {
            status = BerichtStatus.GELEZEN
            map = "archief"
        }

        val magazijnPatch = UitvraagDtoMapper.toMagazijnPatch(patch)

        assertEquals(true, magazijnPatch.gelezen)
        assertEquals("archief", magazijnPatch.map)
    }

    @Test
    fun `lege patch geeft lege magazijn-patch`() {
        val patch = BerichtPatch()

        val magazijnPatch = UitvraagDtoMapper.toMagazijnPatch(patch)

        assertNull(magazijnPatch.gelezen)
        assertNull(magazijnPatch.map)
    }

    @Test
    fun `toLeesstatus en toApiStatus zijn elkaars inverse`() {
        assertEquals(Leesstatus.GELEZEN, UitvraagDtoMapper.toLeesstatus(BerichtStatus.GELEZEN))
        assertEquals(Leesstatus.ONGELEZEN, UitvraagDtoMapper.toLeesstatus(BerichtStatus.ONGELEZEN))
        assertNull(UitvraagDtoMapper.toLeesstatus(null))
        assertEquals(BerichtStatus.GELEZEN, UitvraagDtoMapper.toApiStatus(Leesstatus.GELEZEN))
        assertEquals(BerichtStatus.ONGELEZEN, UitvraagDtoMapper.toApiStatus(Leesstatus.ONGELEZEN))
        assertNull(UitvraagDtoMapper.toApiStatus(null))
    }

    @Test
    fun `toApiBericht mapt alle velden inclusief bijlagen en self-link`() {
        val id = UUID.randomUUID()
        val bijlageId = UUID.randomUUID()
        val domein = Bericht(
            berichtId = id,
            afzender = "00000001003214345000",
            ontvanger = "999990019",
            onderwerp = "Onderwerp",
            inhoud = "Inhoud",
            publicatietijdstip = Instant.parse("2026-05-26T10:00:00Z"),
            magazijnId = "magazijn-a",
            aantalBijlagen = 1,
            bijlagen = listOf(BijlageSamenvatting(bijlageId, "factuur.pdf")),
            map = "werk",
            status = Leesstatus.GELEZEN,
        )

        val api = UitvraagDtoMapper.toApiBericht(domein)

        assertEquals(id, api.berichtId)
        assertEquals("Onderwerp", api.onderwerp)
        assertEquals("Inhoud", api.inhoud)
        assertEquals("magazijn-a", api.magazijnId)
        assertEquals("werk", api.map)
        assertEquals(BerichtStatus.GELEZEN, api.status)
        assertEquals(bijlageId, api.bijlagen.single().bijlageId)
        assertEquals("factuur.pdf", api.bijlagen.single().naam)
        assertEquals("/api/v1/berichten/$id", api.links.self.href)
    }

    @Test
    fun `toApiSamenvatting laat de ontvanger weg en mapt de rest`() {
        val id = UUID.randomUUID()
        val domein = BerichtSamenvatting(
            berichtId = id,
            afzender = "00000001003214345000",
            ontvanger = "999990019",
            onderwerp = "Onderwerp",
            publicatietijdstip = Instant.parse("2026-05-26T10:00:00Z"),
            magazijnId = "magazijn-b",
            aantalBijlagen = 3,
            map = null,
            status = null,
        )

        val api = UitvraagDtoMapper.toApiSamenvatting(domein)

        assertEquals(id, api.berichtId)
        assertEquals("magazijn-b", api.magazijnId)
        assertEquals(3, api.aantalBijlagen)
        assertNull(api.status)
        assertEquals("/api/v1/berichten/$id", api.links.self.href)
    }
}
