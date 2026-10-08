package nl.rijksoverheid.moz.fbs.stelselregister.stelseldocument

import org.jose4j.jwk.EllipticCurveJsonWebKey
import org.jose4j.jwk.JsonWebKey
import org.jose4j.lang.HashUtil
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path
import java.security.Signature
import java.time.Duration
import java.time.Instant

class OndertekensleutelTest {

    private val nu = Instant.now()

    private fun laad(keten: Testketens.Keten, alias: String = Wegwerpketen.ALIAS, moment: Instant = nu) =
        Ondertekensleutel.uitKeystore(keten.pad, keten.wachtwoord, alias, moment)

    private fun geweigerd(blok: () -> Unit): String =
        assertThrows(OngeldigeOndertekensleutelException::class.java, blok).message.orEmpty()

    @Test
    fun `een geldige keystore levert het ondertekencertificaat zonder de root`() {
        val sleutel = laad(Testketens.geldig)

        assertEquals(listOf(Testketens.geldig.certificaat), sleutel.keten)
        assertEquals(1, sleutel.x5c.size)
        assertEquals(Testketens.geldig.certificaat, Afnemer.certificaat(sleutel.x5c.single()))
        assertEquals(Sleutelherkomst.KEYSTORE, sleutel.herkomst)
    }

    @Test
    fun `de root staat niet tussen de overige ondertekencertificaten`() {
        assertEquals(emptyList<Any>(), laad(Testketens.geldig).overige)
    }

    @Test
    fun `de kid is de RFC 7638-thumbprint van de publieke sleutel`() {
        val sleutel = laad(Testketens.geldig)
        val verwacht = JsonWebKey.Factory.newJwk(Testketens.geldig.certificaat.publicKey)
            .calculateBase64urlEncodedThumbprint(HashUtil.SHA_256)

        assertEquals(verwacht, sleutel.kid)
    }

    @Test
    fun `de jwk beschrijft dezelfde publieke sleutel en bevat geen privaat deel`() {
        val jwk = Ondertekensleutel.jwk(Testketens.geldig.certificaat)
        val gelezen = JsonWebKey.Factory.newJwk(jwk) as EllipticCurveJsonWebKey

        assertEquals(Testketens.geldig.certificaat.publicKey, gelezen.publicKey)
        assertEquals(setOf("kty", "crv", "x", "y", "kid", "use", "alg", "x5c"), jwk.keys)
        assertEquals(43, (jwk.getValue("x") as String).length)
        assertEquals(43, (jwk.getValue("y") as String).length)
        assertFalse(jwk.containsKey("d"))
    }

    @Test
    fun `de handtekening is 64 bytes en klopt met het certificaat`() {
        val gegevens = "te ondertekenen".toByteArray()
        val handtekening = laad(Testketens.geldig).onderteken(gegevens)

        assertEquals(64, handtekening.size)
        assertTrue(
            Signature.getInstance("SHA256withECDSAinP1363Format").run {
                initVerify(Testketens.geldig.certificaat.publicKey)
                update(gegevens)
                verify(handtekening)
            },
        )
    }

    @Test
    fun `resterend telt tot de einddatum van het certificaat`() {
        val einde = Testketens.geldig.certificaat.notAfter.toInstant()
        val sleutel = laad(Testketens.geldig)

        assertEquals(Duration.ofDays(3), sleutel.resterend(einde.minus(Duration.ofDays(3))))
        assertTrue(sleutel.resterend(einde.plusSeconds(1)).isNegative)
    }

    @Test
    fun `een tweede ondertekencertificaat in de keystore telt als overige sleutel`() {
        val keystore = java.security.KeyStore.getInstance("PKCS12").apply {
            Files.newInputStream(Testketens.geldig.pad).use { load(it, Testketens.geldig.wachtwoord) }
            setCertificateEntry("vorige", Testketens.ander.certificaat)
            setCertificateEntry("rsa", Testketens.rsa.certificaat)
        }
        val pad = Files.createTempDirectory("rotatie").resolve("keystore.p12")
        Files.newOutputStream(pad).use { keystore.store(it, Testketens.geldig.wachtwoord) }

        val sleutel = Ondertekensleutel.uitKeystore(pad, Testketens.geldig.wachtwoord, Wegwerpketen.ALIAS, nu)

        assertEquals(listOf(Testketens.ander.certificaat), sleutel.overige)
    }

    @Test
    fun `een ontbrekend bestand wordt geweigerd`() {
        val melding = geweigerd {
            Ondertekensleutel.uitKeystore(Path.of("/bestaat/niet.p12"), "x".toCharArray(), Wegwerpketen.ALIAS, nu)
        }

        assertTrue(melding.contains("bestaat niet"), melding)
    }

