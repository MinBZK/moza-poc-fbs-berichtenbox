# Magazijn-simulator

Eén service die zich als veel berichtenmagazijnen tegelijk voordoet. Voor elke deelnemende
organisatie een eigen magazijn-installatie neerzetten is niet te doen; dit programma bedient er
tientallen vanaf één deployment, zodat we kunnen laten zien hoe de Berichtenbox zich houdt bij een
ondernemer die bij drie organisaties berichten heeft staan — en bij een die er honderd heeft.

**Demonstratiecode. Draait nooit in productie.** De grens en wat dat voor de CI betekent staan in
[`../README.md`](../README.md).

Het ontwerp met de afwegingen, de aantallen en de vervolgstappen staat in
[`../../docs/plans/2026-08-21-magazijn-simulator-design.md`](../../docs/plans/2026-08-21-magazijn-simulator-design.md).

## Hoe een magazijn gekozen wordt

Elk gesimuleerd magazijn heeft een eigen pad-prefix met zijn OIN erin:

```
GET   /magazijn/00000009000000000007/api/v1/berichten
PATCH /magazijn/00000009000000000007/api/v1/berichten/{berichtId}
POST  /magazijn/00000009000000000007/api/v1/aanleveringen
```

De uitvraag hoeft daar niets voor te doen: zijn register wijst per OIN een URL aan, en die URL
draagt het prefix.

```properties
magazijnen."00000009000000000007".url=http://magazijn-simulator:8092/magazijn/00000009000000000007
```

Sleutel en pad-segment zijn dus dezelfde waarde, en kunnen niet uit elkaar lopen. Een pad zonder
`/magazijn/`-root of met een OIN die niet is ingeschreven, levert een 404 in `problem+json` — nooit
een willekeurig ander magazijn.

## Dezelfde afspraken als een echt magazijn

De JAX-RS-interfaces worden gegenereerd uit `berichtenmagazijn-api.yaml` van
`services/berichtenmagazijn`. Wijzigt die spec, dan faalt de build van deze module totdat de
simulator meevolgt. `MagazijnSpecContractTest` toetst daarbovenop de antwoorden zelf tegen die spec,
want de generator dekt de vorm van de interface en niet die van wat er over de lijn gaat.

## Welke magazijnen deze instantie voorstelt

Uit de configuratie, ingelezen bij het starten:

```properties
magazijnsimulator.magazijnen."00000009000000000001".naam=Demo-magazijn 1
magazijnsimulator.magazijnen."00000009000000000002".naam=Demo-magazijn 2
```

In de demo komt dat bestand uit `demo/genereer-magazijnen.py` en gaat het via
`SMALLRYE_CONFIG_LOCATIONS` mee; hetzelfde artefact vult het register van de uitvraag. Een OIN-key
die geen OIN is, een lege naam of een lege set blokkeert de boot — anders komt de fout pas bij het
eerste verkeer boven, midden in een demo, bij één van de honderd magazijnen.

## Wat de ondernemer doet, blijft staan

Elk gesimuleerd magazijn heeft echte opslag: PostgreSQL onder alle magazijnen samen, met
`magazijn_db_id` als discriminator op elke query. Honderd echte magazijnen zouden honderd databases
zijn; dit is er één, en de kosten blijven constant in het aantal.

Alle zes de operaties van de spec werken: aanleveren, lijst opvragen, detail, bijlage downloaden,
status bijwerken en verwijderen. Een bericht dat als gelezen is gemarkeerd blijft gelezen, een map
blijft staan, en een verwijderd bericht is weg voor de ondernemer maar niet gewist — soft-delete,
net als bij het echte magazijn.

### Drie dingen die bewust afwijken van het echte magazijn

Alle drie zouden ze hier iets kosten zonder iets te tonen; de eerste twee zijn bovendien
beleidskeuzes van dát magazijn die niet in de spec staan.

- **Bijlagen mogen elk MIME-type hebben.** Het echte magazijn beperkt ze tot `application/pdf`; de
  spec laat elk type toe. Een berichtenbox waarin alleen PDF's bestaan, laat het bijlage-pad maar
  half zien.
- **Geen abonnementscontrole bij de Profiel-service.** Het echte magazijn weigert een aanlevering
  met 403 als de ontvanger die afzender niet heeft aangevinkt. Dat zou hier een externe
  afhankelijkheid in honderdvoud opleveren, en autorisatiediepte staat in het ontwerp expliciet
  buiten de eerste versie.
- **Geen notificatie-outbox.** Een aanlevering bij een echt magazijn plant in dezelfde transactie
  een CloudEvents-push naar de notificatiedienst; hier gebeurt dat niet. De aanlevering zelf is
  compleet — het bericht staat er en is op te halen — maar wie downstream een push verwacht, krijgt
  hem niet. Interessant zodra we push-gedrag van veel magazijnen tegelijk willen tonen; tot die tijd
  is het een tabel plus poller die niets demonstreert.

