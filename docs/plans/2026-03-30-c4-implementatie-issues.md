**Status:** Concept

# GitHub Issues: C4-model implementeren

## Context

Het C4-model in `docs/architecture/workspace.dsl` beschrijft de volledige doelarchitectuur van het Federatief Berichtenstelsel (FBS). Momenteel is alleen de **Berichtensessiecache** geïmplementeerd (`services/berichtensessiecache/`). De overige containers en componenten uit het C4-model moeten nog gebouwd worden. Deze issues verdelen dat werk in 11 logische, onafhankelijk leverbare delen.

**Huidige staat:**
- :warning: Berichtensessiecache (geïmplementeerd, maar wijkt af van C4-model — zie Issue 1)
- :x: Berichtenmagazijn (Aanlever API, Ophaal- & Beheer API, Validatie, Publicatie Stream, Autorisatie, Dataopslag)
- :x: Berichten Uitvraag Service (user-facing API, Token Validatie, Beheer)
- :x: BSNk Transformatie / PseudoniemService
- :x: Aanmeld Service
- :x: MagazijnResolver met Profiel Service
- :x: Autorisatie (AuthZEN PEP/PDP)

---

## Deployment-strategie

C4-containers zijn logische grenzen, geen verplichte deployment-grenzen. De C4-containers worden gegroepeerd in **3 deployable units** (Quarkus applicaties) om operationele complexiteit te beperken:

| Deployable unit | C4-containers (als packages / CDI beans) | Reden |
|---|---|---|
| **`services/berichtenmagazijn`** | Aanlever API, Ophaal- & Beheer API, Validatie Service, Publicatie Stream, Autorisatie Service, Dataopslag | Delen dezelfde database, altijd samen gedeployed per organisatie. Validatie en autorisatie zijn synchrone stappen — geen netwerk-hop nodig. |
| **`services/berichtensessiecache`** | Berichtensessiecache (bestaand) | Eigen schaalbehoefte (Redis-gebonden). Blijft apart. |
| **`services/berichtenuitvraag`** | Berichten Uitvraag Service, Aanmeld Service | Beide centraal gehost, zelfde trust boundary. Aanmeld Service is een lichtgewicht CloudEvents-ontvanger. |

**Gevolg:** de interne C4-relaties binnen het magazijn (D1, D2, D3) worden **CDI method calls** in plaats van REST-endpoints:

```
C4 (logisch):
  magazijnOpslagService → validatieApi          "REST API (intern, mTLS)"
  magazijnOpslagService → publicatieStream      "REST API (intern, mTLS)"
  magazijnOphaalBeheerApi → autorisatieService  "REST API (intern, mTLS)"

Implementatie (gegroepeerd):
  BerichtOpslagService → BerichtValidatieService      @Inject (CDI)
  BerichtOpslagService → PublicatieStream              @Inject (CDI)
  OphaalBeheerResource → AutorisatieService            @Inject (JAX-RS interceptor)
```

Het C4-model blijft ongewijzigd (logische architectuur). De groepering wordt gedocumenteerd in `workspace-docs/` (Issue 11).

---

## API-overzicht (afgeleid uit C4-model)

### Externe API's (via FSC)

| API | Deployable unit | Consumers | Endpoints |
|-----|-----------------|-----------|-----------|
| **Berichten Uitvraag API** | `berichtenuitvraag` | Interactielaag (JWT) | `GET /berichten`, `GET /berichten/_zoeken`, `GET /berichten/{id}`, `GET /berichten/{id}/bijlagen/{id}`, `PATCH /berichten/{id}`, `DELETE /berichten/{id}` |
| **Magazijn Ophaal- en Beheer API** | `berichtenmagazijn` | Sessiecache, Uitvraag Service | `GET /berichten`, `GET /berichten/{id}`, `GET /berichten/{id}/bijlagen/{id}`, `PATCH /berichten/{id}`, `DELETE /berichten/{id}` |
| **Magazijn Aanlever API** | `berichtenmagazijn` | Organisaties | `POST /berichten` |

### Interne API's (mTLS, tussen deployable units)

