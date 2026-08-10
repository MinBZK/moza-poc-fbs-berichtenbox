# PostgreSQL als LDV-backend (issue #736)

**Status:** Uitgevoerd

> **Over de versienummers.** Dit ontwerp is geschreven tegen `2.0.0-SNAPSHOT`. Tijdens de
> uitvoering verscheen dezelfde code als `1.0.0` op Maven Central; overal waar hieronder
> "2.0.0" staat, gaat het om die inmiddels gereleasede versie.

## Context

Het Logboek Dataverwerkingen schrijft nu naar ClickHouse. ClickHouse is gebouwd voor
zeer grote volumes en dat kost navenant geheugen: op ZAD draaien twee ClickHouse-
componenten met een limit van enkele Gi, terwijl het PoC-volume in de orde van
tientallen logregels per demo ligt.

De LDV-wrapper `nl.mijnoverheidzakelijk.ldv:logboekdataverwerking-wrapper` heeft sinds
2.0.0 een `SpanRepository`-abstractie met twee implementaties. De backend wordt gekozen
met `logboekdataverwerking.dbms` (`clickhouse` of `postgresql`). Wij stappen over op
PostgreSQL en houden ClickHouse als schakelbare optie.

De versiesprong 1.2.1 → 2.0.0 brengt meer mee dan de nieuwe backend. Drie wijzigingen
raken onze code direct; die staan hieronder bij "Gedragswijzigingen uit 2.0.0".

## Uitgangspunten

- Eén logboek per organisatie: elke service schrijft naar de database van zijn eigen
  deployment. Dat spiegelt ZAD (elke component krijgt een eigen platformdatabase) en
  het model van de standaard, waarin elke verwerkingsverantwoordelijke een eigen
  logboek voert.
- Geen aparte LDV-database naast de domeindatabase. De tabel `logboek_dataverwerkingen`
  komt in dezelfde database te staan als de domeintabellen van die service.
- PostgreSQL wordt de default; `LDV_DBMS=clickhouse` blijft een werkend pad.
- Fail-closed: een verwerking die niet gelogd kon worden, telt niet als uitgevoerd.

## Afhankelijkheden

`logboekdataverwerking-wrapper` van `1.2.1-SNAPSHOT` naar `1.0.0` in de parent-POM. Het
werk begon op `2.0.0-SNAPSHOT`, maar tijdens de uitvoering verscheen de eerste publieke
release op Maven Central. Alle klassen daarin zijn byte-identiek aan die van de snapshot en
de gedeclareerde dependencies zijn gelijk; alleen de jar-metadata verschilt (versienummer,
build-JDK, javadoc-plugin). De overstap raakt dus geen code. Daarmee
kon ook de `central-portal-snapshots`-repository uit de POM: geen enkele module hangt nog
aan een externe snapshot, wat de build reproduceerbaar maakt.

In deze versie zijn beide JDBC-drivers `optional` en komen ze niet meer transitief mee. De
consumer declareert de driver van de backend die hij kiest:

- `berichtenmagazijn` heeft `quarkus-jdbc-postgresql` al — geen wijziging.
- `berichtenuitvraag` krijgt een kale `org.postgresql:postgresql` (versie uit de
  Quarkus-BOM). Bewust niet de Quarkus-extensie: die verwacht een geconfigureerde
  datasource, en uitvraag heeft er geen. LDV opent zijn eigen JDBC-verbinding.

## Configuratie

Beide services, in `application.properties`:

```properties
logboekdataverwerking.dbms=${LDV_DBMS:postgresql}
logboekdataverwerking.span-processor=simple
logboekdataverwerking.write-failure-policy=fail-closed

logboekdataverwerking.postgresql.url=${LDV_POSTGRES_URL}
logboekdataverwerking.postgresql.username=${LDV_POSTGRES_USERNAME}
logboekdataverwerking.postgresql.password=${LDV_POSTGRES_PASSWORD}
logboekdataverwerking.postgresql.table=logboek_dataverwerkingen
```

Tabelnaam gelijk aan de huidige ClickHouse-tabel, zodat een switch tussen backends geen
naamsverschil oplevert. De wrapper maakt de tabel zelf aan (`CREATE TABLE IF NOT EXISTS`
met kolommen `trace_id`, `span_id`, `status`, `name`, `start_time`, `end_time`,
`parent_span_id`, `attributes jsonb`, `resource jsonb`). Dat gebeurt buiten Flyway om;
Flyway raakt alleen zijn eigen migraties, en `hibernate-orm`-validatie struikelt niet
over een tabel die geen entity heeft.

