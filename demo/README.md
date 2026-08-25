# Demonstratiecode

Alles onder `demo/` bestaat om het Federatief Berichtenstelsel te kúnnen tonen. **Het draait nooit
in productie.** Het stelsel zelf staat onder `services/` (deployables) en `libraries/` (gedeelde
bibliotheken); wie wil weten wat het stelsel is, hoeft deze map niet te lezen.

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
de productie-stack (LDV-wrapper, JAX-RS-filters) niet erft. De prijs is wat duplicatie — de
elfproef-validatie staat er lokaal in.

`.github/scripts/demo-grens.sh` bewaakt de richting: het faalt zodra een module onder `services/` of
`libraries/` een demo-module als dependency declareert. Zonder die controle is de scheiding een
afspraak die alleen in review houdt, en dan is één `<dependency>` erbij genoeg om demo-code naar
productie te laten meeliften.

## Wat dit betekent voor de CI

Demo ≠ "wordt niet uitgerold". De FSC-harness levert het contract-bootstrap-image en een demo-module
kan een eigen ZAD-component hebben. `.github/scripts/wijzigingsfilter.sh` sluit daarom niet `demo/`
als geheel van bouwen en uitrollen uit, maar de delen die aantoonbaar geen image voeden
(`DEMO_NIET_UITGEROLD`). Een nieuwe demo-module valt buiten die uitsluiting en houdt dus zijn build:
vergeten kost een overbodige build, niet een overgeslagen build.

Voor de test-scope geldt `demo/` wél als geheel: raakt een PR niets buiten deze map, dan test de
`demo`-shard alleen de modules hieronder.