| API | Deployable unit | Consumers | Endpoints |
|-----|-----------------|-----------|-----------|
| **Berichtensessiecache API** | `berichtensessiecache` | Uitvraag Service, Aanmeld Service | `GET /berichten`, `GET /berichten/_zoeken`, `GET /berichten/{id}`, `PATCH /berichten/{id}`, `POST /berichten` |
| **Aanmeld Service webhook** | `berichtenuitvraag` | Publicatie Stream (magazijn) | CloudEvents HTTP binding (`nl.rijksoverheid.fbs.bericht.gepubliceerd`) |

### Voormalige interne API's → nu CDI calls binnen magazijn

| C4 relatie | Was (aparte REST API) | Wordt (CDI binnen `berichtenmagazijn`) |
|---|---|---|
| D1: `magazijnOpslagService → validatieApi` | `POST /berichten/_valideer` | `@Inject BerichtValidatieService.valideer(bericht)` |
| D2: `magazijnOpslagService → publicatieStream` | `POST /publicaties` | `@Inject PublicatieStream.planPublicatie(bericht)` |
| D3: `magazijnOphaalBeheerApi → autorisatieService` | `POST /autorisatiebeslissingen/_evalueer` | `@Inject AutorisatieService` als JAX-RS interceptor |

### Uitgaande REST-clients (via FSC)

| Client | In deployable unit | Naar | Doel |
|--------|-------------------|------|------|
| `sessiecacheMagazijnClient` | `berichtensessiecache` | Magazijn Ophaal- en Beheer API | Berichten ophalen voor aggregatie |
| `uitvraagOphaalService` | `berichtenuitvraag` | Magazijn Ophaal- en Beheer API | Bijlagen ophalen |
| `uitvraagBeheerService` | `berichtenuitvraag` | Magazijn Ophaal- en Beheer API | Berichtstatus beheren |
| `magazijnResolver` | `berichtensessiecache` | Profiel Service | Dienstvoorkeuren ophalen |
| `validatieToestemming` | `berichtenmagazijn` | Profiel Service | Toestemming controleren |
| `publicatieStream` | `berichtenmagazijn` | Aanmeld Service, Notificatie Service | CloudEvents doorsturen |

---

## Issues

### Issue 1: Berichtensessiecache alignen met C4-model

**Labels:** `refactor`, `sessiecache`

De bestaande berichtensessiecache implementatie wijkt op meerdere punten af van het C4-model. Deze afwijkingen moeten eerst opgelost worden voordat nieuwe services (uitvraag, magazijn) erop kunnen aansluiten.

**Afwijkingen:**

| # | Ernst | Afwijking | C4-model | Huidige code |
|---|-------|----------|----------|-------------|
| 1 | Hoog | Aggregatie-logica in Resource i.p.v. Service | `sessiecacheService` aggregeert via `magazijnResolver` → `pseudoniemService` → `magazijnClient` (regels 101-105) | `BerichtenOphalenResource` orkestreert alles zelf (regels 55-196) |
| 2 | Hoog | Geen schrijf-endpoints op sessiecache API | B3: uitvraagBeheerService schrijft status (regel 134), B4: aanmeldService voegt berichten toe (regel 131) | Alleen GET endpoints + SSE; geen PATCH/POST |
| 3 | Hoog | MagazijnClient heeft zoek-endpoint dat niet in C4 staat | C4 relatie C1: alleen "Haalt berichten op" (regel 172) | `MagazijnClient.kt:28` heeft `zoekBerichten()` — zoeken hoort lokaal in RediSearch |
| 4 | Medium | MagazijnClient stuurt ontvanger als query param (PII in URL) | ADR: geen gevoelige informatie in URIs | `MagazijnClient.kt:18`: `@QueryParam("ontvanger")` |
| 5 | Medium | EventForwarder is dode code op verkeerde locatie | C4: notificaties gaan van `publicatieStream` → `notificatieService` (regel 162, vanuit magazijn) | `notificatie/EventForwarder.kt` in sessiecache, nergens aangeroepen |
| 6 | Laag | `afzender` filter niet geïmplementeerd | OpenAPI spec accepteert `afzender` parameter | `BerichtensessiecacheService` negeert de parameter |

