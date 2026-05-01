**Status:** Concept

# Plan: review-verwerking PR #31 (berichtenmagazijn Aanlever API)

**Branch:** `feature/berichtenmagazijn-aanlever-api`
**PR:** https://github.com/MinBZK/moza-poc-fbs-berichtenbox/pull/31
**Reviewer:** @mreuvekamp (review `PRR_kwDORjQ8i873JmL6`, Changes Requested, 24 inline-commentaren)

## Context

Op PR #31 is een review met 24 inline-commentaren binnen, status `CHANGES_REQUESTED`. De commentaren zijn grotendeels tekstcorrecties, consistentie-verbeteringen en naming, maar er zitten ook een paar structurele punten tussen (Flyway i.p.v. Hibernate schema-generatie, surrogate primary key, Identificatienummer expliciet getypeerd, test-plaatsing `fbs-common`). Doel van dit plan: voor elk commentaar bepalen of we het oplossen of beargumenteerd weerleggen, en de volgorde/groepering van de wijzigingen vastleggen.

## Overzicht: 24 commentaren, aanpak per item

**Conclusie:** geen enkele bevinding weerleg ik; één heeft een beslissing nodig (zie open vraag R1).

### A. Tekstcorrecties & OpenAPI-copy (triviaal)

| # | Bestand:regel | Commentaar | Actie |
|---|---------------|------------|-------|
| R3 | `fbs-common/…/ProblemExceptionMapper.kt:17` (KDoc) | "gesaniteerd" bestaat niet | vervang door **gesaneerd** (NL consistent) |
| R4 | `…/aanlever/DbConstraintViolationExceptionMapper.kt:43` | "(niet-uniek, errorId=…)" is verwarrend | laat "niet-uniek" weg: `"DB-constraint geschonden (errorId=%s): state=%s constraint=%s"` |
| R11 | `openapi/berichtenmagazijn-api.yaml:70` | "afzendende" → "versturende" | vervang (consistent met FBS-terminologie) |
| R13 | `openapi/berichtenmagazijn-api.yaml:154` | "ontbreekt verplichte velden" → **mist** verplichte velden | vervang ("request mist …" is transitief correct) |
| R14 | `openapi/berichtenmagazijn-api.yaml:172` | "bv." weghalen | vervang door "bijvoorbeeld" of laat de hele toevoeging weg (zie R16) |
| R15 | `openapi/berichtenmagazijn-api.yaml:163` | haal tekst tussen haakjes weg | "(unieke constraint geschonden)" verwijderen uit `Conflict`-description |
| R16 | `openapi/berichtenmagazijn-api.yaml:181` | toevoeging te technisch | `ServiceUnavailable`-description vereenvoudigen: "Magazijn tijdelijk niet beschikbaar" (rest weg) |
| R17 | `…/AanleverResourceIntegrationTest.kt:185` | "in H2" → "in de database" | test-titel aanpassen |
| R20 | `…/ConstraintViolationExceptionMapperTest.kt:46` | assertion-message "mist" → **ontbreekt** | vervang (`"afzender detail ontbreekt: …"`); geen conflict met R13 — context is anders (subject = detail, intransitief) |
| R23 | `…/FbsCommonMapperTest.kt:67` | "saniteert" → **saneert** | vervang in `@DisplayName`/test-naam |
| R24 | `services/berichtenmagazijn/src/test/resources/application.properties:11` | commentaar herschrijven | exact overnemen: "Uitgesloten: gegenereerde code en value-class-wrappers — die vertekenen het coveragepercentage zonder testkwaliteit te meten." |

### B. Naming: OpenAPI-operatie & schema

| # | Bestand:regel | Actie |
|---|---------------|-------|
| R9 | `berichtenmagazijn-api.yaml:26` | `operationId: aanleverBericht` → **`leverBerichtAan`** (Nederlands scheidbaar werkwoord) |
| R10 | `berichtenmagazijn-api.yaml:32` | schema `AanleverBerichtRequest` → **`BerichtAanleverenRequest`** |

