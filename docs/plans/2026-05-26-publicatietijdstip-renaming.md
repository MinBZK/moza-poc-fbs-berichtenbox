**Status:** Concept

# Hernoem `tijdstip`/`publicatiedatum` → `publicatietijdstip`

## Aanleiding

In de sessiecache heet het tijdstip-veld `tijdstip` — nietszeggend voor consumers. In het magazijn heet hetzelfde concept `publicatiedatum`, maar dat veld is een `date-time` (geen datum), dus de naam misleidt. Daarnaast ontbreekt `publicatiedatum` in het magazijn-`Bericht`-schema (alleen `BerichtResponse` op `POST /berichten` heeft het), zodat downstream-consumers het niet kunnen lezen via GET-endpoints. De uitvraag-service exposeert nu `tijdstipOntvangst` (interne audit-tijd) waar een portaal-gebruiker `publicatietijdstip` verwacht.

## Doel

Eén consistente naam — `publicatietijdstip` — door de hele keten:

| Service | Was | Wordt |
|---|---|---|
| Magazijn `Bericht` (GET) | `tijdstipOntvangst` aanwezig; geen publicatie-veld | `tijdstipOntvangst` blijft (audit) + `publicatietijdstip` toegevoegd |
| Magazijn `BerichtInput`/`BerichtResponse` (POST) | `publicatiedatum` | `publicatietijdstip` |
| Magazijn DB-kolom | `publicatiedatum` | `publicatietijdstip` |
| Sessiecache `Bericht`/`BerichtInput`/`BerichtResponse` | `tijdstip` | `publicatietijdstip` |
| Uitvraag `Bericht`/`BerichtSamenvatting` | `tijdstipOntvangst` | `publicatietijdstip` |

Magazijn-`tijdstipOntvangst` blijft bestaan voor audit-doeleinden (intern), maar wordt niet meer door sessiecache/uitvraag overgenomen.

## Volgorde

Magazijn is bron van waarheid → werkstroom van onder naar boven, in één PR om geen tussentijdse breaking state op `main` te hebben:

1. Magazijn (spec + DB + code + WireMock-stubs + Bruno)
2. Sessiecache (spec + code + tests + Bruno)
3. Uitvraag (spec + tests + Bruno)
4. End-to-end verify over alle drie modules

---

## Task 1 — Magazijn: OpenAPI-spec rename

**File:** `services/berichtenmagazijn/src/main/resources/openapi/berichtenmagazijn-api.yaml`

- [ ] In `BerichtInput` (request voor `POST /berichten`): hernoem property `publicatiedatum` → `publicatietijdstip`. Update description en voorbeelden.
- [ ] In `BerichtResponse`: idem, en update de `required`-lijst (regel ~424).
- [ ] In `Bericht` (GET-responses): voeg `publicatietijdstip: { type: string, format: date-time }` toe ná `tijdstipOntvangst`, en voeg toe aan `required`-lijst (regel ~459).
- [ ] Update descriptions: `publicatietijdstip` = effectieve publicatie-tijd (aanleveraar of fallback `tijdstipOntvangst`). `tijdstipOntvangst` = audit-tijd waarop magazijn het bericht ontving.
- [ ] Spectral-lint: 0 errors verwacht.

```bash
npx -y @stoplight/spectral-cli lint services/berichtenmagazijn/src/main/resources/openapi/berichtenmagazijn-api.yaml --ruleset https://static.developer.overheid.nl/adr/ruleset.yaml
```

- [ ] `./mvnw clean compile -pl services/berichtenmagazijn -am` — gegenereerde DTO's bevatten nu `getPublicatietijdstip()`, geen `getPublicatiedatum()` meer.

---

## Task 2 — Magazijn: Flyway-migratie

**File:** `services/berichtenmagazijn/src/main/resources/db/migration/V6__rename_publicatiedatum.sql` (nieuw)

CLAUDE.md: Flyway-migraties zijn immutable na toepassing — wijzig V5 niet, voeg nieuwe migratie toe.

- [ ] Schrijf `V6__rename_publicatiedatum.sql`:

