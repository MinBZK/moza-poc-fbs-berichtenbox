package nl.rijksoverheid.moz.fbs.stelselregister.stelseldocument

import java.nio.file.Files
import java.nio.file.Path
import java.security.SecureRandom
import java.time.Instant
import java.util.Base64
import java.util.concurrent.TimeUnit

/**
 * Maakt een wegwerp-root met een ondertekencertificaat eronder, zodat ontwikkel- en testmodus
 * zonder beheerde keystore een document met een echte keten uitgeven.
 *
 * Via `keytool` en niet in code: de JDK kan een sleutelpaar maken maar heeft geen publieke API om
 * een X.509-certificaat uit te geven, en een cryptobibliotheek alleen hiervoor zou ook in het
 * productie-image belanden. `keytool` zit in elke JDK waarmee ontwikkeld en getest wordt.
 */
object Wegwerpketen {

    const val ROOT_ALIAS = "root"
    const val ALIAS = "stelseldocument"

    /**
     * De geldigheid begint een dag terug: wie het moment van "nu" vóór het aanmaken vastlegde, zou
     * anders een certificaat krijgen dat een fractie van een seconde later pas ingaat.
     */
    private const val GISTEREN = "-1d"

    /** De test-OIN van de stelselbeheerder in de demo-omgeving. */
    const val STANDAARD_OIN = "00000000000000001000"

    private const val KEYTOOL_TIMEOUT_SECONDEN = 60L
    private const val WACHTWOORD_BYTES = 24

    /** De keystore die [maak] oplevert; het wachtwoord bestaat alleen in het geheugen van dit proces. */
    class Keystorebestand(val pad: Path, val wachtwoord: CharArray)

    /**
     * Schrijft `keystore.p12` in [map] met de root onder [ROOT_ALIAS] en het ondertekencertificaat
     * onder [ALIAS], met [uitgeverOin] in het subject. [sleutelalgoritme], [geldigheidDagen] en [startdatum] bestaan voor tests die een
     * afwijkende sleutel of een verlopen certificaat nodig hebben.
     */
    fun maak(
        map: Path,
        uitgeverOin: String = STANDAARD_OIN,
        sleutelalgoritme: List<String> = listOf("-keyalg", "EC", "-groupname", "secp256r1"),
        geldigheidDagen: Int = 30,
        startdatum: String = GISTEREN,
    ): Keystorebestand {
        val pad = map.resolve("keystore.p12")
        val wachtwoord = nieuwWachtwoord()
        val opslag = listOf("-keystore", pad.toString(), "-storetype", "PKCS12", "-storepass", wachtwoord)

        keytool(
            listOf("-genkeypair", "-alias", ROOT_ALIAS, "-keyalg", "EC", "-groupname", "secp256r1") +
                listOf("-dname", "CN=Wegwerp-root stelseldocument", "-ext", "bc:c=ca:true", "-validity", "3650") +
                listOf("-startdate", GISTEREN) +
                opslag,
        )
        keytool(
            listOf("-genkeypair", "-alias", ALIAS, "-signer", ROOT_ALIAS) + sleutelalgoritme +
                listOf("-dname", "CN=Wegwerp-ondertekenaar stelseldocument, SERIALNUMBER=$uitgeverOin", "-validity", geldigheidDagen.toString()) +
                listOf("-startdate", startdatum) +
                opslag,
        )

        return Keystorebestand(pad, wachtwoord.toCharArray())
    }

    private fun nieuwWachtwoord(): String =
        Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(WACHTWOORD_BYTES).also(SecureRandom()::nextBytes))

    private fun keytool(argumenten: List<String>) {
        val keytool = Path.of(System.getProperty("java.home"), "bin", "keytool").toString()
        val proces = ProcessBuilder(listOf(keytool) + argumenten).redirectErrorStream(true).start()
        val uitvoer = proces.inputStream.bufferedReader().use { it.readText() }

        check(proces.waitFor(KEYTOOL_TIMEOUT_SECONDEN, TimeUnit.SECONDS)) { "keytool reageert niet" }
        // Zonder de argumenten: daar staat het wachtwoord van de keystore in.
        check(proces.exitValue() == 0) { "keytool ${argumenten.first()} mislukte: $uitvoer" }
    }

    /** Maakt de keten in een tijdelijke map, laadt hem langs dezelfde controles als een beheerde keystore en ruimt op. */
    fun alsOndertekensleutel(nu: Instant, uitgeverOin: String): Ondertekensleutel {
        val map = Files.createTempDirectory("stelseldocument-wegwerp")

        try {
            val bestand = maak(map, uitgeverOin)

            return Ondertekensleutel.uitKeystore(bestand.pad, bestand.wachtwoord, ALIAS, nu, Sleutelherkomst.WEGWERP)
        } finally {
            map.toFile().deleteRecursively()
        }
    }
}
