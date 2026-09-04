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
Een `magazijnId` dat geen geldige OIN (meer) is — een cache-entry die een registerwijziging
overleefde — levert geen naam op in plaats van de hele lijst te laten falen.

## Wijzigingen

- `berichtenuitvraag-api.yaml`: `afzender` weg uit `BerichtSamenvatting` en `Bericht`,
  `afzenderNaam` erbij met de herkomst en het afwezig-geval in de description.
- `uitvraag/Afzendernamen.kt`: nieuwe CDI-bean, `magazijnId` → naam via `Magazijnregister`.
- `UitvraagDtoMapper`: de naam komt als parameter binnen, zodat de mapper zonder afhankelijkheden
  blijft. `BerichtenlijstService`, `BerichtOphaalService` en `BerichtBeheerService` zoeken hem op.
- `demo/demo-console/…/berichtenbox.js`: leest `afzenderNaam` uit de lijst; de per-zitting
  bewaarde namen-map uit de ophaal-events is daarmee overbodig en verwijderd. Ontbreekt de naam,
  dan toont de box "Onbekende organisatie" in plaats van de OIN.
- Bruno: `docs`-blokken op de berichtenlijst- en detail-request die de naam en het afwezig-geval
  beschrijven.

## Verificatie

- `AfzendernamenTest`: per organisatie de eigen naam (parameterized over leeg/één/meerdere
  inschrijvingen, zodat de lookup aantoonbaar discrimineert), ingeschreven zonder naam,
  niet-ingeschreven, en een `magazijnId` dat geen geldige OIN is.
- `UitvraagDtoMapperTest` en `BerichtenlijstServiceTest`: mét en zónder bekende naam; twee
  berichten uit verschillende magazijnen in één lijst bewijzen dat de naam per bericht wordt
  opgezocht.
- `ServiceCoverageTest`: lijst én detail over HTTP, met naam en met een ontbrekend veld.
- `AanmeldResourceTest`: een via de webhook aangemeld bericht draagt in de lijst de naam van zijn
  organisatie, zonder dat er een ophaalronde is gedraaid.
- `./mvnw clean verify -pl services/berichtenuitvraag -am`: groen, JaCoCo-gate gehaald, detekt
  zonder bevindingen, geen nieuwe build-warnings.

## Gevolg voor afnemers

Een afnemer die vandaag `afzender` uitleest moet overstappen op `afzenderNaam` (en op `magazijnId`
waar hij het nummer nodig heeft). Voor de berichtenbox uit de proeftuin betekent dat: het veld
tonen zoals het binnenkomt, en bij afwezigheid zelf bepalen wat er in de kolom komt.
