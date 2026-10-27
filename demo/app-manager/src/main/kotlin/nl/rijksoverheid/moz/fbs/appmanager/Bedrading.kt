package nl.rijksoverheid.moz.fbs.appmanager

import jakarta.enterprise.context.ApplicationScoped
import jakarta.enterprise.inject.Produces
import jakarta.inject.Singleton
import nl.rijksoverheid.moz.fbs.rechtstreeks.AppManagerSleutels
import nl.rijksoverheid.moz.fbs.rechtstreeks.GeheugenOpslag
import nl.rijksoverheid.moz.fbs.rechtstreeks.Verifier
import nl.rijksoverheid.moz.fbs.rechtstreeks.VerifierSleutel
import java.time.Clock

/** De Verifier van de App Manager: alleen self-signed presentaties, want bij het activeren is de gebruiker nog onbekend. */
@ApplicationScoped
class Bedrading {

    @Produces
    @Singleton
    fun klok(): Clock = Clock.systemUTC()

    /** Zonder proxy: Verifier is een final klasse uit een library. */
    @Produces
    @Singleton
    fun verifier(config: AppManagerConfig, sleutel: VerifierSleutel, klok: Clock): Verifier {
        val opslag = GeheugenOpslag(klok)

        return Verifier(
            oin = config.oin(),
            sleutel = sleutel,
            appManagers = AppManagerSleutels.GEEN,
            scope = AppManagerConfig.SCOPE,
            challenges = opslag,
            sessies = opslag,
            klok = klok,
        )
    }
}
