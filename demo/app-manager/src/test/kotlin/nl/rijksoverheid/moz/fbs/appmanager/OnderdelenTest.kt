package nl.rijksoverheid.moz.fbs.appmanager

import nl.rijksoverheid.moz.fbs.demopersonas.DemoPersona
import nl.rijksoverheid.moz.fbs.demopersonas.PersonaBron
import nl.rijksoverheid.moz.fbs.rechtstreeks.OpslagVolException
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.security.KeyPairGenerator
import java.security.spec.ECGenParameterSpec
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import java.util.Base64
import java.util.Optional

class OnderdelenTest {

    private fun paar() = KeyPairGenerator.getInstance("EC").run {
        initialize(ECGenParameterSpec("secp256r1"))
        generateKeyPair()
    }

    private fun pem(soort: String, der: ByteArray) =
        "-----BEGIN $soort-----\n${Base64.getMimeEncoder(64, "\n".toByteArray()).encodeToString(der)}\n-----END $soort-----\n"

    @Test
    fun `een PEM met een bij elkaar horend paar wordt gelezen`() {
        val paar = paar()

        val gelezen = Ondertekensleutel.leesPem(pem("PRIVATE KEY", paar.private.encoded) + pem("PUBLIC KEY", paar.public.encoded))

        assertEquals(paar.public, gelezen.public)
    }

    @Test
    fun `een PEM zonder beide blokken, of met blokken van twee sleutels, wordt geweigerd`() {
        val een = paar()
        val ander = paar()

        assertThrows(IllegalStateException::class.java) { Ondertekensleutel.leesPem(pem("PRIVATE KEY", een.private.encoded)) }
        assertThrows(IllegalStateException::class.java) { Ondertekensleutel.leesPem(pem("PUBLIC KEY", een.public.encoded)) }

        assertThrows(IllegalArgumentException::class.java) {
            Ondertekensleutel.leesPem(pem("PRIVATE KEY", een.private.encoded) + pem("PUBLIC KEY", ander.public.encoded))
        }
    }

    @Test
    fun `een sleutel op een andere curve dan P-256 wordt met een duidelijke melding geweigerd`() {
        val p384 = KeyPairGenerator.getInstance("EC").run {
            initialize(ECGenParameterSpec("secp384r1"))
            generateKeyPair()
        }

        val fout = assertThrows(IllegalArgumentException::class.java) {
            Ondertekensleutel.leesPem(pem("PRIVATE KEY", p384.private.encoded) + pem("PUBLIC KEY", p384.public.encoded))
        }

        assertTrue(fout.message!!.contains("P-256"), fout.message)
    }

    @Test
    fun `een sleutelpad dat niet bestaat of geen sleutel bevat, noemt de property`(@TempDir map: Path) {
        val leeg = map.resolve("leeg.pem").also { Files.writeString(it, "geen sleutel") }

        listOf(map.resolve("bestaat-niet.pem").toString(), leeg.toString()).forEach { bestand ->
            val fout = assertThrows(IllegalStateException::class.java) { Ondertekensleutel(config(bestand)) }

            assertTrue(fout.message!!.contains("app-manager.sleutel-pad"), fout.message)
        }
    }

    @Test
    fun `alleen de keuzepagina laat de browser bij het formulier de eigen Origin meesturen`() {
        // Onder de no-referrer van de response stuurt een browser `Origin: null` bij de POST, en dan
        // weigert de App Manager elke keuze. De foutpagina heeft geen formulier en blijft streng.
        val meta = """<meta name="referrer" content="same-origin">"""

        assertTrue(Keuzepagina.html(emptyList()).contains(meta))
        assertFalse(Keuzepagina.foutpagina("t", "x").contains("referrer"))
    }

    @Test
    fun `de keuzepagina escapet labels en ids`() {
        val persona = DemoPersona("a\"b", "<script>&'", "KVK", "90000014", emptyList(), PersonaBron.KETEN)

        val html = Keuzepagina.html(listOf(persona))

        assertFalse(html.contains("<script>"))
        assertTrue(html.contains("&lt;script&gt;&amp;&#39;"))
        assertTrue(html.contains("value=\"a&quot;b\""))
    }

    @Test
    fun `een credential van meer dan 24 uur wordt bij de start geweigerd`(@TempDir map: Path) {
        val config = config(pemBestand(map), credentialGeldig = Duration.ofHours(25))

        assertThrows(IllegalArgumentException::class.java) { CredentialUitgifte(config, Ondertekensleutel(config), Clock.systemUTC()) }
    }

