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
    fun `de keuzepagina escapet labels en ids`() {
        val persona = DemoPersona("a\"b", "<script>&'", "KVK", "90000014", emptyList(), PersonaBron.KETEN)

        val html = Keuzepagina.html(listOf(persona))

        assertFalse(html.contains("<script>"))
        assertTrue(html.contains("&lt;script&gt;&amp;&#39;"))
        assertTrue(html.contains("value=\"a&quot;b\""))
    }

    @Test
    fun `een credential van meer dan 24 uur wordt bij de start geweigerd`(@TempDir map: Path) {
        val paar = paar()
        val bestand = map.resolve("sleutel.pem")

        Files.writeString(bestand, pem("PRIVATE KEY", paar.private.encoded) + pem("PUBLIC KEY", paar.public.encoded))

        val config = object : AppManagerConfig {
            override fun oin() = AppManagerConfig.DEMO_OIN
            override fun kid() = "k"
            override fun sleutelPad() = Optional.of(bestand.toString())
            override fun sleutelGeldigTot(): Instant = Instant.MAX
            override fun terugUrl() = "https://app.test/"
            override fun credentialGeldig(): Duration = Duration.ofHours(25)
            override fun registratieGeldig(): Duration = Duration.ofDays(1)
        }

        assertThrows(IllegalArgumentException::class.java) { CredentialUitgifte(config, Ondertekensleutel(config), Clock.systemUTC()) }
    }

    @Test
    fun `registraties en koppelingen verlopen, en een session_token koppelt één keer`() {
        val klok = VerstelbareKlok(Instant.parse("2026-10-27T10:00:00Z"))
        val registraties = Registraties(klok)
        val nu = klok.nu

        val koppeling = registraties.koppel("t", nu.plusSeconds(60))!!

        assertNull(registraties.koppel("t", nu.plusSeconds(60)))
        assertEquals("t", registraties.sessieToken(koppeling))

        val token = registraties.registreer("KVK:90000014", "tp", Duration.ofSeconds(30))

        assertEquals("tp", registraties.registratie(token)?.thumbprint)
        assertFalse(registraties.registratie(token).toString().contains("90000014"))

        klok.nu = nu.plusSeconds(60)

        assertNull(registraties.sessieToken(koppeling))
        assertNull(registraties.registratie(token))
        assertEquals(32, token.length)
    }

    @Test
    fun `een volle opslag geeft 503 met Retry-After`() {
        val antwoord = OpslagVolMapper().toResponse(OpslagVolException(Duration.ofSeconds(12)))

        assertEquals(503, antwoord.status)
        assertEquals("12", antwoord.headers.getFirst("Retry-After").toString())
    }
}

/** Een klok die een test vooruit zet; een benoemde klasse, omdat Quarkus een anonieme in de tests niet kan transformeren. */
class VerstelbareKlok(var nu: Instant) : Clock() {
    override fun getZone(): ZoneOffset = ZoneOffset.UTC

    override fun withZone(zone: java.time.ZoneId?): Clock = this

    override fun instant(): Instant = nu
}
