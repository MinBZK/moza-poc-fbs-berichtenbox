package nl.rijksoverheid.moz.fbs.democonsole

import io.quarkus.test.common.http.TestHTTPResource
import io.quarkus.test.junit.QuarkusTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.net.URL
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse

/**
 * Niets uit dit paneel mag uit een cache komen.
 *
 * Elk antwoord toont de huidige stand van de demo — welke magazijnen op storing staan, wat er in de
 * bakken zit. Een hergebruikt antwoord laat precies zien wat er niet meer is, en dan lijkt een knop
 * niets te doen terwijl hij zijn werk gedaan heeft. Dat gaat verder dan de browser: op de gedeelde
 * omgeving zit er nog een ingress en een authenticatie-proxy tussen.
 */
@QuarkusTest
class CacheHeaderTest {

    @TestHTTPResource("/api/demo/omgeving")
    lateinit var omgevingUrl: URL

    @TestHTTPResource("/")
    lateinit var paginaUrl: URL

    @Test
    fun `de antwoorden van het paneel mogen niet bewaard worden`() {
        assertEquals("no-store", cacheControl(omgevingUrl))
    }

    /** Ook de pagina zelf: die draagt de knoppen en hun uitleg. */
    @Test
    fun `de pagina zelf mag ook niet bewaard worden`() {
        assertEquals("no-store", cacheControl(paginaUrl))
    }

    private fun cacheControl(url: URL): String? = HttpClient.newHttpClient()
        .send(HttpRequest.newBuilder(url.toURI()).GET().build(), HttpResponse.BodyHandlers.discarding())
        .headers()
        .firstValue("Cache-Control")
        .orElse(null)
}
