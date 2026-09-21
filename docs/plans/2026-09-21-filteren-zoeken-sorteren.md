# Filteren, zoeken en sorteren in de berichtenbox

**Status:** Concept

Issue: MinBZK/MijnOverheidZakelijk#940. De proeftuin (`MinBZK/moza-poc`) bouwt de bediening in de
berichtenbox; dit plan beschrijft wat de uitvraag daarvoor moet leveren.

## Context

Een ondernemer wil zijn berichten terugvinden: filteren op map en afzender, zoeken op tekst,
sorteren op datum en alfabetisch. Vandaag:

- **Zoeken** bestaat (`GET /berichten/_zoeken?q=`), maar zonder paginering en zonder filters,
  terwijl `Sessiecache.zoek()` beide al kent.
- **Filteren** op `afzender` en `map` zit in `Sessiecache.lijst()`/`zoek()` en in de RediSearch-index
  (beide `TAG`), maar de uitvraag-API geeft de parameters niet door. De spec-beschrijving van
  `GET /berichten` meldt nog dat `map` niet geïndexeerd is; dat klopt niet meer.
- **Sorteren** ligt vast op `publicatietijdstip` aflopend: het ongefilterde pad leest een Redis-lijst
  die bij het opslaan zo gesorteerd is, het RediSearch-pad sorteert met `SORTBY` op hetzelfde veld.
- **Tijdens een ophaalronde** is er niets op te vragen. `lijst()` en `zoek()` gooien
  `OphalenBezig` (409), en de berichten van alle magazijnen worden pas aan het eind van de ronde in
  één keer opgeslagen (`BerichtensessiecacheService`, `store` samen met de `GEREED`-status).
- **Welk magazijn niet leverde** is alleen in de SSE-stream van `_ophalen` te zien.
  `AggregationStatus` bewaart aantallen (`geslaagd`, `mislukt`, `nietOpgehaald`), geen
  magazijn-id's; na afloop van de stream is die informatie weg.

## Keuze: filteren, zoeken en sorteren gebeuren in de keten

Besluit: de uitvraag filtert, zoekt en sorteert; de berichtenbox vraagt opnieuw op wanneer er iets
verandert.

Onderbouwing:

- Filteren en zoeken staan al in de sessiecache (RediSearch); full-text over alle opgehaalde
  berichten is op het scherm alleen mogelijk als álle berichten bij de client staan. Bij een
  groeiend aantal magazijnen en berichten per ontvanger schaalt dat slecht, en paginering wordt
  zinloos.
- Het resultaat is per definitie een momentopname van de cache. Dat is acceptabel zolang het
  antwoord zegt hoe volledig het is (zie "Volledigheid") en de client weet wanneer hij opnieuw
  moet vragen: bij elk `magazijn-bevraging-voltooid`-event uit `_ophalen` en bij de live-push uit
  MinBZK/MijnOverheidZakelijk#939.
- Eén bron voor sortering en filtering: de berichtenbox en eventuele andere afnemers zien dezelfde
  volgorde en aantallen.

Gevolg voor onvolledige resultaten: een resultaat tijdens een lopende ronde bevat de berichten van de
magazijnen die al geleverd hebben, en draagt `volledigheid.status = BEZIG`. Na de ronde draagt het de
magazijnen die niet (volledig) leverden, met reden. De client toont dat bij het resultaat; hij hoeft
niet zelf te bepalen of er nog iets onderweg is.

Verworpen alternatief: client-side over de volledige lijst. Eenvoudiger voor "vult zich aan", maar
vereist dat de client alle pagina's ophaalt vóór zoeken zinvol is, en verplaatst de full-text-index
naar de browser. Aan onze kant bleef dan alleen het volledigheidsblok over.

## Ontwerp

### API (`berichtenuitvraag-api.yaml`)

`GET /berichten` en `GET /berichten/_zoeken` krijgen dezelfde query-parameters:

| Parameter       | Betekenis                                                         |
|-----------------|-------------------------------------------------------------------|
| `map`           | Filter op map (exacte waarde)                                     |
| `afzender`      | Filter op afzender-OIN (`magazijnId`), herhaalbaar                |
| `sorteer`       | `-publicatietijdstip` (default), `publicatietijdstip`, `onderwerp`, `-onderwerp`, `afzenderNaam`, `-afzenderNaam` |
| `pagina`, `paginaGrootte` | Zoals nu op `/berichten`; nu ook op `_zoeken`           |

- Afzender filteren op OIN, niet op naam: de OIN is stabiel en publiek, de naam is weergave.
  De keuzelijst van afzenders komt uit het magazijnregister en is daardoor vooraf compleet.
- `paginaGrootte`-maximum gelijktrekken met de cap in `BlockingSessiecache` (nu spec 200, cache 100).
- `BerichtenLijst` krijgt een verplicht `volledigheid`-blok:

```yaml
volledigheid:
  status: BEZIG | GEREED         # uitbreidbaar; onbekend = behandelen als onvolledig
  magazijnen:                    # alleen magazijnen die niet (volledig) leverden
    - magazijnId: "000000…"
      afzenderNaam: "RVO"
      status: FOUT | TIMEOUT | NIET_OPGEHAALD | AFGEKAPT
```

