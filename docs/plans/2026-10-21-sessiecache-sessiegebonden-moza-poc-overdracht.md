# Overdracht naar moza-poc: inlogsessies in de berichtenbox

**Status:** Concept

Opdracht voor een Claude-sessie in de repository MinBZK/moza-poc (lokaal
`/home/claude/projects/moza-poc`). Plak de sectie *Prompt* hieronder in een nieuwe sessie die daar
gestart is, zodat de `CLAUDE.md`, hooks en conventies van moza-poc gelden.

## Prompt

Maak in deze repository een PR die de berichtenbox laat werken met inlogsessies van de
FBS-berichtenuitvraag. De uitvraag-kant staat in MinBZK/moza-poc-fbs-berichtenbox PR #393
(issue MinBZK/MijnOverheidZakelijk#943). Beide PR's gaan samen live; achterwaartse
compatibiliteit is niet nodig.

**Contract** — de bron van waarheid is de spec op de branch van die PR:
`https://github.com/MinBZK/moza-poc-fbs-berichtenbox/blob/feature/943-sessiecache-sessiegebonden/services/berichtenuitvraag/src/main/resources/openapi/berichtenuitvraag-api.yaml`
(paden `/sessies`, `/sessies/huidige`, parameter `SessieHeader`, schema `Sessie`). Kort:

- `POST /api/v1/sessies` met `X-Ontvanger` → `201` met `{ sessieId, uiterlijkGeldigTot, _links }`.
  `429` (`urn:fbs:fout:te-veel-sessies`) bij meer dan 5 lopende sessies voor die ontvanger.
- Elk verzoek onder `/api/v1/berichten…` (lijst, detail, PATCH, DELETE, bijlage, `_ophalen`,
  `_volgen`, `_zoeken`) draagt naast `X-Ontvanger` ook `X-Sessie: <sessieId>`.
- `DELETE /api/v1/sessies/huidige` met `X-Ontvanger` + `X-Sessie` → `204` (idempotent).
- Ongeldige, verlopen of ontbrekende sessie → `401` (`urn:fbs:fout:sessie-ongeldig`).
- Een sessie verloopt na 15 minuten zonder verzoeken, en uiterlijk 12 uur na de start. Een open
  `_volgen`-verbinding telt als activiteit (de uitvraag stuurt zelf hartslagen). Bij het
  verlopen of beëindigen van de sessie sluit `_volgen` met `sessie-verlopen`.

**Gedrag in de berichtenbox** (de demo kent geen echte inlog; per tab geldt het kiezen van een
persona als inloggen):

1. **Persona kiezen** (ook de standaardpersona bij het laden) → `POST /sessies` voor die ontvanger.
   Bewaar `sessieId` in `sessionStorage` (per tab, overleeft herladen), onder een sleutel die de
   ontvanger bevat. Herladen met een bewaarde id start géén nieuwe sessie.
2. **Wisselen naar een andere persona** (`ruimOpBijWisseling` in `assets/javascript/personas.js`)
   → eerst `DELETE /sessies/huidige` voor de vórige persona, dan de id uit `sessionStorage` weg,
   dan stap 1 voor de nieuwe.
3. **"Uitloggen"** (`_includes/header-overheid.njk`, nu een gewone link naar `/moza/uitgelogd/`)
   → `DELETE /sessies/huidige` vóór de navigatie, en de id weg.
4. **Alle uitvraag-aanroepen** in `assets/javascript/berichtenbox-keten.js` krijgen `X-Sessie`.
   De bijlage-download gaat via de proxy in `server/keten-proxy.js`, die nu een cookie
   `ontvanger` naar een header vertaalt; die moet ook de sessie doorgeven (eigen cookie, zelfde
   eigenschappen als het bestaande cookie).
5. **`401`** op welk verzoek ook, of `sessie-verlopen` op `_volgen` → id weg, nieuwe sessie
   (stap 1) en opnieuw `_ophalen`; in de demo staat dat voor "opnieuw inloggen".
6. **Niet doen:** afmelden bij het sluiten van een tab (`pagehide`/`sendBeacon`). Een browser
   onderscheidt sluiten niet van herladen; een gesloten tab wacht de 15 minuten af.

Het sessie-id is een geheim: niet loggen, niet in een URL of querystring, niet in `localStorage`.

**Lokaal testen** tegen de uitvraag van die branch: in de FBS-repo de demo-stack starten met de
overlay `compose.proeftuin-versie.yaml` die naar jouw lokale/preview-image van de berichtenbox
wijst (zie `docs/demo-runbook.md` daar).

**PR:** als draft, met in de body een verwijzing naar MinBZK/moza-poc-fbs-berichtenbox#393 en
naar issue MinBZK/MijnOverheidZakelijk#943 (zonder `Closes`: dat issue sluit via de FBS-PR).
Geen reviewer toevoegen. Merge-volgorde bij uitrol: eerst deze PR, daarna zet de FBS-PR zijn
pin op het main-image dat deze merge oplevert en gaat door de merge queue.
