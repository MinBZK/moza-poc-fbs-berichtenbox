package nl.rijksoverheid.moz.fbs.magazijnsimulator.magazijn

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import org.junit.jupiter.params.provider.ValueSource
import java.net.URI

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

    /**
     * Encoding-randen. Het filter werkt op het onbewerkte pad, en dat is precies waarom: op de
     * gedecodeerde vorm zou `%2F` een extra scheidingsteken worden en zou `/%6Dagazijn/…` alsnog als
     * magazijn-pad tellen. Allebei horen ze de veilige kant op te vallen.
     */
    @ParameterizedTest
    @ValueSource(strings = ["/MAGAZIJN/$OIN/api/v1/berichten", "/magazijn/$OIN/API/V1/berichten"])
    fun `een afwijkende schrijfwijze telt niet als magazijn-pad`(pad: String) {
        assertNull(MagazijnPad.oinUit(pad))
    }

    @Test
    fun `een gecodeerd scheidingsteken in een segment splitst het pad niet`() {
        // Gedecodeerd zou dit `/berichten/a/b` worden; onbewerkt blijft het één segment, net als bij
        // een echt magazijn.
        assertEquals("/berichten/a%2Fb", MagazijnPad.padNaPrefix("/magazijn/$OIN/api/v1/berichten/a%2Fb", OIN))
    }

    /**
     * Loopt de herkenning (gedecodeerd) uit de pas met het herschrijven (onbewerkt), dan hoort er
     * niets weggeknipt te worden: het prefix blijft staan, geen resource matcht, en dat is een 404.
     * De gevaarlijke uitkomst zou zijn dat er wél iets wordt weggeknipt en het request bij een ánder
     * magazijn belandt.
     */
    @Test
    fun `een gecodeerde OIN laat het prefix staan in plaats van er half af te knippen`() {
        val requestUri = URI("http://simulator:8092/magazijn/%30" + OIN.substring(1) + "/api/v1/berichten")

        assertEquals(requestUri, MagazijnPad.zonderPrefix(requestUri, OIN))
    }

    @Test
    fun `dubbele slashes na de root maken er geen OIN van`() {
        assertNull(MagazijnPad.oinUit("/magazijn//$OIN/api/v1/berichten"))
    }

    @Test
    fun `het herschrijven behoudt de query en haalt alleen het prefix weg`() {
        val requestUri = URI("http://simulator:8092/magazijn/$OIN/api/v1/berichten?page=2&pageSize=5")

        assertEquals(
            URI("http://simulator:8092/berichten?page=2&pageSize=5"),
            MagazijnPad.zonderPrefix(requestUri, OIN),
        )
    }

    @Test
    fun `het herschrijven laat een gecodeerd segment ongemoeid`() {
        val requestUri = URI("http://simulator:8092/magazijn/$OIN/api/v1/berichten/a%2Fb")

        assertEquals(URI("http://simulator:8092/berichten/a%2Fb"), MagazijnPad.zonderPrefix(requestUri, OIN))
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
