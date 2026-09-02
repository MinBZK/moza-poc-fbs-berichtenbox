> **Status:** Uitgevoerd

# De vijf hoge reviewbevindingen op het bedieningspaneel

## Context

De review van `demo/demo-console` op `feature/storingsknoppen-op-zad` leverde vijf bevindingen op
die het paneel laten liegen of de demo halverwege laten steken. Alle vijf delen dezelfde vorm: het
paneel bevestigt iets dat niet gebeurd is, of een deelstap die er niet toe doet houdt het echte werk
tegen. Dat is voor een bedieningspaneel de duurste faalwijze — wie een groene knop niet kan
vertrouwen, kan er tijdens een demo niets meer mee.

De overige bevindingen (medium en laag) blijven buiten dit plan.

## De vijf bevindingen

### 1. Een 401 van de simulator komt als "gelukt, 0 berichten" binnen

`SimulatorBeheerClient.legen()` en `.seed()` geven data classes van uitsluitend `Int`/`Long` terug.
De module zet `microprofile.rest.client.disable.default.mapper=true`, dus een non-2xx wordt niet naar
een exception gemapt en Jackson krijgt de problem+json-body van de simulator te lezen. Quarkus heeft
`FAIL_ON_UNKNOWN_PROPERTIES` uit staan, en jackson-module-kotlin vult een ontbrekend niet-nullable
*primitief* met de primitieve default. Nagemeten tegen de gecompileerde klassen:

```
LeegUitkomst  -> LeegUitkomst(berichten=0, magazijnenTeruggezet=0)
SeedUitkomst  -> SeedUitkomst(magazijnen=0, ontvangers=0, berichten=0, bijlagen=0, overgeslagen=0, duurMs=0)
```

Staat het beheertoken wel op het simulator-component en niet op dat van de console — twee losse
env-velden — dan meldt "Legen" HTTP 200 met "0 berichten uit 0 magazijnen". Er is niets geleegd.

### 2. Eén falende proxy blokkeert alle volgende proxies op dezelfde Toxiproxy

In `ProxyBootstrap` gooit `controleer()` binnen de `forEach` over de gewenste proxies, terwijl de
`try/catch` een niveau hoger zit, per instantie. Lokaal dragen alle zes proxies één instantie: faalt
de eerste, dan worden de overige vijf nooit bereikt — ook niet in de volgende ronde, want die begint
weer bij dezelfde. De keten blijft dood en de log noemt alle zes namen alsof ze even hard faalden.

### 3. "Alles normaal" bevestigt succes terwijl Toxiproxy een deel van de proxies niet kent

`StoringService.herstel()` repareert alleen wat de instantie teruggeeft; de enige guard is dat de
lijst niet leeg is. Kent Toxiproxy er vijf van de zes, dan komt de reset er zonder klacht doorheen en
schrijft `StoringResource` de vaste tekst "alles normaal" — zonder de toestand terug te lezen.

### 4. Een onbereikbare simulator breekt "Herstel demo" af vóór het echte werk

`HerstelService` roept de simulator aan als derde stap, vóór het legen en vullen van de twee echte
magazijnen, en doet dat ongeacht `demo.omgeving.simulator`. Het paneel verbergt de simulator-knoppen
wanneer die vlag `false` staat, maar de primaire knop "Herstel demo" draagt die markering niet. Op een
omgeving zonder simulator laat die knop de omgeving achter met de stroom gestopt en de storingen weg,
maar met de berichten van de vorige demo er nog in. `/api/demo/legen` heeft hetzelfde patroon.

### 5. De knop "Persona's" kan per definitie niet slagen

`index.html` heeft een info-knop op `/api/demo/personas`, terwijl `personadienst.endpoint=false` dat
adres hier juist uitschakelt — `PaneelContractTest` pint vast dat het 404 hoort te geven. Achter de
compose-demo-proxy routeert nginx het adres naar de personadienst; rechtstreeks op `:8095` en op ZAD
(geen proxy) faalt de knop altijd, en dat leest als een kapotte keten.

## Aanpak

| # | Wat | Waar |
|---|---|---|
| 1 | `ResponseExceptionMapper` op de beheerclient, zodat non-2xx een leesbare fout wordt | `simulator/SimulatorBeheerFout.kt` (nieuw), `simulator/SimulatorBeheerClient.kt` |
| 2 | Per definitie vangen in plaats van per instantie; het uitlezen blijft per instantie | `storing/ProxyBootstrap.kt` |
| 3 | Reset kent de verwachte namen per instantie en meldt de ontbrekende; de resource leest de toestand terug | `storing/StoringService.kt`, `storing/StoringResource.kt` |
| 4 | Simulator-stappen ná het echte werk, achter `OmgevingConfig.simulator()`, met hun uitkomst in het antwoord | `herstel/HerstelService.kt`, `DemoResource.kt`, `bediening.js` |
| 5 | De knop naar `/api/demo/omgeving`, plus een test die het adres uit het paneel weert | `index.html`, `bediening.js`, `PaneelPadenTest.kt` (nieuw) |

## Ontwerpkeuzes

**Een fout van de simulator wordt een `IllegalStateException`, geen doorgegeven status.** Dat is het
idioom dat `StoringService.controleer()` al gebruikt: `DemoFoutMapper` maakt er een 500 van met de
melding in het `fout`-veld, en het paneel toont die onverkort. Een 401 doorgeven zou op ZAD lijken op
de Keycloak-muur voor het paneel zelf.

**Reset repareert eerst wat er is en klaagt daarna pas over wat ontbreekt.** Andersom zou een enkele
ontbrekende proxy de storingen op de overige laten staan — de reset moet zoveel mogelijk goedmaken
én daarna eerlijk zijn over wat hij niet kon.

**Herstel slikt een onbereikbare simulator, maar meldt het in het antwoord.** Een exception zou het
al-uitgevoerde legen en vullen als "mislukt" presenteren. Het overslaan is daarom een uitkomst, geen
fout — maar dan moet het paneel hem ook tonen, anders is dit een stille fout in nieuwe kleren. De
samenvatting in `bediening.js` rendert het veld en kleurt de melding oranje.

**`/api/demo/legen` houdt zijn `Map<String, Int>`-vorm.** Die vorm kan geen reden dragen, dus daar
blijft een onbereikbare simulator een harde fout — na het legen van de echte magazijnen, zodat het
destructieve werk niet meer verloren gaat. Het benoemde antwoordtype voor dat endpoint is een aparte
(medium) bevinding.

## Verificatie

- `./mvnw clean test -pl demo/demo-console -am`
- `./mvnw detekt:check -pl demo/demo-console`
- Nieuwe tests bij elke bevinding: de mapper op 401/500/2xx, twee proxies op één instantie waarvan de
  eerste faalt, een reset die een ontbrekende proxy meldt maar de aanwezige toch herstelt, herstel
  met en zonder simulator en met een simulator die gooit, en het paneel dat `/api/demo/personas` niet
  meer aanroept.
