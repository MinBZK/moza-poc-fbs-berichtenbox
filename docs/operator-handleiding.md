# Operator-handleiding — FBS Berichtenmagazijn

Deze handleiding beschrijft de **verplichte productie-overrides** en
operationele aandachtspunten voor het Berichtenmagazijn. Ontwikkelaars die
lokaal of in tests draaien hebben deze niet allemaal nodig — defaults zijn
veilig voor dev/CI maar **niet** voor productie.

## Verplichte productie-overrides

Onderstaande properties **moeten** vóór productie-deploy gezet worden. De
meeste hebben een `example.com`/`localhost`-default zodat het missen ervan
direct opvalt; enkele hebben geen default en laten de service falen-te-starten
(fail-closed).

| Property | Bron | Doel | Faalwijze |
|---|---|---|---|
| `magazijn.publicatie.organisatie.oin` | `application.properties` | OIN (20 cijfers) van de eigen organisatie; gaat als CloudEvent `source` (URN-notatie) mee | Bean Validation faalt bij start; `^[0-9]{20}$` + non-zero |
| `magazijn.publicatie.verwerkingsregister-publiceren` | `application.properties` | AVG art. 30-register-URI voor publicatie-activiteit; wordt aan elke LDV-context gekoppeld | Bean Validation `@URL` + `@NotBlank` |
| `magazijn.publicatie.verwerkingsregister-aanleveren` | `application.properties` | AVG art. 30-register-URI voor aanlever-activiteit | Bean Validation `@URL` + `@NotBlank` |
| `magazijn.publicatie.downstreams.<key>.url` | `application.properties` | Eén entry per downstream (Aanmeld, Notificatie, ...). Service faalt-te-starten zonder ≥1 downstream | `PublicatieOutbox.valideerStartConfiguratie` |
| `LDV_DBMS` | env var | Backend voor het logboek: `postgresql` (default) of `clickhouse` | Onbekende waarde = startup-fout |
| `LDV_POSTGRES_URL` | env var | JDBC-URL van het logboek; buiten dev/test verplicht `ssl=true` of `sslmode=require`/`verify-ca`/`verify-full` conform BIO 13.2.1 | Geen default in `%prod`: ontbreekt de env var, dan faalt de expressie-expansie bij boot. Versleutelt de URL niet, dan gooit `LdvEndpointValidator` — **tenzij `fbs.ldv.unsafe-allow-plaintext-endpoint=true`** staat (zie hieronder); dan start de service wél en gaat het BSN in `dpl.core.data_subject_id` plaintext over de lijn |
| `LDV_POSTGRES_USERNAME`, `LDV_POSTGRES_PASSWORD` | env var | LDV-credentials; geen prod-defaults | Ontbreekt = de wrapper faalt bij de eerste logregel (hij leest de credentials pas bij gebruik), en fail-closed maakt van die verwerking een 500 |
| `LDV_CLICKHOUSE_ENDPOINT`, `CLICKHOUSE_USERNAME`, `CLICKHOUSE_PASSWORD` | env var | Alleen nodig bij `LDV_DBMS=clickhouse`; endpoint is dan `https://`-only | Endpoint leeg = startup-fout via `LdvEndpointValidator` (die eist `https://`). De credentials hebben een lege default en worden pas bij de eerste logregel gecontroleerd, niet bij boot |
| `FBS_LDV_UNSAFE_ALLOW_PLAINTEXT_ENDPOINT` | env var | **BEWUST ONVEILIG.** Zet de TLS-eis op het LDV-endpoint uit. Default `false` | Geen faalwijze — dat is precies het punt: de guard laat een plaintext endpoint door en logt alleen een WARNING met het token `OUTBOUND_TLS_DISABLED` |
| `logboekdataverwerking.span-processor` / `.write-failure-policy` | `application.properties` | Staan vast op `simple` + `fail-closed`. Niet overschrijven: `batch` exporteert op een achtergrondthread (de applicatie ziet dan nooit of de logregel is opgeslagen) en `fail-open` slikt schrijffouten door — beide zetten fail-closed stil uit, en een env var wint van `application.properties` | Elke andere waarde = startup-fout via `LdvFailClosedValidator` (buiten `dev`/`test`) |
| `quarkus.datasource.jdbc.url`, `quarkus.datasource.username`, `quarkus.datasource.password` | env var | Postgres-connectie | Quarkus datasource-init faalt zonder |

## De onveilige plaintext-override

`fbs.ldv.unsafe-allow-plaintext-endpoint` (env var
`FBS_LDV_UNSAFE_ALLOW_PLAINTEXT_ENDPOINT`) schakelt de TLS-eis op het LDV-endpoint uit.
Default `false`; **op ZAD staat hij aan**, want de platformdatabase is daar alleen intern
en zonder TLS bereikbaar.

