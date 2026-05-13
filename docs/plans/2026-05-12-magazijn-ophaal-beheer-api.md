**Status:** Concept
**Beoogd pad bij oplevering:** `docs/plans/2026-05-12-magazijn-ophaal-beheer-api.md`

# Plan: Issue 3 — Berichtenmagazijn Ophaal- en Beheer API

## Context

Het Federatief Berichtenstelsel (C4-model in `docs/architecture/workspace.dsl`) heeft een berichtenmagazijn met een Aanlever-API (Issue 2 ✓) maar nog geen Ophaal- en Beheer-API. Daardoor kan de berichtensessiecache momenteel geen berichten ophalen bij echte magazijnen (de `MagazijnClient` wordt wel via WireMock gestubd), en kan de toekomstige uitvraag-service (Issue 5) geen bijlagen of statuswijzigingen verwerken.

Issue 3 voegt deze API toe als package `ophaal/` binnen de bestaande `services/berichtenmagazijn` module. De `MagazijnClient` in de sessiecache is al header-based (`X-Ontvanger`) en `zoekBerichten()` zit niet in de interface — de in Issue 3 beschreven breaking changes zijn dus al doorgevoerd. Issue 1 afwijking 1+2 (aggregatie in service, schrijf-endpoints) is ook al klaar. **Issue 3 is daarmee zuiver werk in de magazijn-module.**

**Ontwerpkeuzes (na overleg):**
- Bijlagen: minimale `Bijlage` entity met bytes-content.
- Berichtstatus: aparte `BerichtStatus`-entity per (berichtId, ontvanger) — toekomstvast.
- DELETE: soft-delete via `verwijderdOp: Instant?`.
- OpenAPI: bestaande `berichtenmagazijn-api.yaml` uitbreiden.

## Scope

**In scope:**
- 5 nieuwe endpoints in OpenAPI spec (GET-list, GET-one, GET-bijlage, PATCH, DELETE)
- `OphaalBeheerResource` als implementatie van de gegenereerde interface
- `BerichtOphaalService` + `BerichtBeheerService` (CDI beans, scheiding read vs. write)
- Nieuwe entity `BijlageEntity` (with bytes) + `BerichtStatusEntity` (per ontvanger)
- Soft-delete kolom `verwijderdOp` op `BerichtEntity`
- Repository-uitbreidingen: `findByOntvanger`, `findByBerichtIdAndOntvanger`, status-upsert, soft-delete
- Bruno-requests onder `bruno/berichtenmagazijn/`
- LDV `@Logboek` annotaties op alle endpoints
- Unit- en integratietests (happy + unhappy, contract-tests, autorisatie)

**Expliciet buiten scope (volgt in latere issues):**
- Autorisatie via JAX-RS interceptor (Issue 10). PoC-validatie: ontvanger-match in service-laag (403 als ontvanger uit `X-Ontvanger` header niet matcht).
- JWT/Token validatie (Issue 6).
- BSNk pseudoniem-transformatie (Issue 7).

## Bestanden

### Wijzigen
- `services/berichtenmagazijn/src/main/resources/openapi/berichtenmagazijn-api.yaml` — endpoints + schema's `Bijlage`, `BerichtMetInhoud`, `BerichtStatusPatch`; gedeeld `X-Ontvanger`-header parameter
- `services/berichtenmagazijn/src/main/kotlin/.../opslag/BerichtEntity.kt` — kolom `verwijderdOp: Instant?`, relatie `bijlagen: List<BijlageEntity>`
- `services/berichtenmagazijn/src/main/kotlin/.../opslag/BerichtRepository.kt` — query-methodes
- `services/berichtenmagazijn/src/main/kotlin/.../opslag/Bericht.kt` — domeinmodel uitgebreid met `bijlagen` (metadata-list) en `status`
- `services/berichtenmagazijn/src/main/kotlin/.../aanlever/BerichtOpslagService.kt` — slaat bijlagen mee op (als request ze bevat); `verwijderdOp` blijft `null`
- `services/berichtenmagazijn/src/main/kotlin/.../aanlever/AanleverResource.kt` — geen functionele wijziging; herzien op gedeelde mapping-helpers

### Nieuw
- `.../ophaal/OphaalBeheerResource.kt` — implementeert gegenereerde `BerichtenApi`-interface voor de 5 endpoints
- `.../ophaal/BerichtOphaalService.kt` — read-only orkestratie (ontvanger-filter, status-merge, soft-delete-filter)
- `.../ophaal/BerichtBeheerService.kt` — write (PATCH status, DELETE soft-delete)
- `.../opslag/BijlageEntity.kt` — JPA entity (id, berichtId FK, naam, mimeType, content: ByteArray)
- `.../opslag/BijlageRepository.kt` — `findByBerichtIdAndBijlageId(UUID, UUID): BijlageEntity?`
- `.../opslag/BerichtStatusEntity.kt` — JPA entity (composite key berichtId+ontvanger, gelezen: Boolean, map: String, gewijzigdOp: Instant)
- `.../opslag/BerichtStatusRepository.kt` — `findByBerichtIdAndOntvanger`, `upsert`
- `.../ophaal/NotFoundExceptionMapper.kt` — alleen toevoegen indien nog niet via fbs-common `ProblemExceptionMapper` afgevangen
- `bruno/berichtenmagazijn/getBerichten.bru`, `getBerichtById.bru`, `getBijlage.bru`, `updateStatus.bru`, `deleteBericht.bru`
- `services/berichtenmagazijn/src/test/kotlin/.../ophaal/OphaalBeheerResourceIntegrationTest.kt`
- `services/berichtenmagazijn/src/test/kotlin/.../ophaal/BerichtOphaalServiceTest.kt`
- `services/berichtenmagazijn/src/test/kotlin/.../ophaal/BerichtBeheerServiceTest.kt`
- `services/berichtenmagazijn/src/test/kotlin/.../ophaal/OphaalBeheerOpenApiContractTest.kt`

