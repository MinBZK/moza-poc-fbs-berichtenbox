**Status:** Concept
**Datum:** 2026-04-21
**Module:** `libraries/fbs-common`

# Fixes naar aanleiding van code review fbs-common

Plan om bevindingen uit de review van `libraries/fbs-common` op te volgen.
Review dekte veiligheid, BIO, overheidsstandaarden (NL API Design Rules, RFC 9457, internet.nl, AVG/LDV) en efficiëntie.

## Samenvatting bevindingen

| # | Ernst | Onderwerp | Locatie |
|---|-------|-----------|---------|
| 1 | **Hoog** | `ApiVersionFilter`-regex matcht nooit; header altijd fallback `v1` | `ApiVersionFilter.kt:22, 27` |
| 2 | Medium | Testdekking ontbreekt voor 8 van 10 filters/mappers (conflict met 90% JaCoCo-gate) | `libraries/fbs-common/src/test` |
| 3 | Medium | `LogboekContextDefaultFilter` zet `dataSubjectType = "system"` → valse LDV-audittrail | `LogboekContextDefaultFilter.kt:21-22` |
| 4 | Medium | `CreatedStatusFilter` forceert 201 op élke 200-POST → mis voor command-/upsert-endpoints | `CreatedStatusFilter.kt:19-21` |
| 5 | Laag | HSTS `preload` vanuit applicatiefilter is hard commitment | `SecurityHeadersFilter.kt:18-21` |
| 6 | Laag | `ConstraintViolationExceptionMapper` sorteert niet, kan regex lekken via `message` | `ConstraintViolationExceptionMapper.kt:23-26` |
| 7 | Laag | `JsonProcessingExceptionMapper` bouwt `foo.[0].bar` → valt altijd terug op generieke tekst bij arrays | `JsonProcessingExceptionMapper.kt:26` |
| 8 | Laag | `Problem.of()`-doc verplicht factory; mappers gebruiken directe constructor | `Problem.kt:13` vs alle mappers |
| 9 | Laag | `ProblemExceptionMapper` logt `exception.message` ongefilterd (log-injectie) | `ProblemExceptionMapper.kt:36, 45` |
| 10 | Laag | `logboekdataverwerking-wrapper` hangt op `1.2.1-SNAPSHOT` | `pom.xml:61` |
| 11 | Laag | `SecurityHeadersFilter` mist `Permissions-Policy` en `Cross-Origin-*` | `SecurityHeadersFilter.kt` |
| 12 | Laag | CDI-beans gebruiken `@Inject lateinit var` i.p.v. constructor-injection | `LogboekContextDefaultFilter.kt:17-18` |

## Scope

**In scope voor deze PR-iteratie:** 1, 2, 3, 4, 7, 9.

**Buiten scope, apart vastleggen:**
- **5** (HSTS preload): afhankelijk van deployment-/ingress-strategie (Haven). Vereist afstemming met platform-team; nu niet urgent.
- **6** (Pattern-message-lek): stijlkwestie die beter via een codebase-wijde lintregel/reviewchecklist afgedwongen wordt, niet in fbs-common.
- **8** (doc vs praktijk): cosmetisch; oplossen wanneer andere wijziging in `Problem.kt` langskomt.
- **10** (SNAPSHOT): afhankelijk van release van `logboekdataverwerking-wrapper` buiten deze repo.
- **11** (extra security-headers): eerder ingress-verantwoordelijkheid; documenteren in deployment-plan.
- **12** (constructor-injection): grote refactor over alle CDI-beans; apart plan waard.

---

## Stappen

### Stap 1 — Fix `ApiVersionFilter` (bevinding 1)

**Bestand:** `libraries/fbs-common/src/main/kotlin/nl/rijksoverheid/moz/fbs/common/ApiVersionFilter.kt`

