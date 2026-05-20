package nl.rijksoverheid.moz.fbs.berichtenmagazijn.publicatie

import io.smallrye.config.ConfigMapping
import io.smallrye.config.WithDefault
import jakarta.validation.constraints.Min
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Pattern
import nl.rijksoverheid.moz.fbs.berichtenmagazijn.opslag.Oin
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
    fun backoff(): Backoff
    fun opschonen(): Opschonen

    @WithDefault("50")
    @Min(1)
    fun batchGrootte(): Int

    @WithDefault("5")
    @Min(1)
    fun maxPogingen(): Int

    fun downstreams(): Map<String, Downstream>

    interface Organisatie {
        /** OIN (20 cijfers) van de eigen organisatie; gaat in CloudEvent `source`. */
        @Pattern(regexp = "^[0-9]{20}$", message = "OIN moet exact 20 cijfers zijn")
        fun oin(): String
    }

    /**
     * URI van de verwerkingsregister-entry voor de publicatie-activiteit. Wordt
     * als `processingActivityId` aan elke LDV-context gekoppeld. Verplichte
     * referentie naar het AVG art. 30-register; in productie wijzen naar de
     * echte register-URI van de organisatie. Default-waarde bevat `example.com`
     * om tijdens lokaal/dev draaien direct te signaleren dat dit nog gezet moet
     * worden vóór productie.
     *
     * `@URL(regexp = "^https?://.*")`-parity met [Downstream.url]: weert
     * operator-typo's met CRLF, `javascript:` of niet-URI strings vóór ze als
     * span-attribute of LDV-record naar centrale tracing/audit reizen.
     */
    @WithDefault("https://register.example.com/verwerkingen/berichtenmagazijn-publiceren")
    @NotBlank
    @URL(regexp = "^https?://.*")
    fun verwerkingsregisterPubliceren(): String

    /**
     * URI van de verwerkingsregister-entry voor de aanlever-activiteit. Net
     * als [verwerkingsregisterPubliceren] een AVG art. 30-referentie; gescheiden
     * omdat aanleveren en publiceren twee verschillende verwerkingsactiviteiten
     * zijn (verschillende doelen, dataminimalisatie-overwegingen). Wordt door
     * [nl.rijksoverheid.moz.fbs.berichtenmagazijn.aanlever.AanleverResource]
     * programmatisch op de [nl.mijnoverheidzakelijk.ldv.logboekdataverwerking.LogboekContext]
     * gezet zodat hij configureerbaar is i.p.v. hardcoded in een `@Logboek`-annotatie.
     */
    @WithDefault("https://register.example.com/verwerkingen/berichtenmagazijn-aanleveren")
    @NotBlank
    @URL(regexp = "^https?://.*")
    fun verwerkingsregisterAanleveren(): String

    interface Polling {
        @WithDefault("60s")
        fun interval(): Duration
    }

    interface Opschonen {
        /**
         * Interval voor de outbox-cleanup-job ([PublicatieDeliveriesOpschoner]).
         * Deze KDoc spiegelt de gebruiks-rationale; de operationele tuning-tabel
         * (wanneer verlagen/verhogen, throughput-overwegingen) staat in
         * `docs/operator-handleiding.md` sectie "Tuning-properties" om
         * drift-risico bij duplicatie te vermijden.
         *
         * Default `24h` — voldoende voor typische throughput.
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

    interface Downstream {
        /**
         * Volledige URL waar het CloudEvent met `POST` heen moet. Bean Validation
         * weert al bij applicatie-start syntactisch ongeldige URL's én niet-
         * `http`/`https`-schemes (`file:`, `ftp:`, `javascript:` etc.).
         *
         * **Wat de regex *niet* dekt**: host-component is door `^https?://.*`
         * niet verplicht (`http://` voldoet syntactisch); host-aanwezigheid,
         * loopback-uitzondering voor plain http, en SSRF-blocklist (RFC1918,
         * link-local, ULA, cloud-metadata) worden runtime afgedwongen door
         * [DownstreamClient.valideerUrl] omdat die checks DNS-resolutie
         * nodig hebben.
         */
        @NotBlank
        @URL(regexp = "^https?://.*")
        fun url(): String
    }
}

/**
 * Domein-veilige wrapper rond [PublicatieConfig.Organisatie.oin]. SmallRye Config
 * bindt @ConfigMapping-methods tegen primitieve types; daarom is de underlying
 * accessor `String`. Deze extension herstelt de typetoestand zodat callers met
 * een [Oin] werken — de constructor van `Oin` herhaalt de leading-zero- en
 * non-zero-regels die Bean Validation alleen syntactisch (`^[0-9]{20}$`) bewaakt.
 * (Logius OIN-spec mandateert geen elfproef; alleen lengte + numeriek.)
 */
fun PublicatieConfig.Organisatie.toOin(): Oin = Oin(oin())
