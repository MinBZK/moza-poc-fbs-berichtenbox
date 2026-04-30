**Status:** Concept
**Datum:** 2026-04-23
**Branch:** feature/berichtenmagazijn-aanlever-api

# Plan: restant uit 2026-04-21-berichtenmagazijn-review-fixes.md

**Aanleiding:** de 9 bevindingen uit `docs/plans/2026-04-21-berichtenmagazijn-review-fixes.md` zijn door latere commits deels opgelost of anders geïmplementeerd. Dit plan inventariseert per bevinding de huidige status en definieert alleen de nog open items.

## Status per bevinding

| # | Oud plan | Huidige status | Actie |
|---|----------|----------------|-------|
| 1 | PII (BSN-ontvanger) in error-log bij persist-fout | **Open** — `BerichtOpslagService.kt:74-83` logt nog `bericht.ontvanger.waarde` | Oplossen |
| 2 | PII in debug-log na succes | **Open** — `BerichtOpslagService.kt:87-93` idem | Oplossen |
| 3 | LDV ClickHouse endpoint default `http://` (geen TLS) | **Open** — `application.properties:38` nog `http://localhost:8123`; geen `%prod`-override die 'm leegt | Oplossen |
| 4 | Inconsistent trim tussen `Identificatienummer.parse()` en value-classes + all-zero ontbreekt voor KVK/OIN | **Gedeeltelijk achterhaald** — `parse()` is door R8 vervangen door `of(type, waarde)` (commit 94e418f), maar `of()` trimt nog steeds (regel 26) terwijl `Bsn/Kvk/Oin/Rsin`-constructors strikt zijn. All-zero check ontbreekt voor KVK en OIN | Oplossen in nieuwe vorm |
| 5 | Inhoud-limiet in characters vs. `max-body-size` in bytes | **Open** — `Bericht.kt:29-30` telt `inhoud.length` (chars), `application.properties:14` `max-body-size=2M` (bytes) | Oplossen |
| 6 | Geen authN/rate-limit | Was al out-of-scope; vereist architectuurbeslissing (Digikoppeling REST + mTLS / OAuth2 NL GOV) | Blijft out-of-scope |
| 7 | 409-Problem heeft geen `instance` correlation-id | **Bewust tegenovergestelde keuze in latere harding** — `DbConstraintViolationExceptionMapperTest` asserteert expliciet `assertNull(problem.instance, "409 hoort geen correlation-id te bevatten")` (regel 74). Redenering: 409 is een verwachte client-conflict-situatie, geen server-side diagnose. Dubbele aanlevering is client-reproducerbaar aan de hand van `berichtId`. | Weerleggen in PR-reactie |
| 8 | Random UUIDv4 als PK → B-tree fragmentatie | **Achterhaald** — door R5 is de PK nu een door de database gegenereerde surrogate `Long id` (commit d84b67c). UUIDv4 `berichtId` is nu een unique-constrained secondary key; B-tree-fragmentatie op die index blijft bestaan maar is geen PK-probleem meer. UUIDv7 migratie blijft een mogelijke future optimization. | Markeer als future work, niet nu |
| 9 | `InternalServerErrorException`-message bevat `berichtId` | **Effectief opgelost** door 5xx-masking in `ProblemExceptionMapper` (fbs-common): exception.message wordt nooit in de response-detail gezet; wordt alleen server-side gelogd met correlation-id. | Laten staan (gedekt) |

## Wat gaan we doen

### A. Log-hygiëne (bevinding 1 & 2)

**Bestand:** `services/berichtenmagazijn/src/main/kotlin/nl/rijksoverheid/moz/berichtenmagazijn/aanlever/BerichtOpslagService.kt`

Vervang het loggen van `waarde` door `type + length`. Ontvanger kán een BSN zijn — persoonsgegeven in applicatielog overtreedt AVG art. 5 lid 1c en BIO 12.4.1. LDV blijft de juiste plek voor `dataSubjectId`.

Consistent ook `afzender` zo loggen: altijd OIN (geen PII), maar uniformiteit voorkomt leaks als het model ooit uitbreidt.

