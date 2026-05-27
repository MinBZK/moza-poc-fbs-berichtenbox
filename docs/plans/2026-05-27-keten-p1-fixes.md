**Status:** Concept

# Keten P1-fixes: sessiecache uitbreiden voor uitvraag-detail + dual-write

Bron: `docs/plans/2026-05-27-keten-gap-analyse.md` (P1 1-4).

## Doel

Maak de uitvraag-keten werkbaar voor alle 6 endpoints. Vier blokkers:

1. Sessiecache mist `DELETE /berichten/{id}` (dual-write-invalidate)
2. Sessiecache `BerichtResponse` mist `inhoud` + `bijlagen[]` (uitvraag detail-GET)
3. Sessiecache PATCH eist required `status` en heeft geen `map` (alleen-verplaatsen onmogelijk)
4. PATCH enum↔boolean mapping nergens gespecificeerd

## Design-keuzes

- **Cache uitbreiden** met `inhoud: string` en `bijlagen: [{bijlageId, naam}]` (lichte vorm — alleen ID + naam, geen mimeType/grootte). De volledige `BijlageMetadata` blijft alleen in magazijn; download blijft via uitvraag's bijlage-endpoint dat magazijn-direct streamt.
- **PATCH-mapping** doet de uitvraag-service (`UitvraagDtoMapper.toMagazijnPatch` — bestaat al). Cache krijgt de uitvraag-`BerichtPatch` (zelfde enum-vorm) ongewijzigd door; magazijn krijgt boolean-vorm.
- **Cache PATCH**: `status` wordt optioneel; `map`-veld toegevoegd; `minProperties: 1`.
- **Cache DELETE**: `DELETE /berichten/{berichtId}` met 204/404/500. Idempotent (tweede call → 204).

## Volgorde

Sessiecache eerst (spec + code + WireMock + tests), dan uitvraag-verifieren, dan end-to-end verify. Eén branch, één PR.

---

## Task 1 — Sessiecache OpenAPI-spec uitbreiden

**File:** `services/berichtensessiecache/src/main/resources/openapi/berichtensessiecache-api.yaml`

### 1a — Schemas uitbreiden

In `Bericht`-schema (regel ~321): voeg `inhoud` en `bijlagen[]` toe.

- [ ] Voeg property toe:
  ```yaml
        inhoud:
          type: string
          description: Tekstuele inhoud van het bericht
  ```
- [ ] Voeg property toe ná `aantalBijlagen`:
  ```yaml
        bijlagen:
          type: array
          description: Lijst van bijlagen (alleen identifier en naam; bytes via magazijn)
          items:
            $ref: '#/components/schemas/BijlageSamenvatting'
  ```
- [ ] Voeg property `map` toe (top-level, zoals uitvraag):
  ```yaml
        map:
          type: string
          minLength: 1
          maxLength: 64
          description: Map waarin het bericht is geplaatst door de ontvanger
  ```
- [ ] `required`-lijst aanvullen: `inhoud` toevoegen (verplicht); `bijlagen` en `map` blijven optioneel (een bericht kan geen bijlagen hebben; map mag ontbreken).

In `BerichtInput`-schema (regel ~515): identieke uitbreidingen (wat aanmeld-service binnenkrijgt moet ook deze velden hebben).

In `BerichtResponse`-schema (regel ~478): identieke uitbreidingen (wat sessiecache uitlevert aan uitvraag).

### 1b — Nieuw `BijlageSamenvatting`-schema

- [ ] Voeg toe onder `components.schemas`:
  ```yaml
      BijlageSamenvatting:
        type: object
        description: |
          Lichte representatie van een bijlage in de cache: alleen identifier
          en naam zodat de portal kan opsommen en doorklikken. Volledige
          metadata en bytes blijven bij het magazijn.
        required:
          - bijlageId
          - naam
        properties:
          bijlageId:
            type: string
            format: uuid
            description: Uniek ID; te gebruiken in bijlage-download-endpoint
          naam:
            type: string
            minLength: 1
            maxLength: 255
  ```

### 1c — PATCH-body herzien

`BerichtStatusUpdate` (regel ~517) wordt herzien zodat `map` ook PATCH-baar is en `status` optioneel.

