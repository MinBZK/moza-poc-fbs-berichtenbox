package nl.rijksoverheid.moz.fbs.berichtenuitvraag

import io.quarkus.vertx.http.ManagementInterface
import io.vertx.core.http.HttpHeaders
import io.vertx.ext.web.RoutingContext
import jakarta.enterprise.context.ApplicationScoped
import jakarta.enterprise.event.Observes
import nl.rijksoverheid.moz.fbs.common.BeheerpoortToegang
import nl.rijksoverheid.moz.fbs.common.Meldbegrenzer
import org.eclipse.microprofile.config.inject.ConfigProperty
import org.jboss.logging.Logger
import java.util.Optional
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Laat op de beheerpoort alleen verzoeken door met het token uit `fbs.beheerpoort.token`
 * (`METRICS_AUTH_TOKEN`); de regel zelf staat in [BeheerpoortToegang]. Een geweigerd verzoek
 * krijgt een kale 401 met `WWW-Authenticate: Bearer`, zonder body.
 *
 * Op de router van de beheerpoort, niet als [io.quarkus.vertx.http.runtime.filters.Filters]:
 * die bereiken alleen de applicatiepoort. De handler staat vóór elke route die een pad bedient,
 * zodat ook een pad dat later op de beheerpoort bijkomt achter het token valt.
 *
 * Per dienst en niet in fbs-common, om dezelfde reden als `SecurityHeadersRegistratie`: een
 * observer op een Vert.x-type in fbs-common zou elke consumer erven, ook die zonder HTTP-laag.
 * De kopieën in magazijn, uitvraag en stelselregister zijn identiek en moeten dat blijven.
 */
@ApplicationScoped
class BeheerpoortBeveiliging(
    @ConfigProperty(name = "fbs.beheerpoort.token") token: Optional<String>,
    @param:ConfigProperty(name = "quarkus.profile") private val profiel: String,
) {

    private val geheim = BeheerpoortToegang.ingesteld(token.orElse(null))

    /** `false` bij de start en na elke weigering, zodat ook het herstel na een verkeerd token in de log komt. */
    private val toegangGemeld = AtomicBoolean(false)

    private val weigeringen = Meldbegrenzer(TimeUnit.MINUTES.toNanos(MELDINTERVAL_MINUTEN))

    fun registreer(@Observes beheer: ManagementInterface) {
        if (BeheerpoortToegang.dichtZonderToken(geheim, profiel)) {
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

        if (BeheerpoortToegang.toegestaan(geheim, authorization, profiel)) {
            meldToegang()
            context.next()

            return
        }

        meldWeigering()
        context.response()
            .setStatusCode(UNAUTHORIZED)
            .putHeader(WWW_AUTHENTICATE, "Bearer")
            .end()
    }

    // Eén regel als bewijs dat de scraper binnenkomt: van de scrape zelf ziet de dienst verder niets.
    private fun meldToegang() {
        if (geheim != null && toegangGemeld.compareAndSet(false, true)) {
            log.info("Beheerpoort: verzoek met een geldig token ontvangen (het eerste sinds de start of sinds een weigering)")
        }
    }

    private fun meldWeigering() {
        toegangGemeld.set(false)

        weigeringen.gebeurtenis()?.let { aantal ->
            log.warnf(
                "%s: %d verzoek(en) op de beheerpoort zonder geldig bearer-token geweigerd sinds de vorige melding",
                BeheerpoortToegang.GEWEIGERD,
                aantal,
            )
        }
    }

    private companion object {
        private val log = Logger.getLogger(BeheerpoortBeveiliging::class.java)

        /**
         * Vóór de handlers die op de beheerpoort een pad bedienen (`/q/metrics` en wat erbij komt).
         * Alleen de host-validatie, CORS en de body-handler van Quarkus staan eerder, en die leveren
         * geen inhoud.
         */
        private const val VOLGORDE = -1_000

        private const val UNAUTHORIZED = 401

        private const val WWW_AUTHENTICATE = "WWW-Authenticate"

        private const val MELDINTERVAL_MINUTEN = 10L
    }
}
