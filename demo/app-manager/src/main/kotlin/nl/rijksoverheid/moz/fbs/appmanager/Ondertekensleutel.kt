package nl.rijksoverheid.moz.fbs.appmanager

import io.quarkus.runtime.LaunchMode
import io.quarkus.runtime.Startup
import jakarta.enterprise.context.ApplicationScoped
import jakarta.enterprise.inject.Produces
import jakarta.inject.Singleton
import nl.rijksoverheid.moz.fbs.rechtstreeks.VerifierSleutel
import org.jboss.logging.Logger
import java.math.BigInteger
import java.nio.file.Files
import java.nio.file.Path
import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.Signature
import java.security.interfaces.ECPrivateKey
import java.security.interfaces.ECPublicKey
import java.security.spec.ECGenParameterSpec
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.X509EncodedKeySpec
import java.util.Base64

/**
 * De sleutel waarmee de App Manager challenges en credentials tekent. Hij komt uit een PEM-bestand
 * met een `PRIVATE KEY`- en een `PUBLIC KEY`-blok; alleen een ontwikkel- of testbuild valt zonder
 * bestand terug op een wegwerpsleutel. Die terugval hangt aan de launch mode en niet aan het
 * profiel, zodat een uitgerold image nooit met een wegwerpsleutel tekent.
 */
@Startup
@ApplicationScoped
class Ondertekensleutel(private val config: AppManagerConfig) {

    private val log = Logger.getLogger(Ondertekensleutel::class.java)
    private val paar: KeyPair = laad(LaunchMode.current())

    /** De publieke helft; die staat in het stelseldocument, en de tests controleren er credentials mee. */
    val publiek: ECPublicKey = paar.public as ECPublicKey

    /** De private helft; daarmee tekent de App Manager ook de credentials die hij uitgeeft. */
    internal val privaat: ECPrivateKey = paar.private as ECPrivateKey

    init {
        // De coördinaten zijn publiek; zo kan wie het stelselregister inricht ze overnemen.
        log.infof("App Manager %s tekent met kid=%s x=%s y=%s", config.oin(), config.kid(), x(publiek), y(publiek))
    }

    /** Zonder proxy: VerifierSleutel is een final klasse uit een library. */
    @Produces
    @Singleton
    fun verifierSleutel(): VerifierSleutel = VerifierSleutel(config.kid(), privaat, config.sleutelGeldigTot())

    private fun laad(launchMode: LaunchMode): KeyPair {
        val pad = config.sleutelPad().orElse(null) ?: return wegwerp(launchMode)

        return leesPem(Files.readString(Path.of(pad)))
    }

    private fun wegwerp(launchMode: LaunchMode): KeyPair {
        check(launchMode.isDevOrTest) { "app-manager.sleutel-pad ontbreekt; alleen een ontwikkel- of testbuild maakt een wegwerpsleutel" }

        log.warn("Geen app-manager.sleutel-pad gezet: de App Manager tekent met een wegwerpsleutel")

        return KeyPairGenerator.getInstance("EC").run {
            initialize(ECGenParameterSpec("secp256r1"))
            generateKeyPair()
        }
    }

    companion object {
        private val B64URL = Base64.getUrlEncoder().withoutPadding()
        private val BLOK = Regex("-----BEGIN ([A-Z ]+)-----([^-]+)-----END \\1-----")
        private const val COORDINAAT_BYTES = 32

        /** Leest een PEM met een private key (PKCS#8) en de bijbehorende public key (SPKI). */
        fun leesPem(pem: String): KeyPair {
            val blokken = BLOK.findAll(pem).associate { it.groupValues[1] to Base64.getMimeDecoder().decode(it.groupValues[2]) }
            val privaat = blokken["PRIVATE KEY"] ?: error("geen PRIVATE KEY-blok in de sleutel van de App Manager")
            val publiek = blokken["PUBLIC KEY"] ?: error("geen PUBLIC KEY-blok in de sleutel van de App Manager")
            val sleutels = KeyFactory.getInstance("EC")

            val paar = KeyPair(sleutels.generatePublic(X509EncodedKeySpec(publiek)), sleutels.generatePrivate(PKCS8EncodedKeySpec(privaat)))

            // Twee blokken die niet bij elkaar horen, gaven een App Manager die tekent met een
            // sleutel die niemand kan controleren.
            require(horenBijElkaar(paar)) { "het PUBLIC KEY-blok hoort niet bij de private key van de App Manager" }

            return paar
        }

        private fun horenBijElkaar(paar: KeyPair): Boolean {
            val proef = "app-manager".toByteArray()

            val handtekening = Signature.getInstance("SHA256withECDSA").run {
                initSign(paar.private)
                update(proef)
                sign()
            }

            return Signature.getInstance("SHA256withECDSA").run {
                initVerify(paar.public)
                update(proef)
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
