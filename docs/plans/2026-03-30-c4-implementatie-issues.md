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

## Issues

### Issue 1: Berichtenmagazijn Aanlever API — nieuwe service module

**Labels:** `enhancement`, `magazijn`

Maak een nieuwe Quarkus service module `services/berichtenmagazijn` met de Aanlever API (C4 container: `magazijnAanleverApi`). Hiermee kunnen organisaties berichten aanleveren aan het FBS.

Volgt dezelfde patronen als `services/berichtensessiecache`: OpenAPI-first, functionele packages, Problem JSON, security headers, LDV logging.

**C4 componenten:** `magazijnAanleverResource`, `magazijnCircuitBreaker`, `magazijnOpslagService`

**Acceptatiecriteria:**
- [ ] Maven module `services/berichtenmagazijn` geregistreerd in parent POM
- [ ] OpenAPI spec voor Aanlever API (`POST /api/v1/berichten`) conform NL API Design Rules
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

Voeg de Ophaal- en Beheer API toe aan `services/berichtenmagazijn` (C4 container: `magazijnOphaalBeheerApi`). Dit is de API die de bestaande `MagazijnClient` in de berichtensessiecache aanroept. Het response-formaat moet aansluiten op `MagazijnBerichtenResponse` en de huidige WireMock-stubs.

**Endpoints:**
- `GET /api/v1/berichten` — berichten ophalen (filter op ontvanger)
- `GET /api/v1/berichten/{berichtId}` — enkel bericht met inhoud/bijlagen
- `PATCH /api/v1/berichten/{berichtId}/status` — berichtstatus bijwerken (gelezen, verplaatst)
- `DELETE /api/v1/berichten/{berichtId}` — bericht verwijderen

**Acceptatiecriteria:**
- [ ] OpenAPI spec met bovengenoemde endpoints
- [ ] `OphaalBeheerResource.kt` implementeert gegenereerde interface
- [ ] Response compatibel met bestaande `MagazijnClient` / `MagazijnBerichtenResponse`
- [ ] Berichtstatus per gebruiker (gelezen/ongelezen, map)
- [ ] WireMock mappings bijgewerkt voor nieuwe endpoints
- [ ] LDV logging, Problem JSON, security headers
- [ ] Unit tests en integratietests
- [ ] Bestaande berichtensessiecache tests blijven slagen

**Dependencies:** Issue 1 · **Complexiteit:** M

---

### Issue 3: Bericht Validatie Service en Publicatie Stream

**Labels:** `enhancement`, `magazijn`

Voeg twee C4 containers toe aan het berichtenmagazijn:

1. **Bericht Validatie Service** (`berichtValidatie`) — valideert berichten op technische eisen (PDF-type, grootte, bijlagen) en controleert toestemming via Profiel Service.
2. **Publicatie Stream** (`publicatieStream`) — outbox-patroon: wacht op publicatiedatum, publiceert CloudEvents (`nl.rijksoverheid.fbs.bericht.gepubliceerd`) conform NL GOV profiel v1.1.

**C4 componenten:** `validatieApi`, `validatieTechnisch`, `validatieToestemming`, `publicatieStream`

**Acceptatiecriteria:**
- [ ] `BerichtValidatieService.kt` met technische validatie (type, grootte, bijlagen)
- [ ] `ToestemmingControle.kt` — REST client naar Profiel Service (PoC: WireMock stub)
- [ ] `AanleverResource` roept validatie aan vóór opslag
- [ ] `PublicatieStream.kt` met outbox-patroon (`@Scheduled` polling)
- [ ] CloudEvents conform NL GOV profiel v1.1 (`source: urn:nld:oin:{oin}:systeem:fbs-magazijn`)
- [ ] Unit tests voor validatieregels en publicatie-flow
- [ ] Integratietest voor de keten: aanleveren → valideren → publiceren

**Dependencies:** Issue 1 · **Complexiteit:** M

---

### Issue 4: Berichten Uitvraag Service — nieuwe service module

**Labels:** `enhancement`, `uitvraag`

Maak een nieuwe Quarkus service module `services/berichtenuitvraag` (C4 container: `uitvraagApi`). Dit is de user-facing API die de Interactielaag aanroept namens burgers en ondernemers. Delegeert naar de berichtensessiecache voor ophalen/zoeken en naar het berichtenmagazijn voor bijlagen en beheer.

**C4 componenten:** `uitvraagResource`, `uitvraagBerichtenlijst`, `uitvraagOphaalService`, `uitvraagBeheerService`

**Endpoints:**
- `GET /api/v1/berichten` — berichtenlijst per map
- `GET /api/v1/berichten/{berichtId}` — bericht met inhoud
- `GET /api/v1/berichten/{berichtId}/bijlagen/{bijlageId}` — bijlage ophalen
- `PATCH /api/v1/berichten/{berichtId}` — berichtstatus bijwerken
- `DELETE /api/v1/berichten/{berichtId}` — bericht verwijderen
- `POST /api/v1/berichten/{berichtId}/verplaats` — verplaatsen naar map

**Acceptatiecriteria:**
- [ ] Maven module `services/berichtenuitvraag` in parent POM
- [ ] OpenAPI spec met bovengenoemde endpoints
- [ ] REST clients naar berichtensessiecache en berichtenmagazijn
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

Implementeer de Aanmeld Service (`aanmeldService`) en verbind de volledige notificatie-keten. Ontvangt CloudEvents van de Publicatie Stream bij nieuw gepubliceerde berichten en werkt de berichtensessiecache bij voor actieve sessies.

**Flow:** Publicatie Stream → CloudEvent via FSC → Aanmeld Service → REST API → Berichtensessiecache

De bestaande `EventForwarder.kt` stuurt al CloudEvents, maar de omgekeerde richting (magazijn → cache update) ontbreekt.

**Acceptatiecriteria:**
- [ ] Aanmeld Service als onderdeel van berichtenuitvraag module (of eigen module)
- [ ] CloudEvents webhook endpoint voor `nl.rijksoverheid.fbs.bericht.gepubliceerd`
- [ ] Cache bijwerken via berichtensessiecache REST API (alleen voor actieve sessies)
- [ ] Idempotentie: dubbele events niet opnieuw verwerken (event ID check)
- [ ] `EventForwarder.kt` refactoren naar gedeeld CloudEvents-patroon indien nuttig
- [ ] Unit tests en integratietest voor volledige flow

**Dependencies:** Issues 3 (Publicatie Stream), 4 (Berichtenuitvraag) · **Complexiteit:** M

---

### Issue 9: Autorisatie Service — PEP/PDP patroon

**Labels:** `enhancement`, `security`, `magazijn`

Implementeer de Autorisatie Service (`autorisatieService`) voor het Berichtenmagazijn en het PEP/PDP-patroon conform AuthZEN NL GOV.

Twee autorisatieniveaus uit het C4-model:
1. **Berichtenmagazijn:** Ophaal- en Beheer API als PEP, Autorisatie Service als PDP
2. **Berichten Uitvraag Systeem:** Token Validatie als PEP, MagazijnResolver als PEP/PDP

**Acceptatiecriteria:**
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