Het ClickHouse-blok blijft staan zodat `LDV_DBMS=clickhouse` werkt. De wrapper valideert
bij start alleen de properties van de gekozen backend, dus het ongebruikte blok mag leeg
blijven. `LdvEndpointValidator` injecteert beide endpoint-keys wél onvoorwaardelijk en
doet dat als `Optional`: SmallRye behandelt een lege waarde als afwezig, zodat een gewone
`String`-injectie zou falen (SRCFG00014) zodra `LDV_CLICKHOUSE_ENDPOINT` van een
PostgreSQL-deployment verdwijnt. `%dev` en `%test` houden lokale defaults; `%prod` krijgt ze niet — een
ontbrekende env-var moet een startup-fout geven, niet een stille default.

### Waar de spans landen

| Service | Lokaal | ZAD |
|---|---|---|
| magazijn-a | `postgres-a`, database `berichtenmagazijn` | platformdatabase van `mpfm-w3h`, schema `magazijna` |
| magazijn-b | `postgres-b`, database `berichtenmagazijn` | platformdatabase van `mpfm-w3h`, schema `magazijnb` |
| uitvraag | nieuwe container `postgres-uitvraag`, database `ldv_logging` | platformdatabase van `mpfb-8wh` |

Op ZAD komen de waarden uit de variabelen die het platform aan een component met de
service `postgresql-database` meegeeft:

```yaml
LDV_POSTGRES_URL: jdbc:postgresql://$DATABASE_SERVER_HOST:5432/$DATABASE_DB
LDV_POSTGRES_USERNAME: $DATABASE_SERVER_USER
LDV_POSTGRES_PASSWORD: $DATABASE_PASSWORD
```

### Schema-scheiding tussen magazijnen

ZAD levert die database **per deployment, niet per component**: `magazijna` en `magazijnb`
mounten allebei hetzelfde `<deployment>-database`-secret, dus ze delen host, database én
DB-user. Alleen `DB_SCHEMA` — uit een secret dat wél per component wordt gerenderd —
scheidt ze, en het zet het search_path niet: de datasource geeft het door als
`currentSchema`, waardoor de app-tabellen in `magazijna` respectievelijk `magazijnb`
terechtkomen terwijl de DB-user zelf een deployment-breed default-schema heeft.

De wrapper voert zijn `CREATE TABLE IF NOT EXISTS` en `INSERT` ongekwalificeerd uit en de
LDV-URL zet geen `currentSchema`. Zonder maatregel landt het logboek dus in dat gedeelde
default-schema en schrijven beide magazijnen in één tabel — terwijl het logboek per
organisatie gescheiden hoort te zijn. Daarom draagt de tabelnaam in `%prod` een prefix:

```properties
%prod.logboekdataverwerking.postgresql.table=${DB_SCHEMA}.logboek_dataverwerkingen
```

De wrapper interpoleert de tabelnaam rauw in beide statements, dus een prefix werkt voor de
DDL en de insert. Wat hij wél doet: `TableNames.requireValid` weigert bij constructie alles
buiten `^[a-zA-Z_][a-zA-Z0-9_.]*$`. Een `DB_SCHEMA` met een koppelteken — bijvoorbeeld
afgeleid van een deploymentnaam als `pr-168` — komt daar niet doorheen. Die guard zit in een
externe library; valt hij ooit weg, dan verdwijnt de enige bescherming tegen een onbruikbare
naam in de SQL.

Fout gaat dat pas laat op: de wrapper bouwt zijn repository in een `@ApplicationScoped`-bean
met `@PostConstruct` en zonder `@Startup`, dus lazy. Een onbruikbare naam laat de service
groen opstarten en door de health checks komen, waarna élk verzoek een 500 geeft — een deploy
die er geslaagd uitziet terwijl er niets werkt. `LdvTabelnaamValidator` in `fbs-common` haalt
dat naar voren: die eist bij het opstarten een niet-lege naam in de vorm `tabel` of
`schema.tabel`, met kleine letters. Kleine letters omdat de naam ongequote de SQL in gaat en
PostgreSQL hem dan vouwt; een schema dat gequote mét hoofdletters is aangemaakt, wordt daarna
niet gevonden.

