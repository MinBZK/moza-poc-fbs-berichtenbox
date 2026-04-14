**Status:** Concept

# Berichtenmagazijn — nieuwe service module met Aanlever API

## Context

Issue: [MinBZK/MijnOverheidZakelijk#410](https://github.com/MinBZK/MijnOverheidZakelijk/issues/410)
Onderdeel van: `docs/plans/2026-03-30-c4-implementatie-issues.md` (Issue 2)
Basisbranch: `issue-1/sessiecache-c4-alignment` (PR #26, refactor sessiecache naar C4)

Dit plan introduceert een nieuwe Quarkus service module `services/berichtenmagazijn`. Dit wordt de enkele deployable unit voor het hele Berichtenmagazijn (C4 systeem `decentraalMagazijn`). Alle magazijn-containers (Aanlever API, Ophaal- & Beheer API, Validatie, Publicatie Stream, Autorisatie, Dataopslag) worden packages/CDI-beans binnen deze module.

In deze iteratie wordt alleen de **Aanlever API** functioneel gemaakt (C4-container `magazijnAanleverApi`). De packages voor de andere containers worden voorbereid maar blijven leeg. Daarnaast wordt een gedeelde Maven module `libraries/fbs-common` geïntroduceerd voor herbruikbare JAX-RS filters en exception mappers.

## Scope

**In scope**
- Nieuwe Maven module `libraries/fbs-common` met gedeelde filters/mappers
- Nieuwe Maven module `services/berichtenmagazijn` geregistreerd in de parent POM
- OpenAPI spec voor Aanlever API (`POST /api/v1/berichten`)
- Kotlin resource + service + H2-gebaseerde opslag
- `@CircuitBreaker` op schrijfoperaties (MicroProfile Fault Tolerance)
- LDV logging (`@Logboek`), Problem JSON, security headers, API-Version header
- Unit tests (MockK), component-integratietests (`@QuarkusTest` + H2), contracttests (`swagger-request-validator-restassured`)
- Package-structuur voorbereid op `ophaal/`, `validatie/`, `publicatie/`, `autorisatie/` (leeg)
- Kleine refactor van `berichtensessiecache`: gedeelde filters verhuizen naar `fbs-common`

**Uit scope** (andere issues)
- Implementatie van Ophaal- & Beheer API
- Validatie Service, Publicatie Stream, Autorisatie Service
- Echte database (PostgreSQL) — H2 embedded is PoC-opzet
- FSC/mTLS integratie
- Autorisatie-integratie (AuthZEN)

## Architectuur

### Module-structuur

```
moza-poc-fbs-berichtenbox/
├── libraries/
│   └── fbs-common/                             ← NIEUW: gedeelde filters/mappers
├── services/
│   ├── berichtensessiecache/                   ← bestaand (kleine refactor)
│   └── berichtenmagazijn/                      ← NIEUW
└── pom.xml                                     ← twee nieuwe modules registreren
```

### `libraries/fbs-common`

Gedeelde Kotlin-module zonder Quarkus-applicatielaag (pure JAR). Bevat:

- `SecurityHeadersFilter` — Strict-Transport-Security, X-Frame-Options, X-Content-Type-Options, Content-Security-Policy
- `CacheControlFilter` — `Cache-Control: no-store` (apart zodat individuele services/endpoints het later kunnen overschrijven)
- `ProblemExceptionMapper` — `WebApplicationException` → RFC 9457 Problem JSON
- `ConstraintViolationExceptionMapper` — Bean Validation fouten → Problem JSON
- `CreatedStatusFilter` — HTTP 200 → 201 voor POST-requests (compenseert jaxrs-spec generator)
- `LogboekContextDefaultFilter` — safe defaults op `LogboekContext` vóór Bean Validation

Package: `nl.rijksoverheid.moz.fbs.common`

**Niet** in `fbs-common`: `ApiVersionFilter` — dit is per-service omdat elke API zijn eigen versienummer heeft.

### `services/berichtenmagazijn`

**Package-structuur:**
```
nl.rijksoverheid.moz.berichtenmagazijn/
├── ApiVersionFilter.kt                 ← per-service: API-Version header
├── aanlever/                           ← Aanlever API (deze iteratie)
│   ├── AanleverResource.kt             ← POST /api/v1/berichten
│   └── BerichtOpslagService.kt         ← @CircuitBreaker
├── opslag/                             ← Dataopslag
│   ├── BerichtEntity.kt                ← JPA entity
│   └── BerichtRepository.kt            ← Panache repository
├── ophaal/                             ← voorbereid, leeg
├── validatie/                          ← voorbereid, leeg
├── publicatie/                         ← voorbereid, leeg
└── autorisatie/                        ← voorbereid, leeg
```

### Component-flow (Aanlever)

```
POST /api/v1/berichten
  → AanleverResource (gegenereerde interface implementatie)
    → BerichtOpslagService.opslaanBericht(...)   [@CircuitBreaker]
      → BerichtRepository.persist(...)           [Panache/JPA → H2]
    ← domein-Bericht
  ← BerichtResponse (HTTP 201 Created, _links.self)
```

Conform het C4-model:
- `AanleverResource` = `magazijnAanleverResource`
- `BerichtOpslagService` = `magazijnOpslagService` + `magazijnCircuitBreaker` (combined; de `@CircuitBreaker`-annotatie materialiseert de circuit breaker als proxy rond de service-methode)

## OpenAPI spec

Pad: `services/berichtenmagazijn/src/main/resources/openapi/berichtenmagazijn-api.yaml`

**Endpoints (deze iteratie):**

| Methode | Pad | Beschrijving | Statuscodes |
|---|---|---|---|
| POST | `/api/v1/berichten` | Lever een bericht aan bij het magazijn | 201, 400, 401, 403, 422, 500, 503 |

**Conventies (NL API Design Rules):**
- Prefix `/api/v1`
- camelCase JSON
- Fouten als `application/problem+json` (RFC 9457)
- `API-Version` header op alle responses
- HAL-stijl `_links` (`self`) op resource responses; `self` fungeert als locatie-referentie (geen aparte `Location`-header)
- 201 Created voor POST (afgedwongen door `fbs-common/CreatedStatusFilter`)

**Request (`AanleverBerichtRequest`):**
- `afzender` (string, required, minLength=1) — OIN van afzendende organisatie
- `ontvanger` (string, required, minLength=1) — BSN of KVK-nummer
- `onderwerp` (string, required, minLength=1, maxLength=255)
- `inhoud` (string, required, minLength=1) — tekstinhoud (PoC)

**Response (`BerichtResponse`):**
- `berichtId` (uuid)
- `afzender`, `ontvanger`, `onderwerp`, `tijdstip` (ISO-8601 Instant)
- `_links.self` (href naar `/api/v1/berichten/{berichtId}` — alvast voorbereid op de Ophaal API)

**Gedeelde componenten**: `Problem`, `Link`, `API-Version` header worden binnen deze spec opnieuw gedefinieerd (geen cross-module `$ref` vanwege beperkingen in de OpenAPI generator).

**Validatie:**
- `spectral lint` met ADR ruleset (`https://static.developer.overheid.nl/adr/ruleset.yaml`) — zonder fouten
- Contracttest: `swagger-request-validator-restassured` in integratietests

## Dataopslag

**Keuze:** H2 embedded database + JPA via Quarkus Hibernate ORM Panache.

**Rationale:** H2 draait extern (t.o.v. de applicatielaag), biedt SQL-semantiek en lijkt meer op de toekomstige productie-opstelling dan een `ConcurrentHashMap`. Geen extra containers nodig (embedded), dus geen wijzigingen in `compose.yaml`.

**Entity:**
```kotlin
@Entity
@Table(name = "berichten")
class BerichtEntity {
    @Id
    lateinit var berichtId: UUID

    @Column(nullable = false)
    lateinit var afzender: String

    @Column(nullable = false)
    lateinit var ontvanger: String

    @Column(nullable = false)
    lateinit var onderwerp: String

    @Column(nullable = false, columnDefinition = "CLOB")
    lateinit var inhoud: String

    @Column(nullable = false)
    lateinit var tijdstip: Instant
}
```

**Repository:** `PanacheRepositoryBase<BerichtEntity, UUID>` (interface). Voor de Aanlever API zijn geen custom queries nodig — `persist()` volstaat.

**Configuratie (`application.properties`):**
```properties
quarkus.datasource.db-kind=h2
quarkus.datasource.jdbc.url=jdbc:h2:mem:berichtenmagazijn;DB_CLOSE_DELAY=-1
quarkus.hibernate-orm.database.generation=drop-and-create
quarkus.hibernate-orm.log.sql=false
```

## Circuit breaker

Op `BerichtOpslagService.opslaanBericht(...)`:

```kotlin
@CircuitBreaker(
    requestVolumeThreshold = 20,
    failureRatio = 0.5,
    delay = 5000,
    successThreshold = 2,
)
fun opslaanBericht(request: AanleverBerichtRequest): Bericht { ... }
```

**Gedrag:** Als >50% van 20 opeenvolgende aanvragen faalt, opent de circuit voor 5 seconden. Fouten bubbelen op als `CircuitBreakerOpenException` → `503 Service Unavailable` (via `ProblemExceptionMapper`).

**Dependency:** `io.quarkus:quarkus-smallrye-fault-tolerance`.

## Security & logging

- **Security headers** via `fbs-common/SecurityHeadersFilter` (HSTS, X-Frame-Options, X-Content-Type-Options, CSP)
- **Cache-Control** via `fbs-common/CacheControlFilter` (`no-store`)
- **API-Version** via eigen `ApiVersionFilter` (`0.1.0` initieel)
- **LDV logging** via `@Logboek` op `AanleverResource`-endpoints (verwerkingsactiviteit: `aanleveren-bericht`)
- **LogboekContext defaults** via `fbs-common/LogboekContextDefaultFilter`
- **Problem JSON** via `fbs-common/ProblemExceptionMapper` + `ConstraintViolationExceptionMapper`
- **201 Created** via `fbs-common/CreatedStatusFilter`

## Refactor berichtensessiecache

Kleine aanpassing op basisbranch (PR #26):

- Dependency op `libraries/fbs-common` toevoegen in `services/berichtensessiecache/pom.xml`
- `SecurityHeadersFilter`, `CacheControlFilter`, `ProblemExceptionMapper`, `ConstraintViolationExceptionMapper`, `CreatedStatusFilter`, `LogboekContextDefaultFilter` verhuizen naar `fbs-common` (identieke functionaliteit — tests blijven slagen)
- `ApiVersionFilter` in sessiecache opschonen: alleen nog de `API-Version` header, security headers eruit

## Testing

Conform de 4-lagen teststrategie uit `CLAUDE.md`:

### Laag 1 — Spec-driven aan de randen
- `OpenApiContractTest`: valideert dat responses (200/201/400/422/500/503) voldoen aan de OpenAPI spec en aan het Problem-schema via `swagger-request-validator-restassured`
- Gegenereerde interface dwingt compile-time alignment af

### Laag 2 — Unit tests (deterministische logica)
- `BerichtOpslagServiceTest` (JUnit 5 + MockK): mock `BerichtRepository`, test mapping request → entity → response, test dat `opslaanBericht` het bericht correct persist
- `BerichtEntityMappingTest`: request → entity, entity → response
- Pure logica zonder database of HTTP

### Laag 3 — Component-integratietests
- `AanleverResourceIntegrationTest` (`@QuarkusTest` + H2 embedded): POST met geldige/ongeldige payloads, controleer statuscodes, Problem JSON, `_links.self`, `API-Version` header, security headers
- Testcoverage happy + unhappy paths: ontbrekende verplichte velden (400), te lange onderwerp (422), lege inhoud (400/422), interne fout simulatie
- **Geen Testcontainers nodig** voor H2 (embedded). Indien later PostgreSQL: Testcontainers via Quarkus Dev Services

### Laag 4 — End-to-end (optioneel in deze iteratie)
- Volledige keten HTTP → resource → service → H2. In grote lijnen al gedekt door laag 3 vanwege de eenvoud van de Aanlever API.

### Coverage
- JaCoCo met minimum 90% line coverage (zelfde drempel als sessiecache in PR #26)
- Gegenereerde code (`nl/rijksoverheid/moz/berichtenmagazijn/api/**`) uitgesloten

### Fuzzing
- Niet in deze iteratie. De input-oppervlakte (vier string-velden) is klein en wordt door Bean Validation goed afgedekt. Fuzzing overwegen als er binaire content (bijlagen) bij komt.

## Dependencies (POM)

`services/berichtenmagazijn/pom.xml` (belangrijkste):

```xml
<dependencies>
    <!-- Gedeelde filters/mappers -->
    <dependency>
        <groupId>nl.rijksoverheid.moz</groupId>
        <artifactId>fbs-common</artifactId>
        <version>${project.version}</version>
    </dependency>

    <!-- Quarkus essentials -->
    <dependency><groupId>io.quarkus</groupId><artifactId>quarkus-rest-jackson</artifactId></dependency>
    <dependency><groupId>io.quarkus</groupId><artifactId>quarkus-kotlin</artifactId></dependency>
    <dependency><groupId>io.quarkus</groupId><artifactId>quarkus-smallrye-openapi</artifactId></dependency>
    <dependency><groupId>io.quarkus</groupId><artifactId>quarkus-hibernate-validator</artifactId></dependency>
    <dependency><groupId>io.quarkus</groupId><artifactId>quarkus-smallrye-fault-tolerance</artifactId></dependency>

    <!-- Opslag -->
    <dependency><groupId>io.quarkus</groupId><artifactId>quarkus-hibernate-orm-panache-kotlin</artifactId></dependency>
    <dependency><groupId>io.quarkus</groupId><artifactId>quarkus-jdbc-h2</artifactId></dependency>

    <!-- LDV -->
    <dependency>
        <groupId>nl.mijnoverheidzakelijk.ldv</groupId>
        <artifactId>logboekdataverwerking-wrapper</artifactId>
    </dependency>

    <!-- Test -->
    <dependency><groupId>io.quarkus</groupId><artifactId>quarkus-junit5</artifactId><scope>test</scope></dependency>
    <dependency><groupId>io.rest-assured</groupId><artifactId>rest-assured</artifactId><scope>test</scope></dependency>
    <dependency><groupId>io.mockk</groupId><artifactId>mockk-jvm</artifactId><scope>test</scope></dependency>
    <dependency><groupId>com.atlassian.oai</groupId><artifactId>swagger-request-validator-restassured</artifactId><scope>test</scope></dependency>
    <dependency><groupId>io.quarkus</groupId><artifactId>quarkus-jacoco</artifactId><scope>test</scope></dependency>
</dependencies>
```

OpenAPI generator + Kotlin/Java plugin-configuratie kopiëren van `berichtensessiecache` (identieke setup: `jaxrs-spec`, `interfaceOnly=true`, `apiPackage=nl.rijksoverheid.moz.berichtenmagazijn.api`).

## CI

Nieuwe workflow `.github/workflows/test-berichtenmagazijn.yml`, naar het voorbeeld van `test-sessiecache.yml`:
- Trigger op `feature/berichtenmagazijn-**` en `main`
- Geen externe services nodig (H2 embedded); `redis`-service in workflow weglaten
- Stappen: checkout, setup Java 21, `./mvnw compile -pl services/berichtenmagazijn -am -B`, `./mvnw test -pl services/berichtenmagazijn -B`

## Verificatie

Acceptatiecriteria uit issue #410:

- [ ] Maven module `services/berichtenmagazijn` geregistreerd in parent POM
- [ ] Maven module `libraries/fbs-common` geregistreerd in parent POM
- [ ] Package-structuur voorbereid op alle magazijn-containers
- [ ] OpenAPI spec voor `POST /api/v1/berichten` conform NL API Design Rules
- [ ] OpenAPI spec valideert zonder fouten tegen de ADR Spectral linter
- [ ] `AanleverResource.kt` implementeert gegenereerde interface
- [ ] `BerichtOpslagService.kt` met opslag-logica
- [ ] `@CircuitBreaker` op schrijfoperaties
- [ ] Dataopslag via JPA/Panache met H2 embedded
- [ ] Problem JSON ExceptionMappers (via `fbs-common`)
- [ ] `ApiVersionFilter` per service, security headers + cache-control via `fbs-common`
- [ ] LDV `@Logboek` op endpoints
- [ ] Unit tests (MockK) + component-integratietests (QuarkusTest + H2) + contracttests
- [ ] Coverage ≥ 90% (JaCoCo)
- [ ] `compose.yaml` ongewijzigd (H2 embedded)
- [ ] Bestaande sessiecache-tests blijven slagen na refactor

## Ontwerpkeuzes

| Keuze | Alternatief | Rationale |
|---|---|---|
| Nieuwe module `libraries/fbs-common` | Code kopiëren per service | Eén bron van waarheid voor filters/mappers; `ApiVersionFilter` blijft per-service |
| `SecurityHeadersFilter` + `CacheControlFilter` apart | Gecombineerd | Cache-Control is geen security, en kan later per-endpoint verschillen |
| H2 embedded + JPA/Panache | In-memory `ConcurrentHashMap` | Lijkt meer op de productie-oplossing; geen extra containers nodig |
| `@CircuitBreaker` op service-methode | Custom CDI-interceptor | Standaard MicroProfile Fault Tolerance, minder code |
| Aanlever-module bevat lege packages voor ophaal/validatie/etc. | Pas bij volgende issues | Communiceert doelarchitectuur en voorkomt re-organisatie later |
| Branch vanaf `issue-1/sessiecache-c4-alignment` (PR #26) | Branch vanaf `main` | Nieuwe patronen (MockK, JaCoCo, `CreatedStatusFilter`, swagger-validator) zijn direct beschikbaar |

## Open punten

Geen.
