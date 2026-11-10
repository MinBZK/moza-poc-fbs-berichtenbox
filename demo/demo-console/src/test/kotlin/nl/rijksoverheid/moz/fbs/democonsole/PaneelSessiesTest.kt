package nl.rijksoverheid.moz.fbs.democonsole

import jakarta.ws.rs.Path
import jakarta.ws.rs.QueryParam
import nl.rijksoverheid.moz.fbs.democonsole.sessie.LopendeSessie
import nl.rijksoverheid.moz.fbs.democonsole.sessie.PersonaSessies
import nl.rijksoverheid.moz.fbs.democonsole.sessie.SessieResource
import nl.rijksoverheid.moz.fbs.democonsole.sessie.Sessieoverzicht
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.reflect.KClass
import kotlin.reflect.full.memberProperties

/**
 * Bewaakt de sessiekolom op het Info-blad: het script bouwt die knoppen zelf, dus de controles op
 * `data-pad` in `index.html` zien ze niet. De koppeling met [SessieResource] — pad, parameternaam en
 * de velden van het antwoord — legt alleen deze test vast.
 *
 * Bewust géén `@QuarkusTest`: dit leest het script rechtstreeks van schijf. Dat de routes bestaan,
 * toetst `PaneelContractTest`.
 */
class PaneelSessiesTest {

    private val script: String = PaneelBestanden.script()

    @Test
    fun `de sessies lezen mee met de toestandsbalk, alleen als de sessiecache bereikbaar is`() {
        val toestand = functie("verversToestand")

        assertTrue(
            "heeftSessiecache === false ? null : lees('${pad("lopend")}')" in toestand,
            "de sessies worden niet (of ook zonder sessiecache) gelezen",
        )
        assertTrue("werkSessiesBij(sessies);" in toestand, "de gelezen sessies komen niet in de tabel")
        assertTrue("heeftSessiecache = omgeving ? omgeving.sessiecache : true;" in functie("pasOmgevingToe"))
        assertTrue("heeftSessiecache !== false" in functie("tekenPersonas"), "de sessiekolom hangt niet aan de omgeving")
    }

    @Test
    fun `de knoppen wijzen naar de adressen en parameters van SessieResource`() {
        val rij = functie("sessieRij")

        assertTrue("'${pad("beeindig")}?${parameter("beeindig")}=' + encodeURIComponent(sessie.verwijzing)" in rij)
        assertTrue("'${pad("beeindigVanPersona")}?${parameter("beeindigVanPersona")}=' + encodeURIComponent(persona.id)" in rij)
    }

    @Test
    fun `een sessieknop loopt via de gewone actieknoppen, met samenvatting en merkteken`() {
        val knop = functie("sessieKnop")
        val samenvatting = Regex("""knop\.dataset\.samenvatting = '([\w-]+)'""").find(knop)?.groupValues?.get(1)

        assertTrue(samenvatting != null, "een sessieknop noemt geen samenvatting")
        assertTrue(Regex("""^ {4}'$samenvatting':""", RegexOption.MULTILINE).containsMatchIn(script), "SAMENVATTINGEN kent '$samenvatting' niet")
        assertTrue("knop.dataset.pad = pad;" in knop && "knop.dataset.methode = 'POST';" in knop, "een sessieknop is geen actieknop")
        assertTrue("merk.className = 'knop__uitkomst';" in knop, "een sessieknop krijgt geen merkteken")
    }

    @Test
    fun `na het hertekenen van de tabel houdt een sessieknop zijn focus en zijn merkteken`() {
        // Na elke actie en bij elke poll met een ander verloop tekent de tabel opnieuw. Zonder dit
        // valt de focus terug op <body> en verdwijnt het merkteken dat net gezet werd.
        val teken = functie("tekenPersonas")
        val voor = teken.indexOf("const vorige = sessieKnoppenVoor(doel);")
        val vervangen = teken.indexOf("doel.replaceChildren(omhulsel")
        val na = teken.indexOf("herstelSessieKnoppen(doel, vorige);")

        assertTrue(voor in 0 until vervangen, "de focus en de merktekens worden niet vóór het hertekenen onthouden")
        assertTrue(na > vervangen, "de focus en de merktekens worden niet ná het hertekenen teruggezet")

        val herstel = functie("herstelSessieKnoppen")

        assertTrue("zetUitkomst(knop, uitkomst)" in herstel, "een merkteken gaat niet mee naar de nieuwe knop")
        assertTrue("doelwit.focus()" in herstel, "de focus gaat niet terug")
        assertTrue("document.activeElement !== document.body" in herstel, "de focus wordt weggehaald bij wie intussen verder ging")
        assertTrue("knop.dataset.persona = persona;" in functie("sessieKnop"), "een sessieknop zegt niet bij welke persona hij hoort")
    }

