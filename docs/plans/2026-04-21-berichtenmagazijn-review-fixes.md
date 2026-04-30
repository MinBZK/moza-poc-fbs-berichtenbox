**Status:** Concept
**Datum:** 2026-04-21
**Branch:** feature/berichtenmagazijn-aanlever-api

# Fixes naar aanleiding van code review berichtenmagazijn

Plan om bevindingen uit de review van `services/berichtenmagazijn` op te volgen.
Review dekte veiligheid, BIO, overheidsstandaarden en efficiëntie.

## Samenvatting bevindingen

| # | Ernst | Onderwerp | Locatie |
|---|-------|-----------|---------|
| 1 | **Hoog** | PII (BSN-ontvanger) in applicatielog bij persist-fout | `BerichtOpslagService.kt:72-80` |
| 2 | **Hoog** | PII (BSN-ontvanger) in debug-log na succes | `BerichtOpslagService.kt:84-89` |
| 3 | Medium | LDV ClickHouse endpoint default op `http://` (geen TLS) | `application.properties:29` |
| 4 | Medium | `Oin(waarde)` trimt niet; `Identificatienummer.parse` wél → inconsistent contract | `Identificatienummer.kt` |
| 5 | Medium | 1 MiB-grens op `inhoud` telt *characters*, `max-body-size=2M` telt *bytes* | `Bericht.kt:29-30`, `application.properties:8` |
| 6 | Medium | Geen authenticatie/rate-limiting op POST (bewust POC, documenteren) | `AanleverResource.kt` |
| 7 | Laag | 409-Problem heeft geen `instance` correlation-id | `DbConstraintViolationExceptionMapper.kt:30-38` |
| 8 | Laag | Random UUIDv4 als PK → B-tree fragmentatie bij schaal | `BerichtEntity.kt:29-31` |
| 9 | Laag | `InternalServerErrorException`-message bevat `berichtId` | `BerichtEntity.kt:71-74` |

## Scope

In scope voor deze PR-iteratie: 1, 2, 3, 4, 5, 7, 9.

Buiten scope, als apart planpunt vastleggen: 6 (authN/authZ/rate-limit) en 8 (UUIDv7). Beide vergen architectuurbeslissingen en aparte reviews.

---

## Stappen

### Stap 1 — Verwijder PII uit applicatielog (bevinding 1 & 2)

**Bestand:** `services/berichtenmagazijn/src/main/kotlin/nl/rijksoverheid/moz/berichtenmagazijn/aanlever/BerichtOpslagService.kt`

Vervang het loggen van `afzender.waarde` en `ontvanger.waarde` door type + lengte.
Ontvanger kan een BSN zijn; BSN hoort niet in een reguliere applicatielog (AVG art. 5 lid 1c,
BIO 12.4.1). LDV blijft de juiste plek voor dataSubjectId.

**Ontwerpkeuze:** afzender is altijd OIN (organisatie, geen persoonsgegeven). Toch consequent
type+length loggen voor uniformiteit en zodat een toekomstige uitbreiding naar burger-afzender
geen nieuwe leak introduceert.

```kotlin
private fun typeVan(id: Identificatienummer): String = when (id) {
    is Bsn -> "BSN"; is Kvk -> "KVK"; is Oin -> "OIN"
}

// error-log
log.errorf(
    ex,
    "Persist mislukt berichtId=%s afzenderType=%s ontvangerType=%s onderwerp.length=%d inhoud.length=%d",
    bericht.berichtId,
    typeVan(bericht.afzender),
    typeVan(bericht.ontvanger),
    bericht.onderwerp.length,
    bericht.inhoud.length,
)

// debug-log
log.debugf(
    "Bericht opgeslagen berichtId=%s afzenderType=%s ontvangerType=%s",
    bericht.berichtId,
    typeVan(bericht.afzender),
    typeVan(bericht.ontvanger),
)
```

