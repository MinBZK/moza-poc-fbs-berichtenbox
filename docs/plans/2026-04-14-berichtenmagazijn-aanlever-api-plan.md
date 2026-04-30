**Status:** Uitgevoerd

# Berichtenmagazijn Aanlever API — Implementatieplan

> **Voor agentic workers:** VEREISTE SUB-SKILL: gebruik `superpowers:subagent-driven-development` (aanbevolen) of `superpowers:executing-plans` om dit plan taak-voor-taak uit te voeren. Stappen gebruiken checkbox (`- [ ]`) syntax voor tracking.

**Doel:** Implementeer de Aanlever API (`POST /api/v1/berichten`) als nieuwe service module `services/berichtenmagazijn`, met gedeelde JAX-RS filters in `libraries/fbs-common`.

**Architectuur:** Nieuwe Maven modules: `libraries/fbs-common` (pure JAR met herbruikbare filters/mappers) en `services/berichtenmagazijn` (Quarkus/Kotlin service). Dataopslag via H2 embedded + JPA/Panache. Circuit breaker op schrijfoperaties via MicroProfile Fault Tolerance.

**Tech stack:** Kotlin 2.3.10, Quarkus 3.34.3, Java 21, Hibernate ORM Panache, H2, JUnit 5, MockK, REST-assured, swagger-request-validator.

**Ontwerpdocument:** `docs/plans/2026-04-14-berichtenmagazijn-aanlever-api.md` — dit is het canonieke document voor ontwerpkeuzes. De codefragmenten hieronder zijn de taakbrief voor de agent-executie en weerspiegelen niet noodzakelijk de eindcode na review. Zie de werkelijke Kotlin-bronnen onder `libraries/fbs-common/` en `services/berichtenmagazijn/` voor de actuele implementatie.

---

## Bestandsstructuur

### Nieuwe module: `libraries/fbs-common`
| Pad | Verantwoordelijkheid |
|---|---|
| `libraries/fbs-common/pom.xml` | Pure JAR met Jandex-index voor CDI discovery |
| `src/main/kotlin/.../fbs/common/Problem.kt` | Data class voor RFC 9457 Problem Details |
| `src/main/kotlin/.../fbs/common/ProblemMediaType.kt` | `application/problem+json` constant |
| `src/main/kotlin/.../fbs/common/ProblemExceptionMapper.kt` | `WebApplicationException` → Problem JSON |
| `src/main/kotlin/.../fbs/common/ConstraintViolationExceptionMapper.kt` | Bean Validation → Problem JSON |
| `src/main/kotlin/.../fbs/common/SecurityHeadersFilter.kt` | HSTS, X-Frame-Options, CSP, X-Content-Type-Options |
| `src/main/kotlin/.../fbs/common/CacheControlFilter.kt` | `Cache-Control: no-store` |
| `src/main/kotlin/.../fbs/common/CreatedStatusFilter.kt` | HTTP 200 → 201 voor POST |
| `src/main/kotlin/.../fbs/common/LogboekContextDefaultFilter.kt` | Safe defaults op LogboekContext |

### Gewijzigde module: `services/berichtensessiecache`
| Pad | Wijziging |
|---|---|
| `pom.xml` | Dependency op `fbs-common` |
| `src/main/kotlin/.../ApiVersionFilter.kt` | Alleen API-Version header (security headers eruit) |
| `src/main/kotlin/.../ProblemExceptionMapper.kt` | **Verwijderen** (zit in fbs-common) |
| `src/main/kotlin/.../ConstraintViolationExceptionMapper.kt` | **Verwijderen** |
| `src/main/kotlin/.../CreatedStatusFilter.kt` | **Verwijderen** |
| `src/main/kotlin/.../berichten/LogboekContextDefaultFilter.kt` | **Verwijderen** |

### Nieuwe module: `services/berichtenmagazijn`
| Pad | Verantwoordelijkheid |
|---|---|
| `pom.xml` | Module POM met OpenAPI generator, H2, JPA/Panache, Fault Tolerance |
| `src/main/resources/application.properties` | Quarkus config (H2, poort, application version) |
| `src/main/resources/openapi/berichtenmagazijn-api.yaml` | OpenAPI spec (bron van waarheid) |
| `src/main/kotlin/.../berichtenmagazijn/ApiVersionFilter.kt` | Per-service API-Version header |
| `src/main/kotlin/.../berichtenmagazijn/aanlever/AanleverResource.kt` | JAX-RS resource |
| `src/main/kotlin/.../berichtenmagazijn/aanlever/BerichtOpslagService.kt` | Service met `@CircuitBreaker` |
| `src/main/kotlin/.../berichtenmagazijn/opslag/BerichtEntity.kt` | JPA entity |
| `src/main/kotlin/.../berichtenmagazijn/opslag/BerichtRepository.kt` | Panache repository |
| `src/main/kotlin/.../berichtenmagazijn/opslag/Bericht.kt` | Domeinmodel |
| `src/main/kotlin/.../berichtenmagazijn/{ophaal,validatie,publicatie,autorisatie}/.gitkeep` | Lege placeholders |
| `src/test/kotlin/.../aanlever/BerichtOpslagServiceTest.kt` | Unit tests (MockK) |
| `src/test/kotlin/.../aanlever/AanleverResourceIntegrationTest.kt` | Integratietests (QuarkusTest + H2) |
| `src/test/kotlin/.../aanlever/OpenApiContractTest.kt` | Contract-tests |
| `src/test/resources/application.properties` | Test-config (H2 mem schema) |

### Overig
| Pad | Wijziging |
|---|---|
| `pom.xml` (root) | Voeg `libraries/fbs-common` en `services/berichtenmagazijn` toe aan `<modules>` |
| `.github/workflows/test-berichtenmagazijn.yml` | CI workflow voor magazijn |
| `CLAUDE.md` | Registreer nieuwe modules onder "Actieve modules" |

---

## Task 1: Maak `libraries/fbs-common` Maven module

**Files:**
- Create: `libraries/fbs-common/pom.xml`
- Modify: `pom.xml` (root)

- [ ] **Stap 1: Maak directories**

```bash
mkdir -p libraries/fbs-common/src/main/kotlin/nl/rijksoverheid/moz/fbs/common
```

- [ ] **Stap 2: Schrijf `libraries/fbs-common/pom.xml`**

```xml
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0"
         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 https://maven.apache.org/xsd/maven-4.0.0.xsd">
    <modelVersion>4.0.0</modelVersion>

    <parent>
        <groupId>nl.rijksoverheid.moz</groupId>
        <artifactId>moza-poc-fbs-berichtenbox</artifactId>
        <version>0.1.0-SNAPSHOT</version>
        <relativePath>../../pom.xml</relativePath>
    </parent>

    <artifactId>fbs-common</artifactId>
    <name>FBS Common Library</name>
    <description>Herbruikbare JAX-RS filters en exception mappers voor FBS services</description>

    <dependencies>
        <dependency>
            <groupId>org.jetbrains.kotlin</groupId>
            <artifactId>kotlin-stdlib</artifactId>
        </dependency>
        <dependency>
            <groupId>jakarta.ws.rs</groupId>
            <artifactId>jakarta.ws.rs-api</artifactId>
            <scope>provided</scope>
        </dependency>
        <dependency>
            <groupId>jakarta.validation</groupId>
            <artifactId>jakarta.validation-api</artifactId>
            <scope>provided</scope>
        </dependency>
        <dependency>
            <groupId>jakarta.inject</groupId>
            <artifactId>jakarta.inject-api</artifactId>
            <scope>provided</scope>
        </dependency>
        <dependency>
            <groupId>com.fasterxml.jackson.core</groupId>
            <artifactId>jackson-annotations</artifactId>
            <scope>provided</scope>
        </dependency>
        <dependency>
            <groupId>org.jboss.logging</groupId>
            <artifactId>jboss-logging</artifactId>
            <scope>provided</scope>
        </dependency>
        <dependency>
            <groupId>nl.mijnoverheidzakelijk.ldv</groupId>
            <artifactId>logboekdataverwerking-wrapper</artifactId>
            <version>1.2.1-SNAPSHOT</version>
            <scope>provided</scope>
        </dependency>
    </dependencies>

    <build>
        <sourceDirectory>src/main/kotlin</sourceDirectory>
        <plugins>
            <plugin>
                <groupId>org.jetbrains.kotlin</groupId>
                <artifactId>kotlin-maven-plugin</artifactId>
            </plugin>
            <plugin>
                <groupId>org.apache.maven.plugins</groupId>
                <artifactId>maven-compiler-plugin</artifactId>
                <executions>
                    <execution>
                        <id>default-compile</id>
                        <phase>none</phase>
                    </execution>
                    <execution>
                        <id>default-testCompile</id>
                        <phase>none</phase>
                    </execution>
                </executions>
            </plugin>
            <!-- Jandex-index zodat Quarkus consumer de beans/providers kan ontdekken -->
            <plugin>
                <groupId>io.smallrye</groupId>
                <artifactId>jandex-maven-plugin</artifactId>
                <version>3.2.7</version>
                <executions>
                    <execution>
                        <id>make-index</id>
                        <goals>
                            <goal>jandex</goal>
                        </goals>
                    </execution>
                </executions>
            </plugin>
        </plugins>
    </build>
</project>
```

- [ ] **Stap 3: Voeg module toe aan parent POM**

Vervang regels 15-17 in `pom.xml` (root):

