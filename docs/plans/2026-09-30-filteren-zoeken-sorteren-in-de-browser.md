# Filteren, zoeken en sorteren in de browser

**Status:** Uitgevoerd

Hoort bij [MinBZK/MijnOverheidZakelijk#940](https://github.com/MinBZK/MijnOverheidZakelijk/issues/940).
Afgesplitst: zoeken in de berichttekst ([#1225](https://github.com/MinBZK/MijnOverheidZakelijk/issues/1225))
en de lijst tussentijds per organisatie vullen ([#1226](https://github.com/MinBZK/MijnOverheidZakelijk/issues/1226)).

## Context

Een ondernemer wil berichten kunnen terugvinden: filteren op map en afzender, zoeken op
onderwerp, sorteren op datum (op- en aflopend) en alfabetisch. De vraag die #940 eerst
beantwoord wil hebben: gebeurt dat in de browser, over de opgehaalde set, of in de keten?

Hoe het er nu voor staat:

- **Keten.** `GET /berichten` levert de lijst gepagineerd, vast gesorteerd op
  `publicatietijdstip` aflopend. De sessiecache kan filteren op afzender en map
  (`Sessiecache.lijst`/`zoek`), maar de uitvraag-API geeft die parameters niet door.
  `_zoeken` zoekt alleen in `onderwerp` en levert alleen de eerste pagina (20 treffers),
  zonder paginering.
- **Proeftuin.** De berichtenbox (branch `feat/berichtinhoud-uit-keten` in `MinBZK/moza-poc`)
  haalt na `ophalen-gereed` één pagina op met `paginaGrootte=200`, en filtert (zoekterm, map),
  sorteert (afzender, onderwerp, datum) en pagineert in de browser. `_zoeken` en `_volgen`
  gebruikt hij niet; een afzenderfilter bestaat in de code, maar niet in de UI.
- **Een stille afkapping.** De spec staat `paginaGrootte` tot 200 toe, maar
  `BlockingSessiecache` kapt af op 100. De proeftuin vraagt 200, krijgt er 100 en meldt "maximaal
  200 berichten getoond" pas vanaf 200. Een ondernemer met meer dan 100 berichten ziet dus
  zonder melding een onvolledige lijst, en elke filter, zoekopdracht of sortering in de browser
  werkt op die onvolledige set.

## Keuze

**Filteren, zoeken en sorteren gebeuren in de browser, over de volledige opgehaalde set. De
keten levert die set betrouwbaar en volledig.**

Onderbouwing:

- **De set is klein genoeg.** Per organisatie haalt de keten hooguit
  `max-berichten-per-magazijn` (500) samenvattingen op, zonder berichttekst. Een ondernemer bij
  een handvol organisaties komt op hooguit een paar duizend regels kopgegevens: dat past in
  een browser en is in een paar pagina-aanroepen binnen.
- **Het resultaat is consistent.** Filter, zoekterm en sortering werken op dezelfde set in
  één geheugen; combineren kan zonder dat de server daar iets van hoeft te weten, en elke
  wijziging is direct zichtbaar, zonder aanroep.
- **Aanvullen is eenvoudig.** Een bericht dat via `_volgen` (`bericht-bijgekomen`) binnenkomt,
  voegt de browser toe aan de set; filter en sortering bepalen daarna of en waar het staat.
  Met filteren in de keten zou de browser bij elk nieuw bericht moeten nagaan of het past bij
  een query die op de server leeft.
- **Onvolledigheid is al zichtbaar.** De `_ophalen`-events melden per organisatie `OK`,
  `FOUT`, `TIMEOUT`, `NIET_OPGEHAALD` en `afgekapt`. Filteren in de browser verandert daar
  niets aan: een onvolledige set is na filteren nog steeds onvolledig, en de browser weet
  welke organisatie ontbreekt.
- **De proeftuin werkt al zo.** Er hoeft niets omgebouwd te worden; het gaat om het dichten van
  de gaten.

Gevolgen:

- De mappenlijst en de afzenderlijst leidt de browser af uit de opgehaalde set (`map`,
  `magazijnId` + `afzenderNaam`). De afzenderlijst kan ook uit de `_ophalen`-events komen; dan
  staan ook organisaties zonder berichten erin.
- Zoeken betekent: zoeken in het onderwerp (#940). Zoeken in de berichttekst kan in de browser
  niet, want die tekst staat bewust niet in de lijst; dat is #1225.
- Tijdens de ophaalronde is er nog geen lijst (409 `ophalen-bezig`); de voortgang per
  organisatie is het signaal dat er berichten onderweg zijn. Tussentijds vullen is #1226.

### Overwogen en verworpen

- **In de keten** (`map`, `afzender` en sorteerparameters op `GET /berichten` en `_zoeken`).
  Elke filterwissel wordt een aanroep. Alfabetisch sorteren vraagt een schemawijziging van de
  RediSearch-index (`SORTABLE` op `onderwerp`; `afzenderNaam` staat er nu niet in), en daarmee
  de omzetprocedure uit `docs/operations/redisearch-schema-bump.md`. Nieuwe berichten via
  `_volgen` moeten dan tegen een server-side query worden afgewogen. De proeftuin zou
  omgebouwd moeten worden, voor een set die in de browser past.
- **Beide.** De filterlogica zou op twee plekken staan, zonder afnemer die de keten-variant
  gebruikt.

De bestaande filterparameters van de `Sessiecache`-facade en `_zoeken` blijven staan: ze
kosten niets en er is geen reden ze te slopen. Ze krijgen geen uitbreiding.

## Stappen in deze repo

1. **Afkapping rechtzetten.** `BlockingSessiecache.effectieveGrootte` kapt af op 200 in plaats
   van 100, gelijk aan het maximum in `berichtenuitvraag-api.yaml`. KDoc van
   `Sessiecache.lijst` bijwerken. Test: 150 gevraagd → 150 geleverd, 250 gevraagd → 200 geleverd.
   (De spec-validatie weigert al boven 200 met een 400; de afkapping in de facade blijft als
   vangnet voor andere aanroepers.)
2. **Spec vertelt het echte verhaal** (via `/openapi-wijziging`, `info.version` patch-bump):
   - `GET /berichten`: de verouderde zin over #571 vervalt. In de plaats: dit is de set van de
     lopende sessie (ophaalronde plus later aangemelde berichten); de volgorde is geen contract,
     want een aangemeld bericht komt achteraan; volg `_links.next` tot hij ontbreekt om alles te
     hebben; filteren, zoeken op onderwerp en sorteren doet de afnemer over die set, en bij elk
     bericht uit `_volgen` opnieuw. Een verwijdering tussen twee pagina's kan één bericht laten
     overslaan; dan opnieuw lezen.
   - `_zoeken`: zoekt alleen in `onderwerp` en levert hooguit de eerste 20 treffers, zonder
     paginering. Wie over de volledige set wil zoeken, doet dat over `GET /berichten`.
3. **Tests voor een volledige set over meerdere pagina's.**
   - Keten-test (`UitvraagKetenE2eTest`, echte Redis): meer berichten dan één pagina van het
     plafond, uit meerdere magazijn-pagina's; `_links.next` volgen levert de hele set zonder
     gaten of dubbelingen.
   - Cache-test (`RedisBerichtenCacheIntegrationTest`): pagina voor pagina lezen voor 0, 1, 199,
     200, 201 en 450 berichten.
   - `PaginaGrootteSpecTest`: het spec-maximum en `Sessiecache.MAX_PAGINA_GROOTTE` blijven gelijk.
4. **Bruno-request** voor `GET /berichten?paginaGrootte=200` bij de bestaande lijst-requests.

## Wat de berichtenbox (proeftuin) moet doen

Buiten deze repo; hier vastgelegd zodat de afspraak compleet is:

- `_links.next` volgen tot hij ontbreekt (doet `main` van de proeftuin al, in pagina's van 100;
  naar 200 kan zodra deze wijziging in de keten draait).
- Een afzenderfilter in de UI (de filterlogica bestaat al).
- Datum op- én aflopend.
- `_volgen` aansluiten: `bericht-bijgekomen` toevoegen aan de set (ontdubbeld op `berichtId`),
  daarna filter, zoekterm en sortering opnieuw toepassen.
- `afgekapt` en `NIET_OPGEHAALD` apart tonen, niet onder "niet bereikbaar".
- Tests voor de combinatie filter + zoeken + sorteren, met een lege set, één bericht en
  meerdere.

## Verificatie

- `./mvnw clean test -pl libraries/fbs-berichtensessiecache -am`
- `./mvnw clean verify -pl services/berichtenuitvraag -am` (inclusief `OpenApiContractTest`,
  JaCoCo en detekt)
- Spectral op `berichtenuitvraag-api.yaml`
