**Status:** Concept

# Demo-platform PoC Berichtenbox — ontwerp

Doel: de PoC demonstreerbaar maken voor Product Owner en stakeholders — zowel de
ondernemer-flows in een Berichtenbox-UI als de technische degradatie-scenario's
van het stelsel.

## Context

De PoC heeft vandaag geen manier om zichzelf te tonen. `docs/architecture/workspace-docs/03-poc-afwijkingen.md`
stelt vast: "Er is geen browser-/portaal-laag." Daarnaast ontbreekt demo-datamanagement
(legen/vullen) en is er geen manier om storingen op te wekken zonder handmatig
containers te stoppen.

Dit ontwerp voegt één wegwerp-module toe (`services/demo-console`) plus Toxiproxy in
de compose-stack. **De bestaande services krijgen geen demo-logica in hun gedragspad.**

### Uitgangspunten (vastgesteld in overleg)

| Vraag | Keuze |
|---|---|
| UI-laag | Wegwerp demo-UI; geen NL Design System, geen productiekwaliteit |
| Aanleiding | Demo aan PO/stakeholders — happy flow eerst, degradatie daarna |
| Omgeving | Lokaal (docker compose), niet ZAD |
| Magazijnen | 2 echt (bestaand) + een **variabel** aantal lichtgewicht stubs in één WireMock-container |
| Bediening | Klikbaar bedieningspaneel, bediend door de ontwikkelaar |
| Kwaliteitsgates | `demo-console` valt buiten de 90%-JaCoCo-gate |

## Architectuur

```
browser
  └─> demo-console (nieuwe Quarkus-module, :8095)
        ├── / .............. wegwerp-Berichtenbox-UI
        ├── /console ....... bedieningspaneel
        └── /api/demo/* .... seed / reset / scenario's
              │
              ├─> berichtenuitvraag (:8086) ..... berichten ophalen/tonen
              ├─> berichtenmagazijn a/b ......... aanleveren
              ├─> Postgres a/b (JDBC) ........... legen
              ├─> Toxiproxy admin (:8474) ....... storingen op echte componenten
              └─> WireMock admin ................ storingen op stub-magazijnen
```

Toxiproxy komt tussen `berichtenuitvraag` en zijn afhankelijkheden. Dat is puur een
config-wijziging: in `%dev` is elke afhankelijkheid al een losse URL of host.

| Afhankelijkheid | Nu (`%dev`) | Straks (via Toxiproxy) |
|---|---|---|
| Magazijn A / B | `localhost:8090` / `:8091` | `localhost:18090` / `:18091` |
| Redis | `redis://localhost:6379` | `redis://localhost:16379` |
| Profielservice | `localhost:8089` | `localhost:18089` |
| Aanmeld-stub | `localhost:8083` | `localhost:18083` |
| Notificatie-stub | `localhost:8084` | `localhost:18084` |

### Containerisatie en het TLS-obstakel

De demo draait volledig in containers, zodat opstarten tijdens een sessie één commando is.

`OutboundTlsValidator` (`libraries/fbs-common`) staat `http://` alleen toe in de profielen
`dev` en `test`:

```kotlin
private val PROFIELEN_ZONDER_TLS_EIS = setOf("dev", "test")
```

Een nieuw `%demo`-profiel zou dus https eisen op elke magazijn-URL en elke stub. Dat
betekent zelfondertekende certificaten in de demo-stack — fragiel, en de validator
uitzetten is een security-regressie omwille van een demo.

**Besluit:** containers draaien óók met `QUARKUS_PROFILE=dev`; alleen de URL's worden
via omgevingsvariabelen overschreven. De `%dev`-regels krijgen daarvoor een env-var
met default:

```properties
%dev.magazijnen."00000001003214345000".url=${MAGAZIJN_A_URL:http://localhost:8090}
%dev.quarkus.redis.hosts=${REDIS_HOSTS:redis://localhost:6379}
```

Buiten containers verandert er niets (de default grijpt). `%test` en `%prod` blijven
ongemoeid, dus testsuite en ZAD merken hier niets van.