```xml
    <modules>
        <module>libraries/fbs-common</module>
        <module>services/berichtensessiecache</module>
        <module>services/berichtenmagazijn</module>
    </modules>
```

Noot: `services/berichtenmagazijn` wordt in Task 10 aangemaakt. Tot die tijd zal `./mvnw compile` falen. We accepteren dat en herstellen in Task 10.

- [ ] **Stap 4: Voorlopig `services/berichtenmagazijn` uit modules halen om compile te testen**

Tijdelijk alleen dit toevoegen:

```xml
    <modules>
        <module>libraries/fbs-common</module>
        <module>services/berichtensessiecache</module>
    </modules>
```

- [ ] **Stap 5: Compileer fbs-common (leeg)**

Run: `./mvnw compile -pl libraries/fbs-common -am -B`
Verwacht: `BUILD SUCCESS`

- [ ] **Stap 6: Commit**

```bash
git add libraries/fbs-common/pom.xml pom.xml
git commit -m "Voeg libraries/fbs-common module toe"
```

---

## Task 2: Definieer `Problem` data class + media type

**Files:**
- Create: `libraries/fbs-common/src/main/kotlin/nl/rijksoverheid/moz/fbs/common/Problem.kt`
- Create: `libraries/fbs-common/src/main/kotlin/nl/rijksoverheid/moz/fbs/common/ProblemMediaType.kt`

- [ ] **Stap 1: Schrijf `Problem.kt`**

```kotlin
package nl.rijksoverheid.moz.fbs.common

import com.fasterxml.jackson.annotation.JsonInclude
import java.net.URI

@JsonInclude(JsonInclude.Include.NON_NULL)
data class Problem(
    val type: URI = URI.create("about:blank"),
    val title: String,
    val status: Int,
    val detail: String? = null,
    val instance: URI? = null,
)
```

- [ ] **Stap 2: Schrijf `ProblemMediaType.kt`**

```kotlin
package nl.rijksoverheid.moz.fbs.common

import jakarta.ws.rs.core.MediaType

object ProblemMediaType {
    const val APPLICATION_PROBLEM_JSON = "application/problem+json"
    val APPLICATION_PROBLEM_JSON_TYPE: MediaType = MediaType.valueOf(APPLICATION_PROBLEM_JSON)
}
```

- [ ] **Stap 3: Compileer**

Run: `./mvnw compile -pl libraries/fbs-common -B`
Verwacht: `BUILD SUCCESS`

- [ ] **Stap 4: Commit**

```bash
git add libraries/fbs-common/src/main/kotlin/nl/rijksoverheid/moz/fbs/common/Problem.kt \
         libraries/fbs-common/src/main/kotlin/nl/rijksoverheid/moz/fbs/common/ProblemMediaType.kt
git commit -m "Voeg Problem data class en media type toe aan fbs-common"
```

---

## Task 3: Verplaats `ProblemExceptionMapper` naar fbs-common

**Files:**
- Create: `libraries/fbs-common/src/main/kotlin/nl/rijksoverheid/moz/fbs/common/ProblemExceptionMapper.kt`
- Delete: `services/berichtensessiecache/src/main/kotlin/nl/rijksoverheid/moz/berichtensessiecache/ProblemExceptionMapper.kt`
- Modify: `services/berichtensessiecache/pom.xml`

- [ ] **Stap 1: Schrijf `ProblemExceptionMapper.kt` in fbs-common**

```kotlin
package nl.rijksoverheid.moz.fbs.common

import jakarta.ws.rs.WebApplicationException
import jakarta.ws.rs.core.Response
import jakarta.ws.rs.ext.ExceptionMapper
import jakarta.ws.rs.ext.Provider
import org.jboss.logging.Logger

@Provider
class ProblemExceptionMapper : ExceptionMapper<WebApplicationException> {

    private val log = Logger.getLogger(ProblemExceptionMapper::class.java)

    override fun toResponse(exception: WebApplicationException): Response {
        val status = exception.response?.status ?: 500

        if (status >= 500) {
            log.errorf(exception, "Server error %d: %s", status, exception.message)
        }

        val problem = Problem(
            title = Response.Status.fromStatusCode(status)?.reasonPhrase ?: "Error",
            status = status,
            detail = exception.message,
        )

        return Response.status(status)
            .type(ProblemMediaType.APPLICATION_PROBLEM_JSON_TYPE)
            .entity(problem)
            .build()
    }
}
```

- [ ] **Stap 2: Voeg fbs-common dependency toe aan sessiecache POM**

Wijzig `services/berichtensessiecache/pom.xml`. Voeg bovenaan de `<dependencies>` sectie (direct na `<dependencies>`) toe:

```xml
        <dependency>
            <groupId>nl.rijksoverheid.moz</groupId>
            <artifactId>fbs-common</artifactId>
            <version>${project.version}</version>
        </dependency>
```

- [ ] **Stap 3: Verwijder oude ProblemExceptionMapper**

```bash
rm services/berichtensessiecache/src/main/kotlin/nl/rijksoverheid/moz/berichtensessiecache/ProblemExceptionMapper.kt
```

- [ ] **Stap 4: Compileer + test sessiecache**

Run: `./mvnw test -pl services/berichtensessiecache -am -B`
Verwacht: Alle bestaande tests slagen nog steeds (de mapper doet hetzelfde, enkel met fbs-common Problem in plaats van gegenereerde Problem).

- [ ] **Stap 5: Commit**

```bash
git add libraries/fbs-common/src/main/kotlin/nl/rijksoverheid/moz/fbs/common/ProblemExceptionMapper.kt \
         services/berichtensessiecache/pom.xml \
         services/berichtensessiecache/src/main/kotlin/nl/rijksoverheid/moz/berichtensessiecache/ProblemExceptionMapper.kt
git commit -m "Verplaats ProblemExceptionMapper naar fbs-common"
```

---

## Task 4: Verplaats `ConstraintViolationExceptionMapper` naar fbs-common

**Files:**
- Create: `libraries/fbs-common/src/main/kotlin/nl/rijksoverheid/moz/fbs/common/ConstraintViolationExceptionMapper.kt`
- Delete: `services/berichtensessiecache/src/main/kotlin/nl/rijksoverheid/moz/berichtensessiecache/ConstraintViolationExceptionMapper.kt`

- [ ] **Stap 1: Schrijf nieuwe versie in fbs-common**

```kotlin
package nl.rijksoverheid.moz.fbs.common

import jakarta.validation.ConstraintViolationException
import jakarta.ws.rs.core.Response
import jakarta.ws.rs.ext.ExceptionMapper
import jakarta.ws.rs.ext.Provider
import org.jboss.logging.Logger

@Provider
class ConstraintViolationExceptionMapper : ExceptionMapper<ConstraintViolationException> {

    private val log = Logger.getLogger(ConstraintViolationExceptionMapper::class.java)

    override fun toResponse(exception: ConstraintViolationException): Response {
        log.debugf("Validatiefout: %s", exception.constraintViolations)

        val detail = exception.constraintViolations.joinToString("; ") {
            val paramName = it.propertyPath.lastOrNull()?.name ?: it.propertyPath.toString()
            "$paramName: ${it.message}"
        }

        val problem = Problem(
            title = "Bad Request",
            status = 400,
            detail = detail,
        )

        return Response.status(400)
            .type(ProblemMediaType.APPLICATION_PROBLEM_JSON_TYPE)
            .entity(problem)
            .build()
    }
}
```

- [ ] **Stap 2: Verwijder oude versie**

```bash
rm services/berichtensessiecache/src/main/kotlin/nl/rijksoverheid/moz/berichtensessiecache/ConstraintViolationExceptionMapper.kt
```

- [ ] **Stap 3: Compileer + test sessiecache**

Run: `./mvnw test -pl services/berichtensessiecache -am -B`
Verwacht: Alle tests slagen.

- [ ] **Stap 4: Commit**

```bash
git add libraries/fbs-common/src/main/kotlin/nl/rijksoverheid/moz/fbs/common/ConstraintViolationExceptionMapper.kt \
         services/berichtensessiecache/src/main/kotlin/nl/rijksoverheid/moz/berichtensessiecache/ConstraintViolationExceptionMapper.kt
git commit -m "Verplaats ConstraintViolationExceptionMapper naar fbs-common"
```

---

## Task 5: Maak `SecurityHeadersFilter` in fbs-common

**Files:**
- Create: `libraries/fbs-common/src/main/kotlin/nl/rijksoverheid/moz/fbs/common/SecurityHeadersFilter.kt`

- [ ] **Stap 1: Schrijf filter**

```kotlin
package nl.rijksoverheid.moz.fbs.common

import jakarta.ws.rs.container.ContainerRequestContext
import jakarta.ws.rs.container.ContainerResponseContext
import jakarta.ws.rs.container.ContainerResponseFilter
import jakarta.ws.rs.ext.Provider

/**
 * Voegt security-gerelateerde response headers toe aan alle responses.
 */
@Provider
class SecurityHeadersFilter : ContainerResponseFilter {
    override fun filter(requestContext: ContainerRequestContext, responseContext: ContainerResponseContext) {
        responseContext.headers.putSingle("Strict-Transport-Security", "max-age=31536000")
        responseContext.headers.putSingle("X-Frame-Options", "DENY")
        responseContext.headers.putSingle("X-Content-Type-Options", "nosniff")
        responseContext.headers.putSingle("Content-Security-Policy", "frame-ancestors 'none'")
    }
}
```

