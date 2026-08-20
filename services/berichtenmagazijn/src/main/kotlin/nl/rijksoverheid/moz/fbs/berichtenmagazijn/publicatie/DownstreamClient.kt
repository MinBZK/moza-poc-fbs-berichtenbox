package nl.rijksoverheid.moz.fbs.berichtenmagazijn.publicatie

import com.fasterxml.jackson.core.JsonProcessingException
import com.fasterxml.jackson.databind.ObjectMapper
import io.opentelemetry.api.OpenTelemetry
import io.opentelemetry.context.Context
import io.opentelemetry.context.propagation.TextMapSetter
import io.quarkus.runtime.StartupEvent
import jakarta.annotation.PreDestroy
import jakarta.enterprise.context.ApplicationScoped
import jakarta.enterprise.event.Observes
import jakarta.enterprise.inject.Instance
import nl.rijksoverheid.moz.fbs.common.FoutBeschrijving
import nl.rijksoverheid.moz.fbs.common.fsc.FscOutwayHeaders
import org.eclipse.microprofile.config.inject.ConfigProperty
import org.jboss.logging.Logger
import java.io.IOException
import java.net.InetAddress
import java.net.URI
import java.net.UnknownHostException
import java.net.http.HttpClient
import java.net.http.HttpConnectTimeoutException
import java.net.http.HttpRequest
import java.net.http.HttpRequest.BodyPublishers
import java.net.http.HttpResponse.BodyHandlers
import java.net.http.HttpTimeoutException
import java.time.Duration
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLException
import javax.net.ssl.SSLHandshakeException
import javax.net.ssl.SSLParameters

/**
 * REST-client voor de downstream-aflevering van CloudEvents (Aanmeld Service,
 * Notificatie Service, ... — wat in [PublicatieConfig.downstreams] staat).
 *
 * `java.net.http.HttpClient` i.p.v. een Quarkus REST Client per downstream: aantal en
 * URLs komen puur uit config, dus een `@RegisterRestClient` per stuk is minder flexibel.
 *
 * Structured content mode (`application/cloudevents+json`): één Content-Type voor alle
 * attributen, en sluit aan op de NL GOV-voorbeelden in `ls-notif`.
 */
