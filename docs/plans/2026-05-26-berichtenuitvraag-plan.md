**Status:** Concept

# Berichten Uitvraag Service — implementatieplan

> **Voor agentic workers:** REQUIRED SUB-SKILL: gebruik `superpowers:subagent-driven-development` (aanbevolen) of `superpowers:executing-plans` om dit plan taak-voor-taak uit te voeren. Stappen gebruiken checkbox (`- [ ]`)-syntax voor tracking.

**Doel:** Implementeer `services/berichtenuitvraag` conform [ontwerp](2026-05-26-berichtenuitvraag-design.md) en [issue #413](https://github.com/MinBZK/MijnOverheidZakelijk/issues/413).

**Architectuur:** Quarkus REST-resource implementeert OpenAPI-gegenereerde interface, delegeert aan drie services (`Berichtenlijst`, `BerichtOphaal`, `BerichtBeheer`) die over Quarkus REST-clients praten met `berichtensessiecache` en `berichtenmagazijn`. Beheer-operaties doen magazijn-first dual-write met best-effort cache-invalidate bij cache-faal.

**Tech Stack:** Quarkus 3.x, Kotlin (JVM 21), `quarkus-rest-jackson`, `quarkus-rest-client-jackson`, OpenAPI `jaxrs-spec`-generator, `fbs-common` (filters/mappers), JUnit 5 + MockK + WireMock + `swagger-request-validator-restassured`.

---

## Bestandsstructuur

| Pad | Verantwoordelijkheid |
|---|---|
| `pom.xml` (parent) | Voeg module toe |
| `services/berichtenuitvraag/pom.xml` | Module-pom (kopie van sessiecache-pom, aangepast) |
| `services/berichtenuitvraag/src/main/resources/openapi/berichtenuitvraag-api.yaml` | API-contract |
| `services/berichtenuitvraag/src/main/resources/application.properties` | Quarkus config |
| `…/uitvraag/UitvraagResource.kt` | REST-resource (implementeert gegenereerde `UitvraagApi`) |
| `…/uitvraag/BerichtenlijstService.kt` | Lijst/zoek/SSE-orchestratie naar sessiecache |
| `…/uitvraag/BerichtOphaalService.kt` | Bericht-detail + bijlage stream-passthrough |
| `…/uitvraag/BerichtBeheerService.kt` | Dual-write (magazijn-first + cache-invalidate-fallback) |
| `…/uitvraag/SessiecacheClient.kt` | Quarkus REST-client → sessiecache |
| `…/uitvraag/MagazijnClient.kt` | Quarkus REST-client → magazijn (TODO: FSC) |
| `…/uitvraag/UitvraagDtoMapper.kt` | Sessiecache- en magazijn-DTOs ↔ uitvraag-API-modellen |
| `…/uitvraag/BijlageContentTypeFilter.kt` | Override Content-Type voor bijlage-stream |
| `…/uitvraag/BerichtenuitvraagApiVersionProvider.kt` | `API-Version`-header op alle responses |
| `compose.yaml` | Service-entry + poorten |
| `bruno/berichtenuitvraag/…` | API-requests-collectie |

---

## Task 1: Maven-module-skelet

**Files:**
- Modify: `pom.xml` (parent)
- Create: `services/berichtenuitvraag/pom.xml`
- Create: `services/berichtenuitvraag/src/main/kotlin/nl/rijksoverheid/moz/fbs/berichtenuitvraag/uitvraag/.gitkeep`
- Create: `services/berichtenuitvraag/src/test/kotlin/nl/rijksoverheid/moz/fbs/berichtenuitvraag/.gitkeep`

- [ ] **Step 1: Voeg module toe aan parent pom**

In `pom.xml` zoek het `<modules>`-blok en voeg toe ná `services/berichtenmagazijn`:

```xml
<module>services/berichtenuitvraag</module>
```

- [ ] **Step 2: Maak module-pom op basis van sessiecache-pom**

Kopieer `services/berichtensessiecache/pom.xml` naar `services/berichtenuitvraag/pom.xml` en pas aan:
- `<artifactId>berichtenuitvraag</artifactId>`
- `<name>FBS Berichten Uitvraag Service</name>`
- `<description>Frontend-API voor het portaal: aggregeert sessiecache + magazijn</description>`
- Verwijder dependency op `quarkus-redis-client` (geen Redis in deze service)
- In de openapi-generator `<configuration>` blok:
  - `<inputSpec>${project.basedir}/src/main/resources/openapi/berichtenuitvraag-api.yaml</inputSpec>`
  - `<apiPackage>nl.rijksoverheid.moz.fbs.berichtenuitvraag.api</apiPackage>`
  - `<modelPackage>nl.rijksoverheid.moz.fbs.berichtenuitvraag.api.model</modelPackage>`
  - `<apisToGenerate>Uitvraag</apisToGenerate>`  (één tag: `Uitvraag`)
- In de antrun-plugin `apiinfo.dir`:
  - `${project.build.directory}/generated-sources/openapi/src/gen/java/nl/rijksoverheid/moz/fbs/berichtenuitvraag`
  - Package in het gegenereerde `ApiInfo.java`: `nl.rijksoverheid.moz.fbs.berichtenuitvraag`
  - Spec-pad: `${project.basedir}/src/main/resources/openapi/berichtenuitvraag-api.yaml`
- In jacoco-plugin `<excludes>`: `nl/rijksoverheid/moz/fbs/berichtenuitvraag/api/**`

- [ ] **Step 3: Maak directories aan**

```bash
mkdir -p services/berichtenuitvraag/src/main/kotlin/nl/rijksoverheid/moz/fbs/berichtenuitvraag/uitvraag
mkdir -p services/berichtenuitvraag/src/main/resources/openapi
mkdir -p services/berichtenuitvraag/src/test/kotlin/nl/rijksoverheid/moz/fbs/berichtenuitvraag/uitvraag
touch services/berichtenuitvraag/src/main/kotlin/nl/rijksoverheid/moz/fbs/berichtenuitvraag/uitvraag/.gitkeep
touch services/berichtenuitvraag/src/test/kotlin/nl/rijksoverheid/moz/fbs/berichtenuitvraag/.gitkeep
```

- [ ] **Step 4: Verifieer dat parent pom de module ziet (zonder spec — verwacht generator-fout)**

Run: `./mvnw -pl services/berichtenuitvraag -am help:effective-pom -q | grep -A1 artifactId | head -5`
Expected: `<artifactId>berichtenuitvraag</artifactId>` verschijnt.

- [ ] **Step 5: Commit**

```bash
git add pom.xml services/berichtenuitvraag/
git commit -m "feat(uitvraag): Maven-module-skelet (#413)

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>"
```

---

## Task 2: OpenAPI-spec — basis + lees-endpoints

**Files:**
- Create: `services/berichtenuitvraag/src/main/resources/openapi/berichtenuitvraag-api.yaml`

We splitsen de spec in twee taken: eerst basis + lees-endpoints (Task 2), daarna schrijf-endpoints + bijlage (Task 3). Reden: kleinere reviews, eerder een compile-baseline.

- [ ] **Step 1: Schrijf spec — info, servers, security headers, parameters, gedeelde schemas, GET-paths**

Maak `services/berichtenuitvraag/src/main/resources/openapi/berichtenuitvraag-api.yaml` met de volgende inhoud. (Modelleer naar `services/berichtensessiecache/src/main/resources/openapi/berichtensessiecache-api.yaml` voor stijl-consistentie.)

```yaml
openapi: 3.0.3
info:
  title: Berichten Uitvraag API
  version: 0.1.0
  description: |
    Frontend-API voor het MijnOverheid-Zakelijk portaal. Aggregeert berichten
    uit de berichtensessiecache (lees) en het berichtenmagazijn (bijlagen,
    persistente status-mutaties).
  contact:
    name: MijnOverheid Zakelijk
servers:
  - url: /api/v1

tags:
  - name: Uitvraag
    description: Berichten-uitvraag voor het portaal

paths:
  /berichten:
    get:
      tags: [Uitvraag]
      summary: Berichtenlijst per map
      operationId: getBerichten
      parameters:
        - $ref: '#/components/parameters/OntvangerHeader'
        - name: map
          in: query
          schema: { type: string, minLength: 1, maxLength: 64 }
        - name: pagina
          in: query
          schema: { type: integer, minimum: 0, default: 0 }
        - name: paginaGrootte
          in: query
          schema: { type: integer, minimum: 1, maximum: 200, default: 20 }
      responses:
        '200':
          description: Berichtenlijst
          headers:
            API-Version: { $ref: '#/components/headers/API-Version' }
          content:
            application/json:
              schema: { $ref: '#/components/schemas/BerichtenLijst' }
        '400': { $ref: '#/components/responses/BadRequest' }
        '401': { $ref: '#/components/responses/Unauthorized' }
        '500': { $ref: '#/components/responses/InternalServerError' }

  /berichten/_ophalen:
    get:
      tags: [Uitvraag]
      summary: Vul cache voor alle mappen (Server-Sent Events)
      operationId: ophalenBerichten
      description: |
        Triggert de sessiecache om alle mappen op te halen uit de aangesloten
        magazijnen. Antwoordt met Server-Sent Events; events worden 1-op-1
        doorgegeven aan de client. Geen query-parameters: alle mappen worden
        opgehaald.
      parameters:
        - $ref: '#/components/parameters/OntvangerHeader'
      responses:
        '200':
          description: SSE-stream
          headers:
            API-Version: { $ref: '#/components/headers/API-Version' }
          content:
            text/event-stream:
              schema: { type: string }
        '401': { $ref: '#/components/responses/Unauthorized' }
        '500': { $ref: '#/components/responses/InternalServerError' }

  /berichten/_zoeken:
    get:
      tags: [Uitvraag]
      summary: Zoeken in berichten
      operationId: zoekenBerichten
      parameters:
        - $ref: '#/components/parameters/OntvangerHeader'
        - name: q
          in: query
          required: true
          schema: { type: string, minLength: 1, maxLength: 200 }
        - name: map
          in: query
          schema: { type: string, minLength: 1, maxLength: 64 }
      responses:
        '200':
          description: Zoekresultaten
          headers:
            API-Version: { $ref: '#/components/headers/API-Version' }
          content:
            application/json:
              schema: { $ref: '#/components/schemas/BerichtenLijst' }
        '400': { $ref: '#/components/responses/BadRequest' }
        '401': { $ref: '#/components/responses/Unauthorized' }
        '500': { $ref: '#/components/responses/InternalServerError' }

  /berichten/{berichtId}:
    parameters:
      - $ref: '#/components/parameters/BerichtIdPath'
    get:
      tags: [Uitvraag]
      summary: Bericht ophalen
      operationId: getBerichtById
      parameters:
        - $ref: '#/components/parameters/OntvangerHeader'
      responses:
        '200':
          description: Bericht
          headers:
            API-Version: { $ref: '#/components/headers/API-Version' }
          content:
            application/json:
              schema: { $ref: '#/components/schemas/Bericht' }
        '401': { $ref: '#/components/responses/Unauthorized' }
        '404': { $ref: '#/components/responses/NotFound' }
        '500': { $ref: '#/components/responses/InternalServerError' }

components:
  parameters:
    OntvangerHeader:
      name: X-Ontvanger
      in: header
      required: true
      schema: { type: string, pattern: '^(BSN|RSIN|KVK|OIN):[0-9]+$' }
      description: 'Type:waarde, bijv. `BSN:123456782`'
    BerichtIdPath:
      name: berichtId
      in: path
      required: true
      schema: { type: string, format: uuid }

  headers:
    API-Version:
      schema: { type: string }
      description: API-majorversie, bv. `v1`

  responses:
    BadRequest:
      description: Ongeldige aanvraag
      content:
        application/problem+json:
          schema: { $ref: '#/components/schemas/Problem' }
    Unauthorized:
      description: Niet geauthenticeerd
      content:
        application/problem+json:
          schema: { $ref: '#/components/schemas/Problem' }
    NotFound:
      description: Bericht niet gevonden
      content:
        application/problem+json:
          schema: { $ref: '#/components/schemas/Problem' }
    InternalServerError:
      description: Serverfout
      content:
        application/problem+json:
          schema: { $ref: '#/components/schemas/Problem' }

  schemas:
    BerichtenLijst:
      type: object
      required: [berichten]
      properties:
        berichten:
          type: array
          items: { $ref: '#/components/schemas/BerichtSamenvatting' }
        _links:
          $ref: '#/components/schemas/PaginaLinks'

    BerichtSamenvatting:
      type: object
      required: [id, onderwerp, ontvangenOp]
      properties:
        id: { type: string, format: uuid }
        onderwerp: { type: string }
        afzender: { type: string }
        ontvangenOp: { type: string, format: date-time }
        map: { type: string }
        status: { $ref: '#/components/schemas/BerichtStatus' }
        _links: { $ref: '#/components/schemas/BerichtLinks' }

    Bericht:
      type: object
      required: [id, onderwerp, ontvangenOp]
      properties:
        id: { type: string, format: uuid }
        onderwerp: { type: string }
        afzender: { type: string }
        inhoud: { type: string }
        ontvangenOp: { type: string, format: date-time }
        map: { type: string }
        status: { $ref: '#/components/schemas/BerichtStatus' }
        bijlagen:
          type: array
          items: { $ref: '#/components/schemas/BijlageMetadata' }
        _links: { $ref: '#/components/schemas/BerichtLinks' }

    BijlageMetadata:
      type: object
      required: [id, naam]
      properties:
        id: { type: string, format: uuid }
        naam: { type: string }
        mimeType: { type: string }
        groottebytes: { type: integer, format: int64 }
        _links: { $ref: '#/components/schemas/BijlageLinks' }

    BerichtStatus:
      type: string
      enum: [gelezen, ongelezen]
      nullable: true

    Link:
      type: object
      properties:
        href: { type: string, format: uri-reference }

    BerichtLinks:
      type: object
      properties:
        self: { $ref: '#/components/schemas/Link' }

    BijlageLinks:
      type: object
      properties:
        self: { $ref: '#/components/schemas/Link' }

    PaginaLinks:
      type: object
      properties:
        self: { $ref: '#/components/schemas/Link' }
        next: { $ref: '#/components/schemas/Link' }
        prev: { $ref: '#/components/schemas/Link' }

    Problem:
      type: object
      properties:
        type: { type: string }
        title: { type: string }
        status: { type: integer }
        detail: { type: string }
        instance: { type: string }
```

- [ ] **Step 2: Run Spectral-linter tegen ADR-ruleset**

Run:
```bash
npx -y @stoplight/spectral-cli lint \
  services/berichtenuitvraag/src/main/resources/openapi/berichtenuitvraag-api.yaml \
  --ruleset https://static.developer.overheid.nl/adr/ruleset.yaml
```
Expected: 0 errors (warnings mogen indien onvermijdelijk — vergelijk met sessiecache-output).

- [ ] **Step 3: Compileer module om interface-generatie te verifiëren**

Run: `./mvnw clean compile -pl services/berichtenuitvraag -am`
Expected: BUILD SUCCESS; `target/generated-sources/openapi/src/gen/java/nl/rijksoverheid/moz/fbs/berichtenuitvraag/api/UitvraagApi.java` bestaat met methodes `getBerichten`, `ophalenBerichten`, `zoekenBerichten`, `getBerichtById`.

- [ ] **Step 4: Commit**

```bash
git add services/berichtenuitvraag/src/main/resources/openapi/berichtenuitvraag-api.yaml
git commit -m "feat(uitvraag): OpenAPI-spec lees-endpoints (#413)

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>"
```

---

## Task 3: OpenAPI-spec — schrijf-endpoints + bijlage

**Files:**
- Modify: `services/berichtenuitvraag/src/main/resources/openapi/berichtenuitvraag-api.yaml`

- [ ] **Step 1: Voeg paths toe**

Voeg onder `paths:` toe (ná `/berichten/{berichtId}` `get`):

```yaml
  /berichten/{berichtId}:
    # ... bestaande get behouden
    patch:
      tags: [Uitvraag]
      summary: Bericht-status bijwerken / verplaatsen
      operationId: updateBericht
      parameters:
        - $ref: '#/components/parameters/OntvangerHeader'
      requestBody:
        required: true
        content:
          application/merge-patch+json:
            schema: { $ref: '#/components/schemas/BerichtPatch' }
      responses:
        '200':
          description: Bijgewerkt bericht
          headers:
            API-Version: { $ref: '#/components/headers/API-Version' }
          content:
            application/json:
              schema: { $ref: '#/components/schemas/Bericht' }
        '400': { $ref: '#/components/responses/BadRequest' }
        '401': { $ref: '#/components/responses/Unauthorized' }
        '404': { $ref: '#/components/responses/NotFound' }
        '500': { $ref: '#/components/responses/InternalServerError' }
        '502': { $ref: '#/components/responses/BadGateway' }
    delete:
      tags: [Uitvraag]
      summary: Bericht verwijderen
      operationId: verwijderBericht
      parameters:
        - $ref: '#/components/parameters/OntvangerHeader'
      responses:
        '204':
          description: Verwijderd
          headers:
            API-Version: { $ref: '#/components/headers/API-Version' }
        '401': { $ref: '#/components/responses/Unauthorized' }
        '404': { $ref: '#/components/responses/NotFound' }
        '500': { $ref: '#/components/responses/InternalServerError' }
        '502': { $ref: '#/components/responses/BadGateway' }

  /berichten/{berichtId}/bijlagen/{bijlageId}:
    parameters:
      - $ref: '#/components/parameters/BerichtIdPath'
      - name: bijlageId
        in: path
        required: true
        schema: { type: string, format: uuid }
    get:
      tags: [Uitvraag]
      summary: Bijlage ophalen (octet-stream, dynamic Content-Type)
      operationId: getBijlage
      parameters:
        - $ref: '#/components/parameters/OntvangerHeader'
      responses:
        '200':
          description: Bijlage-bytes
          headers:
            API-Version: { $ref: '#/components/headers/API-Version' }
            Content-Type:
              schema: { type: string }
              description: Werkelijk MIME-type van de bijlage
            Content-Disposition:
              schema: { type: string }
              description: '`attachment`'
          content:
            '*/*':
              schema: { type: string, format: binary }
        '401': { $ref: '#/components/responses/Unauthorized' }
        '404': { $ref: '#/components/responses/NotFound' }
        '500': { $ref: '#/components/responses/InternalServerError' }
```

- [ ] **Step 2: Voeg schemas + responses toe**

Onder `components.schemas` toevoegen:

```yaml
    BerichtPatch:
      type: object
      description: |
        JSON Merge Patch (RFC 7396). Beide velden optioneel; minimaal één veld
        vereist. `status` wijzigt leesstatus; `map` verplaatst het bericht.
      properties:
        status: { $ref: '#/components/schemas/BerichtStatus' }
        map: { type: string, minLength: 1, maxLength: 64 }
      minProperties: 1
```

Onder `components.responses` toevoegen:

```yaml
    BadGateway:
      description: Magazijn-write geslaagd maar cache-update faalde
      content:
        application/problem+json:
          schema: { $ref: '#/components/schemas/Problem' }
```

- [ ] **Step 3: Spectral-validatie**

Run: `npx -y @stoplight/spectral-cli lint services/berichtenuitvraag/src/main/resources/openapi/berichtenuitvraag-api.yaml --ruleset https://static.developer.overheid.nl/adr/ruleset.yaml`
Expected: 0 errors.

- [ ] **Step 4: Compileer en verifieer dat de extra interface-methodes gegenereerd zijn**

Run: `./mvnw clean compile -pl services/berichtenuitvraag -am`
Expected: `UitvraagApi.java` bevat methodes `updateBericht`, `verwijderBericht`, `getBijlage`; `BerichtPatch.java` gegenereerd.

- [ ] **Step 5: Commit**

```bash
git add services/berichtenuitvraag/src/main/resources/openapi/berichtenuitvraag-api.yaml
git commit -m "feat(uitvraag): OpenAPI-spec schrijf-endpoints + bijlage (#413)

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>"
```

---

## Task 4: Quarkus application.properties + ApiVersionProvider

**Files:**
- Create: `services/berichtenuitvraag/src/main/resources/application.properties`
- Create: `…/BerichtenuitvraagApiVersionProvider.kt`
- Create: `…/uitvraag/SessiecacheClient.kt` (REST-client interface, alleen signature)
- Create: `…/uitvraag/MagazijnClient.kt` (REST-client interface, alleen signature)

- [ ] **Step 1: Schrijf application.properties**

Maak `services/berichtenuitvraag/src/main/resources/application.properties` met:

```properties
# Application
quarkus.application.name=berichtenuitvraag
quarkus.application.version=0.1.0

# HTTP
quarkus.http.port=8080

# Security headers op ALLE responses (ook /openapi.json, /q/health, dev-UI).
# JAX-RS-paden krijgen ze óók via fbs-common/SecurityHeadersFilter (defense-in-depth).
quarkus.http.header."X-Frame-Options".value=DENY
quarkus.http.header."X-Content-Type-Options".value=nosniff
quarkus.http.header."Strict-Transport-Security".value=max-age=31536000; includeSubDomains; preload
quarkus.http.header."Content-Security-Policy".value=frame-ancestors 'none'
quarkus.http.header."Referrer-Policy".value=no-referrer

# OpenAPI / Swagger UI
quarkus.smallrye-openapi.path=/openapi.json
quarkus.swagger-ui.always-include=true
quarkus.smallrye-openapi.info-title=FBS Berichten Uitvraag API
quarkus.smallrye-openapi.info-version=0.1.0

# JSON: laat optionele velden weg i.p.v. null (zoals andere services).
quarkus.jackson.serialization-inclusion=non_null

# REST-clients
# TODO(#11): vervangen door FSC outway-URL zodra Issue 11 geïntegreerd is.
quarkus.rest-client."nl.rijksoverheid.moz.fbs.berichtenuitvraag.uitvraag.SessiecacheClient".url=${SESSIECACHE_URL:http://localhost:8080}
quarkus.rest-client."nl.rijksoverheid.moz.fbs.berichtenuitvraag.uitvraag.MagazijnClient".url=${MAGAZIJN_URL:http://localhost:8081}

# Logboek Dataverwerkingen
logboekdataverwerking.enabled=true
logboekdataverwerking.service-name=berichtenuitvraag
logboekdataverwerking.clickhouse.endpoint=http://localhost:8123
logboekdataverwerking.clickhouse.username=${CLICKHOUSE_USERNAME:ldv}
logboekdataverwerking.clickhouse.password=${CLICKHOUSE_PASSWORD:ldv}
logboekdataverwerking.clickhouse.database=ldv_logging
logboekdataverwerking.clickhouse.table=logboek_dataverwerkingen

quarkus.index-dependency.ldv.group-id=nl.mijnoverheidzakelijk.ldv
quarkus.index-dependency.ldv.artifact-id=logboekdataverwerking-wrapper

# Logging
quarkus.log.level=INFO
quarkus.log.category."nl.rijksoverheid.moz".level=DEBUG
```

- [ ] **Step 2: Schrijf ApiVersionProvider**

Maak `services/berichtenuitvraag/src/main/kotlin/nl/rijksoverheid/moz/fbs/berichtenuitvraag/BerichtenuitvraagApiVersionProvider.kt`:

```kotlin
package nl.rijksoverheid.moz.fbs.berichtenuitvraag

import jakarta.enterprise.context.ApplicationScoped
import nl.rijksoverheid.moz.fbs.common.ApiVersionProvider

/** Levert `API-Version`-headerwaarde aan fbs-common; gebruikt de gegenereerde [ApiInfo]. */
@ApplicationScoped
class BerichtenuitvraagApiVersionProvider : ApiVersionProvider {
    override fun apiVersion(): String = ApiInfo.API_VERSION
}
```

> **Verificatie:** zoek in `libraries/fbs-common/` naar `ApiVersionProvider` om het interface-contract te bevestigen. Pas signature aan als die afwijkt.

- [ ] **Step 3: REST-client interfaces — alleen signatures**

Maak `…/uitvraag/SessiecacheClient.kt`:

```kotlin
package nl.rijksoverheid.moz.fbs.berichtenuitvraag.uitvraag

import jakarta.ws.rs.DELETE
import jakarta.ws.rs.GET
import jakarta.ws.rs.HeaderParam
import jakarta.ws.rs.PATCH
import jakarta.ws.rs.Path
import jakarta.ws.rs.PathParam
import jakarta.ws.rs.QueryParam
import nl.rijksoverheid.moz.fbs.berichtenuitvraag.api.model.Bericht
import nl.rijksoverheid.moz.fbs.berichtenuitvraag.api.model.BerichtPatch
import nl.rijksoverheid.moz.fbs.berichtenuitvraag.api.model.BerichtenLijst
import org.eclipse.microprofile.rest.client.inject.RegisterRestClient
import java.util.UUID

/**
 * REST-client naar de Berichtensessiecache-service. URL via
 * `quarkus.rest-client."…SessiecacheClient".url` in application.properties.
 *
 * Verschilt qua DTO-vorm soms van de uitvraag-API (status enum ↔ boolean,
 * paginatie-namen) — mapping in [UitvraagDtoMapper].
 */
@RegisterRestClient
@Path("/api/v1/berichten")
interface SessiecacheClient {
    @GET
    fun lijst(
        @HeaderParam("X-Ontvanger") xOntvanger: String,
        @QueryParam("map") map: String?,
        @QueryParam("pagina") pagina: Int?,
        @QueryParam("paginaGrootte") paginaGrootte: Int?,
    ): BerichtenLijst

    @GET
    @Path("/_zoeken")
    fun zoek(
        @HeaderParam("X-Ontvanger") xOntvanger: String,
        @QueryParam("q") q: String,
        @QueryParam("map") map: String?,
    ): BerichtenLijst

    @GET
    @Path("/{berichtId}")
    fun bericht(
        @HeaderParam("X-Ontvanger") xOntvanger: String,
        @PathParam("berichtId") berichtId: UUID,
    ): Bericht

    @PATCH
    @Path("/{berichtId}")
    fun patchBericht(
        @HeaderParam("X-Ontvanger") xOntvanger: String,
        @PathParam("berichtId") berichtId: UUID,
        patch: BerichtPatch,
    ): Bericht

    @DELETE
    @Path("/{berichtId}")
    fun verwijderBericht(
        @HeaderParam("X-Ontvanger") xOntvanger: String,
        @PathParam("berichtId") berichtId: UUID,
    )
}
```

> **Let op — SSE-endpoint niet hier:** `_ophalen` retourneert SSE en gaat via een separate streaming-aanroep (zie Task 7).

Maak `…/uitvraag/MagazijnClient.kt`:

```kotlin
package nl.rijksoverheid.moz.fbs.berichtenuitvraag.uitvraag

import jakarta.ws.rs.DELETE
import jakarta.ws.rs.GET
import jakarta.ws.rs.HeaderParam
import jakarta.ws.rs.PATCH
import jakarta.ws.rs.Path
import jakarta.ws.rs.PathParam
import jakarta.ws.rs.core.Response
import nl.rijksoverheid.moz.fbs.berichtenuitvraag.api.model.Bericht
import nl.rijksoverheid.moz.fbs.berichtenuitvraag.api.model.BerichtPatch
import org.eclipse.microprofile.rest.client.inject.RegisterRestClient
import java.util.UUID

/**
 * REST-client naar het Berichtenmagazijn. URL via
 * `quarkus.rest-client."…MagazijnClient".url` in application.properties.
 *
 * TODO(#11): vervangen door FSC outway zodra FSC-integratie er is.
 */
@RegisterRestClient
@Path("/api/v1/berichten")
interface MagazijnClient {
    @PATCH
    @Path("/{berichtId}")
    fun patchBericht(
        @HeaderParam("X-Ontvanger") xOntvanger: String,
        @PathParam("berichtId") berichtId: UUID,
        patch: BerichtPatch,
    ): Bericht

    @DELETE
    @Path("/{berichtId}")
    fun verwijderBericht(
        @HeaderParam("X-Ontvanger") xOntvanger: String,
        @PathParam("berichtId") berichtId: UUID,
    )

    @GET
    @Path("/{berichtId}/bijlagen/{bijlageId}")
    fun bijlage(
        @HeaderParam("X-Ontvanger") xOntvanger: String,
        @PathParam("berichtId") berichtId: UUID,
        @PathParam("bijlageId") bijlageId: UUID,
    ): Response
}
```

> **Waarom `Response` voor bijlage:** we hebben zowel de bytes als het werkelijke `Content-Type` van magazijn nodig. Het magazijn levert `application/octet-stream` met dynamische Content-Type-header; `Response` geeft toegang tot beide zonder vooraf type-mapping.

- [ ] **Step 4: Compileer**

Run: `./mvnw clean compile -pl services/berichtenuitvraag -am`
Expected: BUILD SUCCESS. Eventueel klagen over ontbrekende `ApiVersionProvider`-interface — los op door eerst de exacte signature uit `libraries/fbs-common/` over te nemen.

- [ ] **Step 5: Commit**

```bash
git add services/berichtenuitvraag/src/main/
git commit -m "feat(uitvraag): Quarkus config + REST-client-interfaces (#413)

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>"
```

---

## Task 5: DTO-mapper

**Files:**
- Create: `…/uitvraag/UitvraagDtoMapper.kt`
- Create: `services/berichtenuitvraag/src/test/kotlin/.../uitvraag/UitvraagDtoMapperTest.kt`

De gegenereerde modellen uit `…api.model` worden hergebruikt als clients (Quarkus REST-client serializeert ze rechtstreeks via Jackson). Mapper is hier alleen nodig wanneer sessiecache/magazijn afwijkende vorm leveren — concreet: het sessiecache-/magazijn-`Bericht`-schema is identiek aan ons eigen schema *als we de OpenAPI-modellen 1-op-1 overnemen*. Voor magazijn geldt: hun `gelezen: boolean` ↔ onze `status: enum`. Voor sessiecache geldt: identiek (enum).

- [ ] **Step 1: Schrijf falende test voor status-mapping**

Maak `…/uitvraag/UitvraagDtoMapperTest.kt`:

```kotlin
package nl.rijksoverheid.moz.fbs.berichtenuitvraag.uitvraag

import io.kotest.matchers.shouldBe
import nl.rijksoverheid.moz.fbs.berichtenuitvraag.api.model.BerichtPatch
import nl.rijksoverheid.moz.fbs.berichtenuitvraag.api.model.BerichtStatus
import org.junit.jupiter.api.Test

class UitvraagDtoMapperTest {

    @Test
    fun `status gelezen mapt naar magazijn-gelezen-true`() {
        val patch = BerichtPatch().apply { status = BerichtStatus.GELEZEN }
        val magazijnPatch = UitvraagDtoMapper.toMagazijnPatch(patch)
        magazijnPatch.gelezen shouldBe true
        magazijnPatch.map shouldBe null
    }

    @Test
    fun `status ongelezen mapt naar magazijn-gelezen-false`() {
        val patch = BerichtPatch().apply { status = BerichtStatus.ONGELEZEN }
        val magazijnPatch = UitvraagDtoMapper.toMagazijnPatch(patch)
        magazijnPatch.gelezen shouldBe false
    }

    @Test
    fun `alleen map zonder status laat gelezen leeg`() {
        val patch = BerichtPatch().apply { map = "archief" }
        val magazijnPatch = UitvraagDtoMapper.toMagazijnPatch(patch)
        magazijnPatch.gelezen shouldBe null
        magazijnPatch.map shouldBe "archief"
    }

    @Test
    fun `lege patch geeft lege magazijn-patch (validatie elders)`() {
        val patch = BerichtPatch()
        val magazijnPatch = UitvraagDtoMapper.toMagazijnPatch(patch)
        magazijnPatch.gelezen shouldBe null
        magazijnPatch.map shouldBe null
    }
}
```

> **Let op — kotest niet aanwezig?** Vervang `shouldBe` door JUnit `assertEquals`. Check eerst of `kotest-assertions-core-jvm` in `fbs-common` of root-pom staat; zo niet, gebruik JUnit.

- [ ] **Step 2: Run test om FAIL te zien**

Run: `./mvnw test -pl services/berichtenuitvraag -Dtest=UitvraagDtoMapperTest`
Expected: compileerfout — `UitvraagDtoMapper` bestaat niet.

- [ ] **Step 3: Schrijf minimale implementatie**

Maak `…/uitvraag/UitvraagDtoMapper.kt`:

```kotlin
package nl.rijksoverheid.moz.fbs.berichtenuitvraag.uitvraag

import nl.rijksoverheid.moz.fbs.berichtenuitvraag.api.model.BerichtPatch
import nl.rijksoverheid.moz.fbs.berichtenuitvraag.api.model.BerichtStatus

/**
 * Mapt tussen de uitvraag-API-DTO's en de DTO's van downstream-services. Op
 * één punt zijn de vormen niet identiek: magazijn modelleert `gelezen` als
 * boolean, uitvraag/sessiecache als enum `gelezen|ongelezen`. Alle andere
 * velden hebben dezelfde naam en vorm en worden door Jackson rechtstreeks
 * tussen JSON en de gegenereerde DTO-klassen vertaald.
 */
object UitvraagDtoMapper {

    data class MagazijnPatch(val gelezen: Boolean?, val map: String?)

    fun toMagazijnPatch(patch: BerichtPatch): MagazijnPatch =
        MagazijnPatch(
            gelezen = when (patch.status) {
                BerichtStatus.GELEZEN -> true
                BerichtStatus.ONGELEZEN -> false
                null -> null
            },
            map = patch.map,
        )
}
```

- [ ] **Step 4: Run test om PASS te zien**

Run: `./mvnw test -pl services/berichtenuitvraag -Dtest=UitvraagDtoMapperTest`
Expected: BUILD SUCCESS, 4 tests run, 0 failures.

- [ ] **Step 5: Commit**

```bash
git add services/berichtenuitvraag/src/main/kotlin/nl/rijksoverheid/moz/fbs/berichtenuitvraag/uitvraag/UitvraagDtoMapper.kt \
        services/berichtenuitvraag/src/test/kotlin/nl/rijksoverheid/moz/fbs/berichtenuitvraag/uitvraag/UitvraagDtoMapperTest.kt
git commit -m "feat(uitvraag): DTO-mapper status↔gelezen (#413)

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>"
```

---

## Task 6: BerichtBeheerService — dual-write (TDD, alle paden)

**Files:**
- Create: `…/uitvraag/BerichtBeheerService.kt`
- Create: `…/uitvraag/BerichtBeheerServiceTest.kt`

We doen deze service eerst omdat de dual-write-logica de meest delicate is. Tests met MockK.

> **Waarom 502:** "Bad Gateway" betekent semantisch "upstream gaf onbruikbaar resultaat" — past op een gefaalde write naar onze sessiecache-upstream nadat magazijn al geslaagd is.

- [ ] **Step 1: Schrijf falende tests voor alle dual-write-paden**

Maak `…/uitvraag/BerichtBeheerServiceTest.kt`:

```kotlin
package nl.rijksoverheid.moz.fbs.berichtenuitvraag.uitvraag

import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import io.mockk.verifyOrder
import jakarta.ws.rs.BadGatewayException
import jakarta.ws.rs.InternalServerErrorException
import nl.rijksoverheid.moz.fbs.berichtenuitvraag.api.model.Bericht
import nl.rijksoverheid.moz.fbs.berichtenuitvraag.api.model.BerichtPatch
import nl.rijksoverheid.moz.fbs.berichtenuitvraag.api.model.BerichtStatus
import org.eclipse.microprofile.rest.client.inject.RestClient
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import java.util.UUID

class BerichtBeheerServiceTest {

    private val sessiecache: SessiecacheClient = mockk(relaxed = true)
    private val magazijn: MagazijnClient = mockk(relaxed = true)
    private val service = BerichtBeheerService(sessiecache, magazijn)

    private val id = UUID.randomUUID()
    private val ontvanger = "BSN:123456782"
    private val patch = BerichtPatch().apply { status = BerichtStatus.GELEZEN }
    private val updated = Bericht().apply { this.id = id }

    @Test
    fun `patch happy-path magazijn-eerst dan cache geeft bericht uit cache`() {
        every { magazijn.patchBericht(any(), any(), any()) } returns updated
        every { sessiecache.patchBericht(any(), any(), any()) } returns updated

        val result = service.patch(ontvanger, id, patch)

        assertEquals(id, result.id)
        verifyOrder {
            magazijn.patchBericht(ontvanger, id, patch)
            sessiecache.patchBericht(ontvanger, id, patch)
        }
    }

    @Test
    fun `patch faalt op magazijn propageert en raakt cache niet aan`() {
        every { magazijn.patchBericht(any(), any(), any()) } throws InternalServerErrorException("magazijn-down")

        assertThrows(InternalServerErrorException::class.java) {
            service.patch(ontvanger, id, patch)
        }
        verify(exactly = 0) { sessiecache.patchBericht(any(), any(), any()) }
    }

    @Test
    fun `patch cache-faal triggert invalidate en gooit 502`() {
        every { magazijn.patchBericht(any(), any(), any()) } returns updated
        every { sessiecache.patchBericht(any(), any(), any()) } throws InternalServerErrorException("cache-down")
        every { sessiecache.verwijderBericht(any(), any()) } returns Unit  // invalidate ok

        assertThrows(BadGatewayException::class.java) {
            service.patch(ontvanger, id, patch)
        }
        verify { sessiecache.verwijderBericht(ontvanger, id) }
    }

    @Test
    fun `patch cache-faal en invalidate-faal gooit nog steeds 502`() {
        every { magazijn.patchBericht(any(), any(), any()) } returns updated
        every { sessiecache.patchBericht(any(), any(), any()) } throws InternalServerErrorException("cache-down")
        every { sessiecache.verwijderBericht(any(), any()) } throws InternalServerErrorException("ook-down")

        assertThrows(BadGatewayException::class.java) {
            service.patch(ontvanger, id, patch)
        }
    }

    @Test
    fun `verwijder happy-path doet beide deletes in volgorde`() {
        every { magazijn.verwijderBericht(any(), any()) } returns Unit
        every { sessiecache.verwijderBericht(any(), any()) } returns Unit

        service.verwijder(ontvanger, id)

        verifyOrder {
            magazijn.verwijderBericht(ontvanger, id)
            sessiecache.verwijderBericht(ontvanger, id)
        }
    }

    @Test
    fun `verwijder cache-faal gooit 502`() {
        every { magazijn.verwijderBericht(any(), any()) } returns Unit
        every { sessiecache.verwijderBericht(any(), any()) } throws InternalServerErrorException("cache-down") andThen Unit
        // Tweede call (invalidate compensatie) slaagt. Bij DELETE is invalidate
        // hetzelfde als de oorspronkelijke actie — we proberen 'm nogmaals.

        assertThrows(BadGatewayException::class.java) {
            service.verwijder(ontvanger, id)
        }
        verify(exactly = 2) { sessiecache.verwijderBericht(ontvanger, id) }
    }
}
```

> **MockK in pom:** sessiecache-pom heeft `mockk-jvm` als test-scope; dat erft hier mee. Verifieer met `grep mockk services/berichtenuitvraag/pom.xml`.

- [ ] **Step 2: Run test om FAIL te zien**

Run: `./mvnw test -pl services/berichtenuitvraag -Dtest=BerichtBeheerServiceTest`
Expected: compileerfout — `BerichtBeheerService` bestaat niet.

- [ ] **Step 3: Schrijf implementatie**

Maak `…/uitvraag/BerichtBeheerService.kt`:

```kotlin
package nl.rijksoverheid.moz.fbs.berichtenuitvraag.uitvraag

import jakarta.enterprise.context.ApplicationScoped
import jakarta.ws.rs.BadGatewayException
import nl.rijksoverheid.moz.fbs.berichtenuitvraag.api.model.Bericht
import nl.rijksoverheid.moz.fbs.berichtenuitvraag.api.model.BerichtPatch
import org.eclipse.microprofile.rest.client.inject.RestClient
import org.jboss.logging.Logger
import java.util.UUID

/**
 * Schrijft naar magazijn (bron van waarheid) en sessiecache (afgeleide cache).
 * Magazijn-faal → fout naar client, cache niet aangeraakt. Magazijn-OK +
 * cache-faal → best-effort cache-invalidate (vervangt 'stale' door 'leeg'),
 * daarna 502 zodat de client weet dat de operatie niet volledig consistent
 * doorgevoerd is. Alle operaties zijn idempotent — client mag retryen.
 */
@ApplicationScoped
class BerichtBeheerService(
    @RestClient private val sessiecache: SessiecacheClient,
    @RestClient private val magazijn: MagazijnClient,
) {

    fun patch(xOntvanger: String, berichtId: UUID, patch: BerichtPatch): Bericht {
        magazijn.patchBericht(xOntvanger, berichtId, patch)
        return try {
            sessiecache.patchBericht(xOntvanger, berichtId, patch)
        } catch (e: Exception) {
            log.warnf(e, "cache-PATCH faalde na geslaagde magazijn-PATCH; invalidate volgt. berichtId=%s", berichtId)
            compensatieInvalidate(xOntvanger, berichtId)
            throw BadGatewayException("cache-update faalde; magazijn bijgewerkt")
        }
    }

    fun verwijder(xOntvanger: String, berichtId: UUID) {
        magazijn.verwijderBericht(xOntvanger, berichtId)
        try {
            sessiecache.verwijderBericht(xOntvanger, berichtId)
        } catch (e: Exception) {
            log.warnf(e, "cache-DELETE faalde na geslaagde magazijn-DELETE; invalidate volgt. berichtId=%s", berichtId)
            compensatieInvalidate(xOntvanger, berichtId)
            throw BadGatewayException("cache-update faalde; magazijn bijgewerkt")
        }
    }

    private fun compensatieInvalidate(xOntvanger: String, berichtId: UUID) {
        try {
            sessiecache.verwijderBericht(xOntvanger, berichtId)
        } catch (e: Exception) {
            log.warnf(e, "compensatie-invalidate faalde; cache mogelijk stale tot TTL. berichtId=%s", berichtId)
        }
    }

    private companion object {
        private val log: Logger = Logger.getLogger(BerichtBeheerService::class.java)
    }
}
```

- [ ] **Step 4: Run tests om PASS te zien**

Run: `./mvnw test -pl services/berichtenuitvraag -Dtest=BerichtBeheerServiceTest`
Expected: 6 tests run, 0 failures.

- [ ] **Step 5: Commit**

```bash
git add services/berichtenuitvraag/src/main/kotlin/nl/rijksoverheid/moz/fbs/berichtenuitvraag/uitvraag/BerichtBeheerService.kt \
        services/berichtenuitvraag/src/test/kotlin/nl/rijksoverheid/moz/fbs/berichtenuitvraag/uitvraag/BerichtBeheerServiceTest.kt
git commit -m "feat(uitvraag): BerichtBeheerService dual-write (#413)

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>"
```

---

## Task 7: BerichtenlijstService — lijst, zoek, SSE-proxy

**Files:**
- Create: `…/uitvraag/BerichtenlijstService.kt`
- Create: `…/uitvraag/BerichtenlijstServiceTest.kt`

`lijst` en `zoek` zijn pure delegaties — kleine test. SSE-proxy is complexer; we testen die in Task 11 (integratie) met WireMock omdat MockK + SSE-streaming weinig oplevert in isolatie.

- [ ] **Step 1: Schrijf falende test voor lijst en zoek**

Maak `…/uitvraag/BerichtenlijstServiceTest.kt`:

```kotlin
package nl.rijksoverheid.moz.fbs.berichtenuitvraag.uitvraag

import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import nl.rijksoverheid.moz.fbs.berichtenuitvraag.api.model.BerichtenLijst
import org.junit.jupiter.api.Test

class BerichtenlijstServiceTest {

    private val sessiecache: SessiecacheClient = mockk()
    private val service = BerichtenlijstService(sessiecache)

    @Test
    fun `lijst delegeert met juiste headers en query`() {
        val expected = BerichtenLijst()
        every { sessiecache.lijst("BSN:1", "archief", 0, 20) } returns expected

        val actual = service.lijst("BSN:1", "archief", 0, 20)

        assertSame(expected, actual)
        verify(exactly = 1) { sessiecache.lijst("BSN:1", "archief", 0, 20) }
    }

    @Test
    fun `zoek delegeert q en optionele map`() {
        val expected = BerichtenLijst()
        every { sessiecache.zoek("BSN:1", "rente", null) } returns expected

        val actual = service.zoek("BSN:1", "rente", null)

        assertSame(expected, actual)
    }

    private fun <T> assertSame(expected: T, actual: T) =
        org.junit.jupiter.api.Assertions.assertSame(expected, actual)
}
```

- [ ] **Step 2: Run test om FAIL te zien**

Run: `./mvnw test -pl services/berichtenuitvraag -Dtest=BerichtenlijstServiceTest`
Expected: compileerfout — `BerichtenlijstService` bestaat niet.

- [ ] **Step 3: Schrijf implementatie**

Maak `…/uitvraag/BerichtenlijstService.kt`:

```kotlin
package nl.rijksoverheid.moz.fbs.berichtenuitvraag.uitvraag

import jakarta.enterprise.context.ApplicationScoped
import nl.rijksoverheid.moz.fbs.berichtenuitvraag.api.model.BerichtenLijst
import org.eclipse.microprofile.rest.client.inject.RestClient

/**
 * Thin pass-through naar de sessiecache voor lijst- en zoekoperaties. SSE-
 * proxy (`_ophalen`) wordt direct in [UitvraagResource] geregeld omdat de
 * streaming-call de Quarkus REST-client met `Multi<...>` vereist en niet via
 * deze service hoeft.
 */
@ApplicationScoped
class BerichtenlijstService(
    @RestClient private val sessiecache: SessiecacheClient,
) {
    fun lijst(xOntvanger: String, map: String?, pagina: Int?, paginaGrootte: Int?): BerichtenLijst =
        sessiecache.lijst(xOntvanger, map, pagina, paginaGrootte)

    fun zoek(xOntvanger: String, q: String, map: String?): BerichtenLijst =
        sessiecache.zoek(xOntvanger, q, map)
}
```

- [ ] **Step 4: Run tests om PASS te zien**

Run: `./mvnw test -pl services/berichtenuitvraag -Dtest=BerichtenlijstServiceTest`
Expected: 2 tests pass.

- [ ] **Step 5: Commit**

```bash
git add services/berichtenuitvraag/src/main/kotlin/nl/rijksoverheid/moz/fbs/berichtenuitvraag/uitvraag/BerichtenlijstService.kt \
        services/berichtenuitvraag/src/test/kotlin/nl/rijksoverheid/moz/fbs/berichtenuitvraag/uitvraag/BerichtenlijstServiceTest.kt
git commit -m "feat(uitvraag): BerichtenlijstService (#413)

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>"
```

---

## Task 8: BerichtOphaalService + BijlageContentTypeFilter

**Files:**
- Create: `…/uitvraag/BerichtOphaalService.kt`
- Create: `…/uitvraag/BijlageContentTypeFilter.kt`
- Create: `…/uitvraag/BerichtOphaalServiceTest.kt`

- [ ] **Step 1: Schrijf falende test**

Maak `…/uitvraag/BerichtOphaalServiceTest.kt`:

```kotlin
package nl.rijksoverheid.moz.fbs.berichtenuitvraag.uitvraag

import io.mockk.every
import io.mockk.mockk
import jakarta.ws.rs.core.MediaType
import jakarta.ws.rs.core.MultivaluedHashMap
import jakarta.ws.rs.core.Response
import nl.rijksoverheid.moz.fbs.berichtenuitvraag.api.model.Bericht
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.util.UUID

class BerichtOphaalServiceTest {

    private val sessiecache: SessiecacheClient = mockk()
    private val magazijn: MagazijnClient = mockk()
    private val service = BerichtOphaalService(sessiecache, magazijn)

    @Test
    fun `haalBericht delegeert naar sessiecache`() {
        val id = UUID.randomUUID()
        val bericht = Bericht().apply { this.id = id }
        every { sessiecache.bericht("BSN:1", id) } returns bericht

        val result = service.haalBericht("BSN:1", id)

        assertEquals(id, result.id)
    }

    @Test
    fun `haalBijlage retourneert magazijn-response 1-op-1 met mimeType en bytes`() {
        val berichtId = UUID.randomUUID()
        val bijlageId = UUID.randomUUID()
        val bytes = byteArrayOf(1, 2, 3)
        val headers = MultivaluedHashMap<String, Any>().apply { add("Content-Type", "application/pdf") }
        val mockResp = mockk<Response> {
            every { status } returns 200
            every { readEntity(ByteArray::class.java) } returns bytes
            every { mediaType } returns MediaType.valueOf("application/pdf")
            every { close() } returns Unit
        }
        every { magazijn.bijlage("BSN:1", berichtId, bijlageId) } returns mockResp

        val (mimeType, contents) = service.haalBijlage("BSN:1", berichtId, bijlageId)

        assertEquals("application/pdf", mimeType)
        assertEquals(bytes.toList(), contents.toList())
    }
}
```

- [ ] **Step 2: Run test om FAIL te zien**

Run: `./mvnw test -pl services/berichtenuitvraag -Dtest=BerichtOphaalServiceTest`
Expected: compileerfout — service bestaat niet.

- [ ] **Step 3: Schrijf implementatie**

Maak `…/uitvraag/BerichtOphaalService.kt`:

```kotlin
package nl.rijksoverheid.moz.fbs.berichtenuitvraag.uitvraag

import jakarta.enterprise.context.ApplicationScoped
import jakarta.ws.rs.InternalServerErrorException
import nl.rijksoverheid.moz.fbs.berichtenuitvraag.api.model.Bericht
import org.eclipse.microprofile.rest.client.inject.RestClient
import org.jboss.logging.Logger
import java.util.UUID

/**
 * Haalt bericht-detail uit de sessiecache en bijlage-bytes rechtstreeks uit
 * het magazijn (zie ontwerp: bijlagen via FSC, niet via cache).
 *
 * `haalBijlage` levert `(mimeType, bytes)` zodat de resource het Content-Type
 * via [BijlageContentTypeFilter] kan overrulen. We lezen de body in een
 * ByteArray; bij PoC-MAX van 1 MiB is dat acceptabel. Bij grotere bijlagen
 * later: switchen naar InputStream + chunked streaming.
 */
@ApplicationScoped
class BerichtOphaalService(
    @RestClient private val sessiecache: SessiecacheClient,
    @RestClient private val magazijn: MagazijnClient,
) {
    fun haalBericht(xOntvanger: String, berichtId: UUID): Bericht =
        sessiecache.bericht(xOntvanger, berichtId)

    fun haalBijlage(xOntvanger: String, berichtId: UUID, bijlageId: UUID): Pair<String, ByteArray> {
        val response = magazijn.bijlage(xOntvanger, berichtId, bijlageId)
        try {
            if (response.status >= 400) {
                throw InternalServerErrorException("magazijn-bijlage gaf status ${response.status}")
            }
            val mimeType = response.mediaType?.toString()
                ?: throw InternalServerErrorException("magazijn-bijlage zonder Content-Type")
            val bytes = response.readEntity(ByteArray::class.java)
            return mimeType to bytes
        } finally {
            response.close()
        }
    }

    private companion object {
        @Suppress("unused")
        private val log: Logger = Logger.getLogger(BerichtOphaalService::class.java)
    }
}
```

> **Let op — magazijn 4xx-fouten:** WebApplicationException uit een REST-client wordt door Quarkus al gegooid voor 4xx/5xx; in dat geval komen we hier niet. De `response.status >= 400`-check is defensief voor het geval Quarkus de fout niet doorgooit (configuratie-afwijking).

- [ ] **Step 4: Schrijf BijlageContentTypeFilter (kopie van magazijn, ander package)**

Maak `…/uitvraag/BijlageContentTypeFilter.kt`:

```kotlin
package nl.rijksoverheid.moz.fbs.berichtenuitvraag.uitvraag

import jakarta.ws.rs.container.ContainerRequestContext
import jakarta.ws.rs.container.ContainerResponseContext
import jakarta.ws.rs.container.ContainerResponseFilter
import jakarta.ws.rs.core.MediaType
import jakarta.ws.rs.ext.Provider
import org.jboss.logging.Logger

internal const val BIJLAGE_MIME_TYPE_PROPERTY = "fbs.uitvraag.bijlage.mimeType"

/**
 * Overschrijft Content-Type met het werkelijke MIME-type uit het magazijn en
 * forceert `Content-Disposition: attachment` (dicht stored-XSS-vector via
 * `text/html`/`image/svg+xml`-uploads). Identiek aan
 * `…fbs.berichtenmagazijn.ophaal.BijlageContentTypeFilter` — pas bij een
 * derde gebruiker verplaatsen naar fbs-common.
 */
@Provider
class BijlageContentTypeFilter : ContainerResponseFilter {
    override fun filter(req: ContainerRequestContext, resp: ContainerResponseContext) {
        val mimeType = req.getProperty(BIJLAGE_MIME_TYPE_PROPERTY) as? String ?: return
        val parsed = runCatching { MediaType.valueOf(mimeType) }.getOrNull()
        if (parsed == null) {
            log.warnf("BIJLAGE_MIME_TYPE_PROPERTY ongeldig (%s); Content-Type ongewijzigd.", mimeType)
            return
        }
        resp.headers.putSingle("Content-Type", parsed.toString())
        resp.headers.putSingle("Content-Disposition", "attachment")
    }

    private companion object {
        private val log: Logger = Logger.getLogger(BijlageContentTypeFilter::class.java)
    }
}
```

- [ ] **Step 5: Run tests om PASS te zien**

Run: `./mvnw test -pl services/berichtenuitvraag -Dtest=BerichtOphaalServiceTest`
Expected: 2 tests pass.

- [ ] **Step 6: Commit**

```bash
git add services/berichtenuitvraag/src/main/kotlin/nl/rijksoverheid/moz/fbs/berichtenuitvraag/uitvraag/BerichtOphaalService.kt \
        services/berichtenuitvraag/src/main/kotlin/nl/rijksoverheid/moz/fbs/berichtenuitvraag/uitvraag/BijlageContentTypeFilter.kt \
        services/berichtenuitvraag/src/test/kotlin/nl/rijksoverheid/moz/fbs/berichtenuitvraag/uitvraag/BerichtOphaalServiceTest.kt
git commit -m "feat(uitvraag): BerichtOphaalService + bijlage-Content-Type-filter (#413)

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>"
```

---

## Task 9: UitvraagResource — REST-implementatie + LDV-logging

**Files:**
- Create: `…/uitvraag/UitvraagResource.kt`
- Modify: `…/ProcessingActivities.kt` (nog aanmaken)

> Het LDV-`ProcessingActivities`-patroon staat in `services/berichtenmagazijn/src/main/kotlin/.../ProcessingActivities.kt`. We dupliceren het hier zoals dat ook tussen magazijn en sessiecache is gedaan.

- [ ] **Step 1: Voeg ProcessingActivities toe**

Maak `…/ProcessingActivities.kt`:

```kotlin
package nl.rijksoverheid.moz.fbs.berichtenuitvraag

/** LDV processing-activity-ID's per type uitvraag-operatie (AVG Art. 30). */
object ProcessingActivities {
    const val UITVRAAG_LEZEN = "UITVRAAG_LEZEN"
    const val UITVRAAG_BEHEER = "UITVRAAG_BEHEER"
}
```

- [ ] **Step 2: Schrijf UitvraagResource**

Maak `…/uitvraag/UitvraagResource.kt`:

```kotlin
package nl.rijksoverheid.moz.fbs.berichtenuitvraag.uitvraag

import io.smallrye.mutiny.Multi
import jakarta.enterprise.context.ApplicationScoped
import jakarta.ws.rs.Path
import jakarta.ws.rs.container.ContainerRequestContext
import jakarta.ws.rs.core.Context
import jakarta.ws.rs.core.Response
import nl.mijnoverheidzakelijk.ldv.logboekdataverwerking.Logboek
import nl.mijnoverheidzakelijk.ldv.logboekdataverwerking.LogboekContext
import nl.rijksoverheid.moz.fbs.berichtenuitvraag.ApiInfo
import nl.rijksoverheid.moz.fbs.berichtenuitvraag.ProcessingActivities
import nl.rijksoverheid.moz.fbs.berichtenuitvraag.api.UitvraagApi
import nl.rijksoverheid.moz.fbs.berichtenuitvraag.api.model.Bericht
import nl.rijksoverheid.moz.fbs.berichtenuitvraag.api.model.BerichtPatch
import nl.rijksoverheid.moz.fbs.berichtenuitvraag.api.model.BerichtenLijst
import java.util.UUID

/**
 * REST-resource voor de Berichten Uitvraag API. Implementeert de gegenereerde
 * [UitvraagApi]-interface en delegeert per endpoint naar de bijbehorende
 * service. Het SSE-endpoint `_ophalen` valt buiten de generator (geen
 * `Multi<>`-support in jaxrs-spec) en wordt expliciet hier als JAX-RS-route
 * toegevoegd via [SsePassthroughResource].
 */
@Path(ApiInfo.BASE_PATH + "/berichten")
@ApplicationScoped
class UitvraagResource(
    private val lijstService: BerichtenlijstService,
    private val ophaalService: BerichtOphaalService,
    private val beheerService: BerichtBeheerService,
    private val logboekContext: LogboekContext,
    @param:Context private val request: ContainerRequestContext,
) : UitvraagApi {

    @Logboek(name = "uitvraag-lijst", processingActivityId = ProcessingActivities.UITVRAAG_LEZEN)
    override fun getBerichten(
        xOntvanger: String,
        map: String?,
        pagina: Int?,
        paginaGrootte: Int?,
    ): BerichtenLijst {
        registreerLdvSubject(xOntvanger)
        return lijstService.lijst(xOntvanger, map, pagina, paginaGrootte)
    }

    @Logboek(name = "uitvraag-zoeken", processingActivityId = ProcessingActivities.UITVRAAG_LEZEN)
    override fun zoekenBerichten(xOntvanger: String, q: String, map: String?): BerichtenLijst {
        registreerLdvSubject(xOntvanger)
        return lijstService.zoek(xOntvanger, q, map)
    }

    @Logboek(name = "uitvraag-bericht", processingActivityId = ProcessingActivities.UITVRAAG_LEZEN)
    override fun getBerichtById(berichtId: UUID, xOntvanger: String): Bericht {
        registreerLdvSubject(xOntvanger)
        return ophaalService.haalBericht(xOntvanger, berichtId)
    }

    @Logboek(name = "uitvraag-bijlage", processingActivityId = ProcessingActivities.UITVRAAG_LEZEN)
    override fun getBijlage(berichtId: UUID, bijlageId: UUID, xOntvanger: String): ByteArray {
        registreerLdvSubject(xOntvanger)
        val (mimeType, bytes) = ophaalService.haalBijlage(xOntvanger, berichtId, bijlageId)
        request.setProperty(BIJLAGE_MIME_TYPE_PROPERTY, mimeType)
        return bytes
    }

    @Logboek(name = "uitvraag-patch", processingActivityId = ProcessingActivities.UITVRAAG_BEHEER)
    override fun updateBericht(berichtId: UUID, xOntvanger: String, berichtPatch: BerichtPatch): Bericht {
        registreerLdvSubject(xOntvanger)
        return beheerService.patch(xOntvanger, berichtId, berichtPatch)
    }

    @Logboek(name = "uitvraag-verwijder", processingActivityId = ProcessingActivities.UITVRAAG_BEHEER)
    override fun verwijderBericht(berichtId: UUID, xOntvanger: String) {
        registreerLdvSubject(xOntvanger)
        beheerService.verwijder(xOntvanger, berichtId)
    }

    /**
     * `ophalenBerichten` retourneert SSE; jaxrs-spec genereert hier `Object`,
     * dat we niet kunnen gebruiken voor streaming. We laten de gegenereerde
     * methode bestaan als delegatie naar de streaming-route (geen body — de
     * separate `SsePassthroughResource` in deze module handelt het pad af).
     * Quarkus REST routeert dezelfde URL via die @Path-class.
     */
    override fun ophalenBerichten(xOntvanger: String): Any =
        throw UnsupportedOperationException(
            "SSE-pad wordt afgehandeld door SsePassthroughResource — deze override is niet bedoeld om aangeroepen te worden",
        )

    private fun registreerLdvSubject(xOntvanger: String) {
        val delen = xOntvanger.split(':', limit = 2)
        if (delen.size == 2) {
            logboekContext.dataSubjectId = delen[1]
            logboekContext.dataSubjectType = delen[0]
        }
    }
}
```

> **Waarom geen `Identificatienummer.fromHeader`:** dat type leeft nu nog in `services/berichtenmagazijn/...opslag/`. Voor deze service is doorgeven van de raw string voldoende (sessiecache en magazijn valideren zelf via hun eigen Bean Validation op `X-Ontvanger`). Verplaats `Identificatienummer` naar fbs-common is werk voor een latere PR; sluiten hier aan op de pragmatische split.

- [ ] **Step 3: SsePassthroughResource voor `_ophalen`**

Maak `…/uitvraag/SsePassthroughResource.kt`:

```kotlin
package nl.rijksoverheid.moz.fbs.berichtenuitvraag.uitvraag

import io.smallrye.mutiny.Multi
import jakarta.enterprise.context.ApplicationScoped
import jakarta.ws.rs.GET
import jakarta.ws.rs.HeaderParam
import jakarta.ws.rs.Path
import jakarta.ws.rs.Produces
import jakarta.ws.rs.core.MediaType
import nl.mijnoverheidzakelijk.ldv.logboekdataverwerking.Logboek
import nl.mijnoverheidzakelijk.ldv.logboekdataverwerking.LogboekContext
import nl.rijksoverheid.moz.fbs.berichtenuitvraag.ApiInfo
import nl.rijksoverheid.moz.fbs.berichtenuitvraag.ProcessingActivities
import org.eclipse.microprofile.rest.client.inject.RestClient
import org.jboss.logging.Logger

/**
 * SSE-passthrough voor `GET /berichten/_ophalen`. De jaxrs-spec generator
 * ondersteunt geen `Multi<>` return-types; daarom is dit endpoint expliciet
 * hier gedefinieerd. Triggert in de sessiecache het ophalen van alle mappen
 * en pijpt elke event door naar de client.
 */
@Path(ApiInfo.BASE_PATH + "/berichten/_ophalen")
@ApplicationScoped
class SsePassthroughResource(
    @RestClient private val streamingClient: SessiecacheSseClient,
    private val logboekContext: LogboekContext,
) {

    @GET
    @Produces(MediaType.SERVER_SENT_EVENTS)
    @Logboek(name = "uitvraag-ophalen-sse", processingActivityId = ProcessingActivities.UITVRAAG_LEZEN)
    fun ophalen(@HeaderParam("X-Ontvanger") xOntvanger: String): Multi<String> {
        registreerLdvSubject(xOntvanger)
        return streamingClient.ophalen(xOntvanger).onFailure().invoke { e ->
            log.warnf(e, "SSE-passthrough faalde tijdens streaming")
        }
    }

    private fun registreerLdvSubject(xOntvanger: String) {
        val delen = xOntvanger.split(':', limit = 2)
        if (delen.size == 2) {
            logboekContext.dataSubjectId = delen[1]
            logboekContext.dataSubjectType = delen[0]
        }
    }

    private companion object {
        private val log: Logger = Logger.getLogger(SsePassthroughResource::class.java)
    }
}
```

Maak `…/uitvraag/SessiecacheSseClient.kt`:

```kotlin
package nl.rijksoverheid.moz.fbs.berichtenuitvraag.uitvraag

import io.smallrye.mutiny.Multi
import jakarta.ws.rs.GET
import jakarta.ws.rs.HeaderParam
import jakarta.ws.rs.Path
import jakarta.ws.rs.Produces
import jakarta.ws.rs.core.MediaType
import org.eclipse.microprofile.rest.client.inject.RegisterRestClient

/**
 * SSE-stream-client naar de sessiecache. Aparte interface t.o.v.
 * [SessiecacheClient] zodat de Quarkus REST-client de juiste streaming-engine
 * kiest voor `Multi<String>`. URL-property hergebruikt via dezelfde
 * config-key — beide clients praten met dezelfde service.
 */
@RegisterRestClient(configKey = "sessiecache-sse")
@Path("/api/v1/berichten/_ophalen")
interface SessiecacheSseClient {
    @GET
    @Produces(MediaType.SERVER_SENT_EVENTS)
    fun ophalen(@HeaderParam("X-Ontvanger") xOntvanger: String): Multi<String>
}
```

Voeg toe aan `application.properties`:
```properties
quarkus.rest-client.sessiecache-sse.url=${SESSIECACHE_URL:http://localhost:8080}
```

- [ ] **Step 4: Compileer**

Run: `./mvnw clean compile -pl services/berichtenuitvraag -am`
Expected: BUILD SUCCESS.

- [ ] **Step 5: Run alle unit-tests die we tot nu toe hebben**

Run: `./mvnw test -pl services/berichtenuitvraag`
Expected: alle 3 testklassen (`UitvraagDtoMapperTest`, `BerichtBeheerServiceTest`, `BerichtenlijstServiceTest`, `BerichtOphaalServiceTest`) groen.

- [ ] **Step 6: Commit**

```bash
git add services/berichtenuitvraag/src/main/kotlin/ \
        services/berichtenuitvraag/src/main/resources/application.properties
git commit -m "feat(uitvraag): REST-resource + SSE-passthrough (#413)

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>"
```

---

## Task 10: OpenApiContractTest (`@QuarkusTest` + swagger-request-validator)

**Files:**
- Create: `…/uitvraag/OpenApiContractIT.kt` (in test-src)
- Create: WireMock-stubs under `src/test/resources/wiremock/`

`@QuarkusTest` start de service voor real. We zetten WireMock-instances op voor sessiecache + magazijn en gebruiken `swagger-request-validator-restassured` om responses tegen onze eigen spec te valideren.

- [ ] **Step 1: WireMock-test-profile**

Maak `…/uitvraag/WireMockTestProfile.kt`:

```kotlin
package nl.rijksoverheid.moz.fbs.berichtenuitvraag.uitvraag

import io.quarkus.test.junit.QuarkusTestProfile
import org.wiremock.integrations.testcontainers.WireMockContainer

class WireMockTestProfile : QuarkusTestProfile {
    override fun getConfigOverrides(): Map<String, String> = mapOf(
        "quarkus.rest-client.\"nl.rijksoverheid.moz.fbs.berichtenuitvraag.uitvraag.SessiecacheClient\".url" to System.getProperty("wm.sessiecache.url"),
        "quarkus.rest-client.\"nl.rijksoverheid.moz.fbs.berichtenuitvraag.uitvraag.MagazijnClient\".url" to System.getProperty("wm.magazijn.url"),
        "quarkus.rest-client.sessiecache-sse.url" to System.getProperty("wm.sessiecache.url"),
    )
}
```

> **Waarom system-properties:** WireMock-poorten zijn dynamisch (per test-start). De `WireMockResource` (volgende stap) lift ze in System-properties zodat de profile ze kan lezen voordat Quarkus de configuratie bevriest. Dit volgt het patroon uit `services/berichtensessiecache/src/test/.../WireMockTestProfile.kt` — kijk daar voor exacte mechanica als je twijfelt.

- [ ] **Step 2: WireMockResource (`QuarkusTestResourceLifecycleManager`)**

Hergebruik het patroon uit sessiecache. Lees eerst:
`services/berichtensessiecache/src/test/kotlin/nl/rijksoverheid/moz/fbs/berichtensessiecache/berichten/WireMockResource.kt`

Maak een identieke `…/uitvraag/WireMockResource.kt` met 2 WireMock-instances (één voor sessiecache, één voor magazijn) die `wm.sessiecache.url` en `wm.magazijn.url` als System-properties exposen.

- [ ] **Step 3: OpenApiContractIT — happy-path lijst-endpoint**

Maak `…/uitvraag/OpenApiContractIT.kt`:

```kotlin
package nl.rijksoverheid.moz.fbs.berichtenuitvraag.uitvraag

import com.atlassian.oai.validator.restassured.OpenApiValidationFilter
import com.github.tomakehurst.wiremock.client.WireMock.aResponse
import com.github.tomakehurst.wiremock.client.WireMock.get
import com.github.tomakehurst.wiremock.client.WireMock.stubFor
import com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo
import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.junit.TestProfile
import io.restassured.RestAssured.given
import org.hamcrest.Matchers.equalTo
import org.junit.jupiter.api.Test

@QuarkusTest
@TestProfile(WireMockTestProfile::class)
@QuarkusTestResource(WireMockResource::class)
class OpenApiContractIT {

    private val validator = OpenApiValidationFilter("openapi/berichtenuitvraag-api.yaml")

    @Test
    fun `GET berichten retourneert valide BerichtenLijst`() {
        stubFor(
            get(urlPathEqualTo("/api/v1/berichten"))
                .willReturn(
                    aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("""{"berichten":[]}"""),
                ),
        )

        given()
            .filter(validator)
            .header("X-Ontvanger", "BSN:123456782")
            .`when`()
            .get("/api/v1/berichten")
            .then()
            .statusCode(200)
            .body("berichten", equalTo(emptyList<Any>()))
    }
}
```

- [ ] **Step 4: Run en zorg dat hij groen is**

Run: `./mvnw clean test -pl services/berichtenuitvraag -Dtest=OpenApiContractIT`
Expected: PASS — `OpenApiValidationFilter` controleert request én response tegen de spec; validation-failures verschijnen als test-failure met details.

- [ ] **Step 5: Voeg contract-tests toe voor de overige endpoints**

Breid `OpenApiContractIT` uit met test-methodes voor:
- `GET /berichten/_zoeken` (200 met lege lijst)
- `GET /berichten/{id}` (200 met minimaal Bericht)
- `PATCH /berichten/{id}` (200, magazijn én sessiecache gestubd)
- `DELETE /berichten/{id}` (204, magazijn én sessiecache gestubd)
- `GET /berichten/{id}/bijlagen/{id}` (200 met `application/pdf`, body `JVBERi0=` base64 → 5 bytes)

Voor elk: WireMock-stubs voor zowel sessiecache als magazijn waar van toepassing, en valideer met `OpenApiValidationFilter`.

- [ ] **Step 6: Run de volledige test-suite**

Run: `./mvnw clean test -pl services/berichtenuitvraag`
Expected: alle tests groen.

- [ ] **Step 7: Commit**

```bash
git add services/berichtenuitvraag/src/test/
git commit -m "test(uitvraag): OpenAPI-contract-tests via WireMock (#413)

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>"
```

---

## Task 11: Dual-write fail-mode integratietests

**Files:**
- Modify: `…/uitvraag/OpenApiContractIT.kt` (of nieuwe `DualWriteFaultIT.kt`)

- [ ] **Step 1: Schrijf integratietests voor de drie fail-modes (magazijn-fail, cache-fail, beide fail)**

Maak `…/uitvraag/DualWriteFaultIT.kt`:

```kotlin
package nl.rijksoverheid.moz.fbs.berichtenuitvraag.uitvraag

import com.github.tomakehurst.wiremock.client.WireMock.aResponse
import com.github.tomakehurst.wiremock.client.WireMock.delete
import com.github.tomakehurst.wiremock.client.WireMock.patch
import com.github.tomakehurst.wiremock.client.WireMock.stubFor
import com.github.tomakehurst.wiremock.client.WireMock.urlPathMatching
import com.github.tomakehurst.wiremock.client.WireMock.verify
import com.github.tomakehurst.wiremock.client.WireMock.deleteRequestedFor
import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.junit.TestProfile
import io.restassured.RestAssured.given
import org.junit.jupiter.api.Test
import java.util.UUID

@QuarkusTest
@TestProfile(WireMockTestProfile::class)
@QuarkusTestResource(WireMockResource::class)
class DualWriteFaultIT {

    @Test
    fun `PATCH magazijn-faal geeft 5xx en raakt cache niet`() {
        val id = UUID.randomUUID()
        stubFor(
            patch(urlPathMatching("/api/v1/berichten/$id"))
                // Magazijn-instance (verwijs naar de juiste WireMock-port via base-url)
                .willReturn(aResponse().withStatus(503)),
        )

        given()
            .header("X-Ontvanger", "BSN:123456782")
            .header("Content-Type", "application/merge-patch+json")
            .body("""{"status":"gelezen"}""")
            .`when`()
            .patch("/api/v1/berichten/$id")
            .then()
            .statusCode(503)

        // Cache mag niet aangeroepen zijn — verifieer via WireMockResource.cacheServer
        // (specifieke API in WireMockResource bepalen op basis van het bestaande patroon).
    }

    @Test
    fun `PATCH cache-faal na magazijn-OK geeft 502 en triggert invalidate-DELETE`() {
        val id = UUID.randomUUID()
        // magazijn-OK stub
        stubFor(
            patch(urlPathMatching("/api/v1/berichten/$id"))
                .willReturn(aResponse().withStatus(200).withHeader("Content-Type", "application/json").withBody("""{"id":"$id"}""")),
        )
        // cache-PATCH faalt
        // (Zet via WireMockResource.cacheServer een failed-stub op exact dezelfde URL.
        //  De Sessiecache-WireMock-instance heeft een aparte port en wordt gestubd
        //  via WireMockResource.stubSessiecache(...) — patroon overnemen van sessiecache-tests.)

        given()
            .header("X-Ontvanger", "BSN:123456782")
            .header("Content-Type", "application/merge-patch+json")
            .body("""{"status":"gelezen"}""")
            .`when`()
            .patch("/api/v1/berichten/$id")
            .then()
            .statusCode(502)

        // Verifieer dat compensatie-DELETE op cache heeft plaatsgevonden:
        WireMockResource.sessiecacheServer().verify(
            deleteRequestedFor(urlPathMatching("/api/v1/berichten/$id"))
        )
    }

    @Test
    fun `DELETE cache-faal geeft 502 maar magazijn-DELETE is wel uitgevoerd`() {
        val id = UUID.randomUUID()
        // magazijn-OK
        // cache-DELETE faalt eerste keer, succesvol bij compensatie

        given()
            .header("X-Ontvanger", "BSN:123456782")
            .`when`()
            .delete("/api/v1/berichten/$id")
            .then()
            .statusCode(502)

        WireMockResource.magazijnServer().verify(
            deleteRequestedFor(urlPathMatching("/api/v1/berichten/$id"))
        )
    }
}
```

> **Let op — pseudocode voor WireMockResource-API.** De exacte methodes (`sessiecacheServer()`, `magazijnServer()`, `stubSessiecache(...)`) hangen af van het WireMockResource-patroon dat in Task 10 stap 2 is gebouwd. Lees daarvoor het sessiecache-pendant en pas waar nodig aan.

- [ ] **Step 2: Run**

Run: `./mvnw clean test -pl services/berichtenuitvraag -Dtest=DualWriteFaultIT`
Expected: 3 tests pass.

- [ ] **Step 3: Commit**

```bash
git add services/berichtenuitvraag/src/test/kotlin/nl/rijksoverheid/moz/fbs/berichtenuitvraag/uitvraag/DualWriteFaultIT.kt
git commit -m "test(uitvraag): integratietests dual-write fail-modes (#413)

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>"
```

---

## Task 12: compose.yaml + Bruno-collectie + spectral-CI

**Files:**
- Modify: `compose.yaml`
- Create: `bruno/berichtenuitvraag/bruno.json`
- Create: `bruno/berichtenuitvraag/environments/lokaal.bru`
- Create: `bruno/berichtenuitvraag/{lijst,zoek,bericht,bijlage,patch,delete,ophalen-sse}.bru`
- Modify: `.github/workflows/...` (spectral-lint van de nieuwe spec)

- [ ] **Step 1: compose.yaml**

Voeg toe in `compose.yaml`:

```yaml
  berichtenuitvraag:
    image: quay.io/quarkus/ubi-quarkus-mandrel-builder-image:jdk-21
    working_dir: /app
    volumes: ["./services/berichtenuitvraag:/app"]
    command: ./mvnw quarkus:dev
    ports: ["8082:8080"]
    environment:
      SESSIECACHE_URL: http://berichtensessiecache:8080
      MAGAZIJN_URL: http://berichtenmagazijn:8080
    depends_on: [berichtensessiecache, berichtenmagazijn]
```

> **Verifieer compose-stijl** door bestaande service-entries in `compose.yaml` te bekijken — kopieer dezelfde build/image/run-strategie.

- [ ] **Step 2: Bruno-collectie**

Hergebruik het patroon uit `bruno/berichtenmagazijn/`. Maak voor elke endpoint één `.bru`-bestand met de juiste URL, headers, body. Verifieer dat de collection-init werkt:

```bash
ls bruno/berichtenmagazijn/   # check structuur
```

Kopieer en pas paths/payloads aan.

- [ ] **Step 3: Spectral in CI**

In `.github/workflows/`-een-workflow zoeken die al sessiecache/magazijn-spec lint (grep `spectral`). Voeg de uitvraag-spec toe aan dezelfde stap of duplicate de stap.

- [ ] **Step 4: Run lokaal compose + manueel verifiëren**

```bash
docker compose up -d redis wiremock clickhouse berichtenmagazijn berichtensessiecache berichtenuitvraag
curl -s -H "X-Ontvanger: BSN:123456782" http://localhost:8082/api/v1/berichten | jq .
docker compose logs berichtenuitvraag | tail -50
```

Expected: 200-respons met `BerichtenLijst`-vorm (kan leeg zijn als de magazijnen geen data hebben). Geen stacktraces in de log.

- [ ] **Step 5: Commit**

```bash
git add compose.yaml bruno/berichtenuitvraag/ .github/workflows/
git commit -m "chore(uitvraag): compose + Bruno + Spectral-CI (#413)

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>"
```

---

## Task 13: Volledige verify + coverage-gate

**Files:** —

- [ ] **Step 1: Volledige clean verify**

Run: `./mvnw clean verify -pl services/berichtenuitvraag -am`
Expected: BUILD SUCCESS, JaCoCo-rapport gegenereerd, coverage ≥90% lijn (genegeerd: `api.**`).

- [ ] **Step 2: Bij <90% coverage — uitbreiden, niet verlagen**

Open `services/berichtenuitvraag/target/site/jacoco/index.html` en identificeer de packages onder 90%. Voeg gerichte integratietests toe (geen unit-tests — die tellen niet voor `quarkus-jacoco`). Commit per testtoevoeging.

- [ ] **Step 3: Status omhoog in design-doc**

Open `docs/plans/2026-05-26-berichtenuitvraag-design.md` en wijzig `**Status:** Concept` → `**Status:** Uitgevoerd`. Open dit plan `2026-05-26-berichtenuitvraag-plan.md` en wijzig idem.

- [ ] **Step 4: Commit**

```bash
git add docs/plans/2026-05-26-berichtenuitvraag-design.md docs/plans/2026-05-26-berichtenuitvraag-plan.md
git commit -m "docs(uitvraag): markeer ontwerp+plan als Uitgevoerd (#413)

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>"
```

- [ ] **Step 5: Push en open draft-PR**

```bash
git push -u origin feature/issue-413-berichtenuitvraag
gh pr create --draft --title "feat(uitvraag): Berichten Uitvraag Service (#413)" --body "$(cat <<'EOF'
## Summary
- Nieuwe Quarkus-module `services/berichtenuitvraag` als frontend-API voor het portaal
- Dual-backend: sessiecache (lijst/zoek/ophalen/SSE) + magazijn (bijlagen + persistente beheer-mutaties)
- Magazijn-first dual-write met best-effort cache-invalidate (502 bij cache-faal)

## Closes
#413

## Test plan
- [ ] `./mvnw clean verify -pl services/berichtenuitvraag -am` lokaal groen
- [ ] Spectral-lint van de nieuwe spec slaagt in CI
- [ ] Coverage ≥90% (zie JaCoCo-rapport)
- [ ] `docker compose up` + `curl /api/v1/berichten` levert 200

Conform geheugen-feedback: PR opent als draft zonder reviewer; ready-for-review aanvragen na lokale verificatie.

🤖 Generated with [Claude Code](https://claude.com/claude-code)
EOF
)"
```

---

## Self-review — spec-coverage

| Spec-vereiste | Taak |
|---|---|
| Maven-module + parent POM | Task 1 |
| Geen aanmeld-package-skeleton (bewust weggelaten) | n.v.t. — niet geïmplementeerd, conform afspraak |
| OpenAPI-spec met 6 endpoints + `_ophalen` | Task 2 + 3 |
| Spectral-validatie | Task 2/3 + Task 12 (CI) |
| PATCH met `map`-veld in body | Task 3 (`BerichtPatch`-schema) |
| REST-client → sessiecache | Task 4 + 9 (SSE-client) |
| REST-client → magazijn (TODO FSC) | Task 4 |
| `BerichtenlijstService`, `BerichtOphaalService`, `BerichtBeheerService` | Task 6, 7, 8 |
| Problem JSON, API-Version, security headers, LDV | Task 4 (config) + Task 9 (`@Logboek`) |
| Unit + integratietests | Task 5–11 |
| compose.yaml | Task 12 |
| Dual-write magazijn-first + cache-invalidate-compensatie | Task 6 (impl) + Task 11 (IT) |
| Stream-passthrough bijlagen met Content-Type override | Task 8 |
| `X-Ontvanger`-header doorgeven (geen JWT in deze iteratie) | Task 9 |
| status-enum ↔ gelezen-boolean mapping | Task 5 |

Geen onbehandelde spec-vereisten gevonden.
