# Operator-handleiding — FBS Berichtenmagazijn

Deze handleiding beschrijft de **verplichte productie-overrides** en
operationele aandachtspunten voor het Berichtenmagazijn. Ontwikkelaars die
lokaal of in tests draaien hebben deze niet allemaal nodig — defaults zijn
veilig voor dev/CI maar **niet** voor productie; voor het lokale werk geldt
[`ontwikkelen.md`](ontwikkelen.md). De Berichtenuitvraag heeft een eigen
handleiding: [`operator-handleiding-uitvraag.md`](operator-handleiding-uitvraag.md).

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
| `magazijn.publicatie.downstreams.<key>.grant-hash` | env var (bv. `NOTIFICATIE_GRANT_HASH`) | FSC-grant-hash voor een downstream die door de eigen outway loopt; gezet ⇒ de call krijgt `Fsc-Grant-Hash` + `Fsc-Transaction-Id` mee. Leeg of afwezig ⇒ rechtstreeks verkeer | Geen boot-faalwijze; een hash die niet in een HTTP-header past of alleen witruimte bevat maakt de aflevering terminal MISLUKT met de reden in de outbox |
| `magazijn.publicatie.outway.host` | env var `OUTWAY_HOST` | Hostnaam van de eigen FSC-outway. Hoort bij de grant-hash hierboven: alleen een downstream-URL met exact deze host mag de SSRF-blocklist passeren | Geen boot-faalwijze; ontbreekt hij terwijl er een grant-hash staat, dan faalt elke aflevering van die downstream met een configuratiefout |
| `LDV_DBMS` | env var | Backend voor het logboek: `postgresql` (default) of `clickhouse` | Onbekende waarde = startup-fout |
| `LDV_POSTGRES_URL` | env var | JDBC-URL van het logboek; buiten dev/test verplicht `ssl=true` of `sslmode=require`/`verify-ca`/`verify-full` conform BIO 13.2.1 | Geen default in `%prod`: ontbreekt de env var, dan faalt de expressie-expansie bij boot. Versleutelt de URL niet, dan gooit `LdvEndpointValidator` — **tenzij `fbs.ldv.unsafe-allow-plaintext-endpoint=true`** staat (zie hieronder); dan start de service wél en gaat het BSN in `dpl.core.data_subject_id` plaintext over de lijn |
| `LDV_POSTGRES_USERNAME`, `LDV_POSTGRES_PASSWORD` | env var | LDV-credentials; geen prod-defaults | Ontbreekt = de wrapper faalt bij de eerste logregel (hij leest de credentials pas bij gebruik), en fail-closed maakt van die verwerking een 500 |
| `LDV_CLICKHOUSE_ENDPOINT`, `CLICKHOUSE_USERNAME`, `CLICKHOUSE_PASSWORD` | env var | Alleen nodig bij `LDV_DBMS=clickhouse`; endpoint is dan `https://`-only | Endpoint leeg of zonder `https://` = startup-fout via `LdvEndpointValidator` — **tenzij `fbs.ldv.unsafe-allow-plaintext-endpoint=true`** (zie hieronder); dan start de service ook met een leeg of plaintext endpoint. De credentials hebben een lege default en worden pas bij de eerste logregel gecontroleerd, niet bij boot |
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

**Let op de wisselwerking met de tabel hierboven:** met deze vlag aan is de boot-guard
géén garantie meer, voor geen van beide backends. Een plaintext `LDV_POSTGRES_URL` start
gewoon op, en zo ook een leeg of `http://`-`LDV_CLICKHOUSE_ENDPOINT`. Dat laatste is niet
theoretisch: bij een rollback naar ClickHouse blijft deze vlag volgens het ontwerp
aanstaan.

## Tuning-properties (defaults zijn safe maar context-afhankelijk)

