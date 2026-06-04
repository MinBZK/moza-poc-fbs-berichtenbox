# Aanmeld Service (#417)

**Status:** Concept

## Context

De Aanmeld Service ontvangt CloudEvents (`nl.rijksoverheid.fbs.bericht.gepubliceerd`)
van de Publicatie Stream van een magazijn en werkt daarmee de sessiecache bij voor
ontvangers met een actieve sessie. Doel: een nieuw gepubliceerd bericht verschijnt
direct in de berichtenlijst van een ingelogde ontvanger, zonder dat die opnieuw alle
magazijnen hoeft te bevragen.

Sinds #607 / PR #87 is de sessiecache een in-process library
(`libraries/fbs-berichtensessiecache`) achter de `Sessiecache`-CDI-facade. De Aanmeld
Service draait in dezelfde module (`berichtenuitvraag`) en schrijft daarom **in-sessie
bij via `Sessiecache.schrijfBericht(...)` — zonder netwerk-hop of mTLS**. Dit wijkt af
van het oorspronkelijke issue-criterium ("REST client naar sessiecache"); issue #417 is
hierop bijgewerkt.

## Architectuur

```
Publicatie Stream (magazijn)
  │  POST <downstream.url>   Content-Type: application/cloudevents+json  (structured mode)
  ▼
AanmeldResource (POST /api/v1/aanmeldingen, gegenereerd uit OpenAPI, tag Aanmeld)
  ▼
AanmeldService
  ├─ valideer NL GOV CloudEvents-profiel (specversion, source urn:nld:, type)
  ├─ idempotentie  → AanmeldDeduplicatie (Redis SET NX PX)
  ├─ afzender-OIN  → magazijnId           (AfzenderMagazijnIndex, hergebruikt afzenders-config)
  ├─ map BerichtData → sessiecache Bericht
  └─ Sessiecache.schrijfBericht(ontvanger, bericht)
       ├─ 404 geen sessie → 202 ack + skip (normaal geval; geen retry-storm)
       ├─ 503 cache down  → propageer 5xx (transient; zender retryt)
       └─ succes          → 202
```

## Componenten

### 1. OpenAPI-first (`berichtenuitvraag-api.yaml`)
- Nieuw pad `POST /api/v1/aanmeldingen`, tag `Aanmeld`.
- `requestBody`: `application/cloudevents+json`, schema `CloudEvent` (envelope, structured mode):
  `id`, `source`, `specversion`, `type`, `subject`, `time`, `datacontenttype`, `dataschema`, `data`.
  `data` = `BerichtData` (`berichtId`, `afzender`, `ontvanger{type,waarde}`, `onderwerp`,
  `inhoud`, `tijdstipOntvangst`, `publicatietijdstip`) — spiegelt het magazijn-contract.
- Responses: `202` (verwerkt/geskipt/duplicaat, leeg body), `400` (`application/problem+json`),
  `503` (`application/problem+json`).
- `pom.xml`: `apisToGenerate` → `Uitvraag,Aanmeld`.
- Bruno-request onder `bruno/berichtenuitvraag/aanmeld/`.

### 2. `aanmeld/AanmeldResource.kt`
Implementeert gegenereerde `AanmeldApi`. `@Logboek` (nieuwe activity
`UITVRAAG_AANMELDING`). Delegeert naar `AanmeldService`. Geen try/catch — fouten via
de fbs-common ExceptionMappers (`WebApplicationException` → Problem).

### 3. `aanmeld/AanmeldService.kt`
- **Validatie** (NL GOV): `specversion == "1.0"`; `source` start met `urn:nld:`;
  `type == "nl.rijksoverheid.fbs.bericht.gepubliceerd"`. Mismatch → 400 (malformed,
  geen retry). `subject` informatief (= berichtId), niet leidend.