    @Test
    fun `een credential van precies twee minuten of 24 uur mag`(@TempDir map: Path) {
        val pad = pemBestand(map)

        listOf(Duration.ofMinutes(2), Duration.ofHours(24)).forEach { looptijd ->
            val config = config(pad, credentialGeldig = looptijd)

            CredentialUitgifte(config, Ondertekensleutel(config), Clock.systemUTC())
        }
    }

    @Test
    fun `registraties en koppelingen verlopen, en een session_token koppelt één keer`() {
        val klok = VerstelbareKlok(Instant.parse("2026-10-27T10:00:00Z"))
        val registraties = Registraties(klok)
        val nu = klok.nu

        val koppeling = registraties.koppel("t", nu.plusSeconds(60))!!

        assertNull(registraties.koppel("t", nu.plusSeconds(60)))
        assertEquals("t", registraties.sessieToken(koppeling))

        val token = registraties.registreer("KVK:90000014", "tp", "c", Duration.ofSeconds(30))

        assertEquals("tp", registraties.registratie(token)?.thumbprint)
        assertFalse(registraties.registratie(token).toString().contains("90000014"))

        klok.nu = nu.plusSeconds(60)

        assertNull(registraties.sessieToken(koppeling))
        assertNull(registraties.registratie(token))
        assertEquals(32, token.length)
    }

    @Test
    fun `een volle opslag geeft 503 met Retry-After, op een protocolpad als problem+json en in de browser als pagina`() {
        val protocol = Foutvertaling().vertaal(OpslagVolException(Duration.ofSeconds(12)), "/v1/challenge")
        val browser = Foutvertaling().vertaal(OpslagVolException(Duration.ofSeconds(12)), "/register_app/kies")

        assertEquals(503, protocol.status)
        assertEquals("12", protocol.headers.getFirst("Retry-After").toString())
        assertEquals("application/problem+json", protocol.mediaType.toString())
        assertEquals(503, browser.status)
        assertEquals("12", browser.headers.getFirst("Retry-After").toString())
        assertTrue(browser.mediaType.toString().startsWith("text/html"), browser.mediaType.toString())
    }

    @Test
    fun `een verlopen sleutel geeft 503 en geen 500`() {
        val antwoord = Foutvertaling().sleutelVerlopenAntwoord("/v1/challenge")

        assertEquals(503, antwoord.status)
        assertEquals("300", antwoord.headers.getFirst("Retry-After").toString())
    }

    @Test
    fun `de doorverwijzing na een keuze is maar kort opnieuw op te vragen`() {
        val klok = VerstelbareKlok(Instant.parse("2026-10-27T10:00:00Z"))
        val registraties = Registraties(klok)
        val koppeling = registraties.koppel("s", klok.nu.plusSeconds(600))!!
        val token = registraties.registreer("KVK:90000014", "tp", "c", Duration.ofHours(1))

        registraties.bevestig(koppeling, token, "terug")

        klok.nu = klok.nu.plus(Registraties.HERHAALVENSTER).minusSeconds(1)

        assertEquals("terug", registraties.afgerond(koppeling))

        klok.nu = klok.nu.plusSeconds(1)

        assertNull(registraties.afgerond(koppeling))
    }

    @Test
    fun `een registratie verloopt precies op haar einde`() {
        val klok = VerstelbareKlok(Instant.parse("2026-10-27T10:00:00Z"))
        val registraties = Registraties(klok)
        val token = registraties.registreer("KVK:90000014", "tp", "c", Duration.ofSeconds(30))

        klok.nu = klok.nu.plusSeconds(29)

        assertEquals("tp", registraties.registratie(token)?.thumbprint)

        klok.nu = klok.nu.plusSeconds(1)

        assertNull(registraties.registratie(token))
    }

    @Test
    fun `boven het totaal voor alle clients samen komt er geen registratie meer bij`() {
        val registraties = Registraties(VerstelbareKlok(Instant.parse("2026-10-27T10:00:00Z")))

        repeat(Registraties.MAX_AANTAL) { registraties.registreer("KVK:90000014", "tp$it", "client-$it", Duration.ofHours(1)) }

        assertThrows(OpslagVolException::class.java) { registraties.registreer("KVK:90000014", "nog een", "nieuwe client", Duration.ofHours(1)) }
    }

    @Suppress("LongParameterList")
    private fun config(
        pad: String?,
        credentialGeldig: Duration = Duration.ofHours(1),
        geldigTot: Instant = Instant.MAX,
        registratieGeldig: Duration = Duration.ofDays(1),
        terugUrl: String = "https://app.test/",
        issuerUrl: String = "https://app-manager.test/",
        oin: String = AppManagerConfig.DEMO_OIN,
        kid: String = "k",
    ) = object : AppManagerConfig {
        override fun oin() = oin
        override fun kid() = kid
        override fun sleutelPad() = Optional.ofNullable(pad)
        override fun sleutelGeldigTot() = geldigTot
        override fun issuerUrl() = issuerUrl
        override fun terugUrl() = terugUrl
        override fun credentialGeldig() = credentialGeldig
        override fun registratieGeldig() = registratieGeldig
        override fun sessiesPerClient() = 20
    }

