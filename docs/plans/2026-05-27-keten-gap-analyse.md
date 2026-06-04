**Status:** Concept (analyse, geen plan)

> **Update 2026-05-27:** P1-items 1-4 zijn afgerond in PR voor branch
> `feature/keten-p1-fixes` (zie `docs/plans/2026-05-27-keten-p1-fixes.md`).
> P2- en P3-items staan open.

# Gap-analyse: keten uitvraag → sessiecache → magazijn

Onderzoek naar de drie OpenAPI-specs om vast te stellen wat er nodig is om de uitvraag-service correct te bedienen. Geen implementatie — basis voor vervolg-PRs.

## TL;DR

Vier hoofd-blokkers en ~15 inconsistenties. De keten "uitvraag → sessiecache → magazijn" werkt nu **niet** voor alle 6 uitvraag-endpoints. Belangrijkste pijnpunten:

1. **Sessiecache mist DELETE/invalidate-endpoint** — uitvraag's DELETE-flow heeft geen cache-tegenhanger.
2. **Sessiecache levert geen `inhoud`/`bijlagen[]`** — uitvraag's detail-GET kan niet via cache.
3. **Sessiecache PATCH eist required `status`** — uitvraag `{map: "archief"}` (alleen verplaatsen) faalt.
4. **Magazijn PATCH gebruikt `{gelezen: boolean}` ↔ uitvraag/cache `{status: enum}`** — bidirectionele transformatie nergens gespecificeerd.

## Per-endpoint matrix

| # | Uitvraag-endpoint | Downstream | Status | Pijnpunt |
|---|---|---|---|---|
| 1 | `GET /berichten?map=&pagina=` | cache `GET /berichten` | Mismatch | `map` niet in cache; paginatie-paramnamen verschillen (`pagina/paginaGrootte` vs `page/pageSize`); response-envelope HAL-only vs paginatie-tellers |
| 2 | `GET /berichten/_ophalen` (SSE) | cache `_ophalen` | OK met kanttekening | Endpoint bestaat; 409 (al bezig) niet doorgegeven |
| 3 | `GET /berichten/_zoeken?q=&map=` | cache `_zoeken` | Mismatch | `q.minLength` 1 vs 2; `map` niet in cache |
| 4 | `GET /berichten/{id}` | cache → val terug op magazijn? | Significant | Cache levert geen `inhoud`/`bijlagen[]`/`map` |
| 5 | `GET /berichten/{id}/bijlagen/{id}` | magazijn direct | OK | 403/406 niet doorgegeven |
| 6a | `PATCH /berichten/{id}` | magazijn + cache | Blokkerend | Body-formaat 3× verschillend; cache eist required `status`; response-shape verschilt |
| 6b | `DELETE /berichten/{id}` | magazijn + cache-invalidate | **Ontbreekt** | Cache heeft geen DELETE-endpoint |

## Schema-vergelijking (kernverschillen)

### Bericht / BerichtSamenvatting

| Veld | Uitvraag | Sessiecache | Magazijn |
|---|---|---|---|
| `inhoud` | optioneel | **ontbreekt** | required |
| `bijlagen[]` | optioneel | **ontbreekt** | required |
| `tijdstipOntvangst` | — | — | required |
| `publicatietijdstip` | required | required | required |
| `magazijnId` | — | required | — |
| `map` (top-level) | optioneel | **ontbreekt** | binnen `status.map` |
| `status` | enum nullable | enum nullable | `BerichtStatusInfo{gelezen:bool, gewijzigdOp, map?}` |
| `ontvanger` | (uit header) | string | object `{type, waarde}` |
| `afzender` | string | string ("OIN") | string (OIN) |
| `aantalBijlagen` | required | required (na recente fix) | required |

### Patch-body

| | Uitvraag | Sessiecache | Magazijn |
|---|---|---|---|
| Type | `application/merge-patch+json` | `application/merge-patch+json` | `application/merge-patch+json` |
| `status` | optioneel enum | **required** enum | — |
| `gelezen` | — | — | optioneel boolean |
| `map` | optioneel string | **ontbreekt** | optioneel string |
| `minProperties` | 1 | n.v.t. | n.v.t. |

