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
| 9 | Een burger of ondernemer haalt berichten rechtstreeks bij de magazijnen op | Burger of ondernemer |

Daarna volgt een pagina met drie vragen uit het beheer van het stelsel, en wat het stelsel daar
nu wel en niet op kan antwoorden.

## Zo lees je een use case

Elke use case heeft dezelfde opbouw:

- **Actor, aanleiding en resultaat**: wie begint, waarom, en wat er is als het goed gaat.
- **Verloop**: de stappen in gewone taal.
- **Sequence diagram**: dezelfde stappen als plaatje. Van boven naar beneden is de tijd. Een
  doorgetrokken pijl is een verzoek, een gestippelde pijl het antwoord. Een pijl met een open punt
  is een bericht waar de afzender niet op wacht. Een kader met `alt` toont alternatieven, `opt`
  iets dat alleen soms gebeurt, `loop` iets dat zich herhaalt en `par` dingen die tegelijk
  gebeuren.
- **Als het misgaat**: per situatie wat het stelsel doet en wat de actor merkt.
- **Doelarchitectuur**: waar het doelbeeld afwijkt van wat er nu draait.
- **Technische verwijzing**: de koppelvlakken en instellingen achter het verloop, voor wie het in de
  code wil nalopen.

De diagrammen tonen hoe het stelsel zich **nu** gedraagt. Wat in het doelbeeld anders gaat, staat
bij de use case zelf en op de pagina met PoC-afwijkingen.

## De deelnemers

De namen in de diagrammen zijn de namen van onderdelen uit het architectuurmodel, zodat je ze in de
andere diagrammen terugvindt. Bij het bouwen van deze site controleert een script dat elke
deelnemer een onderdeel uit het model is.

| Deelnemer | Wat het is |
|---|---|
| Ondernemer / Zakelijke gebruiker | De ontvanger van berichten, namens een onderneming |
| Organisatie B | Een overheidsorganisatie die berichten verstuurt |
| Interactielaag | Het portaal waarin de ondernemer zijn berichtenbox ziet. Het stelsel levert de gegevens, het portaal toont ze |
| Berichten Uitvraag Service | De centrale dienst waar het portaal berichten opvraagt en beheert |
| Berichtensessiecache | Het tijdelijke geheugen van de uitvraag: de opgehaalde berichtenlijst van een ingelogde ondernemer, zolang hij ingelogd is |
| Aanmeld Service | Het deel van de uitvraag dat nieuwe berichten van een magazijn aanneemt |
| Magazijnregister | Welke organisatie welk berichtenmagazijn heeft, en waar dat te bereiken is |
| Berichtenmagazijn | De opslag van de berichten van één organisatie. Elke organisatie heeft er één: zelf gehost, of afgenomen bij BBO. Een magazijn bestaat uit een Aanlever API, een Ophaal- en Beheer API, validatie, autorisatie, de Publicatie Stream en de Dataopslag |
| Profiel Service | Dienst van MijnOverheid Zakelijk met de voorkeuren van de ondernemer: van welke organisaties hij berichten via de berichtenbox wil ontvangen |
| Notificatie Service | Dienst van MijnOverheid Zakelijk die de ondernemer laat weten dat er een bericht is, bijvoorbeeld per e-mail |
| Stelselregister | Publiceert de lijst van deelnemende organisaties en hun magazijnen, ondertekend |

## Wat voor alle use cases geldt

**Er is geen centrale opslag van berichten.** Een bericht staat alleen in het magazijn van de
organisatie die het verstuurde. De uitvraag houdt de berichtenlijst van een ondernemer alleen vast
zolang hij ingelogd is, en wist die zodra zijn laatste sessie eindigt.

**Inloggen is in deze opstelling nog niet gebouwd.** Het portaal geeft bij elk verzoek mee om welke
ondernemer het gaat, en start bij het inloggen een sessie bij de uitvraag. Elk verzoek over
berichten moet bij een lopende sessie van die ondernemer horen. In het doelbeeld bewijst een token
uit eHerkenning wie de ondernemer is; zie de pagina met PoC-afwijkingen.

**Een sessie verloopt.** Na 15 minuten zonder activiteit, en uiterlijk na 12 uur, eindigt de sessie.
Het portaal kan een kortere grens meegeven en beëindigt de sessie zelf bij uitloggen. Daarna is
opnieuw inloggen nodig, en worden de berichten opnieuw opgehaald.

**Wat het stelsel vastlegt.** Elke dienst die persoonsgegevens verwerkt, legt elke verwerking vast
in zijn eigen Logboek Dataverwerkingen: welke handeling, voor welke ondernemer, en of die lukte.
Een handeling waarvan de vastlegging mislukt, telt niet als uitgevoerd; het magazijn slaat een
aangeleverd bericht dan bijvoorbeeld niet op. Alleen het wissen van gegevens aan het einde van een
sessie gaat dan toch door, met een waarschuwing aan de beheerder. Welke berichten precies getoond
zijn, staat niet in het logboek; zie de pagina met beheervragen.
