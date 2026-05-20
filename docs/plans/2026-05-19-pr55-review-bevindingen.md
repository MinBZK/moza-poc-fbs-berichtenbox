**Status:** Uitgevoerd (commit 96e11bc; M-6/M-7/B-CMT-7/B-PROC-1/B-OBS-1 expliciet als follow-up)

# Plan: Review-bevindingen PR #55 (Ophaal- en Beheer-API)

## Context

PR #55 (`feature/berichtenmagazijn-ophaal-beheer-api`) voegt de Ophaal- en Beheer-API toe aan de berichtenmagazijn-service. Omvang: 56 bestanden, ~3160 LOC.

Twee parallelle reviewstromen:
- **Mens-review:** 24 inline-comments van `@ericwout-overheid` op de PR (zie `gh api repos/MinBZK/moza-poc-fbs-berichtenbox/pulls/55/comments`).
- **Multi-agent AI-review:** 5 specialist-agents (code-reviewer, pr-test-analyzer, silent-failure-hunter, type-design-analyzer, comment-analyzer) op `git diff main...HEAD`.

Dit plan bundelt beide stromen, markeert dubbelingen en geeft een checklist voor de volgende Claude-sessie. Vervolg-Claude: lees dit plan, gebruik `superpowers:executing-plans` of pak items één-voor-één op met `TaskCreate`.

## Aanpak voor vervolg-Claude

1. **Lees deze checklist** in volgorde Kritiek → Medium → Laag.
2. **Per item:** verifieer eerst dat de bevinding nog van toepassing is (code kan ondertussen gewijzigd zijn) — gebruik `git log -p <file>` of `Read`.
3. **Implementeer met TDD**-skill waar mogelijk; voor zuivere refactors (constanten extraheren) volstaat directe edit.
4. **Comment-conventie** (zie CLAUDE.md): default GEEN commentaar; alleen WAAROM. Geen referenties naar issue-nummers, PoC-status, of callers.
5. **Coverage**: na elke wijziging `./mvnw test -pl services/berichtenmagazijn -am` draaien (Docker/Testcontainers vereist; gebruik `mcp__maven__run_maven` als sandbox dat niet toelaat).
6. **Commit-cadans**: één commit per kritieke bevinding of per logisch cluster van Medium/Laag items.

## Conflict-noot

Bevinding **B-CMT-1** is een conflict tussen de twee reviewstromen:
- Comment-analyzer markeert `Bericht.kt:39-43` (BSN/RSIN collision uitleg) als **voorbeeldig WAAROM-commentaar**.
- Ericwout (`Bericht.kt:43`) vindt de comment te lang.

→ Voorlegging vereist. Suggestie: comment inkorten tot 1-2 zinnen die de invariant verwoorden zonder voorbeeld, óf laten staan na overleg. Niet zonder afstemming aanpassen.

---

## Kritiek (must-fix vóór merge)

### K-1: `BijlageContentTypeFilter` slikt fout, levert corrupt content met `octet-stream`
**Bron:** silent-failure-hunter HIGH + code-reviewer Medium + Ericwout (`BijlageContentTypeFilter.kt:36`) — drievoudige bevestiging.
**Locatie:** `services/berichtenmagazijn/src/main/kotlin/nl/rijksoverheid/moz/fbs/berichtenmagazijn/ophaal/BijlageContentTypeFilter.kt:30-39`
**Probleem:** `runCatching { MediaType.valueOf(mimeType) }.getOrNull()` vangt elke `Throwable`, logt op WARN, en valt stil terug op `application/octet-stream`. Een DB-corruptie levert dan een 200-OK met verkeerd MIME-type.
**Voorstel:** vang specifiek `IllegalArgumentException`, ERROR-log met errorId, en overweeg `responseContext.setStatus(500)` + Problem-body — server-side data-corruptie is geen succes-respons.
**Test:** uitbreiden `BijlageContentTypeFilterTest` met 500-pad-assertie.

### K-2: `softDelete` collapse `rows != 1`
**Bron:** silent-failure-hunter HIGH (uniek).
**Locatie:** `services/berichtenmagazijn/src/main/kotlin/nl/rijksoverheid/moz/fbs/berichtenmagazijn/opslag/BerichtRepository.kt:91-100`
**Probleem:** `rows == 0` (niet gevonden) én `rows > 1` (corruptie) → beide `false` → 404. Echte corruptie blijft verborgen.
**Voorstel:** `check(rows <= 1) { "Meerdere rijen gewijzigd door softDelete berichtId=$berichtId rows=$rows" }`; alleen `rows == 0` → false.

