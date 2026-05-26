**Status:** Concept

# MagazijnResolver — design

Issue: [MinBZK/MijnOverheidZakelijk#416](https://github.com/MinBZK/MijnOverheidZakelijk/issues/416).

## Context

`berichtensessiecache` bevraagt vandaag bij elke ophaal-actie élk geconfigureerd magazijn. Issue #416 introduceert `MagazijnResolver`: bepaalt op basis van dienstvoorkeuren (Profiel Service) welke magazijnen bevraagd worden.

Issue is op 2026-05-26 herzien: authenticatie/autorisatie en machtigingsclaims zijn verplaatst naar #552 en uit deze ticket gehaald. Resterende scope: dienstvoorkeuren combineren met beschikbare magazijnen.

De Profiel Service (`github.com/MinBZK/moza-profiel-service`) is al in gebruik in `berichtenmagazijn/validatie/` (`ProfielServiceClient` + `BerichtValidatieService`). Patroon en DTO-vorm zijn dus gevestigd; deze ticket hergebruikt ze.

## Beslissingen (uit brainstorm)

| Onderwerp | Keuze |
|---|---|
| Scope | Alleen dienstvoorkeuren. Geen claims (uitgesteld naar #552). |
| Lookup-key | `X-Ontvanger`-header, na uitlijning met magazijn (`TYPE:WAARDE`). |
| Profiel-contract | Hergebruik bestaande model uit `berichtenmagazijn` (`OntvangViaBerichtenbox`-voorkeur met `scope.partij.OIN`). |
| Magazijn ↔ afzender-mapping | `magazijnen.instances.<id>.afzenders` configuratie-lijst van OIN(s). Resolver kruist opted-in OINs met magazijn-afzenders. |
| OIN-ontvanger | Profiel-call skippen → alle magazijnen. B2B is contractueel buiten Profiel-service. |
| X-Ontvanger-spec | Uitlijnen op magazijn-API: regex `^(BSN:[0-9]{9}\|RSIN:[0-9]{9}\|KVK:[0-9]{8}\|OIN:[0-9]{20})$`. |
| Identificatienummer.kt | Verplaatsen van `berichtenmagazijn/opslag/` naar `libraries/fbs-common/.../identificatie/`. 75 callsites — pure import-vervanging. |
| ProfielServiceClient | Verplaatsen van `berichtenmagazijn/validatie/` naar `libraries/fbs-common/.../profiel/`. Eén gedeelde upstream → één client/DTO/endpoint-validator. |
| Foutpaden | Fail-closed: 200/404 zonder voorkeur → lege resultaten + `OPHALEN_GEREED`. 5xx/timeout/malformed → 503 + `Retry-After`. Geen "alle magazijnen bij Profiel-fout"-fallback — toestemming-onbekend is geen impliciete toestemming. |

## Architectuur

```
HTTP POST /api/v1/berichten/ophalen   (X-Ontvanger: BSN:999993653)
    │
BerichtenOphalenResource
    │  Identificatienummer.fromHeader(header)
    ▼
BerichtensessiecacheService.ophalenBerichten(ontvanger: Identificatienummer)
    │  trySetAggregationStatus(BEZIG)
    │
    ▼
MagazijnResolver.resolve(ontvanger): Uni<Set<MagazijnId>>
    │  ┌─ Oin → return alle config-IDs (skip Profiel)
    │  └─ Bsn/Rsin/Kvk →
    │       ProfielServiceClient.getPartij(type.name, waarde)
    │       └─ filter OntvangViaBerichtenbox=true → opted-in OINs
    │       └─ intersect met magazijnen.instances.*.afzenders → magazijn-IDs
    │
    ▼ (set van IDs)
clientFactory.getAllClients().filterKeys { it in resolved }
    │
    ▼
Magazijn-streams + cache.store + OPHALEN_GEREED   (bestaande pipeline, ongewijzigd)
```

## Componenten

### Nieuw in `fbs-common`

**`nl.rijksoverheid.moz.fbs.common.identificatie`** (verplaatst uit `berichtenmagazijn/opslag/`):
- `sealed interface Identificatienummer` + `Bsn`, `Rsin`, `Kvk`, `Oin` value-classes.
- `enum IdentificatienummerType { BSN, RSIN, KVK, OIN }`.
- `Identificatienummer.fromHeader(header: String)` — parser voor `TYPE:WAARDE`.

**`nl.rijksoverheid.moz.fbs.common.profiel`** (verplaatst uit `berichtenmagazijn/validatie/`):
- `ProfielServiceClient` (`@RegisterRestClient(configKey = "profiel-service")`).
- DTO's: `PartijResponse`, `VoorkeurResponse`, `ScopeResponse`, `IdentificatieResponse`, `DienstResponse`.
- `ProfielServiceEndpointValidator` (TLS-check in non-dev/test profielen). Dunne service-specifieke `@ApplicationScoped`-wrapper die `StartupEvent` observeert blijft per service (anders éénmalige bean conflicteert tussen services in dezelfde JVM — wat hier niet voorkomt, maar het patroon houdt validatie service-lokaal aan-of-uit).

### Nieuw in `berichtensessiecache`

**`magazijn/MagazijnResolver.kt`** (interface):
```kotlin
interface MagazijnResolver {
    fun resolve(ontvanger: Identificatienummer): Uni<Set<String>>
}
```

**`magazijn/ProfielMagazijnResolver.kt`** (`@ApplicationScoped` impl):
- `Oin`-ontvanger: skip Profiel-call → alle magazijn-IDs uit `clientFactory`.
- Andere typen: `profielClient.getPartij(type.name, waarde)` op `Infrastructure.getDefaultWorkerPool()`.
- Filter voorkeuren op `voorkeurType == "OntvangViaBerichtenbox"` + `waarde.lowercase() in {"true","ja"}`.
- Verzamel `scope.partij.identificatieNummer` waar `identificatieType == "OIN"`.
- Kruis met `magazijnen.instances.*.afzenders` → return magazijn-ID-set.
- 404 (`WebApplicationException.response.status == 404`) → `Uni.createFrom().item(emptySet())`.
- 5xx / `ProcessingException` / timeout / malformed → werp `ProfielServiceFoutException`.

**`magazijn/ProfielServiceFoutException.kt`** + **`ProfielServiceFoutExceptionMapper`**:
- 503 Problem+JSON, type `profiel-service-onbereikbaar`, header `Retry-After: 30`.

**`magazijn/MagazijnenConfig.kt`** (uitbreiding):
```kotlin
interface MagazijnInstance {
    fun url(): String
    fun naam(): Optional<String>
    fun afzenders(): List<String>  // OIN(s) van afzenders die dit magazijn serveert
}
```
`MagazijnClientFactory.init()` valideert: elke `afzenders`-waarde via `Oin`-constructor; lege of ontbrekende `afzenders` → `IllegalStateException` fail-fast.

### Wijziging in `berichtensessiecache`

**`BerichtensessiecacheService.ophalenBerichten(ontvanger: Identificatienummer): Multi<MagazijnEvent>`** (signatuur-wijziging):
1. `trySetAggregationStatus(cacheKey, BEZIG)` — zoals nu.
2. `resolver.resolve(ontvanger).await()` — sync vóór SSE-Multi-build.
3. Bij `ProfielServiceFoutException`: `storeAggregationStatus(FOUT, 0, 0, 0)` → werp door.
4. Filter `clientFactory.getAllClients()` op resolved-set → `clients`.
5. `bezigStatus.totaalMagazijnen = clients.size`.
6. Als `clients.isEmpty()` → emit direct `OPHALEN_GEREED(totaal=0, geslaagd=0, mislukt=0, totaalMagazijnen=0)` + `storeAggregationStatus(GEREED, …)`.
7. Anders: bestaande magazijn-pipeline.

**`BerichtenOphalenResource`** + **`BerichtensessiecacheResource`**:
- Vervang raw `ontvanger: String` door `Identificatienummer.fromHeader(headerString)`.
- `DomainValidationException` op invalide header → bestaande mapper (400 Problem+JSON).

**`berichtensessiecache-api.yaml`**:
- `OntvangerHeader.schema.pattern` = `^(BSN:[0-9]{9}|RSIN:[0-9]{9}|KVK:[0-9]{8}|OIN:[0-9]{20})$` (gelijk aan magazijn).
- `minLength: 12`, `maxLength: 24`.
- Voeg 503-response toe op `/berichten/ophalen` met Problem-schema.
- Voeg `Retry-After`-header definitie toe.

**`application.properties` (sessiecache)**:
```properties
# MOZA Profiel Service — gedeelde upstream met berichtenmagazijn.
quarkus.rest-client.profiel-service.url=${PROFIEL_SERVICE_URL}
%dev.quarkus.rest-client.profiel-service.url=http://localhost:8089
%test.quarkus.rest-client.profiel-service.url=http://localhost:8089
quarkus.rest-client.profiel-service.connect-timeout=2000
quarkus.rest-client.profiel-service.read-timeout=5000

# Afzender-OIN(s) per magazijn — basis voor MagazijnResolver-filter.
magazijnen.instances.magazijn-a.afzenders=00000001003214345000
magazijnen.instances.magazijn-b.afzenders=00000001823288444000
```

### Wijziging in `berichtenmagazijn`

- Imports `nl.rijksoverheid.moz.fbs.berichtenmagazijn.opslag.{Identificatienummer,Bsn,Rsin,Kvk,Oin,IdentificatienummerType}` → `nl.rijksoverheid.moz.fbs.common.identificatie.*` (~75 sites, mechanisch).
- Imports `nl.rijksoverheid.moz.fbs.berichtenmagazijn.validatie.{ProfielServiceClient,PartijResponse,VoorkeurResponse,ScopeResponse,IdentificatieResponse,DienstResponse}` → `nl.rijksoverheid.moz.fbs.common.profiel.*`.
- `ProfielServiceEndpointValidator` — tweetraps: shared static `validate(profile, endpoint)` in fbs-common; service-lokale `@ApplicationScoped`-wrapper-bean die `StartupEvent` observeert blijft in elke service.

## Dataflow-voorbeelden

**Success: BSN met voorkeur voor magazijn-a**
```
Client → X-Ontvanger: BSN:999993653
fromHeader → Bsn("999993653")
trySetAggregationStatus(BEZIG)
resolver.resolve(Bsn):
  Profiel.getPartij("BSN", "999993653") → 200 {
    voorkeuren: [{type:OntvangViaBerichtenbox, waarde:true,
                  scopes:[{partij:{type:OIN, nummer:"00000001003214345000"}}]}]
  }
  opted-in OINs: {00000001003214345000}
  magazijn-a.afzenders ∋ OIN → return {"magazijn-a"}
clients = {magazijn-a}
bezigStatus.totaalMagazijnen = 1
SSE: GESTART(magazijn-a) → VOLTOOID(magazijn-a, OK, 3) → OPHALEN_GEREED(totaal=3, geslaagd=1, mislukt=0, totaalMagazijnen=1)
cache.store + storeAggregationStatus(GEREED)
```

**Geen voorkeuren (200, lege voorkeuren-array of geen match)**
```
resolver.resolve → emptySet()
clients = {}
SSE: OPHALEN_GEREED(totaal=0, geslaagd=0, mislukt=0, totaalMagazijnen=0)
cache.store([]) + storeAggregationStatus(GEREED)
```

**404 ontvanger onbekend bij Profiel**
```
resolver.resolve → emptySet() (zelfde flow als boven). Geen onderscheid voor caller: voorkomt timing-leak tussen "bekende-zonder-voorkeur" en "onbekend".
```

**Profiel onbereikbaar (5xx of timeout)**
```
resolver.resolve → ProfielServiceFoutException
storeAggregationStatus(FOUT, 0, 0, 0)
HTTP 503 + Problem+JSON {type:"profiel-service-onbereikbaar"} + Retry-After: 30
```

**OIN-ontvanger (B2B)**
```
fromHeader → Oin("00000001003214345000")
resolver.resolve → alle magazijn-IDs (skip Profiel)
Bestaande aggregatie-pipeline op alle magazijnen.
```

## Tests

### Unit (`ProfielMagazijnResolverTest`, MockK)

- BSN + 1 OIN-match → één magazijn
- BSN + 2 OIN-matches → twee magazijnen
- BSN + voorkeur=true, scope-OIN geen match in config → `emptySet()`
- BSN + voorkeur=false/nee/null → `emptySet()`
- BSN + lege `voorkeuren`-array → `emptySet()`
- BSN + voorkeurType ≠ OntvangViaBerichtenbox → genegeerd
- BSN + scope.partij.identificatieType ≠ OIN → genegeerd
- Voorkeur-waarde case-insensitief: `"TRUE"`, `"Ja"`, `"YES"` (alleen "true"/"ja" zijn opt-in — verifieert exacte set)
- Oin-ontvanger → return alle config-IDs, mockk `verify(exactly = 0)` op Profiel-client
- Rsin-ontvanger → wel Profiel-call (gelijk aan Bsn-pad)
- Kvk-ontvanger → wel Profiel-call
- 404 (ClientWebApplicationException, status=404) → `emptySet()`
- 500 → `ProfielServiceFoutException`
- ProcessingException → `ProfielServiceFoutException`

### Component-integratie (`ProfielMagazijnResolverIntegrationTest`, `@QuarkusTest` + WireMock)

Spiegelt `ProfielServiceClientWireMockTest` uit magazijn (post-verplaatsing leeft daar mogelijk dezelfde of een uitgebreidere test). Eigen `WireMockProfielServiceResource` op dynamische poort. Stubs:
- 200 met opted-in voorkeur
- 200 met lege voorkeuren
- 200 met malformed JSON
- 404
- 500
- delayed (>read-timeout)

### Service-integratie (uitbreiding `BerichtensessiecacheServiceTest`)

- Resolver geeft beide magazijnen → bestaande happy-events
- Resolver → `emptySet()` → directe `OPHALEN_GEREED` (totaal/geslaagd/mislukt = 0), géén `MAGAZIJN_BEVRAGING_*`-events
- Resolver werpt `ProfielServiceFoutException` → 503 propageert, cache-status `FOUT`
- Resolver geeft `magazijn-a` maar magazijn-a faalt → 1 mislukt + OPHALEN_GEREED

### E2E (`BerichtenOphalenResourceTest` uitbreiden)

- Header `BSN:999993653` met opted-in profiel → 200 SSE
- Header `BSN:999993653` met 200/lege profiel → SSE `OPHALEN_GEREED 0/0/0`
- Header `BSN:999993653` met 500 profiel → 503 + `Retry-After`
- Header zonder `:`-prefix → 400 Problem+JSON
- Header `OIN:…` → alle magazijnen, geen Profiel-call

### Contract (`OpenApiContractTest` uitbreiden)

- 503-response matcht Problem-schema
- `X-Ontvanger`-regex valideert
- Spectral-linter: `npx @stoplight/spectral-cli lint berichtensessiecache-api.yaml --ruleset https://static.developer.overheid.nl/adr/ruleset.yaml` → 0 errors

### Bruno-collectie

- Bestaande requests: update `X-Ontvanger` waarden naar `BSN:999993653`-formaat
- Nieuw: `ophalen-zonder-voorkeur.bru` (BSN waar lokale WireMock 404 retourneert)
- Nieuw: `ophalen-profiel-fout.bru` (forceer 503-pad)

### Coverage

ProfielMagazijnResolver bereikbaar via `@QuarkusTest`-integratietest → telt mee voor `quarkus-jacoco` 90%-drempel.

## Verificatie

```bash
./mvnw clean verify -pl libraries/fbs-common -am
./mvnw clean verify -pl services/berichtenmagazijn -am   # 75 import-rewrites
./mvnw clean verify -pl services/berichtensessiecache -am

# Spec-linter
npx @stoplight/spectral-cli lint \
  services/berichtensessiecache/src/main/resources/openapi/berichtensessiecache-api.yaml \
  --ruleset https://static.developer.overheid.nl/adr/ruleset.yaml

# Lokaal dev (Profiel WireMock al in compose.yaml)
docker compose up -d
./mvnw quarkus:dev -pl services/berichtensessiecache
# Bruno-requests draaien tegen lokaal
```

## Open punten

- AC-tekst issue #416 zegt "Fallback: alle magazijnen bij Profiel-fout (graceful degradation)" — design herformuleert tot fail-closed (toestemming-semantiek). Stem af in PR-omschrijving + reactie op issue.
- Optioneel: voeg `dienst`-filter toe in resolver (huidige berichtenmagazijn-code negeert `scope.dienst`). Geen concrete eis nu — laten voor opvolg-ticket.