| Property | Default | Wanneer aanpassen |
|---|---|---|
| `magazijn.publicatie.polling.interval` | `60s` | Lager bij latency-gevoelige downstreams (let op DB-load); hoger bij idle-cluster om CPU/IO te sparen |
| `magazijn.publicatie.polling.max-opeenvolgende-fouten` | `3` | Poison-pill drempel: na N mislukte pollrondes één ronde cooldown. Verhoog bij flaky-maar-herstelbare omgeving; verlaag om sneller te pauzeren bij aanhoudende fouten |
| `magazijn.publicatie.batch-grootte` | `50` | Lager bij trage downstreams om transactie-duur te beperken; verhogen alleen bij hoge throughput + snelle downstreams (langere transactie verhoogt kans op `idle_in_transaction_session_timeout`) |
| `magazijn.publicatie.downstreams.<key>.max-pogingen` | `5` | Per downstream; verhoog bij een doel met lange recovery |
| `magazijn.publicatie.downstreams.<key>.backoff.basis` / `plafond` | `PT1S` / `PT1H` | Per downstream; plafond voorkomt runaway-backoff |
| `magazijn.publicatie.opschonen.interval` | `24h` | Verlaag interval (bv. `1h`) bij hoog volume voor frequenter opruimen; verhoog (bv. `7d`) bij lage throughput |
| `magazijn.publicatie.achterstand.interval` | `60s` | Hoe vaak de outbox geteld wordt voor de meetwaarden (`fbs_publicatie_achterstand`). Eén query per pod per ronde over de hele outbox; lager maakt de meetwaarde actueler, hoger spaart de database |

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
   request-tijd geweigerd — met één uitzondering: een downstream met een
   grant-hash die de eigen FSC-outway aanwijst (zie hieronder).
4. **DNS-rebinding caveat**: SSRF-check resolveert de naam, `http.send()`
   resolveert hem opnieuw. Een korte-TTL DNS-record kan tussen de twee
   resoluties van extern naar intern wisselen. Mitigatie zou DNS-pinning
   vereisen; voor de PoC accepteren we dit risico omdat downstream-URLs
   uit gefixeerde config komen en niet user-supplied zijn. Bij het
   productiseren overwegen: zelf socket openen op gevalideerd IP +
   `Host`-header zetten.
5. **Verkeer door de eigen outway**: een downstream met
   `magazijn.publicatie.downstreams.<key>.grant-hash` gaat door de eigen
   FSC-outway, en dáár bepaalt het FSC-contract achter die hash de
   bestemming — niet onze URL. Voor die downstream vervalt regel 3, want
   een outway-ClusterIP resolveert naar RFC1918. De uitzondering geldt
   alleen als de URL-host exact gelijk is aan
   `magazijn.publicatie.outway.host` (env var `OUTWAY_HOST`); zonder die
   host, of bij een andere host, faalt de aflevering met een
   configuratiefout in plaats van dat de blocklist stilzwijgend opengaat.
   **Regel 1 (TLS) blijft onverkort gelden.** Bij boot logt elke actieve
   uitzondering een WARNING met het stabiele token `DOWNSTREAM_VIA_OUTWAY`
   en de betrokken downstreams en outway-host; hang daar een alert-regel
   aan, zoals bij `OUTBOUND_TLS_DISABLED`.

## Outbox-monitoring

De meetwaarden staan in [operations/meetgegevens.md](operations/meetgegevens.md): de achterstand
per status en bestemming, en de leeftijd van de oudste aanmelding die al had mogen gaan.

* **`fbs_publicatie_achterstand_oudste_seconds` loopt op**, met als oorzaken, in volgorde van
  waarschijnlijkheid:
  1. De bestemming weigert of is onbereikbaar, en de aanmelding wacht in de backoff. Zie
     `fbs_publicatie_aflevering_seconds_count{uitkomst!="geslaagd"}` per `doel`; in de log staan
     WARN-regels `Bericht-publicatie mislukt; retry gepland`.
  2. Een poison-pill of database-uitval in de poller. In de log kan dan
     `Onverwachte fout in pollronde (opeenvolgendeFouten=…)` (ERROR) staan, en na
     `magazijn.publicatie.polling.max-opeenvolgende-fouten` keer op rij
     `Pollronde overgeslagen na … opeenvolgende fouten` (WARN).
  3. De poller draait niet (geen pod, een hangende scheduler). Daar hoort geen logregel bij.
* **`status = MISLUKT`** (`fbs_publicatie_achterstand{status="mislukt"}`): definitief mislukt, na
  `max-pogingen` retries of door een niet-herstelbare fout zoals een 4xx. `laatste_fout` en
  `pogingen` tonen de laatste poging.
  Forensisch onderzoek vereist behoud: de opschoner verwijdert deze rijen niet. Ze verdwijnen pas
  met het bericht zelf, als de retentie dat definitief wist (de FK cascadeert).
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
* **`Content-Disposition` op een bijlage-download kan persoonsgegevens dragen.** De header
  draagt sinds de inline-weergave de bestandsnaam, en die is door de aanleveraar gekozen
  (`aanslag-de-vries.pdf`). De keten logt hem nergens, maar response-header-logging op een
  ingress of outway zou hem alsnog in een log zetten: laat die uit staan voor dit endpoint.