### K-3: `verwijder` niet idempotent (RFC 9110 §9.3.5)
**Bron:** silent-failure-hunter HIGH (uniek).
**Locatie:** `services/berichtenmagazijn/src/main/kotlin/nl/rijksoverheid/moz/fbs/berichtenmagazijn/beheer/BerichtBeheerService.kt:65-76`
**Probleem:** Tweede DELETE door rechtmatige ontvanger op al-soft-deleted bericht → 404 i.p.v. 204. Client met netwerkfout op eerste call kan niet onderscheiden of het bericht ooit bestond.
**Voorstel:** zoek incl. soft-deleted; bij `gevonden + verwijderd + juiste ontvanger` → 204 zonder mutatie; bij andere ontvanger → 403; bij niet-bestaand → 404.

### K-4: Race-tak `softDelete=false` zonder log
**Bron:** silent-failure-hunter HIGH (uniek).
**Locatie:** `services/berichtenmagazijn/src/main/kotlin/nl/rijksoverheid/moz/fbs/berichtenmagazijn/beheer/BerichtBeheerService.kt:70-75`
**Voorstel:** `log.warnf("Race-condition bij softDelete: berichtId=%s ontvanger.type=%s rows=%d", berichtId, ontvanger.type, rows)` vóór throw (geen ontvanger.waarde).

### K-5: `BijlageMetadata` mist `init`-validatie
**Bron:** type-design-analyzer HIGH (uniek).
**Locatie:** `services/berichtenmagazijn/src/main/kotlin/nl/rijksoverheid/moz/fbs/berichtenmagazijn/opslag/Bijlage.kt` (`data class BijlageMetadata`)
**Probleem:** `BijlageMetadata("", "", "")` is legaal; `Bijlage` met dezelfde velden niet. Projection-query slipt invalid data door.
**Voorstel:** zelfde init-checks als `Bijlage` (blank-check, length-check op `naam`/`mimeType`).

### K-6: `BerichtStatusInfo` schema mist `required: [gelezen, gewijzigdOp]`
**Bron:** code-reviewer Medium (uniek).
**Locatie:** `services/berichtenmagazijn/src/main/resources/openapi/berichtenmagazijn-api.yaml:407-418`
**Voorstel:** `required: [gelezen, gewijzigdOp]` (zonder `map`, optioneel).

### K-7: JaCoCo-risico — pure unit-tests tellen niet
**Bron:** pr-test-analyzer rating 9 (uniek).
**Bestanden:**
- `services/berichtenmagazijn/src/test/kotlin/.../ophaal/BerichtDtoMapperTest.kt`
- `services/berichtenmagazijn/src/test/kotlin/.../ophaal/BijlageContentTypeFilterTest.kt`

**Probleem:** `quarkus-jacoco` telt alleen `@QuarkusTest`-coverage; pure JUnit/MockK-tests dragen niet bij aan 90%-drempel. `BerichtDtoMapper` (147 regels) en `BijlageContentTypeFilter` afhankelijk van `@QuarkusTest` voor coverage.
**Voorstel:** OphaalResourceIntegrationTest uitbreiden met cases die multi-page paginering, lege bijlagen, KVK/RSIN/OIN-ontvanger raken (mapper-takken volledig dekt); idem voor filter via extra integratie-cases.

### K-8: Aanlever bijlagen-unhappy-paths ontbreken
**Bron:** pr-test-analyzer rating 8 (uniek).
**Locatie:** `services/berichtenmagazijn/src/test/kotlin/.../aanlever/AanleverResourceIntegrationTest.kt`
**Ontbrekend:**
- Partial-failure: bijlage 2 van 3 met corrupte `mimeType` → rollback vereist
- Te grote bijlage (>25 MiB → `Bijlage.MAX_CONTENT_BYTES`)
- Meerdere bijlagen in één POST (iteratie + ordering)
- Lege bijlage-content

