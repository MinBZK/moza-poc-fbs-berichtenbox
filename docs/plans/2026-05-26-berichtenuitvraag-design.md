**Status:** Concept

# Berichten Uitvraag Service — ontwerp

Issue: [#413](https://github.com/MinBZK/MijnOverheidZakelijk/issues/413) · Bron: `docs/plans/2026-03-30-c4-implementatie-issues.md` (Issue 5)

## Context

Nieuwe Quarkus-module `services/berichtenuitvraag` als deployable unit voor het Berichten Uitvraag Systeem (excl. sessiecache). Bevat in deze iteratie alleen de Berichten Uitvraag Service (C4-container `uitvraagApi`); de Aanmeld Service (#417) krijgt later een eigen package binnen dezelfde module.

C4-componenten gedekt: `uitvraagResource`, `uitvraagBerichtenlijst`, `uitvraagOphaalService`, `uitvraagBeheerService`.

**Dependencies vervuld:**
- Sessiecache heeft schrijf-endpoints (`POST /berichten`, `PATCH /berichten/{id}`) — Issue 1.
- Magazijn Ophaal- en Beheer-API is gemerged (commit `34fac82`) — Issue 3; DELETE is reeds idempotent (`BerichtBeheerService.kt:69`).
- FSC-infra (Issue 11) nog niet geïntegreerd → magazijn-client direct over HTTP met `TODO(#11)`.
- JWT/OIDC (Issue 6) nog niet aanwezig → `X-Ontvanger`-header blijft.

## Module-structuur

```
services/berichtenuitvraag/
├── pom.xml
├── src/main/resources/
│   ├── openapi/berichtenuitvraag-api.yaml
│   └── application.properties
├── src/main/kotlin/nl/rijksoverheid/moz/fbs/berichtenuitvraag/
│   ├── uitvraag/
│   │   ├── UitvraagResource.kt
│   │   ├── BerichtenlijstService.kt
│   │   ├── BerichtOphaalService.kt
│   │   ├── BerichtBeheerService.kt
│   │   ├── SessiecacheClient.kt
│   │   └── MagazijnClient.kt
│   └── BerichtenuitvraagApiVersionProvider.kt
└── src/test/kotlin/...
```

Flat onder `uitvraag/` — weinig klassen, geen sub-packages. Aanmeld-package wordt door #417 toegevoegd wanneer die service geïmplementeerd wordt.

Geen eigen database (geen Flyway/Hibernate/Panache). Dependencies: `quarkus-rest`, `quarkus-rest-jackson`, `quarkus-rest-client`, `quarkus-smallrye-openapi`, `fbs-common`, `quarkus-arc`. Test: `quarkus-junit5`, `rest-assured`, `wiremock`, `mockk`, `swagger-request-validator-restassured`.

## API-contract (`berichtenuitvraag-api.yaml`)

`/api/v1` prefix, camelCase JSON, `application/problem+json` voor fouten, `API-Version` response-header, HAL `_links`. Spec wordt gevalideerd tegen de ADR Spectral-ruleset.

| Method | Path | Body / Headers | Response |
|---|---|---|---|
| GET | `/berichten?map=...&pagina=...` | `X-Ontvanger` | `BerichtenLijst` |
| GET | `/berichten/_ophalen` | `X-Ontvanger` | SSE; vult cache voor alle mappen (geen query-params) |
| GET | `/berichten/_zoeken?q=...&map=...` | `X-Ontvanger` | `BerichtenLijst` |
| GET | `/berichten/{berichtId}` | `X-Ontvanger` | `Bericht` (inhoud + bijlage-metadata) |
| GET | `/berichten/{berichtId}/bijlagen/{bijlageId}` | `X-Ontvanger` | `*/*` octet-stream |
| PATCH | `/berichten/{berichtId}` | `X-Ontvanger`, `application/merge-patch+json`, `BerichtPatch` | `Bericht` |
| DELETE | `/berichten/{berichtId}` | `X-Ontvanger` | `204` |

**Schemas (essentie):**
- `Bericht` — overgenomen vorm uit sessiecache, aangevuld met `map: string` en bijlage-metadata.
- `BerichtPatch` = `{ status?: "gelezen"|"ongelezen", map?: string (1..64) }`. JSON Merge Patch (RFC 7396).
- `BerichtenLijst`, `BerichtSamenvatting`, `BijlageMetadata`, `Problem`, `Link`.

**Veld-keuzes:**
- Status als enum `gelezen|ongelezen` (zoals sessiecache), niet boolean (zoals magazijn). Reden: voor het portaal duidelijker, `null` (onbekend) blijft expliciet uitdrukbaar. `MagazijnClient` mapt intern naar/van `gelezen: boolean`.
- `/berichten/_ophalen` zonder parameters — vult cache voor alle mappen. Semantiek = cache-warming, niet map-specifiek ophalen.

## Service-laag

### SessiecacheClient
Quarkus REST-client → `http://berichtensessiecache:8080/api/v1`.
- `lijst(ontvanger, map, pagina) → BerichtenLijst`
- `ophalen(ontvanger) → Multi<SseEvent>` — passthrough SSE
- `zoek(ontvanger, q, map) → BerichtenLijst`
- `bericht(ontvanger, id) → Bericht`
- `patchBericht(ontvanger, id, patch) → Bericht`
- `verwijderBericht(ontvanger, id)`
- `invalideerKey(ontvanger, id)` — voor compensatie-pad (mag identiek zijn aan `verwijderBericht` op cache-niveau)

### MagazijnClient
Quarkus REST-client → `http://berichtenmagazijn:8080/api/v1` (`TODO(#11)`: vervangen door FSC outway).
- `patchBericht(ontvanger, id, patch) → Bericht`
- `verwijderBericht(ontvanger, id)` — idempotent
- `bijlage(ontvanger, berichtId, bijlageId) → (InputStream, headers)`

### Orchestratie-services

- `BerichtenlijstService`: thin pass-through naar sessiecache voor `lijst`, `ophalen` (SSE), `zoek`.
- `BerichtOphaalService`:
  - `haalBericht(...)` → sessiecache
  - `haalBijlage(...)` → magazijn stream-passthrough. Pattern uit `BijlageContentTypeFilter` (magazijn) wordt gekopieerd; nog niet naar `fbs-common` verplaatsen — pas bij derde gebruiker. Content-Type en Content-Disposition van magazijn worden overgenomen.
- `BerichtBeheerService` (dual-write):

  ```
  patch(ontvanger, id, patch):
      magazijn.patchBericht(...)              # idempotent; faalt → 5xx terug, cache niet aangeraakt
      try:
          bericht = sessiecache.patchBericht(...)
          return bericht                       # 200 OK
      catch cacheFault:
          try { sessiecache.invalideerKey(ontvanger, id) }   # best-effort
          catch e:    log.warn(...)
          throw 502_BadGateway("cache-update faalde; magazijn bijgewerkt")
  ```

  Idem voor `verwijder` (return `204` op succes, `502` op cache-faal-na-magazijn-OK).

### Dual-write — contract en consistentie

- **Magazijn is bron van waarheid.** Sessiecache is afgeleid en zelfherstellend via TTL (60s).
- **Magazijn-faal** vóór cache-stap → cache niet aangeraakt; 5xx terug naar client.
- **Cache-faal** na magazijn-OK → best-effort `invalideerKey` (DELETE cache-entry, vervangt 'stale' door 'leeg'); daarna 502 terug. Volgende read herlaadt vers uit magazijn.
- **Client-retry-contract:** alle PATCH/DELETE op uitvraag-API zijn idempotent. Client mag veilig retryen. Magazijn-DELETE geeft 204 ook bij tweede DELETE door dezelfde ontvanger (al geïmplementeerd); 404 alleen als bericht nooit bestond.
- Geen retry/circuit-breaker in deze service in deze PR (YAGNI). De Fault-Tolerance-dependency wordt niet meegenomen tot er een concrete aanleiding is.

### Foutafhandeling

- Client-fouten (`WebApplicationException` van REST-client) propageren naar `ProblemExceptionMapper` (fbs-common).
- `ProcessingException` (connectiefouten, timeouts) → mapper → 502/504 Problem.
- Cache-compensatie-mislukking → `warn`-log met correlation-id, request resulteert in 502.

## Tests

Conform project-teststrategie (zie CLAUDE.md), met integratietests omdat `quarkus-jacoco` pure unit-tests niet meetelt richting de 90%-drempel.

**Unit (MockK):**
- `BerichtBeheerService` dual-write paden:
  - magazijn-OK + cache-OK → 200/204
  - magazijn-FAIL → 5xx, cache nooit aangeroepen
  - magazijn-OK + cache-FAIL + invalidate-OK → 502, invalidate aangeroepen
  - magazijn-OK + cache-FAIL + invalidate-FAIL → 502, beide gefaalde calls gelogd
- `BerichtenlijstService` orchestratie (delegatie naar client met juiste headers).
- Content-Type mapping voor bijlagen.

**WireMock-contract** voor `SessiecacheClient` en `MagazijnClient`:
- 200, 404, 403, 5xx, connection-timeout, malformed JSON.

**`@QuarkusTest` integratie** (`UitvraagResourceIT`):
- Happy-path elke endpoint, response-validatie tegen OpenAPI via `swagger-request-validator-restassured`.
- Dual-write fail-modes via WireMock-stubs voor beide backends.
- Bijlage stream-passthrough met juiste Content-Type en Content-Disposition.
- SSE-proxy van `/_ophalen`: events doorgestuurd, geen buffering.

**OpenApiContractTest** valideert alle response-shapes tegen `berichtenuitvraag-api.yaml`, inclusief Problem-responses.

**Coverage:** ≥90% lijn-coverage (project-default). Code in `api.**` (gegenereerd) blijft uitgesloten via JaCoCo BUNDLE-exclude.

## compose.yaml

```yaml
berichtenuitvraag:
  build: ./services/berichtenuitvraag/src/main/docker
  ports: ["8082:8080"]                   # 8080 sessiecache, 8081 magazijn, 8082 uitvraag
  environment:
    QUARKUS_REST_CLIENT_SESSIECACHE_URL: http://berichtensessiecache:8080
    QUARKUS_REST_CLIENT_MAGAZIJN_URL:    http://berichtenmagazijn:8080
  depends_on: [berichtensessiecache, berichtenmagazijn]
```

## Bruno-collectie

`bruno/berichtenuitvraag/` met `bruno.json`, `environments/lokaal.bru` en één `.bru`-request per endpoint (conform conventie in CLAUDE.md).

## Acceptatiecriteria-mapping (#413)

| Criterium | Dekking |
|---|---|
| Maven module `services/berichtenuitvraag` in parent POM | sectie *Module-structuur*, `pom.xml` |
| Package-structuur voorbereid op Aanmeld Service | **expliciet weggelaten** — Aanmeld krijgt eigen package via #417 |
| OpenAPI spec met endpoints | sectie *API-contract* (+ `_ophalen` toegevoegd) |
| OpenAPI valideert tegen ADR Spectral-ruleset | onderdeel van plan-uitvoering |
| Verplaatsen als PATCH met `map` in body | `BerichtPatch` schema |
| REST client naar berichtensessiecache | `SessiecacheClient` |
| REST client naar berichtenmagazijn via FSC | `MagazijnClient` met `TODO(#11)` |
| `BerichtenlijstService`, `BerichtOphaalService`, `BerichtBeheerService` | sectie *Service-laag* |
| Problem JSON, API-Version header, security headers, LDV logging | hergebruik `fbs-common` |
| Unit tests en integratietests | sectie *Tests* |
| `compose.yaml` bijgewerkt | sectie *compose.yaml* |

## Bewuste afwijkingen van het issue

- **`_ophalen` toegevoegd** ten opzichte van de issue-lijst — vult cache voor alle mappen, gespiegeld aan sessiecache.
- **Aanmeld-package-skeleton weggelaten** — #417 voegt zijn eigen package toe wanneer die service gebouwd wordt.

## Open punten voor implementatieplan

- Concrete pom-configuratie voor `jaxrs-spec` generator (interfaceOnly, mapping van Problem-types).
- Mapping tussen sessiecache-enum `gelezen|ongelezen` en magazijn-boolean `gelezen` (locatie: `MagazijnClient` of een aparte mapper).
- LDV-logging: per endpoint één activiteit; hergebruik `ProcessingActivities`-patroon uit magazijn.
- Stream-passthrough voor bijlagen — bevestigen dat Quarkus REST-client `InputStream` als response-type ondersteunt zonder volledige buffering; zo niet, alternatief uitwerken.
