# MagazijnResolver Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** MagazijnResolver in `berichtensessiecache` bepaalt op basis van dienstvoorkeuren (Profiel Service) welke magazijnen bevraagd worden.

**Architecture:** Plugbare CDI-bean tussen `BerichtensessiecacheService` en `MagazijnClientFactory`. Profiel-client wordt verplaatst naar `fbs-common` zodat magazijn en sessiecache dezelfde upstream delen. `Identificatienummer`-types verhuizen ook naar `fbs-common` voor service-brede typed signatures. Fail-closed bij Profiel-fouten (503), lege resultaten bij ontbrekende voorkeuren (200/404).

**Tech Stack:** Quarkus 3.x, Kotlin (JVM 21), Quarkus REST Client Reactive + Jackson, MicroProfile Fault Tolerance (`@Retry`), Mutiny (`Uni`/`Multi`), JUnit 5 + MockK + REST-assured + WireMock + Testcontainers.

**Companion spec:** `docs/plans/2026-05-26-magazijn-resolver-design.md` — leest dit eerst.

---

## File Structure

### Verplaatst (fbs-common)
- `libraries/fbs-common/src/main/kotlin/nl/rijksoverheid/moz/fbs/common/identificatie/Identificatienummer.kt` — uit `berichtenmagazijn/opslag/`, met `toCanonicalString()` toegevoegd.
- `libraries/fbs-common/src/test/kotlin/nl/rijksoverheid/moz/fbs/common/identificatie/IdentificatienummerTest.kt` — uit `berichtenmagazijn/opslag/`.
- `libraries/fbs-common/src/main/kotlin/nl/rijksoverheid/moz/fbs/common/profiel/ProfielServiceClient.kt` — uit `berichtenmagazijn/validatie/`.
- `libraries/fbs-common/src/main/kotlin/nl/rijksoverheid/moz/fbs/common/profiel/ProfielServiceEndpointValidator.kt` — uit `berichtenmagazijn/validatie/`, geconsolideerd als enkele bean.
- `libraries/fbs-common/src/main/kotlin/nl/rijksoverheid/moz/fbs/common/profiel/ToestemmingGeweigerdException.kt` — uit `berichtenmagazijn/validatie/`.
- `libraries/fbs-common/src/main/kotlin/nl/rijksoverheid/moz/fbs/common/profiel/ToestemmingGeweigerdExceptionMapper.kt` — uit `berichtenmagazijn/validatie/`.

### Nieuw (fbs-common)
- `libraries/fbs-common/src/main/kotlin/nl/rijksoverheid/moz/fbs/common/profiel/ProfielServiceFoutException.kt`
- `libraries/fbs-common/src/main/kotlin/nl/rijksoverheid/moz/fbs/common/profiel/ProfielServiceFoutExceptionMapper.kt`

### Nieuw (sessiecache)
- `services/berichtensessiecache/src/main/kotlin/nl/rijksoverheid/moz/fbs/berichtensessiecache/magazijn/MagazijnResolver.kt`
- `services/berichtensessiecache/src/main/kotlin/nl/rijksoverheid/moz/fbs/berichtensessiecache/magazijn/ProfielMagazijnResolver.kt`
- `services/berichtensessiecache/src/test/kotlin/nl/rijksoverheid/moz/fbs/berichtensessiecache/magazijn/ProfielMagazijnResolverTest.kt`
- `services/berichtensessiecache/src/test/kotlin/nl/rijksoverheid/moz/fbs/berichtensessiecache/magazijn/ProfielMagazijnResolverIntegrationTest.kt`
- `services/berichtensessiecache/src/test/kotlin/nl/rijksoverheid/moz/fbs/berichtensessiecache/magazijn/WireMockProfielServiceResource.kt`
- `services/berichtensessiecache/src/test/kotlin/nl/rijksoverheid/moz/fbs/berichtensessiecache/magazijn/MockProfielServiceClient.kt`
- `wiremock/profiel-service/mappings/get-partij-onbekend-404.json`
- `wiremock/profiel-service/mappings/get-partij-server-error-500.json`
- `bruno/berichtensessiecache/berichten/ophalen-zonder-voorkeur.bru`
- `bruno/berichtensessiecache/berichten/ophalen-profiel-fout.bru`

### Gewijzigd (sessiecache)
- `services/berichtensessiecache/src/main/resources/openapi/berichtensessiecache-api.yaml` — X-Ontvanger pattern + 503-response.
- `services/berichtensessiecache/src/main/resources/application.properties` — Profiel-config + afzenders.
- `services/berichtensessiecache/src/test/resources/application.properties` — test-config.
- `services/berichtensessiecache/src/main/kotlin/.../magazijn/MagazijnenConfig.kt` — `afzenders()`.
- `services/berichtensessiecache/src/main/kotlin/.../magazijn/MagazijnClientFactory.kt` — fail-fast validatie.
- `services/berichtensessiecache/src/main/kotlin/.../berichten/BerichtenCache.kt` — typed signatures, `cacheKey(Identificatienummer)`.
- `services/berichtensessiecache/src/main/kotlin/.../berichten/BerichtensessiecacheService.kt` — typed signatures, resolver-wire.
- `services/berichtensessiecache/src/main/kotlin/.../berichten/BerichtensessiecacheResource.kt` — `fromHeader`-parsing.
- `services/berichtensessiecache/src/main/kotlin/.../berichten/BerichtenOphalenResource.kt` — `fromHeader`-parsing.
- Bestaande sessiecache-tests met hardcoded `X-Ontvanger`-strings — omgezet naar `BSN:…`-formaat.
- Bestaande sessiecache-`MockedDependenciesProfile`, `WireMockTestProfile`, `RealRedisTestProfile` — afzenders-config.

### Gewijzigd (fbs-common)
- `libraries/fbs-common/pom.xml` — REST-client + Fault-Tolerance deps.

### Gewijzigd (berichtenmagazijn)
- 37 Kotlin-bestanden met import-rewrite voor verplaatste types (mechanisch, geen logica-wijziging).
- `services/berichtenmagazijn/src/main/kotlin/.../validatie/BerichtValidatieService.kt` — gebruikt nu fbs-common-DTO's, ProfielServiceFoutException-paden blijven hetzelfde gedrag.
- Magazijn-tests die de oude verplaatste paths bevatten.

### Verwijderd (berichtenmagazijn)
- `services/berichtenmagazijn/src/main/kotlin/.../opslag/Identificatienummer.kt`
- `services/berichtenmagazijn/src/test/kotlin/.../opslag/IdentificatienummerTest.kt`
- `services/berichtenmagazijn/src/main/kotlin/.../validatie/ProfielServiceClient.kt`
- `services/berichtenmagazijn/src/main/kotlin/.../validatie/ProfielServiceEndpointValidator.kt`
- `services/berichtenmagazijn/src/main/kotlin/.../validatie/ToestemmingGeweigerdException.kt`
- `services/berichtenmagazijn/src/main/kotlin/.../validatie/ToestemmingGeweigerdExceptionMapper.kt`
- `services/berichtenmagazijn/src/test/kotlin/.../validatie/ProfielServiceEndpointValidatorTest.kt` (verhuist naar fbs-common-tests)

---

## Stage A — Fbs-common: Identificatienummer

### Task A1: Pom-dependencies in fbs-common

**Files:**
- Modify: `libraries/fbs-common/pom.xml`

- [ ] **Step 1: Voeg microprofile-rest-client-api + fault-tolerance-api toe**

Edit `libraries/fbs-common/pom.xml`, na de `microprofile-config-api`-dep (regel 63):

```xml
<!-- REST-client interface-annotaties voor ProfielServiceClient (door Quarkus-runtime geleverd). -->
<dependency>
    <groupId>org.eclipse.microprofile.rest.client</groupId>
    <artifactId>microprofile-rest-client-api</artifactId>
    <scope>provided</scope>
</dependency>
<!-- @Retry voor ProfielServiceClient transient-fault recovery. -->
<dependency>
    <groupId>org.eclipse.microprofile.fault-tolerance</groupId>
    <artifactId>microprofile-fault-tolerance-api</artifactId>
    <scope>provided</scope>
</dependency>
```

- [ ] **Step 2: Verifieer fbs-common compileert**

Run: `./mvnw clean compile -pl libraries/fbs-common -am`
Expected: BUILD SUCCESS.

- [ ] **Step 3: Commit**

```bash
git add libraries/fbs-common/pom.xml
git commit -m "build(fbs-common): voeg microprofile rest-client + fault-tolerance deps toe

Voorbereiding voor verplaatsing ProfielServiceClient uit berichtenmagazijn
naar fbs-common."
```

---

### Task A2: Identificatienummer verplaatsen + toCanonicalString toevoegen

**Files:**
- Create: `libraries/fbs-common/src/main/kotlin/nl/rijksoverheid/moz/fbs/common/identificatie/Identificatienummer.kt`
- Delete: `services/berichtenmagazijn/src/main/kotlin/nl/rijksoverheid/moz/fbs/berichtenmagazijn/opslag/Identificatienummer.kt`

- [ ] **Step 1: Schrijf nieuwe Identificatienummer.kt in fbs-common**

Create `libraries/fbs-common/src/main/kotlin/nl/rijksoverheid/moz/fbs/common/identificatie/Identificatienummer.kt`:

```kotlin
package nl.rijksoverheid.moz.fbs.common.identificatie

import nl.rijksoverheid.moz.fbs.common.exception.DomainValidationException
import nl.rijksoverheid.moz.fbs.common.exception.requireValid

/**
 * Polymorfe identificatie van een natuurlijk persoon of organisatie in FBS.
 *
 * Type wordt expliciet meegegeven door de caller (zie [of]), niet afgeleid uit de
 * lengte — dat voorkomt dat bv. een RSIN als BSN wordt geclassificeerd (beide 9
 * cijfers) of dat leading-zero-verlies een BSN stilletjes in een KvK-nummer
 * verandert. De afzender in een bericht is altijd een [Oin]; de ontvanger kan
 * elk type zijn.
 */
sealed interface Identificatienummer {
    val type: IdentificatienummerType
    val waarde: String

    /**
     * Canonical string-representatie `<TYPE>:<WAARDE>`. Identiek aan het format dat
     * door `X-Ontvanger`-header wordt verwacht. Gebruikt door call-sites die een
     * unieke string-key nodig hebben (cache-keys, log-correlatie) zonder de
     * type/waarde-onderscheid te verliezen.
     */
    fun toCanonicalString(): String = "${type.name}:$waarde"

    companion object {
        fun of(type: IdentificatienummerType, waarde: String): Identificatienummer = when (type) {
            IdentificatienummerType.BSN -> Bsn(waarde)
            IdentificatienummerType.RSIN -> Rsin(waarde)
            IdentificatienummerType.KVK -> Kvk(waarde)
            IdentificatienummerType.OIN -> Oin(waarde)
        }

        /**
         * Leest een `X-Ontvanger` header in formaat `<TYPE>:<WAARDE>` en bouwt
         * er een getypeerd [Identificatienummer] van. De header is op spec-niveau
         * al gevalideerd (regex per type, zie `OntvangerHeader` in de OpenAPI-spec)
         * door de JAX-RS Bean Validation annotaties op de gegenereerde interface;
         * deze functie zet om naar het domein-model en delegeert verdere
         * type-invarianten (elfproef, niet-geheel-nullen) aan [of].
         * Gooit [DomainValidationException] bij ongeldige invoer.
         */
        fun fromHeader(header: String): Identificatienummer {
            val parts = header.split(':', limit = 2)
            requireValid(parts.size == 2) {
                "X-Ontvanger header moet in formaat <TYPE>:<WAARDE> zijn"
            }
            val type = try {
                IdentificatienummerType.valueOf(parts[0])
            } catch (ex: IllegalArgumentException) {
                throw DomainValidationException("Onbekend identificatienummer-type: ${parts[0]}", ex)
            }
            return of(type, parts[1])
        }
    }
}

enum class IdentificatienummerType { BSN, RSIN, KVK, OIN }

/**
 * Organisatie-identificatienummer van Logius (20 cijfers, **geen elfproef** — de
 * OIN-spec eist enkel 20-cijferig numeriek). Expliciet vermeld zodat een refactor niet
 * "alsnog" een elfproef-check toevoegt.
 */
@JvmInline
value class Oin(override val waarde: String) : Identificatienummer {
    init {
        requireValid(PATTERN.matches(waarde)) { "OIN moet precies 20 cijfers zijn" }
        requireValid(waarde.any { it != '0' }) { "OIN kan niet geheel uit nullen bestaan" }
    }

    override val type: IdentificatienummerType get() = IdentificatienummerType.OIN

    companion object {
        private val PATTERN = Regex("^[0-9]{20}$")
    }
}

/** KvK-nummer (8 cijfers). */
@JvmInline
value class Kvk(override val waarde: String) : Identificatienummer {
    init {
        requireValid(PATTERN.matches(waarde)) { "KVK-nummer moet precies 8 cijfers zijn" }
        requireValid(waarde.any { it != '0' }) { "KVK-nummer kan niet geheel uit nullen bestaan" }
    }

    override val type: IdentificatienummerType get() = IdentificatienummerType.KVK

    companion object {
        private val PATTERN = Regex("^[0-9]{8}$")
    }
}

/** Burgerservicenummer (9 cijfers, gevalideerd met elfproef). */
@JvmInline
value class Bsn(override val waarde: String) : Identificatienummer {
    init {
        requireValid(PATTERN.matches(waarde)) { "BSN moet precies 9 cijfers zijn" }
        requireValid(waarde.any { it != '0' }) { "BSN kan niet geheel uit nullen bestaan" }
        requireValid(isValidElfproef(waarde)) { "BSN voldoet niet aan elfproef" }
    }

    override val type: IdentificatienummerType get() = IdentificatienummerType.BSN

    companion object {
        private val PATTERN = Regex("^[0-9]{9}$")
    }
}

/** Rechtspersonen en Samenwerkingsverbanden Informatie Nummer (9 cijfers, elfproef). */
@JvmInline
value class Rsin(override val waarde: String) : Identificatienummer {
    init {
        requireValid(PATTERN.matches(waarde)) { "RSIN moet precies 9 cijfers zijn" }
        requireValid(waarde.any { it != '0' }) { "RSIN kan niet geheel uit nullen bestaan" }
        requireValid(isValidElfproef(waarde)) { "RSIN voldoet niet aan elfproef" }
    }

    override val type: IdentificatienummerType get() = IdentificatienummerType.RSIN

    companion object {
        private val PATTERN = Regex("^[0-9]{9}$")
    }
}

// Standaard elfproef voor BSN en RSIN: posities 1-8 met gewichten 9..2,
// positie 9 met gewicht -1. Som modulo 11 moet 0 zijn.
private val ELFPROEF_WEIGHTS = intArrayOf(9, 8, 7, 6, 5, 4, 3, 2, -1)

private fun isValidElfproef(waarde: String): Boolean {
    val som = waarde.mapIndexed { i, c -> (c - '0') * ELFPROEF_WEIGHTS[i] }.sum()
    return som % 11 == 0
}
```

