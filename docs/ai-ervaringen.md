# Ervaringen met AI-ondersteund ontwikkelen

**Status:** Levend document (wordt periodiek aangevuld)

Dit document legt onze praktische ervaringen vast met het bouwen van deze PoC met
een AI-assistent. In ons geval gebruiken we uitsluitend Claude Code (vanaf nu
afgekort met CC). De insteek is concreet en direct: wat werkte, wat niet, en welke
afspraken we onderweg hebben gemaakt om de samenwerking productief te houden.

Het is geen verantwoordingsdocument. Daarvoor verwijzen we naar
[`ai-verantwoording.md`](ai-verantwoording.md). Hier gaat het puur om de praktijk:
lessen die voor een volgend team of een vervolgfase nuttig zijn.

## Maart t/m mei 2026: Opstarten en bouwen

Dit zijn de ervaringen tot nu toe, en ze gaan uitsluitend over het bouwtraject. We
hebben tot dusver een reeks losse componenten laten bouwen op basis van het
[C4-model](https://minbzk.github.io/moza-poc-fbs-berichtenbox/master/).

Hierbij past een belangrijke beperking. Op dit moment bestaan er alleen losse
componenten, die we met Bruno-scripts afzonderlijk kunnen aanspreken. Er is nog
geen werkend geheel en nog geen demo-engine, dus functioneel end-to-end beproeven
kan nog niet. Een eind-oordeel over de aanpak is daarom nog niet mogelijk.

### Ervaringen in grote lijnen

Voor ons is CC een zeer waardevolle aanvulling op ons team geweest. Als je het zou
uitdrukken in toevoeging aan het team, dan varieert dat sterk tussen:

- Een externe die even over onze schouder meekijkt, tot
- Een aantal teamleden die tickets voor ons oplossen

Waarbij in beide gevallen de kwaliteit van zeer junior tot zeer senior ging, maar
gemiddeld toch echt wel sterk was. Het aantal keren dat we elkaar in de armen
moesten knijpen met "gebeurt dit nu echt?" is groter dan de irritatie als het slecht
loopt.

Een paar dingen die regelmatig terugkomen en horen bij "standaard AI-gebruik":

- Soms maakt CC nog in detail fouten.
- Soms zit CC vast in een slechte feedback loop en moet je bijsturen door
  te zeggen "je gaat in rondjes, dit werkt niet".

Maar dit is wel op het niveau van het hebben van "een slechte dag". Over het
algemeen is wat er opgeleverd wordt erg goed.

Het allerbelangrijkste is om niet uit te gaan van een enkele bouw-fase. Er is altijd
een ronde van review en fixes nodig. Voor ons werkt het nu het beste om in een
prompt te vragen wat er gebeuren moet en aansluitend drie review+fix-rondes te
vragen, gebruikmakend van de skills die we toegevoegd hebben. Pas daarna kijken we
naar het resultaat.

### Overheid AI-assistent skills

We maken dankbaar gebruik van de
[overheid-skills van developer.overheid.nl](https://github.com/developer-overheid-nl/skills-marketplace/).
Als ontwikkelaars kennen we de meeste van deze standaarden niet tot in detail. Het
zou veel tijd vergen als we ze ons eerst eigen maken, voordat we zouden beginnen
aan het ontwikkelen van de componenten.

We kiezen ervoor om op deze skills te vertrouwen. We hopen daarmee in de praktijk
te ervaren hoe de standaarden uitpakken en gaandeweg ervaring en kennis op te doen.

We roepen bij het maken van plannen, ontwerpen en review-rondes expliciet deze
skills aan.

- CC kan hier goed gebruik van maken. We zien dat hij echt focus krijgt op
  het naleven van richtlijnen, standaarden en werkwijzen die erin vastgelegd zijn.
- De mate van detail, met name bij reviews, is "onmenselijk". We hadden dit zelf
  nooit op dit niveau kunnen toepassen. Dit geeft vertrouwen in de code en het
  product dat opgeleverd wordt.

### C4-model als basis

Het [C4-model](https://minbzk.github.io/moza-poc-fbs-berichtenbox/master/) is de
basis voor het ontwikkelen van onze componenten. Dit is ook met behulp van AI
opgesteld, met gebruik van de skills hierboven. Uit een eerder project hadden we al
ervaren dat het helpt om een model te hebben op basis waarvan CC kan
werken.

Zonder zo'n plan is er te veel vrijheid en is het lastiger voor CC om een
samenhangend geheel te maken.

### Selectief human-in-the-loop

#### Plannen

We laten de AI plannen maken voor elke taak die het krijgt (groot of klein). Het
C4-model is hier het kader voor. We laten deze in `docs/plans` opslaan ter
referentie.

> Persoonlijk merkte ik dat ik gaandeweg minder belang hechtte aan deze plannen. Ik
> kreeg steeds meer het gevoel dat CC "gewoon wist" hoe het project in
> elkaar stak en met de kaders uit de voeten kon om iets goeds op te leveren.

#### Beperkte review

Na het uitvoeren van de plannen reviewen we het opgeleverde werk via een
Pull-Request (PR). De test-code reviewen we niet regel voor regel. Die keuze is
praktisch en bewust. We werken nu in hoog tempo, en er wordt zoveel code
gegenereerd, met name tests, dat twee ontwikkelaars dat onmogelijk allemaal
grondig kunnen reviewen.

We gaan uit van goede kwaliteit en leunen op een brede set QA-tooling (detekt,
JaCoCo-drempel, CodeQL, Spectral, OpenSSF Scorecard) en de overheid-skills om de
(test)kwaliteit te borgen.

Daarnaast willen we vooral functioneel testen. Op de backlog staat een
**demo-engine** om allerlei scenario's mee te kunnen afspelen en functioneel te
testen via de te ontwikkelen frontend.

Dit project is (nog) een PoC, dus focus op kwaliteit is niet de prioriteit, daarom
is een hoge snelheid met minder precisie een optie. Wel nemen we mee dat er een
kans is dat dit toch naar productie gebracht moet worden.

#### Kleiner is alsnog beter

Overigens zijn we wel tot de conclusie gekomen dat het alsnog belangrijk is om in
kleinere brokken te werken. Tot nu toe hebben we relatief grote brokken in één keer
laten oppakken. En ondanks dat we tests niet bekeken, bleek het alsnog een flinke
kluif om te reviewen. Wanneer CC aangeeft dat iets in een los PR zou
moeten, is het verstandig om daar goed over na te denken. Het kost ook weinig extra
moeite en tijd omdat CC dit allemaal verzorgt.

### Kennisniveau

Wat we gemerkt hebben is dat CC erg veel "weet". Het hele internet is zijn
geheugen. Dit leidt tot oplossingen waar we zelf nooit aan hadden gedacht of lang
naar hadden moeten zoeken of inlezen.

Wel is het zo dat we hiermee oppervlakkige kennis opdoen, omdat we het op een
presenteerblaadje aangereikt krijgen en we weer snel door kunnen. Maar dat is ook
omdat we hier een PoC ontwikkelen en dat een bewuste keuze is.

Daarnaast heeft hij meer doorzettingsvermogen. Als een open source tool niet werkt
zoals verwacht, gaat CC de sources in om te achterhalen wat het echte
gedrag is. Iets wat een ontwikkelaar niet snel zou doen. Nu is dit niet per se
goed, maar geeft wel meer opties.

In een ander geval konden we weinig vinden over het implementeren van een techniek
waar we weinig van wisten. We liepen vast met foutmeldingen. StackOverflow gaf geen
resultaten, de documentatie was niet uitgebreid genoeg en Google gaf geen relevante
resultaten. CC had het probleem snel gevonden. In zijn model zat hier blijkbaar toch
meer kennis over, of hij kon wel de juiste informatie achterhalen.

### Maximale code-vangrails

We zijn steeds meer automatische code-kwaliteit-checks gaan toevoegen. CC
noemen we soms "Claudje-van-Leiden". Het lijkt erop dat het doorzettingsvermogen
snel kan dalen en werk "afgeraffeld" lijkt, of dat de te makkelijke oplossing
gekozen wordt.

Dit kan te weinig of slechte tests opleveren, spaghetti code, etc. Er zijn tools die
dit automatisch aantonen en daarmee CC forceren om het op te lossen.

- Kotlin code-kwaliteit met [detekt](https://detekt.dev/) op `maxIssues: 0`
  voorkomt spaghetti code en veel code-smell.
- [CodeQL](https://codeql.github.com/) binnen GitHub doet security scanning.
- [Scorecard](https://github.com/ossf/scorecard) scant op open source richtlijnen.
- [JaCoCo](https://www.jacoco.org/jacoco/) garandeert een code coverage van 90%. Dat
  is geen garantie op goede tests, maar wel dat hij bijna alle paden langs moet gaan.
- [Spectral linting](https://github.com/stoplightio/spectral) controleert de OpenAPI
  spec op consistentie en andere problemen.

De meeste hiervan hebben we toegevoegd omdat we terugkerend zagen dat dit niet goed
ging. Maar uiteindelijk zijn het ook tools die standaard in een CI/CD-pipeline
aanwezig moeten zijn.

### Geheugen sturen

Voor sommige richtlijnen zijn geen (of niet zo makkelijk) automatische tools
beschikbaar om ze te controleren. Die kunnen we dan toevoegen aan `CLAUDE.md`. Dit
is een project-geheugen, met o.a. richtlijnen over hoe CC zijn werk moet
doen.

- CC is erg goed in taal, en lijkt dat ook te willen tonen. Comments kunnen
  erg lang worden, met als effect dat ze niet meer gelezen worden (TL;DR). Beschrijf
  "waarom, niet wat".
- CC heeft ook de neiging om elke vorm van feedback uitgebreid in comments
  te plaatsen. Dit is vaak onnodig of verwijst naar referenties die later geen
  relevantie meer hebben (bevinding B4, in de vorige iteratie deden we het zo, ...).
- CC is nog niet goed in het gebruiken van Nederlands in een technische
  context waar Engels eigenlijk de voertaal is. Letterlijk vertaalde woorden als
  *voetpistool*, of moeilijk Nederlands als 'exhaustief', maken het commentaar
  moeilijker leesbaar.
- CC reviews gebeuren vaak vanuit een nieuwe context. Het is daarom
  belangrijk om "won't fix"-keuzes vast te leggen, zodat ze niet elke review weer
  terugkomen.
- CC heeft (soms) de neiging om de makkelijkste optie te kiezen. Met name
  bij het ontwikkelen van tests is sturing nodig. We hebben toegevoegd dat hij
  creatief tests moet toevoegen.

### Wat aandacht vraagt

- **Schijnzekerheid.** Dat de AI iets met overtuiging oplevert, is geen bewijs dat
  het klopt of compliant is. De brondocumenten (standaarden) blijven leidend en de
  mens blijft eindverantwoordelijk. Dit is ook als risico opgenomen in de
  verantwoording.
- **Verzonnen of verouderde verwijzingen.** Ticketnummers, klassenamen en
  verwijzingen die de AI noemt moeten geverifieerd worden vóór gebruik. Ze kunnen
  "dangling" zijn, oftewel verwijzen naar iets dat niet of niet meer bestaat.

### Concrete technische lessen

Hard geleerde details die zonder context terugkomen als bug of verwarring. Veel
hiervan staat samengevat ook in `CLAUDE.md`, zodat CC ze meekrijgt.

- **`clean` vóór `test`/`verify`.** Door het hoge ontwikkeltempo en het gelijktijdig
  werken zijn er veel branch-wisselingen. Op een gedeelde bind mount blijven dan
  stale `.class`-bestanden achter, wat misleidende
  `NoSuchMethodError`/"Failed to start Quarkus"-fouten geeft in ongewijzigde code.

### Open vragen en nog te onderzoeken

- Hoeveel menselijke review is werkelijk nodig, en op welke lagen levert het de
  meeste waarde op? Dit is de kernvraag van de beproeving.
- Hoe goed is het resultaat overdraagbaar naar een andere AI-assistent? Dit sluit
  aan op het vendor-lock-in-aandachtspunt uit de verantwoording.
- Welke conventies lonen het om machinaal af te dwingen, en waar wordt de
  tooling-overhead groter dan de winst? De test-runs en pipelines duren ondertussen
  best lang.