`UriInfo.getPath()` levert paden relatief aan de base URI zonder leading slash. Voor onze resources (`@Path("/api/v1/...")`, geen `quarkus.http.root-path`) is dat `api/v1/berichten`. De huidige regex `^v(\d+)/` is geanchord aan het begin en matcht dus nooit — elke response krijgt stilzwijgend de fallback `v1`.

**Wijziging:**

```kotlin
private companion object {
    // Zoekt het eerste versiesegment (`v1`, `v2`, ...) ongeacht of het direct na de base URI
    // of na een `api/`-prefix staat. We matchen een volledig pathsegment, geen substring,
    // zodat een pad als `reserve-vN-later/foo` niet per ongeluk matcht.
    val VERSION_PATTERN = Regex("""(?:^|/)v(\d+)(?:/|$)""")
    const val DEFAULT_VERSION = "v1"
}

override fun filter(requestContext: ContainerRequestContext, responseContext: ContainerResponseContext) {
    val match = VERSION_PATTERN.find(requestContext.uriInfo.path)
    val version = match?.let { "v${it.groupValues[1]}" } ?: DEFAULT_VERSION
    responseContext.headers.putSingle("API-Version", version)
}
```

**Tests (nieuw bestand `ApiVersionFilterTest.kt`):**

- `api/v1/berichten` → `v1`
- `api/v2/berichten/123` → `v2`
- `v1/berichten` (base URI overridden) → `v1`
- `openapi.json` → `v1` (fallback)
- `q/health` → `v1` (fallback)
- lege string → `v1` (fallback)
- `reserve-vN-later/foo` → `v1` (geen vals positief)

Gebruik `mockk` voor `ContainerRequestContext`/`ContainerResponseContext`/`UriInfo`, conform `LogboekContextDefaultFilterTest`.

**Integratietest-aanvulling** in `services/berichtenmagazijn` en `services/berichtensessiecache`: huidige asserts op `API-Version == v1` laten staan; ze worden pas betekenisvol nadat de bug gerepareerd is.

### Stap 2 — Fix `LogboekContextDefaultFilter` default (bevinding 3)

**Bestand:** `libraries/fbs-common/src/main/kotlin/nl/rijksoverheid/moz/fbs/common/LogboekContextDefaultFilter.kt`

`"system"` categoriseert een request foutief als systeeminitiatief in het LDV-logboek (AVG art. 30). Zet op `"unknown"` of een vergelijkbare neutrale waarde.

```kotlin
override fun filter(requestContext: ContainerRequestContext) {
    logboekContext.dataSubjectId = "unknown"
    logboekContext.dataSubjectType = "unknown"
}
```

Controleer in `logboekdataverwerking-wrapper` welke waarden geaccepteerd worden. Als die lib een gesloten enum heeft, kies de neutraalst beschikbare; anders `"unknown"`.

**Tests:** `LogboekContextDefaultFilterTest.kt` aanpassen: `verify { context.dataSubjectType = "unknown" }`.

### Stap 3 — Beperk `CreatedStatusFilter` (bevinding 4)

**Bestand:** `libraries/fbs-common/src/main/kotlin/nl/rijksoverheid/moz/fbs/common/CreatedStatusFilter.kt`

Huidige gedrag promoveert élke succesvolle POST van 200 naar 201; dat klopt niet voor command-style endpoints zoals `POST /api/v1/berichten/_ophalen`.

**Keuze:** filter verwijderen en per-resource de juiste status expliciet terug laten geven.

Rationale: de OpenAPI-generator (`jaxrs-spec`, `returnResponse=false`) forceert het return-type wel, maar de Kotlin-implementatie kan alsnog `Response.status(201).entity(body).build()` of de generator-specifieke responder gebruiken. Dat is expliciet en blijft per endpoint klopppen met de OpenAPI-spec.

**Concreet:**