- [ ] **Step 2: Verwijder magazijn-versie**

Run: `git rm services/berichtenmagazijn/src/main/kotlin/nl/rijksoverheid/moz/fbs/berichtenmagazijn/opslag/Identificatienummer.kt`

- [ ] **Step 3: Verifieer fbs-common compileert**

Run: `./mvnw clean compile -pl libraries/fbs-common -am`
Expected: BUILD SUCCESS.

Magazijn-module nog niet, want imports zijn nog niet bijgewerkt.

---

### Task A3: IdentificatienummerTest verplaatsen + uitbreiden met toCanonicalString

**Files:**
- Create: `libraries/fbs-common/src/test/kotlin/nl/rijksoverheid/moz/fbs/common/identificatie/IdentificatienummerTest.kt`
- Delete: `services/berichtenmagazijn/src/test/kotlin/nl/rijksoverheid/moz/fbs/berichtenmagazijn/opslag/IdentificatienummerTest.kt`

- [ ] **Step 1: Lees originele test**

Run: `cat services/berichtenmagazijn/src/test/kotlin/nl/rijksoverheid/moz/fbs/berichtenmagazijn/opslag/IdentificatienummerTest.kt`

- [ ] **Step 2: Kopieer naar fbs-common met package-update**

Create `libraries/fbs-common/src/test/kotlin/nl/rijksoverheid/moz/fbs/common/identificatie/IdentificatienummerTest.kt` met:
- `package nl.rijksoverheid.moz.fbs.common.identificatie` bovenaan
- Imports voor `Bsn`, `Rsin`, `Kvk`, `Oin`, `Identificatienummer`, `IdentificatienummerType`, `DomainValidationException` blijven hetzelfde maar gericht op nieuwe packages
- Rest van de bestaande testklasse onveranderd

- [ ] **Step 3: Voeg toCanonicalString-test toe**

Voeg aan de test toe (binnen de bestaande klasse):

```kotlin
@Test
fun `toCanonicalString geeft TYPE WAARDE formaat`() {
    assertEquals("BSN:999993653", Bsn("999993653").toCanonicalString())
    assertEquals("RSIN:002564440", Rsin("002564440").toCanonicalString())
    assertEquals("KVK:12345678", Kvk("12345678").toCanonicalString())
    assertEquals("OIN:00000001003214345000", Oin("00000001003214345000").toCanonicalString())
}

@Test
fun `toCanonicalString roundtrip met fromHeader`() {
    val origineel = Bsn("999993653")
    val header = origineel.toCanonicalString()
    val geparsed = Identificatienummer.fromHeader(header)
    assertEquals(origineel, geparsed)
}
```

- [ ] **Step 4: Verwijder magazijn-versie**

Run: `git rm services/berichtenmagazijn/src/test/kotlin/nl/rijksoverheid/moz/fbs/berichtenmagazijn/opslag/IdentificatienummerTest.kt`

- [ ] **Step 5: Run fbs-common-tests**

Run: `./mvnw clean test -pl libraries/fbs-common -am`
Expected: BUILD SUCCESS, alle Identificatienummer-tests pass.

---

### Task A4: Magazijn-import-rewrite voor Identificatienummer-types

**Files:**
- Modify: 37 Kotlin-bestanden in `services/berichtenmagazijn/`

- [ ] **Step 1: Run import-rewrite-script**

Run:
```bash
files=$(grep -rln "nl\.rijksoverheid\.moz\.fbs\.berichtenmagazijn\.opslag\.\(Identificatienummer\|Bsn\|Rsin\|Kvk\|Oin\|IdentificatienummerType\)" services/berichtenmagazijn --include="*.kt")
for f in $files; do
  sed -i \
    -e 's|nl\.rijksoverheid\.moz\.fbs\.berichtenmagazijn\.opslag\.Identificatienummer|nl.rijksoverheid.moz.fbs.common.identificatie.Identificatienummer|g' \
    -e 's|nl\.rijksoverheid\.moz\.fbs\.berichtenmagazijn\.opslag\.IdentificatienummerType|nl.rijksoverheid.moz.fbs.common.identificatie.IdentificatienummerType|g' \
    -e 's|nl\.rijksoverheid\.moz\.fbs\.berichtenmagazijn\.opslag\.Bsn|nl.rijksoverheid.moz.fbs.common.identificatie.Bsn|g' \
    -e 's|nl\.rijksoverheid\.moz\.fbs\.berichtenmagazijn\.opslag\.Rsin|nl.rijksoverheid.moz.fbs.common.identificatie.Rsin|g' \
    -e 's|nl\.rijksoverheid\.moz\.fbs\.berichtenmagazijn\.opslag\.Kvk|nl.rijksoverheid.moz.fbs.common.identificatie.Kvk|g' \
    -e 's|nl\.rijksoverheid\.moz\.fbs\.berichtenmagazijn\.opslag\.Oin|nl.rijksoverheid.moz.fbs.common.identificatie.Oin|g' \
    "$f"
done
```

- [ ] **Step 2: Verifieer geen oude imports meer**

Run: `grep -rln "berichtenmagazijn\.opslag\.\(Identificatienummer\|Bsn\|Rsin\|Kvk\|Oin\|IdentificatienummerType\)" services/berichtenmagazijn --include="*.kt"`
Expected: geen output.

- [ ] **Step 3: Verifieer magazijn compileert**

Run: `./mvnw clean compile -pl services/berichtenmagazijn -am`
Expected: BUILD SUCCESS.

- [ ] **Step 4: Commit**

```bash
git add libraries/fbs-common/src/main/kotlin/nl/rijksoverheid/moz/fbs/common/identificatie/Identificatienummer.kt
git add libraries/fbs-common/src/test/kotlin/nl/rijksoverheid/moz/fbs/common/identificatie/IdentificatienummerTest.kt
git add -u  # voor sed-changes + verwijderde magazijn-files
git commit -m "refactor(fbs-common): verplaats Identificatienummer + types vanuit berichtenmagazijn

Hergebruikbaar in sessiecache (volgt). Voegt toCanonicalString() toe voor
unieke cache-keys en log-correlatie. Imports in magazijn-module mechanisch
gepatcht; gedragsneutraal."
```

---

### Task A5: Verifieer volledige magazijn-test-suite met MCP Maven

**Files:** geen wijzigingen.

- [ ] **Step 1: Draai magazijn-tests via host-runner**

Run via `mcp__maven__run_maven` met:
- `pomFile`: `/home/claude/projects/moza-poc-fbs-berichtenbox/services/berichtenmagazijn/pom.xml`
- `goals`: `["clean", "test"]`
- `args`: `["-am"]`
- env `PROJECT_DIR`: `/home/claude/projects/moza-poc-fbs-berichtenbox`

Expected: BUILD SUCCESS.

**Reden voor MCP**: Testcontainers/Postgres heeft Docker nodig — sandbox-Bash kan dat niet (zie memory `mcp_maven`).

---

## Stage B — Fbs-common: Profiel-laag

### Task B1: Verplaats ProfielServiceClient (en DTO's) naar fbs-common

**Files:**
- Create: `libraries/fbs-common/src/main/kotlin/nl/rijksoverheid/moz/fbs/common/profiel/ProfielServiceClient.kt`
- Delete: `services/berichtenmagazijn/src/main/kotlin/nl/rijksoverheid/moz/fbs/berichtenmagazijn/validatie/ProfielServiceClient.kt`

- [ ] **Step 1: Create fbs-common-versie**

Create `libraries/fbs-common/src/main/kotlin/nl/rijksoverheid/moz/fbs/common/profiel/ProfielServiceClient.kt`:

```kotlin
package nl.rijksoverheid.moz.fbs.common.profiel

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import jakarta.ws.rs.GET
import jakarta.ws.rs.Path
import jakarta.ws.rs.PathParam
import jakarta.ws.rs.Produces
import jakarta.ws.rs.ProcessingException
import jakarta.ws.rs.core.MediaType
import org.eclipse.microprofile.faulttolerance.Retry
import org.eclipse.microprofile.rest.client.inject.RegisterRestClient

/**
 * REST-client naar de MOZA Profiel Service (zie github.com/MinBZK/moza-profiel-service).
 * Gedeelde dependency voor berichtenmagazijn (validatie-flow) en berichtensessiecache
 * (MagazijnResolver-flow); één Profiel-upstream betekent één client-definitie.
 *
 * De profiel-service zet identificatie in pad-parameters — afwijkend van onze interne
 * PII-richtlijn ("BSN nooit in URL"). We zijn hier gebonden aan het externe contract;
 * binnen onze services zelf gaat BSN niet in URL.
 *
 * DTO's zijn een minimale subset van de upstream-schema's; `@JsonIgnoreProperties`
 * negeert velden die we niet gebruiken (createdAt, lastUpdated, contactgegevens, etc.)
 * zodat upstream-veldtoevoegingen onze deserialisatie niet breken.
 */
@RegisterRestClient(configKey = "profiel-service")
interface ProfielServiceClient {

    /**
     * `@Retry` op transient I/O-fouten zodat een korte hapering of TCP-reset
     * geen 503 naar de aanleveraar veroorzaakt. Alleen `ProcessingException`
     * retryen: dat is het JAX-RS-wrapper-type voor connection-resets,
     * read-timeouts en andere netwerk-fouten waar de upstream geen definitief
     * antwoord op gaf. Een `WebApplicationException` (4xx/5xx) is een
     * deterministisch upstream-antwoord en wordt NIET geretryed — anders zou
     * elke onbekende ontvanger 3x worden opgevraagd (retry-storm) en zou een
     * 401/403 (auth-misser) onnodig pogingen veroorzaken op een upstream die
     * een rate-limit of token-lock kan triggeren.
     */
    @GET
    @Path("/api/profielservice/v1/{identificatieType}/{identificatieNummer}")
    @Produces(MediaType.APPLICATION_JSON)
    @Retry(maxRetries = 2, delay = 200, retryOn = [ProcessingException::class])
    fun getPartij(
        @PathParam("identificatieType") identificatieType: String,
        @PathParam("identificatieNummer") identificatieNummer: String,
    ): PartijResponse
}

@JsonIgnoreProperties(ignoreUnknown = true)
data class PartijResponse(
    val partijId: Long? = null,
    val voorkeuren: List<VoorkeurResponse> = emptyList(),
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class VoorkeurResponse(
    val voorkeurType: String,
    val waarde: String? = null,
    val scopes: List<ScopeResponse> = emptyList(),
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class ScopeResponse(
    val partij: IdentificatieResponse? = null,
    val dienst: DienstResponse? = null,
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class IdentificatieResponse(
    val identificatieType: String,
    val identificatieNummer: String,
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class DienstResponse(
    val id: Long? = null,
    val beschrijving: String? = null,
)
```

- [ ] **Step 2: Verwijder magazijn-versie**

Run: `git rm services/berichtenmagazijn/src/main/kotlin/nl/rijksoverheid/moz/fbs/berichtenmagazijn/validatie/ProfielServiceClient.kt`

- [ ] **Step 3: Verifieer fbs-common compileert**

Run: `./mvnw clean compile -pl libraries/fbs-common -am`
Expected: BUILD SUCCESS.

---

### Task B2: Verplaats ToestemmingGeweigerd-exception + mapper

