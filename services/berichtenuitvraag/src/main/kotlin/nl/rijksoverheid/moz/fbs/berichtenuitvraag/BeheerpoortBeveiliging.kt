package nl.rijksoverheid.moz.fbs.berichtenuitvraag

import io.quarkus.vertx.http.ManagementInterface
import io.vertx.core.http.HttpHeaders
import io.vertx.ext.web.RoutingContext
import jakarta.enterprise.context.ApplicationScoped
import jakarta.enterprise.event.Observes
import nl.rijksoverheid.moz.fbs.common.BeheerpoortToegang
import org.eclipse.microprofile.config.inject.ConfigProperty
import org.jboss.logging.Logger
import java.util.Optional
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * Laat op de beheerpoort alleen verzoeken door met het token uit `fbs.beheerpoort.token`
 * (`METRICS_AUTH_TOKEN`); de regel zelf staat in [BeheerpoortToegang]. Een geweigerd verzoek
 * krijgt een kale 401 met `WWW-Authenticate: Bearer`, zonder body.
 *
 * Op de router van de beheerpoort, niet als [io.quarkus.vertx.http.runtime.filters.Filters]:
 * die bereiken alleen de applicatiepoort. De handler staat vóór elke route van Quarkus, zodat
 * ook een pad dat later op de beheerpoort bijkomt achter het token valt.
 *
 * Per dienst en niet in fbs-common, om dezelfde reden als `SecurityHeadersRegistratie`: een
 * observer op een Vert.x-type in fbs-common zou elke consumer erven, ook die zonder HTTP-laag.
 * De kopieën in magazijn, uitvraag en stelselregister zijn identiek en moeten dat blijven.
 */
@ApplicationScoped
class BeheerpoortBeveiliging(
    @param:ConfigProperty(name = "fbs.beheerpoort.token") private val token: Optional<String>,
    @param:ConfigProperty(name = "quarkus.profile") private val profiel: String,
) {

    private val eersteToegelaten = AtomicBoolean(false)
    private val vorigeWeigermelding = AtomicLong(GEEN)
    private val geweigerdSindsMelding = AtomicLong()

    fun registreer(@Observes beheer: ManagementInterface) {
        if (token.isEmpty && profiel !in BeheerpoortToegang.PROFIELEN_ZONDER_TOKEN) {
            log.warnf(
                "%s: geen METRICS_AUTH_TOKEN in profiel '%s'; de beheerpoort weigert elk verzoek " +
                    "tot het platform een token levert (op ZAD: de dienst metrics-scraper)",
                BeheerpoortToegang.TOKEN_ONTBREEKT,
                profiel,
            )
        }

        beheer.router().route().order(VOLGORDE).handler(::bewaak)
    }

    private fun bewaak(context: RoutingContext) {
        val authorization = context.request().getHeader(HttpHeaders.AUTHORIZATION)

        if (BeheerpoortToegang.toegestaan(token.orElse(null), authorization, profiel)) {
            meldEersteToegang()
            context.next()

            return
        }

        meldWeigering()
        context.response()
            .setStatusCode(NIET_GEAUTORISEERD)
            .putHeader(WWW_AUTHENTICATE, "Bearer")
            .end()
    }

    // Eén regel als bewijs dat de scraper binnenkomt: van de scrape zelf ziet de dienst verder niets.
    private fun meldEersteToegang() {
        if (token.isPresent && eersteToegelaten.compareAndSet(false, true)) {
            log.info("Beheerpoort: eerste verzoek met een geldig token ontvangen")
        }
    }

    /**
     * Hooguit één regel per [MELDINTERVAL_NANOS]: een scraper met een verkeerd token komt elke
     * halve minuut, en wie de poort afzoekt, bepaalt anders zelf hoeveel log er ontstaat.
     */
    private fun meldWeigering() {
        val aantal = geweigerdSindsMelding.incrementAndGet()
        val nu = System.nanoTime()
        val vorige = vorigeWeigermelding.get()

        if ((vorige == GEEN || nu - vorige >= MELDINTERVAL_NANOS) && vorigeWeigermelding.compareAndSet(vorige, nu)) {
            geweigerdSindsMelding.addAndGet(-aantal)
            log.warnf(
                "%s: %d verzoek(en) op de beheerpoort zonder geldig bearer-token geweigerd sinds de vorige melding",
                BeheerpoortToegang.GEWEIGERD,
                aantal,
            )
        }
    }

    private companion object {
        private val log = Logger.getLogger(BeheerpoortBeveiliging::class.java)

        /** Vóór de routes die Quarkus zelf op de beheerpoort zet; die hebben een volgorde vanaf 0. */
        private const val VOLGORDE = -1_000

        private const val NIET_GEAUTORISEERD = 401

        private const val WWW_AUTHENTICATE = "WWW-Authenticate"

        private const val GEEN = Long.MIN_VALUE

        private val MELDINTERVAL_NANOS = TimeUnit.MINUTES.toNanos(10)
    }
}
