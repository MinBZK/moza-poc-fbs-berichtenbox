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
   laden. `lees()` kende geen timeout en geen tweede poging, dus een uitvraag die bleef hangen
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

`werkKnopBij(knop)` is de enige plek in het script die de beschikbaarheid van een actieknop
schrijft; een test bewaakt dat. De knop in het inrichtingsblok valt erbuiten — die heeft maar één
eigenaar (`zetInrichtenBezig`) en volgt zijn eigen poging.

### Een leeg veld levert een melding op, geen stilte

`vulPadIn` geeft geen kaal `null` meer terug maar een uitkomst met een reden, in drie soorten die
elk een andere oorzaak hebben:

- **veld ontbreekt in de opmaak** → opmaakfout (het paneel is stuk, niet de invoer)
- **veld is leeg** → "Vul eerst … in"
- **veld is ongeldig** → "… is niet geldig"

Leeg en ongeldig komen samen in één melding: een knop met allebei zou anders na het invullen van het
ene een tweede, andere afwijzing geven.

`voerUit` toont die reden via `toonMelding()` *en* zet het merkteken op de knop, zodat beide kanalen
antwoord geven. De naam in de melding komt uit `data-veldnaam` op het element zelf: de labels in de
opmaak zijn niet bruikbaar als zin ("Elke … seconden"), en twee groepen dragen allebei een veld dat
"Aantal" heet. Daarnaast krijgen de getalvelden `required`, zodat de browser het veld óók zelf
aanwijst; `reportValidity()` gaat naar het eerste struikelende veld.

### Een knop die nog niet kan, ziet er ook niet uit alsof hij kan

De twee knoppen die aan een keuzelijst hangen staan in de opmaak op `disabled` met
`data-wacht-op-lijst="ja"`, en hun keuzelijst draagt een optie "persona's laden…". `vulKeuze` haalt
die vlag weg zodra er echte opties zijn. Voorheen stonden ze enabled boven een lege lijst.

### Een timeout op het uitlezen, en een tweede poging op het inrichten

`lees()` krijgt een `AbortController` met `LEES_TIMEOUT_MS = 4000` — bewust korter dan `POLL_MS`
(5000), zodat een vastgelopen ronde afgelopen is voordat de volgende begint. De catch-tak kijkt naar
`fout.name === 'AbortError'` en houdt de HTTP-status vast: breekt de timer af tijdens het lezen van
de body, dan is die status het enige dat de oorzaak nog aanwijst.

`pasOmgevingToe()` geeft nu terug óf de omgeving gelezen is. `richtIn()` eromheen plant bij een
mislukking een nieuwe poging (2s, 5s, 15s, daarna elke 30s) en toont een blok met een knop "Nu
opnieuw proberen". Automatisch én handmatig, want tijdens een demo is wachten op een lus geen optie
en is de knop het enige dat zeker werkt.

Die knop mag zelf niet stil zijn — precies de storing die dit issue beschrijft. Hij gaat daarom bij
de start uit met de tekst "Bezig de omgeving te lezen…" (een uitlezing duurt tot de timeout, dus
zonder dat lijkt een druk secondenlang niets te doen), en het blok zegt na een mislukking wanneer de
volgende poging komt. `inrichtLoopt` sluit een tweede poging naast een lopende uit: die twee zouden
elkaars uitkomst overschrijven, en de laatste die terugkomt is niet per se de meest actuele.
Handmatige pogingen tellen niet mee in de oplopende wachttijd.

Het blok staat ín het paneel en een ingeklapt paneel is `display: none`; de klap-knop ernaast krijgt
daarom een stip zolang de inrichting niet compleet is.

Een druk op de knop stelt een al geplande poging niet uit — wie blijft drukken zou de automatische
lus anders nooit laten afgaan — en noemt dan ook geen wachttijd die niet klopt.

### Eén vangnet voor wat buiten een eigen try/catch omvalt

