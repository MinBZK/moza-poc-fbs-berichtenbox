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

* Geen vrije permit → wachten en opnieuw proberen (poll-interval 25 ms) tot het wachtbudget
  (`max-wachttijd-ms`, default 5000) verstreken is.
* Het wachten is **asynchroon**: het draait op de scheduler van
  `Infrastructure.getDefaultWorkerPool()` en houdt géén worker-thread bezet. Daarmee blijft de
  eigenschap die de bulkhead moest bewaken exact overeind: er zijn nooit meer dan `max-concurrent`
  worker-threads met een magazijn-call bezig, hoeveel wachtenden er ook zijn.
* Permits blijven op één plek: `tryAcquire()` slaagt of slaagt niet, en de gepaarde `release()` zit
  net als voorheen op de terminatie van de taak. Er wordt nooit een permit "overgedragen" aan een
  wachtende, dus er is geen scenario waarin een permit bij een al afgebroken wachtende belandt en
  lekt.

**Waarom polling en geen FIFO-wachtrij van emitters.** Een emitter-wachtrij die permits doorgeeft
heeft een race die niet lokaal op te lossen is: op het moment dat de release een permit aan de
kop-wachtende toekent, kan die wachtende net zijn budget hebben overschreden en geannuleerd zijn —
de `complete()` is dan een no-op en de permit is van niemand meer. Polling heeft die klasse van
fouten niet, want een permit wordt alleen ooit vastgehouden door een pijplijn die op dat moment
leeft. Wat polling niet biedt is exacte FIFO-eerlijkheid; dat is hier geen bezwaar, omdat de
volgorde binnen een ronde al door laag 1 geborgd is en de winnaar bij gelijktijdige sessies dus geen
positie-afhankelijke, structurele achterstand meer kan oplopen.

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

Alle drie onder `berichtensessiecache.magazijn-bulkhead.`, gevalideerd fail-fast bij boot in de
bulkhead zelf:

| Property | Default | Wat |
|---|---|---|
| `max-concurrent` | 40 | Globaal: hoeveel worker-threads tegelijk een magazijn bevragen, over alle sessies |
| `max-parallel-per-ronde` | 20 | Per ophaalronde: hoeveel bevragingen tegelijk onderweg zijn |
| `max-wachttijd-ms` | 5000 | Bovengrens op het wachten op een permit |

Invarianten: alle drie > 0, en `max-parallel-per-ronde ≤ max-concurrent` (anders wacht een enkele
ronde structureel op permits die er nooit zijn en verbrandt hij zijn wachtbudget).

`max-concurrent` gaat van 20 naar 40 zodat twee gelijktijdige rondes op volle snelheid draaien
zonder aan de wachtrij te komen; dat is nog altijd een vijfde van de Quarkus-worker-pool. De grens
hoeft **niet** meer mee te groeien met het aantal organisaties van een ondernemer — dat is precies
wat laag 1 wegneemt — dus de handmatige 120 in de demo verdwijnt.

## Wijzigingen

**Library `fbs-berichtensessiecache`**
- `MagazijnAggregatieBulkhead`: twee properties erbij, asynchroon wachten met budget, `verlopen`
  in plaats van `afgewezen` als naam van de niet-gelukt-tak, `maxParallelPerRonde` uitleesbaar voor
  de service.
- `BerichtensessiecacheService.haalBerichtenOp`: GESTART-events vooruit, `withConcurrency(...)` op
  de merge, `bouwMagazijnStream` levert alleen nog het VOLTOOID-event.
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

## Verificatie

- `MagazijnAggregatieBulkheadTest`: wachten-tot-permit-vrijkomt, verstreken budget → `verlopen`,
  geen permit-lek na een verstreken budget, config-validatie inclusief de nieuwe invariant, en
  "alle wachtenden komen aan de beurt" (geen structurele uitsluiting).
- `BerichtensessiecacheServiceTest`: een ronde met 50 organisaties tegen een limiet van 5 levert
  50 GESTART- én 50 VOLTOOID-events, allemaal geslaagd, en géén `NIET_OPGEHAALD`. Dat is de test
  die het acceptatiecriterium "geen enkele organisatie valt structureel buiten beeld" pint.
- `./mvnw clean verify -pl libraries/fbs-berichtensessiecache -am` (JaCoCo 90% + detekt).
- `./mvnw clean test -pl services/berichtenuitvraag -am`.

Wat hierna nog open staat: de meting uit
[#1012](https://github.com/MinBZK/MijnOverheidZakelijk/issues/1012) opnieuw draaien met
`demo/meet-fanout.sh` bij 15, 45 en 100 organisaties, op de standaardinstellingen. De
integratietest bewijst dat er niets meer wegvalt; de meting laat zien wat het aan doorlooptijd doet.
