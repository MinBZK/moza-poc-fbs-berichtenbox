package nl.rijksoverheid.moz.fbs.common.bijlage

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource

class BijlageMediaTypeTest {

    @Test
    fun `een gewoon mediatype komt genormaliseerd terug`() {
        assertEquals("application/pdf", BijlageMediaType.parse("application/pdf")?.toString())
    }

    @Test
    fun `parameters blijven behouden`() {
        assertEquals("text/plain;charset=utf-8", BijlageMediaType.parse("text/plain; charset=utf-8")?.toString())
    }

    @ParameterizedTest
    @ValueSource(strings = ["", "geen mediatype", "application", "//"])
    fun `een onparsebare waarde levert null`(mimeType: String) {
        assertNull(BijlageMediaType.parse(mimeType))
    }

    @ParameterizedTest
    @ValueSource(
        strings = [
            "application/pdf;name=\"a\r\nX-Injected: 1\"",
            "application/pdf;name=\"a\nb\"",
            "application/pdf;name=\"a\u0000b\"",
        ],
    )
    fun `een control-teken in een parameter maakt de waarde onbruikbaar`(mimeType: String) {
        // Deze vormen parseren wél, maar de HTTP-laag weigert de headerwaarde pas bij het
        // schrijven van de response — dan is de bijlage onophaalbaar zonder dat iets uitlegt
        // waarom. Hier stoppen betekent een nette fout in plaats van een klap op het eind.
        assertNull(BijlageMediaType.parse(mimeType))
    }
}
