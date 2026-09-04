# Bevragingen wachten op een permit in plaats van afgewezen te worden

**Status:** Uitgevoerd

Issue: [MinBZK/MijnOverheidZakelijk#1038](https://github.com/MinBZK/MijnOverheidZakelijk/issues/1038)

## Context

Een ondernemer met meer aangesloten organisaties dan de gelijktijdigheidsgrens ziet alleen de eerste
twintig; de rest krijgt "tijdelijk niet beschikbaar" terwijl die organisaties nooit bevraagd zijn.
Gemeten met de magazijn-simulator: 45 organisaties → 20 opgehaald, 25 afgewezen; 100 → 20 om 80.

Drie mechanismen werken hier samen, en alle drie zijn nodig om het gedrag te begrijpen:

1. `MagazijnAggregatieBulkhead` is een `Semaphore` met twintig permits, gedeeld over álle sessies.
   De enige acquire is `tryAcquire()`: geen permit betekent onmiddellijk `MagazijnFault.OVERBELAST`.
2. `Multi.createBy().merging()` heeft een default-concurrency van **128** (nagekeken in
   `MultiCreateBy.merging()`, Mutiny 3.3.0). Bij honderd magazijnen subscribet de merge dus in één
   keer op alle honderd substreams — honderd permit-pogingen tegen twintig permits.
3. De volgorde is deterministisch: `MagazijnClientFactory.init()` bouwt met `associate` (een
   `LinkedHashMap`, register-volgorde) en `bepaalClients` filtert die map. De eerste twintig winnen
   elke ronde. Het vermoeden uit het issue is daarmee **bevestigd**: het is een vaste blinde vlek,
   geen wisselende deelverzameling.

De demo zet de knop daarom handmatig op 120 — het aantal organisaties van de grootste persona. Dat
verplaatst het probleem naar de configuratie in plaats van het op te lossen.

## Ontwerp

De kern van de oplossing: **de gelijktijdigheidsgrens hoort een wachtrij te zijn, niet een zeef.**
Dat wordt op twee lagen ingevuld, omdat er twee verschillende dingen begrensd worden.

### Laag 1 — de ronde wacht op zichzelf (backpressure)

`Multi.createBy().merging().withConcurrency(n)` subscribet op maximaal `n` substreams en pakt de
volgende pas op wanneer er één termineert. Dat ís de wachtrij, geïmplementeerd door Mutiny zelf:
geen extra primitief, geen thread per wachtende bevraging, en de volgorde blijft register-volgorde —
maar nu komt élke organisatie aan de beurt in plaats van alleen de eerste `n`.

Hiermee verdwijnt de vaste blinde vlek volledig: honderd organisaties met `n = 20` worden alle
honderd bevraagd, in vijf golven van twintig.

**Wat dit kost aan doorlooptijd.** Een permit komt vrij zodra een willekeurige call termineert, niet
pas als de traagste klaar is. In de gemeten verdeling (91 van 100 antwoorden binnen ~100 ms, 9
vallen om, waarvan enkele op de query-timeout van 10 s) houden de trage calls een handvol permits
bezet terwijl de overige permits de snelle calls in een fractie van een seconde afwerken. "Compleet"
blijft dus bepaald door de organisatie die niet reageert — precies zoals nu. Alleen in het
pathologische geval waarin álle organisaties op hun timeout lopen, wordt de ronde langer; dan is de
ronde toch al onbruikbaar.

**Alle GESTART-events gaan vooruit.** Zonder ingreep zou een wachtende organisatie helemáál niet in
de stroom voorkomen — onzichtbaar in plaats van "ontbreekt nog". Daarom emitteert de stroom eerst
`MAGAZIJN_BEVRAGING_GESTART` voor alle organisaties, en daarna de uitkomsten zoals ze binnenkomen.
Het portaal krijgt daarmee direct de volledige lijst met een wachtindicatie per organisatie, en de
gebruiker ziet er niets van dat er intern golven zijn.

### Laag 2 — de globale grens wacht ook, met een bovengrens

Laag 1 begrenst één ronde. De semafoor is gedeeld over sessies: twee gelijktijdige ophaalrondes
zouden zonder ingreep hetzelfde bug-patroon over sessies heen krijgen (de eerste pakt alle permits,
de tweede krijgt overal OVERBELAST). Daarom wacht de acquire nu, met een budget:

* Geen vrije permit → wachten en opnieuw proberen tot het wachtbudget (`max-wachttijd-ms`,
  default 15000) verstreken is. Het poll-interval schaalt met het budget (minimaal 25 ms, in de
  orde van 200 stappen), zodat de `Uni`-keten kort blijft hoe groot het budget ook staat.
* Het wachten is **asynchroon**: het draait op de scheduler van
  `Infrastructure.getDefaultWorkerPool()` en houdt géén worker-thread bezet. Daarmee blijft de
  eigenschap die de bulkhead moest bewaken overeind: het aantal worker-threads met een magazijn-call
  hangt aan `max-concurrent` en niet aan het aantal wachtenden. Niet exact, overigens: de
  query-timeout faalt de `Uni` zonder de blokkerende call te onderbreken, dus tussen query- en
  read-timeout kan een verlaten thread nog draaien terwijl zijn permit alweer aan een wachtende is
  gegeven. Het werkelijke plafond ligt rond `max-concurrent × read-timeout ÷ query-timeout`.
* Permits blijven op één plek: `tryAcquire()` slaagt of slaagt niet, en de gepaarde `release()` zit
  net als voorheen op de terminatie van de taak. Er wordt nooit een permit "overgedragen" aan een
  wachtende, dus er is geen scenario waarin een permit bij een al afgebroken wachtende belandt en
  lekt.
* De geslaagde acquire en het aanhaken van de release staan in hetzelfde synchrone blok. Zouden ze
  door een operator-grens gescheiden worden (acquire in de poll-stap, release in een
  `transformToUni` erna), dan slaat een annulering die daartussen valt de release-tak over —
  Mutiny levert een item niet meer af aan een geannuleerde subscriber — en is die permit
  permanent kwijt.

**Waarom polling en geen FIFO-wachtrij van emitters.** Een emitter-wachtrij die permits doorgeeft
heeft een race die niet lokaal op te lossen is: op het moment dat de release een permit aan de
kop-wachtende toekent, kan die wachtende net zijn budget hebben overschreden en geannuleerd zijn —
de `complete()` is dan een no-op en de permit is van niemand meer. Polling heeft die klasse van
fouten niet, want een permit wordt alleen ooit vastgehouden door een pijplijn die op dat moment
leeft. Wat polling niet biedt is FIFO-eerlijkheid, en dus ook geen garantie dat een individuele
wachtende wint — `tryAcquire()` bargeert. Wat wél geborgd is, is dat er geen vaste deelverzameling
structureel buiten beeld valt (de volgorde binnen een ronde komt van laag 1) en dat een wachtende
die verliest een eigen, zichtbare uitkomst krijgt in plaats van stil te verdwijnen.

### Wat een verstreken wachtbudget aan de gebruiker toont

`OVERBELAST` betekent na deze wijziging "het wachtbudget is verstreken zonder permit" — een
zeldzame uitkomst die alleen bij aanhoudende verzadiging over sessies heen voorkomt. Die uitkomst
krijgt een eigen woord op de lijn, `NIET_OPGEHAALD` naast `OK`/`FOUT`/`TIMEOUT`, en een melding die
zegt wat er aan de hand is ("nog niet opgehaald") in plaats van te suggereren dat de organisatie
eruit ligt. Zo kan het portaal "dit deel ontbreekt nog" onderscheiden van "die organisatie deed het
niet".

De tellers in `OPHALEN_GEREED` blijven `geslaagd`/`mislukt`: een derde teller zou ook de
aggregatiestatus in Redis raken, en de per-organisatie-status draagt de nuance al.

### Knoppen

Alle drie onder `berichtensessiecache.magazijn-bulkhead.`, gevalideerd fail-fast bij boot — de
eerste drie invarianten in de bulkhead zelf, de vierde in de service (zie hieronder):

| Property | Default | Wat |
|---|---|---|
| `max-concurrent` | 50 | Globaal: hoeveel worker-threads tegelijk een magazijn bevragen, over alle sessies |
| `max-parallel-per-ronde` | 50 | Per ophaalronde: hoeveel bevragingen tegelijk onderweg zijn |
| `max-wachttijd-ms` | 15000 | Bovengrens op het wachten op een permit |

Invarianten:

* Alle drie > 0.
* `max-parallel-per-ronde ≤ max-concurrent`: anders zet een ronde het overschot in de poll-lus in
  plaats van in de kosteloze backpressure-wachtrij van de merge, en juist die wachtenden verliezen
  hun budget zodra de permits door trage calls bezet blijven. Verlaagt een omgeving
  `max-concurrent`, dan moet `max-parallel-per-ronde` mee omlaag — anders start de service niet.
* `max-wachttijd-ms ≥ magazijn-query-timeout-seconds × 1000`, gekruisvalideerd in
  `BerichtensessiecacheService.valideerTimeouts()` naast de bestaande timeout-invarianten. Een
  permit komt pas vrij als de call die hem houdt afgerond is, en die mag tot de query-timeout
  duren; bij een korter budget verliest een bevraging die aanklopt terwijl alle permits door trage
  calls bezet zijn haar budget vóórdat er ook maar één permit kán vrijkomen — dan is de wachtrij
  voor haar alsnog een zeef. Dit is precies het gat dat de eerste versie van dit ontwerp had
  (budget 5 s, query-timeout 10 s).
* `max-wachttijd-ms ≤ 120000`: inhoudelijk omdat langer wachten dan de vangnet-TTL van de
  ophaal-lock zinloos is (de ronde waarvoor gewacht wordt is zichzelf dan al kwijt) — die 120000 is
  gelijk aan de *default* van `aggregation-lock-ttl` en schuift niet mee als die property wordt
  aangepast. Mechanisch omdat het budget naar nanoseconden gaat en een absurde waarde daar
  overloopt naar negatief: de deadline is dan meteen verstreken en elke bevraging krijgt
  onmiddellijk de niet-gelukt-tak.

`max-concurrent` gaat van 20 naar 50, en `max-parallel-per-ronde` staat daaraan gelijk: één
ondernemer mag de volle capaciteit gebruiken zolang hij alleen is. Vijftig is hooguit een kwart van
de Quarkus-worker-pool, die zonder expliciete `quarkus.thread-pool.max-threads` minimaal 200 threads
groot is. De grens hoeft **niet** mee te groeien met het aantal organisaties van een ondernemer —
dat is precies wat laag 1 wegneemt — dus de handmatige 120 in de demo verdwijnt.

De keerzijde van gelijk zetten: een tweede gelijktijdige ophaalronde vindt het bulkhead vol en gaat
meteen wachten in plaats van naast de eerste door te lopen. Dat is een bewuste keuze voor de
demo-schaal (de grootste persona heeft honderd organisaties, dus de wachtrij is er zichtbaar bij
vijftig) en eenvoudig terug te draaien: `max-concurrent` hoger zetten dan `max-parallel-per-ronde`
geeft weer ruimte voor rondes naast elkaar.

### De duur van een ronde tegenover de ophaal-lock

Waar de ronde vroeger ongeveer één query-timeout duurde ongeacht het aantal organisaties, duurt hij
nu in het slechtste geval `⌈organisaties ÷ max-parallel-per-ronde⌉ × (wachtbudget + query-timeout)`.
Het wachtbudget hoort erbij: een bevraging kan eerst haar budget volmaken en daarna alsnog op de
query-timeout lopen. Bij honderd organisaties, vijftig per ronde, 15 s budget en 10 s timeout is dat
2 × 25 = 50 seconden — ruim binnen de `PT2M` van `berichtensessiecache.aggregation-lock-ttl`.

Dat is een echte worst case en geen verwachting: het wachtbudget komt pas in beeld als meerdere
ondernemers tegelijk ophalen, en alleen niet-antwoordende organisaties maken hun timeout vol. De
meting blijft dan ook op tien seconden steken bij honderd organisaties. Maar het is krapper dan het
op het eerste gezicht lijkt, en een startup-controle kan het niet afdwingen — het aantal
organisaties van een ondernemer is bij boot niet bekend. De rekensom staat daarom in de
operator-handleiding, bij de knoppen die eraan draaien.

### Wat bewust niet gebeurt

De volgorde van bevragen wordt **niet** per ronde gehusseld. Het issue noemt dat als mogelijke
pleister tegen de vaste blinde vlek, maar met een wachtrij is er geen blinde vlek meer om te
verhullen: iedereen komt aan de beurt. Husselen zou daar alleen een onvoorspelbare volgorde in het
portaal aan toevoegen.

Er komt ook geen maximum op de wachtrij-diepte met shedding daarboven. Het aantal wachtenden volgt
uit `max-parallel-per-ronde` × het aantal gelijktijdige ophaalrondes, elke wachtende is al in tijd
begrensd door het wachtbudget, en een diepte-grens zou de zeef terugbrengen voor een belasting die
deze dienst niet kent.

## Wijzigingen

**Library `fbs-berichtensessiecache`**
- `MagazijnAggregatieBulkhead`: twee properties erbij, asynchroon wachten met budget, `verlopen`
  in plaats van `afgewezen` als naam van de niet-gelukt-tak, en `ronde(...)` als tweede operatie
  zodat de per-ronde-grens niet als los getal naar buiten hoeft.
- `BerichtensessiecacheService.haalBerichtenOp`: GESTART-events vooruit, de ronde via
  `bulkhead.ronde(...)`, `bouwMagazijnStream` levert alleen nog het VOLTOOID-event, een vangnet om
  de hele bevraging en de half-open probe die aan de terminatie hangt. `valideerTimeouts()` krijgt
  de kruisvalidatie wachtbudget ≥ query-timeout erbij.
- `MagazijnEvent`: `MagazijnStatus.NIET_OPGEHAALD` + `MagazijnFoutStatus.NIET_OPGEHAALD`.
- `MagazijnResult`: KDoc van `OVERBELAST` en `MagazijnOverbelastException` bijgewerkt naar de
  nieuwe betekenis.

**Service `berichtenuitvraag`**
- `application.properties`: de drie knoppen met hun rationale.

**Demo en documentatie**
- `compose.yaml`: de handmatige 120 eruit.
- `demo/environment/zad-demo/magazijn-simulator.md`, `docs/demo-runbook.md`,
  `docs/operator-handleiding-uitvraag.md`, `docs/plans/2026-08-21-magazijn-simulator-design.md`.
- `demo/smoke.sh`: `NIET_OPGEHAALD` telt mee als gesignaleerde uitkomst.

### Wat de review op deze PR nog aan het licht bracht

Een tweede reviewronde legde drie manieren bloot waarop één lokale fout de héle ophaalronde kon
meenemen, en die zijn alle drie gedicht:

* **De wachtstap kon zelf falen.** `delayIt()` maakt van een `RejectedExecutionException` (dode
  scheduler bij pod-shutdown) een gewone `Uni`-failure, en die viel buiten élke recover: de
  substream faalde, de merge annuleerde zijn siblings, en `aggregeerEnSlaOp` werd nooit
  gesubscribed. Geen slotevent, niets opgeslagen, status `BEZIG` tot de lock-TTL — minutenlang 409
  voor de gebruiker. Er ligt nu een vangnet om de hele bevraging, dat elke fout die buiten de
  taak-recover ontstaat vertaalt naar `NIET_OPGEHAALD` mét een `errorf`.
* **`registreerCircuit` stond ná die recover.** Een throw daaruit (of uit de verlopen-tak) werd
  hetzelfde probleem: een bug in de fóutregistratie nam de hele ronde mee. De circuit-melding kan nu
  niet meer terugslaan op de bevraging; falen erin is een `errorf` en verder niets.
* **De half-open probe lekte bij annulering.** `toegestaan()` claimt de enige probe, en die wordt
  door niets anders gewist dan een terminale melding. Werd de bevraging afgebroken vóór een uitkomst
  — met de wachtrij een venster van seconden in plaats van microseconden — dan bleef dat magazijn
  tot de herstart overgeslagen met een `CIRCUIT_OPEN` die niets over dát magazijn zei. De melding
  hangt nu aan de terminatie, idempotent met het normale pad.

Wat bewust níét gefixt is: de per-organisatie-status onderscheidt `NIET_OPGEHAALD` van een storing,
maar de tellers in `OPHALEN_GEREED` doen dat niet — een wachtbudget dat verstrijkt telt als
`mislukt`, en daarmee zet `logboekStatusVoor` de verwerking in het Logboek Dataverwerkingen op
`ERROR`. Dat is strikt genomen onjuist: capaciteitsbeleid is geen verwerkingsfout. Een derde teller
zou echter het SSE-contract, de aggregatiestatus in Redis, `demo/meet-fanout.sh` (die de tellingen
kruiscontroleert) en de berichtenbox-weergave raken, voor een toestand die alleen bij aanhoudende
verzadiging over sessies heen voorkomt. Losgetrokken als vervolgwerk, niet stilzwijgend gelaten.

Ook niet overgenomen: het bulkhead de query-timeout laten kennen zodat de vierde invariant in zijn
eigen `init` past. Dat zou een tweede lezer van `magazijn-query-timeout-seconds` maken, terwijl álle
timeout-ordening van deze service nu op één plek staat (`valideerTimeouts()`). De prijs is één
getter (`wachtbudgetMs()`) naar buiten.

### De wachtrij zichtbaar maken

Een wachtrij is per constructie stil: wie nog in de rij staat doet niets, en juist dat is wat je
wilt kunnen zien. Daarom logt de ronde zichzelf.

Op `INFO`, twee regels per ophaalronde — genoeg om zonder configuratie te zien of iedereen aan bod
kwam: `Ophaalronde: N bevragingen, K tegelijk, M in de wachtrij` bij de start, en
`Ophaalronde afgerond in T ms: N van N organisaties bevraagd (…)` aan het eind. Dat tweede getallenpaar
is het antwoord op het acceptatiecriterium van het issue; loopt het uiteen, dan is er een
organisatie zonder uitkomst verdwenen en is dat een bevinding.

Op `DEBUG`, per organisatie: wanneer de ronde-wachtrij haar oppakte
(`aan de beurt (x van N, T ms na de start van de ronde)`, gehangen aan de `onSubscription` van haar
substream, wat precies dat moment is) en of ze op een permit moest wachten. De eerste `K` staan op
~0 ms en de rest schuift op — dat ís de wachtrij. De permit-regels komen er pas bij als meerdere
ophaalrondes tegelijk lopen: binnen één ronde is er per definitie een permit vrij op het moment dat
de wachtrij een organisatie oppakt, want de grens per ronde is niet groter dan het aantal permits.

Geen PII: de regels dragen aantallen, duren en magazijn-id's (publieke OIN's), nooit de ontvanger.