- [ ] **Stap 2: Compileer**

Run: `./mvnw compile -pl libraries/fbs-common -B`
Verwacht: `BUILD SUCCESS`

- [ ] **Stap 3: Commit**

```bash
git add libraries/fbs-common/src/main/kotlin/nl/rijksoverheid/moz/fbs/common/SecurityHeadersFilter.kt
git commit -m "Voeg SecurityHeadersFilter toe aan fbs-common"
```

---

## Task 6: Maak `CacheControlFilter` in fbs-common

**Files:**
- Create: `libraries/fbs-common/src/main/kotlin/nl/rijksoverheid/moz/fbs/common/CacheControlFilter.kt`

- [ ] **Stap 1: Schrijf filter**

```kotlin
package nl.rijksoverheid.moz.fbs.common

import jakarta.ws.rs.container.ContainerRequestContext
import jakarta.ws.rs.container.ContainerResponseContext
import jakarta.ws.rs.container.ContainerResponseFilter
import jakarta.ws.rs.ext.Provider

/**
 * Zet `Cache-Control: no-store` op alle responses. Apart van SecurityHeadersFilter
 * zodat per-service of per-endpoint overrides mogelijk blijven.
 */
@Provider
class CacheControlFilter : ContainerResponseFilter {
    override fun filter(requestContext: ContainerRequestContext, responseContext: ContainerResponseContext) {
        responseContext.headers.putSingle("Cache-Control", "no-store")
    }
}
```

- [ ] **Stap 2: Compileer**

Run: `./mvnw compile -pl libraries/fbs-common -B`
Verwacht: `BUILD SUCCESS`

- [ ] **Stap 3: Commit**

```bash
git add libraries/fbs-common/src/main/kotlin/nl/rijksoverheid/moz/fbs/common/CacheControlFilter.kt
git commit -m "Voeg CacheControlFilter toe aan fbs-common"
```

---

## Task 7: Verplaats `CreatedStatusFilter` naar fbs-common

**Files:**
- Create: `libraries/fbs-common/src/main/kotlin/nl/rijksoverheid/moz/fbs/common/CreatedStatusFilter.kt`
- Delete: `services/berichtensessiecache/src/main/kotlin/nl/rijksoverheid/moz/berichtensessiecache/CreatedStatusFilter.kt`

- [ ] **Stap 1: Schrijf nieuwe versie**

```kotlin
package nl.rijksoverheid.moz.fbs.common

import jakarta.ws.rs.container.ContainerRequestContext
import jakarta.ws.rs.container.ContainerResponseContext
import jakarta.ws.rs.container.ContainerResponseFilter
import jakarta.ws.rs.ext.Provider

/**
 * Zet de HTTP-status naar 201 Created voor succesvolle POST-requests.
 *
 * De OpenAPI generator (jaxrs-spec, returnResponse=false) genereert methoden
 * die het concrete response-type retourneren, waardoor de status code niet
 * per methode instelbaar is via het return-type. Deze filter corrigeert dat
 * voor POST-endpoints conform de OpenAPI spec.
 */
@Provider
class CreatedStatusFilter : ContainerResponseFilter {
    override fun filter(request: ContainerRequestContext, response: ContainerResponseContext) {
        if (request.method == "POST" && response.status == 200) {
            response.status = 201
        }
    }
}
```

- [ ] **Stap 2: Verwijder oude versie**

```bash
rm services/berichtensessiecache/src/main/kotlin/nl/rijksoverheid/moz/berichtensessiecache/CreatedStatusFilter.kt
```

- [ ] **Stap 3: Test sessiecache**

Run: `./mvnw test -pl services/berichtensessiecache -am -B`
Verwacht: Tests slagen (de POST-endpoints in sessiecache retourneren nog steeds 201).

- [ ] **Stap 4: Commit**

```bash
git add libraries/fbs-common/src/main/kotlin/nl/rijksoverheid/moz/fbs/common/CreatedStatusFilter.kt \
         services/berichtensessiecache/src/main/kotlin/nl/rijksoverheid/moz/berichtensessiecache/CreatedStatusFilter.kt
git commit -m "Verplaats CreatedStatusFilter naar fbs-common"
```

---

## Task 8: Verplaats `LogboekContextDefaultFilter` naar fbs-common

**Files:**
- Create: `libraries/fbs-common/src/main/kotlin/nl/rijksoverheid/moz/fbs/common/LogboekContextDefaultFilter.kt`
- Delete: `services/berichtensessiecache/src/main/kotlin/nl/rijksoverheid/moz/berichtensessiecache/berichten/LogboekContextDefaultFilter.kt`

- [ ] **Stap 1: Schrijf nieuwe versie**

```kotlin
package nl.rijksoverheid.moz.fbs.common

import jakarta.inject.Inject
import jakarta.ws.rs.container.ContainerRequestContext
import jakarta.ws.rs.container.ContainerRequestFilter
import jakarta.ws.rs.ext.Provider
import nl.mijnoverheidzakelijk.ldv.logboekdataverwerking.LogboekContext

/**
 * Zet safe defaults op LogboekContext voordat de @Logboek CDI interceptor draait.
 * Voorkomt IllegalArgumentException als Bean Validation een request afwijst voor
 * de resource-methode de echte dataSubjectId kan zetten.
 */
@Provider
class LogboekContextDefaultFilter : ContainerRequestFilter {

    @Inject
    lateinit var logboekContext: LogboekContext

    override fun filter(requestContext: ContainerRequestContext) {
        logboekContext.dataSubjectId = "unknown"
        logboekContext.dataSubjectType = "system"
    }
}
```

- [ ] **Stap 2: Verwijder oude versie**

```bash
rm services/berichtensessiecache/src/main/kotlin/nl/rijksoverheid/moz/berichtensessiecache/berichten/LogboekContextDefaultFilter.kt
```

- [ ] **Stap 3: Test sessiecache**

Run: `./mvnw test -pl services/berichtensessiecache -am -B`
Verwacht: Alle tests slagen.

- [ ] **Stap 4: Commit**

```bash
git add libraries/fbs-common/src/main/kotlin/nl/rijksoverheid/moz/fbs/common/LogboekContextDefaultFilter.kt \
         services/berichtensessiecache/src/main/kotlin/nl/rijksoverheid/moz/berichtensessiecache/berichten/LogboekContextDefaultFilter.kt
git commit -m "Verplaats LogboekContextDefaultFilter naar fbs-common"
```

---

## Task 9: Schoon sessiecache ApiVersionFilter op

**Files:**
- Modify: `services/berichtensessiecache/src/main/kotlin/nl/rijksoverheid/moz/berichtensessiecache/ApiVersionFilter.kt`

- [ ] **Stap 1: Vervang volledige inhoud van `ApiVersionFilter.kt`**

```kotlin
package nl.rijksoverheid.moz.berichtensessiecache

import jakarta.ws.rs.container.ContainerRequestContext
import jakarta.ws.rs.container.ContainerResponseContext
import jakarta.ws.rs.container.ContainerResponseFilter
import jakarta.ws.rs.ext.Provider
import org.eclipse.microprofile.config.inject.ConfigProperty

/**
 * Zet de API-Version header per service. Security headers en cache-control
 * worden elders in fbs-common-filters gezet.
 */
@Provider
class ApiVersionFilter(
    @ConfigProperty(name = "quarkus.application.version") private val apiVersion: String,
) : ContainerResponseFilter {

    override fun filter(requestContext: ContainerRequestContext, responseContext: ContainerResponseContext) {
        responseContext.headers.putSingle("API-Version", apiVersion)
    }
}
```

- [ ] **Stap 2: Test sessiecache**

Run: `./mvnw test -pl services/berichtensessiecache -am -B`
Verwacht: Alle tests slagen (SecurityHeadersFilter + CacheControlFilter uit fbs-common zetten dezelfde headers als voorheen).

- [ ] **Stap 3: Commit**

```bash
git add services/berichtensessiecache/src/main/kotlin/nl/rijksoverheid/moz/berichtensessiecache/ApiVersionFilter.kt
git commit -m "Schoon sessiecache ApiVersionFilter op — alleen nog API-Version header"
```

---

## Task 10: Maak `services/berichtenmagazijn` module skeleton

**Files:**
- Create: `services/berichtenmagazijn/pom.xml`
- Create: `services/berichtenmagazijn/src/main/resources/application.properties`
- Modify: `pom.xml` (root)

- [ ] **Stap 1: Maak directories**

```bash
mkdir -p services/berichtenmagazijn/src/main/kotlin/nl/rijksoverheid/moz/berichtenmagazijn/{aanlever,opslag,ophaal,validatie,publicatie,autorisatie}
mkdir -p services/berichtenmagazijn/src/main/resources/openapi
mkdir -p services/berichtenmagazijn/src/test/kotlin/nl/rijksoverheid/moz/berichtenmagazijn/aanlever
mkdir -p services/berichtenmagazijn/src/test/resources
```

- [ ] **Stap 2: Schrijf `services/berichtenmagazijn/pom.xml`**

