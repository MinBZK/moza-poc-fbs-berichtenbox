# De echte magazijnen vullen zich bij het opstarten

**Status:** Uitgevoerd

Onderdeel van MinBZK/MijnOverheidZakelijk#1087, naast de opstartvulling van de simulator (#303).

## Context

Een preview begint met lege magazijndatabases. De gesimuleerde magazijnen vullen zichzelf sinds #303
bij het opstarten; de twee echte magazijnen (`magazijna`, `magazijnb`) bleven leeg tot iemand in het
bedieningspaneel op *Basisvulling laden* of *Herstel demo* drukte. Een lege berichtenbox is tijdens een
demo niet van een kapotte keten te onderscheiden.

## Ontwerpkeuzes

**De console vult, niet het magazijn.** Het berichtenmagazijn is stelselcode en hoort van geen demo te
weten; `.github/scripts/demo-grens.sh` verbiedt bovendien elke koppeling van stelsel naar demo. De
console kent de magazijnen al: hij telt hun berichten via de datasources die het legen gebruikt, en
levert de basisvulling via hun aanlever-API. Zo lopen validatie en publicatieketen mee, net als bij de
knop.

**Niet dezelfde functie als de simulator.** De simulator vult zijn eigen database via het beheerpad; de
echte magazijnen hebben dat pad niet en horen het niet te krijgen. Gedeeld is het gedrag: per magazijn,
alleen als het leeg is, en een magazijn met berichten blijft ongemoeid.

**"Leeg" per magazijn.** `SELECT count(*) FROM berichten` per datasource. De koppeling
afzender-OIN ↔ datasource staat in `MagazijnDatabase.MAGAZIJN_PER_OIN`; `DemoDatasetConsistentieTest`
toetst hem tegen `demo.magazijnen` en de datasources in `application.properties`, op letter, zodat een
verwisseling van A en B opvalt.

**Een herhaalde ronde, geen opstart-observer.** Op een verse omgeving starten alle componenten
tegelijk: de database van een magazijn heeft dan nog geen tabellen (Flyway draait in het magazijn), of
het magazijn weigert elke aanlevering tot de profielservice er is. Een `@Scheduled`-ronde
(`opstartvulling.interval`, standaard 20 s) probeert het tot dat lukt, zonder de start van de console
op te houden. Na `opstartvulling.opgeven-na` (standaard 30 min) volgt één `WARN`.

**Eerst één bericht.** Weigert het magazijn dat, dan blijft de rest van de dataset die ronde liggen:
één mislukte aanlevering per ronde in plaats van tientallen `WARN`-regels.

**Eén beoordeling per start.** Een magazijn dat gevuld of al gevuld is aangetroffen valt uit de rondes.
Anders maakt de console een bewust geleegd magazijn een ronde later weer vol, midden in een demo. Na een
herstart staat de post er wél weer — dezelfde afweging als bij de simulator.

**Geen tweede poging na een halve vulling.** Kwam het eerste bericht aan maar een deel van de rest
niet, dan zou opnieuw vullen het aangekomen deel dubbel zetten.

**Aan, behalve onder test.** De defaults van de datasources en magazijn-URL's wijzen naar dezelfde
poorten als een lokaal draaiende demo-stack; een `@QuarkusTest` zou die anders vullen. Lokaal en op ZAD
staat hij aan zonder dat er een omgevingsvariabele bij hoeft — geen OM-handwerk voor bestaande
deployments.

## Bewust niet

- Geen vulstap in `deploy.yml`: die dekt een herstart van de console of een opnieuw aangemaakte
  database buiten een uitrol niet, en zou een API-sleutel of netwerkregel naar de console vragen.
- Geen blijvende bewaking: dat botst met bewust legen tijdens een demo.
- Het uitgeschakelde component uit #1087 (replicas 0) blijft buiten deze wijziging; vullen lost dat
  niet op.

## Verificatie

- `MagazijnOpstartvullingTest`: per magazijn filteren, eerst één bericht, gevuld magazijn ongemoeid,
  geen herbeoordeling na legen, database nog niet te lezen, geweigerd eerste bericht, halve vulling
  zonder herhaling, lege dataset, uitgeschakeld, grens van de opgeef-termijn.
- `DemoDatasetConsistentieTest`: OIN ↔ datasource-koppeling tegen de configuratie en de basisdataset.
- `ApplicationPropertiesTest`: aan, behalve onder test.
- `./mvnw clean verify -pl demo/demo-console -am` groen, inclusief detekt.
- Op de preview van de PR: `$CONSOLE/api/demo/status` toont na de uitrol per magazijn meer dan nul
  zonder dat er een knop is ingedrukt.
