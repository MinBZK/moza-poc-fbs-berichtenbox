# Grenzen op het aantal bij de demo-knoppen

**Status:** Uitgevoerd

Werkt aan MinBZK/MijnOverheidZakelijk#1070.

## Context

Het bedieningspaneel van de demo kan berichten opvoeren met een zelf op te geven aantal. Voor de
knop *Bericht plaatsen* bewaakt `DemoResource.bericht(...)` die grens sinds #280; de knop *Random
berichten opvoeren* en het adres achter *Stroom starten* kenden hem nog niet:

- `POST /api/demo/random?aantal=0` antwoordde met HTTP 200 en `AanleverResultaat(0, 0, 0, 0)`.
  `vullingSoort` in `bediening.js` valt daarop door naar `'goed'` — een groene balk en een groen
  vinkje voor een actie die niets deed. Hetzelfde gold voor een negatief aantal.
- Een onredelijk hoog aantal werd zonder meer uitgevoerd: minutenlang "Bezig…", en knapte de
  verbinding intussen, dan meldde het paneel een afgebroken antwoord terwijl de aanlevering
  gewoon doorliep.
- `?aantal=abc` liet JAX-RS de omzetting naar `Int` doen; die mislukking wordt een HTTP 404, wat
  leest als een knop die naar een niet-bestaand adres wijst in plaats van als een cijfer dat
  verkeerd staat. Voor `/api/demo/tempo/start?interval=abc` gold hetzelfde.

De invoervelden in het paneel bewaakten deze grenzen wel, maar dat is geen contract: deze adressen
worden ook rechtstreeks aangeroepen — vanuit het runbook en tijdens het testen.

Deze branch is gestapeld op `feature/demo-console-bericht-per-persona` (#280), omdat het patroon
dat we hier uitbreiden daar wordt geïntroduceerd.

## Structuur

| Bestand | Wijziging |
|---|---|
| `democonsole/Bedieningsparameters.kt` | Nieuw: `heelGetal(naam, waarde, standaard, grenzen)` — de lezer achter elk getalveld van het paneel |
| `democonsole/DemoResource.kt` | `random` leest zijn aantal via `heelGetal`; `bericht` doet dat nu ook, in plaats van dezelfde logica inline. `MAX_BERICHTEN` → `MAX_GERICHTE_BERICHTEN`, nieuw `MAX_RANDOM_BERICHTEN` |
| `democonsole/tempo/TempoResource.kt` | `start` leest zijn interval via `heelGetal`, met de grenzen van `TempoService` |
| `META-INF/resources/bediening.js` | `vullingSoort` geeft `'let-op'` bij `aangeboden === 0` |
| `demo-console/README.md` | De knoppentabel noemt de grenzen |

## Ontwerpkeuzes

**Eén lezer voor drie velden.** `heelGetal` staat op module-niveau en niet als methode op een
resource, omdat `DemoResource` en `TempoResource` hem allebei nodig hebben. De KDoc draagt de
rationale die eerder bij `bericht` stond, zodat er één plek is waar staat wáárom deze parameters
tekst zijn en geen `Int`.

**De parameter komt als tekst binnen.** Laat je JAX-RS de omzetting naar `Int` doen, dan handelt
hij een mislukking af vóór de eerste regel van de methode — met een 404. Voor `/api/demo/bericht`
is dat bovendien precies de status die "onbekende persona" betekent.

**`BadRequestException` en geen `require()`.** `DemoFoutMapper` vertaalt alleen een
`WebApplicationException` naar zijn eigen status; een `require()` zou een bedieningsfout als HTTP
500 tonen.

**Twee bovengrenzen, allebei genoemd naar hun knop.** Een burst vult een lege omgeving en mag tot
500; een gericht bericht is een demonstratiehandeling en blijft op 100. Beide spiegelen de `max`
van hun invoerveld. `MAX_BERICHTEN` is hernoemd omdat die naam naast `MAX_RANDOM_BERICHTEN` niet
meer zegt welk van de twee hij is.

**Leeg telt als niet opgegeven.** `?aantal=` en een afwezige parameter leveren allebei de default,
en dat gebeurt nu in onze eigen code in plaats van via `@DefaultValue` — dat vervangt alleen een
afwezige waarde, en dat `?aantal=` er vandaag toch doorheen komt is gedrag van JAX-RS dat een
upgrade kan veranderen.

**De grens van de stroom blijft in `TempoService`.** `TempoResource` toetst hem ook, maar dat is de
grens van het adres; de service houdt zijn eigen invariant, met zijn eigen test.

**`vullingSoort` verandert mee.** De adressen weigeren een aantal van nul inmiddels, dus vanuit het
paneel is dat pad onbereikbaar. Het blijft staan voor de vulacties die hun eigen bron leeg kunnen
aantreffen — basisvulling, de simulator-vulling: groen mag daar niet "gelukt" betekenen voor een
ronde waarin niets is aangeboden.

## Verificatie

- `PaneelPadenTest`: de losse `berichtAantal`-test is een `@ParameterizedTest` geworden over de drie
  getalvelden (`aantal`, `berichtAantal`, `tempoInterval`) tegen hun Kotlin-constanten — dat is het
  derde acceptatiecriterium: de grenzen van de API en van de invoervelden lopen niet uiteen.
- `PaneelContractTest`: voor `/api/demo/random` hetzelfde viertal dat `/api/demo/bericht` al had —
  buiten de grenzen (0, -1, 501) → 400 en niets aangeleverd; óp de grenzen (1, 500) → 200;
  onleesbaar (`abc`, `1.5`, `3000000000`) → 400 en géén 404; ontbrekend en leeg → de default. Voor
  `/api/demo/tempo/start` alleen de weigeringen, want een geslaagde start laat een echte klok
  achter voor de tests die erna draaien.
- `BedieningsparametersTest`: de lezer zelf, pure JVM — leeg, witruimte, de grenzen zelf, buiten de
  grenzen, niet-numeriek en een waarde die niet in een `Int` past.
- Vóór het implementeren is bewezen dat de nieuwe tests discrimineren: met de oude `Int`-parameters
  gaven ze `aantal=0 ==> expected: <400> but was: <200>` en `aantal='abc' ==> expected: <400> but
  was: <404>` — precies het gedrag uit het issue.

`./mvnw clean verify -pl demo/demo-console -am`: 297 tests groen, detekt 0 bevindingen.
