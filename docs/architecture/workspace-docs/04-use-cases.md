# Use cases

De diagrammen elders op deze site laten zien uit welke onderdelen het Federatief Berichtenstelsel
bestaat. De use cases hieronder laten zien wat er gebeurt als iemand het gebruikt of beheert: wie
wat doet, in welke volgorde de onderdelen met elkaar praten, en wat er gebeurt als iets misgaat.

| Nr | Use case | Actor |
|---|---|---|
| 1 | Een organisatie verstuurt een bericht, en de ondernemer krijgt een melding | Overheidsorganisatie |
| 2 | Een ondernemer opent de berichtenbox en ziet de berichten van alle organisaties bij elkaar | Ondernemer |
| 3 | Nieuwe berichten verschijnen terwijl de berichtenbox openstaat | Ondernemer |
| 4 | Een ondernemer zoekt op een woord uit de tekst van berichten en bijlagen | Ondernemer |
| 5 | Een ondernemer opent een bericht en leest het | Ondernemer |
| 6 | Een ondernemer opent of downloadt een bijlage | Ondernemer |
| 7 | Een ondernemer verplaatst een bericht naar een map of archiveert het | Ondernemer |
| 8 | Een ondernemer verwijdert een bericht | Ondernemer |
| 9 | Een organisatie gaat na of een ondernemer een bericht heeft kunnen zien | Overheidsorganisatie, met de stelselbeheerder |
| 10 | Een ondernemer laat al zijn berichten wissen | Ondernemer, met de stelselbeheerder |
| 11 | Een magazijn is langere tijd onbereikbaar | Ondernemer, stelselbeheerder en organisatie |
| 12 | Een ondernemer filtert en sorteert zijn berichten op onderwerp, afzender, map of datum | Ondernemer |

Het rechtstreeks ophalen door een burger of ondernemer, met een app en zonder portaal of uitvraag
ertussen, staat hier nog niet: die route is nog te veel in ontwikkeling. Het doelbeeld daarvan
staat in de views *RechtstreeksOphalen* van het architectuurmodel.

## Zo lees je een use case

Een use case beschrijft hoe het stelsel moet werken. Elke use case heeft dezelfde opbouw:

- **Actor, aanleiding en resultaat**: wie begint, waarom, en wat er is als het goed gaat.
- **Verloop**: de stappen in gewone taal.
- **Sequence diagram**: dezelfde stappen als plaatje. Van boven naar beneden is de tijd. Een
  doorgetrokken pijl is een verzoek, een gestippelde pijl het antwoord. Een pijl met een open punt
  is een verzoek waar de afzender niet op wacht. Een kader met `alt` toont alternatieven, `opt`
  iets dat alleen soms gebeurt, `loop` iets dat zich herhaalt en `par` dingen die tegelijk
  gebeuren.
- **Als het misgaat**: per situatie wat het stelsel doet en wat de actor merkt.
- **Nog niet gerealiseerd**: waar wat er nu gebouwd is nog anders werkt dan de use case beschrijft.
- **Open vragen**: keuzes die nog gemaakt moeten worden. Zolang een keuze open is, beschrijft de
  use case op dat punt hoe het nu werkt.

Voor ontwikkelaars staat op de pagina "Stand van de bouw", na de use cases, per use case waar de
software nog afwijkt, met de plek in de code.

## De deelnemers

De diagrammen tonen het stelsel op het niveau van de systemen. De namen komen uit het
architectuurmodel, zodat je ze in de andere diagrammen terugvindt.

| Deelnemer | Wat het is |
|---|---|
| Ondernemer | Iemand die berichten ontvangt namens een onderneming |
| Overheidsorganisatie | Een organisatie die berichten verstuurt. Elke organisatie heeft haar eigen berichtenmagazijn |
| Interactielaag | Het portaal waarin de ondernemer zijn berichtenbox ziet. Het stelsel levert de gegevens, het portaal toont ze |
| Berichten Uitvraag Systeem | De centrale dienst waar het portaal berichten opvraagt en beheert. Hij weet welk magazijn bij welke organisatie hoort, en houdt de berichtenlijst van een ingelogde ondernemer vast zolang die ingelogd is |
| Berichtenmagazijn | De opslag van de berichten van één organisatie. Een organisatie host haar magazijn zelf of neemt een gedeeld magazijn af (in het model: bij BBO). Elk magazijn volgt dezelfde stelselafspraken, dus de lijn in de diagrammen staat voor elk magazijn |
| Profiel Service | Dienst van MijnOverheid Zakelijk met de voorkeuren van de ondernemer: van welke organisaties hij berichten via de berichtenbox wil ontvangen |
| Notificatie Service | Dienst van MijnOverheid Zakelijk die de ondernemer laat weten dat er een bericht is, bijvoorbeeld per e-mail |
| Stelselbeheerder | Beheert het stelsel: de afspraken, de lijst van deelnemende organisaties en hun magazijnen, en de afhandeling van vragen en storingen over de keten. Welke organisatie die rol krijgt, maakt voor de use cases niet uit |

## Wat voor alle use cases geldt

**Er is geen centrale opslag van berichten.** Een bericht staat alleen in het magazijn van de
organisatie die het verstuurde. De uitvraag houdt de berichtenlijst van een ondernemer alleen vast
zolang hij ingelogd is, en wist die zodra zijn laatste sessie eindigt.

**Inloggen.** De ondernemer logt in op het portaal met eHerkenning. Daarmee staat vast wie hij is
en namens welke onderneming hij handelt. Het portaal start een sessie bij de uitvraag, en elk
verzoek over berichten hoort bij zo'n sessie.

**Een sessie verloopt.** Na 15 minuten zonder activiteit, en uiterlijk na 12 uur, eindigt de sessie.
Daarna is opnieuw inloggen nodig, en worden de berichten opnieuw opgehaald.

**Elk magazijn kent de ondernemer onder een eigen pseudoniem.** Een magazijn krijgt niet het
nummer van de ondernemer, maar een versleutelde verwijzing die alleen voor dat magazijn bruikbaar
is. Zo kunnen magazijnen hun gegevens niet onderling koppelen.

**Verbindingen tussen organisaties zijn beveiligd en vastgelegd.** Uitvraag, magazijnen en de
diensten van MijnOverheid Zakelijk praten met elkaar via FSC (Federatieve Service Connectiviteit): elke verbinding rust op een afspraak
tussen twee organisaties, en beide kanten weten met wie ze praten.

**Wat het stelsel vastlegt.** Elke dienst die persoonsgegevens verwerkt, legt elke verwerking vast
in zijn eigen Logboek Dataverwerkingen: welke handeling, voor wie, wanneer, en of die lukte. Er is
geen centraal logboek. Wie wil weten wat er met een bericht gebeurd is, raadpleegt de logboeken van
de betrokken diensten (use case 9).

**Nog niet gerealiseerd, voor alle use cases.** Inloggen met eHerkenning, de pseudoniemen per
magazijn, machtigingen en de verbindingen via FSC zijn nog niet (volledig) gebouwd. Nu geeft het
portaal bij elk verzoek mee om welke ondernemer het gaat, krijgt elk magazijn het nummer van de
ondernemer, en is per verbinding in te stellen of die via FSC loopt ([#414](https://github.com/MinBZK/MijnOverheidZakelijk/issues/414), [#415](https://github.com/MinBZK/MijnOverheidZakelijk/issues/415), [#552](https://github.com/MinBZK/MijnOverheidZakelijk/issues/552)). De pagina "PoC-afwijkingen"
houdt bij wat nog niet gebouwd is.
