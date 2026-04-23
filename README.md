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
# Terminal 1 — berichtensessiecache (poort 8080)
./mvnw compile quarkus:dev -pl services/berichtensessiecache -am

# Terminal 2 — berichtenmagazijn (poort 8090)
./mvnw compile quarkus:dev -pl services/berichtenmagazijn -am
```

De `compile`-fase vóór `quarkus:dev` zorgt dat de gedeelde module `libraries/fbs-common`
(via `-am`) eerst gebouwd wordt; zonder `compile` draait Maven alleen het `quarkus:dev`-goal
en faalt de resolution van `fbs-common-0.1.0-SNAPSHOT.jar` zolang die niet in de lokale
Maven-repository staat.

| Service              | API                                              | OpenAPI                                 |
|----------------------|--------------------------------------------------|-----------------------------------------|
| berichtensessiecache | `http://localhost:8080/api/v1/berichten`         | `http://localhost:8080/openapi.json`    |
| berichtenmagazijn    | `http://localhost:8090/api/v1/berichten`         | `http://localhost:8090/openapi.json`    |

### Tests draaien

```bash
./mvnw test -pl services/berichtensessiecache -am
./mvnw test -pl services/berichtenmagazijn -am
```

### Configuratie

De belangrijkste configuratie staat in `services/berichtensessiecache/src/main/resources/application.properties`:

```properties
# Magazijnen waarmee de berichtensessiecache communiceert
magazijnen.instances.magazijn-a.url=http://localhost:8081
magazijnen.instances.magazijn-a.naam=Magazijn A
magazijnen.instances.magazijn-b.url=http://localhost:8082
magazijnen.instances.magazijn-b.naam=Magazijn B
```

## Licentie

Dit project is gelicenseerd onder de [EUPL-1.2](LICENSE).

## Ondersteuning

Zie [SUPPORT.md](SUPPORT.md) voor informatie over hoe en waar je hulp kunt krijgen.

## Governance

Zie [GOVERNANCE.md](GOVERNANCE.md) voor informatie over de governance-structuur van dit project.
