# Use cases

De diagrammen elders op deze site laten zien uit welke onderdelen het Federatief Berichtenstelsel
bestaat. De use cases hieronder laten zien wat er gebeurt als iemand het gebruikt of beheert: wie
wat doet, in welke volgorde de onderdelen met elkaar praten, en wat er gebeurt als iets misgaat.

De use cases gelden voor burgers en ondernemers. Ze noemen allebei "de ontvanger". Waar iets
alleen voor een van beide geldt, staat dat erbij.

| Nr | Use case | Actor |
|---|---|---|
| 1 | Een organisatie verstuurt een bericht, en de ontvanger krijgt een melding | Overheidsorganisatie |
| 2 | De ontvanger opent de berichtenbox en ziet de berichten van alle organisaties bij elkaar | Ontvanger |
| 3 | Nieuwe berichten verschijnen terwijl de berichtenbox openstaat | Ontvanger |
| 4 | De ontvanger zoekt op een woord uit de tekst van berichten en bijlagen | Ontvanger |
| 5 | De ontvanger opent een bericht en leest het | Ontvanger |
| 6 | De ontvanger opent of downloadt een bijlage | Ontvanger |
| 7 | De ontvanger verplaatst een bericht naar een map of archiveert het | Ontvanger |
| 8 | De ontvanger verwijdert een bericht | Ontvanger |
| 9 | Een organisatie gaat na of de ontvanger een bericht heeft kunnen zien | Overheidsorganisatie, met de stelselbeheerder |
| 10 | De ontvanger laat alle eigen berichten wissen | Ontvanger, met de stelselbeheerder |
| 11 | Een magazijn is langere tijd onbereikbaar | Ontvanger, stelselbeheerder en organisatie |
| 12 | De ontvanger filtert en sorteert de berichten op onderwerp, afzender, map of datum | Ontvanger |
| 13 | Een organisatie sluit aan op het stelsel, met een eigen berichtenmagazijn | Overheidsorganisatie, met de stelselbeheerder |
| 14 | Een organisatie stapt uit het stelsel | Overheidsorganisatie, met de stelselbeheerder |

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
| Ontvanger | Een burger, of iemand die berichten ontvangt namens een onderneming |
| Overheidsorganisatie | Een organisatie die berichten verstuurt. Elke organisatie heeft haar eigen berichtenmagazijn |
| Interactielaag | Het portaal waarin de ontvanger de berichtenbox ziet. Het stelsel levert de gegevens, het portaal toont ze |
| Berichten Uitvraag Systeem | De centrale dienst waar het portaal berichten opvraagt en beheert. Hij weet welk magazijn bij welke organisatie hoort, en houdt de berichtenlijst van een ingelogde ontvanger vast zolang die ingelogd is |
| Berichtenmagazijn | De opslag van de berichten van één organisatie. Een organisatie host haar magazijn zelf of neemt een gedeeld magazijn af (in het model: bij BBO). Elk magazijn volgt dezelfde stelselafspraken, dus de lijn in de diagrammen staat voor elk magazijn |
| Profiel Service | Dienst met de voorkeuren van de ontvanger: van welke organisaties die berichten via de berichtenbox wil ontvangen. Er is één Profiel Service voor burgers en ondernemers; op termijn kunnen dat er twee worden |
| Notificatie Service | Dienst die de ontvanger laat weten dat er een bericht is, bijvoorbeeld per e-mail. Hij is identiteitsneutraal: één dienst voor burgers en ondernemers, zodat het melden overal hetzelfde voelt |
| Stelselregister | Publiceert de deelnemende organisaties en het adres van hun magazijn als één document, ondertekend door de stelselbeheerder. Een app die berichten rechtstreeks bij de magazijnen ophaalt, leest daar welke magazijnen er zijn |
| Stelselbeheerder | Beheert het stelsel: de afspraken, de lijst van deelnemende organisaties en hun magazijnen, en de afhandeling van vragen en storingen over de keten. Welke organisatie die rol krijgt, maakt voor de use cases niet uit |

## Wat voor alle use cases geldt

**Er is geen centrale opslag van berichten.** Een bericht staat alleen in het magazijn van de
organisatie die het verstuurde. De uitvraag houdt de berichtenlijst van een ontvanger alleen vast
zolang die ingelogd is, en wist de lijst zodra de laatste sessie eindigt.

**Inloggen.** Een burger logt in op het portaal met DigiD, een ondernemer met eHerkenning. Daarmee
staat vast wie de ontvanger is, en voor wie of namens wie die handelt. Machtigingen werken in die
twee stelsels verschillend. Het portaal start een sessie bij de uitvraag, en elk
verzoek over berichten hoort bij zo'n sessie.

**Een sessie verloopt.** Na 15 minuten zonder activiteit, en uiterlijk na 12 uur, eindigt de sessie.
Daarna is opnieuw inloggen nodig, en worden de berichten opnieuw opgehaald.

**Elk magazijn kent een persoon onder een eigen pseudoniem.** Is de ontvanger een burger of een
ondernemer met een BSN, dan krijgt een magazijn niet het BSN, maar een versleutelde verwijzing die
alleen voor dat magazijn bruikbaar is. Zo kunnen magazijnen hun gegevens niet onderling koppelen.
Een onderneming met een openbaar nummer (KVK-nummer of RSIN) krijgt geen pseudoniem.

**Verbindingen tussen organisaties zijn beveiligd en vastgelegd.** Uitvraag, magazijnen en de
diensten van MijnOverheid Zakelijk praten met elkaar via FSC (Federatieve Service Connectiviteit): elke verbinding rust op een afspraak
tussen twee organisaties, en beide kanten weten met wie ze praten.

**Wat het stelsel vastlegt.** Elke dienst die persoonsgegevens verwerkt, legt elke verwerking vast
in zijn eigen Logboek Dataverwerkingen: welke handeling, voor wie, wanneer, en of die lukte. Er is
geen centraal logboek. Wie wil weten wat er met een bericht gebeurd is, raadpleegt de logboeken van
de betrokken diensten (use case 9).

**Nog niet gerealiseerd, voor alle use cases.** Inloggen met DigiD of eHerkenning, de
pseudoniemen per magazijn, machtigingen en de verbindingen via FSC zijn nog niet (volledig)
gebouwd. Nu geeft het portaal bij elk verzoek mee om welke ontvanger het gaat, krijgt elk magazijn
het nummer van de ontvanger, en is per verbinding in te stellen of die via FSC loopt ([#414](https://github.com/MinBZK/MijnOverheidZakelijk/issues/414), [#415](https://github.com/MinBZK/MijnOverheidZakelijk/issues/415), [#552](https://github.com/MinBZK/MijnOverheidZakelijk/issues/552)). De pagina "PoC-afwijkingen"
houdt bij wat nog niet gebouwd is.