## Verificatie

- `MagazijnAggregatieBulkheadTest`: wachten-tot-permit-vrijkomt, verstreken budget → `verlopen`,
  geen permit-lek na een verstreken budget én niet na het annuleren van een wáchtende, "nooit meer
  taken tegelijk dan permits", "alle wachtenden komen aan de beurt" (geen structurele uitsluiting)
  en de config-validatie inclusief de nieuwe invarianten.
- `BerichtensessiecacheServiceTest`: een ronde met 5, 6 en 50 organisaties tegen een limiet van 5
  levert per geval evenveel GESTART- als VOLTOOID-events, allemaal geslaagd, en géén
  `NIET_OPGEHAALD`. Dat is de test die het acceptatiecriterium "geen enkele organisatie valt
  structureel buiten beeld" pint.
- Een tweede servicetest onderscheidt de twee lagen: 50 organisaties, limiet 5, calls van 200 ms en
  een wachtbudget van 1 s. Alleen als de merge de wachtenden pas op hun beurt subscribet, blijft
  er niets liggen. Gecontroleerd dat die test zonder `withConcurrency(...)` daadwerkelijk faalt
  (35 van de 50 verbrandden hun wachtbudget) — anders zou de wachtrij van laag 2 hem alsnog groen
  houden en bewees hij niets.