**Files:**
- Create: `libraries/fbs-common/src/main/kotlin/nl/rijksoverheid/moz/fbs/common/profiel/ToestemmingGeweigerdException.kt`
- Create: `libraries/fbs-common/src/main/kotlin/nl/rijksoverheid/moz/fbs/common/profiel/ToestemmingGeweigerdExceptionMapper.kt`
- Delete: `services/berichtenmagazijn/src/main/kotlin/nl/rijksoverheid/moz/fbs/berichtenmagazijn/validatie/ToestemmingGeweigerdException.kt`
- Delete: `services/berichtenmagazijn/src/main/kotlin/nl/rijksoverheid/moz/fbs/berichtenmagazijn/validatie/ToestemmingGeweigerdExceptionMapper.kt`

- [ ] **Step 1: Lees originele mapper**

Run: `cat services/berichtenmagazijn/src/main/kotlin/nl/rijksoverheid/moz/fbs/berichtenmagazijn/validatie/ToestemmingGeweigerdExceptionMapper.kt`

- [ ] **Step 2: Create fbs-common-exception**

Create `libraries/fbs-common/src/main/kotlin/nl/rijksoverheid/moz/fbs/common/profiel/ToestemmingGeweigerdException.kt`:

```kotlin
package nl.rijksoverheid.moz.fbs.common.profiel

/**
 * Gegooid wanneer de Profiel Service expliciet `toegestaan=false` retourneert
 * voor de combinatie van afzender en ontvanger. Wordt door
 * [ToestemmingGeweigerdExceptionMapper] vertaald naar HTTP 403 Problem JSON.
 *
 * Geen subklasse van DomainValidationException: dit is geen domein-invariant
 * (zou altijd waar moeten zijn) maar een policy-besluit van een externe partij,
 * waarbij 403 — niet 400 — de juiste status is.
 */
class ToestemmingGeweigerdException(message: String) : RuntimeException(message)
```

- [ ] **Step 3: Create fbs-common-mapper**

Kopieer de originele `ToestemmingGeweigerdExceptionMapper.kt` content naar `libraries/fbs-common/src/main/kotlin/nl/rijksoverheid/moz/fbs/common/profiel/ToestemmingGeweigerdExceptionMapper.kt` en vervang `package`-regel naar `package nl.rijksoverheid.moz.fbs.common.profiel`. Pas imports aan voor de nieuwe locatie van `ToestemmingGeweigerdException`.

- [ ] **Step 4: Verwijder magazijn-versies**

Run:
```bash
git rm services/berichtenmagazijn/src/main/kotlin/nl/rijksoverheid/moz/fbs/berichtenmagazijn/validatie/ToestemmingGeweigerdException.kt
git rm services/berichtenmagazijn/src/main/kotlin/nl/rijksoverheid/moz/fbs/berichtenmagazijn/validatie/ToestemmingGeweigerdExceptionMapper.kt
```

---

### Task B3: Maak ProfielServiceFoutException + mapper

**Files:**
- Create: `libraries/fbs-common/src/main/kotlin/nl/rijksoverheid/moz/fbs/common/profiel/ProfielServiceFoutException.kt`
- Create: `libraries/fbs-common/src/main/kotlin/nl/rijksoverheid/moz/fbs/common/profiel/ProfielServiceFoutExceptionMapper.kt`

- [ ] **Step 1: Create exception**

Create `libraries/fbs-common/src/main/kotlin/nl/rijksoverheid/moz/fbs/common/profiel/ProfielServiceFoutException.kt`:

```kotlin
package nl.rijksoverheid.moz.fbs.common.profiel

/**
 * Gegooid wanneer de Profiel Service onbereikbaar is of een onleesbare respons
 * gaf na de fault-tolerance-retries (5xx, niet-404 4xx, ProcessingException,
 * timeout, malformed JSON). Wordt door [ProfielServiceFoutExceptionMapper]
 * vertaald naar HTTP 503 Problem JSON met `Retry-After`-header.
 *
 * Geen onderscheid in caller-respons tussen de verschillende oorzaken; alles
 * leidt voor de caller tot "Profiel-Service tijdelijk niet beschikbaar". Voor
 * troubleshooting bewaart [cause] het oorspronkelijke type.
 */
class ProfielServiceFoutException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)
```

- [ ] **Step 2: Lees ProblemResponses (helper-utility)**

Run: `grep -n "problemResponse\|maskedServerErrorProblem" libraries/fbs-common/src/main/kotlin/nl/rijksoverheid/moz/fbs/common/exception/ProblemResponses.kt | head -20`

Bestudeer signature van `problemResponse(...)` voor Problem+JSON-bouw.

- [ ] **Step 3: Create mapper**

Create `libraries/fbs-common/src/main/kotlin/nl/rijksoverheid/moz/fbs/common/profiel/ProfielServiceFoutExceptionMapper.kt`:

```kotlin
package nl.rijksoverheid.moz.fbs.common.profiel

import jakarta.ws.rs.core.Response
import jakarta.ws.rs.ext.ExceptionMapper
import jakarta.ws.rs.ext.Provider
import nl.rijksoverheid.moz.fbs.common.exception.problemResponse
import org.jboss.logging.Logger

/**
 * Vertaalt [ProfielServiceFoutException] naar HTTP 503 + Problem+JSON met
 * `Retry-After: 30`. Caller weet niet welke specifieke fault (5xx, timeout,
 * malformed) erachter zit — alleen dat de toestemmingscontrole nu niet
 * uitgevoerd kan worden. Retry-After geeft een afgesproken back-off-window.
 */
@Provider
class ProfielServiceFoutExceptionMapper : ExceptionMapper<ProfielServiceFoutException> {

    private val log = Logger.getLogger(ProfielServiceFoutExceptionMapper::class.java)

    override fun toResponse(exception: ProfielServiceFoutException): Response {
        log.warnf(exception, "Profiel-service onbereikbaar: %s", exception.message)
        return problemResponse(
            status = 503,
            type = "https://moza.nl/problems/profiel-service-onbereikbaar",
            title = "Profiel-service tijdelijk niet beschikbaar",
            detail = "De toestemmingscontrole kon niet uitgevoerd worden. Probeer over 30 seconden opnieuw.",
        ).header("Retry-After", "30").build()
    }
}
```

**Let op:** `problemResponse` returnt een `Response.ResponseBuilder` (zie fbs-common/exception/ProblemResponses.kt). Als de huidige signatuur een `Response` returnt, splits de header-toevoeging op via `Response.fromResponse(...)` of pas de signatuur aan met een opvolg-ticket. Stap 4 verifieert dit.

- [ ] **Step 4: Verifieer fbs-common compileert**

Run: `./mvnw clean compile -pl libraries/fbs-common -am`
Expected: BUILD SUCCESS.

Als de mapper niet compileert door `problemResponse`-signature-mismatch, bekijk `ProblemResponses.kt:1-80` en pas de mapper aan om consistent met de bestaande API te zijn (`Response.status(503).entity(...).header(...).build()` als directe vorm).

---

### Task B4: Verplaats ProfielServiceEndpointValidator naar fbs-common (geconsolideerd)

**Files:**
- Create: `libraries/fbs-common/src/main/kotlin/nl/rijksoverheid/moz/fbs/common/profiel/ProfielServiceEndpointValidator.kt`
- Delete: `services/berichtenmagazijn/src/main/kotlin/nl/rijksoverheid/moz/fbs/berichtenmagazijn/validatie/ProfielServiceEndpointValidator.kt`
- Delete: `services/berichtenmagazijn/src/test/kotlin/nl/rijksoverheid/moz/fbs/berichtenmagazijn/validatie/ProfielServiceEndpointValidatorTest.kt`
- Create: `libraries/fbs-common/src/test/kotlin/nl/rijksoverheid/moz/fbs/common/profiel/ProfielServiceEndpointValidatorTest.kt`

- [ ] **Step 1: Lees originele validator en test**

Run:
```bash
cat services/berichtenmagazijn/src/main/kotlin/nl/rijksoverheid/moz/fbs/berichtenmagazijn/validatie/ProfielServiceEndpointValidator.kt
cat services/berichtenmagazijn/src/test/kotlin/nl/rijksoverheid/moz/fbs/berichtenmagazijn/validatie/ProfielServiceEndpointValidatorTest.kt
```

- [ ] **Step 2: Create fbs-common-versie**

Create `libraries/fbs-common/src/main/kotlin/nl/rijksoverheid/moz/fbs/common/profiel/ProfielServiceEndpointValidator.kt`:

```kotlin
package nl.rijksoverheid.moz.fbs.common.profiel

import io.quarkus.runtime.StartupEvent
import jakarta.enterprise.context.ApplicationScoped
import jakarta.enterprise.event.Observes
import org.eclipse.microprofile.config.inject.ConfigProperty

/**
 * Borgt dat het Profiel-Service-endpoint in productie-achtige profielen TLS
 * (https://) gebruikt. De client zet BSN/RSIN/KVK in het URL-pad (extern
 * contract); onversleuteld verkeer zou de waarde lekken naar netwerk en
 * intermediaire proxy-toegangslogs (BIO 13.2.1 / AVG art. 32). In `dev` en
 * `test` mag http:// voor lokale containers en WireMock.
 *
 * Eén bean per JVM via fbs-common; eerdere service-lokale wrappers zijn
 * vervallen (zelfde config-key, zelfde policy in alle services).
 */
@ApplicationScoped
class ProfielServiceEndpointValidator(
    @param:ConfigProperty(name = "quarkus.rest-client.profiel-service.url") private val endpoint: String,
    @param:ConfigProperty(name = "quarkus.profile") private val profile: String,
) {

    fun onStartup(@Observes event: StartupEvent) {
        validate(profile, endpoint)
    }

    companion object {
        private val PROFIELEN_ZONDER_TLS_EIS = setOf("dev", "test")

        fun validate(profile: String, endpoint: String) {
            if (profile in PROFIELEN_ZONDER_TLS_EIS) return
            require(endpoint.startsWith("https://")) {
                "quarkus.rest-client.profiel-service.url MOET https:// gebruiken in profiel '$profile' " +
                    "(BIO 13.2.1: persoonsgegevens versleuteld over netwerk). Huidige waarde: '$endpoint'"
            }
        }
    }
}
```

- [ ] **Step 3: Kopieer en pas test aan**

Lees `services/berichtenmagazijn/src/test/kotlin/nl/rijksoverheid/moz/fbs/berichtenmagazijn/validatie/ProfielServiceEndpointValidatorTest.kt` en kopieer naar `libraries/fbs-common/src/test/kotlin/nl/rijksoverheid/moz/fbs/common/profiel/ProfielServiceEndpointValidatorTest.kt` met:
- `package nl.rijksoverheid.moz.fbs.common.profiel`
- Import `nl.rijksoverheid.moz.fbs.common.profiel.ProfielServiceEndpointValidator`
- Overige test-logica onveranderd

- [ ] **Step 4: Verwijder magazijn-versies**

```bash
git rm services/berichtenmagazijn/src/main/kotlin/nl/rijksoverheid/moz/fbs/berichtenmagazijn/validatie/ProfielServiceEndpointValidator.kt
git rm services/berichtenmagazijn/src/test/kotlin/nl/rijksoverheid/moz/fbs/berichtenmagazijn/validatie/ProfielServiceEndpointValidatorTest.kt
```

- [ ] **Step 5: Verifieer fbs-common-tests groen**

Run: `./mvnw clean test -pl libraries/fbs-common -am`
Expected: BUILD SUCCESS.

---

### Task B5: Magazijn-imports voor verplaatste Profiel-types

**Files:**
- Modify: ~6 Kotlin-bestanden in `services/berichtenmagazijn/`

- [ ] **Step 1: Run import-rewrite-script**

```bash
files=$(grep -rln "nl\.rijksoverheid\.moz\.fbs\.berichtenmagazijn\.validatie\.\(ProfielServiceClient\|PartijResponse\|VoorkeurResponse\|ScopeResponse\|IdentificatieResponse\|DienstResponse\|ToestemmingGeweigerd\)" services/berichtenmagazijn --include="*.kt")
for f in $files; do
  sed -i \
    -e 's|nl\.rijksoverheid\.moz\.fbs\.berichtenmagazijn\.validatie\.ProfielServiceClient|nl.rijksoverheid.moz.fbs.common.profiel.ProfielServiceClient|g' \
    -e 's|nl\.rijksoverheid\.moz\.fbs\.berichtenmagazijn\.validatie\.PartijResponse|nl.rijksoverheid.moz.fbs.common.profiel.PartijResponse|g' \
    -e 's|nl\.rijksoverheid\.moz\.fbs\.berichtenmagazijn\.validatie\.VoorkeurResponse|nl.rijksoverheid.moz.fbs.common.profiel.VoorkeurResponse|g' \
    -e 's|nl\.rijksoverheid\.moz\.fbs\.berichtenmagazijn\.validatie\.ScopeResponse|nl.rijksoverheid.moz.fbs.common.profiel.ScopeResponse|g' \
    -e 's|nl\.rijksoverheid\.moz\.fbs\.berichtenmagazijn\.validatie\.IdentificatieResponse|nl.rijksoverheid.moz.fbs.common.profiel.IdentificatieResponse|g' \
    -e 's|nl\.rijksoverheid\.moz\.fbs\.berichtenmagazijn\.validatie\.DienstResponse|nl.rijksoverheid.moz.fbs.common.profiel.DienstResponse|g' \
    -e 's|nl\.rijksoverheid\.moz\.fbs\.berichtenmagazijn\.validatie\.ToestemmingGeweigerdException|nl.rijksoverheid.moz.fbs.common.profiel.ToestemmingGeweigerdException|g' \
    "$f"
done
```