### Uitkomst van een verwerking in het logboek

Aanleveren en publiceren schrijven hun logregel vóór de verwerking (fail-closed). Staat bij het
schrijven al vast dat de verwerking niet doorgaat (een afgewezen aanlevering, een onbekend
publicatiedoel), dan staat die logregel zelf op `ERROR`. Anders staat hij op `UNSET`, en mislukt
de verwerking daarna aantoonbaar, dan krijgt hij een ERROR-child met dezelfde naam, betrokkene en
verwerkingsactiviteit en het attribuut `moza.ldv.uitkomst=mislukt`.

**Leesregel: een logregel op `UNSET` zonder ERROR-child is geslaagd; een logregel die zelf op
`ERROR` staat, is mislukt.** Dit is een afspraak binnen MOZa; de standaard kent deze leesregel nog
niet (Logius-standaarden/logboek-dataverwerkingen#314).

* **Liever te veel dan te weinig.** Een ERROR-child komt er alleen als vaststaat dat de
  verwerking niet plaatsvond. Bij publiceren: een fout bij het opbouwen van het bericht of het
  verzoek, een configuratiefout (URL-validatie, grant-hash, TLS-handshake) of een fout bij het
  opzetten van de verbinding (geweigerd, onbekende host, connect-timeout). Bij een read-timeout, een verbroken
  antwoord of een HTTP-foutantwoord kan de afnemer het bericht wél hebben; die poging blijft als
  verstrekking staan. Bij aanleveren blijft de ERROR-child ook weg als de database de opslag
  mogelijk toch vastlegde: de commit was verstuurd maar de bevestiging bleef uit. Een fout
  vóór de commit krijgt de ERROR-child wel, ook bij een verbroken verbinding; alleen als daarna
  ook het terugdraaien mislukt, is dat niet meer te onderscheiden en blijft hij weg.
* **Pogingen:** elke leverpoging heeft een eigen logregel met `publicatie.poging`, en deelt
  `publicatie.bericht_id` en `publicatie.doel` met de andere pogingen voor dezelfde verstrekking.
  Het nummer is het aantal geregistreerde mislukte pogingen + 1; na een teruggedraaide verwerking
  kan een nummer terugkomen. Meerdere regels zonder ERROR-child voor hetzelfde bericht en doel
  betekenen dat er *mogelijk* meer dan eens is verstrekt: meestal een onzekere poging gevolgd
  door een retry, zeldzamer het duplicate-send-venster na een 2xx. Het foutbeeld per poging
  staat in de applicatielog en de claim-status.
* **Foutattributen:** `exception.type` op een ERROR-child is een vaste samenvattingsklasse
  (`…common.LdvFoutSamenvatting` of `…publicatie.LeveringMislukt`), nooit het echte fouttype.
  Filter daarom op `exception.message`: bij aanleveren de volledige klassenaam van de fout, bij
  publiceren een categorie (`NetwerkFout`, `Timeout`, `SerialisatieFout`, `ConfiguratieFout`,
  `OpbouwFout`). De foutmelding zelf staat er nooit in,
  want die rijen gaan bij een inzageverzoek naar buiten; het foutbeeld staat, gesaneerd, in de
  applicatielog.
* **`LDV_UITKOMST_ONTBREEKT`** (ERROR, met soort verwerking, `berichtId`, bij publiceren ook doel
  en `claimId`, en de `trace_id:span_id` van de betrokken logregels): de ERROR-child kon niet
  worden opgeslagen. De verwerking is mislukt, maar het logboek toont haar als geslaagd. Dat is
  onder-rapportage en vraagt om een alert; herstel is handmatig. Het token werkt alleen met
  `logboekdataverwerking.span-processor=simple`, dat fail-closed toch al vereist.
* **`LDV_OPSLAG_ONZEKER`** (WARN, met het fouttype, de SQLState en `berichtId`): bij een
  aanlevering faalde de commit zonder dat vaststaat of de database het bericht vastlegde. De
  aanleveraar kreeg een fout, het logboek toont de verwerking als geslaagd. Ga met het
  `berichtId` na of het bericht er staat; staat het er niet, dan is dit over-rapportage.
* **`LDV_ZONDER_LOGREGEL`** (ERROR, met methode en pad): een wijzigend verzoek (status bijwerken,
  verwijderen) kreeg een 500 omdat het logboek niet kon schrijven, maar de wijziging was al
  doorgevoerd: de wrapper schrijft pas ná de methode. Het logboek mist die verwerking. Meld de
  ontbrekende regel en herstel hem handmatig; het pad noemt het `berichtId`. Een aanlevering valt
  hier niet onder: die schrijft het logboek vóór de opslag, en telt als geweigerd.
* **`LDV_WISSEN_ONTBREEKT`** (ERROR, met `berichtId` en type ontvanger): het definitief wissen
  slaagde, de logregel daarvan niet. Het bericht is weg en het logboek toont het nog. Meld de
  ontbrekende regel; de rest van de ronde gaat door.

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

## Meetgegevens

Op de beheerpoort (28090, de applicatiepoort met een `2` ervoor) staat `/q/metrics` in
Prometheus-formaat, niet via het publieke adres. Wat erin zit, en de eigen meetwaarde
`fbs_publicatie_aflevering_seconds_*` per downstream: [operations/meetgegevens.md](operations/meetgegevens.md).

## Meldingen

De alertregels staan in [`beheer/meldingen/berichtenmagazijn.yml`](../beheer/meldingen/berichtenmagazijn.yml) en horen bij
het product; een organisatie laadt ze op haar eigen Prometheus, Mimir of Grafana. Elke regel noemt
deze sectie als `runbook_url`. Previews melden niet (`omgeving=~"pr-.*"` valt erbuiten). Drempels en
de reden ervoor: [`beheer/README.md`](../beheer/README.md).

| Melding | Ernst | Wat het betekent | Wat te doen |
|---|---|---|---|
| `MagazijnLevertGeenMeetgegevens` | kritiek | Vijf minuten lang levert geen magazijn meetgegevens | Draait de dienst (`/q/health`, logs)? Bereikt de scrape de beheerpoort, met het token (`BEHEERPOORT_GEWEIGERD` in de log)? Zonder meetgegevens zijn de andere regels blind |
| `MagazijnFoutpercentageHoog` | kritiek | Meer dan 5% van de verzoeken eindigt in een 5xx; een 429 telt niet | Gaat `MagazijnLogboekWeigert` ook af, dan is het logboek de oorzaak. Anders de log op `errorId` en de database (connections in het dashboard) |
| `MagazijnPublicatieStaatStil` | waarschuwing | Een aanmelding wacht al meer dan een kwartier op publicatie | Meestal de bestemming: kijk naar de afleverpogingen per uitkomst; zie [Outbox-monitoring](#outbox-monitoring) |
| `MagazijnPublicatieMislukt` | waarschuwing | Een aanmelding is definitief mislukt; de ontvanger hoort niets over dat bericht | Zoek `Bericht-publicatie definitief mislukt` in de log (met `berichtId`, `doel` en `reden`), herstel de bestemming en lever opnieuw aan |
| `MagazijnTellingVerouderd` | waarschuwing | De telling van de achterstand loopt meer dan tien minuten achter | De log op fouten van `PublicatieAchterstand`, en de database. Tot dan zeggen de twee regels hierboven niets |
| `MagazijnWissenStaatStil` | waarschuwing | Het definitief wissen loopt meer dan twee dagen achter | `fbs_retentie_fouten_total` per soort, en de log van `HardDeleteJob` |
| `MagazijnWissenFouten` | waarschuwing | Fouten bij het wissen: een bericht niet te wissen, gewist zonder logregel, of een afgebroken ronde | De log van `HardDeleteService`; bij `logboek` ook `LDV_WISSEN_ONTBREEKT` |
| `MagazijnLogboekWeigert` | kritiek | Het logboek kon een verwerking niet vastleggen; het magazijn faalt dicht | De database van het logboek (`LDV_POSTGRES_URL`) en de log op `LogboekWriteException` |
| `MagazijnLogboekWaarschuwing` | waarschuwing | Een alert-token van het logboek (label `code`) | Per token in [Logging & observability](#logging--observability) |

## Bekende monitoring-follow-ups (vóór productie)

Twee event-categorieën verdienen nog een eigen counter:

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