Gevolg: regeneratie van JAX-RS interface verandert methodenaam en DTO-klasse. `AanleverResource.kt` en alle testen die de klassenaam noemen moeten mee. Impact op `CircuitBreakerOpen503ContractTest` en `AanleverResourceIntegrationTest` beperkt (alleen de DTO-import/naam).

### C. OpenAPI: tijdstip verduidelijken (R12)

`BerichtResponse.tijdstip` (regel 112) is ambigu: domein-tijdstip of opslag-tijdstip? Huidig gebruik in code: `Instant.now()` bij persist → technisch tijdstip van opslag.

**Actie:** hernoem overal naar **`tijdstipOntvangst`** en update de OpenAPI-description: "Tijdstip waarop het magazijn het bericht heeft ontvangen (server-side gezet, ISO 8601)."
- OpenAPI: `BerichtResponse.tijdstip` → `tijdstipOntvangst`
- Domein: `Bericht.tijdstip` → `tijdstipOntvangst`
- Entity: `BerichtEntity.tijdstip` + DB-kolom `tijdstip` → `tijdstip_ontvangst` (kolomnaam `snake_case` voor DB-conventie, veldnaam `tijdstipOntvangst`)
- Alle callers en tests meetrekken (`BerichtOpslagService`, fixtures, `AanleverResourceIntegrationTest`, contract-tests).

### D. Tests

| # | Bestand:regel | Actie |
|---|---------------|-------|
| R18 | `AanleverResourceIntegrationTest.kt:205` | eerst `assertTrue(responseBerichtId.isNotBlank())`, daarna `repository.findById(UUID.fromString(responseBerichtId))` en asserteer velden op het opgehaalde `Bericht` (sterkere test dan alleen `count()==1`) |
| R19 | `CircuitBreakerOpen503ContractTest.kt:31` | `io.mockk.mockk(relaxed = true)` → importeer `io.mockk.mockk` en gebruik `mockk(relaxed = true)` |
| R21 | `FbsCommonMapperTest.kt:35` | vervang `assertNotEquals("No enum constant …", detail)` door `assertEquals("Er is een interne fout opgetreden. Vermeld errorId bij contact met support.", detail)` — exacte match is veiliger |
| R22 | `FbsCommonMapperTest.kt` | **verplaatsen** naar `libraries/fbs-common/src/test/kotlin/nl/rijksoverheid/moz/fbs/common/`. De testen raken alleen `fbs-common`-types (`ProblemExceptionMapper`, `DomainValidationExceptionMapper`, `IllegalArgumentExceptionMapper`). `@QuarkusTest` is niet nodig — mappers zijn POJOs — omzetten naar pure JUnit 5. Splits zo nodig op per mapper zodat namen 1-op-1 zijn met de doelklasse (`ProblemExceptionMapperTest`, `DomainValidationExceptionMapperTest`, `IllegalArgumentExceptionMapperTest`). |

### E. Persistentie & domein (structureel)

#### R5 — Surrogate primary key (`BerichtEntity.kt:29`)

Reviewer: "Elke tabel zou een eigen, door de database gegenereerde, private key moeten hebben die onafhankelijk is van de gegevens die in de tabel worden opgeslagen (Java Magazine 1 2026)."

Nu: `@Id var berichtId: UUID` is de bedrijfs-identifier én de PK.

**Actie:**
- Voeg `@Id @GeneratedValue(strategy = IDENTITY) var id: Long = 0` toe.
- Maak `berichtId: UUID` een aparte kolom met `@Column(nullable = false, unique = true)`.
- `BerichtRepository` zoekt extern op `berichtId` (business key), niet op `id`. Implementatie: `find("berichtId", berichtId).firstResult()?.toDomain()`.
- DB-unique-violation op `berichtId` blijft een 409 via `DbConstraintViolationExceptionMapper` (SQLSTATE 23505).
- `PLACEHOLDER_UUID` voor `berichtId` is niet meer nodig — default kan een echte waarde zijn omdat persistentie altijd via `fromDomain` gaat.
- Tests (`AanleverResourceIntegrationTest`, `BerichtRepositoryIntegrationTest` als aanwezig) aanpassen.