`LdvTabelnaamConfigTest` borgt dat de configuratie van de service ook werkelijk een prefix
oplevert, en `LdvSchemaGekwalificeerdTest` dat de prefix vervolgens werkt — die laatste laat
het schema door Flyway aanmaken, net als in productie, zodat de ordening schema-vóór-export
zelf getest wordt.

Berichtenuitvraag laat de naam ongekwalificeerd: die service kent geen `DB_SCHEMA` (geen
Flyway, geen JPA) en is het enige app-component in zijn project, dus daar valt niets te
scheiden. Die redenering vervalt zodra `mpfb-8wh` een tweede component krijgt dat naar
dezelfde LDV-database schrijft.

**De scheiding is organisatorisch, niet afgedwongen.** Beide magazijnen gebruiken dezelfde
DB-user, en die heeft rechten op beide schema's. Magazijn-a kán dus
`SELECT * FROM magazijnb.logboek_dataverwerkingen` doen en daarmee de `dataSubjectId`-waarden
(BSN) van de betrokkenen van een andere organisatie lezen. Wat deze maatregel oplost is het
per ongeluk vermengen van logboeken; toegangscontrole vergt een eigen DB-rol per component of
een aparte database. Zie TODO(#928).

## Gedragswijzigingen uit 2.0.0

### 1. `addLogboekContextToSpan` gooit niet meer

In 1.2.1 gooide deze methode een `IllegalArgumentException` op een onvolledige of
ongeldige context. In 2.0.0 waarschuwt hij (LDV 3.3.2.1: loggen mag de verwerking nooit
breken) en exporteert hij de logregel met wat er wél is. Daarmee wordt bestaande code
onbereikbaar:

- `AanleverResource` — inner `catch (IllegalArgumentException)` weg.
- `PublicatieClaimVerwerker` — idem.
- `LogboekContextDefaultFilter` blijft, maar zijn KDoc klopt niet meer. Hij voorkomt
  geen exception meer; hij zorgt dat een request dat vóór de resource wordt afgewezen
  tóch een logregel met ingevulde betrokkene-velden oplevert in plaats van een
  waarschuwing over ontbrekende context.

### 2. Fail-closed loopt via de interceptor, niet via de exporter

De exporter registreert een schrijffout op een thread-gebonden recorder. De
`LogboekInterceptor` consumeert die na `span.end()` met
`enforceWriteAcknowledgement(throwOnFailure = <geen exception onderweg>)` en gooit dan
een `LogboekWriteException`. Alles met `@Logboek` krijgt dat gratis. `throwOnFailure` is
`false` zodra er al een exception propageert, zodat een schrijffout de functionele fout
niet maskeert.

Twee plaatsen bouwen hun span met de hand en krijgen dus niets:

**`AanleverResource`** — bij binnenkomst `LogboekWriteFailureRecorder.clear()`, na
`span.end()` een `enforceWriteAcknowledgement(throwOnFailure = pendingFailure == null)`.
Een LDV-schrijffout levert dan een 500 op in plaats van een 201. Zonder deze aanroep
blijft de fout bovendien op de pooled thread staan tot een volgend request hem oppikt.

**`PublicatieClaimVerwerker`** — zie hieronder.

### 3. `addLogboekContextToSpan` heeft een derde parameter

`propagatingException` (default `null`). Op het foutpad van `AanleverResource` doorgeven:
anders overschrijft een optimistische `OK` uit de context de ERROR-status, en missen de
per-betrokkene child-logregels hun `exception.*`-attributen. Op het publicatiepad speelt
dit niet meer — daar sluit de span vóór de levering, dus er propageert op dat moment nog
geen exception.

## Publicatiepad: logregel vóór de levering

`PublicatieClaimVerwerker` levert nu eerst het CloudEvent af en sluit daarna de span,
binnen één `@Transactional(REQUIRES_NEW)`. De huidige code vangt LDV-fouten bewust weg
met als reden: een rollback na een geslaagde levering geeft een dubbele levering.

Fail-closed wil precies wel gooien. De oplossing is de volgorde omdraaien: de logregel
wordt geschreven en bevestigd vóór de HTTP-call. Faalt het schrijven, dan gooit
`enforceWriteAcknowledgement`, rolt de transactie, blijft de claim openstaan en is er
niets geleverd — een veilige retry.

Let op: de LDV-schrijfactie zit niet in onze JTA-transactie. `PostgresRepository` gebruikt
een eigen verbinding met een eigen commit, dus een rollback van de claim verwijdert de
logregel niet. De logregel kan daarom niet zeggen *dat* er verstrekt is, alleen dat het
op het punt stond te gebeuren. We leggen dat vast als één logregel per poging met status
`UNSET`. De uitkomst van de levering blijft waar hij nu ook al staat: de claim-status in
de database en het applicatielog. Een mislukte levering laat dus een logregel achter voor
een verstrekking die niet plaatsvond, en elke retry voegt er één toe. Dat is de prijs voor
"nooit een verstrekking zonder logregel"; over-rapporteren is hier het veiligere uiterste.

## Aanleverpad: logregel vóór de opslag

Hetzelfde argument als op het publicatiepad, met een ander gevolg. `leverBerichtAan` is
zelf niet transactioneel en `slaBerichtOp` is dat wél, dus bericht én outbox-leveringen
committen zodra die methode terugkeert. Wordt de logregel daarná geschreven en faalt dat,
dan staat het bericht er al, levert de poller het af, krijgt de aanleveraar een 500 en
levert die opnieuw aan — met een nieuw `berichtId`, dus een nieuwe deterministische
CloudEvent-id waarop downstream-dedup op `(source, id)` niet aanslaat.

Daarom is `BerichtOpslagService` gesplitst in `valideerAanlevering` (bouwt en valideert,
raakt de database niet) en `slaBerichtOp` (persisteert). De resource schrijft en bevestigt
de logregel daartussen. De AVG-context krijgt zijn `dataSubjectId`/`dataSubjectType` uit
het gevalideerde domeinobject, niet uit de rauwe request.

Net als bij publiceren legt de logregel daarmee het voornemen vast, niet de uitkomst: een
opslagfout ná dat punt laat een logregel achter voor een aanlevering die niet plaatsvond
(TODO(#924)).

### Gevolg: de fault-tolerance-grens verschuift mee

De splitsing haalt `validatieService.valideer(...)` — inclusief de REST-call naar de
Profiel-service — uit de methode met de `@CircuitBreaker`. Dat is precies de bedoeling
voor de transactie (een trage upstream mag geen JTA-transactie openhouden), maar het
neemt ook de load shedding weg die er ongemerkt bij zat.

Een HTTP-fout van de Profiel-service kwam al als `ClientWebApplicationException` in
`skipOn` en telde nooit mee. Een Profiel-*storing* — connection refused, reset,
read-timeout — surfacet als `jakarta.ws.rs.ProcessingException`, staat niet in `skipOn`,
en opende het circuit dus wél. Zonder compensatie zou een dode Profiel-service elke
aanlever-request tot ~21 s laten hangen (per poging 2 s `connect-timeout` + 5 s
`read-timeout`, drie pogingen met 200 ms backoff ertussen), met worker-threads die
vollopen, in plaats van na 20 requests direct 503'en.

`valideerAanlevering` heeft daarom een eigen `@CircuitBreaker` met dezelfde drempels.
Twee upstreams die los van elkaar uitvallen, krijgen zo elk hun eigen circuit. In
`skipOn`: `DomainValidationException`, `ToestemmingGeweigerdException` en
`WebApplicationException` — client-fouten, policy-besluiten en elk HTTP-antwoord van de
upstream (ook een 5xx komt meteen terug en geeft dus geen latency-amplificatie). Wat
overblijft is `ProcessingException`: connection refused, reset, read-timeout, DNS-fout en
malformed response. `ValidatieCircuitBreakerTest` legt beide kanten vast.

## Fail-closed-guard

`span-processor` en `write-failure-policy` zijn gewone config-properties, en een env-var
(ordinal 300) wint van `application.properties` (250). `batch` exporteert op een
achtergrondthread terwijl de schrijffout-recorder een `ThreadLocal` is — de request-thread
consumeert dan altijd `null` — en `fail-open` slikt de fout al in de exporter. Beide
schakelen fail-closed stil uit. `LdvFailClosedValidator` (fbs-common, `StartupEvent`) eist
daarom buiten `dev`/`test` `simple` + `fail-closed`, in dezelfde vorm als
`LdvEndpointValidator`: bean voor de config-binding, `companion object` voor de
beslissing.

## TLS-guard

`LdvEndpointValidator` eist nu `https://` op de ClickHouse-endpoint in `%prod`,
`%staging` en `%acceptatie` (BIO 13.2.1: het `dataSubjectId` kan een BSN bevatten). Een
JDBC-URL heeft geen scheme om op te controleren, dus de guard wordt dbms-bewust:

- `dbms=clickhouse` → bestaande `OutboundTlsValidator.requireHttps`.
- `dbms=postgresql` → nieuwe `OutboundTlsValidator.requireJdbcTls`: de URL moet
  `ssl=true` bevatten, of een `sslmode` die daadwerkelijk versleutelt (`require`,
  `verify-ca`, `verify-full`). `disable`, `allow` en `prefer` worden afgewezen —
  `prefer` valt stil terug op plaintext en biedt dus geen garantie.

Dezelfde ontsnapping als nu: `fbs.ldv.unsafe-allow-plaintext-endpoint=true`, default
`false`. ZAD blijft die override nodig hebben, want de platformdatabase is intern zonder
TLS bereikbaar.

## Lokale omgeving en deploy

`compose.yaml`: `clickhouse` eruit, `postgres-uitvraag` erin (`postgres:18`, host-poort
5434, database `ldv_logging`, eigen volume, healthcheck in dezelfde vorm als
`postgres-a`/`postgres-b`). De magazijn-containers krijgen `LDV_POSTGRES_URL` naar hun
eigen postgres met de credentials die ze al hebben; uitvraag wijst naar
`postgres-uitvraag`.

`compose.podman-hostnet.yaml` en `demo/podman-up.sh` hebben dezelfde wijziging nodig,
maar bestaan nog niet op `main` — ze komen mee met PR #163. Deze branch vertrekt van
`main`, dus die twee volgen in een rebase zodra #163 gemerged is.

`.github/workflows/deploy.yml`: `CLICKHOUSE_IMAGE` weg, evenals de kopregels die
ClickHouse als projectcomponent beschrijven. `pin-consistency.yml`: clickhouse uit de
image-loop. Er komt geen `POSTGRES_IMAGE` voor terug — op ZAD levert het platform de
database, dus er is niets te pinnen.

Buiten deze repo, handmatig in Operations Manager (hoort in de PR-beschrijving):

1. Service `postgresql-database` koppelen aan component `uitvraag` in `mpfb-8wh`.
2. Op `uitvraag`, `magazijna` en `magazijnb`: `LDV_CLICKHOUSE_ENDPOINT` vervangen door de
   drie `LDV_POSTGRES_*`-aliassen.
3. De `clickhouse`-componenten uit `mpfb-8wh` en `mpfm-w3h` verwijderen.
4. `FBS_LDV_UNSAFE_ALLOW_PLAINTEXT_ENDPOINT=true` blijft staan.

## Documentatie

`README.md` (drie plaatsen), `docs/demo-runbook.md` (infra-opsomming en poorttabel),
`docs/operator-handleiding.md` (env-var-tabel: `LDV_CLICKHOUSE_ENDPOINT`,
`CLICKHOUSE_USERNAME` en `CLICKHOUSE_PASSWORD` maken plaats voor `LDV_DBMS` en de
`LDV_POSTGRES_*`-drieslag) en `CLAUDE.md` (env-var-tabel plus de regels die ClickHouse
als lokale infra noemen).

## Tests

- `LdvEndpointValidatorTest` en `OutboundTlsValidatorTest`: `@ParameterizedTest` over
  dbms × profiel × URL-vorm. Voor PostgreSQL minimaal: geen ssl-parameter,
  `sslmode=disable`, `allow`, `prefer`, `require`, `verify-full`, en `ssl=true`. Plus de
  unsafe-override aan en uit.
- `AanleverResourceLdvSwallowTest`: de IAE-tak vervalt (die exception bestaat niet meer);
  de correlatie-parity-test op `dataSubjectType` blijft. Nieuw: een `LogboekWriteException`
  uit `enforceWriteAcknowledgement` propageert op het succespad, en op het foutpad wordt
  `throwOnFailure = false` meegegeven zodat de domeinfout niet gemaskeerd wordt.
- `PublicatieClaimVerwerker`-tests: `verifyOrder` dat `span.end()` en de acknowledgement
  vóór `downstreamClient.lever` komen, en dat een `LogboekWriteException` betekent: geen
  levering, claim blijft open.
- Eén nieuwe integratietest op berichtenmagazijn, met een eigen TestProfile dat LDV
  aanzet op `dbms=postgresql` tegen de Dev-Services-database die er toch al is. Doet één
  aanlever-call en leest de rij uit `logboek_dataverwerkingen`: `trace_id`/`span_id`
  gevuld, `name`, en de `attributes`-jsonb met `dpl.core.processing_activity_id` en
  `dpl.core.data_subject_id_type`. Dekt `ensureSchema`, de echte insert en het
  fail-closed-pad end-to-end.
- `LdvTabelnaamConfigTest`: leest de echte `application.properties` met profiel `prod` en
  controleert dat de tabelnaam het schema van de organisatie draagt. Zonder deze test dekt
  niets de configregel zelf — `LdvSchemaGekwalificeerdTest` zet de naam in zijn TestProfile
  en blijft groen als de `%prod`-regel wegvalt. Geen `@QuarkusTest`, want die draait in
  profiel `test` en evalueert de regel dus nooit.
- `LdvSchemaGekwalificeerdTest`: dezelfde aanlevering, maar met een schema-prefix in de
  tabelnaam. Meet als verschil hoeveel logregels erbij komen — twee aanleveringen, twee
  regels in het eigen schema en nul erbij in het default-schema — zodat de test niet afhangt
  van de volgorde waarin testklassen een gedeelde container gebruiken. Het schema komt van
  Flyway, net als in productie.
- `LdvOntbrekendSchemaTest`: legt vast dat een tabelnaam naar een niet-bestaand schema de
  service niet tegenhoudt bij het opstarten, maar élk verzoek op een 500 laat lopen. Dat is
  de reden dat `LdvTabelnaamValidator` de vórm van de naam al bij boot controleert.
- `LdvTabelnaamValidatorTest`: de grenzen van die guard — leeg, leidende punt (het gevolg van
  een niet-ingevulde `DB_SCHEMA`), koppeltekens, hoofdletters, meerdere punten — plus de
  gevallen waarin hij zich juist stil houdt (dev, test, ClickHouse-backend).
- `logboekdataverwerking.enabled=false` blijft de default in alle testresources. Fail-closed
  breed aanzetten zou de hele suite van een draaiende LDV-database afhankelijk maken.

## Verificatie

- `./mvnw clean verify -pl services/berichtenmagazijn -am`
- `./mvnw clean test -pl services/berichtenuitvraag -am`
- `./mvnw detekt:check`
- `docker compose up -d` gevolgd door beide services in dev-mode; controleren dat
  `logboek_dataverwerkingen` in `postgres-a` en `postgres-uitvraag` rijen krijgt.
- Demo-stack starten volgens `docs/demo-runbook.md` en één scenario doorlopen.
- Na deploy: op een preview-deployment controleren dat beide services starten en dat de
  tabel in de platformdatabase wordt aangemaakt.

Uitgevoerd op preview `pr-168`: een aanlevering bij magazijn-a én magazijn-b gaf 201 en de
aanmeld-webhook op uitvraag gaf 202. Onder fail-closed betekent elk van die statuscodes een
bevestigde logregel. De 202 is voor uitvraag de maatgevende: de interceptor roept
`enforceWriteAcknowledgement(throwOnFailure = throwable == null)`, dus alleen een geslaagde
aanroep laat een mislukte schrijfactie doorwerken naar de statuscode.

Diezelfde controle bracht de schema-scheiding aan het licht: de logregels van beide
magazijnen stonden in één tabel in het deployment-brede default-schema.

## Openstaand

Bij een mislukte export wordt de logregel niet opnieuw aangeboden — geen enkele
OpenTelemetry-spanprocessor doet dat, ongeacht de backend. Fail-closed maakt het verlies
zichtbaar (de verwerking faalt), maar herstelt niets. Een outbox voor logregels is een
apart vervolgtraject; dit ontwerp voert het niet in.

De schema-scheiding is niet afgedwongen: dezelfde DB-user heeft rechten op beide schema's
(#928).

De tabel van vóór deze wijziging blijft staan, met de logregels van beide magazijnen door
elkaar. Op previews verdwijnt die met de deployment; op `test` niet (#929).
