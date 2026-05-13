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
 * `java.net.http.HttpClient` (sinds Java 11, met `close()` sinds Java 21) i.p.v.
 * een Quarkus REST Client per downstream omdat het aantal en de URLs van
 * downstreams puur uit config komt — een `@RegisterRestClient` per stuk zou
 * minder flexibel zijn.
 *
 * Structured content mode (`application/cloudevents+json`) i.p.v. binary mode:
 * één Content-Type voor alle attributen, makkelijker te debuggen, en sluit
 * direct aan op de NL GOV-voorbeelden in `ls-notif`.
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
        // Forum Standaardisatie: TLS 1.3 voorkeur, TLS 1.2 nog toegestaan; oudere
        // versies (SSLv3, TLS 1.0/1.1) afdwingen-uit voorkomt downgrade-aanvallen
        // en zwakke ciphers. JDK21 default sluit 1.0/1.1 al uit, maar expliciet
        // pinnen documenteert de baseline en beschermt tegen profile-overrides.
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
    fun lever(doel: PublicatieDoel, event: CloudEvent): DownstreamResultaat {
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
        } catch (ex: HttpConnectTimeoutException) {
            log.warnf(ex, "Connect-timeout bij downstream-aflevering: doel=%s", doel)
            DownstreamResultaat.Timeout(FoutBeschrijving.saneer("Connect-timeout naar $doel: ${ex.message}"))
        } catch (ex: HttpTimeoutException) {
            log.warnf(ex, "Read-timeout bij downstream-aflevering: doel=%s", doel)
            DownstreamResultaat.Timeout(FoutBeschrijving.saneer("Read-timeout naar $doel: ${ex.message}"))
        } catch (ex: SSLHandshakeException) {
            // TLS-handshake faalt: cert/CA-mismatch, untrusted CA, expired cert,
            // SNI-mismatch, protocol-downgrade. Retry binnen pollvenster zinloos —
            // herstel vereist cert-rotatie (pod-restart of config-reload), niet
            // een nieuwe HTTP-poging. Categoriseren als ConfiguratieFout
            // (non-herstelbaar) zodat de delivery direct MISLUKT-status krijgt
            // i.p.v. retry tot maxPogingen → resource-besparing + ops ziet direct
            // ERROR-log + status. SSLHandshakeException is een IOException-subklasse:
            // deze catch moet vóór de generieke IOException-catch staan.
            log.errorf(ex, "TLS-handshake faalt bij downstream-aflevering: doel=%s", doel)
            DownstreamResultaat.ConfiguratieFout(
                FoutBeschrijving.saneer("TLS-handshake naar $doel: ${ex.javaClass.simpleName}"),
            )
        } catch (ex: SSLException) {
            // Overige TLS-laag fouten (SSLProtocolException, partial-handshake-RST
            // tijdens server-overload, transient cert-rotatie-window). Geen
            // handshake-failure dus mogelijk transient — retry kan slagen na
            // pod-restart of server-recovery. Categoriseren als NetwerkFout
            // (herstelbaar=true) maar log expliciet als TLS-laag fout zodat ops
            // niet denkt dat het een TCP-hick is.
            log.warnf(ex, "TLS-laag fout (mogelijk transient) bij downstream-aflevering: doel=%s", doel)
            DownstreamResultaat.NetwerkFout(
                FoutBeschrijving.saneer("TLS-fout naar $doel: ${ex.javaClass.simpleName}"),
            )
        } catch (ex: IOException) {
            log.warnf(ex, "Netwerkfout bij downstream-aflevering: doel=%s", doel)
            DownstreamResultaat.NetwerkFout(
                FoutBeschrijving.saneer("${ex.javaClass.simpleName} naar $doel: ${ex.message}"),
            )
        } catch (ex: InterruptedException) {
            // Herstel interrupt-flag zodat bovenliggende code (scheduler-thread)
            // het signaal niet verliest.
            Thread.currentThread().interrupt()
            log.warnf(ex, "Interrupted bij downstream-aflevering: doel=%s", doel)
            DownstreamResultaat.NetwerkFout("Interrupted naar $doel")
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

        // Plain http alleen toegestaan tegen exact-loopback (geen wildcard `.local`
        // of `.localhost`-subdomeinen — die kunnen door DNS-trucs naar willekeurige
        // hosts wijzen). Andere `127.x.x.x`-adressen (bv. `127.0.0.2`) vallen
        // hier expliciet niet onder: we whitelisten enkel `127.0.0.1` en
        // `localhost`/`[::1]`. `URI.getHost()` retourneert IPv6-hosts met
        // brackets, dus alleen die vorm hoeft hier te staan.
        val isLoopback = host == "localhost" || host == "127.0.0.1" || host == "[::1]"
        // Buiten loopback: TLS verplicht (BIO 13.2.1 — vertrouwelijkheid +
        // authenticiteit van data-in-transit naar federatieve dienstverleners).
        if (scheme == "http" && !isLoopback) {
            return DownstreamResultaat.ConfiguratieFout(
                "Plain http:// alleen toegestaan voor loopback — productie vereist TLS (BIO 13.2.1)",
            )
        }

        // SSRF-blocklist: weiger interne adres-ranges + cloud-metadata-endpoints
        // (AWS/GCP 169.254.169.254, Azure 169.254.169.254, RFC1918, link-local,
        // 0.0.0.0). Operator met config-toegang kan anders de magazijn-pod als
        // proxy gebruiken naar interne services. Loopback expliciet toegestaan
        // voor dev-stubs (Wiremock/embedded HTTP server).
        if (!isLoopback) {
            val ssrfFout = blokkeerIntern(host)
            if (ssrfFout != null) return ssrfFout
        }
        return null
    }

    /**
     * Weigert RFC1918, link-local, ULA (IPv6 unique-local `fc00::/7`),
     * any-local en cloud-metadata-IPs (OWASP SSRF cheatsheet). Combineert
     * de checks van [InetAddress] (`isAnyLocalAddress`/`isLinkLocalAddress`/
     * `isSiteLocalAddress` — IPv4 RFC1918 + IPv6 link-local) met een
     * expliciete byte-pattern check voor IPv6 ULA en literal-blocklist voor
     * cloud-metadata IPs (AWS IMDSv1 v4 + v6, Azure, GCP).
     *
     * **DNS-rebinding**: deze check resolveert de naam, maar `http.send()`
     * resolveert hem opnieuw — een korte-TTL DNS-record kan tussen de twee
     * resoluties van interne naar externe IP wisselen. Mitigatie zou DNS-
     * pinning vereisen (zelf de socket openen op het gevalideerde IP en
     * `Host`-header zetten); voor de PoC wegen we de extra complexiteit niet
     * op tegen het risico-niveau, gegeven dat downstream-URLs uit gefixeerde
     * config komen en niet user-supplied zijn.
     *
     * Caveat is verder gedocumenteerd in `docs/operator-handleiding.md`
     * (sectie "Downstream-URL conventies"). Operator-handleiding is de
     * single source of truth; herhaal hier niet de regels (drift-risico).
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

    /** Parseert de `Retry-After`-header. Spec staat zowel seconden (Int) als HTTP-date toe; voor PoC alleen Int-seconden. */
    private fun leesRetryAfter(value: String?): Duration? {
        val seconden = value?.trim()?.toLongOrNull() ?: return null
        return if (seconden in 0..3_600) Duration.ofSeconds(seconden) else null
    }

    @PreDestroy
    fun stop() {
        // Java 21 HttpClient implementeert AutoCloseable; netjes opruimen voorkomt
        // dat selector-threads blijven draaien na shutdown van de Quarkus-app.
        // Chronische fouten hier betekenen thread-leak over redeploys; ERROR-niveau
        // zorgt dat productie-dashboards het signaal niet missen.
        runCatching { http.close() }.onFailure { ex ->
            log.errorf(ex, "HttpClient.close() faalde bij shutdown — risico op selector-thread-leak")
        }
    }

    companion object {
        /**
         * Whitelist-setter: laat alleen W3C `traceparent` door. `tracestate`
         * (vendor-specifieke routings-/sampling-data) wordt expliciet
         * gefilterd zodat interne details niet cross-organisatie lekken — een
         * lege `tracestate`-header is ook niet acceptabel omdat downstream-
         * parsers de aanwezigheid kunnen registreren.
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
