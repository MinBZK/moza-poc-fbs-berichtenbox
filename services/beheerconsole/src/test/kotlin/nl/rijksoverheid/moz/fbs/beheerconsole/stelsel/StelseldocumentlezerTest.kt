package nl.rijksoverheid.moz.fbs.beheerconsole.stelsel

import nl.rijksoverheid.moz.fbs.beheerconsole.stelsel.Stelseldocumentlezer.Uitkomst
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import org.junit.jupiter.params.provider.ValueSource
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.Base64
import java.util.Optional

/** De zes verificatiestappen uit de spec van het stelselregister, telkens met de stap die faalt. */
class StelseldocumentlezerTest {

    // De klok van de run: de testketens gaan in op "gisteren", gerekend vanaf de run.
    private val nu = Instant.now().truncatedTo(ChronoUnit.SECONDS)

    private fun lezer(root: String? = Testketen.geldig.rootPem) = Stelseldocumentlezer(
        object : StelseldocumentConfig {
            override fun root(): Optional<String> = Optional.ofNullable(root)
            override fun uitgever(): String = Testketen.OIN
            override fun omgeving(): String = Testketen.OMGEVING
            override fun unsafeZonderRoot(): Boolean = false
        },
    )

    private fun reden(gelezen: Stelseldocumentlezer.Gelezen): String =
        (gelezen.uitkomst as? Uitkomst.Ongeldig)?.reden ?: error("verwacht ongeldig, kreeg ${gelezen.uitkomst}")

    @ParameterizedTest
    @ValueSource(ints = [0, 1, 3])
    fun `een geldig document levert elke organisatie`(aantal: Int) {
        val organisaties = List(aantal) { Triple("0000000100321434500$it", "Organisatie $it", "https://m$it.example/") }

        val gelezen = lezer().lees(Testketen.geldig.document(nu, organisaties = organisaties), nu)

        val inhoud = requireNotNull(gelezen.inhoud)
        val ondertekening = requireNotNull(gelezen.ondertekening)

        assertEquals(Uitkomst.Geldig, gelezen.uitkomst)
        assertEquals(organisaties.map { it.first }, inhoud.organisaties.map { it.oin })
        assertEquals(nu.plusSeconds(3600), inhoud.geldigTot)
        assertEquals("test-kid", ondertekening.kid)
        assertEquals("CN=Test-ondertekenaar,SERIALNUMBER=${Testketen.OIN}", ondertekening.onderwerp)
        assertEquals(Testketen.geldig.certificaat.notAfter.toInstant(), ondertekening.certificaatGeldigTot)
    }

    @Test
    fun `zonder root is het document niet getoetst, maar wel te lezen`() {
        val gelezen = lezer(root = null).lees(Testketen.ander.document(nu), nu)

        assertTrue(gelezen.uitkomst is Uitkomst.NietGetoetst, gelezen.uitkomst.toString())
        assertNotNull(gelezen.inhoud)
    }

    @Test
    fun `stap 1, een extra headerveld`() {
        assertTrue("andere velden" in reden(lezer().lees(Testketen.geldig.document(nu, extraHeader = mapOf("jku" to "https://x")), nu)))
    }

    @Test
    fun `stap 2, een keten onder een andere root`() {
        assertTrue("root" in reden(lezer().lees(Testketen.ander.document(nu), nu)))
    }

    @Test
    fun `stap 3, een gewijzigde payload`() {
        val delen = Testketen.geldig.document(nu).split('.')
        val payload = String(Base64.getUrlDecoder().decode(delen[1])).replace("Organisatie A", "Organisatie B")
        val vervalst = listOf(delen[0], Base64.getUrlEncoder().withoutPadding().encodeToString(payload.toByteArray()), delen[2])

        assertTrue("handtekening" in reden(lezer().lees(vervalst.joinToString("."), nu)))
    }

    @Test
    fun `stap 4, een certificaat van een andere organisatie onder dezelfde root`() {
        val gelezen = Stelseldocumentlezer(
            object : StelseldocumentConfig {
                override fun root(): Optional<String> = Optional.of(Testketen.andereUitgever.rootPem)
                override fun uitgever(): String = Testketen.OIN
                override fun omgeving(): String = Testketen.OMGEVING
                override fun unsafeZonderRoot(): Boolean = false
            },
        ).lees(Testketen.andereUitgever.document(nu), nu)

        assertTrue("stelselbeheerder" in reden(gelezen))
    }

