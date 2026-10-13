# Afkapsignaal in de berichtenlijst — implementatieplan

**Status:** Uitgevoerd

**Doel:** een organisatie die meer berichten heeft dan de uitvraag ophaalt, blijft als zodanig
zichtbaar in `GET /berichten` (en `_zoeken`), op elke pagina en na verversen, zolang de sessie
duurt ([MinBZK/MijnOverheidZakelijk#1072](https://github.com/MinBZK/MijnOverheidZakelijk/issues/1072)).

Bouwt voort op de branch van #1004 (`fix/1004-berichtsleutel-per-magazijn`, PR #371): daar staan de
sessiecache-sleutels al op `v4` en de uitvraag-API op `0.4.0`.

## Context

Het afkap-gegeven (`afgekapt`, `totaalBeschikbaar`) bestaat vandaag alleen op het
`magazijn-bevraging-voltooid`-event van `_ophalen`. De lijst haalt zijn gegevens uit Redis, en de
`AggregationStatus` daar kent alleen `nietGeleverd` (#996). Wie ververst, bladert of terugkomt,
ziet een onvolledige lijst als volledig.

`nietGeleverd` is het sjabloon: `AggregationStatus` → `volledigheid()` → `BerichtenPagina.volledigheid`
(gevuld in `BlockingSessiecache.lijst`/`zoek`) → `BerichtenlijstService.vulVolledigheid` → `BerichtenLijst`.

## Ontwerp

- **Domein (sessiecache):** nieuw `Afgekapt(magazijnId, naam, aantalOpgehaald: Int, totaalBeschikbaar: Long?)`
  naast `NietGeleverd`. `Volledigheid` krijgt `afgekapt: List<Afgekapt>` (default leeg, zodat
  bestaande aanroepers blijven werken). Invarianten: `aantalOpgehaald >= 0`, `totaalBeschikbaar`
  (indien gezet) `> aantalOpgehaald` — beide volgen uit `MagazijnPaginaLezer`/`naarMagazijnResult`.
- **`AggregationStatus`:** `afgekapt: List<Afgekapt> = emptyList()`. Invarianten: niet meer dan
  `geslaagd`, geen dubbele organisatie, geen overlap met `nietGeleverd`. Een status zonder het veld
  leest terug als leeg — door de `v4`-sleutels van #1004 bestaat zo'n status niet meer.
- **Aggregatie:** `RondeTellers.afgekapt` (thread-veilig), gevuld in `naarVoltooidEvent` bij
  `Success` met `afgekapt = true`; bij opslaan gesorteerd op naam, net als `nietGeleverd`.
- **API (`berichtenuitvraag-api.yaml`):** `BerichtenLijst.afgekapt` (verplicht, array van nieuw
  schema `AfgekapteOrganisatie`: `magazijnId`, `naam`, `aantalOpgehaald`, optioneel
  `totaalBeschikbaar`). Gesorteerd op naam. De zin "Dat een organisatie niets of niet alles
  leverde, staat niet in deze lijst …" wordt vervangen door een verwijzing naar `nietGeleverd`
  en `afgekapt`. Additief → versie `0.5.0`.
  - Bewust geen aggregaat-vlag: de ondernemer moet zien bij wélke organisatie er meer ligt, en dit
    volgt de vorm van `nietGeleverd`.
- **Uitvraag:** `UitvraagDtoMapper.toApiAfgekapt`, `vulVolledigheid` vult ook `afgekapt`.
- **Demo-berichtenbox:** `laadLijst` leest `afgekapt` én `nietGeleverd` van de eerste pagina en
  toont een vaste melding boven de lijst ("Niet alles opgehaald bij: X (500 van 812)" / "Niet
  geleverd door: Y"). Die melding overleeft *Vernieuwen*; de demo las `nietGeleverd` uit de lijst
  nog niet, dat gaat in dezelfde melding mee.
- **Buiten scope:** de proeftuin-berichtenbox (`MinBZK/moza-poc`, `afgekaptVan(deel)`) — andere
  repo; volgt op dit contract.

## Stappen

1. `Afgekapt` + `Volledigheid.afgekapt` + `AggregationStatus.afgekapt` met invarianten; tests in
   `AggregationStatusTest` (leeg/één/meerdere, dubbel, overlap, meer dan geslaagd, Jackson-roundtrip
   zonder veld).
2. `RondeTellers` + `naarVoltooidEvent` + `aggregeerEnSlaOp`; test in `BerichtensessiecacheServiceTest`.
3. Redis-roundtrip in `RedisBerichtenCacheIntegrationTest`; facade in `BlockingSessiecacheTest`.
4. Spec + mapper + `BerichtenlijstService`; tests in `BerichtenlijstServiceTest` en `OpenApiContractTest`.
5. E2E (`UitvraagKetenE2eTest`): magazijn meldt `totalElements` groter dan geleverd; `afgekapt` staat
   op beide pagina's en nog steeds na een tweede lees-ronde — het acceptatiecriterium "overleeft
   een herlaadactie".
6. Demo-berichtenbox + `berichtenbox.html`; `docs/demo-runbook.md` bijwerken.
7. `detekt:check` en tests van sessiecache, uitvraag en demo-console.

## Verificatie

- `./mvnw clean test -pl libraries/fbs-berichtensessiecache -am`
- `./mvnw clean test -pl services/berichtenuitvraag -am`
- `./mvnw clean test -pl demo/demo-console -am`
- `./mvnw detekt:check`
- Spectral op `berichtenuitvraag-api.yaml`