- [ ] **Step 2: Update BerichtValidatieService specifiek**

Open `services/berichtenmagazijn/src/main/kotlin/nl/rijksoverheid/moz/fbs/berichtenmagazijn/validatie/BerichtValidatieService.kt`. Controleer dat de imports na sed correct zijn:

```kotlin
import nl.rijksoverheid.moz.fbs.common.profiel.ProfielServiceClient
import nl.rijksoverheid.moz.fbs.common.profiel.PartijResponse
import nl.rijksoverheid.moz.fbs.common.profiel.ToestemmingGeweigerdException
```

Plus identificatie-imports:
```kotlin
import nl.rijksoverheid.moz.fbs.common.identificatie.IdentificatienummerType
import nl.rijksoverheid.moz.fbs.common.identificatie.Oin
```

- [ ] **Step 3: Verifieer magazijn compileert**

Run: `./mvnw clean compile -pl services/berichtenmagazijn -am`
Expected: BUILD SUCCESS.

---

### Task B6: Update magazijn-WireMock-test + MockProfielServiceClient package

**Files:**
- Modify: `services/berichtenmagazijn/src/test/kotlin/nl/rijksoverheid/moz/fbs/berichtenmagazijn/validatie/ProfielServiceClientWireMockTest.kt`
- Modify: `services/berichtenmagazijn/src/test/kotlin/nl/rijksoverheid/moz/fbs/berichtenmagazijn/validatie/MockProfielServiceClient.kt`

- [ ] **Step 1: Verifieer imports na sed**

Run: `grep -n "import.*validatie\.Profiel\|import.*validatie\.PartijResponse\|import.*validatie\.ToestemmingGeweigerd" services/berichtenmagazijn/src/test/kotlin/nl/rijksoverheid/moz/fbs/berichtenmagazijn/validatie/*.kt`
Expected: lege output (alle imports al gepatcht).

- [ ] **Step 2: Run alle magazijn-tests (host-runner)**

Via MCP Maven met `PROJECT_DIR=/home/claude/projects/moza-poc-fbs-berichtenbox`, `pomFile=services/berichtenmagazijn/pom.xml`, `goals=["clean","test"]`, `args=["-am"]`.

Expected: BUILD SUCCESS.

Bij failures: dump `surefire-reports` en fix imports/use-sites in de gemelde file.

- [ ] **Step 3: Commit Stage B**

```bash
git add -A
git commit -m "refactor(fbs-common): verplaats Profiel-laag uit berichtenmagazijn

- ProfielServiceClient + DTO's, ToestemmingGeweigerd-exception + mapper,
  EndpointValidator naar fbs-common.profiel
- Nieuwe ProfielServiceFoutException + mapper (503 + Retry-After)
- Magazijn-imports gepatcht; gedragsneutraal voor #541-flow.

Bereidt voor: gedeelde Profiel-laag voor MagazijnResolver in sessiecache."
```

---

## Stage C — Sessiecache: spec + config

### Task C1: Update X-Ontvanger spec naar TYPE:WAARDE

**Files:**
- Modify: `services/berichtensessiecache/src/main/resources/openapi/berichtensessiecache-api.yaml`

- [ ] **Step 1: Update OntvangerHeader schema**

In `berichtensessiecache-api.yaml`, vervang het `OntvangerHeader`-blok (~regels 304-311):

```yaml
OntvangerHeader:
  name: X-Ontvanger
  in: header
  required: true
  description: |
    Ontvanger van het bericht in formaat `<TYPE>:<WAARDE>`, bijvoorbeeld
    `BSN:123456782`, `KVK:12345678`, `RSIN:002564440` of `OIN:00000001003214345000`.
    Wordt als header doorgegeven om persoonsgegevens uit URL en toegangslogs te houden.

    De regex koppelt de lengte aan het type: BSN/RSIN = 9 cijfers (elfproef),
    KVK = 8 cijfers, OIN = 20 cijfers. Verdere domein-invarianten (elfproef voor
    BSN/RSIN, niet-geheel-nullen) worden in de service-laag afgedwongen.
  schema:
    type: string
    pattern: '^(BSN:[0-9]{9}|RSIN:[0-9]{9}|KVK:[0-9]{8}|OIN:[0-9]{20})$'
    minLength: 12
    maxLength: 24
```

- [ ] **Step 2: Voeg 503-response toe op POST /berichten/ophalen**

Zoek het `/berichten/ophalen` POST path-item. Voeg toe in `responses:`:

```yaml
'503':
  description: |
    De Profiel Service is tijdelijk niet bereikbaar. De toestemmingscontrole
    kon niet uitgevoerd worden; client kan over 30 seconden opnieuw proberen.
  headers:
    Retry-After:
      description: Aantal seconden waarna client opnieuw mag proberen.
      schema:
        type: integer
        example: 30
  content:
    application/problem+json:
      schema:
        $ref: '#/components/schemas/Problem'
```

- [ ] **Step 3: Spectral-lint**

Run:
```bash
npx @stoplight/spectral-cli lint \
  services/berichtensessiecache/src/main/resources/openapi/berichtensessiecache-api.yaml \
  --ruleset https://static.developer.overheid.nl/adr/ruleset.yaml
```

Expected: 0 errors. Warnings mogen blijven.

- [ ] **Step 4: Verifieer spec-generator compileert**

Run: `./mvnw clean compile -pl services/berichtensessiecache -am`
Expected: BUILD SUCCESS. Resource-interface krijgt nieuwe pattern-Bean-Validation-annotatie.

---

### Task C2: Update sessiecache application.properties

**Files:**
- Modify: `services/berichtensessiecache/src/main/resources/application.properties`

- [ ] **Step 1: Voeg Profiel-config en magazijn-afzenders toe**

Voeg toe na de bestaande `magazijnen.instances.*`-regels:

```properties

# MOZA Profiel Service — gedeelde upstream met berichtenmagazijn.
# REST-client + DTO's wonen in fbs-common (nl.rijksoverheid.moz.fbs.common.profiel).
# WireMock-stub op localhost:8089 (zie compose.yaml + wiremock/profiel-service/).
quarkus.rest-client.profiel-service.url=${PROFIEL_SERVICE_URL}
%dev.quarkus.rest-client.profiel-service.url=http://localhost:8089
%test.quarkus.rest-client.profiel-service.url=http://localhost:8089
quarkus.rest-client.profiel-service.connect-timeout=2000
quarkus.rest-client.profiel-service.read-timeout=5000

# Afzender-OIN(s) per magazijn — bepaalt welke magazijnen MagazijnResolver
# bevraagt op basis van dienstvoorkeuren uit de Profiel Service. Een lege
# of ontbrekende lijst leidt tot startup-fail (MagazijnClientFactory.init).
magazijnen.instances.magazijn-a.afzenders=00000001003214345000
magazijnen.instances.magazijn-b.afzenders=00000001823288444000
```

---

### Task C3: Update test-resources application.properties

**Files:**
- Modify: `services/berichtensessiecache/src/test/resources/application.properties`

- [ ] **Step 1: Voeg afzenders toe voor tests**

Append to `services/berichtensessiecache/src/test/resources/application.properties`:

```properties

# Test-config: matcht WireMockMagazijnResource-test-defaults.
magazijnen.instances.magazijn-a.afzenders=00000001003214345000
magazijnen.instances.magazijn-b.afzenders=00000001823288444000

# Profiel-service-URL voor tests: dynamische WireMockProfielServiceResource
# overschrijft deze waarde per testklasse.
quarkus.rest-client.profiel-service.url=http://localhost:8089
```

---

### Task C4: Update WireMockMagazijnResource met afzenders

**Files:**
- Modify: `services/berichtensessiecache/src/test/kotlin/.../magazijn/WireMockMagazijnResource.kt`

- [ ] **Step 1: Voeg afzenders-config toe aan resource**

Open `services/berichtensessiecache/src/test/kotlin/nl/rijksoverheid/moz/fbs/berichtensessiecache/magazijn/WireMockMagazijnResource.kt` en update `start()`-return-map:

```kotlin
return mapOf(
    "magazijnen.instances.magazijn-a.url" to a.baseUrl(),
    "magazijnen.instances.magazijn-a.naam" to "WireMock Magazijn A",
    "magazijnen.instances.magazijn-a.afzenders" to "00000001003214345000",
    "magazijnen.instances.magazijn-b.url" to b.baseUrl(),
    "magazijnen.instances.magazijn-b.naam" to "WireMock Magazijn B",
    "magazijnen.instances.magazijn-b.afzenders" to "00000001823288444000",
)
```

---

## Stage D — Sessiecache: MagazijnenConfig + service-typed signatures

### Task D1: Update MagazijnenConfig + MagazijnClientFactory met afzenders

**Files:**
- Modify: `services/berichtensessiecache/src/main/kotlin/.../magazijn/MagazijnenConfig.kt`
- Modify: `services/berichtensessiecache/src/main/kotlin/.../magazijn/MagazijnClientFactory.kt`

- [ ] **Step 1: Voeg afzenders aan config**

Replace `services/berichtensessiecache/src/main/kotlin/nl/rijksoverheid/moz/fbs/berichtensessiecache/magazijn/MagazijnenConfig.kt`:

```kotlin
package nl.rijksoverheid.moz.fbs.berichtensessiecache.magazijn

import io.smallrye.config.ConfigMapping
import java.util.Optional

@ConfigMapping(prefix = "magazijnen")
interface MagazijnenConfig {
    fun instances(): Map<String, MagazijnInstance>

    interface MagazijnInstance {
        fun url(): String
        fun naam(): Optional<String>

        /**
         * OIN(s) van afzenders die dit magazijn serveert. Gebruikt door
         * MagazijnResolver om dienstvoorkeuren te koppelen aan magazijnen.
         * Mag niet leeg zijn (fail-fast in MagazijnClientFactory.init).
         */
        fun afzenders(): List<String>
    }
}
```

- [ ] **Step 2: Voeg validatie toe in MagazijnClientFactory.init()**

Update `MagazijnClientFactory.init()` om afzenders te valideren via `Oin`-constructor:

```kotlin
@PostConstruct
fun init() {
    require(config.instances().isNotEmpty()) { "Geen magazijnen geconfigureerd" }
    cachedClients = config.instances().map { (id, instance) ->
        runCatching { URI.create(instance.url()) }.getOrElse {
            throw IllegalStateException("Ongeldige URL voor magazijn '$id': ${instance.url()}", it)
        }
        require(instance.afzenders().isNotEmpty()) {
            "Magazijn '$id' heeft geen afzenders geconfigureerd (magazijnen.instances.$id.afzenders)"
        }
        instance.afzenders().forEach { oin ->
            runCatching { Oin(oin) }.getOrElse {
                throw IllegalStateException("Ongeldige afzender-OIN voor magazijn '$id': '$oin'", it)
            }
        }
        id to createClient(instance)
    }.toMap()
    cachedNamen = config.instances().map { (id, instance) ->
        id to instance.naam().orElse(null)
    }.toMap()
    cachedAfzenders = config.instances().map { (id, instance) ->
        id to instance.afzenders().map { Oin(it) }.toSet()
    }.toMap()
    log.infof("Geconfigureerde magazijnen: %s", cachedClients.keys)
}
```

Voeg veld en accessor toe (binnen de klasse):

```kotlin
private lateinit var cachedAfzenders: Map<String, Set<Oin>>

fun getAfzenders(magazijnId: String): Set<Oin> = cachedAfzenders[magazijnId].orEmpty()

fun getAlleAfzenders(): Map<String, Set<Oin>> = cachedAfzenders
```

Import toevoegen:
```kotlin
import nl.rijksoverheid.moz.fbs.common.identificatie.Oin
```

- [ ] **Step 3: Verifieer sessiecache compileert**

Run: `./mvnw clean compile -pl services/berichtensessiecache -am`
Expected: BUILD SUCCESS.

---

### Task D2: BerichtenCache cacheKey naar Identificatienummer

**Files:**
- Modify: `services/berichtensessiecache/src/main/kotlin/.../berichten/BerichtenCache.kt`

- [ ] **Step 1: Lees huidige cacheKey-functie en interface-signatures**

Run: `grep -n "fun cacheKey\|ontvanger: String" services/berichtensessiecache/src/main/kotlin/nl/rijksoverheid/moz/fbs/berichtensessiecache/berichten/BerichtenCache.kt`

Noteer welke methoden `ontvanger: String` als parameter hebben.

- [ ] **Step 2: Update cacheKey-functie**

In `BerichtenCache.kt` (zoek `fun cacheKey`):

```kotlin
companion object {
    fun cacheKey(ontvanger: Identificatienummer): String {
        val canonical = ontvanger.toCanonicalString()
        return MessageDigest.getInstance("SHA-256")
            .digest(canonical.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
    }
}
```

Vervang alle bestaande `ontvanger: String` parameters in de `BerichtenCache`-interface en implementatie door `ontvanger: Identificatienummer`. Concrete methoden in de interface (zie BerichtenCache.kt-bovenkant) krijgen typed signatures:

```kotlin
fun getPage(key: String, page: Int, pageSize: Int, afzender: String?, ontvanger: Identificatienummer): Uni<BerichtenPage?>
fun getById(berichtId: UUID, ontvanger: Identificatienummer): Uni<Bericht?>
fun search(ontvanger: Identificatienummer, q: String, page: Int, pageSize: Int, afzender: String?): Uni<BerichtenPage>
fun updateStatus(berichtId: UUID, ontvanger: Identificatienummer, status: String): Uni<Bericht?>
fun addBericht(bericht: Bericht): Uni<Void>
fun store(key: String, berichten: List<Bericht>): Uni<Void>
fun storeAggregationStatus(key: String, status: AggregationStatus): Uni<Void>
fun trySetAggregationStatus(key: String, status: AggregationStatus): Uni<Boolean>
fun getAggregationStatus(key: String): Uni<AggregationStatus?>
```

Pas implementaties aan: gebruik `ontvanger.toCanonicalString()` voor logging/RediSearch-filters waar voorheen `ontvanger`-raw gebruikt werd. Voor RediSearch-TAG-filters waar ontvanger als key-fragment dient, gebruik `cacheKey(ontvanger)`.

Import:
```kotlin
import nl.rijksoverheid.moz.fbs.common.identificatie.Identificatienummer
```

- [ ] **Step 3: Update MockBerichtenCache (test-double)**

Open `services/berichtensessiecache/src/test/kotlin/nl/rijksoverheid/moz/fbs/berichtensessiecache/berichten/MockBerichtenCache.kt`. Pas signatures aan zodat ze de nieuwe interface implementeren.

- [ ] **Step 4: Verifieer compilatie**

Run: `./mvnw clean compile -pl services/berichtensessiecache -am`
Expected: BUILD SUCCESS. Service en Resource compileren nog niet (volgende tasks).

---

### Task D3: BerichtensessiecacheService signatures naar Identificatienummer

**Files:**
- Modify: `services/berichtensessiecache/src/main/kotlin/.../berichten/BerichtensessiecacheService.kt`

- [ ] **Step 1: Update alle method-signatures**

In `BerichtensessiecacheService.kt` vervang elk `ontvanger: String` door `ontvanger: Identificatienummer`. Methodes:
- `getBerichten(page, pageSize, ontvanger, afzender)`
- `getAggregationStatus(ontvanger)`
- `getBerichtById(berichtId, ontvanger)`
- `zoekBerichten(q, page, pageSize, ontvanger, afzender)`
- `updateBerichtStatus(berichtId, ontvanger, status)`
- `addBericht(bericht)` — geen wijziging (`Bericht` heeft eigen ontvanger).
- `ophalenBerichten(ontvanger)`

`BerichtenCache.cacheKey(ontvanger)` calls hoeven niet gewijzigd te worden — neemt nu typed parameter.

Import:
```kotlin
import nl.rijksoverheid.moz.fbs.common.identificatie.Identificatienummer
```

- [ ] **Step 2: Verifieer compilatie**

Run: `./mvnw clean compile -pl services/berichtensessiecache -am`
Expected: Resources falen (volgende task).

---

### Task D4: Resources met fromHeader-parsing

**Files:**
- Modify: `services/berichtensessiecache/src/main/kotlin/.../berichten/BerichtensessiecacheResource.kt`
- Modify: `services/berichtensessiecache/src/main/kotlin/.../berichten/BerichtenOphalenResource.kt`

- [ ] **Step 1: Update BerichtenOphalenResource**

In `BerichtenOphalenResource.kt`, vervang de raw-header-validatie en service-aanroep:

```kotlin
@HeaderParam("X-Ontvanger") ontvanger: String?,
...
if (ontvanger.isNullOrBlank()) {
    throw WebApplicationException("Header 'X-Ontvanger' is verplicht.", Response.Status.BAD_REQUEST)
}
val ontvangerId = Identificatienummer.fromHeader(ontvanger)
service.ophalenBerichten(ontvangerId)
```

Imports toevoegen:
```kotlin
import nl.rijksoverheid.moz.fbs.common.identificatie.Identificatienummer
```

LDV `dataSubjectId` (zoek in dezelfde file) — zet op `ontvangerId.waarde`, niet `ontvanger` (de raw header met type-prefix).

- [ ] **Step 2: Update BerichtensessiecacheResource**

Doe hetzelfde voor `BerichtensessiecacheResource.kt`:
- `Identificatienummer.fromHeader(ontvanger)` aan resource-grens
- Service-aanroepen krijgen typed object
- LDV `dataSubjectId = ontvangerId.waarde`

Update ook de body-vs-header consistency-check in `BerichtensessiecacheResource.kt:155` (`"Ontvanger in body komt niet overeen met X-Ontvanger header"`): vergelijk `bericht.ontvanger` (raw uit body) met `ontvangerId.waarde` of `ontvangerId.toCanonicalString()`, afhankelijk van wat `bericht.ontvanger` bevat. Inspecteer eerst `Bericht.ontvanger`-veld:

Run: `grep -n "val ontvanger\|var ontvanger" services/berichtensessiecache/src/main/kotlin/nl/rijksoverheid/moz/fbs/berichtensessiecache/berichten/Bericht.kt`

Pas vergelijking aan zodat hij sluit aan op het bestaande formaat (waarschijnlijk raw waarde).

- [ ] **Step 3: Update bestaande sessiecache-tests met TYPE:WAARDE-headers**

Run:
```bash
grep -rln "X-Ontvanger.*\"[^B][^S][^N:]" services/berichtensessiecache/src/test --include="*.kt"
```

Voor elke gevonden test: vervang hardcoded `"999993653"`, `"cache-test"`, `"multi-ok-..."`, `"conflict-test-..."`, etc. door TYPE-prefixed varianten:
- Statische BSN's: `"BSN:999993653"`
- Generated test-names die niet geldig zijn als BSN — vervang door geldige BSN-pool of gebruik `"OIN:" + hash%-padding-to-20-digits`. Voorbeeld voor unique-per-test: `"BSN:" + listOf("999993653", "111222333", "123456782", "987654321")[index]` of generated met elfproef-helper.

Voor sessiecache-tests die alleen unique cache-key nodig hebben, gebruik `OIN:00000001003214345xxx` met `xxx` = aflopende variant — voldoet aan regex en isoleert tussen tests.

**Belangrijk**: BSN/RSIN vereisen elfproef. Gebruik vaste lijst geldige test-BSN's:
```
999993653, 999992426, 999991401, 999990286, 999996915
```
(Te genereren met `IdentificatienummerTest`-fixtures of Quarkus-config.) Voor isolatie tussen tests: prefix-cache-key niet via BSN-variatie maar via expliciete `MagazijnEvent`-test-utils.

- [ ] **Step 4: Verifieer sessiecache compileert**

Run: `./mvnw clean compile -pl services/berichtensessiecache -am`
Expected: BUILD SUCCESS.

- [ ] **Step 5: Voer sessiecache-tests uit**

Via MCP Maven (Redis-Testcontainer vereist):
- `pomFile`: `/home/claude/projects/moza-poc-fbs-berichtenbox/services/berichtensessiecache/pom.xml`
- `goals`: `["clean", "test"]`
- `args`: `["-am"]`
- env `PROJECT_DIR`: `/home/claude/projects/moza-poc-fbs-berichtenbox`

Expected: BUILD SUCCESS. Bij fail: lees gerapporteerde test-namen en fix headers in die files.

- [ ] **Step 6: Commit Stage C+D**

```bash
git add -A
git commit -m "refactor(sessiecache): typed Identificatienummer-signatures + spec-alignment

- X-Ontvanger header: TYPE:WAARDE-formaat (regex aligned met magazijn)
- BerichtenCache + Service signatures: ontvanger: Identificatienummer
- Resources: fromHeader-parsing aan grens; LDV dataSubjectId = .waarde
- Magazijn-config: verplichte afzenders-OIN-lijst per instance
- Test-fixtures + WireMock-resources: afzenders + TYPE-prefixed headers
- 503-response added to /berichten/ophalen path

Bereidt voor: MagazijnResolver introductie."
```

---

## Stage E — Sessiecache: MagazijnResolver

### Task E1: MagazijnResolver interface

**Files:**
- Create: `services/berichtensessiecache/src/main/kotlin/.../magazijn/MagazijnResolver.kt`

- [ ] **Step 1: Create interface**

Create `services/berichtensessiecache/src/main/kotlin/nl/rijksoverheid/moz/fbs/berichtensessiecache/magazijn/MagazijnResolver.kt`:

```kotlin
package nl.rijksoverheid.moz.fbs.berichtensessiecache.magazijn

import io.smallrye.mutiny.Uni
import nl.rijksoverheid.moz.fbs.common.identificatie.Identificatienummer

/**
 * Bepaalt welke magazijnen bevraagd worden voor een specifieke ontvanger.
 *
 * Default-implementatie ([ProfielMagazijnResolver]) raadpleegt de MOZA Profiel
 * Service voor dienstvoorkeuren en kruist die met de afzender-OIN-lijst per
 * geconfigureerd magazijn. OIN-ontvangers (B2B) slaan de Profiel-call over en
 * krijgen alle magazijn-IDs terug.
 *
 * Foutpaden: 200/404 zonder voorkeur leidt tot een lege set (caller toont lege
 * resultaten). 5xx/timeout/malformed leidt tot een [ProfielServiceFoutException]
 * (caller propageert 503 + Retry-After).
 */
interface MagazijnResolver {
    fun resolve(ontvanger: Identificatienummer): Uni<Set<String>>
}
```

- [ ] **Step 2: Verifieer compilatie**

Run: `./mvnw clean compile -pl services/berichtensessiecache -am`
Expected: BUILD SUCCESS.

---

### Task E2: ProfielMagazijnResolver — TDD test eerst

**Files:**
- Create: `services/berichtensessiecache/src/test/kotlin/.../magazijn/ProfielMagazijnResolverTest.kt`
- Create: `services/berichtensessiecache/src/main/kotlin/.../magazijn/ProfielMagazijnResolver.kt`

- [ ] **Step 1: Schrijf falende tests**

Create `services/berichtensessiecache/src/test/kotlin/nl/rijksoverheid/moz/fbs/berichtensessiecache/magazijn/ProfielMagazijnResolverTest.kt`:

```kotlin
package nl.rijksoverheid.moz.fbs.berichtensessiecache.magazijn

import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import jakarta.ws.rs.ProcessingException
import jakarta.ws.rs.WebApplicationException
import jakarta.ws.rs.core.Response
import nl.rijksoverheid.moz.fbs.common.identificatie.Bsn
import nl.rijksoverheid.moz.fbs.common.identificatie.Kvk
import nl.rijksoverheid.moz.fbs.common.identificatie.Oin
import nl.rijksoverheid.moz.fbs.common.identificatie.Rsin
import nl.rijksoverheid.moz.fbs.common.profiel.IdentificatieResponse
import nl.rijksoverheid.moz.fbs.common.profiel.PartijResponse
import nl.rijksoverheid.moz.fbs.common.profiel.ProfielServiceClient
import nl.rijksoverheid.moz.fbs.common.profiel.ProfielServiceFoutException
import nl.rijksoverheid.moz.fbs.common.profiel.ScopeResponse
import nl.rijksoverheid.moz.fbs.common.profiel.VoorkeurResponse
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import java.time.Duration

class ProfielMagazijnResolverTest {

    private val profielClient = mockk<ProfielServiceClient>()
    private val factory = mockk<MagazijnClientFactory>(relaxed = true).also {
        every { it.getAllClients() } returns mapOf(
            "magazijn-a" to mockk(),
            "magazijn-b" to mockk(),
        )
        every { it.getAlleAfzenders() } returns mapOf(
            "magazijn-a" to setOf(Oin("00000001003214345000")),
            "magazijn-b" to setOf(Oin("00000001823288444000")),
        )
    }
    private val resolver = ProfielMagazijnResolver(profielClient, factory)

    @Test
    fun `BSN met 1 OIN-match levert 1 magazijn`() {
        every { profielClient.getPartij("BSN", "999993653") } returns PartijResponse(
            voorkeuren = listOf(
                VoorkeurResponse(
                    voorkeurType = "OntvangViaBerichtenbox",
                    waarde = "true",
                    scopes = listOf(ScopeResponse(partij = IdentificatieResponse("OIN", "00000001003214345000"))),
                ),
            ),
        )
        val result = resolver.resolve(Bsn("999993653")).await().atMost(Duration.ofSeconds(2))
        assertEquals(setOf("magazijn-a"), result)
    }

    @Test
    fun `BSN met 2 OIN-matches levert 2 magazijnen`() {
        every { profielClient.getPartij("BSN", "999993653") } returns PartijResponse(
            voorkeuren = listOf(
                VoorkeurResponse(
                    "OntvangViaBerichtenbox", "true",
                    scopes = listOf(
                        ScopeResponse(partij = IdentificatieResponse("OIN", "00000001003214345000")),
                        ScopeResponse(partij = IdentificatieResponse("OIN", "00000001823288444000")),
                    ),
                ),
            ),
        )
        val result = resolver.resolve(Bsn("999993653")).await().atMost(Duration.ofSeconds(2))
        assertEquals(setOf("magazijn-a", "magazijn-b"), result)
    }

    @Test
    fun `BSN met scope-OIN buiten config-lijst levert lege set`() {
        every { profielClient.getPartij("BSN", "999993653") } returns PartijResponse(
            voorkeuren = listOf(
                VoorkeurResponse(
                    "OntvangViaBerichtenbox", "true",
                    scopes = listOf(ScopeResponse(partij = IdentificatieResponse("OIN", "99999999999999999999"))),
                ),
            ),
        )
        val result = resolver.resolve(Bsn("999993653")).await().atMost(Duration.ofSeconds(2))
        assertEquals(emptySet<String>(), result)
    }

    @Test
    fun `BSN met voorkeur false levert lege set`() {
        every { profielClient.getPartij("BSN", "999993653") } returns PartijResponse(
            voorkeuren = listOf(
                VoorkeurResponse(
                    "OntvangViaBerichtenbox", "false",
                    scopes = listOf(ScopeResponse(partij = IdentificatieResponse("OIN", "00000001003214345000"))),
                ),
            ),
        )
        val result = resolver.resolve(Bsn("999993653")).await().atMost(Duration.ofSeconds(2))
        assertEquals(emptySet<String>(), result)
    }

    @Test
    fun `BSN met case-variant TRUE en Ja is opt-in`() {
        every { profielClient.getPartij("BSN", "999993653") } returns PartijResponse(
            voorkeuren = listOf(
                VoorkeurResponse(
                    "OntvangViaBerichtenbox", "TRUE",
                    scopes = listOf(ScopeResponse(partij = IdentificatieResponse("OIN", "00000001003214345000"))),
                ),
                VoorkeurResponse(
                    "OntvangViaBerichtenbox", "Ja",
                    scopes = listOf(ScopeResponse(partij = IdentificatieResponse("OIN", "00000001823288444000"))),
                ),
            ),
        )
        val result = resolver.resolve(Bsn("999993653")).await().atMost(Duration.ofSeconds(2))
        assertEquals(setOf("magazijn-a", "magazijn-b"), result)
    }

    @Test
    fun `BSN met YES is geen opt-in`() {
        every { profielClient.getPartij("BSN", "999993653") } returns PartijResponse(
            voorkeuren = listOf(
                VoorkeurResponse(
                    "OntvangViaBerichtenbox", "YES",
                    scopes = listOf(ScopeResponse(partij = IdentificatieResponse("OIN", "00000001003214345000"))),
                ),
            ),
        )
        val result = resolver.resolve(Bsn("999993653")).await().atMost(Duration.ofSeconds(2))
        assertEquals(emptySet<String>(), result)
    }

    @Test
    fun `BSN met lege voorkeuren levert lege set`() {
        every { profielClient.getPartij("BSN", "999993653") } returns PartijResponse(voorkeuren = emptyList())
        val result = resolver.resolve(Bsn("999993653")).await().atMost(Duration.ofSeconds(2))
        assertEquals(emptySet<String>(), result)
    }

    @Test
    fun `BSN met andere voorkeurType wordt genegeerd`() {
        every { profielClient.getPartij("BSN", "999993653") } returns PartijResponse(
            voorkeuren = listOf(
                VoorkeurResponse(
                    "WebsiteTaal", "nl",
                    scopes = listOf(ScopeResponse(partij = IdentificatieResponse("OIN", "00000001003214345000"))),
                ),
            ),
        )
        val result = resolver.resolve(Bsn("999993653")).await().atMost(Duration.ofSeconds(2))
        assertEquals(emptySet<String>(), result)
    }

    @Test
    fun `BSN met scope-identificatieType KVK wordt genegeerd`() {
        every { profielClient.getPartij("BSN", "999993653") } returns PartijResponse(
            voorkeuren = listOf(
                VoorkeurResponse(
                    "OntvangViaBerichtenbox", "true",
                    scopes = listOf(ScopeResponse(partij = IdentificatieResponse("KVK", "12345678"))),
                ),
            ),
        )
        val result = resolver.resolve(Bsn("999993653")).await().atMost(Duration.ofSeconds(2))
        assertEquals(emptySet<String>(), result)
    }

    @Test
    fun `OIN-ontvanger skipt Profiel-call en levert alle magazijnen`() {
        val result = resolver.resolve(Oin("00000001003214345000")).await().atMost(Duration.ofSeconds(2))
        assertEquals(setOf("magazijn-a", "magazijn-b"), result)
        verify(exactly = 0) { profielClient.getPartij(any(), any()) }
    }

    @Test
    fun `RSIN-ontvanger gebruikt RSIN-pad in Profiel-call`() {
        every { profielClient.getPartij("RSIN", "002564440") } returns PartijResponse(voorkeuren = emptyList())
        resolver.resolve(Rsin("002564440")).await().atMost(Duration.ofSeconds(2))
        verify(exactly = 1) { profielClient.getPartij("RSIN", "002564440") }
    }

    @Test
    fun `KVK-ontvanger gebruikt KVK-pad in Profiel-call`() {
        every { profielClient.getPartij("KVK", "12345678") } returns PartijResponse(voorkeuren = emptyList())
        resolver.resolve(Kvk("12345678")).await().atMost(Duration.ofSeconds(2))
        verify(exactly = 1) { profielClient.getPartij("KVK", "12345678") }
    }

    @Test
    fun `404 van Profiel levert lege set zonder fout`() {
        every { profielClient.getPartij("BSN", "999993653") } throws WebApplicationException(Response.status(404).build())
        val result = resolver.resolve(Bsn("999993653")).await().atMost(Duration.ofSeconds(2))
        assertEquals(emptySet<String>(), result)
    }

    @Test
    fun `500 van Profiel werpt ProfielServiceFoutException`() {
        every { profielClient.getPartij("BSN", "999993653") } throws WebApplicationException(Response.status(500).build())
        assertThrows(ProfielServiceFoutException::class.java) {
            resolver.resolve(Bsn("999993653")).await().atMost(Duration.ofSeconds(2))
        }
    }

    @Test
    fun `403 van Profiel werpt ProfielServiceFoutException (niet-404 = infra-fout)`() {
        every { profielClient.getPartij("BSN", "999993653") } throws WebApplicationException(Response.status(403).build())
        assertThrows(ProfielServiceFoutException::class.java) {
            resolver.resolve(Bsn("999993653")).await().atMost(Duration.ofSeconds(2))
        }
    }

    @Test
    fun `ProcessingException van Profiel werpt ProfielServiceFoutException`() {
        every { profielClient.getPartij("BSN", "999993653") } throws ProcessingException("connection reset")
        assertThrows(ProfielServiceFoutException::class.java) {
            resolver.resolve(Bsn("999993653")).await().atMost(Duration.ofSeconds(2))
        }
    }
}
```

- [ ] **Step 2: Run test (moet falen)**

Run: `./mvnw test -pl services/berichtensessiecache -Dtest=ProfielMagazijnResolverTest`
Expected: COMPILE FAIL — `ProfielMagazijnResolver` bestaat nog niet.

- [ ] **Step 3: Implementeer ProfielMagazijnResolver**

Create `services/berichtensessiecache/src/main/kotlin/nl/rijksoverheid/moz/fbs/berichtensessiecache/magazijn/ProfielMagazijnResolver.kt`:

```kotlin
package nl.rijksoverheid.moz.fbs.berichtensessiecache.magazijn

import io.smallrye.mutiny.Uni
import io.smallrye.mutiny.infrastructure.Infrastructure
import jakarta.enterprise.context.ApplicationScoped
import jakarta.ws.rs.ProcessingException
import jakarta.ws.rs.WebApplicationException
import nl.rijksoverheid.moz.fbs.common.identificatie.Identificatienummer
import nl.rijksoverheid.moz.fbs.common.identificatie.IdentificatienummerType
import nl.rijksoverheid.moz.fbs.common.profiel.PartijResponse
import nl.rijksoverheid.moz.fbs.common.profiel.ProfielServiceClient
import nl.rijksoverheid.moz.fbs.common.profiel.ProfielServiceFoutException
import org.eclipse.microprofile.rest.client.inject.RestClient
import org.jboss.logging.Logger

@ApplicationScoped
class ProfielMagazijnResolver(
    @RestClient private val profielClient: ProfielServiceClient,
    private val clientFactory: MagazijnClientFactory,
) : MagazijnResolver {

    private val log = Logger.getLogger(ProfielMagazijnResolver::class.java)

    override fun resolve(ontvanger: Identificatienummer): Uni<Set<String>> {
        // OIN-ontvanger (B2B): geen Profiel-pad bestaat upstream; lever alle magazijnen.
        if (ontvanger.type == IdentificatienummerType.OIN) {
            return Uni.createFrom().item(clientFactory.getAllClients().keys)
        }

        val profielType = naarProfielType(ontvanger.type)
        return Uni.createFrom().item { profielClient.getPartij(profielType, ontvanger.waarde) }
            .runSubscriptionOn(Infrastructure.getDefaultWorkerPool())
            .map { partij -> bepaalMagazijnen(partij) }
            .onFailure(WebApplicationException::class.java).recoverWithUni { error ->
                val webEx = error as WebApplicationException
                if (webEx.response?.status == 404) {
                    log.debugf("Profiel-service 404 voor type=%s; geen voorkeuren bekend", profielType)
                    Uni.createFrom().item(emptySet<String>())
                } else {
                    Uni.createFrom().failure(
                        ProfielServiceFoutException(
                            "Profiel-service onbereikbaar (HTTP ${webEx.response?.status})",
                            error,
                        ),
                    )
                }
            }
            .onFailure(ProcessingException::class.java).recoverWithUni { error ->
                Uni.createFrom().failure(
                    ProfielServiceFoutException("Profiel-service onbereikbaar (netwerkfout)", error),
                )
            }
            // Onder andere malformed JSON komt als RuntimeException; vang restcategorie.
            .onFailure { it !is ProfielServiceFoutException }.recoverWithUni { error ->
                Uni.createFrom().failure(
                    ProfielServiceFoutException("Profiel-service onleesbaar antwoord", error),
                )
            }
    }

    private fun bepaalMagazijnen(partij: PartijResponse): Set<String> {
        val optedInOins = partij.voorkeuren
            .filter { it.voorkeurType == VOORKEUR_ONTVANG_BERICHTEN }
            .filter { it.waarde?.lowercase() in INGESCHAKELDE_WAARDEN }
            .flatMap { it.scopes }
            .mapNotNull { it.partij }
            .filter { it.identificatieType == "OIN" }
            .map { it.identificatieNummer }
            .toSet()

        if (optedInOins.isEmpty()) return emptySet()

        return clientFactory.getAlleAfzenders()
            .filter { (_, afzenders) -> afzenders.any { it.waarde in optedInOins } }
            .keys
    }

    /**
     * Mapping naar het externe profiel-contract. Expliciete `when`-vorm (geen
     * `.name`) zodat een hernoeming in de interne enum het externe contract
     * niet stilletjes breekt. OIN wordt al door de caller afgevangen voordat
     * deze functie geraakt wordt.
     */
    private fun naarProfielType(type: IdentificatienummerType): String = when (type) {
        IdentificatienummerType.BSN -> "BSN"
        IdentificatienummerType.RSIN -> "RSIN"
        IdentificatienummerType.KVK -> "KVK"
        IdentificatienummerType.OIN -> error("OIN-ontvanger moet vóór Profiel-call afgevangen worden")
    }

    companion object {
        private const val VOORKEUR_ONTVANG_BERICHTEN = "OntvangViaBerichtenbox"
        private val INGESCHAKELDE_WAARDEN = setOf("true", "ja")
    }
}
```

- [ ] **Step 4: Run unit-tests (moeten slagen)**

Run: `./mvnw test -pl services/berichtensessiecache -Dtest=ProfielMagazijnResolverTest -am`
Expected: BUILD SUCCESS, alle 15 tests pass.

- [ ] **Step 5: Commit**

```bash
git add -A
git commit -m "feat(sessiecache): ProfielMagazijnResolver — voorkeur-gestuurde magazijn-keuze

Plugbare resolver bepaalt op basis van dienstvoorkeuren uit de Profiel
Service welke magazijnen bevraagd worden. OIN-ontvangers slaan Profiel
over (B2B); BSN/RSIN/KVK doorlopen voorkeur-filter; 404 → lege set;
infra-fout → ProfielServiceFoutException.

Issue #416."
```

---

### Task E3: WireMockProfielServiceResource + MockProfielServiceClient

**Files:**
- Create: `services/berichtensessiecache/src/test/kotlin/.../magazijn/WireMockProfielServiceResource.kt`
- Create: `services/berichtensessiecache/src/test/kotlin/.../magazijn/MockProfielServiceClient.kt`

- [ ] **Step 1: WireMockProfielServiceResource**

Create:

```kotlin
package nl.rijksoverheid.moz.fbs.berichtensessiecache.magazijn

import com.github.tomakehurst.wiremock.WireMockServer
import com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig
import io.quarkus.test.common.QuarkusTestResourceLifecycleManager

class WireMockProfielServiceResource : QuarkusTestResourceLifecycleManager {

    companion object {
        var server: WireMockServer? = null
    }

    override fun start(): Map<String, String> {
        val s = WireMockServer(wireMockConfig().dynamicPort())
        s.start()
        server = s
        return mapOf("quarkus.rest-client.profiel-service.url" to s.baseUrl())
    }

    override fun stop() {
        server?.stop()
        server = null
    }
}
```

- [ ] **Step 2: MockProfielServiceClient**

Lees `services/berichtenmagazijn/src/test/kotlin/nl/rijksoverheid/moz/fbs/berichtenmagazijn/validatie/MockProfielServiceClient.kt` als template.

