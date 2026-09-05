# Een knop in het bedieningspaneel doet nooit meer niets in stilte

**Status:** Uitgevoerd

Issue: [MinBZK/MijnOverheidZakelijk#1069](https://github.com/MinBZK/MijnOverheidZakelijk/issues/1069)

## Context

Het bedieningspaneel van de demo koppelt elke actie terug: een melding bovenaan en een merkteken op
de ingedrukte knop. Twee paden ontsnappen daaraan en leveren een druk op de knop op die *niets*
doet — geen melding, geen merkteken, geen aanroep. Tijdens een demonstratie is dat de vervelendste
storing: er is niets om aan te trekken.

1. **Een leeggemaakt invoerveld.** `vulPadIn` behandelde "leeg" en "ongeldig" als één geval en
   riep `reportValidity()` aan. Voor een veld dat leeg maar geldig is (geen `required`) toont de
   browser dan niets, en `voerUit` keerde terug vóór `zetUitkomst()`/`toonMelding()`.
2. **Een keuzelijst die nog niet gevuld is.** `pasOmgevingToe()` draaide precies één keer bij het
   laden. `lees()` kende geen tijdslimiet en geen tweede poging, dus een uitvraag die bleef hangen
   liet de knoppen onbeperkt dood achter terwijl ze er bruikbaar uitzagen — de toestandsbalk werkte
   zichzelf wél bij, dus de pagina oogde gezond.

## Ontwerpkeuzes

### Eén eigenaar voor de beschikbaarheid van een knop

Het aandachtspunt uit het issue is de kern van deze wijziging, niet een detail: `voerUit` zette in
zijn `finally` onvoorwaardelijk `knop.disabled = false`, en `vulKeuze` deed dat ook. Zodra het
inrichten een herhaalde poging krijgt, kunnen die twee elkaar midden in een lopende actie
tegenkomen en de knop vroegtijdig vrijgeven — met een dubbele aanlevering als gevolg.

Daarom draagt een knop nu twee onafhankelijke redenen als dataset-vlag, elk met zijn eigen
eigenaar, en leidt één functie de zichtbare toestand daaruit af:

| Vlag | Eigenaar | Betekenis |
|------|----------|-----------|
| `data-actie-loopt` | `voerUit` | er loopt een aanroep vanaf deze knop |
| `data-wacht-op-lijst` | `vulKeuze` / `meldLijstOnbekend` | de keuzelijst erbij is niet bruikbaar |

`werkKnopBij(knop)` is de enige plek in het script die `knop.disabled` schrijft. Een test bewaakt
dat.

### Een leeg veld levert een melding op, geen stilte

`vulPadIn` geeft geen kaal `null` meer terug maar een uitkomst met een reden, in drie soorten die
elk een andere oorzaak hebben:

- **veld ontbreekt in de opmaak** → opmaakfout (het paneel is stuk, niet de invoer)
- **veld is leeg** → "Vul eerst … in"
- **veld is ongeldig** → "… is niet geldig"

`voerUit` toont die reden via `toonMelding()` *en* zet het merkteken op de knop, zodat beide kanalen
antwoord geven. De naam in de melding komt uit `data-veldnaam` op het element zelf: de labels in de
opmaak zijn niet bruikbaar als zin ("Elke … seconden"), en twee groepen dragen allebei een veld dat
"Aantal" heet. Daarnaast krijgen de getalvelden `required`, zodat de browser het veld óók zelf
aanwijst; `reportValidity()` gaat naar het eerste struikelende veld.

### Een knop die nog niet kan, ziet er ook niet uit alsof hij kan

De twee knoppen die aan een keuzelijst hangen staan in de opmaak op `disabled` met
`data-wacht-op-lijst="ja"`, en hun keuzelijst draagt een optie "persona's laden…". `vulKeuze` haalt
die vlag weg zodra er echte opties zijn. Voorheen stonden ze enabled boven een lege lijst.

### Een tijdslimiet op het uitlezen, en een tweede poging op het inrichten

`lees()` krijgt een `AbortController` met `LEES_TIMEOUT_MS = 4000` — bewust korter dan `POLL_MS`
(5000), zodat een vastgelopen ronde afgelopen is voordat de volgende begint.

`pasOmgevingToe()` geeft nu terug óf de omgeving gelezen is. `richtIn()` eromheen plant bij een
mislukking een nieuwe poging (2s, 5s, 15s, daarna elke 30s) en toont een blok met een knop "Nu
opnieuw proberen". Automatisch én handmatig, want tijdens een demo is wachten op een lus geen optie
en is de knop het enige dat zeker werkt. Bij succes ná een mislukking verdwijnt het blok met een
melding; bij succes meteen bij het laden zwijgt het paneel — daar valt niets te melden.

### Een onbekende losse actie meldt zichzelf

`LOSSE_ACTIES[knop.dataset.actie]()` gooide een `TypeError` op een naam die het script niet kent.
Die vliegt uit de listener en levert precies het gedrag op dat dit issue bestrijdt: een knop die
niets doet. Nu volgt een opmaakfout-melding, en een test koppelt elke `data-actie` in de opmaak aan
een sleutel in `LOSSE_ACTIES`.

## Verificatie

`bediening.js` wordt in deze repo statisch getoetst (geen node/jsdom); de nieuwe invarianten staan
in `PaneelTerugkoppelingTest` en in twee aanvullingen op `PaneelPadenTest`:

- elk `{veld}` in een knop-adres wijst naar een element met een `data-veldnaam`
- elk getalveld achter een knop-adres draagt `required`
- een knop achter een keuzelijst staat in de opmaak uit, met `data-wacht-op-lijst`
- alleen `werkKnopBij` schrijft `knop.disabled`
- `lees()` draagt een tijdslimiet die korter is dan de poll-lus
- elke `data-actie` in de opmaak bestaat in `LOSSE_ACTIES`
