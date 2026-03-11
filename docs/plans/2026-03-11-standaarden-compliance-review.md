**Status:** Uitgevoerd
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
| CloudEvents | ✅ Geïmplementeerd | ls-notif | EventForwarder met NL GOV CloudEvents profiel v1.1 |
| OpenAPI locatie | ✅ Geïmplementeerd | ls-api, don-apis | Geconfigureerd op `/openapi.json` |
| Zoeken-pad | ✅ Geïmplementeerd | ls-api, don-apis | Aangepast naar `/berichten/_zoeken` |
| Open source bestanden | ✅ Geïmplementeerd | don-open-source | README, CONTRIBUTING, CODE_OF_CONDUCT, SECURITY, LICENSE, publiccode.yml |
| Security headers | ✅ Geïmplementeerd | inet-web, don-security | Via ApiVersionFilter op app-niveau |
| Contact in OpenAPI spec | ✅ Geïmplementeerd | ls-api | `info.contact` met name, email en url |

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

### ✅ 4. CloudEvents forwarding — geïmplementeerd (ls-notif)

**Implementatie in** `EventForwarder.kt`:
- Structured content mode (`application/cloudevents+json`)
- `source`: `urn:nld:oin:00000000000000000000:systeem:berichtenlijst` (OIN is placeholder)
- `type`: `nl.fbs.bericht-ontvangen`
- `specversion`: `"1.0"`
- POST naar Notificatie Service URL uit configuratie
- Error handling met logging bij fouten

---

### ✅ 5. OpenAPI spec op vereist pad — geïmplementeerd (ls-api, don-apis)

Geconfigureerd in `application.properties`: `quarkus.smallrye-openapi.path=/openapi.json`

---

### ✅ 6. Zoek-endpoint pad ADR-conform — geïmplementeerd (ls-api, don-apis)

Aangepast in `berichtenlijst-api.yaml`: `/berichten/zoeken` → `/berichten/_zoeken`

---

### ✅ 7. Open source bestanden — geïmplementeerd (don-open-source)

Aanwezig in repo root:
- `README.md` — projectbeschrijving met installatie-instructies
- `CONTRIBUTING.md` — verwijst naar hoofdrepository
- `CODE_OF_CONDUCT.md` — verwijst naar hoofdrepository
- `SECURITY.md` — verwijst naar hoofdrepository
- `publiccode.yml` — compleet met NL en EN beschrijvingen
- `LICENSE` — EUPL-1.2
- `GOVERNANCE.md` — verwijst naar hoofdrepository
- `SUPPORT.md` — verwijst naar hoofdrepository

---

### ✅ 8. Security headers — geïmplementeerd (inet-web, don-security)

Toegevoegd aan `ApiVersionFilter` (ContainerResponseFilter):
- `Strict-Transport-Security: max-age=31536000`
- `X-Frame-Options: DENY`
- `X-Content-Type-Options: nosniff`
- `Content-Security-Policy: frame-ancestors 'none'`
- `Cache-Control: no-store`

---

### ✅ 9. Contact info in OpenAPI spec — geïmplementeerd (ls-api)

Toegevoegd aan `berichtenlijst-api.yaml`: `info.contact` met `name`, `email` en `url`.

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

| Prioriteit | Actie | Status |
|-----------|-------|--------|
| ⚪ Auth en DK/FSC | Bewust buiten POC-scope | Buiten scope |
| ✅ Logboek | Via moza-logboekdataverwerking wrapper | Gedaan |
| ✅ CloudEvents | EventForwarder met NL GOV profiel v1.1 | Gedaan |
| ✅ OpenAPI pad | `/openapi.json` | Gedaan |
| ✅ Zoeken pad | `/berichten/_zoeken` | Gedaan |
| ✅ Open source | README, CONTRIBUTING, CODE_OF_CONDUCT, SECURITY | Gedaan |
| ✅ Security headers | Via ApiVersionFilter | Gedaan |
| ✅ Contact info | `info.contact` met name, email, url | Gedaan |