# Demonstratiecode

Alles onder `demo/` bestaat om het Federatief Berichtenstelsel te kúnnen tonen. **Het draait nooit
in productie.** Het stelsel zelf staat onder `services/` (deployables) en `libraries/` (gedeelde
bibliotheken); wie wil weten wat het stelsel is, hoeft deze map niet te lezen.

Andersom geldt het niet: niet alle demo-hulpmiddelen staan hier. De stub-mappings (`../wiremock/`),
de storingsinjectie (`../toxiproxy/`), de Bruno-collecties (`../bruno/`) en de compose-stacks in de
repository-root horen er functioneel bij, maar staan op de plek die hun eigen gereedschap
verwacht.

| Pad | Wat |
|---|---|
| `demo-console/` | Maven-module: bedieningspaneel voor demo's — magazijnen legen, vullen, storingen aanzetten. Heeft een eigen image en draait als ZAD-component `democonsole` in de deployment `test` van `mpfm-w3h`, previews inbegrepen — zie `demo-console/README.md` |
| `demo-personas/` | Maven-module: de demo-identiteiten, als eigen dienst met één endpoint (`GET /api/demo/personas`). **Wie deze module als afhankelijkheid opneemt, zet `personadienst.endpoint=false` in zijn eigen `application.properties`** — anders beantwoordt hij dat adres óók, en dan is een verkeerd gerichte proxy niet te zien omdat beide antwoorden gelijk zijn. Bestaat apart zodat een berichtenbox de lijst kan lezen zonder bij de knoppen van het bedieningspaneel te kunnen: dat paneel staat op ZAD achter een authenticatiemuur, en die is niet per pad open te zetten. Draait als ZAD-component `demopersonas`; de demo-console leest dezelfde lijst uit deze module — zie `../docs/plans/2026-09-01-demo-personas-eigen-dienst.md` |
| `magazijn-simulator/` | Maven-module: één service die zich als veel berichtenmagazijnen tegelijk voordoet, elk op pad-prefix `/magazijn/<OIN>`. Genereert uit dezelfde OpenAPI-spec als het echte magazijn — zie `../docs/plans/2026-08-21-magazijn-simulator-design.md` |
| `environment/` | FSC-federatieharness (peers, PKI, contract-bootstrap) én de ZAD-runbooks; `zad-demo/` bevat de eenmalige OM-stappen voor de demo-console en de verificatie erna, plus het voorbereide runbook voor de magazijn-simulator |
| `generated/` | Gegenereerde artefacten (git-ignored): het magazijnregister voor de uitvraag, de set voor de simulator en de profiel-stubs van de vier ondernemers; komt uit `genereer-magazijnen.py`. Lokaal bind-mount compose ze; voor ZAD bakt `deploy.yml` de ondernemer-stubs in het `fbs-demo-profiel`-image |
| `genereer-magazijnen.py` | Genereert uit één getal n: het register, de set van de simulator en de vier ondernemers (3, 15, 45 en 100 aangesloten organisaties) |
| `smoke.sh` | Rookproef over de demo-stack |
| `meet-fanout.sh` | Meet per ondernemer de tijd tot het eerste bericht en tot de complete lijst, uit de SSE-stroom van de uitvraag |
| `podman-prepare.sh`, `podman-up.sh` | Demo-stack draaien onder Podman |

De demo-runbook staat in [`../docs/demo-runbook.md`](../docs/demo-runbook.md).

## De grens

Een module onder `demo/` mag afhangen van het stelsel en van elkaar; andersom niet. `demo-console`
hangt aan `demo-personas` en verder aan niets uit de reactor: de wegwerp-modules erven de
productie-stack (LDV-wrapper, JAX-RS-filters) bewust niet. De prijs is duplicatie: de
elfproef-validatie staat lokaal in `demo-personas/src/main/kotlin/…/Identificatiecheck.kt`,
naast het gezaghebbende `libraries/fbs-common/…/identificatie/Identificatienummer.kt`. Wie de
elfproef wijzigt, wijzigt beide.

`.github/scripts/demo-grens.sh` bewaakt de richting: het faalt zodra een pom van het stelsel — de
modules onder `services/` en `libraries/`, én de root-pom waar ze allemaal van erven — de naam van
een demo-module noemt: als dependency, als parent, als plugin of in een profiel. De pom's worden
daarvoor als XML gelezen en niet met een regex doorzocht, zodat een gespreid element of een
CDATA-sectie de controle niet omzeilt. Kies een demo-module dus een naam die niet met een bestaande
dependency botst. Zonder die controle is de scheiding een afspraak die alleen in review houdt, en
dan is één `<dependency>` erbij genoeg om demo-code naar productie te laten meeliften.

## Wat dit betekent voor de CI

Demo ≠ "wordt niet uitgerold". De FSC-harness levert het contract-bootstrap-image en een demo-module
kan een eigen ZAD-component hebben. `.github/scripts/wijzigingsfilter.sh` sluit daarom niet `demo/`
als geheel van bouwen en uitrollen uit, maar de delen die buiten de uitrolpoort vallen
(`DEMO_BUITEN_UITROLPOORT`) — het contract-bootstrap-image uit `environment/` en het
de demo-images (`build-demo-images`) hangen allebei aan `run` en niet aan `deploy`, dus die
worden daar niet door geraakt. Een nieuwe demo-module valt buiten de uitsluiting
en houdt zijn build: vergeten kost een overbodige build, niet een overgeslagen build.

Voor de test-scope geldt `demo/` wél als geheel: raakt een PR niets buiten deze map, dan test de
`demo`-shard alleen de modules hieronder. Behalve wanneer diezelfde PR de uitrol raakt — dan valt de
test-scope terug op alles, want een preview hoort niet uit te rollen wat in die run niet getest is.

Een PR die alleen `demo/` raakt koopt geen fuzz-ronde meer: de fuzz-doelen staan uitsluitend in
`libraries/` en `services/` (`.clusterfuzzlite/build.sh`), dus zo'n ronde kon per definitie niets
nieuws raken. Om dezelfde reden heeft `demo-console` geen JaCoCo-gate: de demo hoeft niet
productiewaardig te zijn.

`magazijn-simulator` heeft die gate wél, op dezelfde 90 % als het stelsel. Hij draagt straks het
gedrag van honderd magazijnen, en een fout erin lijkt in een demo op een fout in de keten — dan is
de demo juist misleidend in plaats van onaf.
