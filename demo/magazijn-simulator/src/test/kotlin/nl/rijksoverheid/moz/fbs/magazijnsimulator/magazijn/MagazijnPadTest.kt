package nl.rijksoverheid.moz.fbs.magazijnsimulator.magazijn

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import org.junit.jupiter.params.provider.ValueSource

/**
 * Het herkennen van het magazijn-prefix, los van HTTP. De integratiekant staat in
 * [MagazijnPadFilterTest]; hier gaat het om de randen van de pad-vorm zelf, waar een fout stil
 * blijft: een pad dat nét geen magazijn-pad is en toch geaccepteerd wordt, komt bij een willekeurig
 * magazijn uit.
 */
class MagazijnPadTest {

    @ParameterizedTest
    @CsvSource(
        "/magazijn/$OIN/api/v1/berichten,$OIN",
        "magazijn/$OIN/api/v1/berichten,$OIN",
        "/magazijn/$OIN/api/v1/berichten/$UUID/bijlagen/$UUID,$OIN",
        "/magazijn/$OIN/api/v1/aanleveringen,$OIN",
        // Geen OIN-vorm, maar wel de pad-vorm: of dit magazijn bestaat is een vraag voor de set,
        // niet voor de pad-ontleding. Twee bronnen van waarheid over wat een OIN is zouden
        // onvermijdelijk uiteenlopen.
        "/magazijn/12345/api/v1/berichten,12345",
    )
    fun `de OIN komt uit het tweede segment`(pad: String, verwacht: String) {
        assertEquals(verwacht, MagazijnPad.oinUit(pad))
    }

    @ParameterizedTest
    @ValueSource(
        strings = [
            "/berichten",
            "/api/v1/berichten",
            "/magazijn",
            "/magazijn/",
            "/magazijn/$OIN",
            "/magazijn/$OIN/",
            "/magazijn/$OIN/berichten",
            "/magazijn/$OIN/api/berichten",
            "/magazijn/$OIN/api/v2/berichten",
            // Wél het juiste prefix, maar geen operatie erachter.
            "/magazijn/$OIN/api/v1",
            "/magazijn/$OIN/api/v1/",
            // Lijkt op de root maar is het niet — een filter dat op `startsWith("magazijn")` zou
            // matchen, pikt dit ten onrechte op.
            "/magazijnen/$OIN/api/v1/berichten",
        ],
    )
    fun `alles wat geen magazijn-pad is levert geen OIN op`(pad: String) {
        assertNull(MagazijnPad.oinUit(pad))
    }

    @ParameterizedTest
    @CsvSource(
        "/magazijn/$OIN/api/v1/berichten,/berichten",
        "magazijn/$OIN/api/v1/berichten,/berichten",
        "/magazijn/$OIN/api/v1/berichten/$UUID,/berichten/$UUID",
        "/magazijn/$OIN/api/v1/aanleveringen,/aanleveringen",
    )
    fun `het prefix gaat er af en de rest blijft heel`(pad: String, verwacht: String) {
        assertEquals(verwacht, MagazijnPad.padNaPrefix(pad, OIN))
    }

    @Test
    fun `de vorm in de foutmelding noemt de root en het base-path`() {
        assertEquals("/magazijn/<OIN>/api/v1/…", MagazijnPad.VORM)
    }

    private companion object {
        const val OIN = "00000009000000000001"
        const val UUID = "11111111-2222-3333-4444-555555555555"
    }
}
