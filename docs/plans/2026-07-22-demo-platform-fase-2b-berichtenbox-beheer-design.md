**Status:** Concept

# Demo-platform fase 2b — Berichtenbox-UI (beheer) — ontwerp

Vervolg op fase 2a (lezen). Voegt het beheer-deel toe aan de wegwerp-Berichtenbox-UI:
verwijderen, sorteren, filteren, mappen en archiveren. Alles client-side of via de
bestaande `PATCH`/`DELETE` — **geen backend-wijziging**.

## Uitgangspunten (vastgesteld in overleg)

| Vraag | Keuze |
|---|---|
| Mappen-model | Mappen = de `map`-waarden die op berichten voorkomen; geen browser-opslag, geen lege mappen |
| Scope 2b | verwijderen, sorteren, filteren, mappen, archiveren |
| Zoeken | Buiten 2b (kleine losse toevoeging via `GET /_zoeken`) |
| Rode vlag | Fase 7 (echte backend-wijziging) |

## Architectuur

Uitbreiding van de bestaande drie bestanden in demo-console
(`berichtenbox.html/js/css`). Geen nieuwe module, geen backend-call die nog niet bestaat.

```
┌──────────────┬─────────────────────────────┐
│ Postvak IN 12│  [sorteer ▾]  [☐ ongelezen] │
│ Werk        3│  ─────────────────────────  │
│ Archief      │  • bericht                  │
│              │  • bericht                  │
└──────────────┴─────────────────────────────┘
        detail (met acties) onder/naast de lijst
```

## Mappen

De maplijst wordt afgeleid uit de `map`-waarden op de geladen berichten
(`BerichtSamenvatting.map`, optioneel veld):

- **Postvak IN** — berichten zonder `map`.
- Elke overige distinct `map`-waarde — een map, met telling.
- **Archief** — de gereserveerde `map`-waarde `"Archief"`; standaard niet in Postvak IN.

Een map "aanmaken" = een bericht naar een nieuw getypte mapnaam verplaatsen (`PATCH {map}`).
Lege mappen bestaan dus niet — een bewuste, eerlijke consequentie.

## Sorteren

Dropdown, puur client-side op de geladen lijst (geen server-sorteerparameter):

- Datum nieuw → oud (default)
- Datum oud → nieuw
- Onderwerp A-Z
- Afzender A-Z (op de weergegeven organisatienaam)

## Filteren

Client-side:

- **Mapselectie** in de zijbalk is de hoofdfilter.
- **"Alleen ongelezen"**-schakelaar (`status !== 'gelezen'`).

Bij de filterbalk een klein "demo"-label: dit toont dat de interactie werkt, niet dat het
stelsel op schaal filtert (issue #571 — sessiecache indexeert `map` niet).

## Acties (in het geopende bericht)

Naast "markeer gelezen/ongelezen" uit fase 2a:

| Actie | Mechanisme |
|---|---|
| Verplaats naar map | dropdown bestaande mappen + "nieuwe map…" tekstveld → `PATCH {map}` |
| Archiveer | `PATCH {map: "Archief"}` |
| Verwijder | `DELETE` met bevestiging |

Alle drie gebruiken `?magazijnId=` (per bericht bewaard uit fase 2a) en, voor `PATCH`,
content-type `application/merge-patch+json`.

## Dataflow

1. Ophalen (fase 2a) vult de sessiecache; `GET /berichten` levert de lijst.
2. Sorteren/filteren: puur her-renderen van de geladen array — geen server-call.
3. Beheer-actie (`PATCH`/`DELETE`): daarna opnieuw `GET /berichten` en her-renderen, zodat
   maptelling en status kloppen.

**Aanname (te verifiëren):** `PATCH map` en `DELETE` komen meteen in `GET /berichten` door
zonder opnieuw op te halen. Bij "markeer gelezen" (2a, `PATCH status`) werkte dat, dus
waarschijnlijk ja. Blijkt van niet, dan roept een beheer-actie eerst `_ophalen` opnieuw aan.

## Componenten (JS-functies)

Uitbreiding van `berichtenbox.js`, klein en benoembaar gehouden:

- `renderMappen(berichten)` — bouwt de zijbalk uit de `map`-waarden + tellingen.
- `kiesMap(map)` — zet de actieve map en her-rendert de lijst.
- `zichtbareBerichten()` — past mapfilter + ongelezen-filter + sortering toe op de geladen array.
- `verplaats(berichtId, map)` / `archiveer(berichtId)` / `verwijder(berichtId)` — de acties.

De geladen berichten worden in het geheugen gehouden (een module-array), zodat sorteren en
filteren geen nieuwe `GET` vergen. Alleen beheer-acties en Ophalen verversen die array.

## Foutafhandeling

- `DELETE`/`PATCH` faalt (4xx/5xx) → zichtbare melding met HTTP-status, lijst blijft staan.
- Verwijderen vraagt eerst een bevestiging.
- Lege map na selectie (kan gebeuren nadat het laatste bericht is verplaatst) → terug naar
  Postvak IN.

## Teststrategie

Zoals fase 2a: de browser-JS is niet zinnig unit-testbaar zonder browser. Lokaal toetsbaar:
`node --check` op de JS, demo-console bouwt. De beheer-flow zelf is handmatig tegen de
draaiende stack. Geen nieuwe Kotlin, dus detekt onveranderd.

## Bewust buiten scope

Zoeken (`GET /_zoeken`), de rode vlag (fase 7), storingsscenario's (fase 3). Server-side
sorteren/filteren blijft issue #571 — de demo werkt er client-side omheen.

## Verificatie

| Stap | Verificatie |
|---|---|
| Zijbalk | Postvak IN + mappen met tellingen; klikken filtert de lijst |
| Sorteren | Volgorde wijzigt per optie, zonder nieuwe server-call |
| Ongelezen-filter | Toont alleen ongelezen berichten |
| Verplaatsen | Bericht verschijnt in de doelmap, verdwijnt uit Postvak IN; nieuwe map ontstaat |
| Archiveren | Bericht gaat naar Archief, weg uit Postvak IN |
| Verwijderen | Na bevestiging weg uit de lijst; blijft weg na verversen |
| Regressie | `node --check` groen; `./mvnw clean test -pl services/demo-console` groen |
