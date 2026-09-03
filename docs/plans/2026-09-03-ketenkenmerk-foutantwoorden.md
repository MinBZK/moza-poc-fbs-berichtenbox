# Ketenkenmerk op foutantwoorden

**Status:** Concept

Issue: [MinBZK/MijnOverheidZakelijk#1045](https://github.com/MinBZK/MijnOverheidZakelijk/issues/1045)
Aanleiding: de eerste koppeling van de proeftuin-berichtenbox aan de keten (#937, #1023).

## Context

Een ondernemer die een bericht opent dat er niet (meer) is, krijgt altijd hetzelfde antwoord:
`404` met `type: about:blank` en de tekst "Bericht niet gevonden". Dat ene antwoord dekt vier
situaties die voor de gebruiker sterk verschillen — het bericht bestaat niet, het hoort bij een
andere ondernemer, de gebruiker verwijderde het zelf, of het zat niet in de opgehaalde set — en
het is bovendien niet te onderscheiden van een `404` die een proxy onderweg verzint. De
berichtenbox kan daardoor niets stelligers tonen dan een neutrale tekst.

Wat er al ligt:

- `Problem` in `fbs-common` ondersteunt een eigen absolute type-URI, maar `problemResponse(...)`
  geeft die niet door — alle mappers vallen terug op `about:blank`.
- De `404` op `GET /api/v1/berichten/{berichtId}` komt uit een misser in de sessiecache
  (`BerichtOphaalService.zoekBerichtInCache`). Die cache is per ontvanger gesleuteld, dus een
  bericht van een andere ontvanger is per constructie een misser; het magazijn wordt op dat pad
  niet geraadpleegd.
- Een verlopen of nog niet gevulde sessie geeft géén 404 maar 409/503 (`UpstreamFault.kt`,
  `SessiecacheException.naApiFout`). Dat onderscheid bestaat dus al, maar staat nergens beschreven.

## Ontwerpkeuzes

**Het kenmerk is de type-URI, in de vorm `urn:fbs:fout:<code>`.** RFC 9457 wijst `type` aan als
het stabiele, machineleesbare kenmerk van een foutantwoord; een eigen veld ernaast zou een tweede
contract openen. De `urn:`-vorm boven een `https://`-vorm omdat een dereferenceerbare URI de
belofte van een blijvende, gehoste pagina per code met zich meebrengt — een belofte die dood
linkt zodra die pagina verhuist. Het extra segment `fout` houdt ruimte vrij voor andere
`urn:fbs`-soorten later.

**Elk foutantwoord draagt een code, niet alleen de interessante gevallen.** De afnemer moet aan
het antwoord kunnen zien dát het van de keten komt; dat werkt alleen als er geen antwoorden meer
overblijven met `about:blank`. Ook de statussen die Jakarta zelf produceert (405, 415, 406)
krijgen daarom een code, via een terugval op statusklasse.

**Een `FbsFoutException` draagt de code van throw-site naar mapper.** Het alternatief — de code in
een response-header meesmokkelen — opent een tweede, ongedocumenteerd kanaal naar buiten; elke
service zijn eigen `Response` laten bouwen haalt de maskering en sanering uit de mapper weg. Een
`WebApplicationException`-subklasse laat bestaande throw-sites ongemoeid en verandert alleen daar
waar een specifieke code hoort.

**"Zelf verwijderd" krijgt een tombstone in de sessiecache, en `410 Gone`.** Het alternatief —
bij een cache-misser terugvallen op het magazijn — maakt het bestaan van andermans berichten
aftastbaar via het antwoordverschil. De tombstone niet: hij leeft in dezelfde per-ontvanger
gesleutelde ruimte als het bericht zelf en wordt alleen gehonoreerd als de eigenaar klopt. `410`
boven `404` met alleen een ander kenmerk, omdat een afnemer die enkel naar de status kijkt anders
nog steeds niets wijzer wordt — precies de klacht uit het issue.

**Het onderscheid "bestaat niet" versus "is niet van jou" blijft dicht.** Beide leveren
`urn:fbs:fout:bericht-onbekend` met een `404`. De tombstone verandert daar niets aan: bij een
ontvanger-mismatch valt hij weg tegen een gewone misser.

## De codes

| Code | Status | Situatie | Wat een afnemer kan tonen |
|---|---|---|---|
| `bericht-onbekend` | 404 | Bericht bestaat niet, of hoort bij een andere ontvanger, of zat niet in de opgehaalde set | "Dit bericht is niet (meer) beschikbaar" |
| `bericht-verwijderd` | 410 | De ontvanger verwijderde het bericht zelf, binnen deze sessie | "Je hebt dit bericht verwijderd" |
| `nog-niet-opgehaald` | 409 | Er is nog geen ophaalronde geweest voor deze ontvanger | "Even ophalen" — start `_ophalen` |
| `ophalen-bezig` | 409 | Een ophaalronde loopt | "Een moment" — wachten, geen foutmelding |
| `ophalen-mislukt` | 503 | De vorige ophaalronde strandde | "Probeer het opnieuw" — opnieuw `_ophalen` |
| `tijdelijk-niet-beschikbaar` | 503 | Cache onbereikbaar; `Retry-After` staat erbij | "Probeer het straks nog eens" |
| `geen-actieve-sessie` | 404 | Geen actieve sessie om een bericht in bij te schrijven (aanmeld-pad) | — |
| `niet-gevonden` | 404 | Het opgevraagde pad bestaat niet — géén uitspraak over een bericht | — |
| `ongeldig-verzoek` | 4xx overig | Validatie, verkeerde header, niet-ondersteund mediatype | Fout bij de afnemer zelf |
| `geen-toegang` | 401/403 | Ontbrekende of ontoereikende toegang | — |
| `conflict` | 409 | Botsing met de huidige toestand van de resource | — |
| `keten-fout` | 502 | Een schakel verderop in de keten hapert | "Probeer het straks nog eens" |
| `configuratie-mismatch` | 500 | De configuratie van de keten spreekt zichzelf tegen | Opnieuw proberen helpt niet; dit vraagt een beheerder |
| `interne-fout` | 500 | Onverwachte fout; `instance` draagt de correlatie-id | "Er ging iets mis" + de referentie |

Ontbreekt `type` of staat er `about:blank`, dan komt het antwoord niet van de keten.

De terugval per statusklasse mag nooit uit zichzelf `bericht-onbekend` kiezen: een 404 op een
onbekend pad is geen onbekend bericht. Voor 410 ligt dat anders — geen framework-pad produceert
die status, dus komt hij altijd van de keten zelf en mag de terugval hem als "verwijderd" lezen.
Dat is precies wat een `PATCH` op een verwijderd bericht nodig heeft: de 410 valt dan een hop
verderop, in het magazijn.

De profiel-mappers droegen al eigen `https://moza.nl/problems/...`-types. Die gaan mee naar
dezelfde namespace: twee vormen naast elkaar is precies wat een afnemer niet moet hoeven leren.

## Grenzen van "verwijderd"

De tombstone leeft in de sessiecache en dus zolang de sessie leeft. Twee gevolgen die de
API-beschrijving expliciet benoemt, zodat een afnemer er niet naar gaat raden:

- Na afloop van de sessie-TTL valt de tombstone weg en wordt het antwoord weer
  `bericht-onbekend`. "Niet verwijderd" is dus geen bewijs van het tegendeel.
- Alleen verwijderingen die via deze keten liepen zijn bekend. Verwijdert de organisatie het
  bericht in haar eigen magazijn, dan verdwijnt het bij de volgende ophaalronde uit de lijst
  zonder tombstone.

De bijlage-download kent het onderscheid niet: de magazijn-query filtert verwijderde berichten
weg vóór de eigenaar-check, dus daar zou een `410` het bestaan van andermans bericht verraden.
Die blijft `bericht-onbekend`.

## Structuur

| Bestand | Verantwoordelijkheid |
|---|---|
| `libraries/fbs-common/.../exception/Foutcode.kt` (nieuw) | De enum met codes, de `urn:fbs:fout:`-opbouw en de terugval per statusklasse |
| `libraries/fbs-common/.../exception/FbsFoutException.kt` (nieuw) | `WebApplicationException` die een `Foutcode` meedraagt |
| `libraries/fbs-common/.../exception/ProblemResponses.kt` | `type`-parameter erbij, doorgegeven aan `Problem` |
| `libraries/fbs-common/.../exception/*ExceptionMapper.kt` (5×) | Elk hun eigen code meegeven |
| `libraries/fbs-common/.../profiel/*ExceptionMapper.kt` (2×) | Idem; deze bouwen hun `Problem` rechtstreeks |
| `libraries/fbs-berichtensessiecache/.../berichten/BerichtenCache.kt` | Tombstone schrijven bij `delete`, lezen bij een misser |
| `libraries/fbs-berichtensessiecache/.../SessiecacheException.kt` | Nieuw geval `BerichtVerwijderd` |
| `libraries/fbs-berichtensessiecache/.../berichten/BerichtensessiecacheService.kt` | Tombstone-raadpleging op het `bericht`-pad |
| `services/berichtenuitvraag/.../uitvraag/UpstreamFault.kt` | `BerichtVerwijderd` → 410; codes op de bestaande statussen |
| `services/berichtenuitvraag/.../uitvraag/BerichtOphaalService.kt` | Expliciete `bericht-onbekend` i.p.v. kale `NotFoundException` |
| `services/berichtenmagazijn/.../beheer/BerichtBeheerService.kt` | 410 op PATCH van een eigen verwijderd bericht |
| `services/*/src/main/resources/openapi/*.yaml` | 410-response + foutentabel in `info.description` |

Buiten scope: `demo/magazijn-simulator` houdt zijn eigen foutafhandeling. Het door de gebruiker
verwijderde bericht wordt op het uitvraag-pad afgevangen door de tombstone, dus de demo profiteert
zonder dat de simulator meeverandert.

## Stappen

Elke stap sluit af met groene tests en een commit.

### 1. `Foutcode` en de doorgifte in fbs-common

- Test: `Foutcode.BERICHT_VERWIJDERD.uri` is `urn:fbs:fout:bericht-verwijderd`; `voorStatus(...)`
  levert per statusklasse de juiste terugval (400/401/403/404/409/500/502/503 plus een onbekende
  status als 4xx en als 5xx).
- `Foutcode` toevoegen met de codes uit de tabel; `uri` als `URI` afgeleid van de code, zodat de
  string maar op één plek staat.
- `problemResponse(...)` en `maskedServerErrorProblem(...)` krijgen een `type`-parameter.
- Test: `problemResponse(... type = ...)` zet het type op de entity.

### 2. `FbsFoutException` en de mappers

- Test: `ProblemExceptionMapper` op een `FbsFoutException(BERICHT_ONBEKEND)` levert die type-URI;
  op een kale `WebApplicationException(404)` levert het de terugval; op een 500 levert het
  `interne-fout` mét gemaskeerd detail en `instance`.
- `FbsFoutException(foutcode, detail, cause)` toevoegen; de mapper leest de code eraf.
- De overige mappers hun code meegeven: `ConstraintViolation` en `JsonProcessing`/`MismatchedInput`
  → `ongeldig-verzoek`, `DomainValidation` → `ongeldig-verzoek`, `Uncaught` → `interne-fout`,
  `ProfielServiceFout` → `tijdelijk-niet-beschikbaar` (503) resp. `interne-fout` (500),
  `ToestemmingGeweigerd` → `geen-toegang`.
- Test per mapper dat het antwoord geen `about:blank` meer draagt.

### 3. Tombstone in de sessiecache

- Test (`@QuarkusTest`, echte Redis): na `delete` geeft `getById` `null` én meldt de tombstone
  de eigenaar; een ándere ontvanger krijgt géén tombstone-treffer; na opnieuw aanleveren van
  hetzelfde `berichtId` wint het bericht van zijn eigen tombstone; de tombstone duikt niet op in
  `_zoeken`.
- Key `verwijderd:v1:<berichtId>` met de eigenaar-velden, TTL gelijk aan de sessie-TTL. Bewust
  buiten de RediSearch-prefix `bericht:v1:`, anders indexeert de zoekindex hem mee.
- `BerichtenCache.isVerwijderdVoor(berichtId, ontvanger): Uni<Boolean>` erbij; `delete` schrijft
  de tombstone na de bestaande list-prune en `DEL`.

### 4. `BerichtVerwijderd` door de facade

- Test: `Sessiecache.bericht(...)` gooit `BerichtVerwijderd` na een eigen `verwijder`, en levert
  gewoon `null` voor een andere ontvanger.
- Nieuw geval in de gesloten hiërarchie; `BerichtensessiecacheService.bericht` raadpleegt de
  tombstone alleen ná een misser. De `when`-uitputting breekt met opzet de build bij elke consumer.

### 5. Uitvraag: 410 naar buiten

- Test (`@QuarkusTest`): `DELETE` gevolgd door `GET` van hetzelfde bericht geeft 410 met
  `urn:fbs:fout:bericht-verwijderd`; een onbekend `berichtId` geeft 404 met `bericht-onbekend`;
  een bericht van een andere ontvanger geeft exact hetzelfde antwoord als een onbekend bericht.
- `naApiFout()`/`isStoring()` uitbreiden met het nieuwe geval; de bestaande gevallen hun code
  meegeven; `BerichtOphaalService` gooit `FbsFoutException(BERICHT_ONBEKEND)`.

### 6. Magazijn: 410 op PATCH

- Test: PATCH op een eigen verwijderd bericht geeft 410 met `bericht-verwijderd`; PATCH op
  andermans verwijderde bericht blijft 403; DELETE blijft idempotent 204.
- In `wijzigStatus` de `NotFoundException` ná `vereisOntvanger` vervangen door de 410. De
  volgorde staat al goed: op dat punt is de aanroeper aantoonbaar de eigenaar.

### 7. Specs en documentatie

- 410-response op `GET /berichten/{berichtId}` (uitvraag) en `PATCH /berichten/{berichtId}`
  (magazijn); de foutentabel en de grenzen van "verwijderd" in `info.description` van beide specs.
- `OpenApiContractTest` in beide services uitbreiden met de 410.
- Spectral-lint over beide specs.

## Verificatie

```bash
./mvnw clean verify -pl libraries/fbs-common -am
./mvnw clean test -pl libraries/fbs-berichtensessiecache -am
./mvnw clean verify -pl services/berichtenuitvraag -am
./mvnw clean verify -pl services/berichtenmagazijn -am
npx @stoplight/spectral-cli lint services/berichtenuitvraag/src/main/resources/openapi/berichtenuitvraag-api.yaml \
  --ruleset https://static.developer.overheid.nl/adr/ruleset.yaml
npx @stoplight/spectral-cli lint services/berichtenmagazijn/src/main/resources/openapi/berichtenmagazijn-api.yaml \
  --ruleset https://static.developer.overheid.nl/adr/ruleset.yaml
```

Acceptatiecriteria uit het issue en waar ze landen: kenmerk op elk foutantwoord → stap 1 en 2;
kenmerk per situatie in de API-beschrijving → stap 7; andermans bericht ononderscheidbaar →
stap 5; grenzen van "verwijderd" vastgelegd → stap 7 en de sectie hierboven; de berichtenbox kan
een passende tekst tonen → volgt in `MinBZK/moza-poc`, buiten deze PR.