**Tests:**
- Nieuwe unit test `BerichtOpslagServiceLoggingTest`: capture log output via test-logger,
  stuur een BSN-ontvanger, en assert dat `ontvanger.waarde` niet in de log-regel staat,
  maar `ontvangerType=BSN` wél.
- Dek zowel happy-path (debug) als persist-fout (error) af.

### Stap 2 — Harden LDV endpoint (bevinding 3)

**Bestand:** `services/berichtenmagazijn/src/main/resources/application.properties`

Laat `%prod.logboekdataverwerking.clickhouse.endpoint` leeg zodat de service weigert te
starten zonder expliciete (TLS) endpoint-config in productie. Vergelijkbaar patroon als de
datasource-url.

```properties
# Leeg laten in %prod zodat deploy zonder expliciete (https://) endpoint faalt.
# BIO 13.2.1: persoonsgegevens versleuteld over netwerk.
%prod.logboekdataverwerking.clickhouse.endpoint=
```

Voeg comment toe waarin expliciet staat dat het prod-endpoint `https://` MOET zijn.

**Verificatie:** start lokaal met `-Dquarkus.profile=prod` zonder env-override — service faalt.

### Stap 3 — Consistente normalisatie identificatienummer (bevinding 4)

**Bestand:** `services/berichtenmagazijn/src/main/kotlin/nl/rijksoverheid/moz/berichtenmagazijn/opslag/Identificatienummer.kt`

Keuze: **trim verwijderen uit `parse`** (strictere validatie) i.p.v. trim toevoegen in elke
value-class. Reden: externe input hoort al genormaliseerd te zijn; een trim in parse verbergt
clientfouten en maakt het contract inconsistent met de value-class constructors. OpenAPI
`pattern` dwingt dit al af aan de rand.

```kotlin
fun parse(waarde: String): Identificatienummer =
    when (waarde.length) {
        KVK_LENGTE -> Kvk(waarde)
        BSN_LENGTE -> Bsn(waarde)
        OIN_LENGTE -> Oin(waarde)
        else -> throw DomainValidationException(...)
    }
```

Toevoegen: all-zero check voor KVK en OIN (consistent met BSN):
```kotlin
requireValid(waarde.any { it != '0' }) { "OIN kan niet geheel uit nullen bestaan" }
requireValid(waarde.any { it != '0' }) { "KVK-nummer kan niet geheel uit nullen bestaan" }
```

**Tests:** uitbreiden in `IdentificatienummerTest`:
- `parse(" 12345678 ")` → `DomainValidationException` (was: accepteerde whitespace).
- `Oin("0".repeat(20))` en `Kvk("00000000")` → `DomainValidationException`.
- Happy paths blijven ongewijzigd.

### Stap 4 — Byte-gebaseerde inhoud-limiet (bevinding 5)

**Bestanden:**
- `services/berichtenmagazijn/src/main/kotlin/nl/rijksoverheid/moz/berichtenmagazijn/opslag/Bericht.kt`
- `services/berichtenmagazijn/src/main/resources/application.properties`
- `services/berichtenmagazijn/src/main/resources/openapi/berichtenmagazijn-api.yaml`

Stap 4a — Valideer op UTF-8 byte-size:
```kotlin
private const val MAX_INHOUD_BYTES = 1_048_576 // 1 MiB
init {
    val inhoudBytes = inhoud.toByteArray(Charsets.UTF_8).size
    requireValid(inhoudBytes <= MAX_INHOUD_BYTES) {
        "inhoud mag max 1 MiB UTF-8 zijn (kreeg $inhoudBytes bytes)"
    }
}
```

Stap 4b — `max-body-size` verhogen van `2M` naar `4M` zodat JSON-envelope + escaping +
worst-case UTF-8 (4 bytes/char) binnen de limiet past, maar de echte grens in het domein ligt.

