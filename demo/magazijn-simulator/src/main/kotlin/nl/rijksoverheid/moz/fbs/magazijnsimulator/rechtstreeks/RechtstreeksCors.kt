package nl.rijksoverheid.moz.fbs.magazijnsimulator.rechtstreeks

import io.quarkus.vertx.http.runtime.filters.Filters
import io.vertx.core.http.HttpMethod
import io.vertx.ext.web.RoutingContext
import jakarta.enterprise.context.ApplicationScoped
import jakarta.enterprise.event.Observes
import nl.rijksoverheid.moz.fbs.magazijnsimulator.ApiInfo
import nl.rijksoverheid.moz.fbs.magazijnsimulator.magazijn.MagazijnPad

/**
 * CORS voor alleen de tweede ingang: de Berichten-app draait op een eigen origin. Toegang hangt aan
 * het sessietoken in `Authorization`, niet aan een cookie, dus `*` zonder credentials geeft een
 * andere origin niets wat hij niet al heeft. De eerste ingang blijft zonder CORS.
 *
 * Op de Vert.x-laag en niet in JAX-RS: de preflight heeft geen token en geen resource, en moet
 * vóór [nl.rijksoverheid.moz.fbs.magazijnsimulator.magazijn.MagazijnPadFilter] beantwoord worden.
 * De headers op andere antwoorden gaan er pas vlak voor verzending op, zodat ook een `401` uit
 * een exception mapper ze draagt; zonder die header ziet de app een netwerkfout in plaats van
 * een `401` en weet hij niet dat hij opnieuw moet beginnen.
 */
@ApplicationScoped
class RechtstreeksCors {

    fun registreer(@Observes filters: Filters) {
        filters.register(::behandel, PRIORITEIT)
    }

    private fun behandel(context: RoutingContext) {
        if (!PAD.matches(context.normalizedPath())) {
            context.next()

            return
        }

        if (context.request().method() == HttpMethod.OPTIONS) {
            context.response()
                .setStatusCode(NO_CONTENT)
                .putHeader("Access-Control-Allow-Origin", "*")
                .putHeader("Access-Control-Allow-Methods", "GET, POST")
                .putHeader("Access-Control-Allow-Headers", "Authorization, Content-Type")
                .putHeader("Access-Control-Max-Age", MAX_AGE_SECONDEN)
                .end()

            return
        }

        context.addHeadersEndHandler {
            context.response().headers()
                .set("Access-Control-Allow-Origin", "*")
                .set("Access-Control-Expose-Headers", "API-Version, WWW-Authenticate, Retry-After")
        }

        context.next()
    }

    companion object {
        val PAD = Regex("^/${MagazijnPad.SEGMENT}/[0-9]{20}${Regex.escape(ApiInfo.BASE_PATH)}/rechtstreeks(/.*)?$")

        private const val PRIORITEIT = 100
        private const val NO_CONTENT = 204
        private const val MAX_AGE_SECONDEN = "600"
    }
}