`voerUit` en `verversToestand` worden fire-and-forget aangeroepen — vanuit de click-listener, vanuit
de poll-lus en bij het laden — en `vraagBevestiging` kan synchroon gooien. Een throw of een
afgewezen promise daaruit belandde alleen in de browserconsole — en wie een demo geeft heeft geen
devtools open, dus die ziet weer een knop die niets doet. Twee
listeners op `window` (`error`, `unhandledrejection`) zetten dat in de meldingsbalk.

Het vangnet staat direct onder de element-lookups en niet onderaan bij de rest van de bedrading:
die bedrading zoekt zelf elementen op en kan dus zélf omvallen, en dan was het vangnet nog niet
geregistreerd. Het ontdubbelt op de boodschap, want de poll-lus levert dezelfde fout elke paar
seconden opnieuw — maar `toonMelding` wist die vlag, dus zodra iets anders de balk overschrijft mag
dezelfde fout weer gemeld worden. Anders zou de tweede druk op een kapotte knop opnieuw zwijgen.
`toonMelding` bewaakt bovendien alle vijf de elementen van de meldingsbalk, anders sleept die het
vangnet mee dat hem net aanriep.

### Ontdubbeling geldt de lus, niet de bediener

Een ontbrekend element komt bij elke inricht-poging langs, dus `meldOpmaakfout` toont zo'n melding
één keer en logt hem daarna alleen nog. De vlag zit op de aanroepplekken die die lus doorloopt — en
de knop *Nu opnieuw proberen* raakt diezelfde plekken, dus leegt `richtIn` bij handwerk de
lus-ontdubbeling. Een melding die op een druk op een knop volgt is per definitie geen ruis, en
zwijgen bij de tweede druk zou die knop precies zo stil maken als hij vóór deze wijziging was.

Twee sets houden dat uit elkaar: `openstaandePaneelfouten` is wat er nog mis is — dat draagt het
merkteken op de klap-knop en houdt tegen dat het paneel zichzelf compleet noemt — en
`ontdubbeldInLus` is wat de lus al gezegd heeft. Alleen die tweede wordt geleegd: een ontbrekend
invoerveld gaat niet over van een uitlezing die dat veld niet eens bekijkt.

### Een onbekende losse actie meldt zichzelf

`LOSSE_ACTIES[knop.dataset.actie]()` gooide een `TypeError` op een naam die het script niet kent.
Die vliegt uit de listener en levert precies het gedrag op dat dit issue bestrijdt: een knop die
niets doet. Nu volgt een eigen melding die de onbekende naam noemt — bewust géén opmaakfout, want de
opmaak draagt die naam juist wél — en een test koppelt elke `data-actie` in de opmaak aan een sleutel
in `LOSSE_ACTIES`.

## Verificatie

`bediening.js` wordt in deze repo statisch getoetst (geen node/jsdom in de build); de nieuwe
invarianten staan in `PaneelTerugkoppelingTest`, dat de bestanden via `PaneelBestanden` van schijf
leest. Onder meer:

- elk `{veld}` in een knop-adres wijst naar een element met een `data-veldnaam`, en `veldnaam()`
  leest dat attribuut ook echt
- elk getalveld achter een knop-adres draagt `required`
- elke vroege uitgang in `voerUit` laat evenveel merktekens achter als er uitgangen zijn
- een knop achter een keuzelijst staat in de opmaak uit, met `data-wacht-op-lijst`
- alleen `werkKnopBij` schrijft `knop.disabled`, en het weegt allebei de vlaggen
- `lees()` geeft zijn `fetch` een `signal` mee en houdt zijn timeout onder de poll-lus
- de wachttijden lopen strikt op, worden geklemd, en staan zo ook in de README
- `richtIn` sluit twee gelijktijdige pogingen uit en telt handmatige pogingen niet mee
- elke `data-actie` in de opmaak bestaat in `LOSSE_ACTIES`, en de lookup gebruikt `Object.hasOwn`
- elk element dat het script met `getElementById` opzoekt, bestaat in de opmaak

De drie uitkomsten van `vulPadIn` en de eigenaars-splitsing zijn daarnaast lokaal doorgemeten met
een wegwerp-harnas op node; dat harnas is bewust niet toegevoegd, want een JS-toolchain in deze
build is een eigen besluit.