    @Test
    fun `stap 4, iss is een ander dan het certificaat`() {
        assertTrue("iss" in reden(lezer().lees(Testketen.geldig.document(nu, uitgever = "00000000000000009999"), nu)))
    }

    @Test
    fun `stap 5, een andere omgeving`() {
        assertTrue("omgeving" in reden(lezer().lees(Testketen.geldig.document(nu, omgeving = "productie"), nu)))
    }

    // Grenzen van stap 6, elk met een verse lezer: de rollback-toestand speelt hier niet mee.
    @ParameterizedTest
    @CsvSource(
        "60, 3600, ",
        "61, 3600, toekomst",
        "0, 86400, ",
        "0, 86401, 24 uur",
        "-3600, 3600, verlopen",
        "-3599, 3600, ",
        "0, 0, 24 uur",
    )
    fun `stap 6, de grenzen van tijd en geldigheid`(verschuiving: Long, geldigheid: Long, verwacht: String?) {
        val gelezen = lezer().lees(Testketen.geldig.document(nu.plusSeconds(verschuiving), geldigheidSeconden = geldigheid), nu)

        if (verwacht == null) {
            assertEquals(Uitkomst.Geldig, gelezen.uitkomst)
        } else {
            assertTrue(verwacht in reden(gelezen), reden(gelezen))
        }
    }

    @Test
    fun `stap 6, hetzelfde exemplaar opnieuw lezen blijft geldig, een ouder niet`() {
        val lezer = lezer()
        val document = Testketen.geldig.document(nu)

        assertEquals(Uitkomst.Geldig, lezer.lees(document, nu).uitkomst)
        assertEquals(Uitkomst.Geldig, lezer.lees(document, nu).uitkomst)
        assertTrue("ouder" in reden(lezer.lees(Testketen.geldig.document(nu.minusSeconds(1)), nu)))
    }

    @Test
    fun `witruimte om het document verandert niets`() {
        assertEquals(Uitkomst.Geldig, lezer().lees("  " + Testketen.geldig.document(nu) + "\n", nu).uitkomst)
    }

    // PKIX controleert het sleutelgebruik van het laatste certificaat niet; dat doet stap 2 zelf.
    @Test
    fun `stap 2, het eerste certificaat moet een ondertekencertificaat zijn`() {
        listOf(Testketen.leafIsCa, Testketen.zonderSleutelgebruik, Testketen.alleenSleutelafspraak).forEach { keten ->
            val gelezen = Stelseldocumentlezer(
                object : StelseldocumentConfig {
                    override fun root(): Optional<String> = Optional.of(keten.rootPem)
                    override fun uitgever(): String = Testketen.OIN
                    override fun omgeving(): String = Testketen.OMGEVING
                    override fun unsafeZonderRoot(): Boolean = false
                },
            ).lees(keten.document(nu), nu)

            assertEquals("het eerste certificaat is geen ondertekencertificaat", reden(gelezen))
        }
    }

    // De header komt vóór de root: ook zonder root is een header met de verkeerde vorm ongeldig.
    @ParameterizedTest
    @ValueSource(strings = ["\"alg\":\"HS256\"", "\"alg\":\"none\"", "\"typ\":\"JWT\"", "\"x5c\":[]", "\"x5c\":[\"bm9wZQ\"]"])
    fun `stap 1, een header met de verkeerde vorm`(vervanging: String) {
        val delen = Testketen.geldig.document(nu).split('.')
        val header = String(Base64.getUrlDecoder().decode(delen[0]))
        val sleutel = vervanging.substringBefore(':')
        val aangepast = header.replace(Regex("$sleutel:(\\[[^]]*]|\"[^\"]*\")"), vervanging)
        val document = listOf(Base64.getUrlEncoder().withoutPadding().encodeToString(aangepast.toByteArray()), delen[1], delen[2])
            .joinToString(".")

        listOf(lezer(), lezer(root = null)).forEach { lezer ->
            assertTrue(lezer.lees(document, nu).uitkomst is Uitkomst.Ongeldig, "$vervanging: $header")
        }
    }

    @ParameterizedTest
    @ValueSource(strings = ["", "geen-jws", "a.b", "a.b.c.d", "e30.bm9wZQ.x"])
    fun `onleesbare invoer levert alleen een reden`(invoer: String) {
        val gelezen = lezer().lees(invoer, nu)

        assertTrue(gelezen.uitkomst is Uitkomst.Ongeldig, gelezen.uitkomst.toString())
        assertNull(gelezen.inhoud)
    }
}