Stap 4c — OpenAPI spec: `inhoud.maxLength` blijft 1048576 (characters, JSON Schema-definitie),
maar description toevoegen:
```yaml
description: |
  Berichtinhoud als tekst. Max 1 MiB gemeten in UTF-8 bytes
  (kan minder characters zijn bij multibyte tekens).
```

**Tests:**
- `BerichtEdgeCaseTest`: voeg case toe met 500k emoji (4 bytes elk) = 2 MiB → expect `DomainValidationException`.
- Case met exact 1 MiB ASCII → OK.
- Case met 1 MiB + 1 byte → fail.

### Stap 5 — 409 met correlation-id (bevinding 7)

**Bestand:** `services/berichtenmagazijn/src/main/kotlin/nl/rijksoverheid/moz/berichtenmagazijn/aanlever/DbConstraintViolationExceptionMapper.kt`

Voeg ook in de 409-tak een `errorId` toe en log die op `info`.

```kotlin
val errorId = UUID.randomUUID()
log.infof("Unique constraint geschonden (errorId=%s, constraint=%s)",
    errorId, exception.constraintName ?: "(onbekend)")
Problem(
    title = "Conflict",
    status = 409,
    detail = "Aanlevering conflicteert met bestaande data.",
    instance = URI.create("urn:uuid:$errorId"),
)
```

**Tests:** `DbConstraintViolation409ContractTest` uitbreiden: assert dat response een
`instance`-veld met `urn:uuid:` prefix bevat.

### Stap 6 — Exception-message zonder berichtId (bevinding 9)

**Bestand:** `services/berichtenmagazijn/src/main/kotlin/nl/rijksoverheid/moz/berichtenmagazijn/opslag/BerichtEntity.kt`

```kotlin
throw InternalServerErrorException(
    "DB-rij voldoet niet aan domein-invarianten",
    ex,
)
```
`berichtId` blijft in de `log.errorf` vlak erboven. Scheiding: log = diagnose (server-side),
exception.message = generiek (potentieel lekkend).

**Tests:** bestaande tests controleren op gewijzigde message; aanpassen indien ze matchten.

---

## Follow-up (separaat plan)

Niet in deze iteratie, documenteren als openstaand:

- **Bevinding 6 — AuthN/AuthZ + rate-limit.** Digikoppeling REST-API profiel (mTLS PKIoverheid
  of OAuth2 m2m NL GOV profiel). De lege directory `autorisatie/` is al aanwezig. Plannen zodra
  scope verbreedt richting MVP.
- **Bevinding 8 — UUIDv7 i.p.v. v4 als PK.** Time-ordered, vermindert index-fragmentatie.
  Backwards-compatible met `UUID` Java-type, geen schema-migratie nodig. Overwegen bij
  performance-testen met representatieve volumes.

## Verificatie

Volgorde van uitvoeren: 1 → 2 → 3 → 4 → 5 → 6 (elk met eigen commit).

Per stap:
```bash
./mvnw test -pl services/berichtenmagazijn -am
```

Aan het eind van de iteratie:
```bash
./mvnw verify -pl services/berichtenmagazijn -am   # incl. JaCoCo ≥90%
```

Manueel: dev-mode, POST met BSN-ontvanger, grep log — geen `waarde=99999…` meer, wel
`ontvangerType=BSN`.

## Ontwerpkeuzes samengevat

- **Type+lengte i.p.v. waarde in log** — AVG dataminimalisatie zonder diagnose te verliezen.
- **Parse wordt strikter (geen trim)** i.p.v. value-classes laxer — OpenAPI-spec dwingt strict
  patroon af, edge-input is een clientfout.
- **Bytes i.p.v. characters** als canonical grens voor inhoud — voorkomt dat protocol-limiet
  (bytes) en domein-limiet (characters) uit elkaar lopen.
- **%prod leeg laten** voor kritieke endpoints — fail-fast boven stille defaults, consistent
  met bestaand patroon voor datasource en credentials.
