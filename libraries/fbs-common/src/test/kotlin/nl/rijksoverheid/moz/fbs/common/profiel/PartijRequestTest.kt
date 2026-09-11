package nl.rijksoverheid.moz.fbs.common.profiel

import nl.rijksoverheid.moz.fbs.common.identificatie.Bsn
import nl.rijksoverheid.moz.fbs.common.identificatie.Identificatienummer
import nl.rijksoverheid.moz.fbs.common.identificatie.Kvk
import nl.rijksoverheid.moz.fbs.common.identificatie.Oin
import nl.rijksoverheid.moz.fbs.common.identificatie.Rsin
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource

class PartijRequestTest {

    @ParameterizedTest
    @MethodSource("ontvangers")
    fun `elk ontvanger-type krijgt het label van het externe contract`(
        ontvanger: Identificatienummer,
        verwachtType: String,
    ) {
        val aanvraag = PartijRequest.van(ontvanger)

        assertEquals(verwachtType, aanvraag.identificatieType)
        assertEquals(ontvanger.waarde, aanvraag.identificatieNummer)
    }

    @Test
    fun `een OIN-ontvanger hoort hier nooit te komen`() {
        // De Profiel-service kent OIN niet; beide call-sites vangen dat eerder af. Hard falen
        // is beter dan een aanvraag met een type dat upstream niet bestaat.
        assertThrows(IllegalStateException::class.java) {
            PartijRequest.van(Oin("00000001003214345000"))
        }
    }

    @Test
    fun `toString draagt het identificatienummer niet`() {
        // Zonder de override drukt de data class het nummer voluit af zodra dit object in een
        // logregel of foutmelding belandt — voor een burger het BSN.
        val weergave = PartijRequest.van(Bsn("999993653")).toString()

        assertFalse(weergave.contains("999993653"), "identificatienummer mag niet in toString — gevonden: $weergave")
        assertEquals(true, weergave.contains("BSN"), "type mag er wél in, dat is geen PII — gevonden: $weergave")
    }

    companion object {

        @JvmStatic
        fun ontvangers() = listOf(
            org.junit.jupiter.params.provider.Arguments.of(Bsn("999993653"), "BSN"),
            org.junit.jupiter.params.provider.Arguments.of(Rsin("002564440"), "RSIN"),
            org.junit.jupiter.params.provider.Arguments.of(Kvk("12345678"), "KVK"),
        )
    }
}
