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

    const val TUSSEN_ALIAS = "tussen"

    class Keten(val bestand: Wegwerpketen.Keystorebestand) {
        val pad: Path get() = bestand.pad
        val wachtwoord: CharArray get() = bestand.wachtwoord

        private val keystore: KeyStore by lazy {
            KeyStore.getInstance("PKCS12").apply { Files.newInputStream(pad).use { load(it, wachtwoord) } }
        }

        fun certificaat(alias: String): X509Certificate = keystore.getCertificate(alias) as X509Certificate

        val root: X509Certificate get() = certificaat(Wegwerpketen.ROOT_ALIAS)
        val certificaat: X509Certificate get() = certificaat(Wegwerpketen.ALIAS)
        val sleutel: PrivateKey get() = keystore.getKey(Wegwerpketen.ALIAS, wachtwoord) as PrivateKey
    }

    private fun map(naam: String): Path = Files.createTempDirectory("testketen-$naam").also { it.toFile().deleteOnExit() }

    /**
     * Een keten met een eigen root en daaronder de opgegeven certificaten, in volgorde. Elke stap
     * is `alias, uitgever-alias, extra keytool-argumenten`.
     */
    private fun bouw(naam: String, vararg stappen: Triple<String, String, List<String>>): Keten {
        val pad = map(naam).resolve("keystore.p12")
        val wachtwoord = Wegwerpketen.nieuwWachtwoord()
        val opslag = Wegwerpketen.opslag(pad, wachtwoord)

        Wegwerpketen.keytool(Wegwerpketen.ROOT + opslag)

        stappen.forEach { (alias, uitgever, extra) ->
            Wegwerpketen.keytool(listOf("-genkeypair", "-alias", alias, "-signer", uitgever) + extra + opslag)
        }

        return Keten(Wegwerpketen.Keystorebestand(pad, wachtwoord.toCharArray()))
    }

    private fun blad(
        uitgever: String = Wegwerpketen.ROOT_ALIAS,
        dname: String = "CN=Test-ondertekenaar, SERIALNUMBER=${Wegwerpketen.STANDAARD_OIN}",
        sleutel: List<String> = Wegwerpketen.P256,
        extensies: List<String> = Wegwerpketen.ONDERTEKENEN,
        geldigheid: List<String> = listOf("-validity", "30", "-startdate", Wegwerpketen.GISTEREN),
    ) = Triple(Wegwerpketen.ALIAS, uitgever, sleutel + listOf("-dname", dname) + extensies + geldigheid)

    private fun tussen(
        extensies: List<String> = Wegwerpketen.CA,
        dagen: Int = 365,
        start: String = Wegwerpketen.GISTEREN,
        dname: String = "CN=Test-tussencertificaat",
    ) = Triple(
        TUSSEN_ALIAS,
        Wegwerpketen.ROOT_ALIAS,
        Wegwerpketen.P256 + listOf("-dname", dname) + extensies + listOf("-validity", dagen.toString(), "-startdate", start),
    )

    val geldig: Keten by lazy { Keten(Wegwerpketen.maak(map("geldig"))) }

    /** Een tweede, onafhankelijke keten: andere root, andere sleutel. */
    val ander: Keten by lazy { Keten(Wegwerpketen.maak(map("ander"))) }

    /** Zelfde opzet, maar het ondertekencertificaat is van een andere organisatie. */
    val andereUitgever: Keten by lazy { Keten(Wegwerpketen.maak(map("uitgever"), uitgeverOin = "00000000000000007777")) }

    val rsa: Keten by lazy { bouw("rsa", blad(sleutel = listOf("-keyalg", "RSA", "-keysize", "2048"))) }

    val p384: Keten by lazy { bouw("p384", blad(sleutel = listOf("-keyalg", "EC", "-groupname", "secp384r1"))) }

    /** Eén dag geldig, begonnen tien dagen geleden. */
    val verlopen: Keten by lazy { bouw("verlopen", blad(geldigheid = listOf("-validity", "1", "-startdate", "-10d"))) }

    val nogNietGeldig: Keten by lazy { bouw("toekomst", blad(geldigheid = listOf("-validity", "30", "-startdate", "+10d"))) }

    /** Root → tussencertificaat → ondertekencertificaat: de gewone vorm onder een publieke PKI. */
    val metTussencertificaat: Keten by lazy { bouw("tussen", tussen(), blad(uitgever = TUSSEN_ALIAS)) }

    /** Het tussencertificaat verloopt eerder dan het ondertekencertificaat eronder. */
    val metKortTussencertificaat: Keten by lazy { bouw("korttussen", tussen(dagen = 5), blad(uitgever = TUSSEN_ALIAS)) }

    /**
     * Het "tussencertificaat" is een gewoon eindcertificaat. Zo ziet een vervalste keten eruit:
     * een houder van een willekeurig certificaat onder de root geeft er zelf een uit.
     */
    val viaNietCa: Keten by lazy { bouw("nietca", tussen(extensies = Wegwerpketen.ONDERTEKENEN), blad(uitgever = TUSSEN_ALIAS)) }

    /** Het tussencertificaat gaat pas over tien dagen in; het ondertekencertificaat eronder geldt al. */
    val metLaatTussencertificaat: Keten by lazy { bouw("laattussen", tussen(start = "+10d"), blad(uitgever = TUSSEN_ALIAS)) }

    /** Het tussencertificaat is een CA, maar mag met zijn sleutel geen certificaten ondertekenen. */
    val tussenZonderKeyCertSign: Keten by lazy {
        bouw("geenkeycertsign", tussen(extensies = listOf("-ext", "bc:c=ca:true", "-ext", "ku:c=digitalSignature")), blad(uitgever = TUSSEN_ALIAS))
    }

    /** Het tussencertificaat draagt de naam van de root, maar is door de root uitgegeven. */
    val tussenMetRootnaam: Keten by lazy {
        bouw("rootnaam", tussen(dname = "CN=Wegwerp-root stelseldocument"), blad(uitgever = TUSSEN_ALIAS))
    }

    /** Het tussencertificaat mag alleen eindcertificaten uitgeven (padlengte 0). */
    val tussenMetPadlengteNul: Keten by lazy {
        bouw("padlengte", tussen(extensies = listOf("-ext", "bc:c=ca:true,pathlen:0", "-ext", "ku:c=keyCertSign")), blad(uitgever = TUSSEN_ALIAS))
    }

    val bladIsCa: Keten by lazy { bouw("bladca", blad(extensies = Wegwerpketen.CA + listOf("-ext", "ku:c=digitalSignature,keyCertSign"))) }

    val zonderSleutelgebruik: Keten by lazy { bouw("geenku", blad(extensies = emptyList())) }

    val alleenVersleutelen: Keten by lazy { bouw("anderku", blad(extensies = listOf("-ext", "ku:c=keyAgreement"))) }

    val tweeSerienummers: Keten by lazy {
        bouw("tweeoin", blad(dname = "CN=Test, SERIALNUMBER=${Wegwerpketen.STANDAARD_OIN}, SERIALNUMBER=00000000000000007777"))
    }

    val samengesteldeRdn: Keten by lazy { bouw("samengesteld", blad(dname = "CN=Test+SERIALNUMBER=${Wegwerpketen.STANDAARD_OIN}")) }

    val zonderSerienummer: Keten by lazy { bouw("geenoin", blad(dname = "CN=Test, O=Zonder OIN")) }

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
