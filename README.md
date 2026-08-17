# PoC MOZa Berichtenbox

![Project Status](https://img.shields.io/badge/life_cycle-pre_alpha-red)
[![Test](https://github.com/MinBZK/moza-poc-fbs-berichtenbox/actions/workflows/test.yml/badge.svg)](https://github.com/MinBZK/moza-poc-fbs-berichtenbox/actions/workflows/test.yml)
[![detekt](https://github.com/MinBZK/moza-poc-fbs-berichtenbox/actions/workflows/detekt.yml/badge.svg)](https://github.com/MinBZK/moza-poc-fbs-berichtenbox/actions/workflows/detekt.yml)
[![CodeQL](https://github.com/MinBZK/moza-poc-fbs-berichtenbox/actions/workflows/codeql.yml/badge.svg)](https://github.com/MinBZK/moza-poc-fbs-berichtenbox/actions/workflows/codeql.yml)
![OpenSSF Scorecard](https://api.securityscorecards.dev/projects/github.com/MinBZK/moza-poc-fbs-berichtenbox/badge)

Proof of Concept Berichtenbox voor MijnOverheid Zakelijk (MOZa) binnen het Federatief Berichtenstelsel (FBS).

## Inleiding

Dit project is een Proof of Concept voor de Berichtenbox binnen het Federatief Berichtenstelsel,
beschreven op
<https://www.logius.nl/onze-dienstverlening/interactie/federatief-berichten-stelsel>.

## Doel

Dit Open Source project is opgezet als PoC voor het ontvangen, opslaan en ophalen van berichten
binnen MijnOverheid Zakelijk. Het stelsel is federatief: elke deelnemende organisatie houdt haar
eigen berichten in haar eigen magazijn, en de uitvraag haalt ze bij een sessie op en aggregeert ze
voor het portaal.

- **Berichtenmagazijn** — decentrale opslag per organisatie; ontvangt aangeleverde berichten
  (Aanlever-API) en levert ze uit aan de uitvraag.
- **Berichtenuitvraag** — frontend-API voor het portaal: bevraagt alle magazijnen van de ontvanger,
  streamt voortgang via SSE, bedient lijst, zoeken, detail en bijlagen, en neemt aanmeldingen
  van magazijnen aan.
- **Demo-console** — bedieningspaneel voor demonstraties (magazijnen legen, dataset laden,
  berichten opvoeren). Draait alleen mee in de demo-stack.

De uitvraag heeft geen losse berichtensessiecache-service meer: die is opgegaan in
`berichtenuitvraag` als in-process library, met Redis als gedeelde backing store.
De notificatievoorkeuren en toestemming van de ontvanger komen van een externe Profiel-service:
die wordt hier wel bevraagd (`libraries/fbs-common`, package `profiel`), maar niet gebouwd —
lokaal en op de testomgeving draait die, net als de notificatiedienst, als stub.

## Repostructuur

De belangrijkste paden:

| Pad                                   | Wat                                                                             |
|---------------------------------------|---------------------------------------------------------------------------------|
| `services/berichtenmagazijn/`         | Magazijn-service (PostgreSQL + Flyway, Aanlever-API)                             |
| `services/berichtenuitvraag/`         | Uitvraag-service (frontend-API, aggregatie, SSE)                                 |
| `services/demo-console/`              | Demo-bedieningspaneel                                                            |
| `libraries/fbs-common/`               | Gedeelde JAX-RS filters, exception mappers, identificatienummers (BSN/RSIN/KvK/OIN), Profiel-client |
| `libraries/fbs-magazijnregister/`     | Koppeling afzender-OIN ↔ magazijn (`Magazijnregister`-facade)                    |
| `libraries/fbs-berichtensessiecache/` | In-process sessiecache op Redis (`Sessiecache`-facade)                           |
| `bruno/`                              | Bruno-collecties met voorbeeldrequests per service                                |
| `demo/`                               | Demo-stack: stubgenerator, smoke-test; `demo/environment/` bevat de FSC-federatieharness |
| `docs/`                               | Architectuur (C4/Structurizr), runbooks, plannen, verantwoording                  |
| `wiremock/`, `toxiproxy/`             | Stubs en fault-injection voor de lokale keten                                     |
| `compose.yaml`                        | Lokale infrastructuur en de volledige demo-stack (`--profile demo`)               |

## Vereisten

- Java 21+
- Maven 3.9+ (of gebruik de meegeleverde Maven wrapper `./mvnw`)
- Docker (voor lokale services: Redis, WireMock, PostgreSQL)
- Python 3 (alleen voor het genereren van de demo-magazijnstubs)

## Snel starten

```bash
# Start lokale services (Redis, WireMock magazijnen, PostgreSQL)
docker compose up -d
```

De services draaien elk in hun eigen Quarkus-dev-mode. Start ze in **aparte terminals**
zodat elke instantie live-reload en de devconsole houdt:

```bash
# Terminal 1 — berichtenmagazijn (poort 8090)
./mvnw compile quarkus:dev -pl services/berichtenmagazijn -am

# Terminal 2 — berichtenuitvraag (poort 8086, bevat de in-process sessiecache)
./mvnw compile quarkus:dev -pl services/berichtenuitvraag -am
```

De `compile`-fase vóór `quarkus:dev` zorgt dat de gedeelde modules onder `libraries/`
(via `-am`) eerst gebouwd worden; zonder `compile` draait Maven alleen het `quarkus:dev`-goal
en faalt de resolution van bijvoorbeeld `fbs-common-<versie>.jar` zolang die niet in de
lokale Maven-repository staat.

| Service              | API                                              | OpenAPI                                 |
|----------------------|--------------------------------------------------|-----------------------------------------|
| berichtenmagazijn    | `http://localhost:8090/api/v1/berichten`         | `http://localhost:8090/openapi.json`    |
| berichtenuitvraag    | `http://localhost:8086/api/v1/berichten`         | `http://localhost:8086/openapi.json`    |

Wil je de uitvraag over méér dan één magazijn laten aggregeren, dan draai je een tweede magazijn
op 8091. Dat is een tweede *organisatie*, dus die heeft een eigen OIN en een eigen database nodig —
zonder die twee overrides publiceert de instantie onder de identiteit van magazijn A en deelt hij
diens opslag:

```bash
MAGAZIJN_OIN=00000001823288444000 \
DB_JDBC_URL=jdbc:postgresql://localhost:5433/berichtenmagazijn \
./mvnw compile quarkus:dev -pl services/berichtenmagazijn -am \
  -Dquarkus.http.port=8091 -Ddebug=5006
```

`localhost:5433` is de `postgres-b`-instantie uit `compose.yaml`; `-Ddebug=5006` voorkomt een
conflict op de debug-poort van de eerste dev-mode. Voor meer dan twee magazijnen is de demo-stack
handiger dan losse dev-modes.

De demo-console (`http://localhost:8095`) hoort bij de demo-stack hieronder en start niet mee in
dev-mode.

## Demo-stack (alles in containers)

> **Volledige runbook** — opzet, persona's, alle bedieningsknoppen, de scenario's stap voor stap
> en de valkuilen: [`docs/demo-runbook.md`](docs/demo-runbook.md).

Voor demonstraties draait de volledige keten in containers, zodat opstarten één commando
is. Bouw eerst de images met jib — opnieuw nodig na elke codewijziging:

```bash
./mvnw clean package -DskipTests \
  -pl services/berichtenmagazijn,services/berichtenuitvraag,services/demo-console -am \
  -Dquarkus.container-image.build=true \
  -Dquarkus.container-image.group=fbs-demo \
  -Dquarkus.container-image.tag=demo
```

> **CORS voor de Berichtenbox-UI** is een runtime-property, uitsluitend gezet als env-var in
> het demo-profiel van `compose.yaml` — de `application.properties` van `berichtenuitvraag`
> bevat geen CORS-config. Enabled zónder `origins` laat alleen same-origin door en de UI op
> `:8095` roept de API op `:8086` aan, dus de allowlist staat er in compose naast. Prod/ZAD
> (profiel prod) krijgt geen enabled, dus die images blijven CORS-loos. Geen build-flag nodig.

> **Apple Silicon / ARM:** jib bouwt standaard `linux/amd64` (de ZAD-cluster is amd64).
> Op een ARM-host draaien die images onder emulatie — voeg
> `-Dquarkus.jib.platforms=linux/arm64` toe voor native images. Deze flag hoort op de
> commandoregel en niet in de config, anders wordt ook de CI-/ZAD-build arm64.

Genereer daarna de stub-artefacten, start de stack en controleer de keten:

```bash
python3 demo/genereer-magazijnen.py   # vult demo/generated/ (git-ignored, dus altijd nodig)
docker compose --profile demo up -d   # alles in containers
./demo/smoke.sh                       # rookproef: aanleveren bij beide magazijnen + ophalen
```

Sla het generatiescript niet over: compose maakt een ontbrekend mount-pad aan als directory,
waarna `magazijnen-stubs.properties` een map wordt en de uitvraag niet meer start.

Zónder `--profile demo` start compose alleen de infrastructuur (Redis, de drie
Postgres-instanties en de WireMock-stubs voor magazijnen, profiel, aanmelden en
notificaties). Gebruik die modus tijdens het ontwikkelen en draai de services met
`quarkus:dev` zoals hierboven — in een container kost elke codewijziging een image-build.

De poorten zijn in beide modi gelijk (8090, 8091, 8086), dus de Bruno-collectie en de
omgeving `lokaal` werken ongewijzigd. Draai niet beide modi tegelijk: dat geeft een
poortconflict.

De demo-console draait op <http://localhost:8095> — een kaal paneel om de magazijnen te
legen, de basisdataset te laden en random berichten op te voeren.

## Tests draaien

Draai altijd `clean` vóór `test` of `verify`: een achtergebleven `target/` van een andere
branch-state laat Surefire stale `.class`-bestanden draaien, wat misleidende fouten geeft in
ongewijzigde code.

```bash
./mvnw clean test -pl libraries/fbs-common -am                # pure JVM
./mvnw clean test -pl libraries/fbs-magazijnregister -am      # pure JVM
./mvnw clean test -pl services/demo-console -am               # pure JVM
./mvnw clean test -pl libraries/fbs-berichtensessiecache -am  # Docker vereist (Testcontainers)
./mvnw clean test -pl services/berichtenmagazijn -am          # Docker vereist
./mvnw clean test -pl services/berichtenuitvraag -am          # Docker vereist
```

`verify` draait bovendien de kwaliteitsgates — detekt (`maxIssues: 0`, zonder baseline) voor de
hele repo, en JaCoCo met minimaal 90% line coverage voor beide services en alle libraries:

```bash
./mvnw clean verify -pl services/berichtenmagazijn -am
./mvnw detekt:check                                           # alleen de statische analyse
```

De OpenAPI-specs valideren tegen de NL API Design Rules:

```bash
npx @stoplight/spectral-cli lint services/berichtenmagazijn/src/main/resources/openapi/berichtenmagazijn-api.yaml \
  --ruleset https://static.developer.overheid.nl/adr/ruleset.yaml
npx @stoplight/spectral-cli lint services/berichtenuitvraag/src/main/resources/openapi/berichtenuitvraag-api.yaml \
  --ruleset https://static.developer.overheid.nl/adr/ruleset.yaml
```

## API-requests handmatig uitvoeren (Bruno)

De `bruno/`-folder bevat per service een collectie van voorbeeld-requests die je
tegen de lokale dev-mode kunt uitvoeren met [Bruno](https://www.usebruno.com/).

- `bruno/berichtenmagazijn/` — aanlever- en beheer-API
- `bruno/berichtenuitvraag/` — frontend-facade (lijst, zoek, ophalen-SSE, detail, bijlage,
  PATCH/DELETE) en de aanmeld-webhook

Open de folder in Bruno, kies environment `lokaal` en run requests. De collectie
spiegelt de OpenAPI-spec: nieuwe endpoints in de spec krijgen direct een
bijbehorende `.bru`-request.

## Configuratie

De belangrijkste configuratie staat in
`services/berichtenuitvraag/src/main/resources/application.properties`. Ingekort weergegeven —
het bestand zelf bevat per magazijn ook nog de FSC-grant-hash:

```properties
# Magazijnregister: de map-key is de afzender-OIN, de waarde het magazijn van die organisatie.
# %dev vult de URL uit een env-var met de lokale poort als default, zodat dezelfde
# configuratie in een container naar container-DNS wijst.
# %dev-default van MAGAZIJN_A_URL: http://localhost:8090
magazijnen."00000000000000100000".url=${MAGAZIJN_A_URL}
magazijnen."00000000000000100000".naam=Magazijn A
# %dev-default van MAGAZIJN_B_URL: http://localhost:8091
magazijnen."00000001823288444000".url=${MAGAZIJN_B_URL}
magazijnen."00000001823288444000".naam=Magazijn B
```

Voor productie-instellingen (TLS-eisen op Redis en het Logboek Dataverwerkingen, verplichte
overrides) geldt de [operator-handleiding](docs/operator-handleiding.md).

## Architectuur en achtergrond

Het C4-model staat als Structurizr DSL in [`docs/architecture/`](docs/architecture/) en wordt
gepubliceerd op <https://minbzk.github.io/moza-poc-fbs-berichtenbox/>; die site wordt ververst
zodra er iets in `docs/architecture/` wijzigt (per PR ook als preview).

Verder lezen:

- [Aanpak en keuzes van de PoC](docs/aanpak-en-keuzes.md) — waarom federatief, welke standaarden
- [Demo-runbook](docs/demo-runbook.md) — de demo-stack en alle scenario's
- [Operator-handleiding](docs/operator-handleiding.md) — verplichte productie-overrides
- [Vergelijking VoRijk (Blauwe Knop) vs. FBS Berichtenbox](docs/vergelijking-fbs-vorijk.md)
- [Analyse: architectuur voor uniforme bronontsluiting](docs/analyse-architectuur-uniforme-bronontsluiting.md)
- [`docs/plans/`](docs/plans/) — implementatieplannen met de gemaakte ontwerpkeuzes

## Bijdragen

Wijzigingen gaan altijd via een feature branch en een Pull Request; er wordt niet direct naar
`main` gepusht. Een PR die code raakt draait tests met coverage-rapportage en detekt; PR's op
`main` draaien daarnaast CodeQL en krijgen een eigen preview-omgeving op ZAD. PR's die alleen
documentatie wijzigen slaan die checks over. Zie [SUPPORT.md](SUPPORT.md) voor contact en
[GOVERNANCE.md](GOVERNANCE.md) voor besluitvorming.

## Licentie

Dit project is gelicenseerd onder de [EUPL-1.2](LICENSE).

## AI-verantwoording

De code in deze PoC is grotendeels gegenereerd met generatieve AI (Claude Code);
alle niet-testcode wordt menselijk gereviewd en testcode wordt functioneel
beproefd. Zie [DISCLAIMER.md](DISCLAIMER.md) voor de
disclaimer en [docs/ai-verantwoording.md](docs/ai-verantwoording.md) voor de
volledige verantwoording, getoetst aan de Overheidsbrede handreiking voor de
verantwoorde inzet van generatieve AI. In
[docs/ai-ervaringen.md](docs/ai-ervaringen.md) delen we onze praktische ervaringen
met het bouwen van deze PoC met AI.

## Ondersteuning

Zie [SUPPORT.md](SUPPORT.md) voor informatie over hoe en waar je hulp kunt krijgen.

## Governance

Zie [GOVERNANCE.md](GOVERNANCE.md) voor informatie over de governance-structuur van dit project.