### Bijlage

| Veld | Uitvraag | Magazijn |
|---|---|---|
| `grootteInBytes` | optioneel | **ontbreekt** |
| `mimeType` | optioneel | required |

### Paginatie

| | Uitvraag | Sessiecache | Magazijn |
|---|---|---|---|
| Query-paramnamen | `pagina`, `paginaGrootte` | `page`, `pageSize` | `page`, `pageSize` |
| Max pageSize | 200 | 100 | 100 |
| Response-tellers | geen (HAL only) | `totalElements`, `totalPages` | `totalElements`, `totalPages` |

## Actie-items

### P1 — blokkeert lokale werking

1. **Sessiecache: voeg `DELETE /berichten/{id}` (of `_invalideer`) toe** — zonder dit kan uitvraag's DELETE-flow zijn cache-invalidate niet doen.
2. **Sessiecache `BerichtResponse`: voeg `inhoud` + `bijlagen[]` toe** óf maak uitvraag's detail-GET een direct doorroute naar magazijn (en spec dat).
3. **Sessiecache PATCH-body**: `status` optioneel, `map` toegevoegd; anders kan uitvraag `{map: "archief"}` niet door-PATCHen.
4. **PATCH bidirectionele mapping documenteren**: uitvraag/cache `{status: enum}` ↔ magazijn `{gelezen: boolean}`. Mapping moet expliciet in spec of plan, niet impliciet in code.

### P2 — functionele inconsistenties

5. Paginatie-paramnamen uitlijnen (`page/pageSize` of `pagina/paginaGrootte`); max op 100 in uitvraag (matched downstream).
6. Zoek-`q.minLength` uitlijnen (1 of 2).
7. `map`-concept formaliseren in sessiecache, of in uitvraag-spec verantwoorden waar het vandaan komt.
8. Lijst-response-envelope kiezen (HAL only vs tellers) en consistent gebruiken.
9. Error-codes doorgeven (409 op `_ophalen`/`_zoeken`/detail-GET; 403 op detail/PATCH/DELETE/bijlage).
10. `BijlageMetadata.grootteInBytes`: toevoegen aan magazijn of weghalen uit uitvraag.
11. `ontvanger` op uitvraag-responses: ofwel expliciet weglaten met motivatie, ofwel als string opnemen.

### P3 — polish

12. Sessiecache: introduceer aparte `BerichtSamenvatting` (lichter dan `BerichtResponse`).
13. `BerichtStatus` overal als rijk object (`{gelezen, map, gewijzigdOp}`) — overweging.
14. `Problem`: `type/title/status` als required op uitvraag en cache (zoals magazijn).
15. SSE-event-schema in uitvraag verwijzen naar (gedeelde) `MagazijnEvent` of expliciet "opaque passthrough" documenteren.
16. `API-Version`-headerwaarde harmoniseren over de drie specs.
17. `X-Ontvanger`-regex in uitvraag aanscherpen (lengte per type, zoals magazijn).
18. `info.version`-velden uitlijnen.

## Niet-besproken aandachtspunten

- Aanmeldservice → cache `POST /berichten`: relevant voor totaalplaatje maar buiten scope.
- Auth: alle drie X-Ontvanger; geen JWT-laag; magazijn declareert `bearerAuth`-scheme dat ongebruikt is. AuthZEN/PEP staat open (Issue 10).
- `traceparent`/correlation-id: niet expliciet in specs.
- `magazijnId` in cache exposed maar niet in uitvraag — productkeuze.
- Sessiecache `_links.inhoud` wijst naar niet-bestaand endpoint.
- SSE-generator-limiet (`Multi<T>` niet door jaxrs-spec ondersteund) — net opgelost in uitvraag, sessiecache heeft dezelfde workaround al.

## Bron

Onderzoeker: gap-analyse-agent (subagent), 2026-05-27. Alle file:line-verwijzingen in detail-bijlage opvraagbaar (niet in dit document opgenomen om scope-bondig te houden).