    private fun pemBestand(map: Path): String {
        val paar = paar()
        val bestand = map.resolve("sleutel.pem")

        Files.writeString(bestand, pem("PRIVATE KEY", paar.private.encoded) + pem("PUBLIC KEY", paar.public.encoded))

        return bestand.toString()
    }

    @Test
    fun `een uitgerolde build zonder sleutelpad start niet`() {
        assertThrows(IllegalStateException::class.java) { Ondertekensleutel(config(pad = null)) }
    }

    @Test
    fun `een credential korter dan twee minuten wordt bij de start geweigerd`(@TempDir map: Path) {
        val config = config(pemBestand(map), credentialGeldig = Duration.ofMinutes(1))

        assertThrows(IllegalArgumentException::class.java) { CredentialUitgifte(config, Ondertekensleutel(config), Clock.systemUTC()) }
    }

    @Test
    fun `met een verlopen sleutel ondertekent de App Manager geen credential`(@TempDir map: Path) {
        val klok = VerstelbareKlok(Instant.parse("2026-10-27T10:00:00Z"))
        val config = config(pemBestand(map), geldigTot = klok.nu)
        val uitgifte = CredentialUitgifte(config, Ondertekensleutel(config), klok)

        assertThrows(SleutelVerlopen::class.java) { uitgifte.onderteken(paar().public as java.security.interfaces.ECPublicKey, "KVK:90000014") }
    }

    @Test
    fun `een adres in de configuratie is absoluut, zonder fragment, en https buiten ontwikkelmodus`() {
        ConfigControle.controleerAdres("x", "https://app.test/", ontwikkel = false)
        ConfigControle.controleerAdres("x", "http://localhost:8099/", ontwikkel = true)

        listOf("/relatief", "https://app.test/#iets", "geen url", "http://app.test/", "https:app.test", "https:///pad").forEach { adres ->
            assertThrows(IllegalArgumentException::class.java) { ConfigControle.controleerAdres("x", adres, ontwikkel = false) }
        }
    }

    @Test
    fun `een bevestigde registratie vervangt die van dezelfde sleutel, en bij een dubbele submit wint er één`() {
        val klok = VerstelbareKlok(Instant.parse("2026-10-27T10:00:00Z"))
        val registraties = Registraties(klok)
        val eerste = registraties.registreer("KVK:90000014", "zelfde", "c", Duration.ofHours(1))

        assertEquals("terug-0", registraties.bevestig(registraties.koppel("s1", klok.nu.plusSeconds(60))!!, eerste, "terug-0"))

        val koppeling = registraties.koppel("s2", klok.nu.plusSeconds(60))!!
        val a = registraties.registreer("KVK:90000014", "zelfde", "c", Duration.ofHours(1))
        val b = registraties.registreer("KVK:90000014", "zelfde", "c", Duration.ofHours(1))

        assertEquals("terug-a", registraties.bevestig(koppeling, a, "terug-a"))
        assertEquals("terug-a", registraties.bevestig(koppeling, b, "terug-b"))
        assertEquals("terug-a", registraties.afgerond(koppeling))
        assertEquals("zelfde", registraties.registratie(a)?.thumbprint)
        assertNull(registraties.registratie(b))
        assertNull(registraties.registratie(eerste))

        klok.nu = klok.nu.plusSeconds(61)

        assertNull(registraties.afgerond(koppeling))
    }

    @Test
    fun `een koppeling blijft staan als de registratie al weg is`() {
        val klok = VerstelbareKlok(Instant.parse("2026-10-27T10:00:00Z"))
        val registraties = Registraties(klok)
        val koppeling = registraties.koppel("s", klok.nu.plusSeconds(60))!!
        val token = registraties.registreer("KVK:90000014", "tp", "c", Duration.ofHours(1))

        registraties.vergeet(token)

        assertNull(registraties.bevestig(koppeling, token, "terug"))
        assertEquals("s", registraties.sessieToken(koppeling))
        assertNull(registraties.afgerond(koppeling))
    }

