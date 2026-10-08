package nl.rijksoverheid.moz.fbs.stelselregister.stelseldocument

import org.jose4j.jwk.JsonWebKeySet
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.security.KeyStore
import java.security.cert.X509Certificate
import java.time.Instant

class SleutelsetTest {

    private fun sleutelMet(vararg overige: X509Certificate): Ondertekensleutel {
        val bron = Testketens.geldig
        val keystore = KeyStore.getInstance("PKCS12").apply {
            Files.newInputStream(bron.pad).use { load(it, bron.wachtwoord) }
            overige.forEachIndexed { index, certificaat -> setCertificateEntry("vorige-$index", certificaat) }
        }
        val pad = Files.createTempDirectory("sleutelset").resolve("keystore.p12")

        Files.newOutputStream(pad).use { keystore.store(it, bron.wachtwoord) }

        return Ondertekensleutel.uitKeystore(pad, bron.wachtwoord, Wegwerpketen.ALIAS, Instant.now())
    }

    private fun kids(set: Sleutelset) = JsonWebKeySet(set.json).jsonWebKeys.map { it.keyId }

    @Test
    fun `zonder overige sleutels bevat de set alleen de actieve sleutel`() {
        val sleutel = sleutelMet()

        assertEquals(listOf(sleutel.kid), kids(Sleutelset(sleutel)))
    }

    @Test
    fun `tijdens een wissel staat de actieve sleutel voorop en volgen de overige`() {
        val een = sleutelMet(Testketens.ander.certificaat)
        val twee = sleutelMet(Testketens.ander.certificaat, Testketens.andereUitgever.certificaat)

        assertEquals(2, kids(Sleutelset(een)).size)
        assertEquals(3, kids(Sleutelset(twee)).size)
        assertEquals(een.kid, kids(Sleutelset(een)).first())
        assertEquals(twee.kid, kids(Sleutelset(twee)).first())
        assertEquals(3, kids(Sleutelset(twee)).toSet().size)
    }

    @Test
    fun `de ETag verandert zodra er een sleutel bijkomt en is anders gelijk`() {
        val zonder = Sleutelset(sleutelMet())
        val met = Sleutelset(sleutelMet(Testketens.ander.certificaat))

        assertEquals(zonder.etag, Sleutelset(sleutelMet()).etag)
        assertNotEquals(zonder.etag, met.etag)
        assertTrue(met.etag.startsWith("W/\""))
    }

    @Test
    fun `geen enkele sleutel in de set draagt een privaat deel`() {
        val set = Sleutelset(sleutelMet(Testketens.ander.certificaat))

        assertFalse(Afnemer.JSON.readTree(set.json).path("keys").any { it.has("d") })
    }
}