### K-9: Issue-nummer-refs in KDoc (rotbom)
**Bron:** comment-analyzer HIGH (deels overlap met Ericwout's PoC-thema, maar specifieker).
**Locaties:**
- `services/berichtenmagazijn/src/main/kotlin/.../ophaal/BerichtOphaalService.kt:22` ("Issue 10")
- `services/berichtenmagazijn/src/main/kotlin/.../opslag/BerichtAutorisatie.kt:11` ("Issue 10")

**Voorstel:** vervang door tijdloze formulering, bv. "toekomstige AuthZEN PEP/PDP vervangt deze check".

---

## Medium

### M-1: `BerichtStatusRepository.upsert` race → unique-violation 500
**Bron:** code-reviewer + silent-failure-hunter (overlap).
**Locatie:** `services/berichtenmagazijn/src/main/kotlin/.../opslag/BerichtStatusRepository.kt:60-77`
**Voorstel:** native `INSERT ... ON CONFLICT (bericht_db_id) DO UPDATE` óf vang `ConstraintViolationException` + retry.

### M-2: Entity-inconsistentie `lateinit var bericht`
**Bron:** type-design-analyzer.
**Locaties:** `BerichtStatusEntity` + `BijlageEntity` gebruiken `lateinit var`, `BerichtEntity` koos bewust default-init.
**Voorstel:** standaardiseer op default-init zoals `BerichtEntity` (motivatie staat al in z'n KDoc).

### M-3: Length-constanten gedupliceerd ↔ Bijlage/BerichtStatus
**Bron:** type-design-analyzer Laag + Ericwout (`BerichtStatusEntity.kt:40`, `BijlageEntity.kt:45`) — STRONG overlap.
**Voorstel:** entity-`@Column(length = ...)` verwijst naar `BerichtStatus.MAX_MAP_LENGTE` / `Bijlage.MAX_*` constanten.

### M-4: PATCH/DELETE Problem-contract niet end-to-end gevalideerd
**Bron:** pr-test-analyzer rating 8.
**Locatie:** `services/berichtenmagazijn/src/test/kotlin/.../ophaal/OphaalBeheerOpenApiContractTest.kt`
**Voorstel:** 403/404/400-cases voor DELETE en PATCH toevoegen met `OpenApiValidationFilter` om Problem-schema af te dwingen.

### M-5: `IllegalArgumentException` uit repositories → gemaskeerd 500
**Bron:** silent-failure-hunter HIGH.
**Locaties:**
- `services/berichtenmagazijn/src/main/kotlin/.../opslag/BijlageRepository.kt:26-40` (`save`)
- `services/berichtenmagazijn/src/main/kotlin/.../opslag/BerichtStatusRepository.kt:67-70` (`upsert`)

**Voorstel:** vervang door `DomainValidationException` (of dedicated `DataConsistencyException`) + `log.errorf` met `berichtId`.

### M-6: `BerichtStatus.map: String?` primitive obsession + ambigue staat
**Bron:** type-design-analyzer.
**Voorstel:** value-class `Map(val naam: String)` met init-checks; overweeg sealed-status om `(gelezen=false, map=null)` rij onmogelijk te maken.

### M-7: `BerichtAutorisatie` audit-log mist correlation-id
**Bron:** silent-failure-hunter.
**Locatie:** `services/berichtenmagazijn/src/main/kotlin/.../opslag/BerichtAutorisatie.kt:22-28`
**Voorstel:** koppel logregel aan correlation-id van `ProblemExceptionMapper` (MDC).

### M-8: `X-Ontvanger` header edge-cases ongetest
**Bron:** pr-test-analyzer.
**Ontbrekend:** whitespace voor/na, `BSN:` zonder waarde, geen header.
**Voorstel:** parameterized in `IdentificatienummerFromHeaderTest.kt` + integratietest-asserties voor 400 + `application/problem+json`.

### M-9: Paginering edge-cases ongetest
**Bron:** pr-test-analyzer.
**Ontbrekend:** `page > totalPages`, `_links.prev/next` afwezigheid op edges, sortering nieuwste-eerst.

### M-10: `NieuweBijlage` naam onduidelijk
**Bron:** Ericwout (`BerichtOpslagService.kt:120`) — uniek.
**Voorstel:** hernoem naar `BijlageData` of `BijlageRecord`. Combineer met **B-CMT-4**.

### M-11: `Bericht` in `beheer/` heeft inhoud — naamcollision met `ophaal/Bericht`
**Bron:** Ericwout (`BeheerResource.kt:40`) — uniek.
**Voorstel:** evalueer of `Bericht`-class in beheer hernoemd moet worden, óf samenvoegen met `ophaal/Bericht` als ze dezelfde semantiek hebben.

### M-12: `processingActivityId = "https://register.example.com/..."` placeholder
**Bron:** Ericwout (`BeheerResource.kt:34`) + comment-analyzer Algemene observatie 2 — STRONG overlap.
**Voorstel:** ADR/ticket voor LDV register-URL strategie; centraliseer als constante of via configuratie.

### M-13: Spec — regex per identificatienummer-type
**Bron:** Ericwout (`berichtenmagazijn-api.yaml:258`) — uniek.
**Probleem:** Huidige regex accepteert elke 9-20 cijfers achter type-prefix. Per type-specifieke lengte zou strenger.
**Voorstel:** OR-clauses per type-prefix, bv. `^(BSN:\d{9}|RSIN:\d{9}|KVK:\d{8}|OIN:\d{20})$`.

### M-14: Spec — `Bericht`-resource zonder `inhoud` bij lijst
**Bron:** Ericwout (`berichtenmagazijn-api.yaml:74`) — uniek (design vraag).
**Voorstel:** evalueer of `inhoud` in lijst-response moet — voorkomt extra GET-call per bericht. Tegenwicht: bandwidth bij grote lijsten.

---

## Laag

### B-CMT-1: ⚠️ CONFLICT — `Bericht.kt:39-43` BSN/RSIN comment
Comment-analyzer: voorbeeldig WAAROM. Ericwout: te lang. → Voorleggen voor merge.

### B-CMT-2: `BerichtDtoMapper.kt:101` `@Suppress("UNUSED_PARAMETER")`
**Bron:** comment-analyzer Laag + Ericwout (5) — STRONG overlap.
**Voorstel:** verwijder ongebruikte parameter; suppression dan overbodig. Als parameter blijft: korter commentaar.

### B-CMT-3: PoC-references in commentaar
**Bron:** comment-analyzer Algemene observatie 1 + Ericwout (13, 15, 16, 19) — THEMA overlap.
**Voorstel:** in CLAUDE.md → `BerichtStatusEntity`, `BerichtStatusRepository`, `BijlageEntity` opschonen: "PoC-status" laten weg; beschrijf gewoon hoe het werkt.

### B-CMT-4: `NieuweBijlage` KDoc niet nodig
**Bron:** comment-analyzer HIGH.
**Locatie:** `BerichtOpslagService.kt:115-118`
**Voorstel:** KDoc verwijderen; combineer met **M-10** (hernoemen).

### B-CMT-5: `OphaalResource.kt:91` `DEFAULT_PAGE_SIZE` spec-sync
**Bron:** comment-analyzer Medium + pr-test-analyzer + Ericwout (10) — STRONG overlap.
**Voorstel:** unit-test die OpenAPI-spec parseert en `DEFAULT_PAGE_SIZE == spec-default` valideert; verwijder de "consistentie"-paragraaf uit commentaar.

### B-CMT-6: Cross-class KDoc-references
**Bron:** comment-analyzer Medium.
**Locaties:** `BerichtBeheerService.kt:14-21`, andere.
**Voorstel:** vervang prose-verwijzingen door KDoc-links `[X]` óf verwoord zonder cross-class refs.

### B-CMT-7: WAT-comments verwijderen
**Bron:** comment-analyzer Laag (meerdere).
**Locaties:** `Bericht.kt:22-26`, `BerichtRepository.kt:29-32, 46-50`, `BijlageRepository.kt:13-16`, `BerichtStatusRepository.kt:8-9`, `BerichtStatusEntity.kt:12-19`, `Bijlage.kt:6-8`.

### B-CMT-8: Taal-nits "de filter" → "het filter", "parseert"
**Bron:** Ericwout (7, 9, 21) — uniek.
**Locaties:** `BijlageContentTypeFilter.kt:20`, `OphaalResource.kt:23`, `Identificatienummer.kt:37`.

### B-TYP-1: `BerichtAutorisatie.vereisOntvanger` simplificeren
**Bron:** code-reviewer Laag + type-design-analyzer.
**Voorstel:** vervang `type != type || waarde != waarde` door `bericht.ontvanger != ontvanger` (value-class equality).

### B-TYP-2: `PagedBerichten` init-validatie
**Bron:** type-design-analyzer + silent-failure-hunter Laag.
**Voorstel:** `require(page >= 0)` + `require(pageSize > 0)`.

### B-TYP-3: `BerichtStatusRepository.findByBerichtIds.associate` slikt duplicates
**Bron:** silent-failure-hunter Laag.
**Voorstel:** `groupBy` + check; throw bij `> 1`.

### B-TYP-4: `BerichtStatusRepository.findByBerichtIds(emptyList())` early-return ongetest
**Bron:** pr-test-analyzer.

### B-TYP-5: `hashCode` integer-overflow risico
**Bron:** Ericwout (`BerichtOpslagService.kt:135`, `Bijlage.kt:51`) — uniek.
**Voorstel:** evalueer `Objects.hash(...)` — voorkomt overflow-gedrag; check of huidige equals/hashCode-overrides nodig zijn voor ByteArray.

### B-TYP-6: `BijlageContentTypeFilter` property-naam uniek maken
**Bron:** Ericwout (`BijlageContentTypeFilter.kt:15`) — uniek.
**Voorstel:** suggestie: random suffix om naamcollisions met andere providers te voorkomen. Evalueer of nodig.

### B-SPC-1: Generated-code voor constanten?
**Bron:** Ericwout (`BerichtStatus.kt:28`, `Bijlage.kt:59`) — uniek.
**Voorstel:** evalueer of length-constanten via OpenAPI-spec `maxLength` gegenereerd kunnen worden — anders wederzijdse KDoc-verwijzing.

### B-PROC-1: Migratie-TODO als ticket?
**Bron:** Ericwout (`V2__ophaal_beheer.sql:18`) — uniek.
**Voorstel:** óf direct implementeren óf GitHub-issue aanmaken; verwijder TODO uit migratie.

### B-OBS-1: `lijstVoorOntvanger` count+list inconsistency
**Bron:** silent-failure-hunter Medium.
**Voorstel:** documenteer tradeoff via KDoc, óf serializable-isolation.

### B-OBS-2: `BijlageRepository.metadataVoorBericht` soft-delete niet expliciet getest
**Bron:** pr-test-analyzer.

### B-OBS-3: `LdvEndpointValidator` log waarschuwing bij actieve TLS-eis
**Bron:** silent-failure-hunter + type-design-analyzer.
**Voorstel:** `log.infof` bij activatie zodat operators de validator-actie kunnen tracen.

### B-OBS-4: HSTS `preload` op HTTP-only PoC
**Bron:** code-reviewer Laag.
**Status:** cosmetisch, browsers negeren HSTS over HTTP.

### B-OBS-5: `bericht_status.bericht_db_id` UNIQUE zonder expliciete index
**Bron:** code-reviewer Laag.
**Status:** Postgres maakt impliciet index aan; geen actie nodig (alleen ter info).

---

## Positieve observaties (niet weghalen)

- BSN nooit in URL/spec/log; `Identificatienummer`-hiërarchie (sealed + value classes + elfproef) is voorbeeldig (type-design rating 10/10).
- LDV `dataSubjectId` pas na succesvolle validatie gezet — geen PII-leak op 400-foutpad.
- `BerichtAutorisatie` centraal als PEP-substitutiepunt voor AuthZEN.
- Surrogate PK + business-UUID, FK zonder CASCADE — conform CLAUDE.md.
- Rollback-scripts onder `db/rollback/V*.sql`.
- `@Lob` correct vermeden op `BijlageEntity.content` (Postgres BYTEA).
- `metadataVoorBericht` projection-query (geen 25MiB heap-load).
- N+1 statuslijst voorkomen via batch + `JOIN FETCH`.
- OpenAPI contract-test dekt 5 endpoints.
- Sterke WAAROM-comments: `BijlageEntity:39-42`, `BerichtOpslagService:29-40` (skipOn-rationale), `BijlageContentTypeFilter:17-26` (NameBinding-fix), `LdvEndpointValidator:7-12` (BIO/AVG).

## Bronverwijzingen

- Multi-agent review run: deze sessie (5 agents parallel).
- Mens-review: `gh api repos/MinBZK/moza-poc-fbs-berichtenbox/pulls/55/comments`.
- Bestaand plan: `docs/plans/2026-05-12-magazijn-ophaal-beheer-api.md` (origin van Issue 3 scope).
