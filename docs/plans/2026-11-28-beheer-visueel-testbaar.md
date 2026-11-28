# Beheer visueel testbaar maken

**Status:** Concept

## Context

De diensten leveren meetgegevens op de beheerpoort (#494) en de domeinmeetwaarden uit #498 tot en
met #501. Er is nog niets dat ze leest, toont of erop meldt. Doel van deze reeks: met de
storingsknoppen van het bedieningspaneel visueel kunnen zien dat een storing op het scherm komt.

## Snede

De vijf meetgegevens-PR's zijn nog open. De reeks wacht daar niet op: de integratiebranch
`feature/meetgegevens-integratie` is `feature/1474-basismeetgegevens` (#494) met #498, #499, #500
en #501 erin gemerged. Die branch krijgt geen PR; de PR's hieronder stapelen erop. Merget een basis,
dan zet `rebase --onto` de volgende over.

1. **De beheerpoort op ZAD laten uitlezen** (Refs MinBZK/MijnOverheidZakelijk#1473). De poort eist
   het bearer-token `METRICS_AUTH_TOKEN` dat de ZAD-dienst `metrics-scraper` meegeeft; het script
   `.github/scripts/meetgegevens-scraper.sh` koppelt die dienst aan uitvraag, magazijnen en
   stelselregister, en `deploy.yml` controleert de koppeling bij elke run die uitrolt.
2. **Dashboards en alertregels als bestanden** (Refs #1477, #1478). Grafana-JSON per dienst en
   Prometheus-alertregels, met een toets dat elke genoemde meetwaarde bestaat.
3. **Prometheus en Grafana in de lokale demo-stack**, gestapeld op 2, met de bestanden uit 2
   geprovisioneerd. De storingsknoppen (Toxiproxy) laten een uitvallend magazijn, een groeiende
   achterstand en een logboekstoring op het scherm zien.

## Ontwerpkeuzes in 1

- **Zonder token buiten dev/test dicht, niet een opstartfout.** Het platform kan een deployment
  zonder token renderen (OM logt dan alleen een waarschuwing). Een opstartfout zou van een gat in
  het beheer een storing in de keten maken; een dichte poort met `BEHEERPOORT_ZONDER_TOKEN` in de
  log niet.
- **De regel in fbs-common, de bedrading per dienst.** Dezelfde reden als bij de security-headers:
  een observer op een Vert.x-type in fbs-common zou elke consumer erven, ook die zonder HTTP-laag.
- **Een handler op de router van de beheerpoort** (`@Observes ManagementInterface`), vóór elke
  route die een pad bedient. `quarkus.management.auth` kent alleen basic (en mTLS via de
  TLS-configuratie), geen bearer-token met een eigen geheim.
- **De controle in deploy.yml waarschuwt en blokkeert niet.** Een ontbrekende koppeling kost de
  meetgegevens, niet de dienst. Wel rood als het projectbestand niet te lezen is.
- **De koppeling staat aan het component**, dus `test` en elke preview krijgen dezelfde annotaties;
  een preview hoeft niets extra's.

## Verificatie

- 1: `BeheerpoortToegangTest` (fbs-common) en `BeheerpoortTokenTest` per dienst;
  `test-meetgegevens-scraper.sh`. Live: de annotaties in het gerenderde manifest van een preview,
  en "eerste verzoek met een geldig token ontvangen" in de log van het component.
- 2: een toets die elke meetwaarde uit een paneel of regel naast de geregistreerde meters legt.
- 3: de demo-stack met podman, de storingsknoppen, en schermafdrukken in de PR.