```xml
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0"
         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 https://maven.apache.org/xsd/maven-4.0.0.xsd">
    <modelVersion>4.0.0</modelVersion>

    <parent>
        <groupId>nl.rijksoverheid.moz</groupId>
        <artifactId>moza-poc-fbs-berichtenbox</artifactId>
        <version>0.1.0-SNAPSHOT</version>
        <relativePath>../../pom.xml</relativePath>
    </parent>

    <artifactId>berichtenmagazijn</artifactId>
    <name>FBS Berichtenmagazijn Service</name>
    <description>Decentraal berichtenmagazijn per deelnemende organisatie (Aanlever API)</description>

    <dependencies>
        <dependency>
            <groupId>nl.rijksoverheid.moz</groupId>
            <artifactId>fbs-common</artifactId>
            <version>${project.version}</version>
        </dependency>

        <dependency>
            <groupId>io.quarkus</groupId>
            <artifactId>quarkus-rest-jackson</artifactId>
        </dependency>
        <dependency>
            <groupId>io.quarkus</groupId>
            <artifactId>quarkus-kotlin</artifactId>
        </dependency>
        <dependency>
            <groupId>io.quarkus</groupId>
            <artifactId>quarkus-smallrye-openapi</artifactId>
        </dependency>
        <dependency>
            <groupId>io.quarkus</groupId>
            <artifactId>quarkus-hibernate-validator</artifactId>
        </dependency>
        <dependency>
            <groupId>io.quarkus</groupId>
            <artifactId>quarkus-smallrye-fault-tolerance</artifactId>
        </dependency>

        <!-- Persistentie -->
        <dependency>
            <groupId>io.quarkus</groupId>
            <artifactId>quarkus-hibernate-orm-panache-kotlin</artifactId>
        </dependency>
        <dependency>
            <groupId>io.quarkus</groupId>
            <artifactId>quarkus-jdbc-h2</artifactId>
        </dependency>

        <dependency>
            <groupId>org.jetbrains.kotlin</groupId>
            <artifactId>kotlin-stdlib</artifactId>
        </dependency>

        <!-- LDV -->
        <dependency>
            <groupId>nl.mijnoverheidzakelijk.ldv</groupId>
            <artifactId>logboekdataverwerking-wrapper</artifactId>
            <version>1.2.1-SNAPSHOT</version>
        </dependency>

        <!-- Test -->
        <dependency>
            <groupId>io.quarkus</groupId>
            <artifactId>quarkus-junit5</artifactId>
            <scope>test</scope>
        </dependency>
        <dependency>
            <groupId>io.rest-assured</groupId>
            <artifactId>rest-assured</artifactId>
            <scope>test</scope>
        </dependency>
        <dependency>
            <groupId>io.mockk</groupId>
            <artifactId>mockk-jvm</artifactId>
            <version>1.13.16</version>
            <scope>test</scope>
        </dependency>
        <dependency>
            <groupId>com.atlassian.oai</groupId>
            <artifactId>swagger-request-validator-restassured</artifactId>
            <version>2.43.0</version>
            <scope>test</scope>
        </dependency>
        <dependency>
            <groupId>io.quarkus</groupId>
            <artifactId>quarkus-jacoco</artifactId>
            <scope>test</scope>
        </dependency>
        <dependency>
            <groupId>com.fasterxml.jackson.module</groupId>
            <artifactId>jackson-module-kotlin</artifactId>
            <scope>test</scope>
        </dependency>
    </dependencies>

    <build>
        <sourceDirectory>src/main/kotlin</sourceDirectory>
        <testSourceDirectory>src/test/kotlin</testSourceDirectory>

        <plugins>
            <plugin>
                <groupId>io.quarkus</groupId>
                <artifactId>quarkus-maven-plugin</artifactId>
                <extensions>true</extensions>
                <executions>
                    <execution>
                        <goals>
                            <goal>build</goal>
                            <goal>generate-code</goal>
                            <goal>generate-code-tests</goal>
                        </goals>
                    </execution>
                </executions>
            </plugin>

            <plugin>
                <groupId>org.openapitools</groupId>
                <artifactId>openapi-generator-maven-plugin</artifactId>
                <version>${openapi-generator.version}</version>
                <executions>
                    <execution>
                        <goals>
                            <goal>generate</goal>
                        </goals>
                        <configuration>
                            <inputSpec>${project.basedir}/src/main/resources/openapi/berichtenmagazijn-api.yaml</inputSpec>
                            <generatorName>jaxrs-spec</generatorName>
                            <output>${project.build.directory}/generated-sources/openapi</output>
                            <apiPackage>nl.rijksoverheid.moz.berichtenmagazijn.api</apiPackage>
                            <modelPackage>nl.rijksoverheid.moz.berichtenmagazijn.api.model</modelPackage>
                            <configOptions>
                                <interfaceOnly>true</interfaceOnly>
                                <returnResponse>false</returnResponse>
                                <useJakartaEe>true</useJakartaEe>
                                <useSwaggerAnnotations>false</useSwaggerAnnotations>
                                <useTags>true</useTags>
                                <dateLibrary>java8</dateLibrary>
                            </configOptions>
                            <typeMappings>
                                <typeMapping>OffsetDateTime=java.time.Instant</typeMapping>
                            </typeMappings>
                            <generateSupportingFiles>false</generateSupportingFiles>
                            <generateApiTests>false</generateApiTests>
                            <generateModelTests>false</generateModelTests>
                        </configuration>
                    </execution>
                </executions>
            </plugin>

            <plugin>
                <groupId>org.jetbrains.kotlin</groupId>
                <artifactId>kotlin-maven-plugin</artifactId>
                <configuration>
                    <compilerPlugins>
                        <plugin>all-open</plugin>
                        <plugin>jpa</plugin>
                    </compilerPlugins>
                    <pluginOptions>
                        <option>all-open:annotation=jakarta.ws.rs.Path</option>
                        <option>all-open:annotation=jakarta.enterprise.context.ApplicationScoped</option>
                        <option>all-open:annotation=jakarta.enterprise.context.RequestScoped</option>
                        <option>all-open:annotation=jakarta.persistence.Entity</option>
                    </pluginOptions>
                </configuration>
                <dependencies>
                    <dependency>
                        <groupId>org.jetbrains.kotlin</groupId>
                        <artifactId>kotlin-maven-allopen</artifactId>
                        <version>${kotlin.version}</version>
                    </dependency>
                    <dependency>
                        <groupId>org.jetbrains.kotlin</groupId>
                        <artifactId>kotlin-maven-noarg</artifactId>
                        <version>${kotlin.version}</version>
                    </dependency>
                </dependencies>
            </plugin>

            <plugin>
                <groupId>org.jacoco</groupId>
                <artifactId>jacoco-maven-plugin</artifactId>
                <version>0.8.13</version>
                <configuration>
                    <excludes>
                        <exclude>nl/rijksoverheid/moz/berichtenmagazijn/api/**</exclude>
                    </excludes>
                    <dataFile>${project.build.directory}/jacoco-quarkus.exec</dataFile>
                </configuration>
                <executions>
                    <execution>
                        <id>report</id>
                        <phase>test</phase>
                        <goals><goal>report</goal></goals>
                    </execution>
                    <execution>
                        <id>check</id>
                        <phase>test</phase>
                        <goals><goal>check</goal></goals>
                        <configuration>
                            <rules>
                                <rule>
                                    <element>BUNDLE</element>
                                    <limits>
                                        <limit>
                                            <counter>LINE</counter>
                                            <value>COVEREDRATIO</value>
                                            <minimum>0.90</minimum>
                                        </limit>
                                    </limits>
                                </rule>
                            </rules>
                        </configuration>
                    </execution>
                </executions>
            </plugin>

            <plugin>
                <groupId>org.apache.maven.plugins</groupId>
                <artifactId>maven-compiler-plugin</artifactId>
                <executions>
                    <execution>
                        <id>default-compile</id>
                        <phase>none</phase>
                    </execution>
                    <execution>
                        <id>default-testCompile</id>
                        <phase>none</phase>
                    </execution>
                    <execution>
                        <id>java-compile</id>
                        <phase>compile</phase>
                        <goals>
                            <goal>compile</goal>
                        </goals>
                        <configuration>
                            <generatedSourcesDirectory>${project.build.directory}/generated-sources/openapi/src/gen/java</generatedSourcesDirectory>
                        </configuration>
                    </execution>
                </executions>
            </plugin>
        </plugins>
    </build>
</project>
```

- [ ] **Stap 3: Schrijf `services/berichtenmagazijn/src/main/resources/application.properties`**

```properties
quarkus.application.name=berichtenmagazijn
quarkus.application.version=0.1.0

quarkus.http.port=8090

# Persistentie (H2 embedded, PoC)
quarkus.datasource.db-kind=h2
quarkus.datasource.jdbc.url=jdbc:h2:mem:berichtenmagazijn;DB_CLOSE_DELAY=-1
quarkus.hibernate-orm.database.generation=drop-and-create
quarkus.hibernate-orm.log.sql=false

# Circuit breaker (defaults overschreven in annotatie)
quarkus.fault-tolerance.enabled=true
```

- [ ] **Stap 4: Voeg module toe aan parent POM**

Wijzig `pom.xml` (root), vervang de `<modules>` sectie:

```xml
    <modules>
        <module>libraries/fbs-common</module>
        <module>services/berichtensessiecache</module>
        <module>services/berichtenmagazijn</module>
    </modules>
```

- [ ] **Stap 5: Voeg lege placeholder files toe voor niet-actieve packages**

