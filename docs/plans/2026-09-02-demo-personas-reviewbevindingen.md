# Reviewbevindingen demo-personas oplossen

**Status:** Uitgevoerd

## Context

`demo/demo-personas` is nieuw op `feature/storingsknoppen-op-zad` (PR #250): de demo-identiteiten
zijn daar uit `demo-console` gelicht en tot eigen dienst gemaakt, zodat een berichtenbox de lijst
kan lezen zonder bij de knoppen van het bedieningspaneel te kunnen. Een review van uitsluitend die
module leverde zeventien bevindingen op — geen enkele een live bug: de suite is groen, de poort
botst niet, `demo-console` zet `personadienst.endpoint=false`, en de elfproef is een getrouwe kopie
van `fbs-common`.

Wat de bevindingen wél delen, is een patroon: de dure invarianten van deze module zijn opgeschreven
in comments maar niet vastgelegd in asserties. Drie voorbeelden die elkaar versterken —

- de melding bij een botsend identificatienummer mag het nummer niet noemen, en de test die dat
  bewaakt zoekt naar een getal dat in die test niet voorkomt;
- de opstartregel is het enige runtime-signaal dat een `magazijnen`-regel is weggevallen, en juist
  dat deel van de regel is ongepind;
- de kruiscontrole met de proeftuin liep sinds `4b98381d` twee kanten op, maar de nieuwe kant mist
  de leegloop-guard die de oude wél heeft.

Bij demonstratiecode is dat geen theoretisch risico. Een demo faalt niet met een stacktrace maar met
een berichtenbox die leegblijft of een verkeerde naam toont, en dat merkt niemand vóór het moment
waarop het publiek meekijkt.

## Uitgangspunten

- Deze module heeft bewust geen JaCoCo-gate; het doel is niet dekking maar het vastpinnen van de
  invarianten die de comments al benoemen.
- Geen `@QuarkusTest` erbij die Docker vereist. De bestaande `PersonaResourceTest` boot al één keer;
  wat daar bij kan, gaat daarin mee.
- Alleen `demo/demo-personas` wijzigt. De module is een afhankelijkheid van `demo-console`, dus
  gedragswijzigingen in productiecode blijven achterwege — op één na: de bevindingen 8, 11 en 12
  raken uitsluitend comments en witruimte.

## Stappen

| # | Bevinding | Bestand | Aanpak |
|---|-----------|---------|--------|
| 1 | Dode assertie | `PersonaServiceTest.kt` | `contains("12345678")` → regex op elke reeks van 8+ cijfers |
| 2 | Weggevallen `magazijnen`-regel onzichtbaar | nieuw `IngerichtePersonasTest.kt` | pin welke ingerichte persona's een opt-in hebben |
| 3 | Opstartregel niet gepind | `PersonaServiceTest.kt` | assertie op `"<id> (<bron>, <n> magazijn(en))"`, met meerdere persona's |
| 4 | `else`-tak witruimte-guard ongedekt | `PersonaServiceTest.kt` | test met `kenners = listOf(strikt)`; ook volgspatie |
| 5 | `label` niet op waarde geasserteerd | `PersonaResourceTest.kt` | `assertEquals("Garage Van Dijk B.V.", …)` |
| 6 | Omgekeerde kruiscontrole kan leeglopen | `ProeftuinPersonaTest.kt` | gefilterde set in een `val` + `isNotEmpty()`-guard |
| 7 | Kruiscontrole vergelijkt alleen `id` | `PersonaResourceTest.kt` | `@Inject PersonaService`, volledige `DemoPersona`-gelijkheid |
| 8 | Lege regel tussen `if` en `else` | `PersonaService.kt` | comments samenvoegen boven de keten |
| 9 | `opthaaltAlleen` | `proeftuin-personas.json`, `ProeftuinPersonaTest.kt` | spelling herstellen; toelichting zegt dat het veld van ons is |
| 10 | `ALLEEN_BIJ_ONS` zonder vervaldatum | `ProeftuinPersonaTest.kt` | test die de uitzondering laat vervallen |
| 11 | KDoc verwijst naar test in demo-console | `DemoPersona.kt` | inkorten, testnaam eruit |
| 12 | Dubbele inleiding | `microprofile-config.properties` | tweede aanhef laten vervallen |
| 13 | Botsingsgroepen: één groep van twee | `PersonaServiceTest.kt` | twee groepen, waarvan één van drie |
| 14 | Drie security-headers ongetest | `PersonaResourceTest.kt` | `@ParameterizedTest` over de vier paren |
| 15 | Cijfer-als-id alleen op 9 cijfers | `DemoPersonaTest.kt`, `DemoPersona.kt` | 8 én 9 weigeren, 7 toestaan; grens in de comment |
| 16 | `require`-meldingen ongeasserteerd | `DemoPersonaTest.kt`, `PersonaServiceTest.kt` | assertie op de meldingstekst |
| 17 | `check`-guards van de handparser vuren nooit | `TestPersonas.kt`, nieuw `TestPersonasTest.kt` | parserdelen `internal`, drie guards getest |

Daarnaast, buiten de zeventien: een `waarde` met witruimte eromheen — wat een `.properties`-bestand
ongemerkt meelevert — krijgt een eigen weigeringstest.

## Ontwerpkeuzes

**Bevinding 2 en 3 lossen hetzelfde probleem op twee hoogtes op.** De logregel-assertie bewaakt dat
het signaal blijft bestaan; de nieuwe `IngerichtePersonasTest` bewaakt de inrichting zelf. Alleen de
tweede wordt rood als iemand vandaag een `magazijnen`-regel weghaalt, en alleen de eerste blijft
werken als de lijst morgen groeit. Ze vervangen elkaar niet.

**Bevinding 6 krijgt `isNotEmpty()` en geen vast aantal.** Een `assertEquals(4, …)` zou rood worden
zodra iemand terecht een KVK-keten-persona toevoegt, en dan is de reflex om het getal op te hogen in
plaats van te kijken. De guard hoeft alleen "er is überhaupt iets gecontroleerd" te bewijzen.

**Bevinding 15 pint de ondergrens en niet de bovengrens.** Dat `[0-9]{8,9}` een twintigcijferig OIN
als persona-id toelaat is geen ongeluk — een OIN is publiek en mag voluit in een log — maar het is
ook geen eis. De test legt vast wat de guard moet weren (de vorm van een KVK-nummer, BSN of RSIN);
waaróm de bovengrens open is, gaat naar de comment.

**Bevinding 17 dekt drie van de vier guards.** `check(bronnen.size == 1)` hangt aan wat er op het
classpath staat; die in een test uitlokken vraagt een eigen classloader, en dat is meer machinerie
dan de guard waard is. De andere drie werken op een `Properties` die een test zelf kan bouwen, dus
die worden `internal` en krijgen een test. Dat verschil staat als comment bij de guard zelf.

**Geen wijziging aan de productie-gedragslogica.** Alle zeventien punten zijn tests, comments of
witruimte. De enige aanraking van `PersonaService.kt` en `DemoPersona.kt` is het samenvoegen van
comments; het gedrag blijft byte-voor-byte gelijk.

## Verificatie

```bash
./mvnw clean test -pl demo/demo-personas          # de module zelf
./mvnw clean test -pl demo/demo-console -am       # de afnemer van de test-jar
./mvnw detekt:check -pl demo/demo-personas        # maxIssues 0, zonder baseline
```

De tweede is niet optioneel: `TestPersonas` gaat als test-jar naar `demo-console`, dus een
`internal` te veel breekt daar de build en niet hier.