#### R6 — Flyway (`BerichtEntity.kt:25`)

Reviewer: schema-init niet via Hibernate, maar via Flyway/Liquibase.

**Actie:**
- `services/berichtenmagazijn/pom.xml`: voeg `quarkus-flyway` toe.
- `application.properties` (main): zet `quarkus.hibernate-orm.database.generation=validate` (was `drop-and-create`), `quarkus.flyway.migrate-at-start=true`.
- Maak `services/berichtenmagazijn/src/main/resources/db/migration/V1__init.sql` met CREATE TABLE `berichten` inclusief surrogate PK uit R5 (gecombineerde migratie, niet twee losse V-versies, want de PR is nog niet gereleased).
- Test-profiel: gebruik ook Flyway (zelfde migratie) met H2 in `MEMORY`-modus; profiel `%test` expliciet configureren.
- `application.properties` voor tests: controleren dat JaCoCo-exclusies niet raken aan Flyway-classes (alleen `api.*` + value-class-wrappers zijn uitgesloten, zie R24 — geen verandering nodig).

#### R8 — Identificatienummer expliciet getypeerd (`Identificatienummer.kt:18`)

Reviewer: lengte als type-discriminator is niet toekomstbestendig.

**Actie:** uitvoeren volgens bestaand concept-plan `docs/plans/2026-04-22-identificatienummer-type-tagging.md` — **alle vijf stappen, inclusief stap 5 (sessiecache)**. Kernpunten:
- OpenAPI berichtenmagazijn: `ontvanger` wordt object `{ type: BSN|RSIN|KVK|OIN, waarde }`.
- Domein: `parse(String)` verdwijnt, `Identificatienummer.of(type, waarde)`; `Rsin` toegevoegd.
- DB: `ontvanger_type` + `ontvanger_waarde` kolommen (via Flyway-migratie uit R6, dus samenvoegen in één script).
- Service/resource: accepteert het getypeerde DTO.
- Sessiecache (`services/berichtensessiecache/src/main/resources/openapi/berichtensessiecache-api.yaml`): identiek `Identificatienummer`-component opnemen.
- `MagazijnClient`-mappings en WireMock-fixtures in sessiecache bijwerken.
- Integratietests sessiecache meetrekken (inclusief contract-tests op het nieuwe ontvanger-schema).

### F. Repository naming (R7)

Reviewer: Engelse/technische methodenamen in `BerichtRepository`.

