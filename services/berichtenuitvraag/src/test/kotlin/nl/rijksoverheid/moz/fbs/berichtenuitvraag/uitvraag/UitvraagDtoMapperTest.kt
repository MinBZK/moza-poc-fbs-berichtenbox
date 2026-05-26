package nl.rijksoverheid.moz.fbs.berichtenuitvraag.uitvraag

import nl.rijksoverheid.moz.fbs.berichtenuitvraag.api.model.BerichtPatch
import nl.rijksoverheid.moz.fbs.berichtenuitvraag.api.model.BerichtStatus
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

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
    fun `lege patch geeft lege magazijn-patch`() {
        val patch = BerichtPatch()

        val magazijnPatch = UitvraagDtoMapper.toMagazijnPatch(patch)

        assertNull(magazijnPatch.gelezen)
        assertNull(magazijnPatch.map)
    }
}