```sql
-- Hernoemt de publicatiedatum-kolom naar publicatietijdstip.
-- De waarde is altijd een date-time geweest; de oorspronkelijke kolomnaam was
-- misleidend. Indexen worden niet hernoemd omdat er geen index expliciet op
-- deze kolom staat (zie V3/V4); de view publicatie_deliveries_oud refereert
-- niet aan deze kolom, dus geen view-recreation nodig.

ALTER TABLE berichten RENAME COLUMN publicatiedatum TO publicatietijdstip;
```

- [ ] Rollback-script `services/berichtenmagazijn/src/main/resources/db/rollback/V6__rename_publicatiedatum.sql`:

```sql
ALTER TABLE berichten RENAME COLUMN publicatietijdstip TO publicatiedatum;
```

- [ ] Verifieer eerst of indexen/views/triggers de oude kolomnaam gebruiken. Run lokaal `psql` of `\d berichten` op een testdatabase via `quarkus:dev` om dit te bevestigen.

> **Let op:** mocht een index/view de kolomnaam wel raken, voeg dan in dezelfde migratie de juiste `DROP INDEX`/`CREATE INDEX`-statements toe (of `CREATE OR REPLACE VIEW`).

---

## Task 3 — Magazijn: Kotlin-domein + services

**Files:** alles onder `services/berichtenmagazijn/src/main/kotlin/` dat `publicatiedatum` gebruikt.

- [ ] Verken bestaande gebruik:
  ```bash
  grep -rln "publicatiedatum" services/berichtenmagazijn/src/main/kotlin/ services/berichtenmagazijn/src/test/kotlin/
  ```
- [ ] Per bestand: rename `publicatiedatum` → `publicatietijdstip` (Kotlin properties, JPA-`@Column(name="...")`-annotaties, function-params, log-statements).
- [ ] DTO-mappers (`BerichtDtoMapper.kt` of vergelijkbaar): de gegenereerde `Bericht`-DTO heeft nu een `publicatietijdstip`-veld — vul dat uit de domein-entity. Voorheen werd het waarschijnlijk niet gemapt in GET-responses; nu wel.
- [ ] Update test-data builders, fixture-JSON's en test-assertions.
- [ ] `./mvnw clean verify -pl services/berichtenmagazijn -am` — alle tests groen, JaCoCo ≥ 90%.

---

## Task 4 — Magazijn: WireMock-stubs + Bruno

**Files:**
- `wiremock/magazijn-a/mappings/*.json`
- `wiremock/magazijn-a/__files/*.json` (response-bodies)
- `wiremock/magazijn-b/...` idem
- `bruno/berichtenmagazijn/berichten/*.bru`

- [ ] Grep in WireMock-mappings:
  ```bash
  grep -rln "publicatiedatum\|tijdstipOntvangst" wiremock/
  ```
- [ ] Vervang in alle JSON-bodies `"publicatiedatum"` → `"publicatietijdstip"`. Voeg waar nodig `tijdstipOntvangst` toe in GET-bodies (magazijn `Bericht`-schema heeft beide).
- [ ] In Bruno (`bruno/berichtenmagazijn/berichten/`):
  - Bestand `7-bericht-aanleveren-met-publicatiedatum.bru` → hernoem naar `7-bericht-aanleveren-met-publicatietijdstip.bru` (met `git mv` zodat history behouden blijft).
  - In `body:json`: `"publicatiedatum"` → `"publicatietijdstip"`.
  - In `meta.name`: idem.

---

## Task 5 — Sessiecache: OpenAPI-spec rename

**File:** `services/berichtensessiecache/src/main/resources/openapi/berichtensessiecache-api.yaml`

- [ ] In `Bericht`-schema: property `tijdstip` → `publicatietijdstip`. Update `required`-lijst (regel ~328).
- [ ] In `BerichtResponse`: idem (regel ~485).
- [ ] In `BerichtInput` (wat sessiecache van magazijnen ontvangt): idem (regel ~522).
- [ ] Spectral-lint: 0 errors.
- [ ] `./mvnw clean compile -pl services/berichtensessiecache -am` — codegen levert `getPublicatietijdstip()`.

---

