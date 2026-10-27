package nl.rijksoverheid.moz.fbs.magazijnsimulator.rechtstreeks

import io.quarkus.runtime.Startup
import jakarta.enterprise.context.ApplicationScoped
import nl.rijksoverheid.moz.fbs.rechtstreeks.AppManagerSleutel
import nl.rijksoverheid.moz.fbs.rechtstreeks.AppManagerSleutels
import nl.rijksoverheid.moz.fbs.rechtstreeks.GeheugenOpslag
import nl.rijksoverheid.moz.fbs.rechtstreeks.Verifier
import nl.rijksoverheid.moz.fbs.rechtstreeks.VerifierSleutel
import org.jboss.logging.Logger
import java.security.KeyPairGenerator
import java.security.interfaces.ECPrivateKey
import java.security.spec.ECGenParameterSpec
import java.time.Clock
import java.time.Duration
import java.util.concurrent.ConcurrentHashMap

/**
 * Eén [Verifier] per gesimuleerd magazijn, met één gedeelde opslag. De opslag bewaart nonces en
 * sessies per OIN, dus een token van magazijn A werkt niet bij magazijn B.
 *
 * Elk magazijn tekent zijn challenges met een eigen sleutel die bij de start ontstaat. De app
 * controleert die handtekening nog niet; zodra dat tegen het stelseldocument gebeurt, moeten de
 * public keys daar staan.
 * TODO(#1215): sleutels per OIN uit de set van genereer-magazijnen.py, gepubliceerd in het stelseldocument.
 */
@Startup
@ApplicationScoped
class MagazijnVerifiers(config: RechtstreeksConfig, private val klok: Clock) {

    private val log = Logger.getLogger(MagazijnVerifiers::class.java)

    private val opslag = GeheugenOpslag(klok)
    private val appManagers = appManagerSleutels(config)
    private val sleutelsGeldigTot = klok.instant().plus(WEGWERPSLEUTEL_GELDIG)
    private val verifiers = ConcurrentHashMap<String, Verifier>()

    init {
        log.infof("Rechtstreeks ophalen: %d App Manager(s) vertrouwd; elk magazijn tekent met een sleutel van deze start", appManagers.size)
    }

    fun voor(oin: String): Verifier = verifiers.computeIfAbsent(oin) {
        Verifier(it, wegwerpsleutel(it), AppManagerSleutels { iss, kid -> appManagers[iss to kid] }, SCOPE, opslag, opslag, klok)
    }

    private fun wegwerpsleutel(oin: String): VerifierSleutel {
        val paar = KeyPairGenerator.getInstance("EC").run {
            initialize(ECGenParameterSpec("secp256r1"))
            generateKeyPair()
        }

        return VerifierSleutel("$oin-1", paar.private as ECPrivateKey, sleutelsGeldigTot)
    }

    companion object {
        const val SCOPE = "nl.rijksoverheid.moz.fbs.berichten"

        private val WEGWERPSLEUTEL_GELDIG: Duration = Duration.ofDays(365)

        /** Fail-fast bij de start: een kapotte sleutel in de configuratie hoort de boot te laten falen. */
        fun appManagerSleutels(config: RechtstreeksConfig): Map<Pair<String, String>, AppManagerSleutel> =
            config.appManagers().flatMap { (oin, appManager) ->
                appManager.sleutels().map { (kid, sleutel) ->
                    val gelezen = try {
                        AppManagerSleutel.uitCoordinaten(sleutel.x(), sleutel.y(), sleutel.geldigTot())
                    } catch (fout: IllegalArgumentException) {
                        throw IllegalArgumentException("simulator.rechtstreeks.app-managers.\"$oin\".sleutels.\"$kid\" is geen geldige P-256-sleutel", fout)
                    }

                    (oin to kid) to gelezen
                }
            }.toMap()
    }
}