- [ ] Hernoem schema naar `BerichtPatch` (consistent met magazijn/uitvraag) of behoud `BerichtStatusUpdate` en pas inhoud aan. **Kies: behoud `BerichtStatusUpdate`** om aanmeld-service-contract niet te breken — alleen interne shape wordt soepeler.
- [ ] Schema:
  ```yaml
      BerichtStatusUpdate:
        type: object
        description: |
          JSON Merge Patch (RFC 7396). Beide velden optioneel; minimaal één
          veld vereist. `status` wijzigt leesstatus; `map` verplaatst.
        properties:
          status:
            $ref: '#/components/schemas/BerichtStatus'
          map:
            type: string
            minLength: 1
            maxLength: 64
        minProperties: 1
  ```
- [ ] PATCH-endpoint (`patch:` onder `/berichten/{berichtId}`): content-type blijft `application/merge-patch+json`. Response: laat `BerichtResponse` staan.

### 1d — DELETE-endpoint toevoegen

- [ ] Onder `paths:/berichten/{berichtId}:`, voeg toe ná `patch:`:
  ```yaml
      delete:
        tags: [berichten]
        summary: Bericht uit cache verwijderen (cache-invalidate)
        description: |
          Verwijdert het bericht uit de cache. Idempotent: tweede call door
          dezelfde ontvanger geeft 204 (niet 404). 404 alleen als het
          bericht nooit in de cache zat.
        operationId: verwijderBericht
        parameters:
          - $ref: '#/components/parameters/OntvangerHeader'
        responses:
          '204':
            description: Verwijderd (of niet aanwezig)
            headers:
              API-Version: { $ref: '#/components/headers/API-Version' }
          '401': { $ref: '#/components/responses/Unauthorized' }
          '500': { $ref: '#/components/responses/InternalServerError' }
  ```
  > **Bewust geen 404**: dual-write-flow stuurt invalidate naar cache nadat magazijn al verwijderd is; "niet in cache" is een normaal geval en betekent niet "fout".

### 1e — Verifieer

- [ ] Spectral 0 errors:
  ```
  npx -y @stoplight/spectral-cli lint services/berichtensessiecache/src/main/resources/openapi/berichtensessiecache-api.yaml --ruleset https://static.developer.overheid.nl/adr/ruleset.yaml
  ```
- [ ] Compile: `./mvnw clean compile -pl services/berichtensessiecache -am` — gegenereerde DTO's bevatten `getInhoud()`, `getBijlagen()`, `getMap()`, `BijlageSamenvatting`-class, en `BerichtStatusUpdate.getMap()`.

### 1f — Commit

