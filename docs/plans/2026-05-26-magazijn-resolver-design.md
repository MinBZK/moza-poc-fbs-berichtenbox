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
| Identificatienummer.kt | Verplaatsen van `berichtenmagazijn/opslag/` naar `libraries/fbs-common/.../identificatie/`. Alleen 6 symbolen verplaatsen — `Identificatienummer` (sealed interface), `IdentificatienummerType` (enum), `Bsn`, `Rsin`, `Kvk`, `Oin` (value classes). Overige `opslag/`-types (`Bericht`, `BerichtRepository`, `BerichtAutorisatie`, enz.) blijven in berichtenmagazijn. Import-rewrite per symbool, geen wildcard. |
| ProfielServiceClient | Verplaatsen van `berichtenmagazijn/validatie/` naar `libraries/fbs-common/.../profiel/`. Eén gedeelde upstream → één client/DTO/endpoint-validator. Inclusief `ProfielServiceFoutException` + `ToestemmingGeweigerdException` + bijbehorende mappers — gedeelde fout-paden tussen magazijn en sessiecache. |
| Ontvanger-representatie | Service-laag wordt service-breed getypeerd: `Identificatienummer`. Conversie aan resource-grens (`fromHeader`). `BerichtenCache.cacheKey(ontvanger: Identificatienummer)` → `sha256("${type.name}:${waarde}")`. Voorkomt stille cache-miss bij ondiepe typering. |
| Foutpaden | Fail-closed: 200/404 zonder voorkeur → lege resultaten + `OPHALEN_GEREED`. 5xx/4xx≠404/timeout/malformed → 503 + `Retry-After`. Geen "alle magazijnen bij Profiel-fout"-fallback — toestemming-onbekend is geen impliciete toestemming. |

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
    │       ProfielServiceClient.getPartij(naarProfielType(type), waarde)
    │       (expliciete when-mapping, niet `.name` — loskoppelt interne enum
    │        van extern profiel-contract)
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