**Actie:**
- `opslaan(bericht)` → **`save(bericht)`**.
- `vind(berichtId)` → **`findByBerichtId(berichtId)`** (na R5 wordt dit `find("berichtId", berichtId).firstResult()?.toDomain()`; naam onderscheidt expliciet van Panache's `findById` op de surrogate `id`).
- Aanroepers (`BerichtOpslagService`) meetrekken.

### G. CI-workflow (R1/R2)

Bestaande `test.yml` draait `./mvnw -B test` op de hele monorepo (bij push op `main` en alle PR's naar `main`) en pint acties op SHA. `test-berichtenmagazijn.yml` dupliceert dit voor berichtenmagazijn-paden, zonder SHA-pins.

**Actie:** `.github/workflows/test-berichtenmagazijn.yml` **verwijderen**. Argumenten:
- `test.yml` dekt berichtenmagazijn al (root-build bouwt alle modules).
- Geen redundante run (PR triggert `test.yml` op elke push).
- Geen onderhoud aan een tweede workflow; R2 (SHA-pinning) wordt daarmee moot.

## Kritieke bestanden

- `services/berichtenmagazijn/src/main/resources/openapi/berichtenmagazijn-api.yaml` — B, C, E (R8)
- `services/berichtenmagazijn/src/main/kotlin/nl/rijksoverheid/moz/berichtenmagazijn/opslag/{BerichtEntity,Identificatienummer,BerichtRepository,Bericht}.kt` — E (R5, R6, R8), F
- `services/berichtenmagazijn/src/main/kotlin/nl/rijksoverheid/moz/berichtenmagazijn/aanlever/{AanleverResource,BerichtOpslagService,DbConstraintViolationExceptionMapper}.kt` — A (R4), B, E (R8), F
- `services/berichtenmagazijn/src/main/resources/application.properties` + nieuw `db/migration/V1__init.sql` — E (R6)
- `services/berichtenmagazijn/src/main/resources/application.properties` (JaCoCo) — **nee, main config**; voor R24: `services/berichtenmagazijn/src/test/resources/application.properties`
- `libraries/fbs-common/src/main/kotlin/nl/rijksoverheid/moz/fbs/common/ProblemExceptionMapper.kt` — A (R3)
- `libraries/fbs-common/src/test/kotlin/nl/rijksoverheid/moz/fbs/common/` — D (R22: nieuwe test-bestanden hierheen)
- Testbestanden `AanleverResourceIntegrationTest`, `ConstraintViolationExceptionMapperTest`, `CircuitBreakerOpen503ContractTest`, `FbsCommonMapperTest` — D
- `.github/workflows/test-berichtenmagazijn.yml` — G

## Uitvoeringsvolgorde

1. **Groep A (tekst/copy)** — alle trivia in één commit. Snel feedbackmoment.
2. **Groep G (CI-workflow)** — verwijder `test-berichtenmagazijn.yml` in aparte commit.
3. **R22 + R21 + R23** — FbsCommonMapperTest naar `libraries/fbs-common/src/test/`, splitsen per mapper, `@QuarkusTest` weg.
4. **R8 + C (Identificatienummer-typing + `tijdstipOntvangst`)** — volgens concept-plan inclusief stap 5; zet plan-status op "In uitvoering". `tijdstipOntvangst` wordt meegenomen omdat beide wijzigingen dezelfde OpenAPI + DB + domein raken en één migratie-script delen.
5. **R5 + R6 (surrogate PK + Flyway)** — gecombineerde commit: `V1__init.sql` bevat surrogate PK, gesplitste `ontvanger_type`/`ontvanger_waarde` én `tijdstip_ontvangst`. `hibernate-orm.database.generation=validate` aanzetten.
6. **R7 + B (repository-naming + operationId/schema-rename)** — naming-commit na de structurele wijzigingen.
7. **R18 + R19** — integratietest-verbetering; verifieer dat tests na R5/R6/R8 nog slagen.
8. **Eindrun verificatie (onderstaand).**

Elke stap is op zichzelf committable en laat tests draaien; geen half-draaiende tussenstand.

## Verificatie

```bash
# Zorg dat Docker draait (Testcontainers via Quarkus Dev Services voor Redis/WireMock).
docker compose up -d

# Unit + component-tests (inclusief RedisBerichtenCacheIntegrationTest met real Redis)
./mvnw -B test -pl libraries/fbs-common
./mvnw -B test -pl services/berichtenmagazijn -am
./mvnw -B test -pl services/berichtensessiecache -am

# Handmatige end-to-end tegen het nieuwe contract
docker compose up -d
./mvnw quarkus:dev -pl services/berichtenmagazijn -am
# Bruno-collectie bruno/berichtenmagazijn/ bijwerken voor:
#   - nieuwe operationId/schema-naam (R9, R10)
#   - getypeerde ontvanger (R8): { "type": "BSN", "waarde": "123456782" }
# Verifiëren:
#   - 201 Created, Location-header, API-Version-header
#   - DB-rij in H2-console bevat surrogate id én berichtId én ontvanger_type/ontvanger_waarde
#   - 409 bij dubbele berichtId
#   - 400 op onbekend type (geen silent fallback)
#   - OpenApiContractTest slaagt tegen de nieuwe spec
```

CI: afwachten dat `test.yml` groen is.
