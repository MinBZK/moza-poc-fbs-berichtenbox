**Status:** Concept
**Datum:** 2026-03-11

# Compliancebeoordeling PR: feature/multi-magazijn-aggregatie

Beoordeling van de PR (8 commits, 36 bestanden) tegen 20 Nederlandse overheidsstandaarden.
Dit is een Proof of Concept voor het Federatief Berichtenstelsel (FBS).

## Samenvatting

| Categorie | Ernst | Standaarden | Bevinding |
|-----------|-------|-------------|-----------|
| Authenticatie | ⚪ Buiten scope | ls-iam, don-security | Bewust buiten POC-scope; geen OAuth/JWT |
| Digikoppeling/FSC | ⚪ Buiten scope | ls-dk, ls-fsc | Bewust buiten POC-scope; plain HTTP naar magazijnen |
| Logboek Dataverwerkingen | ✅ Geïmplementeerd | ls-logboek, don-data | Via moza-logboekdataverwerking wrapper |
| CloudEvents | 🟡 Medium | ls-notif | EventForwarder is TODO |
| OpenAPI locatie | 🟡 Medium | ls-api, don-apis | `/q/openapi` i.p.v. vereist `/openapi.json` |
| Zoeken-pad | 🟡 Medium | ls-api, don-apis | `/berichten/zoeken` → ADR vereist `/berichten/_zoeken` |
| Open source bestanden | 🟡 Medium | don-open-source | README, CONTRIBUTING, publiccode.yml ontbreken |
| Security headers | 🟢 Laag | inet-web, don-security | Ontbreken op app-niveau (kan op infra/gateway) |
| Contact in OpenAPI spec | 🟢 Laag | ls-api | Controleren of `info.contact` aanwezig is |

## Niet van toepassing (POC context)

| Skill | Reden |
|-------|-------|
| ls-bomos | Geen standaardenorganisatie, maar een applicatie |
| ls-pub | Geen ReSpec-document |
| don-programmeertalen | Kotlin/JVM is gangbaar voor JVM-projecten |
| don-infra | Haven/Kubernetes nog niet geconfigureerd (POC) |
| don-front-end | Puur backend API |
| inet-web | Geen publieke website |
| inet-mail | Geen mailservice |
| inet-toolbox | Geen platformconfiguratie |
| inet-api | Geen internet.nl scanning tool |

## Gedetailleerde bevindingen

### ⚪ 1. Authenticatie en autorisatie — buiten POC-scope (ls-iam, don-security)

**Besluit:** Bewust buiten POC-scope geplaatst. In productie vereist:
- OAuth 2.0 NL profiel (v1.0/v1.1) met `client_credentials` flow
- JWT Bearer tokens via `Authorization` header
- `ontvanger` uit JWT-claim i.p.v. query parameter

---

### ⚪ 2. Digikoppeling/FSC — buiten POC-scope (ls-dk, ls-fsc)

**Besluit:** Bewust buiten POC-scope geplaatst. In productie vereist:
- FSC (verplicht bij Digikoppeling REST-API v3.0.1+)
- mTLS met PKIoverheid-certificaten
- `Fsc-Authorization` header met certificate-bound JWT

---

### ✅ 3. Logboek Dataverwerkingen — geïmplementeerd (ls-logboek, don-data)

