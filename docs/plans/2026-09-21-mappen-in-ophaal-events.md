# Mappen horen bij het bericht: zichtbaar maken in keten en demo

**Status:** Uitgevoerd

Hoort bij [MinBZK/MijnOverheidZakelijk#941](https://github.com/MinBZK/MijnOverheidZakelijk/issues/941).
De berichtenbox van de proeftuin is een eigen repository; dit plan dekt de keten en de demo-engine,
zodat die box de vier criteria kan tonen.

## Context

Een map bestaat alleen als eigenschap van een bericht (`map` op de status van het bericht in het
magazijn). Een berichtenbox leidt zijn mappenoverzicht dus af uit de berichten die hij kent. Vier
dingen stonden dat in de weg:

1. Tijdens een ophaalronde zijn de berichten niet op te vragen: `GET /berichten` geeft `409` zolang
   de ronde loopt, omdat een halve lijst anders als een volledige leest. De SSE-events droegen per
   organisatie alleen tellers.
2. Een bericht uit zijn map halen kon niet: in de merge-patch betekent `null` "niet wijzigen", en
   `map` had `minLength: 1`. Een map kon dus nooit meer leeg raken.
3. Wie niet leverde, stond alleen in de SSE-events. Na verversen of bladeren oogde een onvolledige
   lijst weer volledig.
4. De demo had geen berichten in mappen en geen scenario's of uitleg.

## Keuzes

### 1. Mappen per organisatie in `magazijn-bevraging-voltooid`

Status `OK` draagt `mappen`: per map in de geleverde berichten de naam en het aantal. De box telt
die per organisatie op en heeft zo tijdens de ronde een groeiend overzicht.

- Altijd aanwezig bij `OK`, ook leeg; mislukte uitkomsten dragen het veld niet.
- Postvak IN telt niet mee: dat is `aantalBerichten` min de som.
- Namen woordelijk, hoofdletters onderscheiden; gesorteerd op naam.
- Bij `afgekapt` dekken de tellingen alleen de opgehaalde berichten.

Verworpen: `GET /berichten` tijdens een lopende ronde toestaan met een onvolledig-markering. Dat
tornt aan de guard dat een lijst pas leesbaar is als de ronde af is, en vraagt van elke afnemer dat
hij die markering nooit mist.

### 2. `"map": ""` haalt een bericht uit zijn map

De wis-waarde is overal de lege string; in de sessiecache heet hij `Sessiecache.MAP_WISSEN`. Het
magazijn-contract noemde die sentinel al als voorziene uitbreiding.

- Verworpen: `null` als wissen (RFC 7396). Jackson onderscheidt "afwezig" en "`null`" niet zonder
  tri-state-typen in de gegenereerde modellen, en `null` betekent in dit contract al "niet
  wijzigen" — ook voor `status`.
- Een naam van alleen witruimte blijft ongeldig (400). Uitvraag, sessiecache-facade, magazijn en
  simulator weigeren hem elk; de uitvraag al vóór de magazijn-write.
- Een magazijn van een andere leverancier moet `""` ook als wissen gaan lezen; een magazijn op het
  oude contract (`minLength: 1`) antwoordt met een 400. Daarom gaat de magazijn-spec naar 0.4.0.
- In de sessiecache wordt het hash-veld verwijderd (HDEL), niet leeg gezet: anders leest de cache
  een lege mapnaam terug en ziet de TAG-index een lege map.

### 3. `aantalNietGeleverd` en `nietGeleverd` op `GET /berichten` en `_zoeken`

De aggregatiestatus in Redis bewaart per niet-leverende organisatie `magazijnId`, `naam` en de
uitkomst (`FOUT`/`TIMEOUT`/`NIET_OPGEHAALD`). De facade geeft die als `Volledigheid` mee met elke
pagina; de uitvraag zet hem als `aantalNietGeleverd` en `nietGeleverd` op `BerichtenLijst`. Het
geldt voor de hele lijst en staat op elke pagina.

- Volledig is `aantalNietGeleverd == 0`, niet een lege `nietGeleverd`. Het aantal komt uit de
  tellers, de namen uit de lijst. Een status van vóór dit veld heeft de tellers maar niet de namen:
  dan is de lijst korter dan het aantal, en toont de box "mogelijk onvolledig" zonder namen. Een
  lege lijst die als "iedereen leverde" leest, kan zo niet ontstaan.
- `BerichtenPagina.volledigheid` is `null` zolang de facade hem niet vulde; de uitvraag faalt dan
  hard in plaats van stil volledigheid te claimen.
- Hetzelfde mechanisme past voor `afgekapt` (#1072), maar dat heeft eigen criteria en blijft buiten
  deze wijziging.

### 4. Demo

- Een eigen persona, **Demo-onderneming 4** (KVK `90000015`): of vrije mappen er komen is nog niet
  besloten, dus de andere persona's houden hun demo zonder mappen. KVK en geen BSN, want de
  berichtenbox van de proeftuin neemt BSN-identiteiten niet over. Hij bevraagt dezelfde honderd
  organisaties als Landelijk Concern.
- De mappen komen van de simulator (`DemoMappen`), naar het gedrag van het gesimuleerde magazijn:
  een map bij elke organisatie die levert (groeit mee), een map alleen bij de trage (verschijnt
  laat), een map alleen bij wie niet levert (verschijnt nooit) en een map met één bericht (verdwijnt
  als het eruit gaat). Zo zijn M1–M3 in één ophaalronde te zien, zonder storingsknop — ook op ZAD,
  waar de echte magazijnen niet achter Toxiproxy staan en binnen milliseconden antwoorden.
- Eerst stonden de mappen via de basisdataset op Demo-onderneming 3. Verworpen: twee echte
  magazijnen leveren te snel om het overzicht te zien groeien, en die persona wordt ook voor andere
  demo's gebruikt. `AanleverOpdracht.map` blijft bestaan; een test bewaakt dat de basisdataset hem
  niet gebruikt.
- De proeftuin neemt de persona op in zijn eigen lijst (MinBZK/moza-poc#165), onder dezelfde id,
  naam en nummer; `ProeftuinPersonaTest` houdt onze kopie daarmee gelijk.
- Drie scenario's (M1–M3) in `docs/demo-runbook.md`.
- Niet-technische toelichting in `docs/mappen-bij-het-bericht.md`: waarom zo, gevolgen, het
  alternatief (mappen apart vastleggen) en wat dat kost.

## Spec-versies

Uitvraag 0.2.0 → 0.3.0 (nieuwe velden, `""` als wis-waarde); magazijn 0.3.0 → 0.4.0 (`""` wist,
waar het eerst een 400 gaf).

## Verificatie

- `./mvnw clean verify -pl libraries/fbs-berichtensessiecache,services/berichtenuitvraag,services/berichtenmagazijn,demo/magazijn-simulator -am`
- `./mvnw clean test -pl demo/demo-console -am`
- Spectral op beide specs
