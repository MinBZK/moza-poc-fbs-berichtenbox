# Plan: testdekking fbs-common compleet maken

**Status:** Uitgevoerd

## Context

Bij review van fbs-common viel op dat enkele klassen geen test in fbs-common
zelf hebben:

- `ConstraintViolationExceptionMapper`, `JsonProcessingExceptionMapper` (+
  `MismatchedInputExceptionMapper`) en `CreatedStatusFilter` worden wél getest,
  maar de tests staan in `services/berichtenmagazijn/src/test/...` —
  pure JUnit/MockK, geen runtime-koppeling met de service.
- `ApiVersionFilter`, `CacheControlFilter` en `SecurityHeadersFilter` worden
  alleen indirect gedekt door header-asserts in `AanleverResourceIntegrationTest`
  en `BerichtensessiecacheResourceTest`.
- `requireValid` (in `DomainValidationException.kt`) heeft geen eigen test;
  alleen indirect via Bericht/Identificatienummer-tests.

**Probleem:** een wijziging in fbs-common kan pas in een service-test
zichtbaar worden (geen lokale feedback in de fbs-common-build), en JaCoCo voor
fbs-common meet de dekking niet correct. Een nieuwe service moet de tests
opnieuw toevoegen of importeren. Voor security-gevoelige logica
(`SAFE_PATH_PATTERN` in `JsonProcessingExceptionMapper`, security-headers,
HSTS) is dat een onnodig risico.

**Doel:** elke fbs-common-klasse met logica heeft een test in fbs-common zelf.
Indirect gedekte filters krijgen daarnaast een unit-test die het gedrag
expliciet borgt.

## Bevindingen per bestand

| Klasse                                 | Test bestaat?                | Actie       |
|----------------------------------------|------------------------------|-------------|
| `ConstraintViolationExceptionMapper`   | ja, in services/berichtenmagazijn (`@QuarkusTest` met mocks — onnodig) | **verplaatsen** + `@QuarkusTest` weghalen |
| `JsonProcessingExceptionMapper` + `MismatchedInputExceptionMapper` | ja, in services/berichtenmagazijn (pure JUnit) | **verplaatsen** + uitbreiden met SAFE_PATH-edge cases |
| `CreatedStatusFilter`                  | ja, in services/berichtenmagazijn (pure JUnit/MockK) | **verplaatsen** |
| `ApiVersionFilter`                     | nee (alleen header-assert in roundtrip)  | **toevoegen**: zet header met provider-waarde, overschrijft niet bestaande |
| `CacheControlFilter`                   | nee (alleen header-assert)               | **toevoegen**: zet `no-store` alleen als nog niet aanwezig |
| `SecurityHeadersFilter`                | nee (alleen header-assert)               | **toevoegen**: 5 verwachte headers exact, idempotent over meerdere calls |
| `requireValid` (in `DomainValidationException.kt`) | nee (alleen indirect)            | **toevoegen**: kleine test voor true/false en lazy-message |
| `ApiVersionProvider` (interface)       | n.v.t. — pure interface zonder default-implementaties | geen test nodig |
| `ProblemMediaType`                     | indirect: elke mapper-test asserts `response.mediaType.toString() == "application/problem+json"`, wat zowel de string-constante als `MediaType.valueOf(...)`-parse dekt | geen aparte test nodig |
| `ProblemResponses` (`internal` helpers) | indirect via 6 mappers      | **toevoegen** — defaults (detail-tekst, `urn:uuid:`-prefix, content-type) zijn een impliciet contract dat we centraal willen vastleggen i.p.v. 6× indirect |

## Wijzigingen

### Verplaatsen (geen logica-wijziging)

| Van | Naar |
|---|---|
| `services/berichtenmagazijn/src/test/kotlin/nl/rijksoverheid/moz/berichtenmagazijn/aanlever/ConstraintViolationExceptionMapperTest.kt` | `libraries/fbs-common/src/test/kotlin/nl/rijksoverheid/moz/fbs/common/exception/ConstraintViolationExceptionMapperTest.kt` |
| `services/berichtenmagazijn/.../aanlever/JsonProcessingExceptionMapperTest.kt` | `libraries/fbs-common/.../exception/JsonProcessingExceptionMapperTest.kt` |
| `services/berichtenmagazijn/.../aanlever/CreatedStatusFilterTest.kt` | `libraries/fbs-common/.../CreatedStatusFilterTest.kt` |

Tegelijk:
- `package`-declaraties bijwerken (`...berichtenmagazijn.aanlever` →
  `...fbs.common[.exception]`).
- `@QuarkusTest` weghalen uit `ConstraintViolationExceptionMapperTest` — de
  test gebruikt alleen mockk voor `ConstraintViolation`/`Path` en heeft geen
  Quarkus-runtime nodig.
- Verifiëren dat `fbs-common/pom.xml` mockk en `jakarta.validation-api` als
  test-scope dependencies heeft (zo niet: toevoegen).

### Uitbreiden

`JsonProcessingExceptionMapperTest` (in fbs-common) extra cases voor
`SAFE_PATH_PATTERN`:

- pad met `<script>` → detail valt terug op `"Ongeldige JSON-invoer."`
  (geen reflection van attacker-controlled key).
- pad met spatie / control-char → fallback.
- pad met geneste array-index `data.items[0].naam` → wel gehonoreerd
  (matcht het regex).
- diep genest pad `a.b.c.d` → wel gehonoreerd.