- **Idempotentie**: `AanmeldDeduplicatie.eerstgezien(id)` → false bij duplicaat → 202 skip.
- **magazijnId**: `AfzenderMagazijnIndex.magazijnVoor(afzenderOin)`. 0 matches → 400
  (onbekende bron / config-drift). >1 → deterministisch (gesorteerd eerste) + log warn
  (in %dev/%test is afzender→magazijn 1:1; multi is theoretische config-edge).
- **Mapping** `BerichtData → Bericht`: `magazijnId` uit index; `aantalBijlagen = 0`,
  lege `bijlagen` (de payload draagt geen bijlage-metadata — die volgen bij de volgende
  volledige ophaling). Ontvanger `{type,waarde}` → `Identificatienummer.of(...)`.
- **Schrijven**: `Sessiecache.schrijfBericht(ontvanger, bericht)`.
  404 → vang en 202-ack (geen actieve sessie = normaal). 503 → propageer.

### 4. `aanmeld/AanmeldDeduplicatie.kt`
Redis-backed once-only marker, eigen keyspace `aanmeld:event:{id}` via de Quarkus
Redis-client (`SET key 1 NX PX <ttl>`; gezet → eerstgezien). Cross-instance correct;
in-memory zou bij horizontale schaal of herstart falen. TTL `aanmeld.deduplicatie.ttl`
(default `PT24H`, ruim boven het magazijn-retry-plafond `PT1H`).

Idempotentie is een webhook-concern, geen cache-concern: bewust een eigen component met
eigen keyspace, niet via de `Sessiecache`-facade (die encapsuleert de berichten-cache).

### 5. `aanmeld/AfzenderMagazijnIndex.kt` + config
Uitvraag-`MagazijnenConfig` uitbreiden met `instances(): Map<String, Instance>` met
`afzenders(): List<String>`. Bij start een reverse-map afzender-OIN → Set<magazijnId>.
Spiegelt bewust de library-routing (`magazijnen.instances.*.afzenders`); de
properties-config is het gedeelde contract.

## Foutsemantiek (webhook ↔ publicatie-stream)
| Situatie | HTTP | Retry door zender? |
|----------|------|--------------------|
| Verwerkt / duplicaat / geen sessie | 202 | nee |
| Malformed event / onbekende bron-OIN | 400 | nee (zinloos) |
| Cache (Redis) onbereikbaar | 503 | ja (transient) |

## Privacy / security
- `subject` = berichtId, nooit BSN/RSIN (NL GOV: geen PII in context-attributen).
- BSN/RSIN niet in logs (`.waarde`); afzender-OIN = publiek, mag voluit.
- TLS via gateway; `LdvEndpointValidator` dekt LDV-endpoint in prod/staging/acceptatie.
- Bericht-groottelimieten worden door `schrijfBericht`/`BerichtValidator` afgedwongen (→ 400).

## Tests
- **Unit** (JUnit5 + MockK): CloudEvent-validatie (happy + elke mismatch), `AfzenderMagazijnIndex`
  (0/1/>1 matches), ontvanger-parsing, `BerichtData → Bericht`-mapping, dedup-logica.
- **@QuarkusTest integratie**: echte Redis (Dev Services) voor dedup-idempotentie (dubbele
  POST → één `schrijfBericht`); `MockSessiecache` voor sessie/geen-sessie/503-takken;
  end-to-end webhook → cache.
- **OpenApiContractTest**: 202/400/503 tegen het Problem-schema.
- Geen WireMock nodig (geen uitgaande hop meer).

## Bewuste afwijkingen van het issue
1. Criterium 3 "REST client naar sessiecache" → in-process facade (PR #87). Issue bijgewerkt.
2. Bijlagen/magazijnId niet in de CloudEvent-payload → magazijnId via bestaande
   afzender→magazijn config-routing; bijlagen leeg tot volgende volledige ophaling.

## Verificatie
`./mvnw clean verify -pl services/berichtenuitvraag -am` (Docker vereist voor Redis Dev
Services). Detekt `maxIssues: 0`. JaCoCo ≥ 90% (integratietests dragen de coverage).
Spectral-lint op de bijgewerkte OpenAPI-spec.
