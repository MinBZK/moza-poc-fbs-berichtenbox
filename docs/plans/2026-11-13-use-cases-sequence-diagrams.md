# Use cases met sequence diagrams

**Status:** Uitgevoerd

**Issue:** MinBZK/MijnOverheidZakelijk#1522

## Context

De architectuurdocumentatie (`docs/architecture/`) laat zien uit welke onderdelen het stelsel
bestaat, maar nauwelijks wat er gebeurt als iemand het gebruikt. Alleen het rechtstreeks ophalen
is als verloop uitgewerkt, in twee Structurizr-`dynamic`-views. Het issue vraagt per use case:
actor, aanleiding, verloop als het goed gaat, verloop als het misgaat, een sequence diagram dat
klopt met het huidige gedrag, en de afwijking van de doelarchitectuur.

## Ontwerpkeuzes

### Mermaid `sequenceDiagram` in de workspace-documentatie, niet Structurizr `dynamic`-views

- Een `dynamic`-view kan geen alternatieven of herhaling tonen (`alt`, `opt`, `loop`, `par`).
  Juist de foutpaden zijn wat het issue vraagt. Mermaid kan dat wel.
- De site-generator (`structurizr-site-generatr` 1.5.2) rendert Mermaid in de markdown van
  `workspace-docs/` (lokaal nagegaan: mermaid 11.9, met de bron uitklapbaar onder elk diagram).
