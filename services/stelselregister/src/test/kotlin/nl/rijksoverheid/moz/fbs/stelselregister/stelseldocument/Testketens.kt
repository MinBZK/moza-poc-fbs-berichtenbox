package nl.rijksoverheid.moz.fbs.stelselregister.stelseldocument

import java.nio.file.Files
import java.nio.file.Path
import java.security.KeyStore
import java.security.PrivateKey
import java.security.cert.X509Certificate

/**
 * Keystores voor de tests. Eén keer per JVM gemaakt: elke keten kost een paar `keytool`-aanroepen.
 * De varianten die `keytool` niet wil maken — een certificaat bij de verkeerde sleutel — worden
 * uit dit materiaal samengesteld met de KeyStore-API, die die samenhang niet controleert.
 */
object Testketens {

    class Keten(val bestand: Wegwerpketen.Keystorebestand) {
        val pad: Path get() = bestand.pad
        val wachtwoord: CharArray get() = bestand.wachtwoord

        private val keystore: KeyStore by lazy {
            KeyStore.getInstance("PKCS12").apply { Files.newInputStream(pad).use { load(it, wachtwoord) } }
        }

        val root: X509Certificate get() = keystore.getCertificate(Wegwerpketen.ROOT_ALIAS) as X509Certificate
        val certificaat: X509Certificate get() = keystore.getCertificate(Wegwerpketen.ALIAS) as X509Certificate
        val sleutel: PrivateKey get() = keystore.getKey(Wegwerpketen.ALIAS, wachtwoord) as PrivateKey
        val rootSleutel: PrivateKey get() = keystore.getKey(Wegwerpketen.ROOT_ALIAS, wachtwoord) as PrivateKey
    }

    private fun map(naam: String): Path = Files.createTempDirectory("testketen-$naam").also { it.toFile().deleteOnExit() }

    val geldig: Keten by lazy { Keten(Wegwerpketen.maak(map("geldig"))) }

    /** Een tweede, onafhankelijke keten: andere root, andere sleutel. */
    val ander: Keten by lazy { Keten(Wegwerpketen.maak(map("ander"))) }

    val rsa: Keten by lazy {
        Keten(Wegwerpketen.maak(map("rsa"), sleutelalgoritme = listOf("-keyalg", "RSA", "-keysize", "2048")))
    }

    val p384: Keten by lazy {
        Keten(Wegwerpketen.maak(map("p384"), sleutelalgoritme = listOf("-keyalg", "EC", "-groupname", "secp384r1")))
    }

    /** Eén dag geldig, begonnen tien dagen geleden. */
    val verlopen: Keten by lazy { Keten(Wegwerpketen.maak(map("verlopen"), geldigheidDagen = 1, startdatum = "-10d")) }

    val nogNietGeldig: Keten by lazy { Keten(Wegwerpketen.maak(map("toekomst"), startdatum = "+10d")) }

    /** Een keystore met onder [alias] precies de opgegeven sleutel en keten, hoe onsamenhangend ook. */
    fun samengesteld(sleutel: PrivateKey, keten: List<X509Certificate>, alias: String = Wegwerpketen.ALIAS): Keten {
        val wachtwoord = "samengesteld".toCharArray()
        val pad = map("samengesteld").resolve("keystore.p12")
        val keystore = KeyStore.getInstance("PKCS12").apply { load(null, wachtwoord) }

        keystore.setKeyEntry(alias, sleutel, wachtwoord, keten.toTypedArray())
        Files.newOutputStream(pad).use { keystore.store(it, wachtwoord) }

        return Keten(Wegwerpketen.Keystorebestand(pad, wachtwoord))
    }
}
