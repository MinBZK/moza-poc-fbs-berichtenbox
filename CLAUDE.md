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
- **Persistentie:** berichtenmagazijn-specifiek: PostgreSQL 18 + Hibernate ORM Panache. Tests via Quarkus Dev Services (Testcontainers); dev via `compose.yaml`. Geen H2.
- **Validatie:** Hibernate Validator (Bean Validation via gegenereerde interface-annotaties)
- **Test:** JUnit 5 + REST-assured + QuarkusTest

## Architectuurprincipes

- **OpenAPI-first:** De OpenAPI spec (`berichtensessiecache-api.yaml`) is de bron van waarheid. Interfaces worden gegenereerd; Kotlin resources implementeren deze.
- **Functionele packages:** `berichten/`, `magazijn/`, `notificatie/` — niet technisch (`controller/`, `service/`).
- **NL API Design Rules:** `/api/v1` prefix, camelCase JSON, `application/problem+json` fouten (RFC 9457), `API-Version` header, HAL `_links`. Spec valideren met Spectral-linter (zie Tooling).
- **Cache alleen succesvolle responses** (sessiecache-specifiek): error handling in de resource, niet in de service, zodat de Redis-cache geen foutresultaten opslaat.
- **ExceptionMappers in fbs-common:** `ProblemExceptionMapper` (WebApplicationException, maskeert 5xx met correlation-id), `ConstraintViolationExceptionMapper` (Bean Validation), `DomainValidationExceptionMapper` (domein-invarianten), `JsonProcessingExceptionMapper`/`MismatchedInputExceptionMapper` (Jackson, zonder originalMessage lek), `UncaughtExceptionMapper` (vangnet voor alle overige `Exception`s, 500 + correlation-id). Gedeelde response-helpers: `problemResponse(...)` en `maskedServerErrorProblem(...)` in `ProblemResponses.kt`.
- **BSN/PII-handling:** elfproef-validatie via `Bsn`/`Rsin`. BSN nooit in URL of spec — alleen via header (`X-Ontvanger: BSN:<waarde>`). BSN nooit in applicatie-logs (`.type` mag, `.waarde` niet). LDV's `dataSubjectId` mag de waarde bevatten zolang het endpoint TLS gebruikt (BIO 13.2.1, afgedwongen door `fbs-common/LdvEndpointValidator` in `%prod`/`%staging`/`%acceptatie`).
- **Centrale autorisatie:** `opslag/BerichtAutorisatie.vereisOntvanger(...)` voor alle ontvanger-checks in magazijn-services. Niet dupliceren; wordt later vervangen door AuthZEN PEP (Issue 10).

## Conventies

- **GroupId:** `nl.rijksoverheid.moz`
- **Packages:** `nl.rijksoverheid.moz.fbs.<module-naam>.*` — `fbs` reserveert een productnamespace onder de MOZ-organisatie-groupId, zowel voor services als voor gedeelde libraries.
- **Monorepo structuur:** `services/<service-naam>/` als Maven module
- **Actieve modules:** `services/berichtensessiecache`, `services/berichtenmagazijn`. De gedeelde JAX-RS filters en exception mappers staan in `libraries/fbs-common`. `services/berichtenlijst/` bestaat als directory maar is niet actief.
- **Gegenereerde code:** `target/generated-sources/openapi/` — nooit handmatig aanpassen
- **Bestandsnamen:** geen spaties in bestands- of mapnamen; gebruik `kebab-case` of `snake_case` (documentatie/markdown/configuratie) of `PascalCase`/`camelCase` (Kotlin/Java sources) — zodat shellscripts, build-tools en CI-pipelines zonder quoting werken.
- **Bruno-collectie:** per service met een OpenAPI-spec hoort een Bruno-collectie onder `bruno/<service-naam>/` (met `bruno.json`, `environments/lokaal.bru` en requests per functioneel pad). Nieuwe endpoints in de OpenAPI-spec krijgen direct een bijbehorende `.bru`-request; zo blijft de collectie een levend exempel van de spec.
- **Tests:** Mock externe clients via `@Mock @ApplicationScoped` CDI beans in test-package

## Database & migraties

