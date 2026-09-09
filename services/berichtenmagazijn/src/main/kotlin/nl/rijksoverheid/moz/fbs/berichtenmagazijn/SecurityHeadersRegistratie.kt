package nl.rijksoverheid.moz.fbs.berichtenmagazijn

import io.quarkus.vertx.http.runtime.filters.Filters
import io.vertx.ext.web.RoutingContext
import jakarta.enterprise.context.ApplicationScoped
import jakarta.enterprise.event.Observes
import nl.rijksoverheid.moz.fbs.common.SecurityHeaders
import org.eclipse.microprofile.config.inject.ConfigProperty

/**
 * Plaatst de [SecurityHeaders] op élke response — JAX-RS-paden, `/openapi.json`,
 * `/q/health`, Swagger UI, de dev-UI en alles wat er later bij komt.
 *
 * **Waarom op de Vert.x-laag en niet als `ContainerResponseFilter` of als
 * `quarkus.http.header.*`-config.** Alleen hier is het mogelijk om een header te
 * *vervangen* in plaats van toe te voegen. Dat is nagemeten, en het is geen detail:
 *
 * - Een `ContainerResponseFilter` dekt alleen JAX-RS-paden, en zijn headers komen
 *   bovenop wat de HTTP-laag al plaatste — de response droeg elke header twee keer.
 * - `quarkus.http.header.*` kent één pad per headernaam, dus een tweede waarde voor
 *   een ander pad is niet uit te drukken.
 * - `quarkus.http.filter.*` matcht wél op een regex, maar vóegt toe: een pad dat door
 *   twee filters geraakt wordt, kreeg beide waarden achter elkaar.
 *
 * Twee waarden zijn niet onschuldig. Bij `Content-Security-Policy` doorsnijdt de
 * browser ze en wint de strengste, dus een bewust versoepelde policy zou stil geen
 * effect hebben; bij `X-Frame-Options` is het gedrag bij tegenstrijdige waarden per
 * browser verschillend. Eén header, één waarde, gezet als laatste: dat is wat een
 * `headersEndHandler` met `set` levert.
 *
 * **Waarom deze bedrading per dienst staat en niet in fbs-common.** De waarden staan wél
 * daar, in [SecurityHeaders]. De bedrading niet: elke consumer van fbs-common krijgt de
 * jandex-index mee en daarmee deze observer, ook `fbs-berichtensessiecache` — een library
 * die een Quarkus zonder HTTP-laag start. Die faalde bij het opstarten op een
 * `ClassNotFoundException` voor `Filters`, want de Vert.x-runtime stond er als `provided`
 * niet op. Een bibliotheek die door niet-HTTP-consumers wordt gebruikt, hoort geen
 * HTTP-bedrading te bevatten. De tegenhanger in de berichtenuitvraag is identiek en moet
 * dat blijven.
 *
 * **Let op bij `quarkus.management.*`.** Zet een dienst de aparte management-interface aan,
 * dan verhuizen de `/q`-endpoints naar een eigen router die deze observer niet bereikt, en
 * gaan ze stilzwijgend zónder headers de deur uit. Geen van beide diensten heeft hem aan;
 * wie hem aanzet moet deze registratie meenemen.
 */
@ApplicationScoped
class SecurityHeadersRegistratie(
    @param:ConfigProperty(name = "quarkus.http.root-path", defaultValue = STANDAARD_ROOT_PAD)
    rootPad: String,
    @param:ConfigProperty(name = "quarkus.http.non-application-root-path", defaultValue = STANDAARD_BEHEERPAD)
    nonApplicationRootPad: String,
) {

    private val beheerpadRoot = SecurityHeaders.beheerpadRoot(rootPad, nonApplicationRootPad)

    fun registreer(@Observes filters: Filters) {
        filters.register(
            { context ->
                plaats(context)
                context.next()
            },
            PRIORITEIT,
        )
    }

    /**
     * De headers gaan er pas op vlak vóór ze de deur uit gaan. Meteen plaatsen zou niet
     * werken: alles wat daarna nog schrijft — RESTEasy, een resource, een exception
     * mapper — zou er zijn eigen waarde naast of overheen zetten.
     */
    private fun plaats(context: RoutingContext) {
        context.addHeadersEndHandler {
            val headers = context.response().headers()

            SecurityHeaders.voorPad(context.normalizedPath(), beheerpadRoot)
                .forEach { (naam, waarde) -> headers.set(naam, waarde) }

            // Ná de vaste set, want dit versmalt er twee. De dispositie is op dit moment
            // al gezet door BijlageContentTypeFilter; die volgorde is precies waarom het
            // hier gebeurt en niet in dat filter, dat de HTTP-laag niet kan overstemmen.
            //
            // Alleen op een geslaagde response. De dispositie staat op de request-context
            // zodra de bytes opgehaald zijn, maar een fout die daarná ontstaat — het
            // logboek is in productie fail-closed en gooit ná de resource-methode — levert
            // een foutbody op die de dispositie nog steeds draagt. Zo'n response hoort de
            // versoepeling niet te erven.
            if (context.response().statusCode in GESLAAGD) {
                SecurityHeaders.voorInlineBijlage(headers.get(SecurityHeaders.CONTENT_DISPOSITION))
                    ?.forEach { (naam, waarde) -> headers.set(naam, waarde) }
            }

            if (headers.get(SecurityHeaders.CACHE_CONTROL) == null) {
                headers.set(SecurityHeaders.CACHE_CONTROL, SecurityHeaders.CACHE_CONTROL_DEFAULT)
            }
        }
    }

    private companion object {

        /**
         * Moet vóór élk filter draaien dat een response kan afsluiten zonder door te
         * geven. Gebeurt dat eerder, dan wordt de `headersEndHandler` nooit opgehangen en
         * gaat die response zónder security-headers de deur uit. Quarkus' eigen hoogste
         * is de host-validatie op 400; deze waarde ligt daar ruim boven. Verlaag hem niet.
         */
        private const val PRIORITEIT = 10_000

        /** De statuscodes waarop een `inline`-dispositie werkelijk bytes aankondigt. */
        private val GESLAAGD = 200..299
    }
}

/**
 * Losse constanten omdat een annotatie-argument een compile-time-constante moet zijn en
 * een `companion object`-veld dat binnen dezelfde class niet is. De waarden zijn die van
 * Quarkus zelf; de beheerpad-default is relatief, zie [SecurityHeaders.beheerpadRoot].
 */
private const val STANDAARD_ROOT_PAD = "/"
private const val STANDAARD_BEHEERPAD = "q"
