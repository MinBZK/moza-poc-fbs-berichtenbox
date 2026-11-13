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
(Publicatie Stream, sessiecache, magazijnregister) staan in de tekst en de technische verwijzing
waar het ertoe doet, niet in het diagram: op containerniveau werden de diagrammen te druk voor het
doel, een gedeeld beeld van het verloop.

"Berichtenmagazijn" staat voor elk magazijn. Het model kent "Berichtenmagazijn (BBO-gehost)" en
"Berichtenmagazijn (eigen gehost)", maar de koppelvlakken zijn stelselafspraken, dus een use case
geldt niet voor één hosting. De guard aanvaardt daarom de naam zonder toevoeging tussen haakjes.

### Deelnemers zijn elementen uit het model

Elke `participant`/`actor` in een diagram draagt als label exact de naam van een element uit
`workspace.dsl` (of die naam zonder toevoeging tussen haakjes); elke `box` ook. Een guard-script
faalt als:

- een label geen elementnaam is, of een uitgecommentarieerd element noemt;
- een deelnemer in een `box` in het model geen onderdeel is van het element van de box;
- een pijl, notitie of (de)activatie een deelnemer noemt die niet gedeclareerd is (Mermaid maakt
  die stil aan, en dan ontloopt hij de naamcontrole);
- een regel in een diagram niet herkend wordt: wat niet gelezen wordt, kan een deelnemer bevatten;
- een use-case-pagina geen sequence diagram heeft, of de controle niets vond om te meten.

Het script volgt de sequence-lexer van Mermaid 11.9: hoofdletterongevoelig, `;` als scheiding
tussen statements (ook op de regel van de kop), spaties in een id, directives overal, en fences van
backticks of tildes, ook ingesprongen.

Het script is Python (parsing van markdown en DSL), met een bash-testsuite ernaast volgens het
`test-*.sh`-patroon. Die suite toetst ook de echte documentatie, en draait in `ci-scripts.yml`: een
required check die op elke PR draait. `architecture.yml` is dat niet, en draait alleen bij een
wijziging onder `docs/architecture/`; een hernoemd element in het model zou daar dus niet altijd
langskomen. De suite staat daarom ook in de verplichte set van `ci-scripts.yml`, en de guard heeft
daar een eigen stap tegen de echte documentatie.

### Huidig gedrag, doelbeeld apart benoemd

Het diagram toont wat er nu draait. Wat de doelarchitectuur anders doet (token in plaats van
`X-Ontvanger`, BSNk-pseudoniemen, FSC verplicht, machtigingen), staat per use case in een eigen
sectie. Zo blijft het diagram toetsbaar tegen de code. Gedrag dat de code heeft maar dat misschien
niet bedoeld is (een bericht vóór zijn publicatiedatum zichtbaar, zoeken op meerdere woorden),
staat als feit in de use case en als open vraag in de PR.

### Taal

Bovenste deel per pagina zonder technische voorkennis; endpoints, statuscodes en codeverwijzingen
in een afsluitende sectie "Technische verwijzing".

### Rechtstreeks ophalen nog niet uitgewerkt

Use case 9 uit het voorstel (een burger of ondernemer haalt berichten met een app rechtstreeks bij
de magazijnen op) is weggelaten op verzoek van de opdrachtgever: die route is nog te veel in
ontwikkeling. Het overzicht verwijst naar de bestaande `dynamic`-views `RechtstreeksOphalen*`.

## Structuur

| Bestand | Inhoud |
|---|---|
| `docs/architecture/workspace-docs/04-use-cases.md` | Overzicht, leeswijzer, deelnemers, wat voor alle use cases geldt |
| `05-` t/m `12-` | Use case 1 t/m 8 uit het issue, één pagina per use case |
| `13-beheervragen.md` | De drie beheersituaties uit het overleg met Logius: wat het stelsel nu wel en niet kan tonen |
| `.github/scripts/use-case-deelnemers.py` | Guard |
| `.github/scripts/test-use-case-deelnemers.sh` | Testsuite van de guard, op fixtures en op de echte documentatie |
| `.github/workflows/ci-scripts.yml` | Eigen stap voor de guard, verwacht aantal asserties van de suite, en de suite in de verplichte set |

## Verificatie

- Guard groen op de documentatie, rood op elke fixture-variant. Elke controle in het script is
  gemuteerd in een kopie; de suite ving alle 64 mutaties. Waar de guard Mermaid volgt (directives,
  boxkleuren, sleutelwoorden), is het gedrag nagelopen met Mermaid 11.9.0; voor het DSL-commentaar
  met de parser van Structurizr.
- Elk Mermaid-blok rendert met `mermaid-cli` 11.9.0 (dezelfde versie als de site).
- De site genereert lokaal met `structurizr-site-generatr:1.5.2`; elke use-case-pagina staat in
  het menu en toont zijn diagram.
- Inhoud per use case nagelopen tegen de code (stappen, statuscodes, timeouts), en in de
  reviewronde opnieuw door twee reviewers.
