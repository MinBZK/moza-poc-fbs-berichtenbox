package nl.rijksoverheid.moz.fbs.berichtenmagazijn.publicatie

import com.fasterxml.jackson.core.JsonProcessingException
import com.fasterxml.jackson.databind.ObjectMapper
import io.opentelemetry.api.OpenTelemetry
import io.opentelemetry.context.Context
import io.opentelemetry.context.propagation.TextMapSetter
import jakarta.annotation.PreDestroy
import jakarta.enterprise.context.ApplicationScoped
import jakarta.enterprise.inject.Instance
import nl.rijksoverheid.moz.fbs.common.FoutBeschrijving
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
) {

    private val log = Logger.getLogger(DownstreamClient::class.java)
    private val http: HttpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(5))
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
        val urlValidatie = valideerUrl(url)
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
            .timeout(Duration.ofSeconds(10))
            .header("Content-Type", "application/cloudevents+json")
            .POST(BodyPublishers.ofByteArray(payload))

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
                    reden = "HTTP $status van ${doel.key}",
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

    private fun valideerUrl(url: String): DownstreamResultaat.ConfiguratieFout? {
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
        } catch (_: UnknownHostException) {
            // DNS-resolutie hier alleen voor blocklist-check; resolution-failure
            // bij de echte HTTP-call wordt afgevangen als NetwerkFout.
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