```kotlin
log.errorf(
    ex,
    "Persist mislukt berichtId=%s afzenderType=OIN ontvangerType=%s onderwerp.length=%d inhoud.length=%d",
    bericht.berichtId,
    bericht.ontvanger.type,
    bericht.onderwerp.length,
    bericht.inhoud.length,
)

log.debugf(
    "Bericht opgeslagen: berichtId=%s afzenderType=OIN ontvangerType=%s",
    bericht.berichtId,
    bericht.ontvanger.type,
)
```

`afzenderType` is altijd `OIN` (compile-time invariant). Als dat ooit wijzigt, is het een bewuste API-wijziging die deze log meeneemt.

**Test:** nieuwe pure-unit-test `BerichtOpslagServiceLoggingTest` die via een in-memory `Handler` de log-output capture't en asserteert dat `999993653` (of welke BSN-waarde dan ook uit de input) niet in de output voorkomt, maar `ontvangerType=BSN` wél. Happy-path (debug) + persist-fout (error).

### B. LDV TLS fail-fast in prod (bevinding 3)

**Bestand:** `services/berichtenmagazijn/src/main/resources/application.properties`

```properties
logboekdataverwerking.clickhouse.endpoint=http://localhost:8123

# Prod MOET een TLS-endpoint (https://) hebben; lege default faalt de boot in %prod
# conform BIO 13.2.1 (persoonsgegevens versleuteld over netwerk), consistent met
# het patroon voor datasource-credentials.
%prod.logboekdataverwerking.clickhouse.endpoint=
```

**Verificatie:** `./mvnw quarkus:dev -Dquarkus.profile=prod -pl services/berichtenmagazijn` zonder env-override → service weigert te starten.

### C. Strikte normalisatie + all-zero checks (bevinding 4, herformuleerd)

**Bestand:** `services/berichtenmagazijn/src/main/kotlin/nl/rijksoverheid/moz/berichtenmagazijn/opslag/Identificatienummer.kt`

Keuze: **trim verwijderen uit `of()`** (strict). OpenAPI-pattern dwingt dit al af aan de rand; trim in `of()` verbergt clientfouten en maakt de publieke API inconsistent met de value-class constructors.

```kotlin
fun of(type: IdentificatienummerType, waarde: String): Identificatienummer = when (type) {
    IdentificatienummerType.BSN  -> Bsn(waarde)
    IdentificatienummerType.RSIN -> Rsin(waarde)
    IdentificatienummerType.KVK  -> Kvk(waarde)
    IdentificatienummerType.OIN  -> Oin(waarde)
}
```

En voeg all-zero weigering toe in `Oin` en `Kvk` (consistent met `Bsn`/`Rsin`):

```kotlin
// Oin.init
requireValid(waarde.any { it != '0' }) { "OIN kan niet geheel uit nullen bestaan" }

// Kvk.init
requireValid(waarde.any { it != '0' }) { "KVK-nummer kan niet geheel uit nullen bestaan" }
```

**Tests:** `IdentificatienummerTest` aanvullen:
- `Identificatienummer.of(BSN, "  999993653  ")` → `DomainValidationException` (was: slaagde).
- `Oin("0".repeat(20))` en `Kvk("00000000")` → `DomainValidationException`.
- Bestaande `of trimt whitespace`-test omkeren: verwacht nu `DomainValidationException`.

### D. Byte-gebaseerde inhoud-limiet (bevinding 5)

**Bestanden:**
- `services/berichtenmagazijn/src/main/kotlin/nl/rijksoverheid/moz/berichtenmagazijn/opslag/Bericht.kt`
- `services/berichtenmagazijn/src/main/resources/application.properties`
- `services/berichtenmagazijn/src/main/resources/openapi/berichtenmagazijn-api.yaml`

```kotlin
// Bericht.kt
companion object {
    const val MAX_ONDERWERP_LENGTE = 255
    const val MAX_INHOUD_BYTES = 1_048_576 // 1 MiB, canonical in bytes
}

init {
    // …
    val inhoudBytes = inhoud.toByteArray(Charsets.UTF_8).size
    requireValid(inhoudBytes <= MAX_INHOUD_BYTES) {
        "inhoud mag max 1 MiB UTF-8 zijn (kreeg $inhoudBytes bytes)"
    }
    // …
}
```

`max-body-size` van `2M` → `4M`: JSON-envelope + escaping + worst-case 4-byte UTF-8 chars moet binnen het protocol passen; de echte grens ligt in het domein.

