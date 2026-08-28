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

## Wat er nog niet is

Een beheerpad om demo's in één handeling te vullen, terug te zetten en bij te sturen, en de
omzetting van de demo-omgeving. Zie het ontwerp.

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