**Structurele refactoring (afwijking 1):**

De aggregatie-logica moet verplaatst worden van `BerichtenOphalenResource` naar `BerichtensessiecacheService`, conform de C4 component-flow:

```
Huidige flow:
  BerichtenOphalenResource → MagazijnClientFactory.getAllClients() → MagazijnClient
                           → BerichtenCache (direct)

C4 flow:
  sessiecacheResource → sessiecacheService → magazijnResolver (welke magazijnen?)
                                           → pseudoniemService (PP→EP per magazijn)
                                           → sessiecacheMagazijnClient (ophalen)
                                           → sessiecacheCache (opslaan)
```

De Resource wordt een dunne laag die alleen SSE-events doorgeeft; de Service orkestreert de aggregatie. Dit maakt het mogelijk om in latere issues (7, 8) de MagazijnResolver en PseudoniemService in te pluggen via CDI zonder de Resource te wijzigen.

**Acceptatiecriteria:**

*Afwijking 1 — Aggregatie naar Service:*
- [ ] Aggregatie-logica verplaatsen van `BerichtenOphalenResource` naar `BerichtensessiecacheService`
- [ ] Service krijgt methode `aggregeerBerichten(ontvanger): Multi<MagazijnStatusEvent>` die de volledige flow orkestreert
- [ ] Resource wordt dunne SSE-doorgeefluik: roept service aan, streamt events
- [ ] `MagazijnClientFactory` wordt dependency van de Service, niet van de Resource
- [ ] Bestaande SSE-tests blijven slagen (gedrag ongewijzigd, alleen interne structuur)

*Afwijking 2 — Schrijf-endpoints:*
- [ ] OpenAPI spec uitbreiden met `PATCH /berichten/{berichtId}` — status bijwerken in cache (consumer: uitvraagBeheerService)
- [ ] OpenAPI spec uitbreiden met `POST /berichten` — bericht toevoegen aan cache (consumer: aanmeldService)
- [ ] `BerichtenCache` interface uitbreiden met `updateStatus()` en `addBericht()` methoden
- [ ] Nieuwe endpoints in `BerichtensessiecacheResource` (of aparte Resource class)
- [ ] Unit tests voor schrijf-endpoints (happy + unhappy, o.a. bericht niet in cache, ontvanger mismatch)

*Afwijking 3 — MagazijnClient zoek-endpoint verwijderen:*
- [ ] `zoekBerichten()` methode verwijderen uit `MagazijnClient` interface
- [ ] Bijbehorende WireMock mappings opruimen indien aanwezig
- [ ] Tests die zoeken via magazijn gebruiken aanpassen

*Afwijking 4 — Ontvanger als header:*
- [ ] `MagazijnClient.getBerichten()`: `ontvanger` van `@QueryParam` naar `@HeaderParam("X-Ontvanger")`
- [ ] WireMock mappings bijwerken

*Afwijking 5 — EventForwarder verwijderen:*
- [ ] `notificatie/EventForwarder.kt` verwijderen uit sessiecache (dode code; notificatie-flow hoort in magazijn `publicatieStream`, zie Issue 4)
- [ ] `notificatie.service.url` verwijderen uit `application.properties`

*Afwijking 6 — afzender filter:*
- [ ] `afzender` filter implementeren in `BerichtensessiecacheService.getBerichten()` (RediSearch TAG filter) of parameter verwijderen uit OpenAPI spec als het buiten scope PoC valt

**Dependencies:** geen (eerste issue) · **Complexiteit:** L

---

### Issue 2: Berichtenmagazijn — nieuwe service module met Aanlever API

**Labels:** `enhancement`, `magazijn`

Maak een nieuwe Quarkus service module `services/berichtenmagazijn`. Dit wordt de **enkele deployable unit** voor het hele Berichtenmagazijn (C4 systeem: `decentraalMagazijn`). Alle magazijn-containers (Aanlever API, Ophaal- & Beheer API, Validatie, Publicatie Stream, Autorisatie, Dataopslag) worden packages/CDI beans binnen deze module.

Start met de Aanlever API (C4 container: `magazijnAanleverApi`) als eerste functionele package.