### Twee compose-profielen

In een container is Quarkus' hot reload weg; elke codewijziging wordt dan een image-build.
Daarom twee modi met dezelfde Toxiproxy en dezelfde stub-mappings:

| Commando | Wat draait | Wanneer |
|---|---|---|
| `docker compose up -d` | Infra: Redis, Postgres, WireMock, Toxiproxy | **Bouwen** — services in `quarkus:dev` |
| `docker compose --profile demo up -d` | Alles, inclusief services en demo-console | **Demo** |

Images via jib — `quarkus-container-image-jib` zit al in beide service-POM's met
`quarkus.container-image.name` ingevuld. De demo-console krijgt dezelfde dependency.
Geen Dockerfiles nodig.

## Demo-datamanagement

### Legen — JDBC-truncate vanuit de demo-console

De magazijn-API kent alleen soft-delete; dat is opzettelijk (zie `CLAUDE.md`, sectie
Database & migraties) en geeft geen schone lei voor een volgende demo. De demo-console
verbindt daarom direct met beide Postgres-databases:

```sql
TRUNCATE berichten, bijlagen, bericht_status, publicatie_deliveries
  RESTART IDENTITY CASCADE;
```

**Waarom niet een reset-endpoint in `berichtenmagazijn`:** een `DELETE /alles` in
productiecode is een voetkanon dat nooit meer weggaat. De demo-console is wegwerp en
mag deze kennis dragen.

### Basisvulling — via de echte aanlever-API

Vaste dataset in `demo/dataset/basis.json` (~40 berichten over beide magazijnen), die
via `POST /berichten` naar binnen gaat. Trager dan `INSERT`, maar loopt door echte
validatie, de publicatie-outbox en de notificatieketen — het vullen demonstreert de
keten dus al.

Herkenbare afzenders (Belastingdienst, KVK, RVO, UWV) en een gespreid
`publicatietijdstip`, zodat sorteren en filteren in de UI betekenis hebben.