@ApplicationScoped
class DownstreamClient(
    private val config: PublicatieConfig,
    private val objectMapper: ObjectMapper,
    private val openTelemetryInstance: Instance<OpenTelemetry>,
    @param:ConfigProperty(name = "quarkus.profile") private val profiel: String,
) {

    private val log = Logger.getLogger(DownstreamClient::class.java)

    /**
     * Laat bij boot een spoor na van elke actieve uitzondering op de URL-controles. Een
     * `StartupEvent`-observer en niet een `init`-blok: deze bean is lui `@ApplicationScoped`, dus
     * een init-blok draait pas bij de eerste publicatieronde — tot een pollinterval later, en op
     * een moment dat niet met de boot correleert. [PublicatieConfigValidator] en [PublicatieOutbox]
     * doen het om dezelfde reden zo.
     */
    fun meldActieveUitzonderingen(@Observes startup: StartupEvent) {
        // De bypass zet méér uit dan een TLS-uitzondering — ook de SSRF-blocklist vervalt — dus
        // laat hij een spoor achter, zoals OutboundTlsValidator dat voor de kleinere TLS-only
        // override doet. Draait een instantie ooit onbedoeld onder dev (de demo-compose zet dat
        // profiel op vier plaatsen met hetzelfde image), dan is dat zonder deze regel onzichtbaar.
        if (profiel in PROFIELEN_ZONDER_TLS_EIS) {
            log.warnf(
                "%s: profiel '%s' — downstream-URL-validatie laat plain http naar niet-loopback toe " +
                    "en slaat de SSRF-blocklist over. Uitsluitend bedoeld voor de lokale demo-stack.",
                VALIDATIE_UIT_ALERT_TOKEN,
                profiel,
            )
        }

        val outwayHost = outwayHost()
        val (viaOutway, buitenOutway) = config.downstreams()
            .filterValues { bruikbareGrantHash(it) != null }
            .entries
            .partition { (_, downstream) -> outwayHost != null && urlHost(downstream.url()) == outwayHost }
            .let { (binnen, buiten) -> binnen.map { it.key } to buiten.map { it.key } }

        if (viaOutway.isNotEmpty()) {
            // Een SSRF-uitzondering hoort niet stil te zijn: zonder deze regel is aan een draaiende
            // pod niet te zien welke downstreams buiten de blocklist vallen. De host hoort erbij en
            // niet alleen de key — een alert op het token moet kunnen zien wáárvoor de blocklist
            // vervalt, en dat is precies de bestemming. Een hostnaam is geen persoonsgegeven.
            log.warnf(
                "%s: downstream(s) %s lopen door de eigen FSC-outway op '%s'; de SSRF-blocklist " +
                    "geldt daar niet.",
                OUTWAY_SSRF_ALERT_TOKEN,
                viaOutway.joinToString(", "),
                outwayHost,
            )
        }

        if (buitenOutway.isNotEmpty()) {
            // Een grant-hash op een downstream die de outway niet aanwijst is altijd een vergissing:
            // de header komt bij een partij terecht die er niets mee doet, en het contract dat de
            // aflevering hoort te verantwoorden blijft ongebruikt. Buiten dev faalt zo'n aflevering
            // met een configuratiefout; in dev gaat ze rechtstreeks. Beide keren is "wat er staat"
            // niet wat er gebeurt, dus dat hoort in de log — niet pas bij de eerste aflevering.
            log.warnf(
                "Downstream(s) %s dragen een grant-hash maar hun URL wijst niet naar " +
                    "magazijn.publicatie.outway.host (%s); hun verkeer gaat niet door de outway.",
                buitenOutway.joinToString(", "),
                outwayHost ?: "niet gezet",
            )
        }
    }

    private val http: HttpClient = HttpClient.newBuilder()
        .connectTimeout(config.client().connectTimeout())
        // Forum Standaardisatie: alleen TLS 1.3/1.2. JDK21 sluit oudere versies al uit,
        // maar expliciet pinnen documenteert de baseline en weert profile-overrides.
        .sslContext(SSLContext.getDefault())
        .sslParameters(
            SSLParameters().apply {
                protocols = arrayOf("TLSv1.3", "TLSv1.2")
            },
        )
        .build()

    /**
     * Levert een CloudEvent aan downstream [doel] (key uit config). Resultaat:
     * [DownstreamResultaat.Geslaagd] bij 2xx, een specifiek
     * [DownstreamResultaat.Mislukt]-subtype anders. Gooit zelf nooit — fouten
     * worden naar de stream gerapporteerd zodat retry-besluit één plek heeft.
     */
    fun lever(doel: Publicatiedoel, event: CloudEvent): DownstreamResultaat {
        val downstream = config.downstreams()[doel.key]
            ?: return DownstreamResultaat.ConfiguratieFout(
                "Downstream '${doel.key}' niet geconfigureerd",
            )

        val url = downstream.url()
        val grantHash = bruikbareGrantHash(downstream)

        if (grantHash != null) {
            val hashFout = vormfout(grantHash)

            if (hashFout != null) return hashFout
        }

        val urlValidatie = valideerUrl(url, viaOutway = grantHash != null)

        if (urlValidatie != null) return urlValidatie

        val payload = try {
            objectMapper.writeValueAsBytes(event)
        } catch (ex: JsonProcessingException) {
            log.errorf(ex, "Serialisatie van CloudEvent mislukt: doel=%s eventType=%s", doel, event.type)
            return DownstreamResultaat.SerialisatieFout(
                "Serialisatie mislukt voor doel=$doel: ${ex.javaClass.simpleName}",
            )
        }

        val requestBuilder = HttpRequest.newBuilder()
            .uri(URI.create(url))
            .timeout(config.client().requestTimeout())
            .header("Content-Type", "application/cloudevents+json")
            .POST(BodyPublishers.ofByteArray(payload))

        val transactionId = if (grantHash != null) {
            val fscHeaders = FscOutwayHeaders.headers(grantHash)

            fscHeaders.forEach { (naam, waarde) -> requestBuilder.header(naam, waarde) }

            // Een geslaagde aflevering hoeft alleen terugvindbaar te zijn, vandaar DEBUG. Bij een
            // mislukking gaat dezelfde id via [faalreden] mee in het resultaat, dat op ERROR/WARN
            // gelogd én in de outbox bewaard wordt. Alleen het doel loggen, nooit de URL: die kan
            // een pad met persoonsgegevens dragen.
            log.debugf(
                "FSC-outway-aflevering naar %s: Fsc-Transaction-Id=%s",
                doel.key,
                fscHeaders[FscOutwayHeaders.TRANSACTION_ID_HEADER],
            )

            // De JDK-client onderhandelt op een plain-http-doel eerst een upgrade naar h2c. Die
            // hop-by-hop-headers proxyt de outway ongewijzigd door naar de inway; het
            // Go-http2-transport daarachter weigert ze met "invalid Upgrade request header" en de
            // outway antwoordt 502. Vandaar HTTP/1.1 op deze hop.
            //
            // Niet omdat de spec het eist: fsc-core noemt HTTP/1.1 als minimum en staat HTTP/2
            // uitdrukkelijk toe, en `PROTOCOL_TCP_HTTP_1.1` in een publicatiecontract beschrijft
            // de upstream áchter de inway. Deze hop — app naar de eigen outway — valt buiten de
            // FSC-spec; wat 'm pint is het gedrag van de OpenFSC-proxyketen. Alleen hier pinnen:
            // downstreams die rechtstreeks worden aangesproken mogen wel h2 doen.
            requestBuilder.version(HttpClient.Version.HTTP_1_1)

            fscHeaders[FscOutwayHeaders.TRANSACTION_ID_HEADER]
        } else {
            null
        }

        // W3C Trace Context propagatie: injecteer `traceparent` (en `tracestate`)
        // uit de huidige OpenTelemetry-context, zodat de keten cross-organisatie
        // reconstrueerbaar blijft (Logboek Dataverwerkingen vereiste).
        injecteerTraceparent(requestBuilder)

        return try {
            val response = http.send(requestBuilder.build(), BodyHandlers.ofString())
            when (val status = response.statusCode()) {
                in 200..299 -> DownstreamResultaat.Geslaagd
                else -> DownstreamResultaat.HttpFout(
                    statusCode = status,
                    retryAfter = leesRetryAfter(response.headers().firstValue("Retry-After").orElse(null)),
                    reden = faalreden(status, doel, transactionId, response.body()),
                )
            }
        } catch (ex: IOException) {
            mapDeliveryException(ex, doel)
        } catch (ex: InterruptedException) {
            // Herstel interrupt-flag zodat bovenliggende code (scheduler-thread)
            // het signaal niet verliest.
            Thread.currentThread().interrupt()
            log.warnf(ex, "Interrupted bij downstream-aflevering: doel=%s", doel)
            DownstreamResultaat.NetwerkFout("Interrupted naar $doel")
        }
    }

    /**
     * De reden bij een niet-2xx-antwoord. Een kale "HTTP 404 van notificatie" laat een eigen
     * configuratiefout niet onderscheiden van een fout van de bestemming, terwijl beide terminal
     * zijn en het bericht dus definitief niet aankomt. Dat onderscheid staat juist in de body
     * (`UNKNOWN_GRANT_HASH_IN_HEADER`, "service not found"), en op het outway-pad kan een 502 van
     * de outway, de router óf de inway komen — vandaar ook de transaction-id, want die is de enige
     * sleutel naar de rij in de txlogs van outway en inway, die 'm ongewijzigd doorgeven.
     *
     * Het fragment is kort en gesaneerd: `reden` wordt in de outbox bewaard en gelogd, en een
     * antwoordbody is untrusted invoer.
     */
    private fun faalreden(status: Int, doel: Publicatiedoel, transactionId: String?, body: String?): String {
        val basis = "HTTP $status van ${doel.key}"
        val transactie = transactionId?.let { " (Fsc-Transaction-Id=$it)" } ?: ""
        val fragment = FoutBeschrijving.saneer(body?.trim(), maxLengte = MAX_FAALREDEN_BODY).trim()

        return if (fragment.isEmpty()) basis + transactie else "$basis$transactie: $fragment"
    }

    /**
     * Mapt een [IOException] uit [HttpClient.send] naar het juiste [DownstreamResultaat].
     * Geëxtraheerd uit [lever] zodat de SSL-takken zonder netwerk-trigger testbaar zijn.
     *
     * **Volgorde-invariant** (gepind door [DownstreamClientExceptionMappingTest]): de
     * `when`-`is`-branches matchen op subklasse-volgorde, eerste match wint —
     * [HttpConnectTimeoutException] vóór [HttpTimeoutException], [SSLHandshakeException]
     * (cert-config, non-herstelbaar) vóór [SSLException] (mogelijk transient), beide vóór
     * de generieke [IOException]-tak. Verkeerde volgorde → eindeloze retry op cert-faal.
     */
    internal fun mapDeliveryException(ex: IOException, doel: Publicatiedoel): DownstreamResultaat = when (ex) {
        is HttpConnectTimeoutException -> {
            log.warnf(ex, "Connect-timeout bij downstream-aflevering: doel=%s", doel)
            DownstreamResultaat.Timeout(FoutBeschrijving.saneer("Connect-timeout naar $doel: ${ex.message}"))
        }
        is HttpTimeoutException -> {
            log.warnf(ex, "Read-timeout bij downstream-aflevering: doel=%s", doel)
            DownstreamResultaat.Timeout(FoutBeschrijving.saneer("Read-timeout naar $doel: ${ex.message}"))
        }
        is SSLHandshakeException -> {
            // Cert/CA-mismatch, expired cert, SNI/downgrade: herstel vereist cert-rotatie,
            // niet een nieuwe poging. ConfiguratieFout (non-herstelbaar) → direct MISLUKT
            // i.p.v. retry tot maxPogingen.
            log.errorf(ex, "TLS-handshake faalt bij downstream-aflevering: doel=%s", doel)
            DownstreamResultaat.ConfiguratieFout(
                FoutBeschrijving.saneer("TLS-handshake naar $doel: ${ex.javaClass.simpleName}"),
            )
        }
        is SSLException -> {
            // Overige TLS-laag fouten (geen handshake-faal) zijn mogelijk transient →
            // NetwerkFout (herstelbaar), maar log als TLS-fout zodat ops het niet voor
            // een TCP-hick aanziet.
            log.warnf(ex, "TLS-laag fout (mogelijk transient) bij downstream-aflevering: doel=%s", doel)
            DownstreamResultaat.NetwerkFout(
                FoutBeschrijving.saneer("TLS-fout naar $doel: ${ex.javaClass.simpleName}"),
            )
        }
        else -> {
            log.warnf(ex, "Netwerkfout bij downstream-aflevering: doel=%s", doel)
            DownstreamResultaat.NetwerkFout(
                FoutBeschrijving.saneer("${ex.javaClass.simpleName} naar $doel: ${ex.message}"),
            )
        }
    }

    /**
     * De grant-hash van [downstream], of `null` als er geen staat. Trimmen omdat een hash die via
     * een env-var uit een gegenereerd bestand komt makkelijk een spatie of newline meekrijgt, en
     * de outway daarop `400 UNKNOWN_GRANT_HASH_IN_HEADER` antwoordt.
     *
     * Afwezig of leeg betekent "geen outway, rechtstreeks verkeer" — dat is het gedocumenteerde
     * gedrag van een niet-gezette `*_GRANT_HASH`-env-var. Een waarde die alleen uit witruimte
     * bestaat valt daar niet onder: dat is een typfout, geen keuze, en levert via
     * [vormfout] een [DownstreamResultaat.ConfiguratieFout].
     */
    private fun bruikbareGrantHash(downstream: PublicatieConfig.Downstream): String? =
        downstream.grantHash().orElse(null)?.takeIf { it.isNotEmpty() }?.trim()

    /**
     * De geconfigureerde outway-host, genormaliseerd voor vergelijking met [urlHost].
     */
    private fun outwayHost(): String? =
        config.outway().host().orElse(null)?.trim()?.takeIf { it.isNotEmpty() }?.lowercase()

    /**
     * De host uit [url], of `null` als die er niet uit te halen is. Slikt de syntaxfout omdat de
     * enige caller een boot-logregel is: een onbruikbare URL hoort de aflevering te laten falen
     * met de melding van [valideerUrl], niet de service te laten weigeren te starten.
     */
    private fun urlHost(url: String): String? =
        runCatching { URI.create(url).host?.lowercase() }.getOrNull()

    /**
     * Keurt een grant-hash af die niet als headerwaarde kan dienen. Zonder deze controle gooit
     * `HttpRequest.Builder.header` een [IllegalArgumentException] langs [lever] heen: de claim komt
     * dan ongewijzigd terug in `TE_PUBLICEREN` zonder dat `pogingen` oploopt, en struikelt elke
     * ronde opnieuw — één foutieve configwaarde legt de outbox permanent stil.
     *
     * Getoetst wordt de vorm van een headerwaarde (printable US-ASCII, geen witruimte binnenin) en
     * niet een hash-formaat: dat laatste ligt bij de FSC-implementatie en kan wijzigen. Dat is
     * strenger dan wat de builder zelf nog accepteert — die laat alles tot U+00FF door — maar een
     * grant-hash die tekens buiten dit bereik draagt is hoe dan ook een configuratiefout, en de
     * outway antwoordt erop met `400 UNKNOWN_GRANT_HASH_IN_HEADER`.
     */
    private fun vormfout(grantHash: String): DownstreamResultaat.ConfiguratieFout? {
        if (grantHash.isBlank()) {
            return DownstreamResultaat.ConfiguratieFout("Grant-hash bestaat alleen uit witruimte")
        }

        if (grantHash.any { it.code !in 0x21..0x7E }) {
            return DownstreamResultaat.ConfiguratieFout(
                "Grant-hash bevat een teken dat niet in een HTTP-header past",
            )
        }

        return null
    }

    /**
     * Keurt een downstream-URL goed of af. [viaOutway] betekent "deze downstream draagt een
     * grant-hash"; of die aanspraak op de blocklist-uitzondering ook opgaat wordt hier bepaald,
     * niet door de caller.
     *
     * `internal` zodat de scheme-, loopback-, outway- en SSRF-regels te toetsen zijn zonder een
     * echte call te doen — een test die op een niet-routeerbaar adres moet aflopen kost een
     * connect-timeout, en levert een ándere uitkomst op een machine die dat adres wél kan
     * bereiken. Spiegelt [mapDeliveryException], dat om dezelfde reden rechtstreeks getest wordt.
     */
    internal fun valideerUrl(url: String, viaOutway: Boolean): DownstreamResultaat.ConfiguratieFout? {
        val parsed = try {
            URI.create(url)
        } catch (_: IllegalArgumentException) {
            return DownstreamResultaat.ConfiguratieFout("Ongeldige URL-syntax")
        }
        val scheme = parsed.scheme?.lowercase()
        if (scheme != "https" && scheme != "http") {
            return DownstreamResultaat.ConfiguratieFout(
                "Alleen http/https toegestaan (kreeg scheme=$scheme)",
            )
        }
        val host = parsed.host?.lowercase()
            ?: return DownstreamResultaat.ConfiguratieFout("URL mist host-component")

        // In dev mag http naar niet-loopback hosts en vervalt de SSRF-blocklist. Beide zijn nodig
        // en niet inwisselbaar: de demo-stack levert af op container-DNS (`http://toxiproxy:18086`),
        // dat op een bridge-netwerk naar een RFC1918-adres resolveert. Alleen de TLS-eis versoepelen
        // laat de publicatieketen dus alsnog stuklopen op de blocklist, met een foutmelding die naar
        // TLS wijst. Buiten dev gelden TLS-buiten-loopback (BIO 13.2.1) en de blocklist (OWASP)
        // onverkort. Een actieve bypass logt bij boot een WARNING met [VALIDATIE_UIT_ALERT_TOKEN].
        if (profiel in PROFIELEN_ZONDER_TLS_EIS) return null

        // Exact-loopback-whitelist: geen wildcard-subdomeinen of overige 127.x.x.x
        // (DNS-trucs kunnen die naar willekeurige hosts wijzen). IPv6 met brackets
        // omdat `URI.getHost()` die zo teruggeeft.
        val isLoopback = host == "localhost" || host == "127.0.0.1" || host == "[::1]"
        // Buiten loopback: TLS verplicht (BIO 13.2.1 — vertrouwelijkheid +
        // authenticiteit van data-in-transit naar federatieve dienstverleners).
        if (scheme == "http" && !isLoopback) {
            return DownstreamResultaat.ConfiguratieFout(
                "Plain http:// alleen toegestaan voor loopback — productie vereist TLS (BIO 13.2.1)",
            )
        }

        if (viaOutway) return toetsOutwayBestemming(host)

        // SSRF-blocklist ([blokkeerIntern]): zonder dit kan een operator met
        // config-toegang de magazijn-pod als proxy naar interne services gebruiken.
        // Loopback is hierboven al toegestaan voor dev-stubs (WireMock/embedded HTTP).
        if (!isLoopback) {
            val ssrfFout = blokkeerIntern(host)
            if (ssrfFout != null) return ssrfFout
        }
        return null
    }

    /**
     * Toetst of [host] de eigen outway is, en daarmee of deze downstream de SSRF-blocklist mag
     * passeren.
     *
     * Een downstream met een grant-hash valt buiten die blocklist: zijn verkeer gaat de eigen
     * outway in, en dáár bepaalt het FSC-contract achter de hash de bestemming — niet onze URL.
     * Zonder die uitzondering is het pad onbruikbaar, want een outway-ClusterIP resolveert naar
     * RFC1918.
     *
     * Die redenering geldt alleen als de URL de outway ook echt aanwijst, dus dat wordt hier
     * afgedwongen in plaats van aangenomen: anders opent één grant-hash in de config de blocklist
     * voor een willekeurig intern adres, en is de magazijn-pod een proxy. Fail-closed en met de
     * werkelijke reden, want een stille bypass is van buitenaf niet te onderscheiden van een
     * werkende configuratie. De TLS-eis in [valideerUrl] blijft onverkort gelden, en een actieve
     * uitzondering logt bij boot [OUTWAY_SSRF_ALERT_TOKEN].
     */
    private fun toetsOutwayBestemming(host: String): DownstreamResultaat.ConfiguratieFout? {
        val outwayHost = outwayHost()
            ?: return DownstreamResultaat.ConfiguratieFout(
                "Downstream heeft een grant-hash maar magazijn.publicatie.outway.host ontbreekt",
            )

        // Alleen de verwachte host in de melding: de downstream-URL kan een pad met
        // persoonsgegevens dragen en `reden` belandt in de outbox en in de logs.
        if (host != outwayHost) {
            return DownstreamResultaat.ConfiguratieFout(
                "Downstream met grant-hash wijst niet naar de eigen outway ($outwayHost)",
            )
        }

        return null
    }

    /**
     * Weigert RFC1918, link-local, ULA (`fc00::/7`), any-local en cloud-metadata-IPs
     * (OWASP SSRF). Combineert [InetAddress]-checks (RFC1918 + IPv6 link-local) met een
     * byte-pattern-check voor ULA en een literal-blocklist voor metadata-IPs.
     *
     * **DNS-rebinding** blijft mogelijk (`http.send()` resolveert opnieuw); DNS-pinning
     * weegt niet op tegen het risico zolang downstream-URLs uit gefixeerde config komen.
     * Conventies: `docs/operator-handleiding.md` (single source of truth).
     */
    private fun blokkeerIntern(host: String): DownstreamResultaat.ConfiguratieFout? {
        val adressen = try {
            InetAddress.getAllByName(host)
        } catch (ex: UnknownHostException) {
            // DNS-resolutie hier alleen voor blocklist-check; resolution-failure bij de
            // echte HTTP-call wordt afgevangen als NetwerkFout. Wel een spoor laten zodat
            // een SSRF-gate-skip door onresolvbare host forensisch correleerbaar is.
            log.debugf(ex, "SSRF-blocklist-check overgeslagen: host niet resolvbaar")
            return null
        }
        for (adres in adressen) {
            if (adres.isAnyLocalAddress || adres.isLinkLocalAddress || adres.isSiteLocalAddress) {
                return DownstreamResultaat.ConfiguratieFout(
                    "Host resolveert naar intern adres (SSRF-bescherming)",
                )
            }
            if (isIpv6UniqueLocal(adres)) {
                return DownstreamResultaat.ConfiguratieFout(
                    "Host resolveert naar IPv6 ULA-adres (SSRF-bescherming)",
                )
            }
            // 169.254.169.254 valt onder link-local hierboven; expliciete guards
            // hieronder dekken IPv6-equivalenten en provider-specifieke literals
            // (AWS IMDS IPv6, GCP metadata-FQDN-IP).
            if (adres.hostAddress in CLOUD_METADATA_IPS) {
                return DownstreamResultaat.ConfiguratieFout(
                    "Host wijst naar cloud-metadata-endpoint (SSRF-bescherming)",
                )
            }
        }
        return null
    }

    /** IPv6 Unique-Local Address `fc00::/7` — RFC 4193, geen `isXxxLocalAddress` in JDK. */
    private fun isIpv6UniqueLocal(adres: InetAddress): Boolean {
        val bytes = adres.address
        return bytes.size == 16 && (bytes[0].toInt() and 0xFE) == 0xFC
    }

    private fun injecteerTraceparent(builder: HttpRequest.Builder) {
        val openTelemetry = if (openTelemetryInstance.isResolvable) openTelemetryInstance.get() else null
        val propagator = openTelemetry?.propagators?.textMapPropagator ?: return
        propagator.inject(Context.current(), builder, traceparentOnlySetter)
    }

    /** Parseert de `Retry-After`-header. Spec staat seconden (Int) én HTTP-date toe; hier alleen Int-seconden. */
    private fun leesRetryAfter(value: String?): Duration? {
        val seconden = value?.trim()?.toLongOrNull() ?: return null
        return if (seconden in 0..3_600) Duration.ofSeconds(seconden) else null
    }

    @PreDestroy
    fun stop() {
        // Sluit de HttpClient zodat selector-threads niet doorlopen na shutdown;
        // faalt dit chronisch → thread-leak over redeploys, daarom ERROR.
        runCatching { http.close() }.onFailure { ex ->
            log.errorf(ex, "HttpClient.close() faalde bij shutdown — risico op selector-thread-leak")
        }
    }

    companion object {
        /**
         * Profielen waarin http naar niet-loopback én de SSRF-blocklist vervallen. Alleen `dev`:
         * de demo-stack draait haar downstreams over container-DNS (`http://toxiproxy:18086`),
         * wat op een bridge-netwerk naar een RFC1918-adres resolveert — dat raakt beide guards.
         * `test` staat er bewust NIET in: de testconfig gebruikt loopback-URL's, die sowieso zijn
         * toegestaan, dus opnemen zou de validatie over de hele `@QuarkusTest`-oppervlakte
         * uitschakelen zonder er iets voor terug te krijgen.
         */
        private val PROFIELEN_ZONDER_TLS_EIS = setOf("dev")

        /**
         * Greppable marker bij een actieve bypass. Ops hangt hier een alert-regel aan; de waarde
         * mag niet wijzigen zonder die alert mee te verhuizen.
         */
        const val VALIDATIE_UIT_ALERT_TOKEN = "DOWNSTREAM_URL_VALIDATIE_UIT"

        /**
         * Greppable marker voor downstreams die door de eigen outway lopen en daarmee buiten de
         * SSRF-blocklist vallen. Zelfde afspraak als [VALIDATIE_UIT_ALERT_TOKEN]: ops hangt hier
         * een alert-regel aan, dus de waarde wijzigt niet zonder die alert mee te verhuizen.
         */
        const val OUTWAY_SSRF_ALERT_TOKEN = "DOWNSTREAM_VIA_OUTWAY"

        /**
         * Hoeveel van een foutbody in [DownstreamResultaat.Mislukt.reden] meegaat. Ruim genoeg
         * voor een FSC-foutcode of een `problem+json`-titel, kort genoeg om een HTML-foutpagina
         * van een tussenliggende proxy niet in de outbox te laten belanden.
         */
        private const val MAX_FAALREDEN_BODY = 200

        /**
         * Laat alleen W3C `traceparent` door; `tracestate` (vendor-routing/sampling)
         * wordt gefilterd zodat interne details niet cross-organisatie lekken.
         */
        private val traceparentOnlySetter = TextMapSetter<HttpRequest.Builder> { carrier, key, value ->
            if (key.equals("traceparent", ignoreCase = true)) {
                carrier?.header(key, value)
            }
        }

        /**
         * Bekende cloud-metadata-IPs. AWS IMDS v6 zit op `fd00:ec2::254` (ULA,
         * dus ook door [isIpv6UniqueLocal] gevangen — dubbele bescherming).
         * GCP/Azure FQDN `metadata.google.internal` resolveert naar `169.254.169.254`.
         */
        private val CLOUD_METADATA_IPS: Set<String> = setOf(
            "169.254.169.254", // AWS IMDS v4, Azure, GCP
            "fd00:ec2:0:0:0:0:0:254", // AWS IMDS v6 (canonical form)
            "fd00:ec2::254",
        )
    }
}

