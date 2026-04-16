# CLAUDE.md - Projectcontext voor AI-assistentie

## Project

FBS Berichtenbox - Proof of Concept voor het Federatief Berichtenstelsel (FBS).
Monorepo met Maven, Quarkus en Kotlin. Architectuurdocumentatie in Structurizr DSL (C4 model).

## Taal

Communicatie in het Nederlands. Code en technische termen in het Engels waar gangbaar.

## Technische stack

- **Build:** Maven monorepo (parent POM + modules), Maven wrapper (`./mvnw`)
- **Runtime:** Quarkus 3.x, Java 21
- **Taal:** Kotlin (JVM 21, all-open plugin voor CDI/JAX-RS)
- **API:** OpenAPI-first (`jaxrs-spec` generator, `interfaceOnly=true`), gegenereerde Java interfaces die Kotlin resources implementeren
- **REST:** RESTEasy Reactive + Jackson
- **Caching:** sessiecache-specifiek: Redis (60s sliding TTL, configureerbaar via `berichtensessiecache.ttl`) via `BerichtenCache` interface — elke succesvolle read verlengt TTL op sessie-keys en geraakte berichthashes. Berichtenmagazijn heeft geen cache.
- **Persistentie:** berichtenmagazijn-specifiek: H2 embedded + Hibernate ORM Panache.
- **Validatie:** Hibernate Validator (Bean Validation via gegenereerde interface-annotaties)
- **Test:** JUnit 5 + REST-assured + QuarkusTest

## Architectuurprincipes

- **OpenAPI-first:** De OpenAPI spec (`berichtensessiecache-api.yaml`) is de bron van waarheid. Interfaces worden gegenereerd; Kotlin resources implementeren deze.
- **Functionele packages:** `berichten/`, `magazijn/`, `notificatie/` — niet technisch (`controller/`, `service/`).
- **NL API Design Rules:** `/api/v1` prefix, camelCase JSON, `application/problem+json` fouten (RFC 9457), `API-Version` header, HAL `_links`.
- **Cache alleen succesvolle responses** (sessiecache-specifiek): error handling in de resource, niet in de service, zodat de Redis-cache geen foutresultaten opslaat.
- **ExceptionMappers in fbs-common:** `ProblemExceptionMapper` (WebApplicationException, maskeert 5xx met correlation-id), `ConstraintViolationExceptionMapper` (Bean Validation), `DomainValidationExceptionMapper` (domein-invarianten), `IllegalArgumentExceptionMapper` (vangnet, behandelt generieke IAE als 500), `JsonProcessingExceptionMapper`/`MismatchedInputExceptionMapper` (Jackson, zonder originalMessage lek).

## Conventies

- **GroupId:** `nl.rijksoverheid.moz`
- **Packages:** `nl.rijksoverheid.moz.<service-naam>.*`
- **Monorepo structuur:** `services/<service-naam>/` als Maven module
- **Actieve modules:** `services/berichtensessiecache`, `services/berichtenmagazijn`. De gedeelde JAX-RS filters en exception mappers staan in `libraries/fbs-common`. `services/berichtenlijst/` bestaat als directory maar is niet actief.
- **Gegenereerde code:** `target/generated-sources/openapi/` — nooit handmatig aanpassen
- **Tests:** Mock externe clients via `@Mock @ApplicationScoped` CDI beans in test-package

## Build & test commando's

```bash
docker compose up -d                                       # Start Redis, WireMock, ClickHouse
./mvnw compile -pl services/berichtensessiecache           # Compileren
./mvnw test -pl services/berichtensessiecache              # Tests draaien
./mvnw quarkus:dev -pl services/berichtensessiecache       # Dev mode
./mvnw compile -pl services/berichtenmagazijn -am                # Compileren berichtenmagazijn
./mvnw test -pl services/berichtenmagazijn -am                   # Tests berichtenmagazijn
./mvnw quarkus:dev -pl services/berichtenmagazijn                # Dev mode
```

## Belangrijke bestanden

| Pad                                    | Beschrijving                                                    |
|----------------------------------------|-----------------------------------------------------------------|
| `pom.xml`                              | Parent POM (Quarkus BOM, Kotlin plugin config)                  |
| `services/berichtensessiecache/pom.xml`| Module POM (OpenAPI generator, dependencies)                    |
| `services/berichtensessiecache/src/main/resources/openapi/berichtensessiecache-api.yaml` | OpenAPI spec (bron van waarheid) |
| `libraries/fbs-common/`                | Gedeelde JAX-RS filters en exception mappers                    |
| `services/berichtenmagazijn/pom.xml`   | Module POM (OpenAPI generator, H2, JPA, Fault Tolerance)        |
| `services/berichtenmagazijn/src/main/resources/openapi/berichtenmagazijn-api.yaml` | OpenAPI spec Aanlever API |
| `docs/architecture/`                   | C4 model (Structurizr DSL)                                      |
| `compose.yaml`                         | Lokale dev-omgeving (Redis, WireMock, ClickHouse)               |
| `.github/workflows/`                   | CI: CodeQL security scanning, Scorecard, Architecture validatie |
| `.github/CODEOWNERS`                   | Code ownership (`@MinBZK/mijnoverheid-zakelijk`)                |

