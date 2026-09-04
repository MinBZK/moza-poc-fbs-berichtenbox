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

**Het veld is optioneel, met een expliciet afwezig-geval.** `naam` is in het magazijnregister
`Optional<String>`. Kent het register geen naam, dan ontbreekt `afzenderNaam` in het antwoord;
daaraan herkent een afnemer dát er geen naam is en bepaalt hij zelf wat hij toont. Terugvallen op
het `magazijnId` zou het probleem onzichtbaar maken in plaats van oplossen: dan staat er weer een
nummer waar een naam hoort.

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

**Er zijn drie redenen voor "geen naam", en ze leveren alle drie een afwezig veld.** Het register
kent de organisatie zonder naam (bedoelde configuratie), het kent haar niet (config-drift), of het
`magazijnId` is geen geldige OIN (een cache-entry uit een oudere registerstaat). De laatste twee
zijn operationele signalen; die worden op debug gelogd, niet op warn. De lookup gebeurt per bericht,
dus één gedrift magazijn zou bij warn een hele pagina volschrijven en dat elke poll-ronde opnieuw.
Waar drift de gebruiker écht blokkeert — routering van detail, PATCH, DELETE en bijlagen — escaleert
`MagazijnRouter` hem al naar error + 502. Blijkt drift in de praktijk lastig te diagnosticeren, dan
is één samengevatte waarschuwing per lijst-request (over de distinct magazijnId's) de volgende stap.

**Een blanco naam bestaat niet meer.** `Magazijninschrijving` eist nu, net als bij `grantHash`, dat
een aanwezige `naam` niet leeg of alleen whitespace is, en `ConfigMagazijnregister` trimt de
configwaarde en leest een blanco waarde als afwezig. Zonder die twee kon `magazijnen."<OIN>".naam= `
een lege string als weergavenaam de keten in sturen — precies de toestand die het contract als
onmogelijk beschrijft, en die een afnemer niet als "geen naam" kan herkennen. Anders dan bij
`grantHash` blokkeert het de boot niet: geen naam is geldige configuratie.

## Wijzigingen

- `berichtenuitvraag-api.yaml`: `afzender` weg uit `BerichtSamenvatting` en `Bericht`,
  `afzenderNaam` erbij met de herkomst en het afwezig-geval in de description.
- `uitvraag/Afzendernamen.kt`: nieuwe CDI-bean, `magazijnId` → naam via `Magazijnregister`.
- `fbs-magazijnregister`: `naam` mag niet blanco zijn (`Magazijninschrijving`), en een blanco
  configwaarde wordt als afwezig gelezen (`ConfigMagazijnregister`).
- `UitvraagDtoMapper`: de naam komt als parameter binnen, zodat de mapper zonder afhankelijkheden
  blijft. `BerichtenlijstService`, `BerichtOphaalService` en `BerichtBeheerService` zoeken hem op.
- `demo/demo-console/…/berichtenbox.js`: leest `afzenderNaam` uit de lijst; de per sessie
  bewaarde namen-map uit de ophaal-events is daarmee overbodig en verwijderd. Ontbreekt de naam,
  dan toont de box "Onbekende organisatie" in plaats van de OIN.
- Bruno: `docs`-blokken op de berichtenlijst- en detail-request die de naam en het afwezig-geval
  beschrijven.

## Verificatie

- `AfzendernamenTest`: per organisatie de eigen naam (parameterized over één t/m meerdere
  inschrijvingen, zodat de lookup aantoonbaar discrimineert), een leeg register, ingeschreven
  zonder naam, niet-ingeschreven, en zes vormen van een `magazijnId` dat geen geldige OIN is.
- `UitvraagDtoMapperTest` en `BerichtenlijstServiceTest`: mét en zónder bekende naam; twee
  berichten uit verschillende magazijnen in één lijst bewijzen dat de naam per bericht wordt
  opgezocht.
- `ServiceCoverageTest`: één lijstantwoord met een genoemd én een naamloos magazijn (bewijst dat
  het veld per bericht wordt gezet), detail in beide varianten, een niet-OIN-`magazijnId` dat een
  200 zonder naam oplevert in plaats van een fout, en een guard dat `afzender` weg is.
- `AanmeldResourceTest`: een via de webhook aangemeld bericht draagt in de lijst de naam van zijn
  organisatie, zonder dat er een ophaalronde is gedraaid.
- `UitvraagKetenE2eTest`: het andere been — berichten uit een échte ophaalronde langs twee
  magazijnen, mét en zónder naam in één lijstantwoord.
- `OpenApiContractTest`: de spec-validator ziet het veld ook gevuld, niet alleen afwezig.
- `MagazijninschrijvingTest`/`ConfigMagazijnregisterTest`: blanco naam is niet construeerbaar, een
  blanco configwaarde leest als afwezig, en een naam met omringende whitespace wordt getrimd.
- `./mvnw clean verify -pl services/berichtenuitvraag -am`: groen, JaCoCo-gate gehaald, detekt
  zonder bevindingen, geen nieuwe build-warnings.

## Gevolg voor afnemers

Een afnemer die vandaag `afzender` uitleest moet overstappen op `afzenderNaam` (en op `magazijnId`
waar hij het nummer nodig heeft).

De berichtenbox uit de proeftuin (`MinBZK/moza-poc`, gepind in `compose.yaml`) is nagelopen: die
breekt niet. `assets/javascript/berichtenbox-keten.js` zet de afzender met
`organisaties[bericht.magazijnId] || bericht.afzender || bericht.magazijnId || "Onbekende afzender"`.
`afzender` is daar de middelste terugval; zonder dat veld valt hij door naar `magazijnId` — precies
wat er nu al staat zodra de ophaalronde de naam nog niet geleerd heeft. Zodra die box `afzenderNaam`
als eerste keuze leest, vervalt daar de per-sessie bewaarde namen-map, net als in de demo-console.

## Wat hier bewust niet in zit

- **`afzender` en `magazijnId` kunnen in het domein uiteenlopen.** De sessiecache dwingt nergens af
  dat het `afzender`-veld uit de magazijn-payload gelijk is aan het magazijn dat bevraagd werd. In
  het 1:1-model kan dat niet misgaan, maar de invariant is nu wel gedragsbepalend geworden. Hoort in
  de sessiecache-library thuis (valideren bij het bouwen van een `Bericht`), niet hier.
- **`Bericht.magazijnId` is een `String` waar `Oin` hoort te staan.** Binnen dezelfde data class is
  `ontvanger` wél getypeerd, inclusief de Jackson-roundtrip. Typeren van `magazijnId` zou de
  `try { Oin(...) } catch`-constructie hier én in `MagazijnRouter` overbodig maken.
- **`info.version` blijft `0.1.0`** bij een brekende responsvorm. De versie is sinds de introductie
  van de service nooit gebumpt; dat is een projectbrede lacune, geen keuze van deze wijziging.
