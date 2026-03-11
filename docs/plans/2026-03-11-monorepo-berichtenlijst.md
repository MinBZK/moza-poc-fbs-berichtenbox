# Plan: Monorepo opzet FBS met Berichtenlijst

**Status:** Uitgevoerd
**Datum:** 2026-03-11

## Context

Het project `moza-poc-fbs-berichtenbox` bevat momenteel alleen architectuurdocumentatie (C4 model in Structurizr DSL). De volgende stap is het opzetten van een monorepo-structuur voor de implementatie van het Federatief Berichtenstelsel (FBS), te beginnen met de **Berichtenlijst API** - de service die berichtrecords aggregeert uit alle aangesloten decentrale magazijnen.

## Monorepo structuur

```
moza-poc-fbs-berichtenbox/
├── docs/architecture/                  # (bestaand) C4 model
├── pom.xml                             # Parent POM (monorepo root)
├── services/
│   └── berichtenlijst/
│       ├── pom.xml                     # Module POM
│       └── src/
│           ├── main/
│           │   ├── kotlin/nl/rijksoverheid/moz/berichtenlijst/
│           │   │   ├── berichten/              # Functioneel domein: berichten ophalen/aggregeren
│           │   │   │   ├── BerichtenlijstResource.kt   # Implementeert gegenereerde API interface
│           │   │   │   ├── BerichtenlijstService.kt
│           │   │   │   ├── Bericht.kt                  # Domain model
│           │   │   │   ├── ApiVersionFilter.kt         # API-Version response header
│           │   │   │   ├── ProblemExceptionMapper.kt    # Problem JSON (RFC 9457)
│           │   │   │   └── ConstraintViolationExceptionMapper.kt
│           │   │   ├── magazijn/               # Functioneel domein: communicatie met magazijnen
│           │   │   │   ├── MagazijnClient.kt
│           │   │   │   └── MagazijnBerichtenResponse.kt
│           │   │   └── notificatie/            # Functioneel domein: event forwarding
│           │   │       └── EventForwarder.kt
│           │   └── resources/
│           │       ├── application.properties
│           │       └── openapi/
│           │           └── berichtenlijst-api.yaml     # OpenAPI definitie (bron)
│           └── test/
│               └── kotlin/nl/rijksoverheid/moz/berichtenlijst/
│                   └── berichten/
│                       ├── BerichtenlijstResourceTest.kt
│                       └── MockMagazijnClient.kt
```

## Stap-voor-stap implementatie

### Stap 1: Maven monorepo basis

- **`pom.xml`** (root) - Parent POM met:
  - `<packaging>pom</packaging>`
  - `<modules>` met `services/berichtenlijst`
  - Quarkus BOM in `<dependencyManagement>`
  - Kotlin compiler plugin config in `<pluginManagement>`
  - Gemeenschappelijke properties (Java 21, Kotlin versie, Quarkus versie)

### Stap 2: Berichtenlijst Quarkus module

- **`services/berichtenlijst/pom.xml`**:
  - Parent verwijzing naar root POM
  - Quarkus Maven plugin
  - Kotlin Maven plugin (compileren voor JVM 21)
  - Dependencies:
    - `quarkus-rest-jackson` (RESTEasy Reactive + Jackson)
    - `quarkus-kotlin` (Kotlin ondersteuning)
    - `quarkus-smallrye-openapi` (OpenAPI/Swagger UI)
    - `quarkus-rest-client-jackson` (REST client voor magazijnen)
    - `quarkus-cache` (Caffeine caching)
    - `quarkus-hibernate-validator` (Bean Validation)
  - `openapi-generator-maven-plugin` voor server stub generatie:
    - Input: `src/main/resources/openapi/berichtenlijst-api.yaml`
    - Generator: `jaxrs-spec`
    - `interfaceOnly=true` - alleen interfaces genereren
    - `generateSupportingFiles=false` - geen RestApplication/RestResourceRoot
    - Output naar `target/generated-sources/openapi`