1. Verwijder `libraries/fbs-common/src/main/kotlin/nl/rijksoverheid/moz/fbs/common/CreatedStatusFilter.kt`.
2. Verwijder bijbehorende `beans.xml`-/Jandex-verwijzingen (niet van toepassing: Jandex pakt auto op, dus verwijdering volstaat).
3. In `services/berichtenmagazijn/.../aanlever/AanleverResource.kt`: `POST /api/v1/berichten` expliciet `Response.status(201)`-pad laten produceren. Tests in `AanleverResourceIntegrationTest` blijven 201 asserten.
4. Controleer `services/berichtensessiecache` op andere POST-endpoints (`/_ophalen`): die moeten 200 blijven retourneren — zodra `CreatedStatusFilter` weg is gebeurt dat vanzelf, maar bevestig met een bestaande of nieuwe integratietest.

**Alternatief** (indien verwijdering te invasief blijkt): filter restricten tot een whitelist via pad-pattern of via een `@CreatesResource`-annotatie op de JAX-RS methode; filter haalt `ResourceInfo` op en checkt de annotatie. Dit is meer code voor een vangnet dat één keer per resource verdwijnt — voorkeur gaat uit naar verwijdering.

**Tests:**
- `AanleverResourceIntegrationTest` blijft 201 verwachten.
- Voeg een test toe op `_ophalen`-endpoint dat 200 retourneert (in sessiecache-testsuite) om regressie te voorkomen.

### Stap 4 — Fix path-rendering in `JsonProcessingExceptionMapper` (bevinding 7)

**Bestand:** `libraries/fbs-common/src/main/kotlin/nl/rijksoverheid/moz/fbs/common/JsonProcessingExceptionMapper.kt`

Huidige render: `foo.[0].bar`. Moet: `foo[0].bar`.

```kotlin
private fun renderPath(exception: MismatchedInputException): String =
    buildString {
        exception.path.forEach { ref ->
            val name = ref.fieldName
            if (name != null) {
                if (isNotEmpty()) append('.')
                append(name)
            } else {
                append('[').append(ref.index).append(']')
            }
        }
    }
```

Gebruik deze in plaats van de `joinToString`-expressie.

**Tests (nieuw bestand `JsonProcessingExceptionMapperTest.kt`):**

- Veld `naam` ontbreekt → detail eindigt op `"veld 'naam'."`.
- Pad `adressen[3].postcode` → detail eindigt op `"veld 'adressen[3].postcode'."`.
- Attacker-path (bv. `<script>`) via `MismatchedInputException` met Map-target → detail valt terug op generiek "Ongeldige JSON-invoer."
- Lege path → detail valt terug op generiek.
- `JsonProcessingException` niet van type `MismatchedInputException` → generiek.

### Stap 5 — Voorkom log-injectie in `ProblemExceptionMapper` (bevinding 9)

**Bestand:** `libraries/fbs-common/src/main/kotlin/nl/rijksoverheid/moz/fbs/common/ProblemExceptionMapper.kt`

Loggen van `exception.message` met control-chars kan fake log-regels opleveren. De `sanitizeClientDetail`-helper strip control-chars al; gebruik hem ook vóór logging (niet alleen voor de response).

```kotlin
val safeMessage = sanitizeClientDetail(exception.message) ?: "(no message)"
log.errorf(exception, "Server error %d (errorId=%s): %s", status, errorId, safeMessage)
// respectievelijk voor 4xx:
log.infof("Client error %d (errorId=%s): %s", status, errorId, safeMessage)
```

Let op: voor 5xx willen we de stacktrace (via `exception` als eerste argument van `errorf`) wél volledig hebben — daar doet JBoss Logging zelf de escaping binnen de stacktrace-rendering. Alleen het `%s`-arg wordt onveilig als het control-chars bevat.

Overweeg voor de toekomst (niet in scope nu): structured logging / JSON-log-format via `quarkus.log.console.json=true` in productie.

**Tests (nieuw bestand `ProblemExceptionMapperTest.kt`):**

