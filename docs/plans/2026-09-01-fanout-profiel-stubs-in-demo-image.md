# De fan-out van de vier ondernemers in het demo-profiel-image

**Status:** Uitgevoerd

## Context

Op de gedeelde omgeving toont de demo voor elke persona twee magazijnen, ook voor Landelijk Concern
N.V. (`KVK 90000003`) die er honderd hoort te hebben. Gemeten op preview `pr-250`:

| Schakel | Staat |
|---|---|
| Magazijn-simulator (`mpfm-w3h`) | draait, kent 98 magazijnen (`…0001` en `…0050` geven 200, `…0098` geeft de ingestelde 503, `…0099` een 404) |
| Register van de uitvraag (`mpfb-8wh`) | attachment `magazijnen-register` is gemount; de uitvraag is UP, dus `${MAGAZIJN_SIMULATOR_URL}` is ingevuld |
| Profielservice-stub (`mpfpsm-lcl`) | geeft voor `KVK 90000003` twee scopes: de twee echte magazijnen |

De keten is dus compleet op de profielservice na. Niemands profiel verwijst naar de gesimuleerde
magazijnen, dus de uitvraag heeft niets om te bevragen.

De oorzaak staat als openstaand punt in `demo/environment/zad-demo/magazijn-simulator.md` §5: de
gegenereerde ondernemer-stubs (`demo/generated/profiel/ondernemer-*.json`, voorrang 1) zitten niet in
het image. Lokaal komen ze binnen via de bind-mount in `compose.yaml`; op ZAD bestaat die route niet,
en `build-externe-stubs` in `deploy.yml` draait `demo/genereer-magazijnen.py` niet vóór de build.
Wat overblijft zijn de handgeschreven persona-stubs (voorrang 5) met de twee echte magazijnen.

## Doel

De vier ondernemers hebben op de gedeelde omgeving dezelfde fan-out als op een laptop: 3, 15, 45 en
100 organisaties. Zonder handwerk per uitrol, en zonder dat een fout in de generatie stil terugvalt
op twee.

## Aanpak

### 1. Genereren in CI, vóór de build van het demo-image

In de job `build-externe-stubs` van `.github/workflows/deploy.yml`, tussen de push van
`fbs-externe-stubs` en de build van `fbs-demo-profiel`:

```
python3 demo/genereer-magazijnen.py
mkdir -p wiremock/demo-profiel/generated
cp demo/generated/profiel/*.json wiremock/demo-profiel/generated/
```

`SIMULATOR_URL` blijft ongezet: de ondernemer-stubs dragen alleen OIN's, geen adressen. Die variabele
hoort bij het register en de simulator-set, en die twee komen hier niet uit.

### 2. Een staging-map binnen de build-context

De build-context is `wiremock/`; `demo/generated/` ligt daarbuiten. In plaats van de context naar de
repository-root te verbreden (dan reist de hele boom mee, inclusief `target/`) kopieert de
CI-stap de vier bestanden naar `wiremock/demo-profiel/generated/`. Die map krijgt een `.gitkeep` en
haar `*.json` gaan in `.gitignore` — zo blijft de `COPY` ook geldig in een schone werkkopie waar het
generatiescript niet gedraaid heeft.

In de Dockerfile erbij, als derde laag:

```
COPY demo-profiel/generated /home/wiremock/mappings/demo-profiel-generated
```

Dezelfde mapnaam als de bind-mount in `compose.yaml`, zodat lokaal en ZAD dezelfde structuur tonen.

### 3. Luidruchtig falen in plaats van terugvallen op twee

Dit is precies de faalwijze die de demo nu heeft: alles groen, fan-out stil op twee. Twee controles
in de CI-stap:

- **Vóór de build:** het aantal gekopieerde `ondernemer-*.json` moet gelijk zijn aan het aantal dat
  het script schreef, en niet nul.
- **Ná de build:** het image starten en `GET /api/profielservice/v1/KVK/90000003` opvragen; het
  aantal scopes moet 100 zijn. Dat toetst het artefact dat we werkelijk uitrollen — inclusief de
  voorrangsregel tussen de handgeschreven en de gegenereerde mappings, die geen enkele andere test
  raakt.

### 4. Geen repository-variable voor het aantal

§5 van het runbook stelde het aantal magazijnen uit een repository-variable voor. Dat doen we niet.
`n` zit vast aan twee met de hand geüploade ZAD-attachments (`magazijn-simulator-set` in `mpfm-w3h`,
`magazijnen-register` in `mpfb-8wh`), beide gegenereerd met 98. Een variabele die daarvan afwijkt
levert profielen met scopes naar magazijnen die niet bestaan — de uitvraag slaat die over met een
waarschuwing, dus de demo draait door met een fan-out die niemand heeft ingesteld. De default van het
script (98) is het enige getal; wie het verandert, regenereert en heruploadt beide attachments.

### 5. De uitrolpoort volgt

`demo/genereer-magazijnen.py` voedt vanaf nu een uitgerold image. Het valt vandaag buiten de
uitrolpoort via het `py`-alternatief van `DEMO_BUITEN_UITROLPOORT` in
`.github/scripts/wijzigingsfilter.sh`; blijft dat zo, dan draait een PR die de fan-out wijzigt geen
preview en bewijst de groene check niets. Het alternatief vervalt dus — er is geen ander `*.py`
direct onder `demo/` — en met hem de dode-letter-controle in `test-wijzigingsfilter.sh` die eist dat
het er wél een dekt. De fixture voor de generator verschuift van "demo-stack" naar
"demo met image".

### 6. Documentatie

- `demo/environment/zad-demo/magazijn-simulator.md`: §5 van voornemen naar verslag, het punt uit
  "wat er nog open staat", en de imagenaam corrigeren — de ondernemer-stubs horen in
  `fbs-demo-profiel`, niet in het gedeelde `fbs-externe-stubs` (die splitsing bestond nog niet toen
  §5 geschreven werd).
- `wiremock/demo-profiel/README.md` en de Dockerfile: de twee lagen en hun voorrang.
- `docs/demo-runbook.md` §6: J. Pietersen bevraagt 3 organisaties, niet 2.

## Wat dit niet oplost

De simulator van een preview heeft een eigen, lege database. Ook met de volle fan-out toont
`pr-250` pas berichten nadat het bedieningspaneel hem gevuld heeft — één knop, en het staat al in het
runbook.

## Verificatie

- `demo/genereer-magazijnen.py` draaien en de vier bestanden op scope-aantal controleren (3, 15, 45,
  100 — inclusief de twee echte).
- Het demo-image lokaal bouwen en de nacontrole uit stap 3 met de hand draaien.
- `.github/scripts/test-wijzigingsfilter.sh` groen, inclusief de gewijzigde fixture.
- `shellcheck` over de gewijzigde scripts.
- Na de merge op de gedeelde omgeving: `demo/meet-fanout.sh` tegen de ZAD-URL, vier ondernemers.
