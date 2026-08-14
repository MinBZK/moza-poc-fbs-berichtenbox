package nl.rijksoverheid.moz.fbs.berichtenmagazijn.publicatie

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicInteger

/**
 * Lichtgewicht embedded HTTP-server voor end-to-end-tests van [PublicatieStream].
 * Vervanger voor WireMock: geen externe dep nodig, JDK-built-in.
 *
 * Per request slaat de server de body op zodat een test kan asserten op de CloudEvent-payload.
 */
class DownstreamHttpServer(
    private val pad: String = "/events",
) : AutoCloseable {

    /**
     * Bepaalt de statuscode van het n-de request (1-indexed). Default: altijd 202.
     *
     * Instelbaar per test in plaats van vast bij constructie: zo delen tests met verschillend
     * downstream-gedrag (400, 500, eerst-500-dan-202) dezelfde server en daarmee dezelfde
     * Quarkus-instantie. Een eigen server per gedrag betekende een eigen resource-manager, en
     * die dwingt een applicatie-herstart met verse database-container af.
     *
     * `@Volatile` omdat de testthread schrijft en de handler-threads van de HTTP-server lezen;
     * als constructor-`val` was het veld nog veilig gepubliceerd, als `var` niet meer.
     */
    @Volatile
    var statusVoorAanroep: (Int) -> Int = ALTIJD_GEACCEPTEERD

    private val server: HttpServer = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
    private val ontvangenBodies = ConcurrentLinkedQueue<String>()
    private val ontvangenHeaders = ConcurrentLinkedQueue<Map<String, List<String>>>()
    private val aanroepTeller = AtomicInteger(0)

    val poort: Int get() = server.address.port
    val baseUrl: String get() = "http://127.0.0.1:$poort$pad"
    val bodies: List<String> get() = ontvangenBodies.toList()
    val headers: List<Map<String, List<String>>> get() = ontvangenHeaders.toList()
    val aantalAanroepen: Int get() = aanroepTeller.get()

    fun start() {
        server.createContext(pad) { exchange: HttpExchange ->
            val body = exchange.requestBody.readBytes().toString(Charsets.UTF_8)
            ontvangenBodies.add(body)
            ontvangenHeaders.add(exchange.requestHeaders.toMap())
            // Teller als laatste: een test die op `aantalAanroepen` wacht en daarna `bodies[n]`
            // leest, zou anders tussen beide regels een body kunnen missen.
            val poging = aanroepTeller.incrementAndGet()
            val status = statusVoorAanroep(poging)
            exchange.sendResponseHeaders(status, -1)
            exchange.close()
        }
        server.start()
    }

    /**
     * Reset call-counter, bodies, headers én het antwoordgedrag tussen tests die de server
     * hergebruiken. Het gedrag hoort mee terug naar de default: anders erft een volgende test
     * stilzwijgend de 400 of 500 van zijn voorganger.
     */
    fun reset() {
        ontvangenBodies.clear()
        ontvangenHeaders.clear()
        aanroepTeller.set(0)
        statusVoorAanroep = ALTIJD_GEACCEPTEERD
    }

    override fun close() {
        server.stop(0)
    }

    companion object {
        private val ALTIJD_GEACCEPTEERD: (Int) -> Int = { _ -> 202 }
    }
}