- 5xx → response is 500, `instance` bevat `urn:uuid:`, `detail` is generiek masker, `exception.response` null-fallback naar 500 werkt.
- 4xx → response is de gegeven status, `detail` is gesanitized, `instance` gezet.
- `exception.message` met control-chars → log-assert via een test-Logger of via `Logger.setLevel`-capture dat de gelogde message geen `\n`/`\t`/` ` bevat.

### Stap 6 — Testdekking aanvullen (bevinding 2)

**Nieuwe testbestanden** in `libraries/fbs-common/src/test/kotlin/nl/rijksoverheid/moz/fbs/common/`:

- `ApiVersionFilterTest.kt` (stap 1)
- `JsonProcessingExceptionMapperTest.kt` (stap 4)
- `ProblemExceptionMapperTest.kt` (stap 5)
- `SecurityHeadersFilterTest.kt` — assert dat alle 5 headers gezet worden met verwachte waarden.
- `CacheControlFilterTest.kt` — 2 cases: default `no-store`, bestaande `Cache-Control` blijft staan.
- `ConstraintViolationExceptionMapperTest.kt` — bouw een `ConstraintViolationException` met 2 violations, assert 400 + Problem-shape + `paramName: message`-formaat.
- `DomainValidationExceptionMapperTest.kt` — assert 400, detail = exception.message, problem+json content-type.
- `IllegalArgumentExceptionMapperTest.kt` — assert 500, gemaskeerde detail, `urn:uuid:` instance, log op error-niveau.

Doel: `libraries/fbs-common/target/site/jacoco/index.html` boven 90% line coverage, conform CLAUDE.md.

**Voor coverage-meting:** verifieer dat `quarkus-jacoco`-config in parent POM ook grijpt op fbs-common (module zonder Quarkus-runtime); zo niet, `jacoco-maven-plugin` expliciet aan de fbs-common-pom toevoegen.

---

## Verificatie

```bash
# Unit tests fbs-common
./mvnw test -pl libraries/fbs-common

# Integratie via consumers
./mvnw test -pl services/berichtensessiecache -am
./mvnw test -pl services/berichtenmagazijn -am

# Coverage-rapport
./mvnw verify -pl libraries/fbs-common
open libraries/fbs-common/target/site/jacoco/index.html

# End-to-end rook-test lokaal
docker compose up -d
./mvnw quarkus:dev -pl services/berichtenmagazijn
# Check: curl -i http://localhost:8090/api/v1/berichten -X POST ... → 201 + API-Version: v1
```

## Ontwerpkeuzes

- **ApiVersionFilter:** gekozen voor regex boven path-segment-iteratie omdat de filter slechts op één plek wordt aangeroepen en de regex-variant compacter én tijd-gecompileerd is (`private val` op companion).
- **CreatedStatusFilter verwijderen** i.p.v. whitelisten: expliciete 201 in de resource is lokaler, beter traceerbaar vanuit de OpenAPI-spec, en elimineert een stille globale transformatie.
- **`dataSubjectType = "unknown"`:** liever één neutrale waarde dan aparte labels per filter-pad; het onderscheid "system-initiated vs client-initiated" hoort in de resource-laag thuis, niet in een vangnet-filter.
- **`sanitizeClientDetail` hergebruiken voor logging:** één sanitizer voor response én log voorkomt divergentie en extra dependency. Geen aparte "log-sanitize" helper nodig.
- **Testen niet achteraf maar direct bij elke stap:** review markeert coverage als Medium, maar de gaps overlappen met de bugfixes — tests per stap toevoegen vangt regressies in de fix zelf én sluit de gate.

## Uit scope — apart vastleggen

- HSTS-strategie (preload, max-age-curve, per-omgeving) — onderdeel van deployment-/ingress-plan.
- Constructor-injection over alle CDI-beans — aparte refactor-PR.
- `logboekdataverwerking-wrapper` pinnen op release-versie — actie bij de eigenaar van die wrapper-library.
- Extra security-headers (`Permissions-Policy`, `Cross-Origin-*`) — ingress-verantwoordelijkheid.