## Omgevingsvariabelen

| Variabele              | Default | Beschrijving                                        |
|------------------------|---------|-----------------------------------------------------|
| `CLICKHOUSE_USERNAME`  | `ldv`   | ClickHouse gebruikersnaam (Logboek Dataverwerkingen) |
| `CLICKHOUSE_PASSWORD`  | `ldv`   | ClickHouse wachtwoord                                |

## Plannen

Implementatieplannen worden opgeslagen in `docs/plans/` met oplopend nummer:
- Formaat: `YYYY-MM-DD-korte-beschrijving.md` (bijv. `2026-03-11-monorepo-berichtensessiecache.md`)
- Bevat: context, structuur, stappen, ontwerpkeuzes, verificatie
- Voeg `**Status:**` toe bovenaan (Concept / Uitgevoerd / Verworpen)
- Sla elk plan op bij het afronden, zodat beslissingen traceerbaar blijven

## Git-werkwijze

- **Nooit direct pushen naar `main`.** Alle wijzigingen gaan via een feature branch en een Pull Request.
- Branch naming: `feature/`, `fix/`, `chore/` prefix.
- Bij aanmaken van een pull request **nooit** een reviewer toevoegen.

## Teststrategie

Bij elke codewijziging beoordelen of er tests toegevoegd of aangepast moeten worden:

- **Happy én unhappy paths:** Niet alleen het successcenario, maar ook foutgevallen, edge cases en validatiefouten.
- **Unit tests** zijn de basis (JUnit 5 + REST-assured).
- **Integratietests** (`@QuarkusTest`) wanneer de wijziging meerdere componenten raakt of externe afhankelijkheden (Redis, REST-clients) betreft.
- **Fuzzing / property-based tests** overwegen bij input-parsing, validatielogica of security-gevoelige code.
- Als integratietests of fuzzing een grote toevoeging vormen, dit eerst voorleggen aan de gebruiker voordat je begint.
- **Coverage:** JaCoCo minimum 90% line coverage (`quarkus-jacoco`), gegenereerde code (`api.*`) uitgesloten

## Testlagen

### 1. Spec-driven aan de randen
- OpenAPI-spec is bron van waarheid voor inkomende API en uitgaande clients
- Server-stubs worden gegenereerd uit de spec (`jaxrs-spec`, `interfaceOnly=true`); compilatie faalt bij afwijking
- Responses valideren met `swagger-request-validator-restassured` (`OpenApiContractTest`) — zowel happy paths als foutresponses (400/404/409/500) tegen het Problem-schema
- Externe bronnen (magazijn-clients): WireMock-contracttests voor succes, HTTP-fouten, timeouts en malformed responses

### 2. Unit tests voor deterministische logica
- JUnit 5 + MockK (Kotlin)
- Pure logica: validatie (data class init-blocks), mapping/transformaties, cache-key-opbouw (SHA-256), service-orkestratie
- Geen database of HTTP; buren mocken via MockK
- Fuzzing/property-based tests bij input-parsing en validatielogica

### 3. Component-integratietests tegen echte infrastructuur
- Infrastructuur via Testcontainers (Quarkus Dev Services)
- Test gedrag dat mocks niet vangen: voor Redis bijvoorbeeld serialisatie roundtrip, RediSearch full-text/TAG queries, TTL-expiratie, atomaire lock (SETNX), index drop+recreate
- WireMock voor magazijn-clients: HTTP 500, connection timeout, malformed JSON, lege responses, partial failure
- TestProfiles schakelen per testlaag tussen echte en mock-implementaties (`MockedDependenciesProfile`, `RealRedisTestProfile`, `WireMockTestProfile`)

### 4. End-to-end integratietests
- Volledige keten: HTTP request → service → echte Redis + WireMock magazijnen → SSE response
- Degradatiegedrag: partial failure (1 magazijn OK, 1 FOUT), ophalen-bezig (409), ophalen-mislukt (500)

## Review-aanpak

Bij code reviews classificeren we bevindingen op ernst (Hoog/Medium/Laag) met een samenvattingstabel. Hoge punten worden direct aangepakt, medium in overleg, laag later.