Volgt dezelfde patronen als `services/berichtensessiecache`: OpenAPI-first, functionele packages, Problem JSON, security headers, LDV logging. Naamgeving in het Nederlands conform ADR.

**C4 componenten:** `magazijnAanleverResource`, `magazijnCircuitBreaker`, `magazijnOpslagService`

**Acceptatiecriteria:**
- [ ] Maven module `services/berichtenmagazijn` geregistreerd in parent POM
- [ ] Package-structuur voorbereid op alle magazijn-containers: `aanlever/`, `ophaal/`, `validatie/`, `publicatie/`, `autorisatie/`, `opslag/`
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

### Issue 3: Berichtenmagazijn Ophaal- en Beheer API

**Labels:** `enhancement`, `magazijn`

Voeg de Ophaal- en Beheer API toe als package `ophaal/` aan `services/berichtenmagazijn` (C4 container: `magazijnOphaalBeheerApi`). Naamgeving in het Nederlands conform ADR.

Deze API heeft **twee externe consumers** (C4 relaties):
1. **Berichtensessiecache** (`sessiecacheMagazijnClient`): haalt berichtenlijsten op voor aggregatie (C4 regel 172)
2. **Berichten Uitvraag Service**: haalt bijlagen op (`uitvraagOphaalService`, C4 regel 158) en beheert berichtstatus (`uitvraagBeheerService`, C4 regel 159)

**Endpoints (zelfde OpenAPI spec als Aanlever API, uitgebreid):**
- `GET /api/v1/berichten` — berichtenlijst ophalen (ontvanger via `X-Ontvanger` header — voorkomt PII in URLs/logs)
- `GET /api/v1/berichten/{berichtId}` — enkel bericht met inhoud
- `GET /api/v1/berichten/{berichtId}/bijlagen/{bijlageId}` — bijlage ophalen (consumer: uitvraagOphaalService)
- `PATCH /api/v1/berichten/{berichtId}` — berichtstatus bijwerken (gelezen, map) via JSON Merge Patch
- `DELETE /api/v1/berichten/{berichtId}` — bericht verwijderen