- Zelfde uitbreidbaarheidsregel als bij de `_ophalen`-events: een onbekende status telt als
  "niet geleverd", nooit als geslaagd.
- `409` blijft voor `NogNietGevuld` (er is nooit een ronde gestart); tijdens een lopende ronde komt
  een `200` met `status: BEZIG`.
- Mappenlijst: nieuw `GET /berichten/_mappen` (distinct `map`-waarden met aantallen, via
  `FT.AGGREGATE … GROUPBY @map`), met hetzelfde `volledigheid`-blok. Een mappenlijst is pas
  compleet als de ronde klaar is; de client moet dat kunnen tonen.

### Sessiecache (`fbs-berichtensessiecache`)

1. **Opslaan per magazijn.** Berichten van een magazijn gaan de cache in zodra dat magazijn
   geleverd heeft, niet pas aan het eind van de ronde. De gesorteerde lijst-key wordt dan niet meer
   in één keer geschreven; het ongefilterde pad gaat ook via RediSearch (zie 3), of de lijst wordt
   per magazijn samengevoegd. Voorkeur: alles via RediSearch, dan is er één leespad.
2. **Uitkomst per magazijn bewaren.** Naast de aantallen in `AggregationStatus` een hash per sessie
   met `magazijnId → status` (incl. afgekapt), met dezelfde sliding TTL als de andere sessie-keys.
3. **Sorteren.** `lijst()`/`zoek()` krijgen een `sortering`-parameter (enum in de facade, geen vrije
   string). Index-aanpassingen:
   - `publicatietijdstip` is een `TAG` en sorteert als tekst. `Instant.toString()` laat de fractie
     weg bij hele seconden, waardoor `…00Z` na `…00.5Z` sorteert. Nieuw veld
     `publicatietijdstipMs` als `NUMERIC SORTABLE`.
   - `onderwerp` `TEXT SORTABLE`; `afzenderNaam` erbij als `TAG SORTABLE`.
   - Tie-breaker op `berichtId` voor een stabiele volgorde tussen pagina's.
4. **Gereed-vereiste loslaten.** `BlockingSessiecache` gooit geen `OphalenBezig` meer voor
   `lijst()`/`zoek()`; het resultaattype draagt de volledigheid. `OphalenMislukt` (hele ronde mislukt,
   bv. geen magazijnen te bevragen) blijft een fout.

Index-wijziging = nieuwe indexnaam volgens `docs/operations/redisearch-schema-bump.md`; de bootstrap
laat een bestaande index ongemoeid.

### Uitvraag (`berichtenuitvraag`)

- `UitvraagResource`/lijstservice: parameters doorgeven, `sorteer` naar de facade-enum mappen,
  volledigheid vertalen naar het DTO (afzenderNaam uit het magazijnregister).
- `_links.next/prev` nemen filter-, zoek- en sorteerparameters mee.
- Bruno-requests voor elke nieuwe parametercombinatie en voor `_mappen`.

## Stappen

1. Sessiecache: per-magazijn-opslag en uitkomst per magazijn (incl. tests op een ronde met
   één geslaagd en één mislukt magazijn, en lezen midden in een ronde).
2. Sessiecache: index-bump met sorteervelden, `sortering` in de facade.
3. Sessiecache: `OphalenBezig` weg uit het leespad; volledigheid in `BerichtenPagina`.
4. Spec + uitvraag: parameters, `volledigheid`, `_mappen`, paginaGrootte gelijkgetrokken.
5. Bruno, contracttests, operator-notitie over de index-bump.
6. Afstemming met de proeftuin: wanneer opnieuw vragen (SSE-event, #939-push), hoe `BEZIG` en
   onvolledige magazijnen getoond worden.

Stap 1–3 kunnen als aparte PR's; 4 hangt af van 3.

## Open punten

- Alfabetisch sorteren: op onderwerp, op afzendernaam, of beide? Aangenomen: beide.
- Moet een map-filter hoofdletterongevoelig zijn? `TAG` is dat standaard; bevestigen met de
  proeftuin wat een "map" in hun UI is.
- Samenhang met #939: de live-push kan dezelfde "opnieuw vragen"-trigger zijn. Eén mechanisme
  voor beide houden.

## Verificatie

- Unit- en integratietests (echte Redis) op elke combinatie van filter + zoeken + sorteren, met
  leeg, één en meerdere resultaten; sorteervolgorde met tijdstippen met en zonder fractie.
- Lezen tijdens een lopende ronde: resultaat bevat alleen geleverde magazijnen en `BEZIG`.
- Ronde met één mislukt magazijn: `volledigheid.magazijnen` noemt dat magazijn met reden.
- `OpenApiContractTest` valideert de nieuwe respons, ook bij `BEZIG` en lege resultaten.
- In de demo: filter op afzender tijdens een trage ronde (magazijn-simulator met vertraging)
  toont een groeiend resultaat.
