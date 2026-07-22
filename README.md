# PoC MOZa Berichtenbox

![Project Status](https://img.shields.io/badge/life_cycle-pre_alpha-red)
[![CI](https://github.com/ericwout-overheid/moza-fbs-berichtenbox/actions/workflows/ci.yml/badge.svg)](https://github.com/ericwout-overheid/moza-fbs-berichtenbox/actions/workflows/ci.yml)
![OpenSSF Scorecard](https://api.securityscorecards.dev/projects/github.com/MinBZK/moza-poc-fbs-berichtenbox/badge)

Proof of Concept Berichtenbox voor MijnOverheid Zakelijk (MOZa) binnen het Federatief Berichtenstelsel (FBS).

## Inleiding

Dit project is een Proof of Concept voor de Berichtenbox binnen het Federatief Berichtenstelsel,
beschreven op https://www.logius.nl/onze-dienstverlening/interactie/federatief-berichten-stelsel.

## Doel

Dit Open Source project is opgezet als PoC voor het ontvangen, opslaan en ophalen van berichten binnen MijnOverheid Zakelijk.
De Berichtenbox bestaat uit de volgende onderdelen:

- **Berichtensessiecache** - Ophalen en weergeven van berichten
- **Berichtenmagazijn** - Opslaan van berichten
- **Berichten Uitvraag Service** - Frontend-API voor het portaal (aggregeert sessiecache + magazijn)
- **Berichtnotificatieprofiel** - Beheer van notificatievoorkeuren

## Vereisten

- Java 21+
- Maven 3.9+ (of gebruik de meegeleverde Maven wrapper `./mvnw`)
- Docker (voor lokale services: Redis, WireMock, ClickHouse)

## Snel starten

```bash
# Start lokale services (Redis, WireMock magazijnen, ClickHouse)
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

De `compile`-fase vóór `quarkus:dev` zorgt dat de gedeelde module `libraries/fbs-common`
(via `-am`) eerst gebouwd wordt; zonder `compile` draait Maven alleen het `quarkus:dev`-goal
en faalt de resolution van `fbs-common-0.1.0-SNAPSHOT.jar` zolang die niet in de lokale
Maven-repository staat.

| Service              | API                                              | OpenAPI                                 |
|----------------------|--------------------------------------------------|-----------------------------------------|
| berichtenmagazijn    | `http://localhost:8090/api/v1/berichten`         | `http://localhost:8090/openapi.json`    |
| berichtenuitvraag    | `http://localhost:8086/api/v1/berichten`         | `http://localhost:8086/openapi.json`    |

De vroegere losse berichtensessiecache-service is opgegaan in `berichtenuitvraag`
als in-process library (`libraries/fbs-berichtensessiecache`) met Redis als
gedeelde backing store.

## Demo-stack (alles in containers)

Voor demonstraties draait de volledige keten in containers, zodat opstarten één commando
is. Bouw eerst de images met jib — opnieuw nodig na elke codewijziging:

```bash
./mvnw clean package -DskipTests \
  -pl services/berichtenmagazijn,services/berichtenuitvraag,services/demo-console -am \
  -Dquarkus.container-image.build=true \
  -Dquarkus.container-image.group=fbs-demo \
  -Dquarkus.container-image.tag=demo
```

> **CORS voor de Berichtenbox-UI** is een runtime-property (`quarkus.http.cors.enabled`),
> gezet onder `%dev` in `berichtenuitvraag` én als env-var in het demo-profiel van
> `compose.yaml`. Prod/ZAD (profiel prod) krijgt geen enabled, dus die images blijven
> CORS-loos. Geen build-flag nodig.

> **Apple Silicon / ARM:** jib bouwt standaard `linux/amd64` (de ZAD-cluster is amd64).
> Op een ARM-host draaien die images onder emulatie — voeg
> `-Dquarkus.jib.platforms=linux/arm64` toe voor native images. Deze flag hoort op de
> commandoregel en niet in de config, anders wordt ook de CI-/ZAD-build arm64.

Start daarna de stack en controleer de keten:

```bash
docker compose --profile demo up -d   # alles in containers
./demo/smoke.sh                       # rookproef: aanleveren + ophalen
```

Zónder `--profile demo` start compose alleen de infrastructuur (Redis, Postgres, WireMock,
ClickHouse). Gebruik die modus tijdens het ontwikkelen en draai de services met
`quarkus:dev` zoals hierboven — in een container kost elke codewijziging een image-build.

De poorten zijn in beide modi gelijk (8090, 8091, 8086), dus de Bruno-collectie en de
omgeving `lokaal` werken ongewijzigd. Draai niet beide modi tegelijk: dat geeft een
poortconflict.

De demo-console draait op <http://localhost:8095> — een kaal paneel om de magazijnen te
legen, de basisdataset te laden en random berichten op te voeren.

### Tests draaien

```bash
./mvnw test -pl libraries/fbs-berichtensessiecache -am
./mvnw test -pl services/berichtenmagazijn -am
./mvnw test -pl services/berichtenuitvraag -am
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
# Magazijnen waarmee de in-process sessiecache communiceert
magazijnen.instances.magazijn-a.url=http://localhost:8081
magazijnen.instances.magazijn-a.naam=Magazijn A
magazijnen.instances.magazijn-b.url=http://localhost:8082
magazijnen.instances.magazijn-b.naam=Magazijn B
```

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
