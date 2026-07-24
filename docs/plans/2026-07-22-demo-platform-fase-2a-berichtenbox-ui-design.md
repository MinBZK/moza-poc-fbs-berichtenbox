**Status:** Concept

# Demo-platform fase 2a — Berichtenbox-UI (lezen) — ontwerp

Onderdeel van het demo-platform (`docs/plans/2026-07-21-demo-platform-design.md`, fase 2).
Fase 2 is gesplitst: **2a = lezen** (dit document), **2b = beheer** (verwijderen, sorteren,
filteren, mappen, archief) volgt apart.

## Doel

Een wegwerp demo-frontend waarmee "de ondernemer inlogt en zijn berichten ziet": de
happy-flow-demo voor de Product Owner. Toont de eerste ondernemer-flow uit het
overkoepelende ontwerp.

## Uitgangspunten (vastgesteld in overleg)

| Vraag | Keuze |
|---|---|
| Waar draait de UI | Statische pagina op demo-console (:8095), naast het bedieningspaneel |
| Cross-origin | Dev-only CORS op berichtenuitvraag; geen proxy |
| Techniek | Vanilla HTML/JS, geen framework (zoals het bedieningspaneel) |
| "Login" | Persona-kiezer (dropdown) die de `X-Ontvanger`-header zet |
| Scope 2a | persona, ophalen, lijst, detail, bijlage-download, gelezen/ongelezen |

## Architectuur

```
browser (host)
  └─ http://localhost:8095/berichtenbox.html   (demo-console serveert statisch)
        │  fetch met X-Ontvanger-header
        ▼
     http://localhost:8086/api/v1/...           (berichtenuitvraag, dev-CORS aan)
```

De browser draait op de host, dus `localhost:8086` klopt zowel bij `quarkus:dev` als in
de container (gepubliceerde poort). De uitvraag-base-URL staat als constante boven in de JS.

### CORS — dev-only op berichtenuitvraag

Er is nu geen CORS-config. Toevoegen onder `%dev` in
`services/berichtenuitvraag/src/main/resources/application.properties`:

```properties
%dev.quarkus.http.cors=true
%dev.quarkus.http.cors.origins=http://localhost:8095
%dev.quarkus.http.cors.methods=GET,POST,PATCH,DELETE,OPTIONS
%dev.quarkus.http.cors.headers=X-Ontvanger,Content-Type
```

De `X-Ontvanger`-header is een niet-simpele header en lokt een preflight `OPTIONS` uit;
Quarkus' CORS-filter beantwoordt die. `%prod`/`%staging`/`%acceptatie` krijgen niets, dus
productie- en ZAD-gedrag blijven ongemoeid. De demo draait met `QUARKUS_PROFILE=dev`, dus
de config is actief in de demo-stack.

## De ophaal-flow

De uitvraag-API dwingt een volgorde af: de leespaden lezen uit de sessiecache, en die is
leeg tot `_ophalen` is aangeroepen. `GET /berichten` vóór ophalen geeft **409**.

```
persona kiezen  →  X-Ontvanger = bv. "KVK:12345678"
      │
"Ophalen"  →  fetch(GET /berichten/_ophalen)  →  stream lezen tot terminaal event
      │        (per magazijn: magazijn-bevraging-gestart → -voltooid;
      │         afsluitend: ophalen-gereed  of  ophalen-fout)
      ▼
GET /berichten  →  BerichtenLijst tonen
      │
klik bericht  →  GET /berichten/{berichtId}  →  detail (onderwerp, inhoud, bijlagen)
      │
      ├─ bijlage  →  GET /berichten/{berichtId}/bijlagen/{bijlageId}  (download, attachment)
      └─ gelezen  →  PATCH /berichten/{berichtId}?magazijnId=<uit lijst>
                     content-type application/merge-patch+json, body {"status":"gelezen"}
```

### `_ophalen` via `fetch`, niet `EventSource`

De browser-`EventSource`-API kan geen custom headers zetten, maar `_ophalen` vereist
`X-Ontvanger`. De stream wordt daarom met `fetch()` opgehaald en handmatig geparsed:
`response.body.getReader()` + `TextDecoder`, frames splitsen op `\n\n`, per frame de
`data:`-regel als JSON lezen (één `MagazijnEvent`). De stream is eindig en sluit na het
terminale event.

`MagazijnEvent`-velden die de UI gebruikt: `event` (`magazijn-bevraging-gestart` /
`-voltooid` / `ophalen-gereed` / `ophalen-fout`), `naam`, `status` (`OK`/`FOUT`/`TIMEOUT`),
`aantalBerichten`, `totaalBerichten`, `mislukt`. De voortgang wordt als eenvoudige regels
getoond ("Magazijn A: 12 berichten", "Magazijn B: fout"), zodat fase 3 (trage/uitgevallen
magazijnen) hierop kan voortbouwen zonder de flow te herschrijven.

