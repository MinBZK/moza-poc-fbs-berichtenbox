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
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

/**
 * Eén [Verifier] per gesimuleerd magazijn, met één gedeelde opslag. De opslag bewaart nonces en
 * sessies per OIN, dus een token van magazijn A werkt niet bij magazijn B.
 *
 * Elk magazijn tekent zijn challenges met een eigen sleutel, gemaakt bij de eerste challenge. De
 * `kid` draagt het moment van de start, zodat dezelfde `kid` nooit twee sleutels aanwijst. De app
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
    private val start = klok.instant().epochSecond
    private val verifiers = ConcurrentHashMap<String, Verifier>()

    val vertrouwdeProxies: List<ClientAdres.Netwerk> = config.vertrouwdeProxies().orElse(emptyList()).map(ClientAdres.Netwerk::van)

    init {
        val aantal = appManagers.keys.map { it.first }.distinct().size

        if (aantal == 0) {
            log.warn("Rechtstreeks ophalen: geen App Manager vertrouwd; elke complete geeft 401")
        } else {
            log.infof("Rechtstreeks ophalen: %d App Manager(s) vertrouwd", aantal)
        }

        appManagers.filterValues { !it.geldigTot.isAfter(klok.instant()) }.keys.forEach { (oin, kid) ->
            log.warnf("Rechtstreeks ophalen: sleutel %s van App Manager %s is verlopen", kid, oin)
        }
    }

    fun voor(oin: String): Verifier = verifiers.computeIfAbsent(oin) {
        Verifier(it, wegwerpsleutel(it), AppManagerSleutels { iss, kid -> appManagers[iss to kid] }, SCOPE, opslag, opslag, klok)
    }

    private fun wegwerpsleutel(oin: String): VerifierSleutel {
        val paar = KeyPairGenerator.getInstance("EC").run {
            initialize(ECGenParameterSpec("secp256r1"))
            generateKeyPair()
        }

        // Geen echte vervaldatum: deze sleutel staat nergens gepubliceerd, en een datum die verstrijkt
        // terwijl de simulator draait, maakt van elke challenge een 500.
        return VerifierSleutel("$oin-$start", paar.private as ECPrivateKey, ONBEPAALD)
    }

    companion object {
        const val SCOPE = "nl.rijksoverheid.moz.fbs.berichten"

        private val ONBEPAALD: Instant = Instant.parse("9999-12-31T00:00:00Z")
        private val OIN = Regex("[0-9]{20}")

        /** Fail-fast bij de start: een kapotte sleutel in de configuratie hoort de boot te laten falen. */
        fun appManagerSleutels(config: RechtstreeksConfig): Map<Pair<String, String>, AppManagerSleutel> =
            config.appManagers().flatMap { (oin, appManager) ->
                require(OIN.matches(oin)) { "simulator.rechtstreeks.app-managers.\"$oin\": de sleutel hoort een OIN van 20 cijfers te zijn" }
                require(appManager.sleutels().isNotEmpty()) { "simulator.rechtstreeks.app-managers.\"$oin\" heeft geen sleutels" }

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
