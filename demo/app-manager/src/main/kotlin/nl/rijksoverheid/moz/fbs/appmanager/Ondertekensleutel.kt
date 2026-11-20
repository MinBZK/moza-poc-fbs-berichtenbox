package nl.rijksoverheid.moz.fbs.appmanager

import io.quarkus.runtime.LaunchMode
import io.quarkus.runtime.Startup
import jakarta.enterprise.context.ApplicationScoped
import jakarta.enterprise.inject.Produces
import jakarta.inject.Singleton
import nl.rijksoverheid.moz.fbs.rechtstreeks.VerifierSleutel
import org.jboss.logging.Logger
import java.io.IOException
import java.math.BigInteger
import java.nio.file.Files
import java.nio.file.Path
import java.security.AlgorithmParameters
import java.security.GeneralSecurityException
import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.Signature
import java.security.interfaces.ECPrivateKey
import java.security.interfaces.ECPublicKey
import java.security.spec.ECGenParameterSpec
import java.security.spec.ECParameterSpec
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.X509EncodedKeySpec
import java.util.Base64

/**
 * De sleutel waarmee de App Manager challenges en credentials ondertekent. Hij komt uit een PEM-bestand
 * met een `PRIVATE KEY`- en een `PUBLIC KEY`-blok; alleen een ontwikkel- of testbuild valt zonder
 * bestand terug op een wegwerpsleutel. Die terugval hangt aan de launch mode en niet aan het
 * profiel, zodat een uitgerold image nooit met een wegwerpsleutel ondertekent.
 */
@Startup
@ApplicationScoped
class Ondertekensleutel(private val config: AppManagerConfig) {

    private val log = Logger.getLogger(Ondertekensleutel::class.java)
    private val paar: KeyPair = laad(LaunchMode.current())

    /** De publieke helft; die hoort in het stelseldocument, en de tests controleren er credentials mee. */
    val publiek: ECPublicKey = paar.public as ECPublicKey

    /** De private helft; daarmee ondertekent de App Manager ook de credentials die hij uitgeeft. */
    internal val privaat: ECPrivateKey = paar.private as ECPrivateKey

    init {
        // De coördinaten zijn publiek; zo kan wie het stelselregister inricht ze overnemen.
        log.infof("App Manager %s ondertekent met kid=%s x=%s y=%s", config.oin(), config.kid(), x(publiek), y(publiek))
    }

    /** Zonder proxy: VerifierSleutel is een final klasse uit een library. */
    @Produces
    @Singleton
    fun verifierSleutel(): VerifierSleutel = VerifierSleutel(config.kid(), privaat, config.sleutelGeldigTot())

    private fun laad(launchMode: LaunchMode): KeyPair {
        val pad = config.sleutelPad().orElse(null) ?: return wegwerp(launchMode)

        // Elke fout bij het lezen noemt de property, zodat de operator weet waar hij moet kijken. Kotlin
        // kent geen multi-catch; de `when` kiest de typen, de rest gaat ongewijzigd door.
        return try {
            leesPem(Files.readString(Path.of(pad)))
        } catch (fout: Exception) {
            throw when (fout) {
                is IOException, is GeneralSecurityException, is IllegalArgumentException, is IllegalStateException -> onbruikbaar(pad, fout)
                else -> fout
            }
        }
    }

    private fun onbruikbaar(pad: String, fout: Exception): IllegalStateException =
        IllegalStateException("app-manager.sleutel-pad ($pad) bevat geen bruikbare P-256-sleutel: ${fout.message}", fout)

    private fun wegwerp(launchMode: LaunchMode): KeyPair {
        check(launchMode.isDevOrTest) { "app-manager.sleutel-pad ontbreekt; alleen een ontwikkel- of testbuild maakt een wegwerpsleutel" }

        log.warn("Geen app-manager.sleutel-pad gezet: de App Manager ondertekent met een wegwerpsleutel")

        return KeyPairGenerator.getInstance("EC").run {
            initialize(ECGenParameterSpec("secp256r1"))
            generateKeyPair()
        }
    }

    companion object {
        private val B64URL = Base64.getUrlEncoder().withoutPadding()
        private val BLOK = Regex("-----BEGIN ([A-Z ]+)-----([^-]+)-----END \\1-----")
        private const val COORDINAAT_BYTES = 32

        private val P256: ECParameterSpec = AlgorithmParameters.getInstance("EC").run {
            init(ECGenParameterSpec("secp256r1"))
            getParameterSpec(ECParameterSpec::class.java)
        }

        /** Leest een PEM met een private key (PKCS#8) en de bijbehorende public key (SPKI). */
        fun leesPem(pem: String): KeyPair {
            val blokken = BLOK.findAll(pem).associate { it.groupValues[1] to Base64.getMimeDecoder().decode(it.groupValues[2]) }
            val privaat = blokken["PRIVATE KEY"] ?: error("geen PRIVATE KEY-blok in de sleutel van de App Manager")
            val publiek = blokken["PUBLIC KEY"] ?: error("geen PUBLIC KEY-blok in de sleutel van de App Manager")
            val sleutels = KeyFactory.getInstance("EC")

            val paar = KeyPair(sleutels.generatePublic(X509EncodedKeySpec(publiek)), sleutels.generatePrivate(PKCS8EncodedKeySpec(privaat)))

            // ES256 vraagt P-256; een andere curve zou pas bij het eerste credential misgaan.
            require((paar.public as? ECPublicKey)?.params?.curve == P256.curve) { "de sleutel van de App Manager ligt niet op P-256" }

            // Twee blokken die niet bij elkaar horen, geven een App Manager die ondertekent met een
            // sleutel die niemand kan controleren.
            require(horenBijElkaar(paar)) { "het PUBLIC KEY-blok hoort niet bij de private key van de App Manager" }

            return paar
        }

        private fun horenBijElkaar(paar: KeyPair): Boolean {
            val testbericht = "app-manager".toByteArray()

            val handtekening = Signature.getInstance("SHA256withECDSA").run {
                initSign(paar.private)
                update(testbericht)
                sign()
            }

            return Signature.getInstance("SHA256withECDSA").run {
                initVerify(paar.public)
                update(testbericht)
                verify(handtekening)
            }
        }

        fun x(sleutel: ECPublicKey): String = coordinaat(sleutel.w.affineX)

        fun y(sleutel: ECPublicKey): String = coordinaat(sleutel.w.affineY)

        private fun coordinaat(waarde: BigInteger): String {
            val bytes = waarde.toByteArray().dropWhile { it == 0.toByte() }.toByteArray()

            return B64URL.encodeToString(ByteArray(COORDINAAT_BYTES - bytes.size) + bytes)
        }
    }
}
