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
    internal const val GISTEREN = "-1d"

    /** De test-OIN van de stelselbeheerder in de demo-omgeving. */
    const val STANDAARD_OIN = "00000000000000001000"

    private const val KEYTOOL_TIMEOUT_SECONDEN = 60L
    private const val WACHTWOORD_BYTES = 24

    /** De keystore die [maak] oplevert; het wachtwoord bestaat alleen in het geheugen van dit proces. */
    class Keystorebestand(val pad: Path, val wachtwoord: CharArray)

    /**
     * Schrijft `keystore.p12` in [map] met de root onder [ROOT_ALIAS] en het ondertekencertificaat
     * onder [ALIAS], met [uitgeverOin] in het subject.
     */
    fun maak(map: Path, uitgeverOin: String = STANDAARD_OIN): Keystorebestand {
        val pad = map.resolve("keystore.p12")
        val wachtwoord = nieuwWachtwoord()
        val opslag = opslag(pad, wachtwoord)

        keytool(ROOT + opslag)
        keytool(
            listOf("-genkeypair", "-alias", ALIAS, "-signer", ROOT_ALIAS) + P256 +
                listOf("-dname", "CN=Wegwerp-ondertekenaar stelseldocument, SERIALNUMBER=$uitgeverOin") +
                ONDERTEKENEN + listOf("-validity", "30", "-startdate", GISTEREN) +
                opslag,
        )

        return Keystorebestand(pad, wachtwoord.toCharArray())
    }

    internal fun opslag(pad: Path, wachtwoord: String): List<String> =
        listOf("-keystore", pad.toString(), "-storetype", "PKCS12", "-storepass", wachtwoord)

    internal val P256 = listOf("-keyalg", "EC", "-groupname", "secp256r1")
    internal val ONDERTEKENEN = listOf("-ext", "ku:c=digitalSignature")
    internal val CA = listOf("-ext", "bc:c=ca:true", "-ext", "ku:c=keyCertSign,cRLSign")

    /** De argumenten voor de wegwerp-root, zonder de opslag. */
    internal val ROOT: List<String> = listOf("-genkeypair", "-alias", ROOT_ALIAS) + P256 +
        listOf("-dname", "CN=Wegwerp-root stelseldocument") + CA + listOf("-validity", "3650", "-startdate", "-1d")

    internal fun nieuwWachtwoord(): String =
        Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(WACHTWOORD_BYTES).also(SecureRandom()::nextBytes))

    /**
     * De uitvoer gaat naar een bestand en niet naar een pipe: lezen tot het einde van een pipe
     * blokkeert zolang het proces leeft, en dan gaat de timeout nooit af.
     */
    internal fun keytool(argumenten: List<String>) {
        val keytool = Path.of(System.getProperty("java.home"), "bin", "keytool").toString()
        val uitvoer = Files.createTempFile("keytool", ".log")

        try {
            val proces = ProcessBuilder(listOf(keytool) + argumenten)
                .redirectErrorStream(true)
                .redirectOutput(uitvoer.toFile())
                .start()

            if (!proces.waitFor(KEYTOOL_TIMEOUT_SECONDEN, TimeUnit.SECONDS)) {
                proces.destroyForcibly()
                error("keytool ${argumenten.first()} reageerde niet binnen $KEYTOOL_TIMEOUT_SECONDEN seconden")
            }

            // Zonder de argumenten: daar staat het wachtwoord van de keystore in.
            check(proces.exitValue() == 0) { "keytool ${argumenten.first()} mislukte: ${Files.readString(uitvoer)}" }
        } finally {
            Files.deleteIfExists(uitvoer)
        }
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