```bash
touch services/berichtenmagazijn/src/main/kotlin/nl/rijksoverheid/moz/berichtenmagazijn/ophaal/.gitkeep
touch services/berichtenmagazijn/src/main/kotlin/nl/rijksoverheid/moz/berichtenmagazijn/validatie/.gitkeep
touch services/berichtenmagazijn/src/main/kotlin/nl/rijksoverheid/moz/berichtenmagazijn/publicatie/.gitkeep
touch services/berichtenmagazijn/src/main/kotlin/nl/rijksoverheid/moz/berichtenmagazijn/autorisatie/.gitkeep
```

- [ ] **Stap 6: Commit (nog niet compileren — OpenAPI spec ontbreekt)**

```bash
git add services/berichtenmagazijn/ pom.xml
git commit -m "Maak services/berichtenmagazijn module skeleton"
```

---

## Task 11: Schrijf OpenAPI spec

**Files:**
- Create: `services/berichtenmagazijn/src/main/resources/openapi/berichtenmagazijn-api.yaml`

- [ ] **Stap 1: Schrijf de spec**

```yaml
openapi: 3.0.3
info:
  title: FBS Berichtenmagazijn - Aanlever API
  version: 0.1.0
  description: |
    Aanlever-API van het decentrale berichtenmagazijn. Organisaties leveren
    nieuwe berichten aan via POST /api/v1/berichten. Onderdeel van het
    Federatief Berichtenstelsel (FBS).
  contact:
    name: MijnOverheid Zakelijk
    url: https://github.com/MinBZK/moza-poc-fbs-berichtenbox

servers:
  - url: /api/v1
    description: Versioned API base path

tags:
  - name: Aanlever
    description: Aanlevering van berichten aan het magazijn

paths:
  /berichten:
    post:
      tags: [Aanlever]
      summary: Lever een nieuw bericht aan bij het magazijn
      operationId: aanleverBericht
      requestBody:
        required: true
        content:
          application/json:
            schema:
              $ref: '#/components/schemas/AanleverBerichtRequest'
      responses:
        '201':
          description: Bericht succesvol opgeslagen
          headers:
            API-Version:
              $ref: '#/components/headers/API-Version'
          content:
            application/json:
              schema:
                $ref: '#/components/schemas/BerichtResponse'
        '400':
          $ref: '#/components/responses/BadRequest'
        '401':
          $ref: '#/components/responses/Unauthorized'
        '403':
          $ref: '#/components/responses/Forbidden'
        '422':
          $ref: '#/components/responses/UnprocessableEntity'
        '500':
          $ref: '#/components/responses/InternalServerError'
        '503':
          $ref: '#/components/responses/ServiceUnavailable'

components:
  headers:
    API-Version:
      description: Versie van de API
      schema:
        type: string
        example: "0.1.0"

  schemas:
    AanleverBerichtRequest:
      type: object
      required: [afzender, ontvanger, onderwerp, inhoud]
      properties:
        afzender:
          type: string
          minLength: 1
          description: OIN van de afzendende organisatie
          example: "00000001003214345000"
        ontvanger:
          type: string
          minLength: 1
          description: BSN van natuurlijk persoon of KVK-nummer van organisatie
          example: "999993653"
        onderwerp:
          type: string
          minLength: 1
          maxLength: 255
          example: "Voorlopige aanslag 2026"
        inhoud:
          type: string
          minLength: 1
          description: Berichtinhoud (tekst)

    BerichtResponse:
      type: object
      required: [berichtId, afzender, ontvanger, onderwerp, tijdstip, _links]
      properties:
        berichtId:
          type: string
          format: uuid
        afzender:
          type: string
        ontvanger:
          type: string
        onderwerp:
          type: string
        tijdstip:
          type: string
          format: date-time
          description: Tijdstip van opslag in het magazijn (ISO 8601)
        _links:
          $ref: '#/components/schemas/BerichtLinks'

    BerichtLinks:
      type: object
      required: [self]
      properties:
        self:
          $ref: '#/components/schemas/Link'

    Link:
      type: object
      required: [href]
      properties:
        href:
          type: string
          format: uri

    Problem:
      type: object
      description: RFC 9457 Problem Details
      properties:
        type:
          type: string
          format: uri
          default: "about:blank"
        title:
          type: string
        status:
          type: integer
        detail:
          type: string
        instance:
          type: string
          format: uri

  responses:
    BadRequest:
      description: Request is syntactisch onjuist of ontbreekt verplichte velden
      headers:
        API-Version:
          $ref: '#/components/headers/API-Version'
      content:
        application/problem+json:
          schema:
            $ref: '#/components/schemas/Problem'
    Unauthorized:
      description: Authenticatie ontbreekt of is ongeldig
      headers:
        API-Version:
          $ref: '#/components/headers/API-Version'
      content:
        application/problem+json:
          schema:
            $ref: '#/components/schemas/Problem'
    Forbidden:
      description: Geen toestemming voor deze actie
      headers:
        API-Version:
          $ref: '#/components/headers/API-Version'
      content:
        application/problem+json:
          schema:
            $ref: '#/components/schemas/Problem'
    UnprocessableEntity:
      description: Request is syntactisch correct maar semantisch ongeldig
      headers:
        API-Version:
          $ref: '#/components/headers/API-Version'
      content:
        application/problem+json:
          schema:
            $ref: '#/components/schemas/Problem'
    InternalServerError:
      description: Interne fout in het magazijn
      headers:
        API-Version:
          $ref: '#/components/headers/API-Version'
      content:
        application/problem+json:
          schema:
            $ref: '#/components/schemas/Problem'
    ServiceUnavailable:
      description: Magazijn tijdelijk niet beschikbaar (circuit breaker open of H2 down)
      headers:
        API-Version:
          $ref: '#/components/headers/API-Version'
      content:
        application/problem+json:
          schema:
            $ref: '#/components/schemas/Problem'
```

- [ ] **Stap 2: Valideer tegen ADR Spectral ruleset**

Run: `npx --yes @stoplight/spectral-cli lint services/berichtenmagazijn/src/main/resources/openapi/berichtenmagazijn-api.yaml --ruleset https://static.developer.overheid.nl/adr/ruleset.yaml`
Verwacht: Geen errors. Warnings zijn acceptabel mits verklaarbaar.

- [ ] **Stap 3: Compileer om interface-generatie te verifiëren**

Run: `./mvnw compile -pl services/berichtenmagazijn -am -B`
Verwacht: `BUILD SUCCESS`. Controleer dat `services/berichtenmagazijn/target/generated-sources/openapi/src/gen/java/nl/rijksoverheid/moz/berichtenmagazijn/api/AanleverApi.java` bestaat.

- [ ] **Stap 4: Commit**

```bash
git add services/berichtenmagazijn/src/main/resources/openapi/berichtenmagazijn-api.yaml
git commit -m "Voeg OpenAPI spec toe voor Berichtenmagazijn Aanlever API"
```

---

## Task 12: Maak domein- en entity-klassen

**Files:**
- Create: `services/berichtenmagazijn/src/main/kotlin/nl/rijksoverheid/moz/berichtenmagazijn/opslag/Bericht.kt`
- Create: `services/berichtenmagazijn/src/main/kotlin/nl/rijksoverheid/moz/berichtenmagazijn/opslag/BerichtEntity.kt`

- [ ] **Stap 1: Schrijf `Bericht.kt` (domeinmodel)**

```kotlin
package nl.rijksoverheid.moz.berichtenmagazijn.opslag

import java.time.Instant
import java.util.UUID

/**
 * Domeinmodel voor een opgeslagen bericht.
 * Gescheiden van [BerichtEntity] (JPA) om domeinlogica onafhankelijk te houden van persistentie.
 */
data class Bericht(
    val berichtId: UUID,
    val afzender: String,
    val ontvanger: String,
    val onderwerp: String,
    val inhoud: String,
    val tijdstip: Instant,
) {
    init {
        require(afzender.isNotBlank()) { "afzender mag niet leeg zijn" }
        require(ontvanger.isNotBlank()) { "ontvanger mag niet leeg zijn" }
        require(onderwerp.isNotBlank()) { "onderwerp mag niet leeg zijn" }
        require(inhoud.isNotBlank()) { "inhoud mag niet leeg zijn" }
    }
}
```

- [ ] **Stap 2: Schrijf `BerichtEntity.kt`**

```kotlin
package nl.rijksoverheid.moz.berichtenmagazijn.opslag

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.Instant
import java.util.UUID

@Entity
@Table(name = "berichten")
class BerichtEntity {

    @Id
    @Column(nullable = false)
    lateinit var berichtId: UUID

    @Column(nullable = false)
    lateinit var afzender: String

    @Column(nullable = false)
    lateinit var ontvanger: String

    @Column(nullable = false)
    lateinit var onderwerp: String

    @Column(nullable = false, columnDefinition = "CLOB")
    lateinit var inhoud: String

    @Column(nullable = false)
    lateinit var tijdstip: Instant

    fun toDomain(): Bericht = Bericht(
        berichtId = berichtId,
        afzender = afzender,
        ontvanger = ontvanger,
        onderwerp = onderwerp,
        inhoud = inhoud,
        tijdstip = tijdstip,
    )

    companion object {
        fun fromDomain(bericht: Bericht): BerichtEntity = BerichtEntity().apply {
            berichtId = bericht.berichtId
            afzender = bericht.afzender
            ontvanger = bericht.ontvanger
            onderwerp = bericht.onderwerp
            inhoud = bericht.inhoud
            tijdstip = bericht.tijdstip
        }
    }
}
```

- [ ] **Stap 3: Compileer**