- `./mvnw clean verify -pl services/berichtenuitvraag,libraries/fbs-berichtensessiecache -am`
  (JaCoCo 90% + detekt); `shellcheck -x -S warning demo/smoke.sh`.

### De meting

`demo/meet-fanout.sh 3` tegen de lokale demo-stack, **op de standaardinstellingen** — de handmatige
120 uit `compose.yaml` is weg, dus dit is 40 permits met 20 per ronde. Mediaan over drie rondes:

| Ondernemer | Organisaties | Eerste bericht | Compleet | Geslaagd |
|---|---|---|---|---|
| kleine-eenmanszaak | 3 | 55 ms | 0,12 s | 3 van 3 |
| klein-bedrijf | 15 | 24 ms | 2,8 s | 15 van 15 |
| grootbedrijf | 45 | 24 ms | 10,1 s | 41 van 45 |
| landelijk-concern | 100 | 122 ms | 10,3 s | 91 van 100 |

Vóór deze wijziging leverde diezelfde standaardinstelling 20 van 45 en 20 van 100; de rest kreeg
"tijdelijk niet beschikbaar". Nu komen de aantallen exact uit op de tabel van
[#1012](https://github.com/MinBZK/MijnOverheidZakelijk/issues/1012), die met de knop op 120 gemeten
is — de knop is dus overbodig geworden en niet vervangen door verlies.

Wat níét slaagde, is wat de simulator opzettelijk stuk zet: 33 FOUT en 6 TIMEOUT over alle rondes
samen, en **geen enkele `NIET_OPGEHAALD`**. "Compleet" hangt net als voorheen aan de organisatie die
niet reageert (de query-timeout van 10 s), niet aan het aantal organisaties: 45 en 100 komen op
dezelfde 10,1 s uit. De wachtrij kost dus geen meetbare doorlooptijd.

De volgorde klopt ook: in de ronde met honderd organisaties staan alle 100 GESTART-events vóór het
eerste VOLTOOID-event, zodat het portaal meteen de volledige lijst met wachtindicaties heeft.

Herhaald op 2026-09-04 met de grens op vijftig per ronde in plaats van twintig, drie ronden,
mediaan — om te controleren of een grotere golf de gesimuleerde magazijnen niet overvraagt:

| Ondernemer | Organisaties | 20 tegelijk | 50 tegelijk |
|---|---|---|---|
| kleine-eenmanszaak | 3 | 3 van 3, 0,08 s | 3 van 3, 0,08 s |
| klein-bedrijf | 15 | 15 van 15, 2,8 s | 15 van 15, 1,3 s |
| grootbedrijf | 45 | 41 van 45, 10,1 s | 41 van 45, 10,1 s |
| landelijk-concern | 100 | 90 van 100, 10,2 s | 91 van 100, 10,1 s |

Gelijk of beter; alleen de ondernemer met vijftien organisaties wordt merkbaar sneller, omdat die
nu in één golf past. "Compleet" blijft op tien seconden staan — de organisatie die niet antwoordt,
niet het aantal.

Let op bij het zelf nameten: de eerste ronde ná een herstart is niet representatief. Een koude
ronde bij honderd organisaties gaf 45 van 100; drie warme ronden erna 91 van 100. Dat is het
opwarmeffect dat ook in de meetopstelling hierboven staat, geen eigenschap van de grens.

Gemeten op één machine waarop de hele stack draaide (uitvraag, Redis, twee echte magazijnen met elk
een PostgreSQL, de simulator met 98 magazijnen op nog een PostgreSQL, het bedieningspaneel en de
stubs), dus de getallen zijn pessimistisch — zie de meetopstelling in
`docs/plans/2026-08-21-magazijn-simulator-design.md`.
