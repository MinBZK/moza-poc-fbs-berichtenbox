# SSE-contract van de ophaalronde publiceren

**Status:** Uitgevoerd

Issue: MinBZK/MijnOverheidZakelijk#1086 — voortgekomen uit de review op PR #283.
Deze branch staat op `feature/magazijn-bulkhead-wachtrij` (PR #283) en de PR target die branch,
niet `main`: de nieuwe statuswaarde die dit plan documenteert bestaat daar pas.

## Context

`GET /berichten/_ophalen` levert een SSE-stroom met per-organisatie voortgang. De spec beschrijft
die payload als `text/event-stream: schema: { type: string }` — een opake string. De feitelijke
woordenlijst (`EventType`, `MagazijnStatus`) staat alleen in `MagazijnEvent.kt` in de
sessiecache-library. Een afnemer die een berichtenbox op deze uitvraag bouwt kan dus nergens
opzoeken welke events er zijn, welke uitkomsten een organisatie kan hebben, en wat ze betekenen.

PR #283 voegt `NIET_OPGEHAALD` aan die woordenlijst toe: de organisatie is niet bevraagd omdat de
gelijktijdigheidsgrens van de uitvraag zelf vol bleef. Dat is geen storing van die organisatie, en
opnieuw proberen helpt er wél. Het gat is ouder dan die toevoeging, maar de toevoeging maakt hem
schadelijk: zonder gepubliceerde uitbreidbaarheidsregel is elke nieuwe waarde een breuk voor een
afnemer die uitputtend op de bekende waarden matcht.

Onze eigen referentie-afnemer laat zien wat er misgaat. `berichtenbox.js` rendert de status
letterlijk (geen crash), maar de slotregel telt via `gebeurtenis.mislukt`, en die teller bevat
`NIET_OPGEHAALD` — `naarVoltooidEvent` verhoogt `mislukt` voor élke `MagazijnResult.Failure`. Het
onderscheid dat de KDoc van `MagazijnStatus` belangrijk noemt, komt bij de gebruiker niet aan.

De bestaande gates vangen dit niet: Spectral blijft groen bij een toegevoegde enum-waarde en
`swagger-request-validator` valideert geen SSE-body.

## Aanpak

Vier blokken. A en B zijn onafhankelijk; C leunt op A én B, D op B.

### A. Het contract publiceren

In `berichtenuitvraag-api.yaml` onder `components/schemas`:

- `OphaalEvent`: `oneOf` over vier event-schemas met `discriminator: { propertyName: event, mapping }`
  op de vier `EventType`-waarden.
- `MagazijnStatus`: eigen enum-schema met `OK`, `FOUT`, `TIMEOUT`, `NIET_OPGEHAALD`, elk met de
  betekenis in de `description`.
- `text/event-stream` verwijst naar `OphaalEvent` in plaats van `{ type: string }`.

Twee gevallen die `event` niet discrimineert — geslaagd vs. mislukt binnen
`magazijn-bevraging-voltooid`, en vóór- vs. ná-bevraging binnen `ophalen-fout` — worden één schema
met optionele velden, waarbij de `description` zegt wanneer welk veld er staat. Een tweede
`oneOf`-laag levert codegen-ruis zonder winst: het wire-formaat is in beide gevallen hetzelfde
`event`.

De uitbreidbaarheidsregel komt in de `description` van het endpoint: een onbekende `status`
betekent "niet geleverd, opnieuw proberen kan helpen" en nooit `OK`; een onbekend `event` mag
genegeerd worden.

**Bijwerking:** de generator maakt `api.model`-klassen voor deze schemas die niemand gebruikt — het
endpoint valt buiten codegen (`Multi<>` wordt niet ondersteund). Ze vallen in de bestaande
JaCoCo-`api.**`-exclude en detekt kijkt niet naar gegenereerde Java. Dit is wél het eerste wat met
een echte build geverifieerd wordt.

### B. Eigen `nietOpgehaald`-teller

Vier plekken, in lockstep:

- `OphalenGereed` en `OphalenMisluktNaBevraging` krijgen `nietOpgehaald: Int`; die laatste draagt
  dezelfde tellers omdat het portaal de per-magazijn-uitkomsten al gezien heeft.
- `AggregationStatus` krijgt hetzelfde veld met default `0`, en de invariant wordt
  `geslaagd + mislukt + nietOpgehaald <= totaalMagazijnen`. Een in Redis achtergebleven waarde
  zonder het veld leest als 0, dus er is geen migratie nodig.
- `BerichtensessiecacheService`: derde `AtomicInteger`, gevoed vanuit
  `MagazijnFoutStatus.NIET_OPGEHAALD` in `naarVoltooidEvent`; `logRondeAfgerond` noemt hem apart.
- `logboekStatusVoor`: `OK` zolang `mislukt == 0`, ook bij `nietOpgehaald > 0`.

Die laatste is de enige gedragsomslag van dit plan, en de correctie op wat PR #283 als bewuste
schuld noteerde: een bevraging die door onze eigen capaciteitskeuze niet gestart is, is geen
verwerking die misging. De LDV-status (AVG art. 30) hoort te zeggen of de verwerking goed verliep,
niet of alles wat een ondernemer wilde ook geleverd is — dat laatste staat in de tellers van het
slotevent.

### C. Guard-test

Nieuwe test in `berichtenuitvraag`, met het patroon van `RouteDekkingTest` (`OpenAPIV3Parser` op de
spec van het test-classpath). Drie asserties:

1. Het `MagazijnStatus`-enum in de spec is exact `MagazijnStatus.entries`.
2. De sleutels van `discriminator.mapping` zijn exact de `EventType`-waarden.
3. Elke JSON-sleutel die een voorbeeld-instantie van elk event-type serialiseert, komt voor in de
   `properties` van het bijbehorende spec-schema.

Assertie 3 vangt precies wat nu gerot is: een veld dat de code wél stuurt en de spec niet kent. De
test draait zonder Quarkus — hij leest een bestand en serialiseert data classes.

### D. Referentie-afnemer

`berichtenbox.js`:

- `NIET_OPGEHAALD` krijgt eigen tekst in plaats van de rauwe status.
- De slotregel meldt de categorie apart uit het nieuwe veld in plaats van hem in "mislukt" te
  verstoppen.
- Een onbekende status volgt de regel uit blok A: nooit als geslaagd tellen, en tonen als
  "niet geleverd".

`demo/meet-fanout.sh` kruiscontroleert de tellingen van het slotevent tegen de zelf getelde
`magazijn-bevraging-voltooid`-events. Die controle telt nu twee categorieën en zou vals alarm slaan
zodra het slotevent er drie meldt.

## Verificatie

- `./mvnw clean verify -pl services/berichtenuitvraag,libraries/fbs-berichtensessiecache -am`:
  tests, JaCoCo-90%-gate, detekt op 0 bevindingen, en geen nieuwe build-warnings.
- `npx @stoplight/spectral-cli lint services/berichtenuitvraag/src/main/resources/openapi/berichtenuitvraag-api.yaml --ruleset https://static.developer.overheid.nl/adr/ruleset.yaml`
- `shellcheck -x -S warning demo/meet-fanout.sh`
- Meebewegende tests: `MagazijnEventTest`, `BerichtensessiecacheServiceTest`, `LogboekStatusVoorTest`,
  `OphalenSseTest`, `UitvraagKetenE2eTest`.

## Wat dit plan niet doet

- De berichtenbox uit de proeftuin bijwerken: die staat in een andere repository. De
  uitbreidbaarheidsregel uit blok A is precies bedoeld om die afnemer niet te breken.
- Een Bruno-request toevoegen: de collectie heeft er al een voor dit endpoint, en er verandert niets
  aan het verzoek.