Wat je opgeeft: het `dataSubjectId` in een logregel kan een BSN bevatten, en dat gaat dan
onversleuteld over het netwerk (BIO 13.2.1 / AVG art. 32). De guard blokkeert niet meer,
maar logt bij élke boot een WARNING die begint met het stabiele token
`OUTBOUND_TLS_DISABLED`.

Alleen verantwoord wanneer aan één van deze voorwaarden is voldaan:

1. het netwerkpad levert zelf transport-security (mesh-mTLS, of een niet-routeerbaar
   intern segment binnen één cluster), of
2. er stromen geen echte persoonsgegevens (test-/demodata).

Verplicht bij gebruik: een alert-regel (Loki/SIEM) op `OUTBOUND_TLS_DISABLED`. Zonder die
regel scrolt de waarschuwing weg en blijft een onbedoeld onversleuteld endpoint
onopgemerkt — precies het scenario waarin de vlag per ongeluk aan blijft staan nadat het
netwerk wél TLS kreeg.

**Let op de wisselwerking met de tabel hierboven:** met deze vlag aan is de
boot-guard op `LDV_POSTGRES_URL` géén garantie meer dat de verbinding versleuteld is.
Een plaintext JDBC-URL start dan gewoon op.

## Tuning-properties (defaults zijn safe maar context-afhankelijk)

| Property | Default | Wanneer aanpassen |
|---|---|---|
| `magazijn.publicatie.polling.interval` | `60s` | Lager bij latency-gevoelige downstreams (let op DB-load); hoger bij idle-cluster om CPU/IO te sparen |
| `magazijn.publicatie.polling.max-opeenvolgende-fouten` | `3` | Poison-pill drempel: na N mislukte pollrondes één ronde cooldown. Verhoog bij flaky-maar-herstelbare omgeving; verlaag om sneller te pauzeren bij aanhoudende fouten |
| `magazijn.publicatie.batch-grootte` | `50` | Lager bij trage downstreams om transactie-duur te beperken; verhogen alleen bij hoge throughput + snelle downstreams (langere transactie verhoogt kans op `idle_in_transaction_session_timeout`) |
| `magazijn.publicatie.downstreams.<key>.max-pogingen` | `5` | Per downstream; verhoog bij een doel met lange recovery |
| `magazijn.publicatie.downstreams.<key>.backoff.basis` / `plafond` | `PT1S` / `PT1H` | Per downstream; plafond voorkomt runaway-backoff |
| `magazijn.publicatie.opschonen.interval` | `24h` | Verlaag interval (bv. `1h`) bij hoog volume voor frequenter opruimen; verhoog (bv. `7d`) bij lage throughput |

## Downstream-URL conventies

De Publicatie Stream stript userinfo en query-string uit downstream-URLs
voordat ze als span-attribute naar centrale tracing reizen
(`PublicatieClaimVerwerker.stripUrlGeheimen`). **Path-segmenten worden NIET
gestript** — wie path-tokens (`/secret-xyz/events`) als geheim gebruikt
schendt de aanbeveling om credentials uit URL-paths te houden.

**Regels voor downstream-URLs:**

1. **TLS verplicht buiten loopback**: alleen `https://` in productie. Plain
   `http://localhost`, `127.0.0.1` of `[::1]` is voor dev/test toegestaan.
   Andere `127.x.x.x`-adressen (bv. `127.0.0.2`) vallen niet onder de
   loopback-uitzondering.
2. **Geen tokens in URL-paden**: gebruik HTTP-headers (toekomstige uitbreiding)
   of mTLS via PKIoverheid voor downstream-authenticatie. Tokens in paths
   lekken via tracing.
3. **Geen interne IP's**: SSRF-blocklist weert RFC1918, IPv6 link-local,
   IPv6 ULA (`fc00::/7`) en cloud-metadata-IPs (`169.254.169.254`,
   `fd00:ec2::254`). Hosts die naar deze ranges resolven worden bij
   request-tijd geweigerd.
4. **DNS-rebinding caveat**: SSRF-check resolveert de naam, `http.send()`
   resolveert hem opnieuw. Een korte-TTL DNS-record kan tussen de twee
   resoluties van extern naar intern wisselen. Mitigatie zou DNS-pinning
   vereisen; voor de PoC accepteren we dit risico omdat downstream-URLs
   uit gefixeerde config komen en niet user-supplied zijn. Bij het
   productiseren overwegen: zelf socket openen op gevalideerd IP +
   `Host`-header zetten.

## Outbox-monitoring

* **`publicatie_deliveries.status = TE_PUBLICEREN` met oude `volgende_poging`**:
  poller-stilstand of poison-pill (zie ERROR-log "MAX_OPEENVOLGENDE_FOUTEN").
* **`status = MISLUKT` met `volgende_poging IS NULL`**: definitief mislukt
  (hetzij na `max-pogingen` retries, hetzij niet-herstelbare fout zoals 4xx).
  Forensisch onderzoek vereist behoud — opschoner verwijdert deze rijen
  niet.
