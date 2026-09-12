package nl.rijksoverheid.moz.fbs.democonsole.bereikbaarheid

import com.fasterxml.jackson.annotation.JsonValue
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.logging.Logger

/**
 * Of een component zelf antwoordt. [NIET_GEREED] staat los van [ONBEREIKBAAR]: een magazijn dat
 * draait maar zijn database kwijt is, vraagt om een ander vervolg dan een magazijn dat er niet is.
 */
enum class Bereikbaarheid(@get:JsonValue val waarde: String) {
    BEREIKBAAR("bereikbaar"),
    NIET_GEREED("niet-gereed"),
    ONBEREIKBAAR("onbereikbaar"),
}

/**
 * Vraagt de readiness van elk component op, allemaal tegelijk.
 *
 * Dit kijkt naar het component zelf, niet naar de lijn ernaartoe: wat Toxiproxy op die lijn aanzet
 * toont de storingen-chip al, en op een gedeelde omgeving hebben de magazijnen geen proxy. Readiness
 * en niet liveness, omdat die de eigen database en Redis meeneemt — zonder die twee kan een
 * component niets, ook al draait het proces.
 *
 * Los van [BereikbaarheidService], zodat dit zonder Quarkus tegen een echte HTTP-server te toetsen is.
 */
internal class Bereikbaarheidscontrole(adressen: Map<String, String>, private val timeout: Duration = TIMEOUT) {

    private val log = Logger.getLogger(Bereikbaarheidscontrole::class.java.name)

    private val healthAdressen: Map<String, URI> =
        adressen.mapValues { (component, adres) -> healthAdres(component, adres) }.toSortedMap()

    // HTTP/1.1 vast, zodat de client op http geen upgrade naar HTTP/2 probeert die over het component
    // niets zegt. Doorverwijzingen niet volgen: een inlogpagina die ertussen komt, is het component niet.
    private val client: HttpClient = HttpClient.newBuilder()
        .version(HttpClient.Version.HTTP_1_1)
        .followRedirects(HttpClient.Redirect.NEVER)
        .connectTimeout(timeout)
        .build()

    private val laatstGezien = ConcurrentHashMap<String, Bereikbaarheid>()

    /** Per component, gesorteerd op naam. Duurt hooguit [timeout], ook als alles hangt. */
    fun controleer(): Map<String, Bereikbaarheid> {
        val lopend = healthAdressen.mapValues { (_, adres) -> vraagOp(adres) }

        return lopend.mapValues { (component, antwoord) ->
            val (toestand, oorzaak) = uitkomst(antwoord)

            meldWisseling(component, toestand, oorzaak)

            toestand
        }
    }

    // De harde grens met `orTimeout`, naast de request-timeout: die laatste begint pas te lopen als de
    // verbinding er is, en zonder deze grens duurt een ronde dan tot tweemaal zo lang.
    private fun vraagOp(adres: URI): CompletableFuture<HttpResponse<Void>> =
        client.sendAsync(
            HttpRequest.newBuilder(adres).timeout(timeout).GET().build(),
            HttpResponse.BodyHandlers.discarding(),
        ).orTimeout(timeout.toMillis(), TimeUnit.MILLISECONDS)

    private fun uitkomst(antwoord: CompletableFuture<HttpResponse<Void>>): Pair<Bereikbaarheid, String> = try {
        val status = antwoord.join().statusCode()
        val toestand = if (status == GEREED) Bereikbaarheid.BEREIKBAAR else Bereikbaarheid.NIET_GEREED

        toestand to "HTTP $status"
    } catch (fout: CompletionException) {
        Bereikbaarheid.ONBEREIKBAAR to (fout.cause ?: fout).javaClass.simpleName
    }

    /**
     * Alleen een wisseling komt in het log. Het paneel vraagt dit elke vijf seconden op, en een
     * component dat een uur plat ligt hoort het log niet te vullen. De eerste ronde telt vanaf
     * bereikbaar: een gezonde start heeft niets te melden, een component dat al bij de start plat
     * ligt wel.
     */
    private fun meldWisseling(component: String, toestand: Bereikbaarheid, oorzaak: String) {
        val vorige = laatstGezien.put(component, toestand) ?: Bereikbaarheid.BEREIKBAAR

        if (vorige == toestand) return

        if (toestand == Bereikbaarheid.BEREIKBAAR) {
            log.info("$component is weer bereikbaar")
        } else {
            log.warning("$component ${toestand.waarde} ($oorzaak) op ${healthAdressen[component]}")
        }
    }

    internal companion object {

        /**
         * Ruim onder de vier seconden waarna het paneel een uitlezing afbreekt; daarboven staat de chip
         * op onbekend in plaats van te zeggen wélk component hangt.
         */
        val TIMEOUT: Duration = Duration.ofSeconds(2)

        const val HEALTH_PAD = "/q/health/ready"

        private const val GEREED = 200

        private val SCHEMAS = setOf("http", "https")

        /**
         * Een onbruikbaar adres faalt bij het opstarten. Anders staat dat component de hele demo op
         * onbereikbaar, en zoekt de bediener een storing waar een typefout in de configuratie zit.
         */
        private fun healthAdres(component: String, adres: String): URI {
            val melding = "het adres voor de bereikbaarheid van $component is geen http(s)-adres: '$adres'"
            val uri = runCatching { URI.create(adres.trimEnd('/') + HEALTH_PAD) }.getOrNull()

            requireNotNull(uri) { melding }
            require((uri.scheme ?: "") in SCHEMAS && !uri.host.isNullOrBlank()) { melding }

            return uri
        }
    }
}