- **Surrogate PK per tabel:** elke tabel heeft `id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY`. Business-keys (UUID's, OIN, etc.) zijn unique-constrained kolommen, nooit PK.
- **FK's op surrogate PK:** child-tabellen verwijzen via `<parent>_db_id BIGINT` naar `parent.id`, niet via de business-key. JPA-relatie: `@ManyToOne(LAZY) @JoinColumn(name = "<parent>_db_id")`.
- **FK's zonder `ON DELETE CASCADE` tenzij opzettelijk:** soft-delete is de default voor `berichten`; CASCADE ondergraaft de soft-delete-semantiek en is een voetkanon als ooit hard-delete wordt toegevoegd. Default = RESTRICT.
- **Flyway migraties zijn immutable na toepassing:** wijzig een bestaande `V*.sql` nooit; voeg een nieuwe `V(N+1)__...sql` toe. Lokale rollback-scripts staan onder `src/main/resources/db/rollback/V*.sql` (handmatig draaien + `flyway_schema_history`-rij opruimen).
- **Test-cleanup volgorde** met RESTRICT-FK's: child-tabellen eerst (`statusRepository.deleteAll()` → `bijlageRepository.deleteAll()` → `berichtRepository.deleteAll()`).
- **`@Lob byte[]` NIET op PostgreSQL:** mapt naar `oid` (Large Object) i.p.v. `bytea`. Hibernate 6 default mapping (`byte[]` zonder annotatie) → VARBINARY → BYTEA is correct.

## Quarkus configuratie

- **Globale HTTP-headers vs JAX-RS filters:** `ContainerResponseFilter` dekt alleen JAX-RS-paden, niet `/openapi.json`, `/q/health`, `/q/metrics`, dev-UI. Security-headers daarom ÓÓK globaal via `quarkus.http.header."X-Frame-Options".value=DENY` etc. — `fbs-common/SecurityHeadersFilter` blijft als JAX-RS-defense-in-depth.
- **`/openapi.json` expliciet configureren:** ADR-vereiste; default Quarkus-path is `/q/openapi`. Beide services hebben `quarkus.smallrye-openapi.path=/openapi.json`.
- **`quarkus.jackson.serialization-inclusion=non_null`:** optionele HAL-velden (bv. `_links.next` op laatste pagina) moeten afwezig zijn i.p.v. `null`, anders faalt `swagger-request-validator` op de Problem-schema-check.
- **Dynamic Content-Type pattern:** voor endpoints met variabel MIME-type (bv. bijlage-download) — OpenAPI `content: '*/*':` + een `ContainerResponseFilter` die `Content-Type` overschrijft uit een unieke request-property. **NameBinding (`@NameBinding`) werkt niet** op override-methodes vanuit gegenereerde JAX-RS interfaces in Quarkus REST; property-driven gating is de werkbare guard (zie `BijlageContentTypeFilter`).

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
| `bruno/<service-naam>/`                | Bruno-collectie per service (handmatige / exploratieve API-requests tegen de lokale dev-mode) |
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
- **Coverage:** JaCoCo minimum 90% line coverage (`quarkus-jacoco`), gegenereerde code (`api.**`) uitgesloten. **Let op:** `quarkus-jacoco` telt alleen `@QuarkusTest`-coverage in `jacoco-quarkus.exec` — pure unit-tests (JUnit + MockK zonder Quarkus) dragen NIET bij aan de drempel. Integratietests zijn dus vereist voor code dat alleen via HTTP/CDI bereikbaar is. JaCoCo BUNDLE-exclude pattern MOET `**` gebruiken voor subpackages (`api.**`, niet `api.*` — anders matchen alleen direct kinderen en tellen DTO's in `api.model.*` onbedoeld mee).

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

## Tooling

- **Docker-afhankelijke tests via `mcp__maven-host`:** Testcontainers en Quarkus Dev Services hebben Docker nodig; de Claude-sandbox heeft dat niet. Roep `mcp__maven-host__run_maven` aan met `goals="test"`, `extra_args="-pl services/<naam> -am"` en `env={"TESTCONTAINERS_RYUK_DISABLED": "true"}` (Rancher Desktop / Docker-socket bind-mount issue). Lokale `./mvnw test-compile` kan zonder Docker — handig voor snelle compilatie-check.
- **ADR-spec-linting:** `npx @stoplight/spectral-cli lint <spec.yaml> --ruleset https://static.developer.overheid.nl/adr/ruleset.yaml` valideert tegen Forum-Standaardisatie API Design Rules.
- **CI-status volgen:** `gh pr checks <PR#>` en `gh run watch <run-id> --exit-status`. Bij falen: `gh run view <id> --log-failed`.