## Task 6 — Sessiecache: code + magazijn-client mapping

**Files:** alles onder `services/berichtensessiecache/src/main/kotlin/` dat `tijdstip` als field-naam gebruikt (≈ 34 hits per grep — hoofdzakelijk DTO-mapping en cache-payload).

- [ ] Inventariseer:
  ```bash
  grep -rln '\btijdstip\b\|"tijdstip"' services/berichtensessiecache/src/main/kotlin/ services/berichtensessiecache/src/test/kotlin/
  ```
- [ ] Domein-/cache-class (Redis-payload Jackson-serialisatie): rename `tijdstip` → `publicatietijdstip`. Geen migratie nodig — Redis-cache is ephemeral (60s TTL), bestaande sleutels lopen vanzelf af.
- [ ] Magazijn-client deserializeert nu `publicatietijdstip` uit de magazijn-`Bericht`-response (Task 1 voegde het toe). Update mapper/transformer zodat het veld doorstroomt.
- [ ] Test-fixtures + assertions bijwerken.
- [ ] `./mvnw clean verify -pl services/berichtensessiecache -am` — alle tests groen.

---

## Task 7 — Sessiecache: Bruno

**Files:** `bruno/berichtensessiecache/**/*.bru`

- [ ] Grep:
  ```bash
  grep -rln '"tijdstip"' bruno/berichtensessiecache/
  ```
- [ ] Vervang in elke gevonden body of variabele.

---

## Task 8 — Uitvraag: spec + tests

**File:** `services/berichtenuitvraag/src/main/resources/openapi/berichtenuitvraag-api.yaml`

- [ ] In `BerichtSamenvatting` en `Bericht`: property `tijdstipOntvangst` → `publicatietijdstip`. Update `required`-lijsten.
- [ ] Spectral-lint: 0 errors.
- [ ] `./mvnw clean compile -pl services/berichtenuitvraag -am`.

**Files tests:**
- `services/berichtenuitvraag/src/test/kotlin/.../OpenApiContractTest.kt` (6 tests)
- `services/berichtenuitvraag/src/test/kotlin/.../ServiceCoverageTest.kt` (4 tests)
- `services/berichtenuitvraag/src/test/kotlin/.../DualWriteFaultTest.kt` (waar PATCH-response-body wordt gestubd)

- [ ] In alle JSON-stubs: `"tijdstipOntvangst"` → `"publicatietijdstip"`.
- [ ] `./mvnw clean verify -pl services/berichtenuitvraag -am` — alle tests groen, coverage ≥ 90%.

---

## Task 9 — Uitvraag: Bruno

- [ ] Bruno-collectie raakt geen field-namen (URL's en headers); geen wijziging verwacht. Verifieer met:
  ```bash
  grep -rn "tijdstip\|publicatie" bruno/berichtenuitvraag/
  ```

---

## Task 10 — End-to-end verificatie

- [ ] `./mvnw clean verify` op project-root — alle modules groen.
- [ ] Lokale rooktest: start de drie services, doe een aanlever via Bruno, check dat het bericht via uitvraag-API met `publicatietijdstip` zichtbaar wordt.
- [ ] Status omhoog: dit plan-document op **Status:** Uitgevoerd.

---

## Risico's en aandachtspunten

- **Flyway-migratie op productieve data:** de PoC heeft (nog) geen productieve data, dus `RENAME COLUMN` is veilig. Bij echte data: lock op de tabel kort, geen data-conversie.
- **Cache-payload-versie:** sessiecache-cache krijgt een ander veld-name in JSON. Bestaande sleutels uit een eerdere versie zijn na 60s vanzelf weg, dus geen dual-read nodig.
- **Backwards compatibility:** alle drie API's zijn nog niet vrijgegeven. Geen API-versiebump nodig.
- **`tijdstipOntvangst` in magazijn-`Bericht`:** blijft staan voor audit; sessiecache leest het niet meer. Geen actie tenzij we het ook willen verwijderen — buiten scope voor deze refactor.
- **Tests die op JSON-strings stubben:** de meeste mismatch komt uit hardgecodeerde JSON-bodies. Helder grep-pad gegeven per task.