    @Test
    fun `per client is het aantal registraties begrensd, met een vaste wachttijd`() {
        val klok = VerstelbareKlok(Instant.parse("2026-10-27T10:00:00Z"))
        val registraties = Registraties(klok)
        val vroegste = registraties.registreer("KVK:90000014", "tp-vroeg", "c", Duration.ofMinutes(10))

        repeat(49) { registraties.registreer("KVK:90000014", "tp$it", "c", Duration.ofHours(1)) }

        val fout = assertThrows(OpslagVolException::class.java) { registraties.registreer("KVK:90000014", "nog een", "c", Duration.ofHours(1)) }

        assertEquals(Duration.ofSeconds(60), fout.opnieuwNa)

        registraties.registreer("KVK:90000014", "van een ander", "d", Duration.ofHours(1))
        registraties.vergeet(vroegste)
        registraties.registreer("KVK:90000014", "weer ruimte", "c", Duration.ofHours(1))
    }

    @Test
    fun `de sleutel voor een quotum is het IPv4-adres, of het IPv6-prefix van 64 bits`() {
        assertEquals("192.0.2.1", ActiverenResource.clientSleutel("192.0.2.1"))
        assertEquals(ActiverenResource.clientSleutel("2001:db8:1:2::1"), ActiverenResource.clientSleutel("2001:db8:1:2:ffff::9"))
        assertFalse(ActiverenResource.clientSleutel("2001:db8:1:2::1") == ActiverenResource.clientSleutel("2001:db8:1:3::1"))
        assertEquals("192.0.2.1", ActiverenResource.clientSleutel("::ffff:192.0.2.1"))

        listOf(null, "", "unknown", "_verborgen", "localhost", "999.0.0.1").forEach {
            assertEquals("onbekend", ActiverenResource.clientSleutel(it))
        }
    }

    @Test
    fun `een sleutel die binnen de levensduur van een nonce verloopt, of een registratieduur van niets of meer dan 30 dagen, blokkeert de start`() {
        val klok = VerstelbareKlok(Instant.parse("2026-10-27T10:00:00Z"))

        assertThrows(IllegalArgumentException::class.java) { ConfigControle(config(pad = null, geldigTot = klok.nu.plusSeconds(1)), klok) }
        assertThrows(IllegalArgumentException::class.java) { ConfigControle(config(pad = null, registratieGeldig = Duration.ZERO), klok) }
        assertThrows(IllegalArgumentException::class.java) { ConfigControle(config(pad = null, registratieGeldig = Duration.ofSeconds(-1)), klok) }
        assertThrows(IllegalArgumentException::class.java) { ConfigControle(config(pad = null, registratieGeldig = Duration.ofDays(30).plusSeconds(1)), klok) }

        ConfigControle(config(pad = null), klok)
        ConfigControle(config(pad = null, registratieGeldig = Duration.ofDays(30)), klok)
    }

    @Test
    fun `een sleutel die precies na de levensduur van een nonce verloopt, mag`() {
        val klok = VerstelbareKlok(Instant.parse("2026-10-27T10:00:00Z"))

        assertThrows(IllegalArgumentException::class.java) { ConfigControle(config(pad = null, geldigTot = klok.nu.plusSeconds(60)), klok) }

        ConfigControle(config(pad = null, geldigTot = klok.nu.plusSeconds(61)), klok)
    }

    @ParameterizedTest
    @ValueSource(strings = ["terug-http", "issuer-http", "issuer-zonder-slash", "oin", "kid"])
    fun `buiten ontwikkel- en testmodus blokkeert een verkeerde instelling de start`(fout: String) {
        val klok = VerstelbareKlok(Instant.parse("2026-10-27T10:00:00Z"))

        val config = when (fout) {
            "terug-http" -> config(pad = null, terugUrl = "http://app.test/")
            "issuer-http" -> config(pad = null, issuerUrl = "http://app-manager.test/")
            "issuer-zonder-slash" -> config(pad = null, issuerUrl = "https://app-manager.test")
            "oin" -> config(pad = null, oin = "123")
            else -> config(pad = null, kid = " ")
        }

        assertThrows(IllegalArgumentException::class.java) { ConfigControle(config, klok) }
    }

    @Test
    fun `een formulierveld wordt gedecodeerd, en een kapotte encoding geeft niets`() {
        assertEquals("a b", ActiverenResource.formulierveld("x=1&persona=a+b", "persona"))
        assertNull(ActiverenResource.formulierveld("persona=%zz", "persona"))
        assertNull(ActiverenResource.formulierveld("persona", "persona"))
        assertNull(ActiverenResource.formulierveld("", "persona"))
    }
}

/** Een klok die een test vooruit zet. */
class VerstelbareKlok(var nu: Instant) : Clock() {
    override fun getZone(): ZoneOffset = ZoneOffset.UTC

    override fun withZone(zone: java.time.ZoneId?): Clock = this

    override fun instant(): Instant = nu
}
