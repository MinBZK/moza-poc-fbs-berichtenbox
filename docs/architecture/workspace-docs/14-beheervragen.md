# Beheervragen

In het overleg met Logius over de use cases kwamen drie situaties op tafel die niet over het gebruik
gaan, maar over het beheer van het stelsel. Ze delen één onderzoeksvraag: hoeveel moet er centraal
vastgelegd worden om het stelsel goed te beheren, en botst dat met de bedoeling dat er geen centrale
verwerker van berichten is?

Deze pagina is geen uitgewerkte use case: of en hoe deze situaties erin komen, stelt de refinement
vast. Wel staat hier wat het stelsel er **nu** op kan antwoorden, met verwijzing naar de use cases
waar het gedrag beschreven is. Logius wordt hier genoemd als beheerder van het stelsel; voor de
vragen maakt het niet uit wie die rol krijgt.

## 1. Heeft de ondernemer het bericht kunnen zien?

Een organisatie heeft een conflict met een ondernemer over een belangrijk bericht. Haar eigen
logging laat zien dat het bericht nooit geopend is; de ondernemer zegt het nooit ontvangen te
hebben. De organisatie vraagt de beheerder: is er een melding gestuurd, en zo nee, waarom niet? En
heeft de ondernemer in zijn berichtenbox gekeken terwijl het bericht daar te zien was?

**Wat het stelsel nu vastlegt:**

| Vraag | Waar het staat | Wat er wel en niet uit blijkt |
|---|---|---|
| Is het bericht aangenomen? | Logboek van het magazijn, bij het aanleveren (use case 1) | Wel: wanneer, voor welke ontvanger, en of het lukte. Weigerde het magazijn het bericht, bijvoorbeeld omdat de ondernemer geen berichten van deze organisatie wilde, dan staat de weigering er ook, maar zonder de ontvanger: die legt het magazijn pas vast als het bericht door de controle is. De organisatie kreeg de weigering wel direct |
| Is de melding verstuurd? | Logboek van het magazijn, per poging om de Notificatie Service te bereiken (use case 1) | Wel: of de Notificatie Service de melding aannam. Niet: of die dienst daarna een e-mail stuurde, en naar welk adres. Dat ligt bij de Notificatie Service |
| Heeft de ondernemer gekeken? | Logboek van de uitvraag (ophalen, lijst, zoeken, openen), en het logboek van elk magazijn dat bevraagd werd (use case 2) | Wel: wanneer de ondernemer ingelogd was, wanneer de uitvraag de berichtenlijst bij het magazijn van deze organisatie ophaalde, en of dat lukte. Niet: welke berichten in die lijst zaten, en welke het portaal liet zien |
| Heeft de ondernemer het bericht geopend? | Logboek van de uitvraag en van het magazijn (use case 5) | Wel: dat er een bericht geopend is, voor welke ondernemer. Het berichtnummer staat er niet bij |

Daaruit valt af te leiden, niet aan te tonen, dat het bericht getoond kon worden: het magazijn gaf
op een bepaald moment de lijst van deze ondernemer af, en het bericht was toen al gepubliceerd en
niet verwijderd. Dat het in de lijst zat (er gaan hooguit 500 berichten per magazijn mee) en dat
het portaal het liet zien, staat nergens.

**Wat het kost om dat wel vast te leggen.** Bij elke keer inloggen bevraagt de uitvraag alleen de
magazijnen van de organisaties waarvan de ondernemer berichten via de berichtenbox wil ontvangen;
is de ontvanger een organisatie, dan alle magazijnen. Elk bevraagd magazijn legt nu één regel per
pagina van 100 berichten vast. Leggen we per getoond bericht een regel vast, dan groeit dat naar
één regel per bericht per keer inloggen, verspreid over de logboeken van alle betrokken magazijnen
en de uitvraag. Met 1800 magazijnen en een ontvanger die een organisatie is, levert één keer
inloggen nu al minstens 1800 logregels op; per getoond bericht vastleggen voegt daar één regel per
bericht aan toe.

**Waar het antwoord vandaan moet komen.** Er is geen centrale plek die dit bijhoudt. Het antwoord
vraagt het logboek van de uitvraag, het logboek van het magazijn van de organisatie, en het
logboek van de Notificatie Service.

## 2. Alle berichten van een ondernemer wissen

Een ondernemer beroept zich op het recht op gegevenswissing: al zijn berichten moeten uit de
berichtenbox verdwijnen, met bijlagen. Ondernemer en beheerder willen daarna bewijs dat dat gelukt
is.

**Wat het stelsel nu kan:**

- De ondernemer kan berichten één voor één verwijderen (use case 8). Het magazijn markeert ze als
  verwijderd en ruimt ze pas definitief op als ze zeven jaar geleden verwijderd én zeven jaar
  geleden ontvangen zijn.
- Er is geen opdracht die alle berichten van één ondernemer in één keer verwijdert, niet in een
  magazijn en niet over alle magazijnen heen.
- Het magazijn legt elk verwijderen door de ondernemer vast in zijn logboek. Het definitief
  opruimen legt het alleen vast in de applicatielog, zonder het nummer van de ondernemer.
- De berichtenlijst in de uitvraag verdwijnt vanzelf aan het einde van de sessie, en dat wissen
  staat in het logboek van de uitvraag.
- De logboeken zelf bevatten het nummer van de ondernemer bij elke verwerking.

**Wat er nodig zou zijn:** een opdracht aan elk magazijn om alle berichten van een ontvanger te
wissen, een bevestiging per magazijn, en een manier om om te gaan met een magazijn dat (even) niet
kan wissen. Zonder centrale opslag van berichten weet ook niemand centraal in welke magazijnen
berichten van deze ondernemer staan: dat blijkt alleen door ze allemaal te vragen.

## 3. Een magazijn is langere tijd uit de lucht

Het magazijn van een organisatie ligt langere tijd plat. Een ondernemer mist daardoor belangrijke
berichten en vraagt de beheerder om er toch bij te kunnen.

**Wat het stelsel nu doet:**

- Bij het openen van de berichtenbox ziet de ondernemer de berichten van de andere organisaties, en
  de melding welke organisatie niet leverde (use case 2). Na een paar mislukte pogingen slaat de
  uitvraag het magazijn telkens even over.
- Berichten die de organisatie in die tijd wil versturen, kan het magazijn niet aannemen
  (use case 1). Wat al gepubliceerd was en een melding opleverde, kan de ondernemer niet openen: hij
  heeft dan een melding van een bericht dat hij niet ziet.
- Er is geen kopie van de berichten buiten het magazijn. Alleen de organisatie zelf kan de
  berichten op een andere manier bij de ondernemer krijgen.

## Andere situaties uit het overleg

- **Een melding ontvangen, maar het bericht niet zien.** Kan bij een onbereikbaar magazijn
  (hierboven), bij een magazijn dat niet in het magazijnregister staat (use case 2), of als het
  bericht intussen verwijderd is.
- **Een bericht verwachten dat niet komt.** Kan als het magazijn het bericht weigerde omdat de
  ondernemer geen berichten van deze organisatie via de berichtenbox wil (use case 1): de
  organisatie weet dat, de ondernemer niet.
- **Magazijnen die machtigingen kennen.** Een magazijn moet ook de berichten teruggeven die horen
  bij een machtiging. Dat zit nog niet in het stelsel; zie de doelarchitectuur bij use case 2.