### Nieuwe unit-tests in fbs-common

Vijf nieuwe testfiles, allemaal pure JUnit + MockK, in
`libraries/fbs-common/src/test/kotlin/nl/rijksoverheid/moz/fbs/common/`:

1. **`ApiVersionFilterTest.kt`**
   - Roept `ApiVersionProvider.version()` aan en zet de `API-Version`-header op
     de response.
   - Bij meerdere bestaande `API-Version`-waarden vervangt `putSingle` ze (geen
     duplicate header).
   - Stub `ApiVersionProvider` via mockk.

2. **`CacheControlFilterTest.kt`**
   - Geen bestaande `Cache-Control` → `no-store` wordt gezet.
   - Bestaande `Cache-Control` (bv. `public, max-age=60`) → blijft ongewijzigd.

3. **`SecurityHeadersFilterTest.kt`**
   - 5 headers exact: `Strict-Transport-Security`, `X-Frame-Options`,
     `X-Content-Type-Options`, `Content-Security-Policy`, `Referrer-Policy`.
   - HSTS-waarde `max-age=31536000; includeSubDomains; preload` exact, omdat
     internet.nl daarop test.
   - Idempotent: tweede call overschrijft, geen duplicates.

4. **`RequireValidTest.kt`** (in package `nl.rijksoverheid.moz.fbs.common.exception`)
   - `requireValid(true) {...}` → geen exception, lazy-message niet
     geëvalueerd (gebruik een mockk-lambda en `verify(exactly = 0)`).
   - `requireValid(false) { "fout" }` → gooit `DomainValidationException` met
     juiste message.

5. **`ProblemResponsesTest.kt`** (in package `nl.rijksoverheid.moz.fbs.common.exception`)
   - `problemResponse(400, "Bad Request", "iets")` → status 400, content-type
     `application/problem+json`, `entity is Problem` met dezelfde velden,
     `instance == null` als niet meegegeven.
   - `maskedServerErrorProblem(uuid)` → status 500, title "Internal Server
     Error", default-detail bevat "onverwachte interne fout", `instance`
     gelijk aan `URI.create("urn:uuid:$uuid")`.
   - `maskedServerErrorProblem(uuid, status = 503, title = "Service
     Unavailable", detail = "x")` → status/title/detail overrides werken,
     instance blijft op uuid.

   Dit pint het impliciete contract op één plek vast: een wording-wijziging
   in de helper-defaults breekt nu eerst deze test in plaats van stilzwijgend
   door 6 mapper-tests heen te lekken.

### Service-tests opruimen na verplaatsing

De drie verplaatste tests verdwijnen uit `services/berichtenmagazijn`. De
roundtrip-tests in `AanleverResourceIntegrationTest` en
`BerichtensessiecacheResourceTest` blijven staan — die testen de end-to-end
filter-keten en zijn een waardevolle aanvulling op de nieuwe unit-tests.

## Bestanden

**Verplaatsen** (3 testbestanden) — paden hierboven.

**Nieuw** (5 testbestanden in fbs-common):

- `libraries/fbs-common/src/test/kotlin/nl/rijksoverheid/moz/fbs/common/ApiVersionFilterTest.kt`
- `libraries/fbs-common/src/test/kotlin/nl/rijksoverheid/moz/fbs/common/CacheControlFilterTest.kt`
- `libraries/fbs-common/src/test/kotlin/nl/rijksoverheid/moz/fbs/common/SecurityHeadersFilterTest.kt`
- `libraries/fbs-common/src/test/kotlin/nl/rijksoverheid/moz/fbs/common/exception/RequireValidTest.kt`
- `libraries/fbs-common/src/test/kotlin/nl/rijksoverheid/moz/fbs/common/exception/ProblemResponsesTest.kt`

**Uitbreiden** (1 bestand):

- `libraries/fbs-common/src/test/kotlin/nl/rijksoverheid/moz/fbs/common/exception/JsonProcessingExceptionMapperTest.kt` (na verplaatsing)

**POM**:

- `libraries/fbs-common/pom.xml` — controleren/aanvullen test-deps (`mockk`,
  `jakarta.validation-api`).

**Geen wijzigingen** in:

- `ApiVersionProvider` (interface), `ProblemMediaType` (constants).
- Productiecode in fbs-common (alleen tests bewegen/komen erbij).

## Verificatie

```bash
./mvnw -B clean test -pl libraries/fbs-common         # nieuwe tests groen
./mvnw -B test -pl services/berichtenmagazijn -am     # roundtrip-tests blijven groen
./mvnw -B test -pl services/berichtensessiecache -am  # roundtrip-tests blijven groen
```

Verwacht:

- fbs-common test-aantal stijgt van 22 naar ±35 (3 verplaatst + uitbreidingen +
  5 nieuwe).
- berichtenmagazijn test-aantal daalt met 3 verplaatste + ev. enkele
  uitgebreide cases die meekomen.
- JaCoCo coverage check fbs-common voldoet (minimum 90% line, met
  gegenereerde code uitgesloten).

Daarnaast handmatig:

- `git mv` zodat de history van de verplaatste testbestanden behouden blijft.

## Niet in scope

- Productiecode-wijzigingen in fbs-common.
- Gedragswijziging van filters/mappers; deze tests borgen alleen het bestaande
  gedrag zodat regressies in fbs-common direct lokaal zichtbaar worden.
- Coverage-verhoging in service-modules; dat hoort thuis in een eigen plan
  als nodig.