Wat er wél is overgenomen, tot in de randen: de volgorde van 403 en 404, de merge-patch-semantiek
(een ontbrekend én een expliciet `null` veld laten de waarde staan), een lege patch als 400, een
tweede `DELETE` die opnieuw slaagt, de grens van 1 MiB in UTF-8-bytes, en dat `status` wegblijft
zolang de ontvanger niets heeft gezet.

## Elk magazijn heeft een eigen karakter

In werkelijkheid reageert niet elke organisatie even snel, en ligt er af en toe eentje eruit. Het
interessante gedrag van de Berichtenbox zit juist in die randen; een demo waarin alles het altijd
doet, laat niet zien wat een gebruiker merkt als het níét meezit.

| Modus | Wat de aanroeper merkt |
|---|---|
| `NORMAAL` | een vlot en correct antwoord |
| `TRAAG` | een correct antwoord, later — log-normaal verdeeld, dus met een lange staart |
| `HAPERT` | meestal goed, met een zekere kans een serverfout |
| `STUK` | consequent een serverfout |
| `UIT` | geen antwoord binnen de tijd die de aanroeper hem gunt |
| `WEIGERT` | een nette 4xx in `problem+json` |
| `MALFORMED` | 200, maar met een body die het schema schendt |

De laatste twee zijn er niet voor de sier. De Berichtenbox behandelt een *beschikbaarheids*-storing
(timeout, 5xx, netwerk — telt mee voor de circuit breaker) anders dan een magazijn dat wél
antwoordde maar iets onbruikbaars zei (telt níét mee). Met alleen de eerste vijf wordt die tweede
tak in een demo nooit geraakt, terwijl juist die eerder een echte fout opleverde.

**Het gedrag geldt op élke endpoint**, dus ook op gelezen markeren, verplaatsen naar een map en
aanleveren. Dat is realistisch — een schrijfactie is net zo goed een aanroep naar een andere
organisatie — maar het heeft een gevolg dat niet mag verrassen: een magazijn dat op storing staat
weigert ook nieuwe berichten. Vullen doe je dus vóór de storing, of via het beheerpad, dat buiten de
simulatie valt.

### Wie welk karakter krijgt

Uit het volgnummer, deterministisch en zonder loting: elke omgeving krijgt dezelfde verdeling, en
een demo die je vandaag oefent gedraagt zich morgen hetzelfde. Over de volle achtennegentig komt dat
neer op 72 normaal, 15 traag, 4 haperend, 3 stuk, 2 onbereikbaar, 1 weigerend en 1 onbruikbaar.

Binnen één demo wisselt een haperend magazijn wél af — anders hapert het niet. Elk magazijn heeft
daarvoor zijn eigen toevalsgenerator met een vaste startwaarde uit zijn OIN, zodat de reeks
herhaalbaar is zonder saai te worden.

```properties
magazijnsimulator.magazijnen."00000009000000000005".index=5        # volgnummer bepaalt het gedrag
magazijnsimulator.magazijnen."00000009000000000005".gedrag=STUK    # of overschrijf het expliciet
```

## Een demo voorbereiden en bijsturen

Wie een demonstratie geeft, moet die kunnen voorbereiden en tussendoor kunnen bijsturen. Zonder die
bediening is elke demo handwerk en is een tweede ronde niet hetzelfde als de eerste — en dan is hij
niet te oefenen en niet te vertrouwen.

| Aanroep | Waarvoor |
|---|---|
| `GET /beheer/magazijnen` | wat er staat en hoe elk magazijn zich nu gedraagt |
| `POST /beheer/seed` | berichten klaarzetten in alle magazijnen, in één handeling |
| `POST /beheer/legen` | alles terug naar de begintoestand — berichten én gedrag |
| `PUT /beheer/magazijnen/{oin}/gedrag` | tijdens het verhaal één organisatie kapot maken |

```bash
curl -X POST localhost:8092/beheer/seed -H 'Content-Type: application/json' \
  -d '{"ontvangers": ["KVK:90000001"], "berichtenPerMagazijn": 20, "bijlageElke": 4}'
```

**Vullen kost seconden, geen minuten.** Honderd magazijnen maal twintig berichten via losse
aanleveringen zouden tweeduizend rondjes naar de database kosten; dit is één opdracht met veel rijen
per magazijn. Wie een demo vlak van tevoren voorbereidt, doet dat anders niet — en draait dan op wat
er toevallig nog stond.

**Wat er klaargezet wordt is volledig afgeleid.** Dezelfde aanroep geeft dezelfde berichten, tot en
met de bericht-nummers. Een demo die je oefent is daarmee dezelfde demo als je hem geeft. De nummers
verschillen wél over magazijnen heen: twee magazijnen mogen in werkelijkheid hetzelfde nummer
uitdelen, maar de sessiecache van de uitvraag slaat berichten op zonder magazijn in de sleutel, en
zolang dat gebrek openstaat hoort een demo daar niet per ongeluk over te vallen.