## Contract-details die de UI moet respecteren

| Detail | Consequentie |
|---|---|
| `X-Ontvanger` pattern `^(BSN\|RSIN\|KVK\|OIN):[0-9]+$` | Persona-waarden: `BSN:999993653`, `BSN:123456782`, `KVK:12345678` |
| `magazijnId` zit in elke lijst/detail-response | Per bericht bewaren |
| PATCH/DELETE vereisen `?magazijnId=` | Bij markeren gelezen meesturen; detail/bijlage niet |
| `status` is `gelezen`/`ongelezen`/`null` (nullable) | Afwezig = nooit geopend; defensief tonen |
| Bijlage-HAL-link wordt niet gevuld | Download-URL zelf samenstellen: `/api/v1/berichten/{berichtId}/bijlagen/{bijlageId}` |
| Bijlage-download vereist `X-Ontvanger`-header | Geen `<a href>` (stuurt de header niet); `fetch` → `blob` → programmatische download, net als bij `_ophalen` |
| `mimeType`/`grootteInBytes` ontbreken uit de cache | Alleen `bijlageId` + `naam` tonen; MIME komt bij de download |
| `serialization-inclusion=non_null` | Optionele velden kunnen ontbreken; defensief lezen |
| 409 op `GET /berichten` | "Nog niet opgehaald — klik Ophalen"; geen crash |

## Componenten

Alles in demo-console, als statische resources (geen Kotlin):

| Bestand | Verantwoordelijkheid |
|---|---|
| `META-INF/resources/berichtenbox.html` | markup + persona-kiezer + lijst/detail-lay-out |
| `META-INF/resources/berichtenbox.js` | API-client, ophaal-stream-parser, rendering, acties |
| `META-INF/resources/berichtenbox.css` | kale styling (leesbaar, geen designsysteem) |

Splitsing in drie bestanden houdt de JS apart van de markup; de `index.html` (paneel)
blijft ongemoeid. Beide pagina's linken naar elkaar.

De JS is bewust in kleine, benoembare functies opgedeeld: `zetPersona`, `ophalen`
(stream-consumptie), `laadLijst`, `toonDetail`, `downloadBijlage`, `markeerGelezen`. Elk
doet één ding en is los te lezen.

## Foutafhandeling

- **409 op de lijst:** melding "Nog niet opgehaald", knop Ophalen benadrukken.
- **`ophalen-fout` of per-magazijn `FOUT`/`TIMEOUT`:** tonen in de voortgangsregels; de
  lijst laadt alsnog met wat er wél is (partial success — de basis voor fase 3).
- **Netwerk/CORS-fout:** zichtbare foutregel met de HTTP-status, geen stille mislukking.
- **Lege lijst:** expliciete "Geen berichten"-tekst, niet een leeg scherm.

## Teststrategie

De UI is vanilla browser-JS; er is geen bestaande JS-testinfra in dit repo en die optuigen
voor een wegwerp-pagina is niet gerechtvaardigd. Verificatie is daarom:

- **Lokaal toetsbaar (zonder Docker):** de CORS-config via Quarkus-augmentatie op
  berichtenuitvraag; `./mvnw clean test` blijft groen (bewijst dat `%test`/`%prod`
  ongemoeid zijn); demo-console bouwt met de nieuwe resources.
- **Handmatig tegen de draaiende stack (Docker):** de volledige flow per persona —
  ophalen, lijst, detail, bijlage-download, gelezen/ongelezen. Inclusief het 409-pad
  (lijst vóór ophalen) en een lege postbus.

Deze eerlijke splitsing volgt fase 0 en 1: claim niet "werkt" op basis van augmentatie
alleen; de browser-flow is pas bewezen na een handmatige doorloop.

## Bewust buiten scope (2a)

Verwijderen, sorteren, client-side filteren, eigen mappen, archiveren en de rode vlag —
dat is fase 2b (beheer) respectievelijk fase 7 (vlag). Ook: echte authenticatie (de
persona-kiezer ís de "login"), en styling met NL Design System (wegwerp).

## Verificatie

| Stap | Verificatie |
|---|---|
| CORS-config | `./mvnw clean package` (augmentatie) groen; `git diff` raakt geen `%test`/`%prod` |
| Resources | demo-console-image bevat `berichtenbox.html/js/css` |
| Flow (Docker) | Per persona: ophalen → lijst gevuld → detail → bijlage-download → gelezen-status wijzigt |
| Foutpaden (Docker) | Lijst vóór ophalen toont 409-melding; lege postbus toont "Geen berichten" |
| Regressie | `./mvnw clean test` groen; detekt niet geraakt (geen nieuwe Kotlin) |