```
git add services/berichtensessiecache/src/main/resources/openapi/
git commit -m "$(cat <<'EOF'
feat(sessiecache): uitbreiden spec met inhoud, bijlagen, map + DELETE

P1-fixes uit gap-analyse:
- Bericht/BerichtInput/BerichtResponse krijgen inhoud + bijlagen[] + map
- BijlageSamenvatting (bijlageId + naam) — bytes blijven bij magazijn
- BerichtStatusUpdate: status optioneel, map toegevoegd, minProperties 1
- DELETE /berichten/{id} voor cache-invalidate (idempotent, geen 404)

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

## Task 2 — Sessiecache: domein-class + cache-payload uitbreiden

**Files:**
- `services/berichtensessiecache/src/main/kotlin/.../berichten/Bericht.kt`
- `services/berichtensessiecache/src/main/kotlin/.../berichten/BerichtenCache.kt`
- `services/berichtensessiecache/src/main/kotlin/.../berichten/Bijlage.kt` (nieuw, of inline data class)
- Test-fixtures

### 2a — Bericht-domein

- [ ] Voeg properties toe aan `Bericht`-class:
  ```kotlin
  val inhoud: String,
  val bijlagen: List<BijlageSamenvatting> = emptyList(),
  val map: String? = null,
  ```
- [ ] Update `require(...)`-validatie:
  ```kotlin
  init {
      require(inhoud.isNotBlank()) { "inhoud mag niet leeg zijn" }
      require(bijlagen.size <= 100) { "Maximaal 100 bijlagen per bericht" }
      map?.let { require(it.length in 1..64) { "map-naam 1-64 karakters" } }
      // ... bestaande requires
  }
  ```
- [ ] Maak `BijlageSamenvatting`-data-class (in `berichten/`-package):
  ```kotlin
  data class BijlageSamenvatting(
      val bijlageId: UUID,
      val naam: String,
  ) {
      init {
          require(naam.isNotBlank() && naam.length in 1..255) { "bijlage-naam 1-255 karakters" }
      }
  }
  ```

### 2b — Cache-serialisatie (Redis-hash)

`BerichtenCache.berichtToHash` / `hashToBericht` / `documentToBericht`:

- [ ] Schrijf `inhoud` als hash-field.
- [ ] Schrijf `map` als hash-field (skip als null).
- [ ] Schrijf `bijlagen` als JSON-string (gebruik Jackson om de lijst te serializeren — bestaande pattern volgen).
- [ ] Lees-paden: voeg fallback `?: ""` op `inhoud` en `?: emptyList()` op `bijlagen` voor backwards-compat met oude cache-entries.

### 2c — Resource-mapping uitbreiden

`BerichtensessiecacheResource.kt`:

- [ ] `toApiModel` en `toResponse` propageren nieuwe velden naar `Bericht`/`BerichtResponse` DTO.
- [ ] `BerichtInput → Bericht` (in `addBericht`) leest nieuwe velden uit input.

### 2d — Tests

- [ ] Update `Bericht`-builders in test-fixtures (`MockMagazijnClientFactory`, fuzzer, repository-test-data).
- [ ] Update assertions waar `Bericht` wordt vergeleken (extra velden bewaren).
- [ ] Voeg `BerichtTest`-cases toe voor de nieuwe validaties (`inhoud` leeg, bijlage-naam te lang, etc.).

### 2e — Verify

- [ ] `./mvnw clean test -pl services/berichtensessiecache -am` (skip JaCoCo voorlopig).

### 2f — Commit

```
git add services/berichtensessiecache/src/main/kotlin/ \
        services/berichtensessiecache/src/test/kotlin/
git commit -m "$(cat <<'EOF'
feat(sessiecache): domein-uitbreiding inhoud + bijlagen + map

Cache-payload draagt nu het volledige bericht zoals het magazijn het
levert (inhoud-tekst + bijlage-IDs+namen + map). BijlageSamenvatting
zit in berichten-package; bytes blijven exclusief in magazijn.

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

## Task 3 — Sessiecache: magazijn-client mapping voor nieuwe velden

**File:** `services/berichtensessiecache/src/main/kotlin/.../magazijn/MagazijnBericht.kt`

Magazijn-`Bericht`-spec heeft `inhoud` (string), `bijlagen[]` (BijlageMetadata met bijlageId/naam/mimeType/`_links`) en `status: BerichtStatusInfo {gelezen, map, gewijzigdOp}`. We nemen uit `bijlagen[]` alleen `bijlageId` + `naam` over, en uit `status` alleen `map` (status zelf wordt al verwerkt).

### 3a — DTO uitbreiden

- [ ] Voeg toe aan `MagazijnBericht`:
  ```kotlin
  @param:JsonProperty("inhoud") val inhoud: String,
  @param:JsonProperty("bijlagen") val bijlagen: List<MagazijnBijlage> = emptyList(),
  @param:JsonProperty("status") val status: MagazijnBerichtStatus? = null,
  ```
- [ ] Geneste types:
  ```kotlin
  data class MagazijnBijlage @JsonCreator constructor(
      @param:JsonProperty("bijlageId") val bijlageId: UUID,
      @param:JsonProperty("naam") val naam: String,
      // mimeType, _links worden genegeerd via @JsonIgnoreProperties (al class-level gezet)
  )

  data class MagazijnBerichtStatus @JsonCreator constructor(
      @param:JsonProperty("gelezen") val gelezen: Boolean? = null,
      @param:JsonProperty("map") val map: String? = null,
      // gewijzigdOp genegeerd
  )
  ```

### 3b — Mapper uitbreiden

- [ ] `toBericht(magazijnId)` mapt:
  ```kotlin
  Bericht(
      // ...bestaande velden
      inhoud = inhoud,
      bijlagen = bijlagen.map { BijlageSamenvatting(it.bijlageId, it.naam) },
      map = status?.map,
  )
  ```

### 3c — Verify

- [ ] `./mvnw clean test -pl services/berichtensessiecache -am`.

### 3d — Commit

