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

## Wat er nog niet is

De simulator draagt op dit moment alleen de buitenkant: **hij slaat niets op.** Elke berichtenlijst
is leeg, elk bericht bestaat niet, en aanleveren antwoordt met de 503 die de spec daarvoor kent — in
plaats van een aanlevering te bevestigen die daarna nergens terug te vinden is.

Wat er in de volgende stappen bij komt: opslag (PostgreSQL, mappen en leesstatus die blijven staan),
gedrag per magazijn (traag, haperend, onbereikbaar), een beheerpad om demo's te vullen en terug te
zetten, en de omzetting van de demo-omgeving. Zie het ontwerp.

## Draaien

```bash
./mvnw clean test -pl demo/magazijn-simulator -am                   # tests (geen Docker nodig)
./mvnw compile quarkus:dev -pl demo/magazijn-simulator -am          # dev mode (poort 8092)
```

```bash
curl -H 'X-Ontvanger: KVK:90000001' \
  http://localhost:8092/magazijn/00000009000000000001/api/v1/berichten
```