Create `services/berichtensessiecache/src/test/kotlin/nl/rijksoverheid/moz/fbs/berichtensessiecache/magazijn/MockProfielServiceClient.kt`:

```kotlin
package nl.rijksoverheid.moz.fbs.berichtensessiecache.magazijn

import io.quarkus.test.Mock
import jakarta.enterprise.context.ApplicationScoped
import nl.rijksoverheid.moz.fbs.common.profiel.IdentificatieResponse
import nl.rijksoverheid.moz.fbs.common.profiel.PartijResponse
import nl.rijksoverheid.moz.fbs.common.profiel.ProfielServiceClient
import nl.rijksoverheid.moz.fbs.common.profiel.ScopeResponse
import nl.rijksoverheid.moz.fbs.common.profiel.VoorkeurResponse
import org.eclipse.microprofile.rest.client.inject.RegisterRestClient

/**
 * CDI-`@Mock`-bean die de echte REST-client vervangt in tests. Default-respons
 * geeft elke ontvanger toestemming voor beide configured-magazijn-OINs zodat
 * bestaande BerichtenOphalenResourceTest-cases identieke aggregatie houden.
 *
 * WireMockProfielServiceTestProfile schakelt deze mock uit (via
 * `quarkus.arc.exclude-types`) zodat de echte REST-client onder test komt.
 */
@Mock
@ApplicationScoped
@RegisterRestClient(configKey = "profiel-service")
open class MockProfielServiceClient : ProfielServiceClient {
    override fun getPartij(identificatieType: String, identificatieNummer: String): PartijResponse =
        PartijResponse(
            voorkeuren = listOf(
                VoorkeurResponse(
                    voorkeurType = "OntvangViaBerichtenbox",
                    waarde = "true",
                    scopes = listOf(
                        ScopeResponse(partij = IdentificatieResponse("OIN", "00000001003214345000")),
                        ScopeResponse(partij = IdentificatieResponse("OIN", "00000001823288444000")),
                    ),
                ),
            ),
        )
}
```

- [ ] **Step 3: Verifieer compilatie**

Run: `./mvnw test-compile -pl services/berichtensessiecache -am`
Expected: BUILD SUCCESS.

---

### Task E4: ProfielMagazijnResolverIntegrationTest (WireMock + QuarkusTest)

**Files:**
- Create: `services/berichtensessiecache/src/test/kotlin/.../magazijn/ProfielMagazijnResolverIntegrationTest.kt`

- [ ] **Step 1: Schrijf integratietest**

Create:

```kotlin
package nl.rijksoverheid.moz.fbs.berichtensessiecache.magazijn

import com.github.tomakehurst.wiremock.client.WireMock.aResponse
import com.github.tomakehurst.wiremock.client.WireMock.get
import com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo
import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.junit.QuarkusTestProfile
import io.quarkus.test.junit.TestProfile
import jakarta.inject.Inject
import nl.rijksoverheid.moz.fbs.common.identificatie.Bsn
import nl.rijksoverheid.moz.fbs.common.profiel.ProfielServiceFoutException
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.time.Duration

@QuarkusTest
@TestProfile(WireMockProfielServiceTestProfile::class)
@QuarkusTestResource(WireMockProfielServiceResource::class)
@QuarkusTestResource(WireMockMagazijnResource::class)
class ProfielMagazijnResolverIntegrationTest {

    @Inject
    lateinit var resolver: MagazijnResolver

    private val wireMock get() = WireMockProfielServiceResource.server!!

    @BeforeEach
    fun resetStubs() {
        wireMock.resetAll()
    }

    @Test
    fun `200 met opted-in voorkeur retourneert magazijn-a`() {
        wireMock.stubFor(
            get(urlEqualTo("/api/profielservice/v1/BSN/999993653")).willReturn(
                aResponse().withStatus(200).withHeader("Content-Type", "application/json").withBody(
                    """
                    {
                      "partijId": 1,
                      "voorkeuren": [
                        { "voorkeurType": "OntvangViaBerichtenbox", "waarde": "true",
                          "scopes": [ { "partij": { "identificatieType": "OIN", "identificatieNummer": "00000001003214345000" } } ] }
                      ]
                    }
                    """.trimIndent(),
                ),
            ),
        )
        val result = resolver.resolve(Bsn("999993653")).await().atMost(Duration.ofSeconds(5))
        assertEquals(setOf("magazijn-a"), result)
    }

    @Test
    fun `404 retourneert lege set`() {
        wireMock.stubFor(
            get(urlEqualTo("/api/profielservice/v1/BSN/999993653"))
                .willReturn(aResponse().withStatus(404)),
        )
        val result = resolver.resolve(Bsn("999993653")).await().atMost(Duration.ofSeconds(5))
        assertEquals(emptySet<String>(), result)
    }

    @Test
    fun `500 werpt ProfielServiceFoutException`() {
        wireMock.stubFor(
            get(urlEqualTo("/api/profielservice/v1/BSN/999993653"))
                .willReturn(aResponse().withStatus(500)),
        )
        assertThrows(ProfielServiceFoutException::class.java) {
            resolver.resolve(Bsn("999993653")).await().atMost(Duration.ofSeconds(20))
        }
    }

    @Test
    fun `malformed JSON werpt ProfielServiceFoutException`() {
        wireMock.stubFor(
            get(urlEqualTo("/api/profielservice/v1/BSN/999993653"))
                .willReturn(aResponse().withStatus(200).withHeader("Content-Type", "application/json").withBody("{this is not json")),
        )
        assertThrows(ProfielServiceFoutException::class.java) {
            resolver.resolve(Bsn("999993653")).await().atMost(Duration.ofSeconds(5))
        }
    }
}

class WireMockProfielServiceTestProfile : QuarkusTestProfile {
    override fun getConfigOverrides(): Map<String, String> = mapOf(
        "quarkus.arc.exclude-types" to MockProfielServiceClient::class.java.name,
    )
}
```

- [ ] **Step 2: Run integratietest (host-runner)**

Via MCP Maven (Testcontainers + Redis):
- `pomFile`: `/home/claude/projects/moza-poc-fbs-berichtenbox/services/berichtensessiecache/pom.xml`
- `goals`: `["test"]`
- `args`: `["-Dtest=ProfielMagazijnResolverIntegrationTest", "-am"]`
- env `PROJECT_DIR`: `/home/claude/projects/moza-poc-fbs-berichtenbox`

Expected: BUILD SUCCESS, alle 4 tests pass.

---

### Task E5: Wire MagazijnResolver in BerichtensessiecacheService.ophalenBerichten

**Files:**
- Modify: `services/berichtensessiecache/src/main/kotlin/.../berichten/BerichtensessiecacheService.kt`

- [ ] **Step 1: Inject resolver in constructor**

```kotlin
@ApplicationScoped
class BerichtensessiecacheService(
    private val berichtenCache: BerichtenCache,
    private val clientFactory: MagazijnClientFactory,
    private val resolver: MagazijnResolver,
) {
```

- [ ] **Step 2: Update ophalenBerichten met resolver-stap**

Vervang de body van `ophalenBerichten` om resolver in te schakelen vlak na de lock-set:

```kotlin
fun ophalenBerichten(ontvanger: Identificatienummer): Multi<MagazijnEvent> {
    val cacheKey = BerichtenCache.cacheKey(ontvanger)

    val bezigStatus = AggregationStatus(
        status = OphalenStatus.BEZIG,
        totaalMagazijnen = 0,
    )

    val wasSet = berichtenCache.trySetAggregationStatus(cacheKey, bezigStatus)
        .await().atMost(Duration.ofSeconds(5))

    if (!wasSet) {
        throw WebApplicationException(
            "Berichten worden momenteel al opgehaald voor deze ontvanger. Wacht tot het ophalen is afgerond.",
            409,
        )
    }

    val resolvedIds = try {
        resolver.resolve(ontvanger).await().atMost(Duration.ofSeconds(20))
    } catch (ex: ProfielServiceFoutException) {
        // Lock vrijgeven via storeAggregationStatus (doet del(lockKey) intern).
        berichtenCache.storeAggregationStatus(
            cacheKey,
            AggregationStatus(status = OphalenStatus.FOUT, totaalMagazijnen = 0),
        ).await().indefinitely()
        throw ex
    }

    val clients = clientFactory.getAllClients().filterKeys { it in resolvedIds }
    val alleBerichten = ConcurrentLinkedQueue<Bericht>()
    val geslaagd = AtomicInteger(0)
    val mislukt = AtomicInteger(0)

    // Geen magazijnen → lege resultaten + GEREED-status. Cache overschrijven met
    // lege lijst zodat eventuele stale data uit eerdere sessies niet zichtbaar
    // blijft via GET-endpoints.
    if (clients.isEmpty()) {
        berichtenCache.store(cacheKey, emptyList()).await().indefinitely()
        berichtenCache.storeAggregationStatus(
            cacheKey,
            AggregationStatus(status = OphalenStatus.GEREED, totaalMagazijnen = 0, geslaagd = 0, mislukt = 0),
        ).await().indefinitely()
        return Multi.createFrom().item(
            MagazijnEvent(
                event = EventType.OPHALEN_GEREED,
                totaalBerichten = 0,
                geslaagd = 0,
                mislukt = 0,
                totaalMagazijnen = 0,
            ),
        )
    }

    // Update aantal magazijnen in lopende BEZIG-status (info-only; volgende
    // storeAggregationStatus overschrijft 'm bij voltooiing).
    berichtenCache.storeAggregationStatus(
        cacheKey,
        bezigStatus.copy(totaalMagazijnen = clients.size),
    ).await().indefinitely()
    // N.B. trySetAggregationStatus heeft lock én status gezet; storeAggregationStatus
    // doet del(lockKey). Daarom hieronder opnieuw een lock zetten — anders kan
    // concurrent ophaal nu inkomen. Maak een aparte updateAggregationStatus zónder
    // lock-cleanup als dit een probleem blijkt; voor PoC volstaat dit (BEZIG blijft
    // tot GEREED/FOUT).

    val magazijnStreams = clients.map { (magazijnId, client) ->
        // ... bestaande magazijn-stream-logica
    }

    // ... bestaande pipeline-vervolg
}
```

**Lock-aware status-update**: de bestaande `storeAggregationStatus` doet `del(lockKey)` (zie `BerichtenCache.kt:135`). Voor de mid-flow BEZIG-update mag de lock NIET vrij — anders kan een concurrent ophaal hier inkomen. Voeg een nieuwe `updateAggregationStatus(key, status)` toe die uitsluitend het status-record overschrijft:

```kotlin
override fun updateAggregationStatus(key: String, status: AggregationStatus): Uni<Void> {
    val statusKey = statusKey(key)
    val json = objectMapper.writeValueAsString(status)
    return redis.value(String::class.java).setex(statusKey, ttl.seconds, json)
        .replaceWithVoid()
        .onFailure().invoke { e -> log.errorf(e, "Redis updateAggregationStatus mislukt voor key=%s", key) }
}
```

In de interface:
```kotlin
fun updateAggregationStatus(key: String, status: AggregationStatus): Uni<Void>
```

Update `MockBerichtenCache` met deze nieuwe methode.

In service: gebruik `berichtenCache.updateAggregationStatus(...)` voor de BEZIG-mid-flow-update; behoud `storeAggregationStatus(...)` voor de GEREED/FOUT-finale (die lock vrijgeeft).

- [ ] **Step 3: Imports en compileer**

Voeg imports toe:
```kotlin
import nl.rijksoverheid.moz.fbs.common.identificatie.Identificatienummer
import nl.rijksoverheid.moz.fbs.common.profiel.ProfielServiceFoutException
```

Run: `./mvnw clean compile -pl services/berichtensessiecache -am`
Expected: BUILD SUCCESS.

---

### Task E6: Service-integratietest voor resolver-paden

**Files:**
- Modify: `services/berichtensessiecache/src/test/kotlin/.../berichten/BerichtensessiecacheServiceTest.kt`

- [ ] **Step 1: Voeg test voor `emptySet`-pad**

Voeg toe aan `BerichtensessiecacheServiceTest`:

```kotlin
@Test
fun `lege resolver-set leidt tot OPHALEN_GEREED met totaal 0`() {
    val ontvanger = Bsn("999993653")
    every { resolver.resolve(ontvanger) } returns Uni.createFrom().item(emptySet<String>())

    val events = service.ophalenBerichten(ontvanger).collect().asList().await().atMost(Duration.ofSeconds(5))

    assertEquals(1, events.size)
    assertEquals(EventType.OPHALEN_GEREED, events[0].event)
    assertEquals(0, events[0].totaalBerichten)
    assertEquals(0, events[0].totaalMagazijnen)
    verify { berichtenCache.store(any(), emptyList()) }
}
```

- [ ] **Step 2: Voeg test voor `ProfielServiceFoutException`-pad**

```kotlin
@Test
fun `ProfielServiceFoutException uit resolver propageert en zet FOUT-status`() {
    val ontvanger = Bsn("999993653")
    every { resolver.resolve(ontvanger) } returns
        Uni.createFrom().failure(ProfielServiceFoutException("upstream 500"))

    assertThrows(ProfielServiceFoutException::class.java) {
        service.ophalenBerichten(ontvanger).collect().asList().await().atMost(Duration.ofSeconds(5))
    }
    verify {
        berichtenCache.storeAggregationStatus(
            any(),
            match { it.status == OphalenStatus.FOUT },
        )
    }
}
```