Run: `./mvnw compile -pl services/berichtenmagazijn -B`
Verwacht: `BUILD SUCCESS`

- [ ] **Stap 4: Commit**

```bash
git add services/berichtenmagazijn/src/main/kotlin/nl/rijksoverheid/moz/berichtenmagazijn/opslag/
git commit -m "Voeg Bericht domeinmodel en BerichtEntity JPA-entity toe"
```

---

## Task 13: Maak `BerichtRepository`

**Files:**
- Create: `services/berichtenmagazijn/src/main/kotlin/nl/rijksoverheid/moz/berichtenmagazijn/opslag/BerichtRepository.kt`

- [ ] **Stap 1: Schrijf repository**

```kotlin
package nl.rijksoverheid.moz.berichtenmagazijn.opslag

import io.quarkus.hibernate.orm.panache.kotlin.PanacheRepositoryBase
import jakarta.enterprise.context.ApplicationScoped
import java.util.UUID

@ApplicationScoped
class BerichtRepository : PanacheRepositoryBase<BerichtEntity, UUID>
```

- [ ] **Stap 2: Compileer**

Run: `./mvnw compile -pl services/berichtenmagazijn -B`
Verwacht: `BUILD SUCCESS`

- [ ] **Stap 3: Commit**

```bash
git add services/berichtenmagazijn/src/main/kotlin/nl/rijksoverheid/moz/berichtenmagazijn/opslag/BerichtRepository.kt
git commit -m "Voeg BerichtRepository toe (Panache)"
```

---

## Task 14: Schrijf falende unit tests voor `BerichtOpslagService`

**Files:**
- Create: `services/berichtenmagazijn/src/test/kotlin/nl/rijksoverheid/moz/berichtenmagazijn/aanlever/BerichtOpslagServiceTest.kt`

- [ ] **Stap 1: Schrijf de tests (alleen de klasse en methoden bestaan nog niet)**

```kotlin
package nl.rijksoverheid.moz.berichtenmagazijn.aanlever

import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import nl.rijksoverheid.moz.berichtenmagazijn.opslag.BerichtEntity
import nl.rijksoverheid.moz.berichtenmagazijn.opslag.BerichtRepository
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class BerichtOpslagServiceTest {

    private val repository = mockk<BerichtRepository>(relaxed = true)
    private val service = BerichtOpslagService(repository)

    @Test
    fun `opslaanBericht persist entity en retourneert domeinobject met gegenereerd id en tijdstip`() {
        val entitySlot = slot<BerichtEntity>()
        every { repository.persist(capture(entitySlot)) } answers { }

        val bericht = service.opslaanBericht(
            afzender = "00000001003214345000",
            ontvanger = "999993653",
            onderwerp = "Voorlopige aanslag 2026",
            inhoud = "Hierbij ontvangt u...",
        )

        assertNotNull(bericht.berichtId)
        assertNotNull(bericht.tijdstip)
        assertEquals("00000001003214345000", bericht.afzender)
        assertEquals("999993653", bericht.ontvanger)
        assertEquals("Voorlopige aanslag 2026", bericht.onderwerp)
        assertEquals("Hierbij ontvangt u...", bericht.inhoud)

        verify { repository.persist(any<BerichtEntity>()) }
        assertEquals(bericht.berichtId, entitySlot.captured.berichtId)
        assertEquals(bericht.afzender, entitySlot.captured.afzender)
    }

    @Test
    fun `opslaanBericht genereert uniek berichtId per aanroep`() {
        every { repository.persist(any<BerichtEntity>()) } answers { }

        val bericht1 = service.opslaanBericht("a", "b", "c", "d")
        val bericht2 = service.opslaanBericht("a", "b", "c", "d")

        assertTrue(bericht1.berichtId != bericht2.berichtId)
    }
}
```

- [ ] **Stap 2: Voer test uit — verwacht compile failure**

Run: `./mvnw test -pl services/berichtenmagazijn -B`
Verwacht: Compile error (`BerichtOpslagService` bestaat nog niet).

---

## Task 15: Implementeer `BerichtOpslagService`

**Files:**
- Create: `services/berichtenmagazijn/src/main/kotlin/nl/rijksoverheid/moz/berichtenmagazijn/aanlever/BerichtOpslagService.kt`

- [ ] **Stap 1: Schrijf service**

```kotlin
package nl.rijksoverheid.moz.berichtenmagazijn.aanlever

import jakarta.enterprise.context.ApplicationScoped
import jakarta.transaction.Transactional
import nl.rijksoverheid.moz.berichtenmagazijn.opslag.Bericht
import nl.rijksoverheid.moz.berichtenmagazijn.opslag.BerichtEntity
import nl.rijksoverheid.moz.berichtenmagazijn.opslag.BerichtRepository
import org.eclipse.microprofile.faulttolerance.CircuitBreaker
import java.time.Instant
import java.util.UUID

@ApplicationScoped
class BerichtOpslagService(
    private val repository: BerichtRepository,
) {

    @CircuitBreaker(
        requestVolumeThreshold = 20,
        failureRatio = 0.5,
        delay = 5_000L,
        successThreshold = 2,
    )
    @Transactional
    fun opslaanBericht(
        afzender: String,
        ontvanger: String,
        onderwerp: String,
        inhoud: String,
    ): Bericht {
        val bericht = Bericht(
            berichtId = UUID.randomUUID(),
            afzender = afzender,
            ontvanger = ontvanger,
            onderwerp = onderwerp,
            inhoud = inhoud,
            tijdstip = Instant.now(),
        )
        repository.persist(BerichtEntity.fromDomain(bericht))
        return bericht
    }
}
```

- [ ] **Stap 2: Voer unit tests uit**

Run: `./mvnw test -pl services/berichtenmagazijn -B -Dtest=BerichtOpslagServiceTest`
Verwacht: 2 tests PASS.

- [ ] **Stap 3: Commit**

```bash
git add services/berichtenmagazijn/src/main/kotlin/nl/rijksoverheid/moz/berichtenmagazijn/aanlever/BerichtOpslagService.kt \
         services/berichtenmagazijn/src/test/kotlin/nl/rijksoverheid/moz/berichtenmagazijn/aanlever/BerichtOpslagServiceTest.kt
git commit -m "Implementeer BerichtOpslagService met @CircuitBreaker en unit tests"
```

---

## Task 16: Maak `ApiVersionFilter` voor berichtenmagazijn

**Files:**
- Create: `services/berichtenmagazijn/src/main/kotlin/nl/rijksoverheid/moz/berichtenmagazijn/ApiVersionFilter.kt`

- [ ] **Stap 1: Schrijf filter**

```kotlin
package nl.rijksoverheid.moz.berichtenmagazijn

import jakarta.ws.rs.container.ContainerRequestContext
import jakarta.ws.rs.container.ContainerResponseContext
import jakarta.ws.rs.container.ContainerResponseFilter
import jakarta.ws.rs.ext.Provider
import org.eclipse.microprofile.config.inject.ConfigProperty

/**
 * Zet de API-Version header per service. Security headers en cache-control
 * worden elders in fbs-common-filters gezet.
 */
@Provider
class ApiVersionFilter(
    @ConfigProperty(name = "quarkus.application.version") private val apiVersion: String,
) : ContainerResponseFilter {

    override fun filter(requestContext: ContainerRequestContext, responseContext: ContainerResponseContext) {
        responseContext.headers.putSingle("API-Version", apiVersion)
    }
}
```

- [ ] **Stap 2: Compileer**

Run: `./mvnw compile -pl services/berichtenmagazijn -B`
Verwacht: `BUILD SUCCESS`

- [ ] **Stap 3: Commit**

```bash
git add services/berichtenmagazijn/src/main/kotlin/nl/rijksoverheid/moz/berichtenmagazijn/ApiVersionFilter.kt
git commit -m "Voeg ApiVersionFilter toe voor berichtenmagazijn"
```

---

## Task 17: Schrijf falende integratietests voor `AanleverResource`

**Files:**
- Create: `services/berichtenmagazijn/src/test/kotlin/nl/rijksoverheid/moz/berichtenmagazijn/aanlever/AanleverResourceIntegrationTest.kt`
- Create: `services/berichtenmagazijn/src/test/resources/application.properties`

- [ ] **Stap 1: Schrijf test-config `src/test/resources/application.properties`**

```properties
# Test-config: identiek aan main (embedded H2); per testrun verse DB.
quarkus.datasource.db-kind=h2
quarkus.datasource.jdbc.url=jdbc:h2:mem:berichtenmagazijn-test;DB_CLOSE_DELAY=-1
quarkus.hibernate-orm.database.generation=drop-and-create
```

- [ ] **Stap 2: Schrijf integratietests**