/**
 * Resultaat van één afleverings-poging. Sealed-hierarchie zodat
 * [PublicatieStream] en [RetryBeleid] niet-herstelbare fouten direct
 * als terminal kunnen markeren (geen zinloze retry op 4xx-client-errors,
 * misconfiguratie of serialisatie-bugs).
 */
sealed interface DownstreamResultaat {
    data object Geslaagd : DownstreamResultaat

    sealed interface Mislukt : DownstreamResultaat {
        val reden: String

        /** `true` als opnieuw proberen kans van slagen biedt (5xx, timeout, netwerk). */
        val herstelbaar: Boolean

        /** Optionele server-aanwijzing hoe lang te wachten (vooral 429/503 + Retry-After). */
        val retryAfter: Duration?
            get() = null
    }

    data class HttpFout(
        val statusCode: Int,
        override val retryAfter: Duration?,
        override val reden: String,
    ) : Mislukt {
        // 5xx = server-side, retryen. 408/429 = throttling/timeout, retryen.
        // Overige 4xx = client-fout (contract, payload, autorisatie), retry zinloos.
        override val herstelbaar: Boolean =
            statusCode in 500..599 || statusCode == 408 || statusCode == 429
    }

    data class Timeout(override val reden: String) : Mislukt {
        override val herstelbaar: Boolean = true
    }

    data class NetwerkFout(override val reden: String) : Mislukt {
        override val herstelbaar: Boolean = true
    }

    data class SerialisatieFout(override val reden: String) : Mislukt {
        override val herstelbaar: Boolean = false
    }

    data class ConfiguratieFout(override val reden: String) : Mislukt {
        override val herstelbaar: Boolean = false
    }
}
