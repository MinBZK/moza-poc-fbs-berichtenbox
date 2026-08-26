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
| `demo-console/` | Maven-module: bedieningspaneel voor demo's — magazijnen legen, vullen, storingen aanzetten |
| `environment/` | FSC-federatieharness: peers, PKI en contract-bootstrap voor de lokale en de ZAD-federatie |
| `generated/` | Gegenereerde stub-mappings (git-ignored); komt uit `genereer-magazijnen.py` |
| `genereer-magazijnen.py` | Genereert de stub-magazijnen en de profiel-persona's |
| `smoke.sh` | Rookproef over de demo-stack |
| `podman-prepare.sh`, `podman-up.sh` | Demo-stack draaien onder Podman |

De demo-runbook staat in [`../docs/demo-runbook.md`](../docs/demo-runbook.md).

## De grens

Een module onder `demo/` mag afhangen van het stelsel; andersom niet. `demo-console` doet vandaag
zelfs dat eerste niet: het heeft bewust geen enkele reactor-afhankelijkheid, zodat de wegwerp-module
de productie-stack (LDV-wrapper, JAX-RS-filters) niet erft. De prijs is duplicatie: de
elfproef-validatie staat lokaal in `demo-console/src/main/kotlin/…/personas/Identificatiecheck.kt`,
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
(`DEMO_BUITEN_UITROLPOORT`) — het contract-bootstrap-image uit `environment/` hangt aan `run` en niet
aan `deploy`, dus dat wordt daar niet door geraakt. Een nieuwe demo-module valt buiten de uitsluiting
en houdt zijn build: vergeten kost een overbodige build, niet een overgeslagen build.

Voor de test-scope geldt `demo/` wél als geheel: raakt een PR niets buiten deze map, dan test de
`demo`-shard alleen de modules hieronder. Behalve wanneer diezelfde PR de uitrol raakt — dan valt de
test-scope terug op alles, want een preview hoort niet uit te rollen wat in die run niet getest is.

Een PR die alleen `demo/` raakt koopt geen fuzz-ronde meer: de fuzz-doelen staan uitsluitend in
`libraries/` en `services/` (`.clusterfuzzlite/build.sh`), dus zo'n ronde kon per definitie niets
nieuws raken. Om dezelfde reden heeft `demo-console` geen JaCoCo-gate: de demo hoeft niet
productiewaardig te zijn.