### Stap 3: OpenAPI definitie conform NL API Design Rules

Het bestand **`berichtenlijst-api.yaml`** volgt de [API Design Rules](https://gitdocumentatie.logius.nl/publicatie/api/adr/) van de overheid:

- **Versionering**: `/api/v1` prefix
- **JSON**: `application/json` als standaard media type
- **Foutafhandeling**: Problem JSON (`application/problem+json`, RFC 9457)
- **Paginering**: Offset-based met `page` en `pageSize` query parameters
- **HTTP status codes**: 200, 400, 401, 403, 404, 500 correct toepassen
- **Naamgeving**: camelCase voor JSON properties, kebab-case voor URL paden
- **Headers**: `API-Version` response header
- **HAL links**: `_links` in responses voor discoverability

Endpoints (afgeleid uit C4 model):
- `GET /api/v1/berichten` - Geaggregeerde berichtenlijst ophalen (met paginering en filtering)
- `GET /api/v1/berichten/{berichtId}` - Enkel berichtrecord ophalen
- `GET /api/v1/berichten/zoeken` - Zoeken in berichten

### Stap 4: Functionele packagestructuur

In plaats van een technische structuur (`controller/`, `service/`, `repository/`) gebruiken we een **functionele** structuur:

```
nl.rijksoverheid.moz.berichtenlijst
├── berichten/          # Kerndomein: berichtenlijst en zoeken
├── magazijn/           # Integratie met decentrale magazijnen
└── notificatie/        # Event forwarding naar Notificatie Service
```

Elke package bevat alle lagen (resource, service, model) voor dat functionele domein.

### Stap 5: Applicatieconfiguratie

- **`application.properties`**: Quarkus config, OpenAPI UI, caching (60s TTL conform C4 model), REST client config voor magazijnen

### Stap 6: Basis-implementatie

- **`BerichtenlijstResource.kt`**: Implementeert de gegenereerde OpenAPI interface, delegeert naar service, error handling
- **`BerichtenlijstService.kt`**: Aggregeert records van magazijnen, cacht resultaten (Caffeine, 60s TTL)
- **`MagazijnClient.kt`**: REST client interface (Quarkus REST Client)
- **`MagazijnBerichtenResponse.kt`**: Response wrapper met paginering-metadata
- **`Bericht.kt`**: Domain model voor berichtrecords
- **`ApiVersionFilter.kt`**: ContainerResponseFilter voor API-Version header
- **`ProblemExceptionMapper.kt`**: WebApplicationException → Problem JSON
- **`ConstraintViolationExceptionMapper.kt`**: Validatiefouten → Problem JSON
- **`EventForwarder.kt`**: Stuurt bericht-events door naar Notificatie Service (CloudEvents)
- **`BerichtenlijstResourceTest.kt`**: REST-assured tests
- **`MockMagazijnClient.kt`**: Mock REST client voor tests

## Ontwerpkeuzes

- **Maven**: Standaard buildtool, parent POM voor gedeelde config, modules voor services
- **OpenAPI-first**: API-definitie is de bron van waarheid; controllers worden gegenereerd als interfaces die de Kotlin resource implementeert
- **Functionele packages**: Bevordert cohesie en maakt het makkelijk om later services af te splitsen
- **Caffeine cache**: Lichtgewicht in-memory caching conform C4 model (60s TTL), cachet alleen succesvolle responses
- **Problem JSON (RFC 9457)**: Consistente foutresponses via ExceptionMappers
- **Maven wrapper**: `mvnw` voor reproduceerbare builds zonder lokale Maven-installatie

## Verificatie

1. `./mvnw compile -pl services/berichtenlijst` - Compilatie slaagt
2. `./mvnw test -pl services/berichtenlijst` - 6 tests slagen
3. `curl http://localhost:8080/q/openapi` - OpenAPI spec beschikbaar
4. `curl http://localhost:8080/api/v1/berichten` - Endpoint retourneert response met API-Version header
