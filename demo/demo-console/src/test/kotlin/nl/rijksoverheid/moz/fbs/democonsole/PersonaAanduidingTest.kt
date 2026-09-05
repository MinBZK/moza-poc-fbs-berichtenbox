package nl.rijksoverheid.moz.fbs.democonsole

import jakarta.ws.rs.BadRequestException
import jakarta.ws.rs.NotFoundException
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource

/**
 * De weigering die twee resources delen. Over HTTP toetst `PaneelContractTest` dat het nummer niet
 * terugkomt; hier staan de randen rechtstreeks op de functie, zonder de omweg van een
 * `@QuarkusTest` — een OIN, de lengtegrens, en een waarde met een regeleinde erin.
 */
class PersonaAanduidingTest {

    @ParameterizedTest
    @ValueSource(
        strings = [BSN, "999-993-653", "999_993_653", "0$BSN", "BSN:$BSN", " $BSN ", KVK, RSIN, "$OIN-$BSN"],
    )
    fun `een aanduiding met een identificatienummer wordt geweigerd zonder hem te herhalen`(waarde: String) {
        val fout = onbekendePersona(waarde, TERUGWEG)

        assertTrue(fout is BadRequestException, "verwachtte een 400 voor '$waarde', kreeg ${fout::class.simpleName}")
        assertFalse(
            fout.message.orEmpty().filter(Char::isDigit).contains(waarde.filter(Char::isDigit)),
            "de melding hoort het aangeboden nummer niet te dragen",
        )
        assertTrue(fout.message.orEmpty().contains(TERUGWEG), "de melding hoort de weg terug te wijzen")
    }

    /**
     * Twintig cijfers is een OIN: publiek, geen PII, en `DemoPersona` staat zo'n id uitdrukkelijk
     * toe. Zonder deze bovengrens gaf dit adres 400 op een id dat de personadienst zelf accepteert
     * — een tweede mening over wat een geldige id is.
     */
    @Test
    fun `een OIN is geen reden om te weigeren`() {
        val fout = onbekendePersona(OIN, TERUGWEG)

        assertTrue(fout is NotFoundException, "een OIN hoort een gewone onbekende persona te zijn")
        assertTrue(fout.message.orEmpty().contains(OIN), "een OIN is publiek en mag in de melding")
    }

    /**
     * `proeftuin-2026-01` draagt zes cijfers en hoort gewoon een 404 te krijgen. Zonder dat geval
     * kan de drempel ongemerkt naar beneden schuiven, en dan leest een bediener bij een vertypte id
     * met een jaartal erin "gebruik een naam, geen nummer" — waarna hij in het verkeerde veld zoekt.
     */
    @ParameterizedTest
    @ValueSource(strings = ["pietersen", "proeftuin-een", "de.vries", "jan_2", "klant+1", "a@b", "proeftuin-2026-01"])
    fun `een onbekende maar veilige aanduiding komt voluit in de melding`(waarde: String) {
        val fout = onbekendePersona(waarde, TERUGWEG)

        assertTrue(fout is NotFoundException, "verwachtte een 404 voor '$waarde'")
        assertEquals("onbekende persona '$waarde'; $TERUGWEG", fout.message)
    }

    /**
     * De melding gaat onverkort naar de applicatielog, dus een regeleinde zou daar een tweede
     * logregel kunnen verzinnen; een waarde die niet in de vorm van een sleutel past wordt daarom
     * benoemd in plaats van geciteerd.
     */
    @ParameterizedTest
    @ValueSource(strings = ["twee\nregels", "met spatie", "quote'erin", "<script>", "müller", "a:b", "../../etc"])
    fun `een onveilige aanduiding wordt benoemd en niet geciteerd`(waarde: String) {
        val fout = onbekendePersona(waarde, TERUGWEG)

        assertTrue(fout is NotFoundException, "verwachtte een 404 voor '$waarde'")
        assertEquals("onbekende persona de aangeboden waarde; $TERUGWEG", fout.message)
    }

    /**
     * De uitzondering hangt op de vorm van een OIN — precies twintig cijfers en verder niets — en
     * niet op "twintig of meer". Zonder dit geval zou een ruimere vorm dezelfde uitkomst geven op
     * elke andere testwaarde, en glipt er een reeks van eenentwintig cijfers mee naar de melding.
     */
    @Test
    fun `een reeks die langer is dan een OIN blijft een nummer`() {
        assertTrue(onbekendePersona(OIN + "0", TERUGWEG) is BadRequestException)
    }

    /**
     * Losse voorbeelden tonen niet wat de allowlist buitensluit. Elk printbaar ASCII-teken langs, in
     * een verder onschuldige waarde: alles buiten de toegestane vorm hoort benoemd te worden in
     * plaats van geciteerd, zodat een verruiming van de tekenklasse hier opvalt.
     */
    @Test
    fun `elk teken buiten de toegestane vorm wordt benoemd en niet geciteerd`() {
        val toegestaan = ('a'..'z') + ('A'..'Z') + ('0'..'9') + listOf('_', '.', '@', '+', '-')
        val geciteerd = (' '..'~').filter { teken ->
            onbekendePersona("ab${teken}cd", TERUGWEG).message.orEmpty().contains("'ab${teken}cd'")
        }

        assertEquals(toegestaan.sorted(), geciteerd.sorted(), "de toegestane tekens zijn verschoven")
    }

    /**
     * De lengtegrens, van beide kanten. Zonder de bovengrens zou een melding van willekeurige lengte
     * de applicatielog in gaan; met alleen een bovengrens zou niets aantonen dat hij ergens ligt.
     */
    @Test
    fun `de lengtegrens van een geciteerde aanduiding ligt op vierenzestig tekens`() {
        assertTrue(onbekendePersona("a".repeat(64), TERUGWEG).message.orEmpty().contains("a".repeat(64)))
        assertEquals(
            "onbekende persona de aangeboden waarde; $TERUGWEG",
            onbekendePersona("a".repeat(65), TERUGWEG).message,
        )
    }

    private companion object {

        /** Uit de ingerichte personaset; fictieve nummers uit de 999-testreeks. */
        const val BSN = "999993653"

        const val RSIN = "999999990"

        const val KVK = "90000014"

        const val OIN = "00000000000000100000"

        const val TERUGWEG = "kies een persona uit personas van /api/demo/omgeving"
    }
}
