# PoC MOZa Berichtenbox

![Project Status](https://img.shields.io/badge/life_cycle-pre_alpha-red)
[![Test](https://github.com/MinBZK/moza-poc-fbs-berichtenbox/actions/workflows/test.yml/badge.svg)](https://github.com/MinBZK/moza-poc-fbs-berichtenbox/actions/workflows/test.yml)
[![detekt](https://github.com/MinBZK/moza-poc-fbs-berichtenbox/actions/workflows/detekt.yml/badge.svg)](https://github.com/MinBZK/moza-poc-fbs-berichtenbox/actions/workflows/detekt.yml)
[![CodeQL](https://github.com/MinBZK/moza-poc-fbs-berichtenbox/actions/workflows/codeql.yml/badge.svg)](https://github.com/MinBZK/moza-poc-fbs-berichtenbox/actions/workflows/codeql.yml)
![OpenSSF Scorecard](https://api.securityscorecards.dev/projects/github.com/MinBZK/moza-poc-fbs-berichtenbox/badge)

Proof of Concept Berichtenbox voor MijnOverheid Zakelijk (MOZa) binnen het Federatief Berichtenstelsel (FBS).

## Inleiding

Dit project is een Proof of Concept voor de Berichtenbox binnen het Federatief Berichtenstelsel,
beschreven op https://www.logius.nl/onze-dienstverlening/interactie/federatief-berichten-stelsel.

## Doel

Dit Open Source project is opgezet als PoC voor het ontvangen, opslaan en ophalen van berichten
binnen MijnOverheid Zakelijk. Het stelsel is federatief: elke deelnemende organisatie houdt haar
eigen berichten in haar eigen magazijn, en de uitvraag haalt ze bij een sessie op en aggregeert ze
voor het portaal.

- **Berichtenmagazijn** — decentrale opslag per organisatie; ontvangt aangeleverde berichten
  (Aanlever-API) en levert ze uit aan de uitvraag.
- **Berichtenuitvraag** — frontend-API voor het portaal: bevraagt alle magazijnen van de ontvanger,
  streamt voortgang via SSE en bedient lijst, zoeken, detail en bijlagen.
- **Demo-console** — bedieningspaneel voor demonstraties (magazijnen legen, dataset laden,
  berichten opvoeren).

De uitvraag heeft geen losse berichtensessiecache-service meer: die is opgegaan in
`berichtenuitvraag` als in-process library, met Redis als gedeelde backing store.
Het berichtnotificatieprofiel en de notificatiedienst zitten niet in deze repository — lokaal en op
de testomgeving draaien die als stubs.

## Repostructuur

| Pad                                | Wat                                                                        |
|------------------------------------|----------------------------------------------------------------------------|
| `services/berichtenmagazijn/`      | Magazijn-service (PostgreSQL + Flyway, Aanlever-API)                        |
| `services/berichtenuitvraag/`      | Uitvraag-service (frontend-API, aggregatie, SSE)                            |
| `services/demo-console/`           | Demo-bedieningspaneel                                                       |
| `libraries/fbs-common/`            | Gedeelde JAX-RS filters, exception mappers, identificatienummers (BSN/RSIN/OIN) |
| `libraries/fbs-magazijnregister/`  | Koppeling afzender-OIN ↔ magazijn (`Magazijnregister`-facade)               |
| `libraries/fbs-berichtensessiecache/` | In-process sessiecache op Redis (`Sessiecache`-facade)                   |
| `bruno/`                           | Bruno-collecties met voorbeeldrequests per service                          |
| `demo/`                            | Demo-stack: stubgenerator, smoke-test, omgevingen                           |
| `docs/`                            | Architectuur (C4/Structurizr), runbooks, plannen, verantwoording            |
| `wiremock/`, `toxiproxy/`          | Stubs en fault-injectie voor de lokale keten                                |

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
zodat beide live-reload en de devconsole blijven werken:

```bash
# Terminal 1 — berichtenmagazijn (poort 8090)
./mvnw compile quarkus:dev -pl services/berichtenmagazijn -am

# Terminal 2 — berichtenuitvraag (poort 8086, bevat de in-process sessiecache)
./mvnw compile quarkus:dev -pl services/berichtenuitvraag -am
```

De `compile`-fase vóór `quarkus:dev` zorgt dat de gedeelde modules onder `libraries/`
(via `-am`) eerst gebouwd worden; zonder `compile` draait Maven alleen het `quarkus:dev`-goal
en faalt de resolution van bijvoorbeeld `fbs-common-0.1.0-SNAPSHOT.jar` zolang die niet in de
lokale Maven-repository staat.

| Service              | API                                              | OpenAPI                                 |
|----------------------|--------------------------------------------------|-----------------------------------------|
| berichtenmagazijn    | `http://localhost:8090/api/v1/berichten`         | `http://localhost:8090/openapi.json`    |
| berichtenuitvraag    | `http://localhost:8086/api/v1/berichten`         | `http://localhost:8086/openapi.json`    |
| demo-console         | `http://localhost:8095`                          | —                                       |

Een tweede magazijn draai je op 8091, zodat de uitvraag over meerdere magazijnen kan aggregeren:

```bash
./mvnw quarkus:dev -pl services/berichtenmagazijn -Dquarkus.http.port=8091
```

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
Postgres-instanties, WireMock). Gebruik die modus tijdens het ontwikkelen en draai de services met
`quarkus:dev` zoals hierboven — in een container kost elke codewijziging een image-build.

De poorten zijn in beide modi gelijk (8090, 8091, 8086), dus de Bruno-collectie en de
omgeving `lokaal` werken ongewijzigd. Draai niet beide modi tegelijk: dat geeft een
poortconflict.

De demo-console draait op <http://localhost:8095> — een kaal paneel om de magazijnen te
legen, de basisdataset te laden en random berichten op te voeren.

### Tests draaien

Draai altijd `clean` vóór `test` of `verify`: een achtergebleven `target/` van een andere
branch-state laat Surefire stale `.class`-bestanden draaien, wat misleidende fouten geeft in
ongewijzigde code.

```bash
./mvnw clean test -pl libraries/fbs-common -am                # pure JVM
./mvnw clean test -pl libraries/fbs-magazijnregister -am      # pure JVM
./mvnw clean test -pl libraries/fbs-berichtensessiecache -am  # Docker vereist (Testcontainers)
./mvnw clean test -pl services/berichtenmagazijn -am          # Docker vereist
./mvnw clean test -pl services/berichtenuitvraag -am          # Docker vereist
```

`verify` draait bovendien de kwaliteitspoorten — JaCoCo (minimaal 90% line coverage) en
detekt (`maxIssues: 0`, zonder baseline):

```bash
./mvnw clean verify -pl services/berichtenmagazijn -am
./mvnw detekt:check                                           # alleen de statische analyse
```

De OpenAPI-specs valideren tegen de NL API Design Rules:

```bash
npx @stoplight/spectral-cli lint services/berichtenmagazijn/src/main/resources/openapi/berichtenmagazijn-api.yaml \
  --ruleset https://static.developer.overheid.nl/adr/ruleset.yaml
```

### API-requests handmatig uitvoeren (Bruno)

De `bruno/`-folder bevat per service een collectie van voorbeeld-requests die je
tegen de lokale dev-mode kunt uitvoeren met [Bruno](https://www.usebruno.com/).

- `bruno/berichtenmagazijn/` — aanlever- en beheer-API
- `bruno/berichtenuitvraag/` — frontend-facade (lijst, zoek, ophalen-SSE, detail, bijlage, PATCH/DELETE)

Open de folder in Bruno, kies environment `lokaal` en run requests. De collectie
spiegelt de OpenAPI-spec: nieuwe endpoints in de spec krijgen direct een
bijbehorende `.bru`-request.

### Configuratie

De belangrijkste configuratie staat in `services/berichtenuitvraag/src/main/resources/application.properties`:

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
`main` gepusht. Elke PR draait tests met coverage-rapportage, detekt en CodeQL, en krijgt een
eigen preview-omgeving. Zie [SUPPORT.md](SUPPORT.md) voor contact en
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
