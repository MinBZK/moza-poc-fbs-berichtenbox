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
(paden `/sessies`, `/sessies/huidige`, `/sessies/huidige/_verlengen`, securityschema `sessie`,
schema's `Sessie` en `SessieGeldigheid`). Kort (zie ook *Wijzigingen na de eerste versie*):

- `POST /api/v1/sessies` met `X-Ontvanger` → `201` met `{ sessieId, geldigTot, uiterlijkGeldigTot, _links }`.
  `429` (`urn:fbs:fout:te-veel-sessies`, met `Retry-After`) bij meer dan 5 lopende sessies voor die
  ontvanger.
- Elk verzoek onder `/api/v1/berichten…` (lijst, detail, PATCH, DELETE, bijlage, `_ophalen`,
  `_volgen`, `_zoeken`) draagt naast `X-Ontvanger` ook `X-Sessie: <sessieId>`.
- `DELETE /api/v1/sessies/huidige` met `X-Ontvanger` + `X-Sessie` → `204` (idempotent).
- Ongeldige, verlopen of ontbrekende sessie → `401` (`urn:fbs:fout:sessie-ongeldig`).
- Een sessie verloopt na 15 minuten zonder verzoeken of verlenging, en uiterlijk 12 uur na de
  start (of eerder, op het plafond dat de berichtenbox bij de start meegaf). Een open
  `_volgen`-verbinding telt **niet** als activiteit. Bij het verlopen of beëindigen van de sessie
  sluit `_volgen` met `sessie-verlopen`.

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
5. **`401`** op welk verzoek ook, of `sessie-verlopen` op `_volgen` → id weg en de gebruiker een
   duidelijke melding geven ("uw sessie is verlopen"); bij zijn volgende actie een nieuwe sessie
   (stap 1) en opnieuw `_ophalen`. In de demo staat dat voor "opnieuw inloggen". Niet de stream
   vanzelf herstarten: zie *Wijzigingen na de eerste versie*.
6. **Niet doen:** afmelden bij het sluiten van een tab (`pagehide`/`sendBeacon`). Een browser
   onderscheidt sluiten niet van herladen; een gesloten tab wacht de 15 minuten af.

Het sessie-id is een geheim: niet loggen, niet in een URL of querystring, niet in `localStorage`.

### Wijzigingen na de eerste versie

Nadat de eerste versie van deze opdracht is uitgegeven, is het contract op de FBS-branch op de
volgende punten veranderd. Loop ze na als je al met de eerste versie bent begonnen.

**Het portaal (de berichtenbox) is de klok.** Besluit van de opdrachtgever: het portaal opent straks
sessies naar meer diensten dan FBS, en als elke dienst zijn eigen inactiviteitstimer heeft, logt de
gebruiker op verschillende momenten uit. Daarom bepaalt de berichtenbox wanneer de gebruiker
inactief of uitgelogd is; de timers van FBS zijn alleen een vangnet. Concreet:

- **Verlengen bij activiteit:** roep `POST /api/v1/sessies/huidige/_verlengen` (met `X-Ontvanger` en
  `X-Sessie`, zonder body) aan bij activiteit van de gebruiker: een klik, een toets, een navigatie.
  Hooguit eens per minuut (throttle), **niet** op een timer en **niet** omdat er een stream openstaat.
  Antwoord `200` met `{ geldigTot, uiterlijkGeldigTot, _links }`; `401 sessie-ongeldig` als de sessie
  niet meer loopt (verlengen maakt haar niet opnieuw geldig). Gewone uitvraag-verzoeken verlengen
  zelf ook; die hoef je niet dubbel te verlengen.
- **Uitloggen en time-out van het portaal:** `DELETE /api/v1/sessies/huidige` bij uitloggen (al
  in stap 3) én bij het verlopen van de eigen portaalsessie.
- **Plafond meegeven:** `POST /api/v1/sessies` krijgt een optionele JSON-body
  `{ "uiterlijkGeldigTot": "<date-time>" }`: het plafond van de portaalsessie. FBS neemt het vroegste
  van dat moment en start + 12 uur. Een moment dat niet in de toekomst ligt, of een onleesbare body,
  geeft `400`. Zonder body: verstuur dan ook geen `Content-Type` (of `application/json`); een ander
  mediatype geeft `415`.

**`_volgen` houdt de sessie niet meer levend** (expliciet gevraagd door de opdrachtgever). De hartslag
controleert alleen nog of de sessie loopt, en het openen van de stream verlengt haar evenmin.
Waarom: inactiviteit is inactiviteit van de gebruiker (ASVS V3.3.2, BIO). Een open tabblad op een
onbeheerde werkplek hield de berichtgegevens anders tot het plafond van 12 uur bereikbaar. Wat de
berichtenbox moet doen:

- Niet op de stream vertrouwen om ingelogd te blijven: alleen activiteit van de gebruiker (via
  `_verlengen` of een gewoon verzoek) houdt de sessie in leven.
- Eindigt de stream met `sessie-verlopen`, of krijgt een verzoek `401 sessie-ongeldig`: de
  gebruiker een duidelijke melding geven en pas bij zijn volgende actie een nieuwe sessie starten.
  **Geen automatische herstart van de stream** na `sessie-verlopen`: die zou een nieuwe sessie
  openen en zo toch levend houden wat net verlopen is. Eindigt de stream zónder `sessie-verlopen`
  (maximale duur, herstart van de dienst), dan wel gewoon opnieuw verbinden.

**Overige contractwijzigingen:**

- Het `201`-antwoord van `POST /sessies` heet anders: `geldigTot` is het verloop bij inactiviteit
  (wat eerst `uiterlijkGeldigTot` heette), `uiterlijkGeldigTot` is nu het harde plafond. Het
  antwoord draagt `Location: /api/v1/sessies/huidige` en `_links.verlengen`.
- Nieuw `GET /api/v1/sessies/huidige` (met `X-Ontvanger` en `X-Sessie`): `200` met
  `{ geldigTot, uiterlijkGeldigTot, _links }`, zonder het sessie-id en zonder te verlengen; `401`
  als de sessie niet loopt. Handig om bij het laden te tonen hoe lang de sessie nog geldig is.
- `429 te-veel-sessies` draagt `Retry-After` (seconden tot de vroegst verlopende sessie, minstens 1).
  Een aanwijzing, geen belofte: een verzoek in die sessie schuift het moment op.
- Elke `401` draagt `WWW-Authenticate: X-Sessie realm="berichtenuitvraag"`. Blijf de 401 herkennen
  aan het problem-`type` `urn:fbs:fout:sessie-ongeldig`, zoals in stap 5.
- `DELETE /sessies/huidige` zonder of met een krom gevormde `X-Sessie` geeft nu `401` (was `400`).
  Een onbekende of verlopen sessie geeft nog steeds `204`.
- `X-Sessie` staat in de spec als securityschema (`components.securitySchemes.sessie`, `apiKey` in de
  header) in plaats van als parameter. De header zelf is ongewijzigd. Alles wat niet expliciet
  vrijgesteld is (`POST /sessies`, `/aanmeldingen`, `/openapi.json`), vereist hem.
- API-versie `0.7.0`.

**Lokaal testen** tegen de uitvraag van die branch: in de FBS-repo de demo-stack starten met de
overlay `compose.proeftuin-versie.yaml` die naar jouw lokale/preview-image van de berichtenbox
wijst (zie `docs/demo-runbook.md` daar).

**PR:** als draft, met in de body een verwijzing naar MinBZK/moza-poc-fbs-berichtenbox#393 en
naar issue MinBZK/MijnOverheidZakelijk#943 (zonder `Closes`: dat issue sluit via de FBS-PR).
Geen reviewer toevoegen. Merge-volgorde bij uitrol: eerst deze PR, daarna zet de FBS-PR zijn
pin op het main-image dat deze merge oplevert en gaat door de merge queue.