**`libraries/fbs-common/pom.xml`** — voeg dependencies toe (in `<scope>provided</scope>` waar Quarkus al levert):
- `org.eclipse.microprofile.rest.client:microprofile-rest-client-api`
- `org.eclipse.microprofile.fault-tolerance:microprofile-fault-tolerance-api`
- `com.fasterxml.jackson.core:jackson-annotations` (voor `@JsonIgnoreProperties` op DTO's — al transitief via Quarkus, expliciteren voor duidelijkheid).

JAX-RS-annotaties (`@GET`, `@Path`, …) en CDI-annotaties zijn al beschikbaar; geen extra dependencies vereist.

**`nl.rijksoverheid.moz.fbs.common.identificatie`** (verplaatst uit `berichtenmagazijn/opslag/Identificatienummer.kt`):
- `sealed interface Identificatienummer` + `Bsn`, `Rsin`, `Kvk`, `Oin` value-classes.
- `enum IdentificatienummerType { BSN, RSIN, KVK, OIN }`.
- `Identificatienummer.fromHeader(header: String)` — parser voor `TYPE:WAARDE`.
- **Nieuw:** `Identificatienummer.toCanonicalString(): String` = `"${type.name}:${waarde}"`. Identiek aan header-format; één canonical representatie voor cache-keys.

**`nl.rijksoverheid.moz.fbs.common.profiel`** (verplaatst uit `berichtenmagazijn/validatie/`):
- `ProfielServiceClient` (`@RegisterRestClient(configKey = "profiel-service")`).
- DTO's: `PartijResponse`, `VoorkeurResponse`, `ScopeResponse`, `IdentificatieResponse`, `DienstResponse`.
- `ProfielServiceFoutException(message, cause)` — gemapt naar 503 + `Retry-After: 30` door `ProfielServiceFoutExceptionMapper`. Gedeeld: zowel sessiecache als (toekomstig) magazijn kunnen dezelfde fout-respons leveren.
- `ToestemmingGeweigerdException` + mapper — verplaatst (geen wijziging). 403-pad blijft beschikbaar voor magazijn-validatie; sessiecache-resolver gebruikt 'm niet (lege set i.p.v. weigering).
- `ProfielServiceEndpointValidator` — `@ApplicationScoped` bean in `fbs-common` met `@Startup` observer. Eén bean per JVM volstaat; sessiecache en magazijn delen exact dezelfde TLS-eis op dezelfde config-key. Service-lokale wrapper vervalt. Conventie-uitlijning met bestaande `LdvEndpointValidator` (al in fbs-common).

### Nieuw in `berichtensessiecache`

**`magazijn/MagazijnResolver.kt`** (interface):
```kotlin
interface MagazijnResolver {
    fun resolve(ontvanger: Identificatienummer): Uni<Set<String>>
}
```

**`magazijn/ProfielMagazijnResolver.kt`** (`@ApplicationScoped` impl):
- `Oin`-ontvanger: skip Profiel-call → alle magazijn-IDs uit `clientFactory`.
- Andere typen: `profielClient.getPartij(naarProfielType(ontvanger.type), ontvanger.waarde)` op `Infrastructure.getDefaultWorkerPool()`. Mapping via expliciete `when (type) { BSN → "BSN"; RSIN → "RSIN"; KVK → "KVK"; OIN → error("…") }` zoals `BerichtValidatieService.controleerAbonnement` doet — niet `.name` (loskoppeling intern enum vs extern contract).
- Filter voorkeuren op `voorkeurType == "OntvangViaBerichtenbox"` + `waarde?.lowercase() in {"true","ja"}` (case-insensitief; exacte set zoals magazijn).
- Verzamel `scope.partij.identificatieNummer` waar `identificatieType == "OIN"`.
- Kruis met `magazijnen.instances.*.afzenders` → return magazijn-ID-set.
- **404** (`WebApplicationException.response?.status == 404`) → `emptySet()` (deterministisch geen profiel).
- **5xx, 4xx ≠ 404, `ProcessingException`, timeout, malformed JSON** → werp `ProfielServiceFoutException`. Geen onderscheid in caller-respons; 401/403/418 enz. duiden op contract-mismatch en zijn voor sessiecache transient infra-fouten.

Geen `@Retry` op resolver-niveau: `ProfielServiceClient.getPartij` heeft al `@Retry(maxRetries = 2, delay = 200, retryOn = [ProcessingException::class])` voor transient netwerkfouten. Worst-case voor SSE-lock: 3 × (5 s read-timeout + 200 ms delay) ≈ 15.4 s vóór `ProfielServiceFoutException`. Acceptabel binnen lock-TTL (60 s); SSE-client hangt deze ~15 s ofwel ziet 503 ofwel happy-pad. Documenteren in code-comment.

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

**`BerichtensessiecacheService` signatuur-wijzigingen** (consistent service-breed):

| Methode | Was | Wordt |
|---|---|---|
| `getBerichten` | `(page, pageSize, ontvanger: String, afzender)` | `(page, pageSize, ontvanger: Identificatienummer, afzender)` |
| `getAggregationStatus` | `(ontvanger: String)` | `(ontvanger: Identificatienummer)` |
| `getBerichtById` | `(berichtId, ontvanger: String)` | `(berichtId, ontvanger: Identificatienummer)` |
| `zoekBerichten` | `(q, page, pageSize, ontvanger: String, afzender)` | `(q, page, pageSize, ontvanger: Identificatienummer, afzender)` |
| `updateBerichtStatus` | `(berichtId, ontvanger: String, status)` | `(berichtId, ontvanger: Identificatienummer, status)` |
| `ophalenBerichten` | `(ontvanger: String)` | `(ontvanger: Identificatienummer)` |
| `BerichtenCache.cacheKey` | `(ontvanger: String)` | `(ontvanger: Identificatienummer)` — sha256 over `ontvanger.toCanonicalString()` |
| `BerichtenCache.*`-methodes met `ontvanger: String`-params | idem | typed door |

`ophalenBerichten`-flow:
1. `trySetAggregationStatus(cacheKey, BEZIG)` — zoals nu.
2. `resolver.resolve(ontvanger).await().atMost(Duration.ofSeconds(20))` — sync vóór SSE-Multi-build. Timeout ruim boven worst-case 15.4 s retry-budget.
3. Bij `ProfielServiceFoutException`: `storeAggregationStatus(cacheKey, FOUT(0,0,0)).await()` (waarvan `BerichtenCache` regel 135 ook `del(lockKey)` doet → lock vrij) → werp door.
4. Filter `clientFactory.getAllClients()` op resolved-set → `clients`.
5. `bezigStatus.totaalMagazijnen = clients.size` (overschrijven via `storeAggregationStatus` is hier niet nodig; bestaande pipeline updatet 'm in finale GEREED-status).
6. Als `clients.isEmpty()` → `cache.store(cacheKey, emptyList())` + emit `OPHALEN_GEREED(0,0,0,0)` + `storeAggregationStatus(GEREED, 0, 0, 0)`. Cache-overschrijven is expliciet (anders blijft eventuele stale data uit eerdere ophaal-sessie zichtbaar via `getBerichten`/`zoekBerichten`).
7. Anders: bestaande magazijn-pipeline.

**`BerichtenOphalenResource`** + **`BerichtensessiecacheResource`**:
- Parse header met `Identificatienummer.fromHeader(headerString)` aan resource-grens. Service-aanroepen krijgen typed object.
- `DomainValidationException` van `fromHeader` → bestaande `ConstraintViolationException`-mapper of `DomainValidationExceptionMapper` (400 Problem+JSON). Bean Validation op de OpenAPI-pattern is de eerste defensie-laag — `fromHeader` is de tweede (defense-in-depth voor domein-invarianten zoals elfproef).
- **LDV `dataSubjectId`**: zet op `ontvanger.waarde` (rauwe BSN/RSIN/KVK/OIN-waarde), niet inclusief type-prefix. CLAUDE.md BSN/PII-sectie: LDV's `dataSubjectId` mag de waarde bevatten zolang het endpoint TLS gebruikt (BIO 13.2.1, afgedwongen door endpoint-validator).

**`berichtensessiecache-api.yaml`**:
- `OntvangerHeader.schema.pattern` = `^(BSN:[0-9]{9}|RSIN:[0-9]{9}|KVK:[0-9]{8}|OIN:[0-9]{20})$` (gelijk aan magazijn).
- `minLength: 12`, `maxLength: 24`.
- Pattern geldt **alle 6 endpoints** die `X-Ontvanger` consumeren (GET /berichten, GET /berichten/{id}, POST /berichten/ophalen, GET /berichten/zoeken, POST /berichten, PATCH /berichten/{id}/status). Eén gedeelde `OntvangerHeader`-parameter-reference is voldoende; controleer dat alle path-items er naar verwijzen.
- **Breaking change** voor bestaande callers die de bare-string-vorm gebruiken (vandaag dezelfde laxiteit). Documenteer in PR + CHANGELOG.
- Voeg 503-response **alleen op `POST /berichten/ophalen`** toe met Problem-schema + `Retry-After`-header. Overige endpoints raken Profiel-service niet en behouden hun huidige fout-set.
- Definieer `Retry-After`-response-header (numeriek of HTTP-date).

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

**`services/berichtensessiecache/src/test/resources/application.properties`** (en test-fixtures `MockedDependenciesProfile`, `WireMockTestProfile`, `RealRedisTestProfile`):
- Voeg `magazijnen.instances.magazijn-a.afzenders` + `.magazijn-b.afzenders` toe — anders faalt `MagazijnClientFactory.init()` op de fail-fast-check.
- Voeg `quarkus.rest-client.profiel-service.url=http://localhost:8089` toe (overschreven door dynamische `WireMockProfielServiceResource` per testklasse die de Profiel-stub nodig heeft).
- Mock-CDI-bean (`MockProfielServiceClient`) toevoegen, gespiegeld aan magazijn-test-setup, zodat unit-tests Profiel niet hoeven te benaderen.

### Wijziging in `berichtenmagazijn`

- Imports `nl.rijksoverheid.moz.fbs.berichtenmagazijn.opslag.{Identificatienummer,Bsn,Rsin,Kvk,Oin,IdentificatienummerType}` → `nl.rijksoverheid.moz.fbs.common.identificatie.*` (74 imports gemeten, alleen deze 6 symbolen). Per-symbool import-rewrite; geen wildcard, anders raken andere `opslag/`-types geraakt.
- Imports `nl.rijksoverheid.moz.fbs.berichtenmagazijn.validatie.{ProfielServiceClient,PartijResponse,VoorkeurResponse,ScopeResponse,IdentificatieResponse,DienstResponse,ToestemmingGeweigerdException}` → `nl.rijksoverheid.moz.fbs.common.profiel.*`.
- `ProfielServiceEndpointValidator` — verplaatst naar fbs-common (één bean per JVM, identieke logica voor alle services).
- Magazijn-test `ProfielServiceClientWireMockTest` blijft staan als consumer-contract-test; sessiecache krijgt een eigen variant. Beide draaien tegen lokale dynamische WireMock — voorkomt cross-service test-coupling.

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
- Header `BSN:999993653` met 200/lege profiel → SSE `OPHALEN_GEREED 0/0/0` + cache overschreven met lege lijst
- Header `BSN:999993653` met 500 profiel → 503 + `Retry-After`, `getAggregationStatus`-poll levert `FOUT`
- Header zonder `:`-prefix (`"999993653"`) → 400 Problem+JSON. Bean-Validation op OpenAPI-pattern is eerste laag; `fromHeader` is defense-in-depth voor het geval Bean-Validation niet geraakt wordt (bv. unit-tests die de resource direct aanroepen). Eén test forceert specifiek het `fromHeader`-pad door Bean-Validation te omzeilen.
- Header `OIN:00000001003214345000` → alle magazijnen, geen Profiel-call (verify WireMock niet aangeroepen)
- Bestaande tests met bare-string-header (`"cache-test"`, `"multi-ok-…"`) → moeten omgezet naar TYPE:WAARDE-vorm (`"BSN:999993653"` etc.). Inventariseer en update.

### Contract (`OpenApiContractTest` uitbreiden)

- 503-response matcht Problem-schema
- `X-Ontvanger`-regex valideert
- Spectral-linter: `npx @stoplight/spectral-cli lint berichtensessiecache-api.yaml --ruleset https://static.developer.overheid.nl/adr/ruleset.yaml` → 0 errors

### Bruno-collectie + WireMock-mappings

- Bestaande requests in `bruno/berichtensessiecache/`: update `X-Ontvanger` waarden naar `BSN:999993653`-formaat (zelfde BSN als magazijn-collectie gebruikt).
- Nieuw: `bruno/berichtensessiecache/berichten/ophalen-zonder-voorkeur.bru` — `X-Ontvanger: BSN:111222333` (matcht nieuwe `wiremock/profiel-service/mappings/get-partij-onbekend-404.json`).
- Nieuw: `bruno/berichtensessiecache/berichten/ophalen-profiel-fout.bru` — `X-Ontvanger: BSN:444555666` (matcht nieuwe `wiremock/profiel-service/mappings/get-partij-server-error-500.json`).
- Bestaande `wiremock/profiel-service/mappings/get-partij-actieve-voorkeur.json` blijft voor happy-path; voeg twee nieuwe mappings toe:
  - `get-partij-onbekend-404.json` — 404 op specifieke BSN.
  - `get-partij-server-error-500.json` — 500 op specifieke BSN.

### Coverage

ProfielMagazijnResolver bereikbaar via `@QuarkusTest`-integratietest → telt mee voor `quarkus-jacoco` 90%-drempel.

## Verificatie

```bash
./mvnw clean verify -pl libraries/fbs-common -am
./mvnw clean verify -pl services/berichtenmagazijn -am   # 74 import-rewrites
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

## Aandachtspunten code-review (2026-05-26)

Verwerkt in dit document na review-iteratie:
- 74 (niet 75) import-rewrites in magazijn-module, alleen 6 symbolen uit `opslag/Identificatienummer.kt`.
- `fbs-common/pom.xml` krijgt expliciete dependencies voor REST-client + fault-tolerance.
- `ProfielServiceFoutException` + `ToestemmingGeweigerdException` + mappers naar `fbs-common` (gedeeld tussen magazijn en sessiecache).
- Test-fixtures + `src/test/resources/application.properties` updaten voor verplichte `magazijnen.*.afzenders`.
- Service-laag en `BerichtenCache.cacheKey` consistent typed naar `Identificatienummer` om cache-mismatch te voorkomen.
- `storeAggregationStatus` doet al `del(lockKey)` (`BerichtenCache.kt:135`) — geen extra cleanup nodig bij fail-fast.
- Profiel-type-mapping via expliciete `when`, niet `.name` (loskoppeling enum vs extern contract).
- 503 alleen op `POST /berichten/ophalen`; overige endpoints blijven Profiel-onafhankelijk.
- Non-404 4xx-respons → behandelen als infra-fout (`ProfielServiceFoutException`).
- LDV `dataSubjectId` = `ontvanger.waarde` (zonder type-prefix).
- `ProfielServiceEndpointValidator` als enkele bean in fbs-common (was per-service wrapper).
- Bruno + WireMock-mapping-bestanden expliciet benoemd.

Bewust **niet** verwerkt:
- `dienst`-filter (alleen `scope.dienst` ipv breder voorkeur-model). Geen concrete eis nu; opvolg-ticket bij toekomstige aanleiding.
