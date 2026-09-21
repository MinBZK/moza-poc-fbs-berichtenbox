# Mappen per organisatie in de ophaal-events

**Status:** Uitgevoerd

Hoort bij [MinBZK/MijnOverheidZakelijk#941](https://github.com/MinBZK/MijnOverheidZakelijk/issues/941)
(eerste acceptatiecriterium: het mappenoverzicht groeit zichtbaar mee terwijl de berichten binnenkomen).

## Context

Een map bestaat alleen als eigenschap van een bericht (`map` op de status van het bericht in het
magazijn). Een berichtenbox leidt zijn mappenoverzicht dus af uit de berichten die hij kent. Tijdens een
ophaalronde kan hij die berichten nog niet opvragen: `GET /berichten` geeft `409` zolang de ronde
loopt, omdat een halve lijst anders als een volledige leest. De SSE-events van `_ophalen` droegen per
organisatie alleen tellers. Een mappenoverzicht dat meegroeit was daarmee niet te bouwen, welke
berichtenbox je ook gebruikt.

## Keuze

Het `magazijn-bevraging-voltooid`-event met status `OK` draagt een veld `mappen`: per map die in de
geleverde berichten van die organisatie voorkomt de naam en het aantal berichten. De box telt die
per organisatie op en heeft zo tijdens de ronde een groeiend overzicht.

Verworpen alternatief: `GET /berichten` tijdens een lopende ronde toestaan met een
onvolledig-markering. Dat tornt aan de bewuste guard dat een lijst pas leesbaar is als de ronde af
is, en vraagt van elke afnemer dat hij die markering nooit mist.

## Ontwerpkeuzes

- **Altijd aanwezig bij `OK`, ook leeg.** `[]` zegt "deze organisatie leverde geen berichten in een
  map" — dat is informatie, geen ontbrekende waarde.
- **Postvak IN telt niet mee.** Berichten zonder map staan in Postvak IN; dat aantal is
  `aantalBerichten` min de som van `mappen`.
- **Geen speciale behandeling van namen.** De naam gaat woordelijk mee zoals het magazijn hem levert
  ("Archief" is gewoon een map). Hoofdletters tellen: "Belasting" en "belasting" zijn twee mappen —
  dat is ook hoe ze in de lijst staan.
- **Gesorteerd op naam.** Deterministisch voor afnemers en tests.
- **Afgekapt = afgekapt.** De tellingen dekken de opgehaalde berichten, net als `aantalBerichten`; het
  bestaande `afgekapt`-signaal zegt al dat er meer is.
- **Niet verplicht in het schema.** `MagazijnBevragingVoltooid` dekt ook de mislukte uitkomsten, die
  het veld niet dragen — zelfde regime als `aantalBerichten`.

## Stappen

1. `MapTelling` + `mappen` op `MagazijnBevragingGeslaagd`; tellen in `naarVoltooidEvent`.
2. Spec: `mappen` op `MagazijnBevragingVoltooid`, schema `MapTelling`, toelichting bij `_ophalen`.
3. Tests: wire-contract (library en SSE-transport), `SseContractTest`, tellen per cardinaliteit in de
   service.

## Verificatie

- `./mvnw clean test -pl libraries/fbs-berichtensessiecache -am`
- `./mvnw clean test -pl services/berichtenuitvraag -am`
- Spectral op `berichtenuitvraag-api.yaml`
