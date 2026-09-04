# De berichtenlijst draagt de naam van de afzender

**Status:** Uitgevoerd

Issue: [MinBZK/MijnOverheidZakelijk#1065](https://github.com/MinBZK/MijnOverheidZakelijk/issues/1065)

## Aanleiding

Een ondernemer die zijn berichtenlijst opent hoort bij elk bericht te zien van welke organisatie
het komt. De lijst die de uitvraag teruggaf droeg van de afzender alleen een nummer van twintig
cijfers: `magazijnId` (de afzender-OIN) en `afzender` bevatten allebei diezelfde OIN. De leesbare
naam kwam uitsluitend voorbij in de voortgangsmeldingen (`magazijn-bevraging-gestart`/`-voltooid`)
tijdens een ophaalronde.

Dat gaf een afnemer twee problemen. Hij moest elke keer een volledige ophaalronde langs álle
aangesloten organisaties draaien om de namen te leren kennen, ook wanneer de lijst zelf al direct
te leveren was. En kwam er een bericht binnen van een organisatie die tijdens die ronde niet
meedeed — het normale geval bij een aanmelding via de webhook — dan stond er een rij cijfers in de
kolom "Afzender", die een schermlezer cijfer voor cijfer voorleest.

## Ontwerpkeuzes

**De naam gaat als veld mee, niet als los adres.** Twee richtingen lagen open: `afzenderNaam` als
eigen veld naast `magazijnId` in `BerichtSamenvatting`/`Bericht`, of een eigen endpoint dat het
magazijnregister uitleest. Het veld wint op alle vier de gedragseisen uit het issue: geen extra
ronde vóór de eerste lijst, meteen goed voor een aangemeld bericht van een nog niet bevraagde
organisatie, en de afnemer hoeft niets tussen aanroepen door te onthouden. De kosten — de naam
staat per bericht in het antwoord — zijn een paar tientallen bytes op een bericht dat in de
praktijk kilobytes groot is.

**Het veld is verplicht, en die belofte wordt op twee plekken waargemaakt.** Een deelnemende
organisatie zonder leesbare naam is geen geldige inschrijving: `naam` is verplicht in het
magazijnregister en de boot faalt zonder, net als bij een ontbrekende `url`. En elk bericht krijgt
die naam mee op het moment dat het in de sessie wordt opgeslagen, zodat een organisatie die later
uit het register verdwijnt haar berichten niet naamloos achterlaat. `afzenderNaam` is daarmee
`required` in de API en nooit leeg — een afnemer hoeft geen terugval te bouwen.

Een optioneel veld met een expliciet afwezig-geval was het alternatief. Dat legt de vraag "en wat
toon ik dan?" bij elke afnemer neer, terwijl het antwoord hier hoort: een organisatie zonder naam
is een configuratiefout, geen toestand om een UI op in te richten.

**`afzender` verdwijnt uit de uitvraag-API.** Dat veld droeg dezelfde OIN als `magazijnId` en is
precies het nummer dat zich als naam voordoet — een afnemer die "afzender" toont, toont cijfers.
`magazijnId` (het nummer, voor routering) en `afzenderNaam` (de naam, om te tonen) houden elk één
betekenis over. Het `afzender`-veld in `AangemeldBerichtData` blijft ongemoeid: dat is het
inkomende magazijn-contract, waar het veld wél een OIN hoort te zijn en als zodanig gedocumenteerd
staat.

**De bron is het register, niet de ophaalronde.** `Afzendernamen` zoekt de naam per bericht op in
het `Magazijnregister` bij het `magazijnId`. Daarmee draagt een bericht uit een ophaalronde en een
via de webhook aangemeld bericht dezelfde naam, zonder dat de sessiecache namen hoeft te bewaren.

Dat de lookup op `magazijnId` gaat en niet op `afzender` is bovendien de veilige kant. `magazijnId`
is toegekend door de aggregatie en tegen het register gehouden; `afzender` komt ongevalideerd uit
de payload van het magazijn. De getoonde naam volgt dus de organisatie waar het bericht vandaan
kwam, niet de organisatie die het bericht over zichzelf claimt.

**Het register wint van de meegeschreven naam.** Kent het register de organisatie, dan levert
`Afzendernamen` de naam van nú; dan werkt een hernoeming meteen door in plaats van pas na het
verlopen van de sessie. De meegeschreven naam is het vangnet voor het ene geval waarin het register
niets weet: een organisatie die eruit verdween terwijl haar berichten nog in een lopende sessie
staan. Dat vangnet is precies wat het veld `required` laat zijn.

Die twee anomalieën — een `magazijnId` dat het register niet kent, of dat geen geldige OIN is —
worden op debug gelogd, niet op warn. De lookup gebeurt per bericht, dus één gedrift magazijn zou
bij warn een hele pagina volschrijven, elke poll-ronde opnieuw. Waar drift de gebruiker écht
blokkeert — routering van detail, PATCH, DELETE en bijlagen — escaleert `MagazijnRouter` hem al naar
error + 502.

**Een blanco naam bestaat niet.** `Magazijninschrijving` eist een niet-blanco `naam`, en
`ConfigMagazijnregister` trimt de configwaarde en faalt fail-fast op blanco — dezelfde behandeling
als `url` en `grantHash`. Zonder die check kon `magazijnen."<OIN>".naam= ` een lege string als
weergavenaam de keten in sturen: geen naam, maar ook niet als ontbrekend te herkennen.

**De cache-sleutels gaan naar `v2`.** Een `bericht:v1:`-entry mist het nieuwe veld en zou als
corrupt gelezen worden. Met een eigen prefix verlopen de oude entries via hun eigen TTL in plaats
van leesfouten te geven. Dat vraagt bij uitrol één keer de schema-bump-procedure uit
`docs/operations/redisearch-schema-bump.md` (drop index + herstart); pre-productie mag de cache
gewoon leeglopen.

## Wijzigingen

- `berichtenuitvraag-api.yaml`: `afzender` weg uit `BerichtSamenvatting` en `Bericht`,
  `afzenderNaam` erbij met de herkomst en het afwezig-geval in de description.
- `uitvraag/Afzendernamen.kt`: nieuwe CDI-bean die de naam bij een bericht levert — register
  eerst, de meegeschreven naam als vangnet.
- `fbs-magazijnregister`: `naam` is verplicht en niet-blanco; de boot faalt zonder.
- `fbs-berichtensessiecache`: `Bericht`/`BerichtSamenvatting` dragen `afzenderNaam`, opgeslagen als
  hash-veld en meegeprojecteerd in de samenvatting; sleutel-prefix naar `v2`. `MagazijnResult` en de
  SSE-events `magazijn-bevraging-*` dragen de naam nu verplicht in plaats van optioneel.
- `aanmeld/AfzenderMagazijnIndex`: levert naast het `magazijnId` ook de naam, zodat een via de
  webhook geschreven cache-entry er net zo goed een heeft als een opgehaald bericht.
- `UitvraagDtoMapper`: de naam komt als parameter binnen, zodat de mapper zonder afhankelijkheden
  blijft. `BerichtenlijstService`, `BerichtOphaalService` en `BerichtBeheerService` zoeken hem op.
- `demo/demo-console/…/berichtenbox.js`: leest `afzenderNaam` uit de lijst; de per sessie
  bewaarde namen-map uit de ophaal-events is daarmee overbodig en verwijderd. Ontbreekt de naam,
  dan toont de box "Onbekende organisatie" in plaats van de OIN.
- Bruno: `docs`-blokken op de berichtenlijst- en detail-request die de naam en het afwezig-geval
  beschrijven.

## Verificatie

- `AfzendernamenTest`: per organisatie de eigen naam (parameterized over één t/m meerdere
  inschrijvingen, zodat de lookup aantoonbaar discrimineert), het register dat van de meegeschreven
  naam wint bij een hernoeming, een leeg register, een niet-ingeschreven organisatie, en zes vormen
  van een `magazijnId` dat geen geldige OIN is — alle vier de terugvalgevallen leveren een naam.
- `MagazijninschrijvingTest`/`ConfigMagazijnregisterTest`/`MagazijnregisterConfigMappingTest`: een
  blanco naam is niet construeerbaar, een blanco configwaarde blokkeert de boot, een ontbrekende
  naam bindt niet, en omringende whitespace wordt getrimd.
- `UitvraagDtoMapperTest` en `BerichtenlijstServiceTest`: mét en zónder bekende naam; twee
  berichten uit verschillende magazijnen in één lijst bewijzen dat de naam per bericht wordt
  opgezocht.
- `ServiceCoverageTest`: één lijstantwoord met twee magazijnen die elk hun eigen naam dragen
  (bewijst dat het veld per bericht wordt opgezocht), het detail, een magazijn dat uit het register
  verdween en toch zijn naam houdt, en een guard dat `afzender` weg is.
- `AanmeldResourceTest`: een via de webhook aangemeld bericht draagt in de lijst de naam van zijn
  organisatie, zonder dat er een ophaalronde is gedraaid.
- `UitvraagKetenE2eTest`: het andere been — berichten uit een échte ophaalronde langs twee
  magazijnen, elk met hun eigen naam in één lijstantwoord.
- `OpenApiContractTest`: de spec-validator ziet het veld ook gevuld, niet alleen afwezig.
- `./mvnw clean verify -pl services/berichtenuitvraag -am`: groen, JaCoCo-gate gehaald, detekt
  zonder bevindingen, geen nieuwe build-warnings. `./mvnw clean test -pl demo/demo-console -am`
  eveneens groen.

## Gevolg voor afnemers

Een afnemer die vandaag `afzender` uitleest moet overstappen op `afzenderNaam` (en op `magazijnId`
waar hij het nummer nodig heeft).

De berichtenbox uit de proeftuin (`MinBZK/moza-poc`, gepind in `compose.yaml`) is nagelopen: die
breekt niet. `assets/javascript/berichtenbox-keten.js` zet de afzender met
`organisaties[bericht.magazijnId] || bericht.afzender || bericht.magazijnId || "Onbekende afzender"`.
`afzender` is daar de middelste terugval; zonder dat veld valt hij door naar `magazijnId` — precies
wat er nu al staat zodra de ophaalronde de naam nog niet geleerd heeft. Zodra die box `afzenderNaam`
als eerste keuze leest, vervalt daar de per-sessie bewaarde namen-map, net als in de demo-console.

Voor beheerders is er één harde eis bij: elke `magazijnen."<OIN>"` in de configuratie moet nu ook
een `naam` hebben, anders start de uitvraag niet. Alle bestaande configuraties voldoen daar al aan,
inclusief de 98 regels die `demo/genereer-magazijnen.py` uitschrijft.

## Wat hier bewust niet in zit

- **`afzender` en `magazijnId` kunnen in het domein uiteenlopen.** De sessiecache dwingt nergens af
  dat het `afzender`-veld uit de magazijn-payload gelijk is aan het magazijn dat bevraagd werd. In
  het 1:1-model kan dat niet misgaan, maar de invariant is nu wel gedragsbepalend geworden. Hoort in
  de sessiecache-library thuis (valideren bij het bouwen van een `Bericht`), niet hier.
- **`Bericht.magazijnId` is een `String` waar `Oin` hoort te staan.** Binnen dezelfde data class is
  `ontvanger` wél getypeerd, inclusief de Jackson-roundtrip. Typeren van `magazijnId` zou de
  `try { Oin(...) } catch`-constructie hier én in `MagazijnRouter` overbodig maken.
- **Een hernoeming werkt niet door in een bericht dat de uitvraag niet meer kan thuisbrengen.**
  Dat is de prijs van het vangnet en raakt alleen een organisatie die uit het register verdween.
- **`info.version` blijft `0.1.0`** bij een brekende responsvorm. De versie is sinds de introductie
  van de service nooit gebumpt; dat is een projectbrede lacune, geen keuze van deze wijziging.