```kotlin
package nl.rijksoverheid.moz.berichtenmagazijn.aanlever

import io.quarkus.test.junit.QuarkusTest
import io.restassured.RestAssured.given
import io.restassured.http.ContentType
import jakarta.inject.Inject
import jakarta.transaction.Transactional
import nl.rijksoverheid.moz.berichtenmagazijn.opslag.BerichtRepository
import org.hamcrest.Matchers.containsString
import org.hamcrest.Matchers.equalTo
import org.hamcrest.Matchers.`is`
import org.hamcrest.Matchers.matchesRegex
import org.hamcrest.Matchers.notNullValue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

@QuarkusTest
class AanleverResourceIntegrationTest {

    @Inject
    lateinit var repository: BerichtRepository

    @BeforeEach
    @Transactional
    fun cleanDatabase() {
        repository.deleteAll()
    }

    @Test
    fun `POST berichten met geldige payload retourneert 201 met bericht en _links_self`() {
        given()
            .contentType(ContentType.JSON)
            .body(
                """
                {
                  "afzender": "00000001003214345000",
                  "ontvanger": "999993653",
                  "onderwerp": "Voorlopige aanslag 2026",
                  "inhoud": "Hierbij ontvangt u..."
                }
                """.trimIndent(),
            )
            .`when`().post("/api/v1/berichten")
            .then()
            .statusCode(201)
            .header("API-Version", `is`("0.1.0"))
            .header("X-Frame-Options", `is`("DENY"))
            .header("X-Content-Type-Options", `is`("nosniff"))
            .header("Cache-Control", `is`("no-store"))
            .contentType("application/json")
            .body("berichtId", matchesRegex("[0-9a-f-]{36}"))
            .body("afzender", `is`("00000001003214345000"))
            .body("ontvanger", `is`("999993653"))
            .body("onderwerp", `is`("Voorlopige aanslag 2026"))
            .body("tijdstip", notNullValue())
            .body("_links.self.href", containsString("/api/v1/berichten/"))
    }

    @Test
    fun `POST berichten met ontbrekende ontvanger retourneert 400 Problem JSON`() {
        given()
            .contentType(ContentType.JSON)
            .body(
                """
                {
                  "afzender": "00000001003214345000",
                  "onderwerp": "Voorlopige aanslag 2026",
                  "inhoud": "Hierbij ontvangt u..."
                }
                """.trimIndent(),
            )
            .`when`().post("/api/v1/berichten")
            .then()
            .statusCode(400)
            .contentType("application/problem+json")
            .body("status", `is`(400))
            .body("title", `is`("Bad Request"))
    }

    @Test
    fun `POST berichten met lege onderwerp retourneert 400 Problem JSON`() {
        given()
            .contentType(ContentType.JSON)
            .body(
                """
                {
                  "afzender": "00000001003214345000",
                  "ontvanger": "999993653",
                  "onderwerp": "",
                  "inhoud": "Hierbij ontvangt u..."
                }
                """.trimIndent(),
            )
            .`when`().post("/api/v1/berichten")
            .then()
            .statusCode(400)
            .contentType("application/problem+json")
            .body("status", `is`(400))
    }

    @Test
    fun `POST berichten met te lange onderwerp retourneert 400 Problem JSON`() {
        val tooLong = "x".repeat(256)
        given()
            .contentType(ContentType.JSON)
            .body(
                """
                {
                  "afzender": "00000001003214345000",
                  "ontvanger": "999993653",
                  "onderwerp": "$tooLong",
                  "inhoud": "Hierbij ontvangt u..."
                }
                """.trimIndent(),
            )
            .`when`().post("/api/v1/berichten")
            .then()
            .statusCode(400)
            .contentType("application/problem+json")
    }

    @Test
    fun `POST berichten persisteert het bericht in H2`() {
        val responseBerichtId: String = given()
            .contentType(ContentType.JSON)
            .body(
                """
                {
                  "afzender": "00000001003214345000",
                  "ontvanger": "999993653",
                  "onderwerp": "Test persistentie",
                  "inhoud": "Inhoud"
                }
                """.trimIndent(),
            )
            .`when`().post("/api/v1/berichten")
            .then()
            .statusCode(201)
            .extract().path("berichtId")

        val opgeslagenAantal = repository.count()
        assert(opgeslagenAantal == 1L) { "Verwacht 1 bericht in DB, kreeg $opgeslagenAantal" }
        assert(responseBerichtId.isNotBlank())
    }
}
```

- [ ] **Stap 3: Voer tests uit — verwacht compile failure**

Run: `./mvnw test -pl services/berichtenmagazijn -B`
Verwacht: Compile error (`AanleverResource` bestaat nog niet).

---

## Task 18: Implementeer `AanleverResource`

**Files:**
- Create: `services/berichtenmagazijn/src/main/kotlin/nl/rijksoverheid/moz/berichtenmagazijn/aanlever/AanleverResource.kt`

- [ ] **Stap 1: Bekijk eerst de gegenereerde interface**

Run: `cat services/berichtenmagazijn/target/generated-sources/openapi/src/gen/java/nl/rijksoverheid/moz/berichtenmagazijn/api/AanleverApi.java`
Verwacht: Interface met `@POST @Path("/berichten") aanleverBericht(request): BerichtResponse`. Merk op: gegenereerde interface heeft geen basis-path `/api/v1` — dat komt van `servers:` in spec. De resource moet dus zelf `@Path("/api/v1")` hebben.

- [ ] **Stap 2: Schrijf resource**

```kotlin
package nl.rijksoverheid.moz.berichtenmagazijn.aanlever

import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject
import jakarta.ws.rs.Path
import jakarta.ws.rs.core.UriInfo
import jakarta.ws.rs.core.Context
import nl.mijnoverheidzakelijk.ldv.logboekdataverwerking.Logboek
import nl.mijnoverheidzakelijk.ldv.logboekdataverwerking.LogboekContext
import nl.rijksoverheid.moz.berichtenmagazijn.api.AanleverApi
import nl.rijksoverheid.moz.berichtenmagazijn.api.model.AanleverBerichtRequest
import nl.rijksoverheid.moz.berichtenmagazijn.api.model.BerichtLinks
import nl.rijksoverheid.moz.berichtenmagazijn.api.model.BerichtResponse
import nl.rijksoverheid.moz.berichtenmagazijn.api.model.Link

@Path("/api/v1")
@ApplicationScoped
class AanleverResource(
    private val opslagService: BerichtOpslagService,
) : AanleverApi {

    @Inject
    lateinit var logboekContext: LogboekContext

    @Context
    lateinit var uriInfo: UriInfo

    @Logboek(activity = "aanleveren-bericht")
    override fun aanleverBericht(request: AanleverBerichtRequest): BerichtResponse {
        logboekContext.dataSubjectId = request.ontvanger
        logboekContext.dataSubjectType = "natuurlijk_persoon_of_organisatie"

        val bericht = opslagService.opslaanBericht(
            afzender = request.afzender,
            ontvanger = request.ontvanger,
            onderwerp = request.onderwerp,
            inhoud = request.inhoud,
        )

        val selfHref = uriInfo.baseUriBuilder
            .path("berichten")
            .path(bericht.berichtId.toString())
            .build()
            .toString()

        return BerichtResponse().apply {
            berichtId = bericht.berichtId
            afzender = bericht.afzender
            ontvanger = bericht.ontvanger
            onderwerp = bericht.onderwerp
            tijdstip = bericht.tijdstip
            links = BerichtLinks().apply {
                self = Link().apply { href = java.net.URI.create(selfHref) }
            }
        }
    }
}
```

Noot: de property-namen (`berichtId`, `afzender`, etc.) en setters op `BerichtResponse`, `BerichtLinks`, `Link` komen uit de gegenereerde code. Als de gegenereerde code andere namen gebruikt (bijv. `_links` → `links`), pas de toewijzingen aan op basis van de werkelijk gegenereerde Java. Controleer na compile.

- [ ] **Stap 3: Voer integratietests uit**

Run: `./mvnw test -pl services/berichtenmagazijn -B -Dtest=AanleverResourceIntegrationTest`
Verwacht: Alle 5 tests PASS.

Als een test faalt door verkeerde property-naam in `BerichtResponse` (bijv. `_links` vs `links`), pas de resource aan volgens de gegenereerde Java code. De generator zet JSON-properties die met `_` beginnen om naar Java-veldnamen zonder `_`, met `@JsonProperty("_links")` behoud van JSON-naam.

- [ ] **Stap 4: Commit**

```bash
git add services/berichtenmagazijn/src/main/kotlin/nl/rijksoverheid/moz/berichtenmagazijn/aanlever/AanleverResource.kt \
         services/berichtenmagazijn/src/test/kotlin/nl/rijksoverheid/moz/berichtenmagazijn/aanlever/AanleverResourceIntegrationTest.kt \
         services/berichtenmagazijn/src/test/resources/application.properties
git commit -m "Implementeer AanleverResource met integratietests"
```

---

## Task 19: Voeg OpenAPI contract-test toe

**Files:**
- Create: `services/berichtenmagazijn/src/test/kotlin/nl/rijksoverheid/moz/berichtenmagazijn/aanlever/OpenApiContractTest.kt`

- [ ] **Stap 1: Schrijf contract-test**

```kotlin
package nl.rijksoverheid.moz.berichtenmagazijn.aanlever

import com.atlassian.oai.validator.restassured.OpenApiValidationFilter
import io.quarkus.test.junit.QuarkusTest
import io.restassured.RestAssured.given
import io.restassured.http.ContentType
import jakarta.inject.Inject
import jakarta.transaction.Transactional
import nl.rijksoverheid.moz.berichtenmagazijn.opslag.BerichtRepository
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

@QuarkusTest
class OpenApiContractTest {

    @Inject
    lateinit var repository: BerichtRepository

    private val validationFilter = OpenApiValidationFilter(
        "openapi/berichtenmagazijn-api.yaml",
    )

    @BeforeEach
    @Transactional
    fun cleanDatabase() {
        repository.deleteAll()
    }

    @Test
    fun `happy path respecteert OpenAPI spec (request en response)`() {
        given()
            .filter(validationFilter)
            .contentType(ContentType.JSON)
            .body(
                """
                {
                  "afzender": "00000001003214345000",
                  "ontvanger": "999993653",
                  "onderwerp": "Contract test",
                  "inhoud": "Contract test inhoud"
                }
                """.trimIndent(),
            )
            .`when`().post("/api/v1/berichten")
            .then()
            .statusCode(201)
    }

    @Test
    fun `400 Problem response respecteert OpenAPI spec`() {
        given()
            .filter(validationFilter)
            .contentType(ContentType.JSON)
            .body("""{"afzender": "a"}""")
            .`when`().post("/api/v1/berichten")
            .then()
            .statusCode(400)
    }
}
```

