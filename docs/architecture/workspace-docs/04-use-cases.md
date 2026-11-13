# Use cases

De diagrammen elders op deze site laten zien uit welke onderdelen het Federatief Berichtenstelsel
bestaat. De use cases hieronder laten zien wat er gebeurt als iemand het gebruikt: wie wat doet,
in welke volgorde de onderdelen met elkaar praten, en wat er gebeurt als iets misgaat.

De lijst is het eerste voorstel uit issue
[MijnOverheidZakelijk#1522](https://github.com/MinBZK/MijnOverheidZakelijk/issues/1522). Welke use
cases er definitief in komen, stelt de refinement vast.

| Nr | Use case | Actor |
|---|---|---|
| 1 | Een organisatie levert een bericht aan, en de ondernemer krijgt een melding | Organisatie |
| 2 | Een ondernemer opent de berichtenbox en ziet de berichten uit alle magazijnen bij elkaar | Ondernemer |
| 3 | Nieuwe berichten verschijnen terwijl de berichtenbox openstaat | Ondernemer |
| 4 | Een ondernemer zoekt in de eigen berichten | Ondernemer |
| 5 | Een ondernemer opent een bericht en leest het | Ondernemer |
| 6 | Een ondernemer opent of downloadt een bijlage | Ondernemer |
| 7 | Een ondernemer verplaatst een bericht naar een map of archiveert het | Ondernemer |
| 8 | Een ondernemer verwijdert een bericht | Ondernemer |

Het rechtstreeks ophalen door een burger of ondernemer, met een app en zonder portaal of uitvraag
ertussen, staat hier nog niet: die route is nog te veel in ontwikkeling. Het doelbeeld daarvan
staat in de views *RechtstreeksOphalen* van het architectuurmodel.

Na de use cases volgt een pagina met drie vragen uit het beheer van het stelsel, en wat het stelsel daar
nu wel en niet op kan antwoorden.

## Zo lees je een use case

Elke use case heeft dezelfde opbouw:

- **Actor, aanleiding en resultaat**: wie begint, waarom, en wat er is als het goed gaat.
- **Verloop**: de stappen in gewone taal.
- **Sequence diagram**: dezelfde stappen als plaatje. Van boven naar beneden is de tijd. Een
  doorgetrokken pijl is een verzoek, een gestippelde pijl het antwoord. Een pijl met een open punt
  is een verzoek waar de afzender niet op wacht. Een kader met `alt` toont alternatieven, `opt`
  iets dat alleen soms gebeurt, `loop` iets dat zich herhaalt en `par` dingen die tegelijk
  gebeuren.
- **Als het misgaat**: per situatie wat het stelsel doet en wat de actor merkt.
- **Doelarchitectuur**: waar het doelbeeld afwijkt van wat er nu draait.
- **Technische verwijzing**: de koppelvlakken en instellingen achter het verloop, voor wie het in de
  code wil nalopen.

De diagrammen tonen hoe het stelsel zich **nu** gedraagt. Wat in het doelbeeld anders gaat, staat
bij de use case zelf en op de pagina met PoC-afwijkingen.

## De deelnemers

De diagrammen tonen het stelsel op het niveau van de systemen: één lijn voor het Berichten
Uitvraag Systeem en één voor het Berichtenmagazijn, ook als een stap binnen zo'n systeem door
verschillende onderdelen gaat. De tekst en de technische verwijzing noemen die onderdelen waar het
ertoe doet. De namen zijn die uit het architectuurmodel, zodat je ze in de andere diagrammen
terugvindt; bij elke wijziging in de repository controleert een script dat elke deelnemer een
onderdeel uit het model is.

| Deelnemer | Wat het is |
|---|---|
| Ondernemer / Zakelijke gebruiker | De ontvanger van berichten, namens een onderneming |
| Organisatie B | Een overheidsorganisatie die berichten verstuurt |
| Interactielaag | Het portaal waarin de ondernemer zijn berichtenbox ziet. Het stelsel levert de gegevens, het portaal toont ze |
| Berichten Uitvraag Systeem | De centrale dienst waar het portaal berichten opvraagt en beheert. Hij weet welk magazijn bij welke organisatie hoort (het magazijnregister), houdt de opgehaalde berichtenlijst van een ingelogde ondernemer vast in een tijdelijk geheugen, en neemt aanmeldingen van nieuwe berichten aan |
| Berichtenmagazijn | De opslag van de berichten van één organisatie. Elke organisatie heeft er één, zelf gehost of afgenomen bij BBO, en elk magazijn volgt dezelfde stelselafspraken over aanleveren, ophalen, beheren en aanmelden. De lijn in de diagrammen staat voor elk magazijn |
| Profiel Service | Dienst van MijnOverheid Zakelijk met de voorkeuren van de ondernemer: van welke organisaties hij berichten via de berichtenbox wil ontvangen |
| Notificatie Service | Dienst van MijnOverheid Zakelijk die de ondernemer laat weten dat er een bericht is, bijvoorbeeld per e-mail |

## Wat voor alle use cases geldt

**Er is geen centrale opslag van berichten.** Een bericht staat alleen in het magazijn van de
organisatie die het verstuurde. De uitvraag houdt de berichtenlijst van een ondernemer alleen vast
zolang hij ingelogd is, en wist die zodra zijn laatste sessie eindigt.

**Inloggen is in deze opstelling nog niet gebouwd.** Het portaal geeft bij elk verzoek mee om welke
ondernemer het gaat, en start bij het inloggen een sessie bij de uitvraag. Elk verzoek over
berichten moet bij een lopende sessie van die ondernemer horen. In het doelbeeld logt de ondernemer
in met eHerkenning, en geeft de Interactielaag daarna een token uit dat bij elk verzoek bewijst wie
hij is; zie de pagina met PoC-afwijkingen.

**Een sessie verloopt.** Na 15 minuten zonder activiteit, en uiterlijk na 12 uur, eindigt de sessie.
Het portaal kan een kortere grens meegeven en beëindigt de sessie zelf bij uitloggen. Daarna is
opnieuw inloggen nodig, en worden de berichten opnieuw opgehaald.

**Wat het stelsel vastlegt.** Elke dienst die persoonsgegevens verwerkt, legt elke verwerking vast
in zijn eigen Logboek Dataverwerkingen: welke handeling, voor welke ondernemer, en of die lukte.
Bij het aanleveren en publiceren legt het magazijn de handeling vast vóórdat het hem uitvoert:
mislukt de vastlegging, dan gebeurt er niets, en slaat het een aangeleverd bericht bijvoorbeeld niet
op. De meeste andere handelingen worden vastgelegd zodra ze klaar zijn en vóór het antwoord:
mislukt de vastlegging, dan krijgt het portaal of de organisatie een foutmelding en geen gegevens,
terwijl een wijziging dan al wel is doorgevoerd. Het ophalen wordt vastgelegd bij de start, en een
mislukt ophalen komt er later bij. Het volgen wordt alleen bij de start vastgelegd: een afgebroken
verbinding staat niet in het logboek. Het wissen van gegevens bij het einde of de start van een
sessie gaat altijd door; mislukt de vastlegging, dan krijgt de beheerder een waarschuwing. Welke
berichten precies getoond zijn, staat niet in het logboek; zie de pagina met beheervragen.
