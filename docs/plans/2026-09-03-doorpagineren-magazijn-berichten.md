# Alle berichten van een organisatie ophalen, niet alleen de eerste twintig

**Status:** Uitgevoerd

Issue: [MinBZK/MijnOverheidZakelijk#996](https://github.com/MinBZK/MijnOverheidZakelijk/issues/996)

## Aanleiding

Een ondernemer die bij één organisatie meer dan twintig berichten heeft staan, ziet er in de
Berichtenbox maar twintig — zonder dat iets dat meldt. De uitvraag vraagt per magazijn precies één
pagina op: `MagazijnClient.getBerichten` stuurt geen `page`/`pageSize`, dus het magazijn valt terug
op zijn default van twintig. Alles daarna wordt nooit opgevraagd.

De heap-bescherming `berichtensessiecache.max-berichten-per-magazijn` (200) wordt door die grens van
twintig nooit bereikt. Er staan dus twee grenzen, waarvan de bedoelde niet werkt en de onbedoelde
wel.

Vandaag valt dat niet op omdat de demo per magazijn een handvol berichten bevat. Met de
magazijn-simulator gaan we voor het eerst realistisch vullen, en dan verdwijnt er stilletjes post.

## Wat het wordt

De uitvraag pagineert per magazijn door tot alles binnen is, met een bovengrens van **500 berichten
per magazijn**. Raakt een magazijn die grens, dan krijgt de ondernemer de nieuwste 500 berichten
plus een expliciet signaal dat er meer zijn — het magazijn blijft `status: OK`, want zijn post is er
wel degelijk.

```
event: magazijn-bevraging-voltooid
data: {"event":"magazijn-bevraging-voltooid","magazijnId":"00000001234567890000",
       "naam":"Belastingdienst","status":"OK","aantalBerichten":500,
       "afgekapt":true,"totaalBeschikbaar":1340}
```

`afgekapt` staat er altijd (`false` in het normale geval); `totaalBeschikbaar` alleen als het
magazijn een `totalElements` meestuurde. Beide velden zijn additief — een berichtenbox die ze nog
niet kent, blijft werken.

## Ontwerpkeuzes

### Waarom 500

Vijf pagina's van honderd (het spec-maximum voor `pageSize`). Dat dekt "jaren post bij dezelfde
afzender" ruim, terwijl vijf sequentiële calls per magazijn binnen de query-timeout van tien
seconden passen. Bij vijftien magazijnen en berichten van enkele kilobytes blijft het heap-beslag
een handvol megabytes.

Tien pagina's (1000) is voor de query-timeout te krap: een traag magazijn valt dan eerder in TIMEOUT
en levert daarna *helemaal* niets. Bij 200 blijven zou de grens bij realistische vulling zo vaak
raken dat de ondernemer vaker "er is meer" ziet dan zijn eigen post.

### De cap begrenst, hij faalt niet meer

`max-berichten-per-magazijn` was een fout-conditie: meer berichten dan de cap gaf een
`MagazijnResponseOverflow` en dus `status: FOUT` voor dat magazijn. Dat kostte de ondernemer alle
berichten van die organisatie, ook de eerste tweehonderd die prima waren. De cap wordt nu de grens
waarop we stoppen met doorpagineren.

De bescherming tegen een magazijn dat zich niet aan het contract houdt blijft, maar verschuift naar
de plek waar ze thuishoort: levert één pagina méér berichten dan de gevraagde `pageSize`, dan
negeert het magazijn zijn eigen paginering en is de respons onbetrouwbaar. Dat blijft
`MagazijnFault.OVERFLOW`. Zo bewaakt elke grens precies één ding.

### Stoppen op een volle pagina, niet op `totalPages`

De lus stopt als een pagina minder berichten oplevert dan gevraagd, als de cap bereikt is, of als
`totalPages` (wanneer meegestuurd) op zijn eind is. De eerste voorwaarde is de leidende: in een
federatief stelsel is een magazijn een implementatie van derden, en een lus die volledig op
andermans tellers vertrouwt, hangt zodra die tellers onzin zijn. `totalElements`/`totalPages` zijn
daarom nullable in `MagazijnBerichtenResponse` en dienen alleen als extra stopvoorwaarde en als
getal achter het `afgekapt`-signaal.

Het afkap-signaal leunt wél op `totalElements` zodra dat er is: `afgekapt` = wat wij leveren is
minder dan wat het magazijn zegt te hebben. Een magazijn dat `pageSize=100` naar zijn eigen maximum
van bijvoorbeeld twintig bijstelt in plaats van een 400 te geven, levert anders een niet-volle
pagina — en dan zou de paginavulling als enige maatstaf de lijst opnieuw stil afkappen.

### Duplicaten en een magazijn dat `page` negeert

De oogst wordt op `berichtId` verzameld, niet simpelweg aaneengeregen. Twee gevallen maken dat
nodig: een bericht dat tijdens het doorpagineren binnenkomt schuift het venster op, waardoor het
bericht op de paginagrens tweemaal in de respons staat; en een magazijn dat `page` negeert geeft
steeds dezelfde pagina terug. Het tweede geval krijgt bovendien een eigen uitgang — een volle pagina
zonder één nieuw bericht stopt de lus — anders loopt ze door tot de cap en staat elk bericht
meermaals in de berichtenbox.

### De lus heeft een eigen deadline

De query-timeout faalt de `Uni`, maar onderbreekt de blokkerende call niet: de verlaten thread zou
ná de timeout nog vier pagina's blijven ophalen, terwijl zijn bulkhead-permit al is vrijgegeven. De
lus krijgt daarom hetzelfde budget als parameter mee en stopt zelf zodra dat op is. Zo blijft de
bezetting van een trage leverancier begrensd zoals het bulkhead veronderstelt.

### De timeout blijft om de héle lus staan

`berichtensessiecache.magazijn-query-timeout-seconds` (10) dekt straks alle pagina's van één
magazijn samen, niet elke pagina apart. Een magazijn heeft dus hetzelfde totale budget als nu, en de
belofte "de ophaalronde is binnen tien seconden klaar of het magazijn heet traag" verandert niet.
Prijs: loopt een magazijn halverwege pagina drie in de timeout, dan gaan ook de eerste twee pagina's
verloren. Dat is dezelfde alles-of-niets-uitkomst als vandaag, en het alternatief — een deelresultaat
als "geslaagd" tonen — zou opnieuw stilzwijgend post weglaten.

### Sortering met tiebreaker in het magazijn

`BerichtRepository.lijstVoorOntvanger` sorteert alleen op `tijdstipOntvangst DESC`. Twee berichten
binnen dezelfde klok-tik hebben dan geen vaste volgorde, en bij paginering kan een bericht daardoor
op twee pagina's staan of op geen. Zolang er één pagina werd opgehaald viel dat niet op; met
doorpagineren wordt het een echte bug. De database-id als tweede sleutel maakt de volgorde
deterministisch — de magazijn-simulator doet dit al zo.

## Stappen

1. **`services/berichtenmagazijn`** — tiebreaker `id DESC` in `BerichtRepository.lijstVoorOntvanger`,
   met een test die twee berichten met hetzelfde `tijdstipOntvangst` over twee pagina's van één
   verdeelt en aantoont dat elk bericht precies één keer voorkomt.
2. **`MagazijnClient`** — `page`- en `pageSize`-queryparameters op `getBerichten`.
3. **`MagazijnBerichtenResponse`** — `totalElements`/`totalPages` erbij, nullable.
4. **`MagazijnResult.Success`** — velden `afgekapt` en `totaalBeschikbaar`.
5. **`BerichtensessiecacheService`** — pagineerlus in de blokkerende magazijn-call; cap als
   stopvoorwaarde; OVERFLOW alleen nog bij een pagina groter dan de gevraagde `pageSize`; default van
   `max-berichten-per-magazijn` naar 500 en een nieuwe `berichtensessiecache.magazijn-page-size`
   (100).
6. **`MagazijnBevragingGeslaagd`** — `afgekapt` + `totaalBeschikbaar` op de lijn.
7. **`demo-console`** — de berichtenbox-UI toont het afkap-signaal in de voortgangsregel.
8. **Docs** — `docs/operator-handleiding-uitvraag.md`: de twee properties met hun waarom.

## Verificatie

- `./mvnw clean verify -pl services/berichtenmagazijn -am` (tiebreaker + bestaande suite)
- `./mvnw clean verify -pl libraries/fbs-berichtensessiecache -am` (pagineerlus, cap, events)
- `./mvnw clean test -pl services/berichtenuitvraag -am` (keten ongewijzigd)
- `./mvnw clean test -pl demo/demo-console -am`
- `./mvnw detekt:check`

Nieuwe tests, langs de cardinaliteiten die de fout uitlokken: nul berichten, precies één pagina,
precies vol (grensgeval waarin een tweede call nog volgt), meerdere pagina's, méér dan de cap, een
cap die geen veelvoud van de paginagrootte is, een magazijn zonder totalen, een magazijn dat
kleinere pagina's teruggeeft, een magazijn dat `page` negeert, een bericht dat op twee pagina's
staat, en een verbruikt budget.

## Wat hierna nog open staat

Het `afgekapt`-signaal reist mee met de SSE-ophaalronde, niet met de gecachede lijst. Vraagt de
ontvanger de lijst daarna opnieuw op (refresh, volgende pagina), dan ziet hij een lijst die er
compleet uitziet. Het signaal meenemen in de aggregatiestatus en in `GET /berichten` raakt het
API-contract van de uitvraag en is een eigen keuze; hier is bewust alleen de ophaalronde gedekt.
