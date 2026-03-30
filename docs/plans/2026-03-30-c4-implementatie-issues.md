**Status:** Concept

# GitHub Issues: C4-model implementeren

## Context

Het C4-model in `docs/architecture/workspace.dsl` beschrijft de volledige doelarchitectuur van het Federatief Berichtenstelsel (FBS). Momenteel is alleen de **Berichtensessiecache** geïmplementeerd (`services/berichtensessiecache/`). De overige containers en componenten uit het C4-model moeten nog gebouwd worden. Deze issues verdelen dat werk in 10 logische, onafhankelijk leverbare delen.

**Huidige staat:**
- :white_check_mark: Berichtensessiecache (aggregatie, Redis cache, SSE, multi-magazijn)
- :x: Berichtenmagazijn (Aanlever API, Ophaal- & Beheer API, Validatie, Publicatie Stream, Autorisatie, Dataopslag)
- :x: Berichten Uitvraag Service (user-facing API, Token Validatie, Beheer)
- :x: BSNk Transformatie / PseudoniemService
- :x: Aanmeld Service
- :x: MagazijnResolver met Profiel Service
- :x: Autorisatie (AuthZEN PEP/PDP)

---

## API-overzicht (afgeleid uit C4-model)

Alle REST-relaties uit `workspace.dsl` vertaald naar concrete API's:

### Externe API's (via FSC)

| API | Container | Consumers | Endpoints |
|-----|-----------|-----------|-----------|
| **Berichten Uitvraag API** | `uitvraagApi` | Interactielaag (JWT) | `GET /berichten`, `GET /berichten/_zoeken`, `GET /berichten/{id}`, `GET /berichten/{id}/bijlagen/{id}`, `PATCH /berichten/{id}`, `DELETE /berichten/{id}` |
| **Magazijn Ophaal- en Beheer API** | `magazijnOphaalBeheerApi` | Sessiecache, Uitvraag Service | `GET /berichten`, `GET /berichten/{id}`, `GET /berichten/{id}/bijlagen/{id}`, `PATCH /berichten/{id}`, `DELETE /berichten/{id}` |
| **Magazijn Aanlever API** | `magazijnAanleverApi` | Organisaties | `POST /berichten` |

### Interne API's (mTLS, binnen systeem)

| API | Container | Consumers | Endpoints |
|-----|-----------|-----------|-----------|
| **Berichtensessiecache API** | `sessiecacheApp` | Uitvraag Service, Aanmeld Service | `GET /berichten`, `GET /berichten/_zoeken`, `GET /berichten/{id}`, `PATCH /berichten/{id}`, `POST /berichten` |
| **Bericht Validatie API** | `berichtValidatie` | Bericht Opslag Service | `POST /berichten/_valideer` |
| **Publicatie Stream API** | `publicatieStream` | Bericht Opslag Service | `POST /publicaties` |
| **Autorisatie Evaluatie API** | `autorisatieService` | Ophaal- en Beheer API | `POST /autorisatiebeslissingen/_evalueer` |
| **Aanmeld Service webhook** | `aanmeldService` | Publicatie Stream | CloudEvents HTTP binding (`nl.rijksoverheid.fbs.bericht.gepubliceerd`) |

### Uitgaande REST-clients (via FSC)

| Client | In container | Naar | Doel |
|--------|-------------|------|------|
| `sessiecacheMagazijnClient` | Sessiecache | Magazijn Ophaal- en Beheer API | Berichten ophalen voor aggregatie |
| `uitvraagOphaalService` | Uitvraag Service | Magazijn Ophaal- en Beheer API | Bijlagen ophalen |
| `uitvraagBeheerService` | Uitvraag Service | Magazijn Ophaal- en Beheer API | Berichtstatus beheren |
| `magazijnResolver` | Sessiecache | Profiel Service | Dienstvoorkeuren ophalen |
| `validatieToestemming` | Bericht Validatie | Profiel Service | Toestemming controleren |
| `publicatieStream` | Publicatie Stream | Aanmeld Service, Notificatie Service | CloudEvents doorsturen |