- [ ] **Stap 2: Voer uit**

Run: `./mvnw test -pl services/berichtenmagazijn -B -Dtest=OpenApiContractTest`
Verwacht: Beide tests PASS. De validator controleert dat request- en response-body aan de spec voldoen.

- [ ] **Stap 3: Commit**

```bash
git add services/berichtenmagazijn/src/test/kotlin/nl/rijksoverheid/moz/berichtenmagazijn/aanlever/OpenApiContractTest.kt
git commit -m "Voeg OpenApiContractTest toe"
```

---

## Task 20: Voeg CI-workflow toe

**Files:**
- Create: `.github/workflows/test-berichtenmagazijn.yml`

- [ ] **Stap 1: Schrijf workflow**

```yaml
name: Test Berichtenmagazijn

on:
  push:
    branches: ['feature/berichtenmagazijn-**', 'main']
  pull_request:
    paths:
      - 'services/berichtenmagazijn/**'
      - 'libraries/fbs-common/**'
      - 'pom.xml'
  workflow_dispatch:

permissions: read-all

jobs:
  test:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4
      - uses: actions/setup-java@v4
        with:
          distribution: temurin
          java-version: 21
          cache: maven
      - name: Compile
        run: ./mvnw compile -pl services/berichtenmagazijn -am -B -q
      - name: Test
        run: ./mvnw test -pl services/berichtenmagazijn -am -B
```

- [ ] **Stap 2: Commit**

```bash
git add .github/workflows/test-berichtenmagazijn.yml
git commit -m "Voeg CI-workflow toe voor berichtenmagazijn"
```

---

## Task 21: Update CLAUDE.md

**Files:**
- Modify: `CLAUDE.md`

- [ ] **Stap 1: Werk "Actieve modules" regel bij**

Zoek in `CLAUDE.md` de regel die begint met `- **Actieve modules:**` en vervang door:

```markdown
- **Actieve modules:** `services/berichtensessiecache`, `services/berichtenmagazijn`. De gedeelde JAX-RS filters en exception mappers staan in `libraries/fbs-common`. `services/berichtenlijst/` bestaat als directory maar is niet actief.
```

- [ ] **Stap 2: Werk tabel "Belangrijke bestanden" bij**

Zoek de tabel onder "## Belangrijke bestanden" en voeg de volgende rijen toe (onder de bestaande rijen, vóór `docs/architecture/`):

```markdown
| `libraries/fbs-common/`                | Gedeelde JAX-RS filters en exception mappers                    |
| `services/berichtenmagazijn/pom.xml`   | Module POM (OpenAPI generator, H2, JPA, Fault Tolerance)        |
| `services/berichtenmagazijn/src/main/resources/openapi/berichtenmagazijn-api.yaml` | OpenAPI spec Aanlever API |
```

- [ ] **Stap 3: Werk "Build & test commando's" sectie bij**

Voeg onder de bestaande commando's toe:

```bash
./mvnw compile -pl services/berichtenmagazijn -am                # Compileren berichtenmagazijn
./mvnw test -pl services/berichtenmagazijn -am                   # Tests berichtenmagazijn
./mvnw quarkus:dev -pl services/berichtenmagazijn                # Dev mode
```

- [ ] **Stap 4: Commit**

```bash
git add CLAUDE.md
git commit -m "Documenteer berichtenmagazijn en fbs-common in CLAUDE.md"
```

---

## Task 22: End-to-end verificatie

- [ ] **Stap 1: Full build vanaf schone workspace**

Run: `./mvnw clean verify -B`
Verwacht: `BUILD SUCCESS` over alle drie de modules (`fbs-common`, `berichtensessiecache`, `berichtenmagazijn`).

- [ ] **Stap 2: Coverage check**

Run: `./mvnw test -pl services/berichtenmagazijn -am -B`
Verwacht: JaCoCo meldt ≥ 90% line coverage voor berichtenmagazijn (anders slaagt de `check`-executie niet).

Als coverage te laag is: voeg tests toe voor ongedekte branches in `BerichtOpslagService` of `AanleverResource`.

- [ ] **Stap 3: Dev-mode smoke test (handmatig)**

Run: `./mvnw quarkus:dev -pl services/berichtenmagazijn -am`
In een andere terminal:
```bash
curl -v -X POST http://localhost:8090/api/v1/berichten \
  -H "Content-Type: application/json" \
  -d '{"afzender":"00000001003214345000","ontvanger":"999993653","onderwerp":"Handmatige test","inhoud":"Inhoud"}'
```
Verwacht: `HTTP/1.1 201 Created`, response headers bevatten `API-Version: 0.1.0`, `X-Frame-Options: DENY`, `Cache-Control: no-store`. Body is valide JSON met `berichtId` en `_links.self.href`.

- [ ] **Stap 4: Update planstatus**

Wijzig in `docs/plans/2026-04-14-berichtenmagazijn-aanlever-api.md` en `docs/plans/2026-04-14-berichtenmagazijn-aanlever-api-plan.md` de eerste regel naar:

```markdown
**Status:** Uitgevoerd
```

- [ ] **Stap 5: Commit**

```bash
git add docs/plans/2026-04-14-berichtenmagazijn-aanlever-api.md \
         docs/plans/2026-04-14-berichtenmagazijn-aanlever-api-plan.md
git commit -m "Markeer plannen als uitgevoerd"
```

- [ ] **Stap 6: Push en open PR**

```bash
git push -u origin feature/berichtenmagazijn-aanlever-api
gh pr create --base issue-1/sessiecache-c4-alignment \
  --title "Issue #410: Berichtenmagazijn module met Aanlever API" \
  --body "$(cat <<'EOF'
## Summary
- Nieuwe Maven module `libraries/fbs-common` met gedeelde JAX-RS filters en exception mappers
- Nieuwe Maven module `services/berichtenmagazijn` met Aanlever API (POST /api/v1/berichten)
- Dataopslag via H2 embedded + JPA/Panache; @CircuitBreaker op schrijfoperaties
- Sessiecache refactor: security/cache headers naar fbs-common

Implementeert [#410](https://github.com/MinBZK/MijnOverheidZakelijk/issues/410).

Basisbranch: `issue-1/sessiecache-c4-alignment` (PR #26) — merge pas nadat #26 is gemerged naar main.

## Test plan
- [ ] `./mvnw clean verify` slaagt over alle modules
- [ ] JaCoCo line coverage ≥ 90% voor berichtenmagazijn
- [ ] OpenAPI spec valideert zonder fouten tegen ADR Spectral ruleset
- [ ] Handmatige curl-test in dev-mode retourneert 201 Created met correcte headers

🤖 Generated with [Claude Code](https://claude.com/claude-code)
EOF
)"
```

---

## Self-review

**Spec coverage** — gecontroleerd tegen `docs/plans/2026-04-14-berichtenmagazijn-aanlever-api.md`:

| Spec-onderdeel | Taak |
|---|---|
| `libraries/fbs-common` met filters/mappers | Tasks 1–9 |
| `services/berichtenmagazijn` module | Task 10 |
| Package-structuur voorbereid | Task 10 (stap 5: `.gitkeep` per package) |
| OpenAPI spec + Spectral validatie | Task 11 |
| Bericht + BerichtEntity | Task 12 |
| BerichtRepository | Task 13 |
| BerichtOpslagService met @CircuitBreaker | Tasks 14–15 |
| ApiVersionFilter per service | Task 16 |
| AanleverResource (integratietests) | Tasks 17–18 |
| OpenAPI contract tests | Task 19 |
| CI workflow | Task 20 |
| CLAUDE.md update | Task 21 |
| End-to-end verificatie + coverage | Task 22 |
| Sessiecache refactor (ApiVersionFilter opschonen) | Task 9 |

**Placeholder scan:** Geen TBD/TODO/"implement later" in het plan. Elke stap heeft concrete code of een concreet commando.

**Type consistency:**
- `Bericht` data class (Task 12): `berichtId`, `afzender`, `ontvanger`, `onderwerp`, `inhoud`, `tijdstip`. Consistent gebruikt in `BerichtEntity` (Task 12), `BerichtOpslagServiceTest` (Task 14), `BerichtOpslagService` (Task 15), `AanleverResource` (Task 18).
- `BerichtOpslagService.opslaanBericht(afzender, ontvanger, onderwerp, inhoud)`: signature identiek in test en implementatie.
- `Problem(type, title, status, detail, instance)` in fbs-common: consistent gebruikt in beide mappers.
- OpenAPI-gegenereerde types (`AanleverBerichtRequest`, `BerichtResponse`, `BerichtLinks`, `Link`): gebruik in Task 18 expliciet geverifieerd via stap 1 (cat gegenereerde interface).
