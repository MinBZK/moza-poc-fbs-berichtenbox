# Verantwoording inzet van generatieve AI in de Berichtenbox-PoC

**Toetsing en verantwoording i.h.k.v. het Overheidsbreed Standpunt Generatieve AI**

Dit document verantwoordt het gebruik van generatieve AI bij het bouwen van deze
Proof of Concept (PoC). Voor een beknopte samenvatting, zie
[`DISCLAIMER.md`](../DISCLAIMER.md).

## Beschrijving van de PoC en de rol van AI

Deze repository is een PoC voor de Berichtenbox binnen het Federatief
Berichtenstelsel (FBS). De PoC bestaat uit twee actieve services —
**Berichtensessiecache** (ophalen en weergeven van berichten) en
**Berichtenmagazijn** (opslaan van berichten) — plus een gedeelde library
`fbs-common`. Het doel is het ontvangen, opslaan en ophalen van berichten binnen
MijnOverheid Zakelijk (MOZa) te verkennen.

**Rol van AI.** De code is grotendeels gegenereerd met de AI-assistant
Claude Code (Anthropic). AI is ingezet voor codegeneratie en voor ondersteuning
bij refactoring en review. Architectuur- en ontwerpbeslissingen zijn vastgelegd
in [`docs/plans/`](plans/).

**Menselijke review.** Alle met AI gegenereerde bijdragen worden volledig door
ontwikkelaars beoordeeld voordat ze worden opgenomen in de hoofdbranch. Dit
gebeurt via de pull-request-workflow met code-eigenaarschap (`CODEOWNERS`) en een
CI-pijplijn met onder andere CodeQL-securityscanning en OpenSSF Scorecard. De
mens blijft eindverantwoordelijk; de AI is een hulpmiddel.

**Gegevens.** De PoC verwerkt geen persoonsgegevens. Er wordt uitsluitend
gewerkt met fictieve en testgegevens.

**Scope-grens.** Deze verantwoording betreft uitsluitend de PoC. Eventueel
gebruik in een pilot of productie valt **buiten de huidige scope** en vereist
aanvullende toetsing, waaronder een beoordeling tegen de BIO (Baseline
Informatiebeveiliging Overheid) en een DPIA (Data Protection Impact Assessment).

## Verantwoording per stappenplan