**Twintig berichten per magazijn is niet toevallig.** De uitvraag haalt per magazijn één pagina op
en het magazijn levert er standaard twintig; daarboven ziet de ondernemer niets. Zolang dat gat er
is, demonstreer je met meer onbedoeld dát gat in plaats van het gedrag dat je wilt tonen.

**De bijlage is een echte PDF met een vaste tekst.** Eén A4 dat zegt dat het demonstratiemateriaal
is en dat er geen echte gegevens in staan. Een paar bytes die toevallig met `%PDF` beginnen zouden de
spec ook halen, maar in een demo wordt zo'n bijlage geopend: een viewer die hem weigert of een leeg
vel toont, laat de kijker denken dat het downloadpad kapot is terwijl dat juist het onderdeel is dat
we laten zien.

**Het beheerpad valt buiten de simulatie.** Een magazijn dat op storing staat weigert al zijn gewone
verkeer, maar hier komt het gedrag niet aan te pas — anders zou een kapot gezet magazijn niet meer
te repareren of te vullen zijn.

### Afscherming

Buiten `%dev` en `%test` is een token verplicht; zonder blokkeert de simulator zijn eigen boot.

```properties
magazijnsimulator.beheer.token=${BEHEER_TOKEN:}
```

```bash
curl -H 'X-Beheer-Token: …' localhost:8092/beheer/magazijnen
```

Dat is geen overdaad. De WireMock-admin-API van de stubs op de gedeelde omgeving stond publiek en
zonder authenticatie open; langs dat pad zou iemand hier de demo kunnen legen of een magazijn kapot
zetten. De schoonste vorm blijft het beheerpad helemaal niet publiceren — binnen één ZAD-project
bereiken componenten elkaar intern — en het token is het vangnet voor als dat niet lukt.

## Zicht op de connection pool

Eén service die honderd magazijnen voorstelt, deelt één pool. Of dat knelt is van buiten niet te
zien: een aanvraag die op een connection wacht lijkt sprekend op een magazijn dat traag antwoordt.
Daarom schrijft `Poolmonitor` elke vijf seconden één regel — en alleen als er iets veranderd is,
zodat het stil blijft zolang er niets gebeurt:

```
pool: 18 in gebruik, 2 vrij, 7 wachtend van max 120 | piek 20 | opgezet 20, vernietigd 0
    | wachten gem 41ms, langst 380ms, totaal 12,4s
```

Wat je eraan afleest:

| Wat je wilt weten | Waar je kijkt |
|---|---|
| Worden er connections opgezet, en hoeveel accepteert de database? | `opgezet` en `vernietigd` — blijft `opgezet` onder de grens van de database, dan zit de rem in de gelijktijdigheid en niet in de pool |
| Worden ze teruggegeven? | `in gebruik` tegenover `vrij`, en `piek` als hoogste bezetting ooit |
| Wordt er gewacht, en hoe lang? | `wachtend` is de rij op dit moment; de drie wachttijden zeggen hoe erg het was |
| Is de pool zelf de grens? | `van max` — het ingestelde maximum, tegenover wat de database toelaat |

Twee knoppen:

```properties
magazijnsimulator.pool.log-interval=5s   # `off` zet de regel uit (POOL_LOG_INTERVAL)
quarkus.log.category."io.agroal.pool".level=TRACE   # elke acquire/creatie/teruggave apart
```

Die tweede komt uit Agroal zelf en vraagt geen code, maar bij een fan-out van honderd levert hij
honderden regels per ophaalronde — bruikbaar om iets uit te zoeken, niet om mee te demonstreren.
TRACE vraagt bovendien `quarkus.log.min-level=TRACE`, en dat is een build-time-instelling.

De tellers komen uit Agroal en vragen `quarkus.datasource.jdbc.metrics.enabled` (staat aan). Zonder
die vlag geeft elke teller nul terug — een pool die nooit iets doet.

## Wat er nog niet is

De omzetting van de demo-omgeving: de oude antwoordmachines eruit, de simulator erin, en vier
ondernemers met een verschillend aantal aangesloten organisaties. Zie het ontwerp.

## Draaien

```bash
./mvnw clean test -pl demo/magazijn-simulator -am                   # tests (Docker vereist)
./mvnw compile quarkus:dev -pl demo/magazijn-simulator -am          # dev mode (poort 8092)
```

De tests starten hun eigen PostgreSQL via Quarkus Dev Services; dev-mode verwacht de database uit
`compose.yaml`.

```bash
curl -H 'X-Ontvanger: KVK:90000001' \
  http://localhost:8092/magazijn/00000009000000000001/api/v1/berichten
```
