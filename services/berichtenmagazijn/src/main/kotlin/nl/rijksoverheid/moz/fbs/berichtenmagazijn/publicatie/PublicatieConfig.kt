package nl.rijksoverheid.moz.fbs.berichtenmagazijn.publicatie

import io.smallrye.config.ConfigMapping
import io.smallrye.config.WithDefault
import jakarta.validation.constraints.Min
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Pattern
import nl.rijksoverheid.moz.fbs.common.identificatie.Oin
import org.hibernate.validator.constraints.URL
import java.time.Duration

/**
 * Type-safe configuratie voor de Publicatie Stream.
 *
 * Downstreams (Aanmeld Service, Notificatie Service, ...) worden via een map
 * geconfigureerd. Toevoegen of verwijderen van een downstream is dus
 * config-only — geen schema- of code-wijziging:
 *
 * ```
 * magazijn.publicatie.downstreams.aanmeld.url=https://aanmeld.example.nl/events
 * magazijn.publicatie.downstreams.notificatie.url=https://notif.example.nl/events
 * ```
 *
 * `organisatie.oin` is de OIN van het magazijn zelf en gaat als `source`-attribuut
 * mee in elke CloudEvent (NL GOV CloudEvents-profiel v1.1 op basis van
 * CloudEvents core spec v1.0, URN-notatie).
 *
 * Bean Validation-annotaties worden door SmallRye Config gevalideerd bij applicatie-
 * start; bij overtreding faalt de bean-resolutie i.p.v. dat een misconfiguratie
 * pas runtime opvalt.
 */
@ConfigMapping(prefix = "magazijn.publicatie")
interface PublicatieConfig {

    fun organisatie(): Organisatie
    fun polling(): Polling
    fun opschonen(): Opschonen
    fun client(): Client

    @WithDefault("50")
    @Min(1)
    fun batchGrootte(): Int

    fun downstreams(): Map<String, Downstream>

    interface Organisatie {
        /** OIN (20 cijfers) van de eigen organisatie; gaat in CloudEvent `source`. */
        @Pattern(regexp = "^[0-9]{20}$", message = "OIN moet exact 20 cijfers zijn")
        fun oin(): String
    }

    /**
     * URI van de verwerkingsregister-entry (AVG art. 30) voor de publicatie-activiteit,
     * gekoppeld als `processingActivityId` aan elke LDV-context. Default met `example.com`
     * signaleert dat dit vóór productie gezet moet worden. `@URL`-parity met [Downstream.url]
     * weert CRLF/`javascript:`/niet-URI vóór ze in tracing/audit belanden.
     */
    @WithDefault("https://register.example.com/verwerkingen/berichtenmagazijn-publiceren")
    @NotBlank
    @URL(regexp = "^https?://.*")
    fun verwerkingsregisterPubliceren(): String

    /**
     * Als [verwerkingsregisterPubliceren], maar voor de aanlever-activiteit; gescheiden
     * omdat aanleveren en publiceren losse verwerkingsactiviteiten zijn. Wordt door
     * [nl.rijksoverheid.moz.fbs.berichtenmagazijn.aanlever.AanleverResource] programmatisch
     * gezet i.p.v. hardcoded in een `@Logboek`-annotatie.
     */
    @WithDefault("https://register.example.com/verwerkingen/berichtenmagazijn-aanleveren")
    @NotBlank
    @URL(regexp = "^https?://.*")
    fun verwerkingsregisterAanleveren(): String

    interface Polling {
        @WithDefault("60s")
        fun interval(): Duration

        /**
         * Poison-pill drempel: na dit aantal opeenvolgende mislukte pollrondes
         * (zonder succes ertussen) slaat [PublicatieStream] één ronde over als
         * cooldown. Met de default (3) en interval (60s) is dat ~4 × interval
         * (~4 min) rust — genoeg om een transiente DB-blip te overleven, weinig
         * genoeg om CPU/IO-burnout op een poison-claim te voorkomen.
         */
        @WithDefault("3")
        @Min(1)
        fun maxOpeenvolgendeFouten(): Int
    }

    interface Opschonen {
        /**
         * Interval voor de outbox-cleanup-job ([PublicatieDeliveriesOpschoner]); default 24h.
         * Tuning-overwegingen: `docs/operator-handleiding.md` sectie "Tuning-properties".
         */
        @WithDefault("24h")
        fun interval(): Duration
    }

    interface Backoff {
        /** Basis voor exponential backoff: `volgendePoging = now() + basis * 2^pogingen`. */
        @WithDefault("PT1S")
        fun basis(): Duration

        /** Bovengrens om runaway-backoff te voorkomen. */
        @WithDefault("PT1H")
        fun plafond(): Duration
    }

    /**
     * Timeouts op de gedeelde [DownstreamClient]-`HttpClient` richting downstreams.
     * Anders dan de sessiecache-awaits (lokale Redis) zijn dit remote, cross-organisatie
     * calls: de defaults dekken TCP+TLS-opbouw en een POST-round-trip over publiek
     * internet naar een federatieve dienstverlener. Per omgeving instelbaar voor een
     * trager netwerk of een striktere SLO. Beide moeten positief zijn — `HttpClient`/
     * `HttpRequest` weigeren een niet-positieve Duration al fail-fast bij opbouw.
     */
    interface Client {
        /** Max wachttijd op TCP+TLS-verbindingsopbouw naar een downstream. */
        @WithDefault("PT5S")
        fun connectTimeout(): Duration

        /** Max wachttijd op de volledige POST-round-trip (verbinding + verzenden + antwoord). */
        @WithDefault("PT10S")
        fun requestTimeout(): Duration
    }

    interface Downstream {
        /**
         * Volledige URL waar het CloudEvent met `POST` heen moet. Bean Validation weert
         * bij start ongeldige syntax en niet-http(s)-schemes. Host-aanwezigheid, de
         * loopback-uitzondering en de SSRF-blocklist worden runtime afgedwongen door
         * [DownstreamClient.valideerUrl] (die checks vereisen DNS-resolutie).
         */
        @NotBlank
        @URL(regexp = "^https?://.*")
        fun url(): String

        /** Max afleverpogingen voordat de delivery terminal MISLUKT wordt. Per downstream,
         *  zodat een trage/onbetrouwbare doel meer pogingen kan krijgen dan een snelle. */
        @WithDefault("5")
        @Min(1)
        fun maxPogingen(): Int

        /** Backoff-grenzen per downstream. */
        fun backoff(): Backoff
    }
}

/**
 * Geeft callers een [Oin] i.p.v. de rauwe `String` waartegen SmallRye bindt. De
 * `Oin`-constructor bewaakt de regels die Bean Validation syntactisch (`^[0-9]{20}$`)
 * niet dekt. (Logius OIN-spec: alleen lengte + numeriek, geen elfproef.)
 */
fun PublicatieConfig.Organisatie.toOin(): Oin = Oin(oin())