    @Test
    fun `een verkeerd wachtwoord wordt geweigerd zonder het wachtwoord te noemen`() {
        val melding = geweigerd {
            Ondertekensleutel.uitKeystore(Testketens.geldig.pad, "verkeerd-wachtwoord".toCharArray(), Wegwerpketen.ALIAS, nu)
        }

        assertTrue(melding.contains("niet te openen"), melding)
        assertFalse(melding.contains("verkeerd-wachtwoord"), melding)
    }

    @Test
    fun `een bestand dat geen keystore is wordt geweigerd`() {
        val pad = Files.createTempFile("geen-keystore", ".p12").also { Files.writeString(it, "dit is geen PKCS#12") }

        val melding = geweigerd { Ondertekensleutel.uitKeystore(pad, "x".toCharArray(), Wegwerpketen.ALIAS, nu) }

        assertTrue(melding.contains("niet te openen"), melding)
    }

    @Test
    fun `een onbekende alias wordt geweigerd`() {
        val melding = geweigerd { laad(Testketens.geldig, alias = "bestaat-niet") }

        assertTrue(melding.contains("geen sleutel onder alias 'bestaat-niet'"), melding)
    }

    @Test
    fun `een RSA-sleutel wordt geweigerd`() {
        val melding = geweigerd { laad(Testketens.rsa) }

        assertTrue(melding.contains("geen EC P-256-sleutel"), melding)
    }

    @Test
    fun `een EC-sleutel op een andere curve wordt geweigerd`() {
        val melding = geweigerd { laad(Testketens.p384) }

        assertTrue(melding.contains("geen EC P-256-sleutel"), melding)
    }

    @Test
    fun `een zelfondertekend certificaat zonder uitgever wordt geweigerd`() {
        val melding = geweigerd { laad(Testketens.geldig, alias = Wegwerpketen.ROOT_ALIAS) }

        assertTrue(melding.contains("zelfondertekend"), melding)
    }

    @Test
    fun `een certificaat dat niet bij de sleutel hoort wordt geweigerd`() {
        val keten = Testketens.samengesteld(
            sleutel = Testketens.geldig.sleutel,
            keten = listOf(Testketens.ander.certificaat, Testketens.ander.root),
        )

        val melding = geweigerd { laad(keten) }

        assertTrue(melding.contains("hoort niet bij de sleutel"), melding)
    }

    @Test
    fun `een certificaat met een RSA-sleutel bij een EC-sleutel wordt geweigerd`() {
        val keten = Testketens.samengesteld(
            sleutel = Testketens.geldig.sleutel,
            keten = listOf(Testketens.rsa.certificaat, Testketens.rsa.root),
        )

        val melding = geweigerd { laad(keten) }

        assertTrue(melding.contains("hoort niet bij de sleutel"), melding)
    }

    // Rechtstreeks op de controle: een PKCS#12-keystore bouwt bij het laden zelf een keten en laat
    // een certificaat dat er niet in past weg, dus via een bestand is deze toestand niet te maken.
    @Test
    fun `een keten waarvan de schakels niet sluiten wordt geweigerd`() {
        val melding = geweigerd {
            Ondertekensleutel.valideerSchakels(listOf(Testketens.geldig.certificaat, Testketens.ander.root), "alias")
        }

        assertTrue(melding.contains("sluit niet"), melding)
    }

    @Test
    fun `een sluitende keten en een keten van een schakel passeren`() {
        Ondertekensleutel.valideerSchakels(listOf(Testketens.geldig.certificaat, Testketens.geldig.root), "alias")
        Ondertekensleutel.valideerSchakels(listOf(Testketens.geldig.certificaat), "alias")
    }

    @Test
    fun `een verlopen certificaat wordt geweigerd`() {
        val melding = geweigerd { laad(Testketens.verlopen) }

        assertTrue(melding.contains("niet geldig"), melding)
    }

    @Test
    fun `een certificaat dat nog niet geldig is wordt geweigerd`() {
        val melding = geweigerd { laad(Testketens.nogNietGeldig) }

        assertTrue(melding.contains("niet geldig"), melding)
    }

    @Test
    fun `precies op de einddatum geldt het certificaat niet meer`() {
        val einde = Testketens.geldig.certificaat.notAfter.toInstant()

        laad(Testketens.geldig, moment = einde.minusSeconds(1))
        geweigerd { laad(Testketens.geldig, moment = einde) }
    }

    @Test
    fun `een keten zonder root in de keystore blijft bruikbaar`() {
        val keten = Testketens.samengesteld(
            sleutel = Testketens.geldig.sleutel,
            keten = listOf(Testketens.geldig.certificaat),
        )

        assertEquals(listOf(Testketens.geldig.certificaat), laad(keten).keten)
    }
}