* **`status = GEPUBLICEERD` ouder dan retentie-interval**: `PublicatieDeliveriesOpschoner`
  ruimt deze elke `magazijn.publicatie.opschonen.interval`. Bij DB-uitval
  ziet de operator `ERROR Outbox-opschoning faalde (persistence|transactie)
  — outbox kan groeien tot volgende ronde slaagt`. Grep-anchor:
  `Outbox-opschoning faalde` matcht beide varianten. Volgende ronde retried
  automatisch.

## Logging & observability

* **PII**: persoonsgegevens (BSN, RSIN, KvK) horen **niet** in de reguliere
  applicatielog (AVG art. 5 lid 1c, BIO 12.4.1). LDV is de enige plek voor
  `dataSubjectId`; de service forceert dit via `LogboekContextDefaultFilter`
  + `FoutBeschrijving.saneer()`.
* **Categorie-logs**: zoek op `categorie=` in MISLUKT/retry-logs voor
  programmatische aggregatie van foutsoorten.

### W3C Trace Context (outbound)
* Alleen `traceparent` wordt naar downstream gestuurd; `tracestate`
  (vendor-data) wordt expliciet gefilterd om vendor-data-leak
  cross-organisatie te voorkomen.

### W3C Trace Context (inbound naar Aanlever-endpoint)
* Inbound `traceparent` wordt **NIET** als parent geadopteerd — elke
  aanlever-request start een nieuwe root-span. Reden: het aanlever-endpoint
  is in de PoC ongeauthentiseerd; een aanvaller kan anders via een
  zelfgekozen trace-id requests van verschillende afzenders cross-organisatie
  aan dezelfde keten koppelen, of een trace-id naar Aanmeld/Notificatie
  laten propageren. (Zie `AanleverResource` KDoc + `leverBerichtAan`
  `processingHandler.startSpan(..., null)`.)
* **Gevolg voor LDV-keten reconstructie**: legitieme upstream-systemen
  zien een keten-breuk bij het magazijn — hun trace-id is in centrale
  audit niet gelinkt aan downstream publicatie-spans. Wanneer mTLS
  (PKIoverheid) of OAuth2 client-credentials op het endpoint actief zijn,
  kan deze keuze worden heroverwogen — dan kan `traceparent` als parent
  of als `addLink` geadopteerd worden.
* **`traceparent-processor`-header (LDV vendor-extension)**: wordt
  whitelist-gevalideerd (`^[A-Za-z0-9._=:/-]{1,256}$`) vóór het als
  span-attribute geschreven wordt. Voorkomt log-poisoning (CWE-117) en
  audit-DoS via oversized of hex-only payloads. Niet-matchende waardes
  worden gereduceerd tot lege string. (Zie `AanleverResource.leverBerichtAan`
  finally-blok + companion `TRACEPARENT_PROCESSOR_PATTERN`.)

## Bekende monitoring-follow-ups (vóór productie)

Twee event-categorieën verdienen een eigen counter zodra Quarkus Micrometer
toegevoegd wordt (out-of-scope voor publicatie-stream PoC):

* **`traceparent_processor_rejections_total`** (geen labels). Telt elke
  whitelist-rejection in `AanleverResource.leverBerichtAan`, óók die de
  `LogStormLimiter`-cooldown opslokt. Reden: de log-cooldown is een
  defense tegen log-volume DoS; zonder counter ziet ops "1 rejection/min"
  terwijl de werkelijke rate 100/sec kan zijn (cluster-aanval-detectie
  blind spot). Counter is goedkoop (geen labels = geen high-cardinality
  risico) en geeft onmiskenbaar volume-signaal in dashboards.
* **`downstream_tls_handshake_fail_total{doel=...}`** in `DownstreamClient`
  bij `SSLHandshakeException`-catch. Reden: handshake-fouten worden direct
  als terminal MISLUKT gemarkeerd (correct gedrag — retry zinloos voor
  cert-config-fouten). Zonder counter ziet ops aanhoudende terminale
  TLS-faal pas via uitblijvende deliveries, niet binnen seconden via
  alert-rule. `doel`-label is bounded (≤ aantal geconfigureerde
  downstreams = 2-5).

Tot beide counters bestaan: scrape de logs op deze concrete patronen
(grep-anchors) voor trend-signaal:

* (a) **`traceparent-processor whitelist-rejection`** — exact-substring uit
  `AanleverResource.leverBerichtAan` warn-log; pak `WARN`-records met deze
  string voor rejection-frequency.
* (b) **`TLS-handshake faalt bij downstream-aflevering`** — exact-substring
  uit `DownstreamClient` `SSLHandshakeException`-catch (ERROR-niveau);
  pak ERROR-records om aanhoudende terminale TLS-faal binnen seconden
  te detecteren.

## Pre-existing aandachtspunten

* **Aanlever-endpoint heeft geen authenticatie**. PoC-scope. Vóór
  productie: mTLS via PKIoverheid + OIN-binding op client-cert valideren
  vóór `opslaanBericht`, conform Digikoppeling/FSC.