- [ ] **Step 3: Pas bestaande happy-path tests aan**

Tests die `ophalenBerichten` direct aanroepen, moeten nu een gemockte resolver hebben die alle magazijnen retourneert:

```kotlin
every { resolver.resolve(any()) } returns Uni.createFrom().item(setOf("magazijn-a", "magazijn-b"))
```

Plus `String`-ontvanger-args → `Bsn("999993653")` etc.

- [ ] **Step 4: Run tests**

Via MCP Maven:
- `goals`: `["test"]`, `args`: `["-Dtest=BerichtensessiecacheServiceTest", "-am"]`

Expected: BUILD SUCCESS.

---

### Task E7: E2E-tests in BerichtenOphalenResourceTest

**Files:**
- Modify: `services/berichtensessiecache/src/test/kotlin/.../berichten/BerichtenOphalenResourceTest.kt`

- [ ] **Step 1: Update bestaande tests naar TYPE:WAARDE-headers**

Vervang hardcoded `"999993653"` (en andere) door `"BSN:999993653"` etc. in alle `.header("X-Ontvanger", …)`-calls.

- [ ] **Step 2: Voeg test voor 200/lege profiel**

```kotlin
@Test
fun `BSN met lege voorkeuren krijgt OPHALEN_GEREED 0_0_0`() {
    WireMockProfielServiceResource.server!!.stubFor(
        get(urlEqualTo("/api/profielservice/v1/BSN/999993653"))
            .willReturn(aResponse().withStatus(200).withBody("""{"voorkeuren": []}""")),
    )

    val response = given()
        .header("X-Ontvanger", "BSN:999993653")
        .`when`().post("/api/v1/berichten/ophalen")
        .then().statusCode(200)
        .extract().asString()

    assertTrue(response.contains("\"event\":\"OPHALEN_GEREED\""))
    assertTrue(response.contains("\"totaalBerichten\":0"))
}
```

- [ ] **Step 3: Voeg test voor 500/Profiel-fout**

```kotlin
@Test
fun `BSN met Profiel-500 retourneert 503 met Retry-After`() {
    WireMockProfielServiceResource.server!!.stubFor(
        get(urlEqualTo("/api/profielservice/v1/BSN/999993653"))
            .willReturn(aResponse().withStatus(500)),
    )

    given()
        .header("X-Ontvanger", "BSN:999993653")
        .`when`().post("/api/v1/berichten/ophalen")
        .then().statusCode(503)
        .header("Retry-After", "30")
        .contentType(containsString("application/problem+json"))
        .body("type", containsString("profiel-service-onbereikbaar"))
}
```

- [ ] **Step 4: Voeg test voor invalide header**

```kotlin
@Test
fun `X-Ontvanger zonder type-prefix retourneert 400`() {
    given()
        .header("X-Ontvanger", "999993653")
        .`when`().post("/api/v1/berichten/ophalen")
        .then().statusCode(400)
        .body("detail", containsString("X-Ontvanger"))
}
```

- [ ] **Step 5: Voeg test voor OIN-ontvanger zonder Profiel-call**

```kotlin
@Test
fun `OIN-ontvanger bevraagt alle magazijnen zonder Profiel-call`() {
    given()
        .header("X-Ontvanger", "OIN:00000001003214345000")
        .`when`().post("/api/v1/berichten/ophalen")
        .then().statusCode(200)

    WireMockProfielServiceResource.server!!.verify(0, getRequestedFor(urlEqualTo("/api/profielservice/v1/OIN/00000001003214345000")))
}
```

Voeg `@QuarkusTestResource(WireMockProfielServiceResource::class)` toe aan de klasse-annotaties.

- [ ] **Step 6: Run E2E-tests**

Via MCP Maven met dezelfde args als Task E4. Expected: BUILD SUCCESS.

---

### Task E8: OpenApiContractTest uitbreiding

**Files:**
- Modify: `services/berichtensessiecache/src/test/kotlin/.../berichten/OpenApiContractTest.kt`

- [ ] **Step 1: Voeg test dat 503-response Problem-schema matcht**

```kotlin
@Test
fun `503 op berichten ophalen matcht Problem-schema`() {
    WireMockProfielServiceResource.server!!.stubFor(
        get(urlEqualTo("/api/profielservice/v1/BSN/999993653"))
            .willReturn(aResponse().withStatus(500)),
    )

    given()
        .header("X-Ontvanger", "BSN:999993653")
        .`when`().post("/api/v1/berichten/ophalen")
        .then().assertThat()
        .body(matchesJsonSchemaInClasspath("openapi/berichtensessiecache-api.yaml"))
}
```

(Gebruik bestaande `swagger-request-validator-restassured`-pattern uit andere `OpenApiContractTest`-bestanden in het project als template.)

- [ ] **Step 2: Run + verify Spectral-linter laatste keer**

```bash
npx @stoplight/spectral-cli lint \
  services/berichtensessiecache/src/main/resources/openapi/berichtensessiecache-api.yaml \
  --ruleset https://static.developer.overheid.nl/adr/ruleset.yaml
```

Expected: 0 errors.

---

### Task E9: WireMock-mappings + Bruno-collectie

**Files:**
- Create: `wiremock/profiel-service/mappings/get-partij-onbekend-404.json`
- Create: `wiremock/profiel-service/mappings/get-partij-server-error-500.json`
- Create: `bruno/berichtensessiecache/berichten/ophalen-zonder-voorkeur.bru`
- Create: `bruno/berichtensessiecache/berichten/ophalen-profiel-fout.bru`

- [ ] **Step 1: WireMock-mapping 404**

Create `wiremock/profiel-service/mappings/get-partij-onbekend-404.json`:

```json
{
  "request": {
    "method": "GET",
    "urlPattern": "/api/profielservice/v1/BSN/111222333"
  },
  "response": {
    "status": 404
  }
}
```

- [ ] **Step 2: WireMock-mapping 500**

Create `wiremock/profiel-service/mappings/get-partij-server-error-500.json`:

```json
{
  "request": {
    "method": "GET",
    "urlPattern": "/api/profielservice/v1/BSN/444555666"
  },
  "response": {
    "status": 500,
    "body": "internal server error"
  }
}
```

**N.B.** BSN `111222333` en `444555666` zijn syntactisch-correcte 9-cijfers maar wijken af van de elfproef-validatie. Voor sessiecache komen ze niet langs de service-laag wanneer de service de header door `Identificatienummer.fromHeader` ontleedt — `Bsn` constructor zou ze verwerpen. Kies bewust elfproef-geldige BSN's voor deze mappings als ze door de hele stack moeten lopen, anders blijf bij syntactisch-juiste-maar-elfproef-falende waarden voor pure-Profiel-call-stubs.

Voor deze ticket: gebruik elfproef-geldige BSN's `999992426` (404-pad) en `999991401` (500-pad).

Update beide mappings:
- 404: `urlPattern: /api/profielservice/v1/BSN/999992426`
- 500: `urlPattern: /api/profielservice/v1/BSN/999991401`

- [ ] **Step 3: Bruno-request voor lege voorkeuren**

Create `bruno/berichtensessiecache/berichten/ophalen-zonder-voorkeur.bru`:

```
meta {
  name: Ophalen zonder voorkeur (lege resultaten)
  type: http
  seq: 5
}

post {
  url: {{baseUrl}}/api/v1/berichten/ophalen
  body: none
  auth: none
}

headers {
  X-Ontvanger: BSN:999992426
}

assert {
  res.status: eq 200
}
```

- [ ] **Step 4: Bruno-request voor Profiel-fout**

Create `bruno/berichtensessiecache/berichten/ophalen-profiel-fout.bru`:

```
meta {
  name: Ophalen met Profiel-503-pad
  type: http
  seq: 6
}

post {
  url: {{baseUrl}}/api/v1/berichten/ophalen
  body: none
  auth: none
}

headers {
  X-Ontvanger: BSN:999991401
}

assert {
  res.status: eq 503
  res.headers["Retry-After"]: eq "30"
}
```

- [ ] **Step 5: Update bestaande Bruno-requests met TYPE-prefix**

Run:
```bash
grep -rln "X-Ontvanger:" bruno/berichtensessiecache/
```

Voor elke gevonden `.bru`-file: vervang `X-Ontvanger: 999993653` (en andere bare strings) door `X-Ontvanger: BSN:999993653`.

---

### Task E10: Volledige verify + commit

- [ ] **Step 1: Run alle services tests via host-runner**

Via MCP Maven sequentieel:
- fbs-common: `goals=["clean","verify"]`, `args=["-am"]`
- berichtenmagazijn: `goals=["clean","verify"]`, `args=["-am"]`
- berichtensessiecache: `goals=["clean","verify"]`, `args=["-am"]`

Expected: BUILD SUCCESS voor alle drie. JaCoCo-rapport voor sessiecache moet ≥90% line coverage halen.

- [ ] **Step 2: Final commit**

```bash
git add -A
git status   # verifieer dat niets gemist is
git commit -m "feat(sessiecache): MagazijnResolver - Issue #416

Resolver tussen BerichtensessiecacheService en MagazijnClientFactory die
op basis van dienstvoorkeuren uit de MOZA Profiel Service bepaalt welke
magazijnen bevraagd worden.

Gedrag:
- OIN-ontvanger: skip Profiel, alle magazijnen (B2B)
- BSN/RSIN/KVK: Profiel-call + filter op afzender-OIN-overlap per magazijn
- 200/404 zonder voorkeur: lege resultaten + OPHALEN_GEREED
- 5xx/timeout/malformed: 503 + Retry-After

Spec aligned: X-Ontvanger TYPE:WAARDE-formaat over alle endpoints.
Bruno-collectie + WireMock-mappings uitgebreid met onbekend-404 en
server-error-500 paden. Refactoring: Identificatienummer +
ProfielServiceClient + DTO's + endpoint-validator + mappers verplaatst
naar fbs-common voor gedeeld gebruik met berichtenmagazijn."
```

- [ ] **Step 3: Open PR**

```bash
git push -u origin feature/magazijn-resolver-416
gh pr create --title "MagazijnResolver — Issue #416" --body "$(cat <<'EOF'
## Summary

Implementeert MagazijnResolver in `berichtensessiecache` per Issue #416 (herzien 2026-05-26 — auth/claims verplaatst naar #552).

- Pluggable resolver bepaalt op basis van Profiel-Service-voorkeuren welke magazijnen bevraagd worden
- Profiel-laag (client, DTO's, exception-mappers, endpoint-validator) verplaatst naar `fbs-common` voor gedeeld gebruik met berichtenmagazijn
- `Identificatienummer`-types verplaatst naar `fbs-common`; sessiecache krijgt typed service-signatures
- `X-Ontvanger` spec uitgelijnd met magazijn (`TYPE:WAARDE` regex over alle endpoints)
- Fail-closed bij Profiel-fout: 503 + `Retry-After: 30`; geen "alle magazijnen"-fallback (toestemming-semantiek)
- Lege voorkeuren (200/404): lege resultaten + `OPHALEN_GEREED` (cache overschreven met lege lijst)

Design: `docs/plans/2026-05-26-magazijn-resolver-design.md`.

## Notable spec deviation

Originele AC zegt "Fallback: alle magazijnen bij Profiel-fout (graceful degradation)". Design en implementatie wijken hiervan af naar fail-closed (`ProfielServiceFoutException` → 503), op uitdrukkelijk verzoek tijdens design-review: Profiel-fout ≠ impliciete toestemming. Issue-tekst aanpassen wanneer akkoord.

## Test plan

- [ ] Unit-tests (`ProfielMagazijnResolverTest`) — 15 scenarios groen
- [ ] Component-integratie (`ProfielMagazijnResolverIntegrationTest`) tegen WireMock-Profiel-stub
- [ ] Service-integratie (uitgebreide `BerichtensessiecacheServiceTest`) — emptySet + Profiel-fout-paden
- [ ] E2E (uitgebreide `BerichtenOphalenResourceTest`) — 200/lege, 503, invalide header, OIN-skip
- [ ] Spectral-lint op `berichtensessiecache-api.yaml`
- [ ] Lokaal dev-mode + Bruno-collectie (5 happy, 1 zonder-voorkeur, 1 Profiel-fout)
- [ ] JaCoCo line-coverage ≥ 90% in sessiecache

🤖 Generated with [Claude Code](https://claude.com/claude-code)
EOF
)"
```

Expected: PR aangemaakt op `MinBZK/MijnOverheidZakelijk`. Niet review-reviewer toevoegen (zie CLAUDE.md).

---

## Open punten

1. **`ProblemResponses.kt`-signatuur**: in Task B3 staat een opmerking over de mapper. Verifieer aan de hand van de bestaande code in `libraries/fbs-common/src/main/kotlin/nl/rijksoverheid/moz/fbs/common/exception/ProblemResponses.kt` of `problemResponse` een `ResponseBuilder` of `Response` returnt; pas mapper-code aan zodat hij `Retry-After` correct kan toevoegen.

2. **Sessiecache `Bericht.ontvanger`-veld**: Task D4 step 2 vereist inspectie van de huidige type. Voor PoC: laat `String` blijven in `Bericht`-DTO; de service-grens accepteert de raw waarde via `fromHeader`.

3. **AC-tekst Issue #416**: stem fail-closed-keuze af in PR-comment (zie spec-deviation).