    @Test
    fun `Cache verlopen zonder telling staat op let op en noemt geen getal`() {
        // De sessies tellen mag mislukken; het wissen gaat dan door, maar "null lopende sessie(s)" is
        // geen melding.
        assertTrue(
            "typeof body.gewisteKeys === 'number' && typeof body.gewisteSessies !== 'number'" in functie("vullingSoort"),
            "een mislukte telling staat niet op let op",
        )
        assertTrue("typeof body.gewisteSessies === 'number' ? body.gewisteSessies :" in script, "een mislukte telling toont 'null'")
    }

    @Test
    fun `het script leest de velden die het antwoord draagt`() {
        assertEquals(setOf("personas"), velden(Sessieoverzicht::class))
        assertEquals(setOf("persona", "sessies"), velden(PersonaSessies::class))

        val perPersona = functie("sessiesPerPersona")

        assertTrue("antwoord.personas" in perPersona && "rij.persona" in perPersona && "rij.sessies" in perPersona)

        // Van een sessie alleen wat het antwoord draagt: geen id of hash, want die zijn er niet.
        val gelezen = Regex("""sessie\.(\w+)""").findAll(functie("sessieRij")).map { it.groupValues[1] }.toSet()

        assertEquals(velden(LopendeSessie::class), gelezen)
    }

    @Test
    fun `de verwijzing staat alleen in het adres van de knop`() {
        val rij = functie("sessieRij")

        assertEquals(1, Regex("""sessie\.verwijzing""").findAll(rij).count(), "de verwijzing komt ook buiten het adres van de knop terecht")
        assertTrue("encodeURIComponent(sessie.verwijzing)" in rij, "de verwijzing staat niet in het adres van de knop")
    }

    @Test
    fun `de sessies komen niet in de bewaarde stand van het Info-blad`() {
        // Een verwijzing wijst alleen iets aan zolang deze console draait.
        assertFalse("sessieStand" in functie("bewaarInfo"), "bewaarInfo bewaart de sessies")
        assertFalse("werkInfoBij(" in functie("werkSessiesBij"), "de sessies lopen via de bewaarde stand")
        assertFalse(Regex("""^ {4}sessies:""", RegexOption.MULTILINE).containsMatchIn(blok("INFO_BLOKKEN")), "de sessies zijn een bewaard info-blok")
    }

    private fun velden(klasse: KClass<*>): Set<String> = klasse.memberProperties.map { it.name }.toSet()

    private fun pad(methode: String): String =
        SessieResource::class.java.getAnnotation(Path::class.java).value +
            SessieResource::class.java.methods.single { it.name == methode }.getAnnotation(Path::class.java).value

    private fun parameter(methode: String): String =
        SessieResource::class.java.methods.single { it.name == methode }
            .parameters.single().getAnnotation(QueryParam::class.java).value

    private fun blok(naam: String): String {
        val gevonden = Regex("""^const $naam = \{$(.*?)^};$""", setOf(RegexOption.MULTILINE, RegexOption.DOT_MATCHES_ALL))
            .find(script)
            ?.groupValues?.get(1)

        assertTrue(gevonden != null, "het blok $naam niet gevonden in het script")

        return gevonden!!
    }

    private fun functie(naam: String): String {
        val body = Regex("""^(?:async )?function $naam\([^)]*\) \{$(.*?)^}$""", setOf(RegexOption.MULTILINE, RegexOption.DOT_MATCHES_ALL))
            .find(script)
            ?.groupValues?.get(1)

        assertTrue(body != null, "de functie $naam niet gevonden in het script")

        return body!!
    }
}
