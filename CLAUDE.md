# CLAUDE.md - Projectcontext voor AI-assistentie

## Project

FBS Berichtenbox - Proof of Concept voor het Federatief Berichtenstelsel (FBS).
Monorepo met Maven, Quarkus en Kotlin. Architectuurdocumentatie in Structurizr DSL (C4 model).

## Taal

Communicatie in het Nederlands. Code en technische termen in het Engels waar gangbaar.

## Technische stack

- **Build:** Maven monorepo (parent POM + modules), Maven wrapper (`./mvnw`)
- **Runtime:** Quarkus 3.x, Java 21
- **Taal:** Kotlin (JVM 21, all-open plugin voor CDI/JAX-RS)
- **API:** OpenAPI-first (`jaxrs-spec` generator, `interfaceOnly=true`), gegenereerde Java interfaces die Kotlin resources implementeren
- **REST:** RESTEasy Reactive + Jackson
- **Caching:** Redis (60s TTL) via `BerichtenCache` interface
- **Validatie:** Hibernate Validator (Bean Validation via gegenereerde interface-annotaties)
- **Test:** JUnit 5 + REST-assured + QuarkusTest

## Architectuurprincipes

- **OpenAPI-first:** De OpenAPI spec (`berichtensessiecache-api.yaml`) is de bron van waarheid. Interfaces worden gegenereerd; Kotlin resources implementeren deze.
- **Functionele packages:** `berichten/`, `magazijn/`, `notificatie/` — niet technisch (`controller/`, `service/`).
- **NL API Design Rules:** `/api/v1` prefix, camelCase JSON, `application/problem+json` fouten (RFC 9457), `API-Version` header, HAL `_links`.
- **Cache alleen succesvolle responses:** Error handling in de resource, niet in de service, zodat de Redis-cache geen foutresultaten opslaat.
- **ExceptionMappers:** `ProblemExceptionMapper` (WebApplicationException) en `ConstraintViolationExceptionMapper` voor consistente Problem JSON responses.

## Conventies

- **GroupId:** `nl.rijksoverheid.moz`
- **Packages:** `nl.rijksoverheid.moz.berichtensessiecache.*`
- **Monorepo structuur:** `services/<service-naam>/` als Maven module
- **Gegenereerde code:** `target/generated-sources/openapi/` — nooit handmatig aanpassen
- **Tests:** Mock externe clients via `@Mock @ApplicationScoped` CDI beans in test-package

## Build & test commando's

```bash
./mvnw compile -pl services/berichtensessiecache          # Compileren
./mvnw test -pl services/berichtensessiecache              # Tests draaien
./mvnw quarkus:dev -pl services/berichtensessiecache       # Dev mode
```

## Belangrijke bestanden

| Pad | Beschrijving |
|-----|-------------|
| `pom.xml` | Parent POM (Quarkus BOM, Kotlin plugin config) |
| `services/berichtensessiecache/pom.xml` | Module POM (OpenAPI generator, dependencies) |
| `services/berichtensessiecache/src/main/resources/openapi/berichtensessiecache-api.yaml` | OpenAPI spec (bron van waarheid) |
| `docs/architecture/` | C4 model (Structurizr DSL) |

## Plannen

Implementatieplannen worden opgeslagen in `docs/plans/` met oplopend nummer:
- Formaat: `YYYY-MM-DD-korte-beschrijving.md` (bijv. `2026-03-11-monorepo-berichtensessiecache.md`)
- Bevat: context, structuur, stappen, ontwerpkeuzes, verificatie
- Voeg `**Status:**` toe bovenaan (Concept / Uitgevoerd / Verworpen)
- Sla elk plan op bij het afronden, zodat beslissingen traceerbaar blijven

## Review-aanpak

Bij code reviews classificeren we bevindingen op ernst (Hoog/Medium/Laag) met een samenvattingstabel. Hoge punten worden direct aangepakt, medium in overleg, laag later.