```
git add services/berichtensessiecache/src/main/kotlin/.../magazijn/
git commit -m "$(cat <<'EOF'
feat(sessiecache): magazijn-client leest inhoud, bijlagen, status.map

MagazijnBericht-DTO breidt uit met inhoud, bijlagen[] (mappen naar
BijlageSamenvatting) en status.map (gemapt naar cache-Bericht.map).
gewijzigdOp en mimeType worden bewust niet opgenomen in cache.

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

## Task 4 — Sessiecache: PATCH-resource accepteert lege `status`

**File:** `services/berichtensessiecache/src/main/kotlin/.../berichten/BerichtensessiecacheResource.kt`

De gegenereerde `BerichtStatusUpdate` heeft nu `status` als nullable + `map`. Resource moet beide velden verwerken.

### 4a — PATCH-pad

- [ ] In `updateStatus` (of vergelijkbare methode): merge `status` en/of `map` in de bestaande cache-entry. Pseudocode:
  ```kotlin
  val huidig = cache.get(ontvanger, berichtId) ?: throw NotFound
  val bijgewerkt = huidig.copy(
      status = patch.status ?: huidig.status,
      map = patch.map ?: huidig.map,
  )
  cache.put(ontvanger, bijgewerkt)
  return toResponse(bijgewerkt)
  ```
- [ ] Vereis dat minstens één van `status`/`map` ingevuld is (Bean Validation via `minProperties:1` op spec) — anders 400.

### 4b — Tests

- [ ] Bestaande PATCH-tests (alleen-status) blijven groen.
- [ ] Nieuwe test: PATCH met alleen `map` → bericht heeft nieuwe map, status onveranderd.
- [ ] Nieuwe test: PATCH met beide → beide gewijzigd.
- [ ] Nieuwe test: lege body → 400.

### 4c — Commit

```
git add services/berichtensessiecache/src/main/kotlin/ \
        services/berichtensessiecache/src/test/kotlin/
git commit -m "$(cat <<'EOF'
feat(sessiecache): PATCH ondersteunt alleen-status, alleen-map, of beide

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

## Task 5 — Sessiecache: DELETE-resource

**File:** `services/berichtensessiecache/src/main/kotlin/.../berichten/BerichtensessiecacheResource.kt`

### 5a — Endpoint

- [ ] Implementeer `verwijderBericht(berichtId, xOntvanger)` (gegenereerd door OpenAPI). Implementatie:
  ```kotlin
  override fun verwijderBericht(berichtId: UUID, xOntvanger: String): Response {
      val ontvanger = parseOntvanger(xOntvanger)
      cache.delete(ontvanger, berichtId)  // idempotent: geen exception als afwezig
      return Response.noContent().build()
  }
  ```
- [ ] `BerichtenCache.delete(ontvanger, berichtId)` als nieuwe interface-methode + Redis-implementatie (DEL op de berichtId-hash + remove from index).

### 5b — Tests

- [ ] DELETE bestaand bericht → 204, niet meer ophaalbaar.
- [ ] DELETE niet-bestaand bericht → 204 (idempotent).
- [ ] Concurrente DELETE × 2 → beide 204.

### 5c — Commit

```
git add services/berichtensessiecache/src/main/kotlin/ \
        services/berichtensessiecache/src/test/kotlin/
git commit -m "$(cat <<'EOF'
feat(sessiecache): DELETE /berichten/{id} (idempotent cache-invalidate)

Geen 404 op DELETE: dual-write-invalidate behandelt "niet in cache"
als normale staat (geen fout).

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

## Task 6 — WireMock-stubs uitbreiden

**Files:**
- `wiremock/magazijn-{a,b}/mappings/get-berichten.json`
- `wiremock/magazijn-{a,b}/mappings/get-bericht-by-id.json`
- `wiremock/mappings/get-berichten.json`
- `wiremock/mappings/get-bericht-by-id.json`
- `wiremock/mappings/zoek-berichten.json`

- [ ] Voeg `inhoud` (een korte tekst-string) toe aan elke bericht-body.
- [ ] Voeg `bijlagen: []` toe (lege array is voldoende voor de stubs; eventueel één voorbeeld in `get-bericht-by-id.json` met `{bijlageId, naam, mimeType, _links}`).
- [ ] Voeg `status: {gelezen: false, map: null, gewijzigdOp: "..."}` toe als magazijn-vorm (cache pakt alleen `.map`).
- [ ] Update `tijdstipOntvangst` indien nog niet aanwezig (magazijn-`Bericht`-schema vereist het — was al toegevoegd in eerdere fix).

### 6a — Commit

```
git add wiremock/
git commit -m "$(cat <<'EOF'
chore(wiremock): bericht-bodies krijgen inhoud, bijlagen[], status

