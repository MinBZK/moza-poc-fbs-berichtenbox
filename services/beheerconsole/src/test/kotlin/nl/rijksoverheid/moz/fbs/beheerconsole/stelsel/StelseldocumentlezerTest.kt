package nl.rijksoverheid.moz.fbs.beheerconsole.stelsel

import nl.rijksoverheid.moz.fbs.beheerconsole.stelsel.Stelseldocumentlezer.Uitkomst
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.time.Instant
import java.util.Base64
import java.util.Optional

/** De zes verificatiestappen uit de spec van het stelselregister, telkens met de stap die faalt. */
class StelseldocumentlezerTest {

    private val nu = Instant.parse("2026-11-27T12:00:00Z")

    private fun lezer(root: String? = Testketen.geldig.rootPem) = Stelseldocumentlezer(
        object : StelseldocumentConfig {
            override fun root(): Optional<String> = Optional.ofNullable(root)
            override fun uitgever(): String = Testketen.OIN
            override fun omgeving(): String = Testketen.OMGEVING
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

    @Test
    fun `stap 6, verlopen, in de toekomst, te lang geldig en teruggedraaid`() {
        val lezer = lezer()

        assertTrue("verlopen" in reden(lezer.lees(Testketen.geldig.document(nu.minusSeconds(7200)), nu)))
        assertTrue("toekomst" in reden(lezer.lees(Testketen.geldig.document(nu.plusSeconds(120)), nu)))
        assertTrue("24 uur" in reden(lezer.lees(Testketen.geldig.document(nu, geldigheidSeconden = 25 * 3600), nu)))
        assertEquals(Uitkomst.Geldig, lezer.lees(Testketen.geldig.document(nu), nu).uitkomst)
        assertTrue("ouder" in reden(lezer.lees(Testketen.geldig.document(nu.minusSeconds(10)), nu)))
    }

    @ParameterizedTest
    @ValueSource(strings = ["", "geen-jws", "a.b", "a.b.c.d", "e30.bm9wZQ.x"])
    fun `onleesbare invoer levert alleen een reden`(invoer: String) {
        val gelezen = lezer().lees(invoer, nu)

        assertTrue(gelezen.uitkomst is Uitkomst.Ongeldig, gelezen.uitkomst.toString())
        assertNull(gelezen.inhoud)
    }
}