Hieronder volgen we het globale stappenplan uit hoofdstuk 4 van de
[Overheidsbrede handreiking verantwoorde inzet van generatieve AI](https://open.overheid.nl/documenten/9c273b71-cebb-4e11-b06f-fa20f7b4b90e/file).

### 1) Doel en toepassingsgebied

*Doel:* onderzoeken of een Berichtenbox-PoC verantwoord met behulp van
generatieve AI te bouwen is, en of de resultaten aantoonbaar in lijn te brengen
zijn met de standaarden, kaders en richtlijnen van de Nederlandse overheid
(o.a. NL API Design Rules, FBS, relevante Logius-standaarden).

*Toepassingsgebied:* de ontwikkeling van deze experimentele PoC. Niet in scope:
gebruik in pilot of productie.

### 2) Zorg voor de juiste mensen en vaardigheden

Bij de PoC zijn ontwikkelaars met domein- en standaardenkennis betrokken. AI
wordt ingezet als gereedschap onder menselijke regie; de beoordeling van
gegenereerde code gebeurt door mensen die de betreffende standaarden en het
domein begrijpen.

### 3) Creëer een (generatieve) AI-governance structuur

Het werk gebeurt in opdracht van het Ministerie van Binnenlandse Zaken en
Koninkrijksrelaties (BZK). Als beleidsmatige leidraad gelden het
[Overheidsbreed standpunt voor de inzet van generatieve AI](https://open.overheid.nl/documenten/bc03ce31-0cf1-4946-9c94-e934a62ebe73/file)
en de bijbehorende
[handreiking](https://open.overheid.nl/documenten/9c273b71-cebb-4e11-b06f-fa20f7b4b90e/file).

Concrete governance-maatregelen:

- Met AI gegenereerde bijdragen zijn herkenbaar gemarkeerd via de commit-trailer
  `Co-Authored-By`.
- Alle bijdragen worden volledig menselijk gereviewd via de
  pull-request-workflow vóór merge.
- Voor maximale transparantie is de repository openbaar en onder een open
  licentie ([EUPL-1.2](../LICENSE)); reageren kan via GitHub-issues.

### 4) Risicoanalyse

De gangbare assessment-instrumenten gaan er doorgaans van uit dat een
organisatie zelf een AI-systeem bouwt of structureel inzet. Dat is hier niet het
geval: we gebruiken een AI-assistant als gereedschap, bouwen zelf geen
AI-systeem, nemen niets in productie en verwerken geen persoonsgegevens. Dit
beperkt de gebruikelijke AI-risico's (zoals bias en ethische risico's). De
volgende aandachtspunten blijven relevant.

#### a. Voldoen aan de EU AI-verordening

Wij maken geen AI-systeem maar gebruiken Claude Code als gereedschap. De
verplichtingen vallen primair op de aanbieder (Anthropic). Anthropic is
ondertekenaar van de
[General Purpose AI Code of Practice](https://digital-strategy.ec.europa.eu/en/policies/contents-code-gpai)
van de EU. We houden bij welke AI-assistant en modellen we gebruiken en markeren
de met AI gegenereerde output als zodanig.

#### b. AVG en DPIA

De PoC verwerkt geen persoonsgegevens; er wordt uitsluitend met fictieve/test­data
gewerkt. Organisaties die deze code later in een pilot of productie zouden
gebruiken, dienen op dat moment zelf te beoordelen welke AVG-verplichtingen van
toepassing zijn, waaronder een eventuele DPIA.

#### c. BIO en beveiligingseisen

Voor experimentele PoC-code die niet in productie gaat, gelden geen
BIO-verplichtingen. Wel kent het project basismaatregelen: CodeQL-securityscans,
OpenSSF Scorecard en aandacht voor dependencies en secrets in CI. Gebruik in
pilot of productie vereist een volledige toetsing aan de geldende
beveiligingseisen.

#### d. Datadeling met de AI-aanbieder

Het risico op datadeling wordt beperkt doordat geen vertrouwelijke gegevens of
persoonsgegevens worden gebruikt, en doordat in de instellingen van de
AI-assistant is gekozen voor de opt-out voor modeltraining. De gebruikte
AI-licenties worden aangeschaft door een overheidsorganisatie.

#### e. Risico op "schijnzekerheid"

Het inzetten van AI bij de ontwikkeling van software is geen compliance-garantie.
De officiële brondocumenten (zoals de beschrijvingen van standaarden) zijn altijd
leidend. Het team blijft zelf verantwoordelijk voor het voldoen aan standaarden
en richtlijnen. AI is slechts een hulpmiddel.

#### f. Kwaliteitsrisico: onjuiste of onveilige gegenereerde code

De kwaliteit wordt op meerdere niveaus geborgd: volledige menselijke
code-review, een teststrategie met onder meer ≥90% line coverage (JaCoCo), een
spec-driven OpenAPI-first-aanpak (compilatie faalt bij afwijking van de spec) en
geautomatiseerde CI-controles.

#### g. Auteursrecht op brondocumenten als input

Per gebruikte standaard of bron wordt gecontroleerd of deze als input voor een
AI-assistant gebruikt mag worden. Brondocumentatie wordt vermeld, met
bijbehorende licentie-informatie waar nodig.

#### h. Uitlegbaarheid / gevaar op "black box"

De gegenereerde code en de architectuur (vastgelegd in een C4-model met
Structurizr) zijn openbaar en in voor mensen leesbare vorm gepubliceerd.
Ontwerpbeslissingen worden vastgelegd in [`docs/plans/`](plans/).

#### i. AI-geletterdheid van betrokken medewerkers

Kennis over de inzet van AI-assistants wordt binnen het team gedeeld; deze
verantwoording wordt openbaar gepubliceerd.

### 5) Generatieve AI inkopen of bouwen

#### a. Vendor lock-in

Op dit moment wordt uitsluitend Claude Code gebruikt. Dat is een expliciet
aandachtspunt voor een eventueel vervolg. De opgeleverde software zelf is
leverancier-onafhankelijk (Quarkus/Kotlin) en kent geen runtime-afhankelijkheid
van een AI-aanbieder; een andere AI-assistant kan in een vervolg worden ingezet.

#### b. Keuze voor de AI-assistant

In deze PoC is gekozen voor Claude Code (Anthropic), een aanbieder die de EU
General Purpose AI Code of Practice heeft ondertekend.
