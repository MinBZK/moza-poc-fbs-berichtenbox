# PoC MOZa Berichtenbox

![Project Status](https://img.shields.io/badge/life_cycle-pre_alpha-red)

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

# Compileer en start de berichtensessiecache service in dev mode
./mvnw quarkus:dev -pl services/berichtensessiecache
```

De API is beschikbaar op `http://localhost:8080/api/v1/berichten`.
De OpenAPI specificatie staat op `http://localhost:8080/openapi.json`.

### Tests draaien

```bash
./mvnw test -pl services/berichtensessiecache
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