**Acceptatiecriteria:**
- [ ] OpenAPI spec uitbreiden met bovengenoemde endpoints
- [ ] OpenAPI spec valideert zonder fouten tegen [ADR Spectral linter](https://static.developer.overheid.nl/adr/ruleset.yaml)
- [ ] `ontvanger` als `X-Ontvanger` header (niet als query parameter) — conform ADR-regel "geen gevoelige informatie in URIs"
- [ ] Bijlagen-endpoint voor het ophalen van individuele bijlagen bij een bericht
- [ ] `OphaalBeheerResource.kt` implementeert gegenereerde interface
- [ ] **Breaking change:** `MagazijnClient` interface bijwerken: `ontvanger` van `@QueryParam` naar `@HeaderParam("X-Ontvanger")`; zoek-endpoint verwijderen (zoeken gebeurt lokaal in sessiecache via RediSearch, niet op het magazijn)
- [ ] Deelt dezelfde dataopslag-interface als de Aanlever API (uit Issue 2)
- [ ] Berichtstatus per gebruiker (gelezen/ongelezen, map) via `PATCH` met JSON Merge Patch body
- [ ] WireMock mappings bijgewerkt voor nieuwe en gewijzigde endpoints
- [ ] LDV logging, Problem JSON, security headers
- [ ] Unit tests en integratietests
- [ ] Bestaande berichtensessiecache tests bijwerken voor gewijzigde `MagazijnClient` interface

**Dependencies:** Issues 1, 2 · **Complexiteit:** M

---

### Issue 4: Bericht Validatie en Publicatie Stream

**Labels:** `enhancement`, `magazijn`

Voeg twee C4 containers toe als packages `validatie/` en `publicatie/` aan `services/berichtenmagazijn`:

1. **Bericht Validatie Service** (`berichtValidatie`) — valideert berichten op technische eisen (PDF-type, grootte, bijlagen) en controleert toestemming via Profiel Service.
2. **Publicatie Stream** (`publicatieStream`) — outbox-patroon: wacht op publicatiedatum, publiceert CloudEvents (`nl.rijksoverheid.fbs.bericht.gepubliceerd`) conform NL GOV profiel v1.1.

Beide worden **CDI beans** binnen het magazijn-proces (geen aparte REST-endpoints):

```
BerichtOpslagService → @Inject BerichtValidatieService.valideer(bericht)
BerichtOpslagService → @Inject PublicatieStream.planPublicatie(bericht)
```

**C4 componenten:** `validatieTechnisch`, `validatieToestemming`, `publicatieStream`

**Acceptatiecriteria:**
- [ ] `BerichtValidatieService.kt` als CDI bean met technische validatie (type, grootte, bijlagen)
- [ ] `ToestemmingControle.kt` — REST client naar Profiel Service (PoC: WireMock stub)
- [ ] `BerichtOpslagService` (uit Issue 2) roept validatie aan vóór opslag via `@Inject`
- [ ] `PublicatieStream.kt` met outbox-patroon (`@Scheduled` polling op berichten met status "te publiceren")
- [ ] CloudEvents conform NL GOV profiel v1.1 (`source: urn:nld:oin:{oin}:systeem:fbs-magazijn`)
- [ ] CloudEvents doorsturen naar Aanmeld Service en Notificatie Service (PoC: WireMock stubs)
- [ ] Unit tests voor validatieregels en publicatie-flow
- [ ] Integratietest voor de keten: aanleveren → valideren → opslaan → publiceren

**Dependencies:** Issue 2 · **Complexiteit:** M

---

### Issue 5: Berichten Uitvraag Service — nieuwe service module

**Labels:** `enhancement`, `uitvraag`

Maak een nieuwe Quarkus service module `services/berichtenuitvraag`. Dit wordt de **enkele deployable unit** voor het Berichten Uitvraag Systeem (excl. sessiecache). Bevat de Berichten Uitvraag Service (C4 container: `uitvraagApi`) en later de Aanmeld Service (Issue 9). Naamgeving in het Nederlands conform ADR.

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

**Acceptatiecriteria:**
- [ ] Maven module `services/berichtenuitvraag` in parent POM
- [ ] Package-structuur voorbereid op Aanmeld Service (Issue 9): `uitvraag/`, `aanmeld/`
- [ ] OpenAPI spec voor uitvraag API met bovengenoemde endpoints
- [ ] OpenAPI spec valideert zonder fouten tegen [ADR Spectral linter](https://static.developer.overheid.nl/adr/ruleset.yaml)
- [ ] Verplaatsen gemodelleerd als `PATCH` met `map`-veld in de body (geen apart endpoint met werkwoord)
- [ ] REST client naar berichtensessiecache (berichtenlijst, zoeken, status in cache bijwerken)
- [ ] REST client naar berichtenmagazijn via FSC (bijlagen ophalen, berichtstatus persistent beheren)
- [ ] `BerichtenlijstService.kt`, `BerichtOphaalService.kt`, `BerichtBeheerService.kt`
- [ ] Problem JSON, API-Version header, security headers, LDV logging
- [ ] Unit tests en integratietests
- [ ] `compose.yaml` bijgewerkt

**Dependencies:** Issues 1 (sessiecache schrijf-endpoints + refactored service), 3 (magazijn beschikbaar) · **Complexiteit:** L

---

### Issue 6: Token Validatie — JWT-authenticatie

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

**Dependencies:** Issue 5 · **Complexiteit:** M

---

### Issue 7: BSNk Transformatie en PseudoniemService

**Labels:** `enhancement`, `security`

Implementeer de PseudoniemService (`pseudoniemService`) en integratie met BSNk Transformatie (`bsnkTransformatie`). Elk berichtenmagazijn ontvangt een uniek versleuteld pseudoniem (EP) zodat cross-magazijn koppeling cryptografisch uitgesloten is.

Momenteel stuurt de sessiecache dezelfde `ontvanger` naar alle magazijnen. In de doelarchitectuur wordt het PP per magazijn getransformeerd naar een uniek EP.

**Acceptatiecriteria:**
- [ ] `PseudoniemService.kt` interface en implementatie in berichtensessiecache
- [ ] Transformeert PP naar uniek EP per magazijn-ID
- [ ] `BerichtensessiecacheService` gebruikt PseudoniemService (inplugbaar dankzij Issue 1 refactoring)
- [ ] PoC-modus: deterministische stub (bijv. HMAC van PP + magazijnId)
- [ ] Interface-abstractie voor latere aansluiting echte BSNk API
- [ ] Bestaande tests aangepast
- [ ] Unit tests voor transformatie en uniciteit per magazijn

**Dependencies:** Issues 1 (aggregatie in Service maakt inpluggen mogelijk), 6 (PP beschikbaar uit JWT) · **Complexiteit:** M

---

### Issue 8: MagazijnResolver met Profiel Service

**Labels:** `enhancement`

Implementeer MagazijnResolver (`magazijnResolver`). Momenteel bevraagt de sessiecache alle geconfigureerde magazijnen. De MagazijnResolver bepaalt op basis van dienstvoorkeuren (Profiel Service) en machtigingsclaims welke magazijnen bevraagd worden.

**Acceptatiecriteria:**
- [ ] `MagazijnResolver.kt` interface en implementatie in berichtensessiecache
- [ ] REST client naar Profiel Service (PoC: WireMock stub)
- [ ] Combineert dienstvoorkeuren met beschikbare magazijnen
- [ ] Voor zakelijke gebruikers: evaluatie machtigingsclaims per magazijn
- [ ] `BerichtensessiecacheService` gebruikt MagazijnResolver (inplugbaar dankzij Issue 1 refactoring)
- [ ] Fallback: alle magazijnen bevragen als Profiel Service onbereikbaar (graceful degradation)
- [ ] WireMock mappings voor Profiel Service
- [ ] Unit tests en integratietests

**Dependencies:** Issues 1 (aggregatie in Service maakt inpluggen mogelijk), 6 (machtigingsclaims), 7 (EP per magazijn) · **Complexiteit:** M

---

### Issue 9: Aanmeld Service en CloudEvents notificatie-flow

**Labels:** `enhancement`, `uitvraag`

Implementeer de Aanmeld Service (`aanmeldService`) als package `aanmeld/` binnen `services/berichtenuitvraag`. De Aanmeld Service is een brug tussen CloudEvents (van magazijnen) en de sessiecache REST API.

**C4 relaties:**
- `publicatieStream → aanmeldService` — "Meldt nieuw bericht aan" (E1, regel 161, via FSC)
- `aanmeldService → sessiecacheResource` — "Werkt cache bij" (B4, regel 131, intern mTLS)

**Flow:** Publicatie Stream → CloudEvents HTTP webhook (via FSC) → Aanmeld Service → `POST /api/v1/berichten` → Berichtensessiecache

**Acceptatiecriteria:**
- [ ] `AanmeldResource.kt` in package `aanmeld/` binnen berichtenuitvraag module
- [ ] CloudEvents HTTP webhook endpoint conform [CloudEvents HTTP Protocol Binding](https://github.com/cloudevents/spec/blob/v1.0.2/cloudevents/bindings/http-protocol-binding.md) voor `nl.rijksoverheid.fbs.bericht.gepubliceerd`
- [ ] REST client naar sessiecache `POST /api/v1/berichten` (endpoint uit Issue 1)
- [ ] Alleen bijwerken als cache-key voor de ontvanger bestaat (actieve sessie)
- [ ] Idempotentie: dubbele events niet opnieuw verwerken (CloudEvents `id` attribuut)
- [ ] Unit tests en integratietest voor volledige flow (publicatie → aanmelding → cache update)

**Dependencies:** Issues 1 (sessiecache POST-endpoint), 4 (Publicatie Stream), 5 (Berichtenuitvraag module) · **Complexiteit:** M

---

### Issue 10: Autorisatie Service — PEP/PDP patroon

**Labels:** `enhancement`, `security`, `magazijn`

Implementeer de Autorisatie Service (`autorisatieService`) als package `autorisatie/` en CDI interceptor binnen `services/berichtenmagazijn`, conform AuthZEN NL GOV.

Omdat de autorisatie-service in hetzelfde proces draait als de Ophaal- en Beheer API, wordt het een **CDI bean met JAX-RS interceptor** in plaats van een apart REST-endpoint:

```kotlin
@AutorisatieCheck  // custom interceptor binding
fun getBerichten(@HeaderParam("X-Ontvanger") ontvanger: String): Response
```

Twee autorisatieniveaus uit het C4-model:
1. **Berichtenmagazijn:** Ophaal- en Beheer API als PEP, Autorisatie Service als PDP (CDI interceptor)
2. **Berichten Uitvraag Systeem:** Token Validatie als PEP, MagazijnResolver als PEP/PDP

**Acceptatiecriteria:**
- [ ] `AutorisatieService.kt` interface en PoC-implementatie als CDI bean
- [ ] JAX-RS interceptor of `ContainerRequestFilter` als PEP op `OphaalBeheerResource` en `AanleverResource`
- [ ] Autorisatiebeslissing-model: subject/action/resource/context (AuthZEN)
- [ ] PoC-beleid: basisregels (bijv. alleen eigen berichten ophalen)
- [ ] Authorization Decision Log conform LDV standaard
- [ ] Machtigingsvalidatie voor eHerkenning ketenmachtiging in uitvraag service
- [ ] Unit tests voor autorisatiebeslissingen (allowed/denied)

**Dependencies:** Issues 3, 6 · **Complexiteit:** L

---

### Issue 11: C4-model synchronisatie met implementatiebeslissingen

**Labels:** `documentation`

Werk `docs/architecture/workspace.dsl` bij zodat het de implementatiebeslissingen reflecteert. De CI-workflow genereert automatisch een site met Structurizr Site Generatr en publiceert PR-previews.

**Acceptatiecriteria:**
- [ ] Deployment model views toevoegen die de 3 deployable units tonen (berichtenmagazijn, berichtensessiecache, berichtenuitvraag) met hun C4-containers als interne componenten
- [ ] Documenteren in `workspace-docs/` dat C4-containers gegroepeerd zijn in 3 deployment units (logisch vs. fysiek)
- [ ] Eventuele nieuwe componenten die tijdens implementatie ontstaan toevoegen
- [ ] Properties bijwerken met concrete technologiekeuzes (bijv. H2 vs PostgreSQL)
- [ ] PoC-afwijkingen documenteren in `workspace-docs/`
- [ ] Relaties valideren: alle code-paden kloppen met `->` relaties in DSL
- [ ] Architecture site bouwt succesvol (CI workflow)

**Dependencies:** Issues 1–10 (incrementeel meebijwerken) · **Complexiteit:** S

---

## Afhankelijkheden en volgorde

```
Issue 1:  Sessiecache alignen met C4     (geen deps)          [L]
Issue 2:  Magazijn module + Aanlever API (geen deps)          [L]
Issue 3:  Magazijn Ophaal- & Beheer API  (→ 1, 2)             [M]
Issue 4:  Validatie + Publicatie Stream   (→ 2)                [M]
Issue 5:  Berichten Uitvraag Service     (→ 1, 3)             [L]
Issue 6:  Token Validatie (JWT)          (→ 5)                [M]
Issue 7:  BSNk / PseudoniemService       (→ 1, 6)             [M]
Issue 8:  MagazijnResolver + Profiel Svc (→ 1, 6, 7)          [M]
Issue 9:  Aanmeld Service + notificaties (→ 1, 4, 5)          [M]
Issue 10: Autorisatie (AuthZEN)          (→ 3, 6)             [L]
Issue 11: C4-model synchronisatie        (→ 1–10, incrementeel) [S]
```

**Aanbevolen parallellisatie:**
- **Wave 1:** Issues 1, 2 (parallel: sessiecache refactoren + magazijn module opzetten)
- **Wave 2:** Issues 3, 4, 11 (parallel: magazijn APIs + validatie + C4 sync incrementeel)
- **Wave 3:** Issue 5 (→ 1, 3)
- **Wave 4:** Issues 6, 9 (parallel: JWT + notificatie-flow)
- **Wave 5:** Issue 7 (→ 1, 6)
- **Wave 6:** Issues 8, 10 (parallel: resolver + autorisatie)