OpenAPI `inhoud`-description uitbreiden: "Max 1 MiB in UTF-8 bytes (kan minder characters zijn bij multibyte tekens)". `maxLength: 1048576` blijft (JSON-Schema definieert characters, dus is dit een bovengrens die in karakters altijd ruim genoeg is).

**Tests:** `BerichtEdgeCaseTest` aanvullen:
- 500_000 "😀" emoji (4 bytes elk = 2 MiB) → `DomainValidationException`.
- Exact `MAX_INHOUD_BYTES` ASCII → slaagt.
- `MAX_INHOUD_BYTES + 1` bytes → faalt.

## Wat we niet doen (en waarom)

- **Bevinding 6 (authN/rate-limit):** architectuurniveau; aparte track. `autorisatie/`-dir staat klaar.
- **Bevinding 7 (409 instance):** bewust andere keuze. Response-shape is in de laatste harding bevroren en `DbConstraintViolationExceptionMapperTest` borgt het (regel 74). Rationale: 409 op `bericht_id` is volledig client-reproduceerbaar (dezelfde UUID opnieuw sturen). Een correlation-id zou suggereren dat server-side onderzoek nodig is.
- **Bevinding 8 (UUIDv7):** sinds R5 is de fysieke PK een surrogate `Long` — de fragmentatieclaim gold voor een UUIDv4-PK, niet voor een unique index. UUIDv7 migreren blijft een optimalisatie zonder schema-impact, niet nu nodig.
- **Bevinding 9 (exception-message):** 5xx-masking in `ProblemExceptionMapper` (fbs-common) voorkomt dat `exception.message` naar de client lekt; server-side log behoudt `berichtId` voor diagnose. Geen actie (gebruiker heeft bevestigd: "niet opschonen").

## Kritieke bestanden

- `services/berichtenmagazijn/src/main/kotlin/nl/rijksoverheid/moz/berichtenmagazijn/aanlever/BerichtOpslagService.kt` — A
- `services/berichtenmagazijn/src/main/resources/application.properties` — B (LDV) + D (max-body-size)
- `services/berichtenmagazijn/src/main/kotlin/nl/rijksoverheid/moz/berichtenmagazijn/opslag/Identificatienummer.kt` — C
- `services/berichtenmagazijn/src/main/kotlin/nl/rijksoverheid/moz/berichtenmagazijn/opslag/Bericht.kt` — D
- `services/berichtenmagazijn/src/main/resources/openapi/berichtenmagazijn-api.yaml` — D (description)
- `services/berichtenmagazijn/src/test/kotlin/…/aanlever/BerichtOpslagServiceLoggingTest.kt` (nieuw) — A
- `services/berichtenmagazijn/src/test/kotlin/…/opslag/IdentificatienummerTest.kt` — C
- `services/berichtenmagazijn/src/test/kotlin/…/opslag/BerichtEdgeCaseTest.kt` — D

## Uitvoeringsvolgorde

1. **A** (log-hygiëne) — aparte commit, eenvoudig reviewbaar.
2. **B** (LDV prod-fail-fast) — aparte kleine commit.
3. **C** (strict `of()` + all-zero) — aparte commit; keert de bestaande `of trimt whitespace`-test expliciet om.
4. **D** (byte-limiet) — gecombineerde commit met OpenAPI-description en `max-body-size` bump.

Per stap: `./mvnw -B clean test -pl services/berichtenmagazijn -am`. Aan het eind root-build `./mvnw -B test`.

## Verificatie

```bash
./mvnw -B clean test -pl services/berichtenmagazijn -am
# Verwacht: 77 → ~80 tests (3 nieuwe suites: logging, all-zero, byte-limit), coverage ≥ 90%

# Handmatig:
docker compose up -d
./mvnw quarkus:dev -pl services/berichtenmagazijn -am
# POST met BSN-ontvanger → grep log — geen cijferwaarde van de BSN zichtbaar.
# POST met emoji-payload > 256k karakters (= > 1 MiB UTF-8) → 400.
# Start met profile=prod zonder env-override → boot faalt op LDV-endpoint.
```

## Bij afronding

- Oude plan-document `2026-04-21-berichtenmagazijn-review-fixes.md` status-header bijwerken naar **Uitgevoerd** met verwijzing naar de commit-hashes van A-D.
- Dit plan-document status-header bijwerken naar **Uitgevoerd**.
