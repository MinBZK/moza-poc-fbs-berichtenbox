**Status:** Uitgevoerd

# PR #55 — Comments van @ericwout-overheid afhandelen

24 inline-comments op `feature/berichtenmagazijn-ophaal-beheer-api`. Per comment:
status (apply / pushback / discuss), korte motivering en bestand:regel.

Beslissingen die de gebruiker bevestigd heeft:

- **hashCode-comments (C-2, C-18):** pushback met technische uitleg
- **BSN/RSIN-collision-comment (C-11):** inkorten tot 2 regels
- **Corrupt MIME-type (C-8):** 500 i.p.v. fallback
- **PATCH null-shortcut (C-16):** documenteren zonder "PoC"-framing
- **NieuweBijlage (C-1):** hernoemen naar `BijlageInvoer`
- **processingActivityId (C-3):** const + GitHub-issue
- **BerichtMetInhoud (C-4) + lijst-zonder-inhoud (C-23):** consolideren tot één DTO `Bericht`; lijst krijgt inhoud
- **Retention (C-22):** GitHub-issue

## Werkstroom (per groep een commit)

### Groep A — OpenAPI: consolideer `BerichtSamenvatting` + `BerichtMetInhoud` → `Bericht`
Bestand: `services/berichtenmagazijn/src/main/resources/openapi/berichtenmagazijn-api.yaml`

- Schema's `BerichtSamenvatting` en `BerichtMetInhoud` samenvoegen tot één `Bericht` (met `inhoud` verplicht). `BerichtenLijst.berichten[]` → `Bericht`.
- `getBerichten` response wordt `Bericht` (mét inhoud).
- Description aanpassen: lijst bevat nu volledige inhoud.
- Adresseert: **C-4** (verwarrende naam) + **C-23** (waarom zonder inhoud).

Code-impact:
- `BerichtDtoMapper.toBerichtSamenvatting` verwijderen; `toBericht` (samengevoegd).
- Resources/services: ophaalservice geeft al volledige `Bericht`-domein objecten — geen wijziging nodig in service-layer.
- Tests: `BerichtDtoMapperTest`, `OphaalResourceIntegrationTest`, `BeheerResourceIntegrationTest`, `OphaalBeheerOpenApiContractTest` aanpassen.
- Bruno-requests in `bruno/berichtenmagazijn/ophalen-beheer/`: documentatie/assertions update.

### Groep B — Hernoem `NieuweBijlage` → `BijlageInvoer` (C-1)
Bestand: `aanlever/BerichtOpslagService.kt` + alle gebruikers
- Hernoemen van data class + parameter.
- Tests + AanleverResource.

### Groep C — JPA `length` koppelen aan domeinconstantes (C-14, C-20)
- `opslag/BerichtStatusEntity.kt`: `length = 64` → `BerichtStatus.MAX_MAP_LENGTE`.
- `opslag/BijlageEntity.kt`: `length = 255` → `Bijlage.MAX_NAAM_LENGTE`; `length = 127` → `Bijlage.MAX_MIME_LENGTE`.

### Groep D — Cross-reference comments (C-10, C-12, C-17)
- `OphaalResource.DEFAULT_PAGE_SIZE`: korter, plus link naar `PageSizeParam` in spec en omgekeerd.
- `BerichtStatus.MAX_MAP_LENGTE`: kdoc-cross-ref naar `BerichtStatusPatch.map.maxLength` in spec.
- `Bijlage.MAX_NAAM_LENGTE` / `MAX_MIME_LENGTE` / `MAX_CONTENT_BYTES`: kdoc-cross-ref naar spec.

### Groep E — "PoC"-framing weghalen (C-13, C-15, C-16, C-19)
- `BerichtStatusEntity.kt`: kdoc herschrijven zonder PoC-framing.
- `BerichtStatusRepository.kt`: idem op klasse-kdoc + `upsert` + `BerichtStatusPatch` kdoc.
- `BijlageEntity.kt`: idem; S3 noemen als optie zonder "PoC"-framing.

### Groep F — Tekstuele NL-fixes (C-7, C-9, C-21)
- `BijlageContentTypeFilter.kt:20`: "De filter" → "het filter".
- `OphaalResource.kt:23`: "de filter" → "het filter".
- `opslag/Identificatienummer.kt:37`: "Parseert" → "Leest".

### Groep G — Filter-fout = 500 (C-8 / K-1)
- `BijlageContentTypeFilter.filter`: bij ongeldig `mimeType` → `responseContext.setEntity(...)` met `application/problem+json` 500 i.p.v. fallback warn-log. Status, headers, body invullen.
- `BijlageContentTypeFilterTest` (unit) + integratie-test (`OphaalResourceIntegrationTest` corrupt-mime case): assertie wijzigen naar 500 + problem+json.
- Reviewer + multi-agent zijn het hierover eens.

### Groep H — `@Suppress("UNUSED_PARAMETER")` weg (C-5)
- `BerichtDtoMapper.pagineerLinks`: `ontvanger` parameter weghalen, in caller weglaten.

### Groep I — BSN/RSIN comment inkorten (C-11)
- `opslag/Bericht.kt`: comment bij `afzender != ontvanger` van 5 → 2 regels.

### Groep J — `processingActivityId` constant maken (C-3)
- Extract `processingActivityIdOphalen` / `...Beheer` als `const val` in `ApiInfo` of een eigen `ProcessingActivities` object.
- @Logboek-annotaties verwijzen naar de constants (compile-time constant verplicht voor annotaties).
- GitHub-issue maken voor "echte register-URL's bepalen".

### Groep K — Regex per identificatienummer-type strikter (C-24)
- `OntvangerHeader.schema.pattern`: `^(BSN:[0-9]{9}|RSIN:[0-9]{9}|KVK:[0-9]{8}|OIN:[0-9]{20})$`.
- `minLength`/`maxLength` aanpassen of weghalen (regex is strikter).

### Groep L — GitHub-issue voor retention (C-22)

### Pushbacks (geen code-wijziging, alleen reactie op de comment)
- **C-2, C-18:** hashCode-overflow. Standaard idioom; `Objects.hash()` is semantisch gelijk + autoboxing-overhead.
- **C-6:** `BIJLAGE_MIME_TYPE_PROPERTY` random-prefix overbodig — interne JVM-scope.

## Verificatie

- `./mvnw test -pl services/berichtenmagazijn -am` groen
- `./mvnw verify -pl services/berichtenmagazijn -am` (incl. JaCoCo ≥ 90%)
- Spectral linter zonder errors op spec
- Reply op alle 24 inline-comments via `gh api .../comments/{id}/replies`