**Geen vaste bericht-ID's.** `BerichtAanleverenRequest` vereist `[afzender, ontvanger,
onderwerp, inhoud]` en accepteert géén `berichtId`: het magazijn kent de UUID zelf toe.
Herhaalbaarheid komt daarom uit vaste *inhoud*, niet uit vaste ID's. Twee keer vullen
zonder legen geeft dus dubbele berichten met verschillende ID's — dat is geen
ontdubbeling maar dubbele data, en daarom is legen vóór vullen verplicht in het
bedieningspaneel.

### Random berichten — dezelfde weg, met generator

Eén knop die N random berichten aanlevert. Dit is tegelijk het scenario "tijdens zijn
ingelogde sessie komen er nieuwe berichten beschikbaar": je klikt tijdens de demo, en
de UI toont ze — of juist niet, omdat de sessiecache ze nog niet heeft. Dat laatste is
het gedrag dat uitgelegd moet worden.

De generator respecteert bestaande invarianten: elfproef-geldige BSN/RSIN via `Bsn`/`Rsin`
uit `fbs-common`, en ruim onder `Bericht.MAX_INHOUD_BYTES`.

## Berichtenbox-UI

| UI-eis | Status backend | Aanpak |
|---|---|---|
| Zoeken | `GET /berichten/_zoeken` bestaat | Direct gebruiken |
| Verwijderen | `DELETE /berichten/{id}` bestaat | Direct gebruiken |
| Gelezen/ongelezen | `PATCH` met `status` | Direct gebruiken |
| Bijlage-indicatie | `aantalBijlagen` in samenvatting | Direct gebruiken |
| Eigen map aanmaken | `map` is vrij tekstveld (max 128) | UI houdt mappenlijst bij |
| Archiveren | Bestaat niet | Gereserveerde mapnaam `Archief` |
| Sorteren | Geen sorteerparameter | Client-side |
| Filteren | Geen filterparameter, `map` niet geïndexeerd | Client-side |
| Rode vlag | Bestaat niet | **Uitgesteld naar fase 7** |

### Ontwerpkeuzes en hun schulden

**Archiveren als gereserveerde mapnaam.** Een bericht naar map `Archief` verplaatsen ís
archiveren; `PATCH` kan dat vandaag. Nul backend-werk. Als archiveren later andere
retentie of zichtbaarheid moet krijgen, is dat een echte feature.

**Mappen bestaan alleen in de UI.** Er is geen mappen-entiteit: de mappenlijst is de
verzameling `map`-waarden in de berichten. Gevolg: **een lege map kan niet bestaan.**
De demo-UI houdt daarom zelf een lijstje mapnamen bij in de browser. Dat is een illusie
en wordt niet verstopt.

**Sorteren en filteren client-side.** De spec stelt vast dat filteren per map nog niet
kan omdat de sessiecache `map` niet indexeert (issue #571). Met enkele tientallen
berichten haalt de UI de hele lijst op en sorteert/filtert lokaal.

> Dit toont dat de *interactie* werkt, niet dat het *stelsel* kan filteren op schaal.
> Bij de vraag "en met 10.000 berichten?" is het antwoord: issue #571, en dat is echt
> werk. De filterbalk krijgt een zichtbaar "demo"-label.

**De rode vlag kan niet gefaket worden.** Een bericht kan tegelijk gemarkeerd zijn én
in een map zitten, dus het is geen mapnaam. Het vraagt een echt veld `gemarkeerd` door
de hele keten: OpenAPI-spec van beide services, Flyway-migratie `V8` op `bericht_status`,
serialisatie in de sessiecache, mapping in beide services, tests per laag. Dat raakt
productiecode inclusief de 90%-coveragegate. Uitgesteld: het PO-verhaal staat zonder
vlag, en een half afgemaakte markering is duurder dan zijn demowaarde.

## Scenario-besturing

| # | Scenario | Mechanisme |
|---|---|---|
| 1 | Berichten succesvol opgehaald | Basisvulling + normale flow |
| 2 | Trager dan normaal (>5s) | Toxiproxy `latency` op alle magazijnen |
| 3 | Magazijnen onbereikbaar (weinig/veel) | WireMock per stub / Toxiproxy per echt magazijn |
| 4 | Enkele magazijnen antwoorden pas laat | Idem, per magazijn |
| 5 | Nieuwe berichten tijdens sessie | Random-generator |
| 6 | Cache-tijd verloopt | Zie hieronder |
| 7 | Bijlage wordt niet opgehaald | Toxiproxy `disable` tijdens download |
| 8 | Foutieve aanlevering | Demo-console stuurt ongeldige payloads |
| 9 | Profielservice weg | Toxiproxy `disable` |
| 10 | Notificatieservice weg | Toxiproxy `disable` |
| 11 | Uitvraagsysteem eruit | Toxiproxy `disable` op aanmeld-stub |
| 12 | Redis weg | Toxiproxy `disable` |
| 13 | Ontdubbeling | Bestaat al — dezelfde CloudEvent (zelfde `id`) nogmaals naar de aanmeld-webhook |
| 14 | Load/stress | k6, aparte fase |

**Ontdubbeling (scenario 13) zit op de webhook, niet op het aanleveren.**
`AanmeldDeduplicatie.eerstgezien(eventId)` markeert verwerkte CloudEvents op hun `id`
in een eigen Redis-keyspace, los van de `Sessiecache`-facade. De demo is dus: stuur
tweemaal dezelfde CloudEvent naar `POST /api/v1/aanmeldingen` op de uitvraag en toon
dat er één bericht ontstaat. Tweemaal hetzelfde bericht *aanleveren* bij het magazijn
levert géén ontdubbeling op — dat geeft twee berichten met verschillende ID's.

Elf van de veertien zijn dezelfde bouwsteen: één knop die één toxic aan- of uitzet.
Na de eerste is elke volgende een configuratieregel.

### Cache-verloop (scenario 6)

De cache verloopt tijdgebaseerd, niet sessiegebaseerd. De TTL is config
(`berichtensessiecache.ttl`) en dus niet tijdens runtime te wijzigen. Beide varianten
worden gebouwd, omdat ze verschillende dingen bewijzen:

- **"Laat nu verlopen"-knop** — de demo-console verwijdert de sessie-keys uit Redis.
  Instant, demonstreert het gevolg. Bewijst niet dat de TTL zelf werkt.
- **Korte TTL in de demo-stack** (90 seconden, via env-var) — echte sliding expiry,
  inclusief verlenging bij elke read.

### Het aantal stub-magazijnen is variabel

Het aantal ligt niet vast. Er is één harde beperking: `ConfigMagazijnregister` valideert
het register **fail-fast bij boot**, dus een magazijn toevoegen vraagt een herstart. Het
aantal is daarmee op twee niveaus instelbaar:

| Niveau | Wat | Wanneer |
|---|---|---|
| **Ingericht aantal** | `DEMO_MAGAZIJN_STUBS=<n>` genereert n register-entries en n WireMock-stubs | Bij opstarten van de stack |
| **Actief aantal** | Bedieningspaneel zet stubs aan/uit via de WireMock-admin-API | Tijdens de demo, direct |

Praktische werkwijze: richt het ingerichte aantal ruim in (bijvoorbeeld 25) en varieer
tijdens de demo het *actieve* aantal. Zo schuif je live van "2 magazijnen" naar "27
magazijnen" zonder herstart.

Dat vraagt dat register-entries en stub-mappings **gegenereerd** worden uit één getal,
niet handmatig uitgeschreven. De demo-console genereert ze bij het opstarten; er staat
dus geen lijst van 25 magazijnen in `application.properties` of in de compose-stack.
De OIN's worden afgeleid uit een vast patroon met geldige elfproef, zodat ze door
`Magazijnregister` geaccepteerd worden.

### Storingen op stub-magazijnen: WireMock, niet Toxiproxy

De stub-magazijnen delen één WireMock-container met pad-gebaseerde routering
(`/m01`, `/m02`, …). Ze delen dus één TCP-endpoint, en **Toxiproxy kan daar niet per
magazijn schakelen** — een toxic raakt alle stubs tegelijk.

WireMock kan dat wél, per stub en tijdens runtime via de admin-API:

| Wat te tonen | WireMock per stub |
|---|---|
| Magazijn is traag | `fixedDelayMilliseconds: 6000` |
| Magazijn geeft fout | `status: 503` |
| Verbinding valt weg | `fault: CONNECTION_RESET_BY_PEER` |
| Antwoordt nooit | delay van 30s (loopt in de timeout) |
| Corrupte respons | `fault: MALFORMED_RESPONSE_CHUNK` |

**Wat je hiermee niet dekt:** `connection refused` — geen server om te antwoorden. Dat
raakt connect-timeouts en de circuit breaker op verbindingsniveau in plaats van op
responsniveau. Dat gat wordt gedekt door de twee echte magazijnen, die elk wél achter
een eigen Toxiproxy zitten.

Verdeling: **echte verbindingsuitval** demonstreren op magazijn A of B (Toxiproxy),
**volume** demonstreren op de stubs (WireMock). Geen enkel scenario uit de eisen valt af.

Mocht tijdens het bouwen blijken dat TCP-uitval ook op de stubs nodig is, dan is
opsplitsen naar drie stub-containers (5 magazijnen elk) een wijziging van de basis-URL's
in het magazijnregister.

### Load/stress (scenario 14)

k6, als los scriptbestand. **Een loadtest op een laptop met twee Quarkus-instanties en
twee Postgressen bewijst niets over productiecapaciteit.** Wat hij wél toont is relatieve
degradatie: waar circuit breakers openslaan, hoe de bulkhead zich onder druk gedraagt.
Dat is een legitiem verhaal, maar het is geen capaciteitsbewijs. Hoort daarom in de
laatste fase en los van de PO-demo.

## Fasering

Elke fase eindigt in iets dat draait en getoond kan worden. Eerste demo-waardige
mijlpaal is fase 2.

| Fase | Inhoud | Omvang | Werkend geheel na afloop |
|---|---|---|---|
| 0 | Containeriseren: env-vars met default, compose-profiel `demo`, jib-images | klein | Bestaande keten draait volledig in containers; verifieerbaar met Bruno |
| 1 | Randvoorwaarden: legen (JDBC), basisvulling, random-generator; kaal bedieningspaneel | middel | Magazijnen in seconden te legen en vullen |
| 2 | Berichtenbox-UI happy flow: lijst, detail, bijlage, zoeken, gelezen/ongelezen, verwijderen, sorteren, filteren, mappen + archief | groot | **Eerste PO-demo.** Ondernemer-flow 1 + 7 van 8 Berichtenbox-functies |
| 3 | Toxiproxy voor alle afhankelijkheden; knoppen traagheid, magazijn uit, bijlage onbereikbaar | middel | Ondernemer-flows 2, 4, 7 en scenario 3 op de twee echte magazijnen (het weinig-versus-veel-plaatje volgt in fase 6) |
| 4 | Sessie en cache: nieuwe berichten tijdens sessie, cache laten verlopen, korte TTL | klein | **Ondernemer-lijst compleet** (alle 7 flows) |
| 5 | Technische verantwoording: profiel/notificatie/aanmeld/Redis uit, foutieve aanlevering, ontdubbeling | middel | 6 van 7 technische punten |
| 6 | Veel magazijnen: één WireMock met een variabel aantal pad-gebaseerde magazijnen (gegenereerd uit `DEMO_MAGAZIJN_STUBS`), schuifje voor het actieve aantal | middel | Scenario 3 volledig |
| 7 | Uitgesteld werk: rode vlag door de keten; k6-loadtest | groot | Twee losse brokken, onafhankelijk van elkaar |

Fase 0 gaat bewust eerst: alles daarna leunt erop en het is de goedkoopste fase. Loopt
het daar mis, dan weet je dat vóór er iets bovenop staat.

Geen doorlooptijden in dagen: de relatieve maten kloppen, een dagschatting niet zolang
de beschikbare tijd onbekend is.

Elke fase krijgt een eigen implementatieplan in `docs/plans/`. Dit document is het
overkoepelende ontwerp, niet het uitvoeringsplan van fase 0.

## Bewust buiten scope

- **Issue #571** (filteren op map in de sessiecache) blijft open. De demo werkt eromheen
  met client-side filteren; dat is een bewuste schuld, geen vergeten werk.
- **ZAD-deployment van het demo-platform.** De demo draait lokaal. Op ZAD zou Toxiproxy
  niet werken zoals bedoeld: Argo CD draait met `selfHeal: true` + `prune: true`, dus
  handmatige ingrepen worden teruggedraaid.
- **Productiekwaliteit van de demo-console.** Geen 90%-coveragegate, geen NL Design
  System, geen toegankelijkheidstraject. De module is expliciet wegwerp.

## Verificatie

| Fase | Verificatie |
|---|---|
| 0 | `docker compose --profile demo up -d`; bestaande Bruno-collectie draait groen tegen de containers. `./mvnw clean test` blijft groen (bewijst dat `%test`/`%prod` ongemoeid zijn) |
| 1 | Legen → lijst is leeg; vullen → 40 berichten; nogmaals vullen zonder legen → 80 berichten (bewijst dat het magazijn eigen ID's toekent en dat legen verplicht is) |
| 2 | Handmatige doorloop van alle 8 Berichtenbox-functies in de UI |
| 3 | Per knop: gedrag in de UI komt overeen met het scenario; circuit breaker-logs bevestigen het foutpad |
| 4 | Cache-verloop zichtbaar via zowel de knop als het aflopen van de korte TTL |
| 5 | Per uitgeschakelde afhankelijkheid: de UI degradeert zoals ontworpen, geen 500 zonder correlation-id |
| 6 | Aantal instelbaar bij opstarten (test met minstens 2, 10 en 25 stubs); tijdens de demo geeft "1 van de N uit" een zichtbaar andere gebruikerservaring dan "meer dan de helft uit" |
| 7 | Vlag: unit- + integratietests per laag, coveragegate blijft gehaald. k6: rapport met latency-percentielen |
