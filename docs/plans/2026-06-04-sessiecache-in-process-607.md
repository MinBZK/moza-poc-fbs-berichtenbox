---
Status: Concept
Issue: MinBZK/MijnOverheidZakelijk#607
---

# Berichtensessiecache als afgeschermd in-process onderdeel (#607)

## Context

`services/berichtensessiecache` draait nu als losse Quarkus-deployable. `services/berichtenuitvraag`
roept hem aan via `SessiecacheClient` + `SessiecacheSseClient` (REST/JSON) — een interne mTLS-hop
*binnen* de `berichtenUitvraagSysteem`-vertrouwensgrens. Die hop levert latency + overhead zonder
meerwaarde. Doel: sessiecache-core wordt een gedeelde library die in-process via één CDI-facade
wordt aangeroepen; de losse deployable verdwijnt. Redis blijft de (afgeschermde) backing store,
gedeeld, zodat de toekomstige Aanmeld Service (#417) in-sessie kan bijschrijven zonder netwerk-hop.

## Doelstructuur

```
libraries/fbs-berichtensessiecache/         (nieuw, analoog aan fbs-common)
  Sessiecache.kt            ← publieke facade-interface (de ENIGE public ingang)
  domein: Bericht, BerichtenPagina, MagazijnEvent  (public domain-types)
  internal: BerichtensessiecacheService(impl), BerichtenCache/RedisBerichtenCache,
            BerichtValidator, BerichtLimieten, MagazijnClient(Factory), MagazijnenConfig,
            MagazijnResolver/ProfielMagazijnResolver, Magazijn* DTO's, CacheCorruptedException
services/berichtenuitvraag/                 (consumer)
  injecteert `Sessiecache` via CDI; mapt domein → uitvraag-api-model
services/berichtensessiecache/              (VERWIJDERD als deployable)
```

## Facade-interface (domein-types, niet de OpenAPI-modellen)

De facade retourneert **domein-types** uit de library — niet de gegenereerde API-modellen van
sessiecache (vervallen) of uitvraag (blijft van de consumer). De JSON-bridge die nu de twee
type-werelden ontkoppelde verdwijnt; daarom mapt `berichtenuitvraag` voortaan domein → uitvraag-api.

```
interface Sessiecache {
    fun lijst(ontvanger, pagina, paginaGrootte): BerichtenPagina
    fun zoek(ontvanger, q, pagina, paginaGrootte, afzender, map): BerichtenPagina
    fun bericht(ontvanger, berichtId): Bericht?
    fun werkBerichtBij(ontvanger, berichtId, status?, map?): Bericht
    fun verwijder(ontvanger, berichtId)
    fun ophalen(ontvanger): Multi<MagazijnEvent>          // in-process i.p.v. SSE-hop
    fun schrijfBericht(ontvanger, bericht): Bericht       // aanmeld-write (#417)
}
```

`ontvanger` = `Identificatienummer` (fbs-common). De `_links`/paginatie-vertaling die
`BerichtenlijstService` nu op REST-`_links` doet, wordt in-process overbodig (de consumer bouwt
zijn eigen HAL-links uit `BerichtenPagina`).

## Stappen

1. **C4-model** (`docs/architecture/workspace.dsl`): sessiecache van `container "Service"` →
   afgeschermd in-process onderdeel binnen `berichtenUitvraagSysteem`; Redis genest binnen de
   modulegrens; interne `* -> sessiecacheResource "REST API (intern, mTLS)"`-relaties → in-process
   facade-aanroepen. **Aanmeld Service → sessiecache ook in-process** (vooruitlopend op #417).
2. **Aggregation-lock-TTL-fix**: aparte korte `berichtensessiecache.aggregation-lock-ttl`
   (default `PT2M`) i.p.v. de cache-`ttl` (`PT12H`) voor de SETNX-lock-key. Voorkomt dat een
   pod-crash midden in aggregatie een ontvanger tot 12u blokkeert.
3. **Library-module** `libraries/fbs-berichtensessiecache`: POM (jandex + quarkus.index-dependency
   in de consumer), Redis/RediSearch/Profiel-client deps. Geen OpenAPI-generator (geen eigen API).
4. **Core verplaatsen** naar de library, package `nl.rijksoverheid.moz.fbs.sessiecache.*`. Alles
   behalve de facade + domein-types `internal`. JAX-RS-resources + API-version-provider + OpenAPI-
   spec **niet** mee (vervallen).
5. **`berichtenuitvraag` herbedraden**: `Sessiecache`-facade injecteren; `SessiecacheClient`,
   `SessiecacheSseClient`, `SsePassthroughResource` verwijderen; `BerichtenlijstService`,
   `BerichtOphaalService`, `BerichtBeheerService`, `UitvraagDtoMapper` mappen domein → uitvraag-api;
   SSE wordt een directe `Multi<MagazijnEvent>` → SSE-respons.
6. **Deployable verwijderen**: module `services/berichtensessiecache`, OpenAPI-spec, JAX-RS-
   resources, Bruno-collectie, `compose.yaml`-entry, parent-POM `<module>`.
7. **Config verhuizen**: `berichtensessiecache.*`, `magazijnen.instances.*`, `profiel.resolver.*`,
   `quarkus.redis.*`, profiel-service-url → `berichtenuitvraag` (+ test-profielen/Redis-devservices).
8. **Guard**: test die borgt dat de facade-impl staatloos is (geen nieuwe in-JVM mutable state;
   alle staat in Redis).
9. **Tests verplaatsen**: pure-unit + data-class-tests naar de library; @QuarkusTest-integratie
   (Redis/WireMock) naar de consumer (`berichtenuitvraag`) of de library-testset met Testcontainers.

## Verificatie

- Per fase: `./mvnw -q clean test-compile` (sandbox) — vangt type-/visibility-/wiring-fouten.
- Pure-unit + data-class-tests: sandbox.
- Redis/RediSearch/WireMock-integratie (Testcontainers): **alleen CI** (sandbox kan geen sibling-
  containers starten). De PR-CI draait de volledige suite.

## Security / robuustheid

- Vervallende mTLS-hops liggen volledig binnen één vertrouwensgrens (`berichtenUitvraagSysteem`).
  Geen cross-organisatie-TLS verwijderd; externe verbindingen blijven FSC/mTLS. Geen BIO-regressie.
- Alle staat blijft in Redis (gedeelde store) → multi-pod-robuust; aggregatie-lock (SETNX),
  ophalen-status (SETEX), sliding-TTL (EXPIRE) blijven het gedeelde coördinatiepunt.

## Out of scope (apart)

- Dedupliceren van de twee magazijn-clients (sessiecache-aggregatie vs uitvraag-bijlage/status).