---

## Issues

### Issue 1: Berichtenmagazijn Aanlever API — nieuwe service module

**Labels:** `enhancement`, `magazijn`

Maak een nieuwe Quarkus service module `services/berichtenmagazijn` met de Aanlever API (C4 container: `magazijnAanleverApi`). Hiermee kunnen organisaties berichten aanleveren aan het FBS.

Volgt dezelfde patronen als `services/berichtensessiecache`: OpenAPI-first, functionele packages, Problem JSON, security headers, LDV logging. Naamgeving in het Nederlands conform bestaande berichtensessiecache-spec en ADR.

**C4 componenten:** `magazijnAanleverResource`, `magazijnCircuitBreaker`, `magazijnOpslagService`

**Acceptatiecriteria:**
- [ ] Maven module `services/berichtenmagazijn` geregistreerd in parent POM
- [ ] OpenAPI spec voor Aanlever API (`POST /api/v1/berichten`) conform NL API Design Rules
- [ ] OpenAPI spec valideert zonder fouten tegen [ADR Spectral linter](https://static.developer.overheid.nl/adr/ruleset.yaml)
- [ ] Gedeelde OpenAPI-componenten (`Problem`, `Link`, `PaginationLinks`, `API-Version` header) hergebruiken uit berichtensessiecache-spec (via `$ref` naar gedeeld bestand of kopie per spec — keuze vastleggen)
- [ ] `AanleverResource.kt` implementeert gegenereerde interface
- [ ] `BerichtOpslagService.kt` met opslag-logica
- [ ] CircuitBreaker op schrijfoperaties (MicroProfile Fault Tolerance `@CircuitBreaker`)
- [ ] Dataopslag als interface (PoC: in-memory of embedded H2)
- [ ] Problem JSON ExceptionMappers, ApiVersionFilter, security headers
- [ ] LDV logging (`@Logboek`) op endpoints
- [ ] Unit tests en integratietests (happy + unhappy paths)
- [ ] `compose.yaml` bijgewerkt indien nodig

**Dependencies:** geen · **Complexiteit:** L

---

### Issue 2: Berichtenmagazijn Ophaal- en Beheer API

**Labels:** `enhancement`, `magazijn`

Voeg de Ophaal- en Beheer API toe aan `services/berichtenmagazijn` (C4 container: `magazijnOphaalBeheerApi`). Naamgeving in het Nederlands conform ADR.

Deze API heeft **twee externe consumers** (C4 relaties):
1. **Berichtensessiecache** (`sessiecacheMagazijnClient`): haalt berichtenlijsten op voor aggregatie (C4 regel 172)
2. **Berichten Uitvraag Service**: haalt bijlagen op (`uitvraagOphaalService`, C4 regel 158) en beheert berichtstatus (`uitvraagBeheerService`, C4 regel 159)

**Endpoints:**
- `GET /api/v1/berichten` — berichtenlijst ophalen (ontvanger via `X-Ontvanger` header — voorkomt PII in URLs/logs)
- `GET /api/v1/berichten/{berichtId}` — enkel bericht met inhoud
- `GET /api/v1/berichten/{berichtId}/bijlagen/{bijlageId}` — bijlage ophalen (consumer: uitvraagOphaalService)
- `PATCH /api/v1/berichten/{berichtId}` — berichtstatus bijwerken (gelezen, map) via JSON Merge Patch
- `DELETE /api/v1/berichten/{berichtId}` — bericht verwijderen

**Acceptatiecriteria:**
- [ ] OpenAPI spec met bovengenoemde endpoints
- [ ] OpenAPI spec valideert zonder fouten tegen [ADR Spectral linter](https://static.developer.overheid.nl/adr/ruleset.yaml)
- [ ] `ontvanger` als `X-Ontvanger` header (niet als query parameter) — conform ADR-regel "geen gevoelige informatie in URIs"
- [ ] Bijlagen-endpoint voor het ophalen van individuele bijlagen bij een bericht
- [ ] `OphaalBeheerResource.kt` implementeert gegenereerde interface
- [ ] **Breaking change:** `MagazijnClient` interface bijwerken: `ontvanger` van `@QueryParam` naar `@HeaderParam("X-Ontvanger")`; zoek-endpoint verwijderen (zoeken gebeurt lokaal in sessiecache via RediSearch, niet op het magazijn)
- [ ] Berichtstatus per gebruiker (gelezen/ongelezen, map) via `PATCH` met JSON Merge Patch body
- [ ] WireMock mappings bijgewerkt voor nieuwe en gewijzigde endpoints
- [ ] LDV logging, Problem JSON, security headers
- [ ] Unit tests en integratietests
- [ ] Bestaande berichtensessiecache tests bijwerken voor gewijzigde `MagazijnClient` interface

**Dependencies:** Issue 1 · **Complexiteit:** M

---

### Issue 3: Bericht Validatie Service en Publicatie Stream

**Labels:** `enhancement`, `magazijn`

Voeg twee C4 containers toe aan het berichtenmagazijn:

1. **Bericht Validatie Service** (`berichtValidatie`) — valideert berichten op technische eisen (PDF-type, grootte, bijlagen) en controleert toestemming via Profiel Service.
2. **Publicatie Stream** (`publicatieStream`) — outbox-patroon: wacht op publicatiedatum, publiceert CloudEvents (`nl.rijksoverheid.fbs.bericht.gepubliceerd`) conform NL GOV profiel v1.1.

**C4 componenten:** `validatieApi`, `validatieTechnisch`, `validatieToestemming`, `publicatieStream`

**Interne REST-contracten (C4 relaties D1, D2):**
- `POST /api/v1/berichten/_valideer` — bericht ter validatie aanbieden (consumer: `magazijnOpslagService`, C4 regel 77)
- `POST /api/v1/publicaties` — gevalideerd bericht doorsturen voor publicatie (consumer: `magazijnOpslagService`, C4 regel 78)

**Acceptatiecriteria:**
- [ ] Interne OpenAPI specs voor validatie- en publicatie-endpoints
- [ ] `BerichtValidatieService.kt` met technische validatie (type, grootte, bijlagen)
- [ ] `ToestemmingControle.kt` — REST client naar Profiel Service (PoC: WireMock stub)
- [ ] `AanleverResource` roept validatie aan vóór opslag via intern REST-endpoint
- [ ] `PublicatieStream.kt` met outbox-patroon (`@Scheduled` polling op berichten met status "te publiceren")
- [ ] CloudEvents conform NL GOV profiel v1.1 (`source: urn:nld:oin:{oin}:systeem:fbs-magazijn`)
- [ ] Unit tests voor validatieregels en publicatie-flow
- [ ] Integratietest voor de keten: aanleveren → valideren → publiceren

**Dependencies:** Issue 1 · **Complexiteit:** M

---

### Issue 4: Berichten Uitvraag Service — nieuwe service module

**Labels:** `enhancement`, `uitvraag`

Maak een nieuwe Quarkus service module `services/berichtenuitvraag` (C4 container: `uitvraagApi`). Dit is de user-facing API die de Interactielaag aanroept namens burgers en ondernemers. Naamgeving in het Nederlands conform ADR.

**C4 componenten:** `uitvraagResource`, `uitvraagBerichtenlijst`, `uitvraagOphaalService`, `uitvraagBeheerService`

**Dual-backend architectuur (afgeleid uit C4 relaties):**
- **Via Berichtensessiecache:** berichtenlijst (B2, regel 133), berichten ophalen (B1, regel 132), zoeken, status bijwerken in cache (B3, regel 134)
- **Via Magazijn (direct, via FSC):** bijlagen ophalen (C2, regel 158), berichtstatus persistent beheren (C3, regel 159)

Bij statuswijzigingen en verwijderingen schrijft de uitvraag service naar **zowel** het magazijn (persistent) **als** de sessiecache (cache-invalidatie).

**Endpoints:**
- `GET /api/v1/berichten` — berichtenlijst per map (via sessiecache)
- `GET /api/v1/berichten/_zoeken` — zoeken in berichten (via sessiecache)
- `GET /api/v1/berichten/{berichtId}` — bericht met inhoud (via sessiecache)
- `GET /api/v1/berichten/{berichtId}/bijlagen/{bijlageId}` — bijlage ophalen (via magazijn direct)
- `PATCH /api/v1/berichten/{berichtId}` — berichtstatus bijwerken en verplaatsen naar andere map (JSON Merge Patch → magazijn + sessiecache)
- `DELETE /api/v1/berichten/{berichtId}` — bericht verwijderen (→ magazijn + sessiecache)

**Sessiecache API-uitbreiding benodigd:**
De sessiecache API moet uitgebreid worden met schrijf-endpoints om de C4 relaties B3 en B4 te ondersteunen:
- `PATCH /api/v1/berichten/{berichtId}` — status bijwerken in cache (consumer: uitvraagBeheerService)
- `POST /api/v1/berichten` — bericht toevoegen aan cache (consumer: aanmeldService, zie Issue 8)

**Acceptatiecriteria:**
- [ ] Maven module `services/berichtenuitvraag` in parent POM
- [ ] OpenAPI spec voor uitvraag API met bovengenoemde endpoints
- [ ] OpenAPI spec valideert zonder fouten tegen [ADR Spectral linter](https://static.developer.overheid.nl/adr/ruleset.yaml)
- [ ] Verplaatsen gemodelleerd als `PATCH` met `map`-veld in de body (geen apart endpoint met werkwoord)
- [ ] REST client naar berichtensessiecache (berichtenlijst, zoeken, status in cache bijwerken)
- [ ] REST client naar berichtenmagazijn via FSC (bijlagen ophalen, berichtstatus persistent beheren)
- [ ] Sessiecache OpenAPI spec uitbreiden met `PATCH` schrijf-endpoint voor cache-invalidatie
- [ ] `BerichtenlijstService.kt`, `BerichtOphaalService.kt`, `BerichtBeheerService.kt`
- [ ] Problem JSON, API-Version header, security headers, LDV logging
- [ ] Unit tests en integratietests
- [ ] `compose.yaml` bijgewerkt

**Dependencies:** Issues 1, 2 (magazijn beschikbaar), berichtensessiecache (bestaand) · **Complexiteit:** L

---

### Issue 5: Token Validatie — JWT-authenticatie

**Labels:** `enhancement`, `security`, `uitvraag`

Implementeer het Token Validatie component (`tokenValidatie`) in de Berichten Uitvraag Service. Vervangt het huidige `X-Ontvanger` header patroon door JWT bearer tokens conform OIDC NL GOV profiel.

De Interactielaag verstrekt een JWT met PP als `sub` claim (burgers) of KvK/machtigingsclaims (ondernemers). Token Validatie verifieert handtekening, issuer, audience, expiration, jti en acr.

**Acceptatiecriteria:**
- [ ] Quarkus OIDC extensie (`quarkus-oidc`) geconfigureerd
- [ ] JWT validatie: handtekening, iss, aud, exp, jti, acr
- [ ] Gebruikersidentiteit uit claims: PP (burgers), KvK/RSIN + machtigingen (zakelijk)
- [ ] Betrouwbaarheidsniveau (LoA) uit `acr` claim
- [ ] `X-Ontvanger` niet meer nodig in uitvraag service (wel intern voor service-to-service)
- [ ] PoC-modus: configureerbare dev/test JWKS endpoint
- [ ] Unit tests met gesimuleerde JWT tokens (valid, invalid, expired)
- [ ] Integratietest met Quarkus OIDC test utilities

**Dependencies:** Issue 4 · **Complexiteit:** M

---

### Issue 6: BSNk Transformatie en PseudoniemService

**Labels:** `enhancement`, `security`

Implementeer de PseudoniemService (`pseudoniemService`) en integratie met BSNk Transformatie (`bsnkTransformatie`). Elk berichtenmagazijn ontvangt een uniek versleuteld pseudoniem (EP) zodat cross-magazijn koppeling cryptografisch uitgesloten is.

Momenteel stuurt `BerichtenOphalenResource.kt` dezelfde `ontvanger` naar alle magazijnen. In de doelarchitectuur wordt het PP per magazijn getransformeerd naar een uniek EP.

**Acceptatiecriteria:**
- [ ] `PseudoniemService.kt` interface en implementatie in berichtensessiecache
- [ ] Transformeert PP naar uniek EP per magazijn-ID
- [ ] `BerichtenOphalenResource` en `BerichtensessiecacheService` gebruiken PseudoniemService
- [ ] PoC-modus: deterministische stub (bijv. HMAC van PP + magazijnId)
- [ ] Interface-abstractie voor latere aansluiting echte BSNk API
- [ ] Bestaande tests aangepast
- [ ] Unit tests voor transformatie en uniciteit per magazijn

**Dependencies:** Issue 5 (PP beschikbaar uit JWT) · **Complexiteit:** M

---

### Issue 7: MagazijnResolver met Profiel Service

**Labels:** `enhancement`

Implementeer MagazijnResolver (`magazijnResolver`). Momenteel bevraagt `BerichtenOphalenResource` alle geconfigureerde magazijnen via `clientFactory.getAllClients()`. De MagazijnResolver bepaalt op basis van dienstvoorkeuren (Profiel Service) en machtigingsclaims welke magazijnen bevraagd worden.

**Acceptatiecriteria:**
- [ ] `MagazijnResolver.kt` interface en implementatie in berichtensessiecache
- [ ] REST client naar Profiel Service (PoC: WireMock stub)
- [ ] Combineert dienstvoorkeuren met beschikbare magazijnen
- [ ] Voor zakelijke gebruikers: evaluatie machtigingsclaims per magazijn
- [ ] `BerichtenOphalenResource` gebruikt MagazijnResolver i.p.v. `getAllClients()`
- [ ] Fallback: alle magazijnen bevragen als Profiel Service onbereikbaar (graceful degradation)
- [ ] WireMock mappings voor Profiel Service
- [ ] Unit tests en integratietests

**Dependencies:** Issues 5 (machtigingsclaims), 6 (EP per magazijn) · **Complexiteit:** M

---

### Issue 8: Aanmeld Service en CloudEvents notificatie-flow

**Labels:** `enhancement`, `uitvraag`

Implementeer de Aanmeld Service (`aanmeldService`) en verbind de volledige notificatie-keten. De Aanmeld Service is een brug tussen CloudEvents (van magazijnen) en de sessiecache REST API.

**C4 relaties:**
- `publicatieStream → aanmeldService` — "Meldt nieuw bericht aan" (E1, regel 161, via FSC)
- `aanmeldService → sessiecacheResource` — "Werkt cache bij" (B4, regel 131, intern mTLS)

**Flow:** Publicatie Stream → CloudEvents HTTP webhook (via FSC) → Aanmeld Service → `POST /api/v1/berichten` → Berichtensessiecache

**Acceptatiecriteria:**
- [ ] Aanmeld Service als onderdeel van berichtenuitvraag module (of eigen module)
- [ ] CloudEvents HTTP webhook endpoint conform [CloudEvents HTTP Protocol Binding](https://github.com/cloudevents/spec/blob/v1.0.2/cloudevents/bindings/http-protocol-binding.md) voor `nl.rijksoverheid.fbs.bericht.gepubliceerd`
- [ ] Sessiecache OpenAPI spec uitbreiden met `POST /api/v1/berichten` — bericht toevoegen aan cache voor actieve sessies (consumer: aanmeldService)
- [ ] Alleen bijwerken als cache-key voor de ontvanger bestaat (actieve sessie)
- [ ] Idempotentie: dubbele events niet opnieuw verwerken (CloudEvents `id` attribuut)
- [ ] Unit tests en integratietest voor volledige flow (publicatie → aanmelding → cache update)

**Dependencies:** Issues 3 (Publicatie Stream), 4 (Berichtenuitvraag) · **Complexiteit:** M

---

### Issue 9: Autorisatie Service — PEP/PDP patroon

**Labels:** `enhancement`, `security`, `magazijn`

Implementeer de Autorisatie Service (`autorisatieService`) voor het Berichtenmagazijn en het PEP/PDP-patroon conform AuthZEN NL GOV.

**C4 relatie:** `magazijnOphaalBeheerApi → autorisatieService` — "Toetst autorisatie per verzoek" (D3, regel 83, intern mTLS)

Twee autorisatieniveaus uit het C4-model:
1. **Berichtenmagazijn:** Ophaal- en Beheer API als PEP, Autorisatie Service als PDP
2. **Berichten Uitvraag Systeem:** Token Validatie als PEP, MagazijnResolver als PEP/PDP

**AuthZEN evaluatie-endpoint:**
- `POST /api/v1/autorisatiebeslissingen/_evalueer` — evalueert autorisatieverzoek (subject/action/resource/context → allow/deny)

**Acceptatiecriteria:**
- [ ] OpenAPI spec voor autorisatie evaluatie-endpoint conform AuthZEN NL GOV
- [ ] `AutorisatieService.kt` interface en PoC-implementatie in berichtenmagazijn
- [ ] PEP-integratie in `OphaalBeheerResource` en `AanleverResource` (interceptor of filter)
- [ ] Autorisatiebeslissing-model: subject/action/resource/context (AuthZEN)
- [ ] PoC-beleid: basisregels (bijv. alleen eigen berichten ophalen)
- [ ] Authorization Decision Log conform LDV standaard
- [ ] Machtigingsvalidatie voor eHerkenning ketenmachtiging in uitvraag service
- [ ] Unit tests voor autorisatiebeslissingen (allowed/denied)

**Dependencies:** Issues 2, 5 · **Complexiteit:** L

---

### Issue 10: C4-model synchronisatie met implementatiebeslissingen

**Labels:** `documentation`

Werk `docs/architecture/workspace.dsl` bij zodat het de implementatiebeslissingen reflecteert. De CI-workflow genereert automatisch een site met Structurizr Site Generatr en publiceert PR-previews.

**Acceptatiecriteria:**
- [ ] Deployment model views toevoegen (Quarkus services, Redis, ClickHouse, BSNk)
- [ ] Eventuele nieuwe componenten die tijdens implementatie ontstaan toevoegen
- [ ] Properties bijwerken met concrete technologiekeuzes (bijv. H2 vs PostgreSQL)
- [ ] PoC-afwijkingen documenteren in `workspace-docs/`
- [ ] Relaties valideren: alle code-paden kloppen met `->` relaties in DSL
- [ ] Architecture site bouwt succesvol (CI workflow)

**Dependencies:** Issues 1–9 (incrementeel meebijwerken) · **Complexiteit:** S

---

## Afhankelijkheden en volgorde

```
Issue 1: Magazijn Aanlever API           (geen deps)         [L]
Issue 2: Magazijn Ophaal- & Beheer API   (→ 1)               [M]
Issue 3: Validatie + Publicatie Stream    (→ 1)               [M]
Issue 4: Berichten Uitvraag Service      (→ 1, 2)            [L]
Issue 5: Token Validatie (JWT)           (→ 4)               [M]
Issue 6: BSNk / PseudoniemService        (→ 5)               [M]
Issue 7: MagazijnResolver + Profiel Svc  (→ 5, 6)            [M]
Issue 8: Aanmeld Service + notificaties  (→ 3, 4)            [M]
Issue 9: Autorisatie (AuthZEN)           (→ 2, 5)            [L]
Issue 10: C4-model synchronisatie        (→ 1–9, incrementeel) [S]
```

**Aanbevolen parallellisatie:**
- **Wave 1:** Issues 1 + 10 (incrementeel)
- **Wave 2:** Issues 2, 3 (parallel, beiden → 1)
- **Wave 3:** Issue 4 (→ 1, 2)
- **Wave 4:** Issues 5, 8 (parallel)
- **Wave 5:** Issue 6 (→ 5)
- **Wave 6:** Issues 7, 9 (parallel)
