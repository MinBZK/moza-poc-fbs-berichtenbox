package nl.rijksoverheid.moz.fbs.stelselregister.stelseldocument

import io.quarkus.runtime.LaunchMode
import jakarta.enterprise.context.ApplicationScoped
import org.jboss.logging.Logger
import java.nio.file.Path
import java.time.Clock
import java.time.Duration
import java.time.Instant

/**
 * Levert de [Ondertekensleutel]: uit de geconfigureerde keystore, of — alleen in ontwikkel- en
 * testmodus — een wegwerpketen.
 *
 * De terugval hangt aan de launch mode waarmee de applicatie gebouwd is en niet aan de
 * profielnaam. Een profiel is bij het starten te kiezen; een uitgerolde dienst die zo ongemerkt
 * met een wegwerpsleutel ondertekent, levert documenten die geen afnemer kan verifiëren terwijl
 * alles groen meldt.
 */
@ApplicationScoped
class Sleutelbron(
    private val config: StelseldocumentConfig,
    private val clock: Clock,
) {

    val sleutel: Ondertekensleutel by lazy {
        laad(config.keystore(), LaunchMode.current(), clock.instant(), config.uitgeverOin())
    }

    companion object {
        private val log = Logger.getLogger(Sleutelbron::class.java)
        private val WAARSCHUWEN_BINNEN: Duration = Duration.ofDays(30)

        fun laad(
            keystore: StelseldocumentConfig.Keystore,
            launchMode: LaunchMode,
            nu: Instant,
            uitgeverOin: String,
        ): Ondertekensleutel {
            val pad = keystore.pad().map(String::trim).filter(String::isNotEmpty).orElse(null)

            val sleutel = if (pad != null) {
                uitKeystore(Path.of(pad), keystore, nu)
            } else if (launchMode == LaunchMode.DEVELOPMENT || launchMode == LaunchMode.TEST) {
                log.warn("Geen stelseldocument.keystore.pad gezet: het stelseldocument wordt ondertekend met een wegwerpketen")
                Wegwerpketen.alsOndertekensleutel(nu, uitgeverOin)
            } else {
                throw OngeldigeOndertekensleutelException(
                    "stelseldocument.keystore.pad ontbreekt. Buiten ontwikkel- en testmodus ondertekent de dienst " +
                        "alleen met een beheerde keystore",
                )
            }

            waarschuwBijNaderendVerloop(sleutel, nu)

            return sleutel
        }

        private fun uitKeystore(pad: Path, keystore: StelseldocumentConfig.Keystore, nu: Instant): Ondertekensleutel {
            val wachtwoord = keystore.wachtwoord().orElseThrow {
                OngeldigeOndertekensleutelException("stelseldocument.keystore.wachtwoord ontbreekt")
            }

            return Ondertekensleutel.uitKeystore(pad, wachtwoord.toCharArray(), keystore.alias(), nu)
        }

        private fun waarschuwBijNaderendVerloop(sleutel: Ondertekensleutel, nu: Instant) {
            val resterend = sleutel.resterend(nu)

            if (resterend < WAARSCHUWEN_BINNEN) {
                log.warnf(
                    "Het ondertekencertificaat (kid %s) verloopt over %d dagen, op %s. Vervang het vóór die datum",
                    sleutel.kid,
                    resterend.toDays(),
                    sleutel.certificaat.notAfter.toInstant(),
                )
            }
        }
    }
}