**Implementatie via** [`moza-logboekdataverwerking`](https://github.com/MinBZK/moza-logboekdataverwerking):
- `@Logboek` annotatie op alle resource-endpoints met `processingActivityId`
- `LogboekContext` injectie met `dataSubjectId` en `dataSubjectType`
- ClickHouse als backing store (via compose.yaml)
- Configuratie in `application.properties` (`logboekdataverwerking.enabled=true`)
- Tests draaien met `logboekdataverwerking.enabled=false`

---

### 🟡 4. CloudEvents forwarding is TODO (ls-notif)

**Bestand:** `services/berichtenlijst/src/main/kotlin/nl/rijksoverheid/moz/berichtenlijst/notificatie/EventForwarder.kt:12`

```kotlin
// TODO: Implementeer CloudEvents forwarding naar Notificatie Service
```

**Vereiste per NL GOV CloudEvents profiel v1.1:**
- `source`: `urn:nld:oin:<OIN>:systeem:berichtenlijst`
- `type`: bijv. `nl.fbs.bericht-ontvangen`
- `specversion`: `"1.0"`
- POST naar Notificatie Service met `Content-Type: application/cloudevents+json`

---

### 🟡 5. OpenAPI spec niet op vereist pad (ls-api, don-apis)

**Huidig gedrag:** Quarkus serveert OpenAPI op `/q/openapi`.

**ADR vereiste:** Spec MOET beschikbaar zijn op `/openapi.json` (verplicht).

**Fix in `application.properties`:**

```properties
quarkus.smallrye-openapi.path=/openapi.json
```

---

### 🟡 6. Zoek-endpoint pad niet ADR-conform (ls-api, don-apis)

**Huidig:** `GET /api/v1/berichten/zoeken`

**ADR-regel:** Sub-resources die acties uitdrukken krijgen een `_` prefix als laatste padsegment.

**Correct:** `GET /api/v1/berichten/_zoeken`

Aanpassen in `berichtenlijst-api.yaml`; de gegenereerde interface werkt automatisch mee.

---

### 🟡 7. Open source bestanden ontbreken (don-open-source)

**Ontbrekend in repo root:**
- `README.md` — projectbeschrijving, installatie-instructies
- `CONTRIBUTING.md` — bijdragerichtlijnen
- `CODE_OF_CONDUCT.md`
- `publiccode.yml` — verplicht voor overheidsprojecten
- `LICENSE` — licentiebestand (bij voorkeur EUPL-1.2)
- `SECURITY.md` / `security.txt`

---

### 🟢 8. Security headers (inet-web, don-security)

**Huidig:** Niet geconfigureerd op applicatieniveau.

**ADR/inet-web vereiste:**
- `Strict-Transport-Security: max-age=31536000`
- `X-Frame-Options: DENY`
- `X-Content-Type-Options: nosniff`
- `Content-Security-Policy: frame-ancestors 'none'`
- `Cache-Control: no-store`

**Aanpak:** Toevoegen aan de bestaande `ApiVersionFilter` (is al een `ContainerResponseFilter`).
Alternatief: via een API Gateway/reverse proxy buiten de applicatie.

---

### 🟢 9. Contact info in OpenAPI spec (ls-api)

**Te controleren in `berichtenlijst-api.yaml`:**

```yaml
info:
  contact:
    name: FBS Berichtenlijst
    email: support@example.nl
```

ADR vereist dat `info.contact` aanwezig is met `name` en `email`.

---

## Wat wél goed is ✅

- `/api/v1` prefix conform ADR
- `API-Version` response header via `ApiVersionFilter`
- `application/problem+json` (RFC 9457) via beide ExceptionMappers
- HAL `_links` in gepagineerde responses
- Semantic versioning `0.1.0` in `info.version`
- Kebab-case paden, camelCase query-parameters
- Geen trailing slashes
- Functionele package-structuur conform NeRDS
- OpenAPI-first met gegenereerde interfaces (`interfaceOnly=true`)
- Geautomatiseerde tests (JUnit 5 + REST-assured)
- Redis cache slaat alleen succesvolle responses op (correct)
- Onderscheid 404 vs 502 bij magazijn-fouten

## Aanbevolen actieplan

| Prioriteit | Actie | Effort |
|-----------|-------|--------|
| ✅ POC-scope vastgelegd | Auth en DK/FSC bewust buiten POC-scope | Gedaan |
| ✅ Logboek | Via moza-logboekdataverwerking wrapper | Gedaan |
| 🟡 CloudEvents | EventForwarder implementeren | Medium |
| 🟡 OpenAPI pad | `quarkus.smallrye-openapi.path=/openapi.json` | Klein |
| 🟡 Zoeken pad | `/berichten/zoeken` → `/berichten/_zoeken` | Klein |
| 🟡 Open source | README, LICENSE, publiccode.yml toevoegen | Medium |
| 🟢 Security headers | Uitbreiden ApiVersionFilter | Klein |
| 🟢 Contact info | Toevoegen aan OpenAPI spec | Klein |