Magazijn-spec vereist inhoud + bijlagen + tijdstipOntvangst op Bericht.
Sessiecache leest deze sinds Task 3 ook.

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

## Task 7 — Uitvraag: verifieer dual-write-flow + detail-GET

**Files:** alleen verifieren; geen code-wijziging verwacht.

`UitvraagDtoMapper.toMagazijnPatch` doet al de enum→boolean mapping (commit van Task 5 in plan-#413). `SessiecacheClient.verwijderBericht` bestaat al in de uitvraag-codebase — de cache had het endpoint nog niet, nu wel.

- [ ] Run uitvraag-tests; bevestig dat ze nog groen zijn.
- [ ] Lokale rooktest:
  - Start magazijn op 8090 (`mvnw quarkus:dev -pl services/berichtenmagazijn`)
  - Start sessiecache op 8080 (`mvnw quarkus:dev -pl services/berichtensessiecache`)
  - Start uitvraag op 8086 (`mvnw quarkus:dev -pl services/berichtenuitvraag`)
  - Doe POST aanlever via Bruno (`bruno/berichtenmagazijn/`); doe daarna GET via uitvraag (`bruno/berichtenuitvraag/`).
  - Verifieer dat detail-GET via uitvraag werkt (geen `inhoud`-mismatch meer); PATCH met `{status:"gelezen"}` werkt; PATCH met `{map:"archief"}` werkt; DELETE werkt (zowel magazijn als cache).

---

## Task 8 — End-to-end verify + status bijwerken + PR

- [ ] `mcp__maven-host: ./mvnw clean verify -Denv.TESTCONTAINERS_RYUK_DISABLED=true`. Alle modules groen, JaCoCo ≥ 90% per module.
- [ ] Status omhoog in dit document: `**Status:** Concept` → `**Status:** Uitgevoerd`.
- [ ] Status omhoog in `docs/plans/2026-05-27-keten-gap-analyse.md`: voeg een opmerking dat P1-items 1-4 zijn afgerond.
- [ ] Commit + push.
- [ ] Draft-PR (geen reviewer toevoegen):
  ```
  gh pr create --draft --title "feat: P1-keten-fixes (sessiecache uitbreiden + DELETE + PATCH-map)" --body "..."
  ```

---

## Risico's en aandachtspunten

- **Cache-payload groeit** met `inhoud` (tot 1 MiB theoretisch). CLAUDE.md: in praktijk paar KB; geen extra payload-caps in dit plan.
- **Backwards-compat van Redis-keys**: bestaande entries hebben geen `inhoud`/`bijlagen` — fallback `?: ""` / `?: emptyList()` op read; TTL (60s) wist ze vanzelf op.
- **PATCH-mapping ongewijzigd in uitvraag** — die deed al boolean-conversie naar magazijn (`UitvraagDtoMapper.toMagazijnPatch`). Dit plan formaliseert alleen dat dat de juiste plek is.
- **DELETE op cache geeft 204 ook bij afwezig bericht** — bewuste design-keuze (idempotency-eis vanuit dual-write-flow). Documenteer in spec-description.
- **Aanmeld-service-contract** wordt niet gebroken: `BerichtStatusUpdate.status` was required, wordt optioneel (verruiming, geen versie-bump nodig). Wel: aanmeld-service moet zelf het `inhoud` + `bijlagen[]` + `map` veld gaan invullen wanneer die nieuwe berichten POST'et — buiten scope (aanmeld bestaat nog niet).
- **P2-items volgen in aparte PR**: paginatie-naamharmonisatie, zoek-minLength, error-codes, `grootteInBytes`, etc.

## Niet in dit plan

- P2- en P3-items uit de gap-analyse.
- Uitvraag-spec wijzigingen (huidige spec werkt ongewijzigd na cache-uitbreiding).
- `magazijnId` exposeren via uitvraag (productkeuze, open).
- `_links.inhoud` op cache (dood field, kan in P3 schoongemaakt).
