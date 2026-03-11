# Plan: Multi-magazijn aggregatie met SSE-status, Redis cache en lokale paginering

**Status:** Uitgevoerd
**Datum:** 2026-03-11

## Context

De Berichtenlijst-service aggregeert berichten uit decentrale magazijnen. Vijf problemen opgelost:

1. **In-memory cache schaalt niet** → Redis cache
2. **Slechts één magazijn** → Dynamische multi-magazijn configuratie
3. **Fouten worden ingeslikt** → SSE-stream toont FOUT/TIMEOUT per magazijn
4. **Geen parallelle bevraging** → Concurrent ophalen via Mutiny
5. **Geen zichtbaarheid** → Live SSE-status per magazijn

## UX-flow

1. **Ophalen met live status (SSE):** `GET /berichten/ophalen?ontvanger=...` → SSE-stream met BEZIG/OK/FOUT/TIMEOUT events per magazijn, afgesloten met `ophalen-gereed`
2. **Pagineren over cache:** `GET /berichten?page=0&pageSize=20` → gepagineerde resultaten uit Redis cache

## Geïmplementeerde wijzigingen

| Actie | Bestand | Beschrijving |
|-------|---------|-------------|
| Nieuw | `magazijn/MagazijnenConfig.kt` | ConfigMapping voor lijst magazijnen |
| Nieuw | `magazijn/MagazijnClientFactory.kt` | Dynamische REST client creatie met caching |
| Nieuw | `magazijn/MagazijnResult.kt` | Sealed class succes/fout per magazijn |
| Nieuw | `berichten/BerichtenCache.kt` | Interface + Redis implementatie (List + metadata) |
| Nieuw | `berichten/BerichtenOphalenResource.kt` | SSE endpoint voor ophaalstatus |
| Nieuw | `berichten/MagazijnStatusEvent.kt` | Data class voor SSE events |
| Wijzig | `magazijn/MagazijnClient.kt` | Verwijder `@RegisterRestClient`, verwijder paginering params |
| Wijzig | `berichten/BerichtenlijstService.kt` | Lees uit cache i.p.v. magazijn-calls |
| Wijzig | `berichten/BerichtenlijstResource.kt` | Uni-based, aggregatiestatus in response |
| Wijzig | `openapi/berichtenlijst-api.yaml` | AggregationStatus, MagazijnStatusEvent schemas |
| Wijzig | `application.properties` | Magazijnen-config, Redis |
| Wijzig | `pom.xml` (module) | `quarkus-redis-client` i.p.v. `quarkus-cache` |
| Nieuw | `test/MockMagazijnClientFactory.kt` | JDK Proxy-based mock |
| Nieuw | `test/MockBerichtenCache.kt` | In-memory cache mock |
| Nieuw | `test/BerichtenOphalenResourceTest.kt` | SSE tests |
| Wijzig | `test/BerichtenlijstResourceTest.kt` | Cache-based paginering tests |
| Verwijder | `test/MockMagazijnClient.kt` | Niet meer nodig |

## Ontwerpkeuzes

- **BerichtenCache als interface:** maakt testen zonder Redis mogelijk (MockBerichtenCache)
- **JDK Proxy voor mock MagazijnClient:** voorkomt dat Quarkus de mock als JAX-RS endpoint registreert
- **SSE endpoint buiten OpenAPI generator:** handmatig geïmplementeerd omdat `text/event-stream` niet goed werkt met de `jaxrs-spec` generator
- **Redis List (RPUSH/LRANGE):** efficiënte paginering over gesorteerde berichten
- **Mutiny Multi voor SSE:** native Quarkus RESTEasy Reactive ondersteuning

## TODO

- Ontvanger uit security-context halen (zie `x-todo` in OpenAPI spec)
- Testcontainers/Redis DevServices configureren voor Rancher Desktop Docker socket