- Mermaid is in draw.io te importeren (MinBZK/MijnOverheidZakelijk#686) en gaat zonder herschrijven
  mee naar docs.mijnoverheidzakelijk.nl of moza-site (MinBZK/MijnOverheidZakelijk#1355): daar staat
  al een Mermaid-render in de build.
- Nadeel uit het issue: de deelnemers staan los van het C4-model. Daarom een guard (hieronder).

### Systeemniveau

De diagrammen tonen één lijn per systeem: het Berichten Uitvraag Systeem en het Berichtenmagazijn,
plus de actoren en de diensten van MijnOverheid Zakelijk. De onderdelen binnen een systeem
(Publicatie Stream, sessiecache, magazijnregister) staan alleen op de pagina "Stand van de bouw",
niet in de use cases: op containerniveau werden de diagrammen te druk voor het
doel, een gedeeld beeld van het verloop.

"Berichtenmagazijn" staat voor elk magazijn. Het model kent "Berichtenmagazijn (BBO-gehost)" en
"Berichtenmagazijn (eigen gehost)", maar de koppelvlakken zijn stelselafspraken, dus een use case
geldt niet voor één hosting. De guard aanvaardt daarom de naam zonder toevoeging tussen haakjes.

### Deelnemers zijn elementen uit het model

Elke `participant`/`actor` in een diagram draagt als label exact de naam van een element uit
`workspace.dsl` (of die naam zonder toevoeging tussen haakjes); elke `box` ook. Een guard-script
faalt als:

- een label geen elementnaam is, of een uitgecommentarieerd element noemt;
- een pijl, notitie of (de)activatie een deelnemer noemt die niet gedeclareerd is (Mermaid maakt
  die stil aan, en dan ontloopt hij de naamcontrole);
- een regel in een diagram niet herkend wordt: wat niet gelezen wordt, kan een deelnemer bevatten;
- een use-case-pagina geen sequence diagram heeft, of de controle niets vond om te meten.

Het script leest een strikte deelverzameling van Mermaid: één statement per regel, de kop
`sequenceDiagram` op een eigen regel, en geen directives, frontmatter, boxen, `;` of `#`. Wat
erbuiten valt, faalt. Drie reviewrondes lieten zien dat elke leesregel van Mermaid die de guard
probeert na te bootsen (statements scheiden op `;`, directives over meerdere regels, frontmatter,
boxkleuren, commentaar en entities met `#`) een nieuwe plek opleverde waar een deelnemer ongezien
kon staan. Een strikte deelverzameling sluit die hele klasse in één keer af, en de diagrammen op
systeemniveau hebben geen van die vormen nodig. Binnen de deelverzameling volgt het script Mermaid
11.9: hoofdletterongevoelig, spaties in een id, sleutelwoorden alleen met witruimte erna, alleen
`\n` als regeleinde, en fences van backticks of tildes, ook ingesprongen. Het DSL-commentaar volgt
de parser van Structurizr.

Het script is Python (parsing van markdown en DSL), met een bash-testsuite ernaast volgens het
`test-*.sh`-patroon. Die suite toetst ook de echte documentatie, en draait in `ci-scripts.yml`: een
required check die op elke PR draait. `architecture.yml` is dat niet, en draait alleen bij een
wijziging onder `docs/architecture/`; een hernoemd element in het model zou daar dus niet altijd
langskomen. De suite staat daarom ook in de verplichte set van `ci-scripts.yml`, en de guard heeft
daar een eigen stap tegen de echte documentatie.

### Eindresultaat, niet de huidige code

Na de eerste review door de opdrachtgever: de use cases beschrijven het stelsel zoals het moet
werken, voor lezers die de code niet bekijken. Waar de bouw nog afwijkt, staat dat kort onder "Nog
niet gerealiseerd". Waar een keuze nog open is, beschrijft de use case op dat punt het huidige
gedrag en staat de keuze onder "Open vragen". Wat voor alle use cases nog niet gebouwd is
(eHerkenning, pseudoniemen, machtigingen, FSC), staat één keer in het overzicht.

Endpoints, statuscodes, klassen en instellingen staan niet op de use-case-pagina's maar op één
pagina voor ontwikkelaars, "Stand van de bouw": per use case de afwijking met de plek in de code,
de volgorde waarin het logboek vastlegt, en de koppelvlakken.

### Algemene namen

De deelnemers heten wat ze zijn, niet welk exemplaar uit het model ze zijn. Het model kreeg daarom
algemene namen met de variant tussen haakjes: "Overheidsorganisatie (eigen magazijn)" en
"Overheidsorganisatie (magazijn bij BBO)" in plaats van "Organisatie A" en "Organisatie B", en de
medewerkers op dezelfde manier. Via de verkorte naam staat "Overheidsorganisatie" in de diagrammen.

### Generiek voor burgers en ondernemers

De use cases gelden voor burgers en ondernemers, allebei "de ontvanger". In het model heten de
personen "Ontvanger (burger)" en "Ontvanger (ondernemer)"; de diagrammen tonen "Ontvanger".
Uitgangspunt van de opdrachtgever: één Profiel Service voor beide (op termijn mogelijk twee), en één
identiteitsneutrale Notificatie Service, voor één overheidsbeleving. Waar het verschil ertoe doet,
staat het bij de use case: DigiD of eHerkenning, een pseudoniem alleen voor een BSN, het recht op
wissing (elke burger, bij ondernemers afhankelijk van de rechtsvorm), en de vragen uit het
Logius-overleg die alleen voor bedrijven gelden.

### Filteren en zoeken gescheiden

Use case 12 beschrijft filteren en sorteren op kopgegevens, zoals de berichtenbox dat nu in de
browser doet. Use case 4 beschrijft zoeken in de tekst en bijlagen, bij de magazijnen zelf; dat is
nog niet gebouwd (MinBZK/MijnOverheidZakelijk#1598).

### Issues bij wat nog niet gerealiseerd is

Elk punt onder "Nog niet gerealiseerd" linkt naar een issue in MijnOverheidZakelijk. Wat nog geen
issue had, is aangemaakt als #1586 tot en met #1598, onder #1564. Het model kreeg ook een persoon
"Stelselbeheerder", de actor in de beheer-use-cases.

### Beheersituaties zijn use cases

De drie situaties uit het overleg met Logius (aantonen dat een bericht gezien kon worden, alle
berichten van een ondernemer wissen, een magazijn dat lang plat ligt) zijn use case 9 tot en met
11, ook waar ze nog niet gebouwd of afgesproken zijn. Het verloop daarvan is een voorstel; wat nog
afgesproken moet worden, staat onder "Open vragen".

### Aansluiten en uitstappen

Later toegevoegd: use case 13 (een organisatie sluit aan: magazijn neerzetten, verbindingen
afspreken, opnemen in register en stelseldocument, bekend maken bij de Profiel Service) en use
case 14 (een organisatie stapt uit: aankondigen, overgangstermijn, uit register en Profiel
Service, berichten overdragen, archiveren of vernietigen). Ook dit zijn beheersituaties, met de
stelselbeheerder als tweede actor. Het Stelselregister staat als deelnemer in het overzicht,
omdat de stelselbeheerder het in beide use cases bijwerkt. Een overstap naar een ander magazijn is
geen uitstappen en staat als open vraag bij use case 14.

### Rechtstreeks ophalen nog niet uitgewerkt

Use case 9 uit het voorstel (een burger of ondernemer haalt berichten met een app rechtstreeks bij
de magazijnen op) is weggelaten op verzoek van de opdrachtgever: die route is nog te veel in
ontwikkeling. Het overzicht verwijst naar de bestaande `dynamic`-views `RechtstreeksOphalen*`.

## Structuur

| Bestand | Inhoud |
|---|---|
| `docs/architecture/workspace-docs/04-use-cases.md` | Overzicht, leeswijzer, deelnemers, wat voor alle use cases geldt |
| `05-` t/m `12-` | Use case 1 t/m 8 uit het issue, één pagina per use case |
| `13-` t/m `15-` | Use case 9 t/m 11: de beheersituaties uit het overleg met Logius |
| `16-` | Use case 12: filteren en sorteren op kopgegevens |
| `17-` en `18-` | Use case 13 en 14: een organisatie sluit aan, of stapt uit |
| `19-use-cases-stand-van-de-bouw.md` | Voor ontwikkelaars: afwijkingen met de plek in de code, logboekvolgorde, koppelvlakken |
| `docs/architecture/workspace.dsl` | Algemene namen voor organisaties, ondernemer en medewerkers, en de persoon Stelselbeheerder |
| `.github/scripts/use-case-deelnemers.py` | Guard |
| `.github/scripts/test-use-case-deelnemers.sh` | Testsuite van de guard, op fixtures en op de echte documentatie |
| `.github/workflows/ci-scripts.yml` | Eigen stap voor de guard, verwacht aantal asserties van de suite, en de suite in de verplichte set |

## Verificatie

- Guard groen op de documentatie, rood op elke fixture-variant. Elke controle in het script is
  gemuteerd in een kopie; de suite ving alle 58 mutaties. Waar de guard Mermaid volgt, is het
  gedrag in de reviewrondes nagelopen met Mermaid 11.9.0; voor het DSL-commentaar met de parser van
  Structurizr. De overstap naar de strikte deelverzameling kwam na de laatste reviewronde.
- Elk Mermaid-blok rendert met `mermaid-cli` 11.9.0 (dezelfde versie als de site).
- De site genereert lokaal met `structurizr-site-generatr:1.5.2`; elke use-case-pagina staat in
  het menu en toont zijn diagram.
- Inhoud per use case nagelopen tegen de code (stappen, statuscodes, timeouts), en in vier
  reviewrondes opnieuw, deels tegen een echte zoekindex.