### Hergebruik (niet nieuw bouwen)
- `libraries/fbs-common/.../exception/ProblemExceptionMapper.kt` en `ProblemResponses.kt` — voor 404/403/500
- `libraries/fbs-common/.../SecurityHeadersFilter.kt`, `ApiVersionFilter.kt`, `CacheControlFilter.kt` — al geactiveerd via fbs-common
- `services/berichtenmagazijn/.../opslag/Identificatienummer.kt` — bestaande BSN/Oin/Rsin/Kvk value classes voor afzender/ontvanger
- `services/berichtenmagazijn/.../aanlever/DbConstraintViolationExceptionMapper.kt` — patroon voor DB-fouten (eventueel hergebruik bij PATCH/DELETE)
- LDV `@Logboek` annotatie zoals in `AanleverResource.kt`

## Endpoints (samenvatting)

| Methode | Pad | Headers | Body | Response |
|---|---|---|---|---|
| GET | `/api/v1/berichten` | `X-Ontvanger` (verplicht) | – | `BerichtenLijst` met `_links` |
| GET | `/api/v1/berichten/{berichtId}` | `X-Ontvanger` | – | `BerichtMetInhoud` (incl. bijlagen-metadata) |
| GET | `/api/v1/berichten/{berichtId}/bijlagen/{bijlageId}` | `X-Ontvanger` | – | `application/pdf` of `application/octet-stream` (bytes) |
| PATCH | `/api/v1/berichten/{berichtId}` | `X-Ontvanger`, `Content-Type: application/merge-patch+json` | `{ "gelezen": true, "map": "archief" }` | `BerichtMetStatus` |
| DELETE | `/api/v1/berichten/{berichtId}` | `X-Ontvanger` | – | 204 No Content |

**Autorisatie (PoC):** in de service-laag valideren dat de ontvanger uit de header gelijk is aan de ontvanger op het bericht. Mismatch → 403 met `Problem`-response, gemaskeerde logging. Echte AuthZEN PEP komt in Issue 10.

## Stappen

1. **OpenAPI spec uitbreiden** — endpoints, schema's, `X-Ontvanger`-header als gedeelde parameter, `application/merge-patch+json` content-type voor PATCH. Valideren tegen ADR Spectral linter.
2. **DB-model uitbreiden** — `verwijderdOp` op `BerichtEntity`; `BijlageEntity` (met `@Lob` op `content`); `BerichtStatusEntity` met composite key. Hibernate `hbm2ddl.auto=update` (al actief in dev) maakt schema-migratie automatisch; voor prod-pad zou Flyway nodig zijn maar dat is buiten PoC-scope.
3. **Repositories** — Panache query-methodes; eerst tests, dan implementatie (TDD).
4. **Domein-mapping** — `Bericht` uitbreiden met `bijlagen: List<BijlageMetadata>` en `status: BerichtStatus?`. `BerichtMapper`/-helper om `Entity → Bericht` te vertalen (status optioneel meeladen).
5. **Services** — `BerichtOphaalService` (filter `verwijderdOp == null`, autorisatie-check op ontvanger-match, status-merge); `BerichtBeheerService` (PATCH = upsert in `BerichtStatusEntity`; DELETE = `verwijderdOp = now()`).
6. **Resource** — `OphaalBeheerResource` implementeert gegenereerde JAX-RS interface; LDV `@Logboek` met juiste `processingActivityId`; PII-masking in logs.
7. **Bijlage-streaming** — `Response.ok(bytes).type(mimeType)` voor de byte-uitlevering. PoC: in-memory uit DB; bij grote bijlagen later StreamingOutput.
8. **Bruno-collectie** — requests per endpoint onder `bruno/berichtenmagazijn/` met `X-Ontvanger` env-var.
9. **Tests** — unit (mapping, status-merge, autorisatie-check), integratie (`@QuarkusTest` met H2 + REST-assured), OpenAPI contract-test (Swagger Request Validator), 404/403/400/204-paden.
10. **Coverage controleren** — JaCoCo ≥ 90% line coverage; gegenereerde `api.*` uitsluiten.

## Verificatie

```bash
# 1. OpenAPI spec lint (lokaal of via CI)
npx @stoplight/spectral-cli lint services/berichtenmagazijn/src/main/resources/openapi/berichtenmagazijn-api.yaml \
  --ruleset https://static.developer.overheid.nl/adr/ruleset.yaml

# 2. Compileren + tests (Maven Host MCP met Ryuk uit per memory-notitie)
./mvnw test -pl services/berichtenmagazijn -am

# 3. Coverage rapport bekijken
open services/berichtenmagazijn/target/site/jacoco/index.html

# 4. End-to-end handmatig via Bruno
./mvnw quarkus:dev -pl services/berichtenmagazijn
# POST aanleveren → GET /berichten (X-Ontvanger=…) → GET /berichten/{id} → PATCH status → GET → DELETE → GET (404)
```

**Acceptatie:**
- Alle 5 endpoints reageren conform OpenAPI spec; contract-test groen.
- Soft-delete: na DELETE geeft GET 404 voor die ontvanger; rij staat nog in DB met `verwijderdOp ≠ null`.
- PATCH met `{ "gelezen": true }` zet status; opnieuw ophalen toont `gelezen: true`.
- GET met mismatched `X-Ontvanger` → 403, geen PII in logs.
- Bijlage-GET levert bytes met juist `Content-Type`.
- JaCoCo ≥ 90% (excl. `api.*`).
