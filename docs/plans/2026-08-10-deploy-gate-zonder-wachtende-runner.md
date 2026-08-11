# Deploy-gate zonder wachtende runner

**Status:** Uitgevoerd
**Issue:** #173

## Context

`deploy.yml` heeft een `gate`-job die de deploy tegenhoudt tot de verplichte checks uit *andere*
workflows groen zijn. Die checks draaien in aparte workflows, dus er kan geen `needs:` aan gehangen
worden; de gate pollt hun check-runs op dezelfde head-SHA met `sleep 15`. Functioneel klopt dat,
maar het kost een volledige runner-VM die vrijwel alleen slaapt.

Gemeten over 3–10 augustus 2026 (`gh api repos/{repo}/actions/runs/{id}/jobs`, script in de
PR-beschrijving):

| job | workflow | n | totaal | gemiddeld | max |
|-----|----------|---|--------|-----------|-----|
| `gate` | deploy.yml | 51 | 23.522 s (392 min) | 461 s | 910 s |
| `test` | test.yml | 50 | 21.001 s | 420 s | 504 s |
| `PR` (fuzz) | cflite_pr.yml | 17 | 8.942 s | 526 s | 663 s |
| `detekt-gate` | detekt.yml | 53 | 2.840 s | 54 s | 79 s |
| `infra-image-pins` | pin-consistency.yml | 55 | 304 s | 6 s | 31 s |
| `build` (matrix ×2) | deploy.yml | 104 | 8.572 s | 83 s | 271 s |

Twee dingen vallen op die het richtingskeuze-plaatje bepalen:

- **De fuzz-check is de langste pool (526 s), niet `test` (420 s).** Elke oplossing die alleen
  `test` versnelt of ontwijkt, lost niets op.
- **`build` duurt maar 83 s.** De gate wacht dus ~380 s ná de build. Dat maakt richting 3
  ("gate pas na de build starten") bij voorbaat marginaal.

Daarnaast: de gate-timeout staat op 900 s terwijl de fuzz al 663 s piekte — krapper dan bedoeld.

## Afweging van de richtingen

### 1. `workflow_run`-trigger — afgevallen

Een `workflow_run`-run hangt aan de default branch: `GITHUB_SHA` is de main-head, en de check-runs
van die run verschijnen dus op main, niet op de head-SHA van de PR. De required contexts
`deploy-preview-uitvraag`/`-externe-stubs`/`-magazijnen` zouden nooit meer op een PR verschijnen en
elke merge permanent blokkeren. Dat is te omzeilen door met `POST /repos/{o}/{r}/statuses/{sha}`
zelf commit-statussen op de PR-head te zetten, maar dan:

- draait de deploy in een context mét schrijfrechten en projectsecrets, getriggerd door een run van
  onvertrouwde PR-code (het patroon dat Scorecard's Dangerous-Workflow-check adresseert);
- is de merge-route afhankelijk van zelfgeschreven statussen in plaats van de check-runs die
  branch protection nu op `app_id 15368` (GitHub Actions) vastpint;
- en — doorslaggevend — **is het niet te verifiëren vóór de merge**: een `workflow_run`-workflow
  triggert alleen als het bestand op de default branch staat. Precies de wijziging met het hoogste
  "blokkeert alle merges"-risico is dan de enige die je niet op een PR kunt uitproberen.

### 2. Consolideren via `workflow_call` — gekozen

`test.yml`, `detekt.yml`, `pin-consistency.yml` en `cflite_pr.yml` krijgen een `workflow_call`-
trigger en worden vanuit `deploy.yml` als job aangeroepen. De deploy hangt er met `needs:` aan; de
`gate` blijft bestaan maar wordt een **aggregator zonder wachtlus**: hij leest `needs.*.result` en
is in seconden klaar.

Waarom de gate niet helemaal verdwijnt: de "skipped/neutral telt als OK"-regel (docs-only PR's,
niet-fuzz-relevante PR's, push-naar-main zonder fuzz) moet ergens staan. Als `if:`-expressie op
elk van de zes deploy-jobs is dat zes keer dezelfde `always() && ...`-formule met zes kansen op
drift. In één aggregator staat de regel één keer, mét de bestaande audit-logging bij een
overgeslagen check.

Dubbele runs worden voorkomen door de PR-trigger van de aangeroepen workflows te beperken tot
`branches-ignore: [main]` — precies het complement van `deploy.yml`'s `branches: [main]`. Voor een
PR naar main draait alleen `deploy.yml` (en roept de checks aan); voor een gestapelde PR draaien
alleen de losse workflows. De `push: branches: [main]`-triggers vervallen: `deploy.yml` draait daar
al en roept dezelfde jobs aan.

Kosten: de check-namen krijgen het caller-job-voorvoegsel (`test` → `test / test`), dus branch
protection moet mee. Dat is een handmatige stap buiten de repo, maar — anders dan bij richting 1 —
**verschijnen de nieuwe contexts op de PR zelf**, dus je ziet vóór het omzetten dat ze bestaan en
groen zijn.

### 3. Gate goedkoper maken — afgevallen als hoofdrichting

`gate` pas na `build` starten scheelt de build-duur: 83 s van 461 s (18%). Poll-interval omhoog
scheelt API-verkeer maar geen runnertijd. Beide laten de kern intact: een VM die slaapt. Alleen
relevant geweest als 1 én 2 waren afgevallen.

## Wijzigingen

### `deploy.yml`

- `gate` verliest de poll-lus en wordt aggregator: `needs: [changes, checks-test, checks-detekt,
  checks-pins, checks-fuzz]`, `if: always() && ...`, faalt zodra een van de results niet
  `success`/`skipped` is. `TIMEOUT_SECONDS`/`POLL_SECONDS` en de `checks: read`-permissie
  vervallen.
- Vier caller-jobs erbij: `checks-test`, `checks-detekt`, `checks-pins`, `checks-fuzz`. Elk met
  `if: github.event.action != 'closed'` (de `closed`-trigger is voor de cleanup, niet voor
  toetsing) en de permissies die de aangeroepen workflow nodig heeft. `checks-fuzz` draait alleen
  op `pull_request` — cflite bestaat niet op push.
- De workflow-brede `concurrency` verhuist naar job-niveau. Reden: met de tests binnen deze
  workflow zou `cancel-in-progress: false` op workflow-niveau een opvolgende push laten wachten
  tot de vorige deploy klaar is, én de achterhaalde testrun helemaal uit laten draaien. Dat is
  precies de verspilling die we wegnemen. De deploy- en cleanup-jobs krijgen nu elk een eigen
  groep per project met `cancel-in-progress: false` (dezelfde bescherming als voorheen: geen
  halverwege afgebroken ZAD-deploy), terwijl de toetsende jobs hun eigen
  `cancel-in-progress: true` uit de aangeroepen workflow houden.

- `timeout-minutes` op elke job. GitHub's default is 360 minuten; een hangende job houdt zo lang
  een runner bezet voordat iemand ingrijpt — dezelfde soort verspilling als de wachtlus, alleen
  zeldzamer. De waarden staan op ~3× de waargenomen maximumduur. Jobs die een andere workflow
  aanroepen kunnen de sleutel niet dragen; die staat daar in de aangeroepen workflow.

### `test.yml`, `detekt.yml`, `pin-consistency.yml`, `cflite_pr.yml`

- `workflow_call:` toegevoegd.
- `pull_request` beperkt tot `branches-ignore: [main]` (test, detekt, pins). `cflite_pr.yml`
  verliest zijn `pull_request`-trigger volledig: fuzzing draaide al alleen voor PR's naar main.
- `push: branches: [main]` verwijderd waar `deploy.yml` die dekking nu levert.
- De workflow-brede `permissions: read-all` van `test.yml` en `detekt.yml` teruggebracht tot
  `contents: read` + `pull-requests: read`. Een aangeroepen workflow mag niet méér rechten hebben
  dan de caller-job toekent, en die kent alleen toe wat de jobs echt nodig hebben.
- `timeout-minutes` per job.

## Bewust niet gedaan

- **De vier `changes`-jobs samenvoegen.** `deploy.yml`, `test.yml`, `detekt.yml` en `cflite_pr.yml`
  detecteren elk zelf of er iets buiten documentatie is gewijzigd: ~211 jobs per week van ~4 s
  (~14 min) plus evenzoveel API-calls. Dedupliceren vergt een optionele `workflow_call`-input plus
  extra conditielogica in elke aangeroepen workflow, omdat ze óók standalone moeten draaien voor
  gestapelde PR's. Bij het uitwerken kwam een concreet gat boven: laat je `checks-test` van
  `changes` afhangen, dan wordt bij een falende `changes`-job de hele testjob overgeslagen — en
  `skipped` telt door als succes voor branch protection, dus ongeteste code zou merge-baar worden.
  Sluitbaar met een `!cancelled()`-guard plus "sla alleen over bij een expliciete `false`", maar
  dat zijn drie samenwerkende condities over drie bestanden voor 14 minuten per week. Niet waard
  tegenover de 388 minuten die deze PR wegneemt.
- **`build` achter de gate zetten.** Zou ~190 s runnertijd besparen op de 12% runs waarin een
  check faalt (6 van 51 in de meetweek), maar kost 83 s extra wachttijd op élke geslaagde run.
  Netto slechter.
- ~~**`build` laten afhangen van `changes`.**~~ Opgelost in #172, dat tijdens deze PR op main
  landde: `build` en `build-externe-stubs` hangen daar nu aan `changes` en slaan docs-only én
  bot-PR's over. De blokkade die dit hier deed doorschuiven — de cleanup die een niet-bestaande
  tag zou verwijderen — verviel in dezelfde wijziging, doordat de image-cleanup op prefix werkt
  in plaats van op een exacte tag.
- **De per-commit image-tag (#171/#172)** is niet aangeraakt.

Deze posten staan met meetgegevens en de gevonden valkuilen in #176.

## Samenloop met #172

#172 (unieke image-tag per commit) landde op main terwijl deze PR openstond en raakt dezelfde
jobs. Bij het samenvoegen:

- `build`/`build-externe-stubs` houden de `changes`-afhankelijkheid uit #172 én de job-concurrency
  uit deze PR. Het oorspronkelijke argument voor die concurrency — voorkomen dat twee runs dezelfde
  tag overschrijven — is met de per-commit-tag vervallen; wat blijft is dat een achterhaalde build
  geen runnertijd hoeft te kosten. Het comment is daarop aangepast.
- De `cleanup-preview-*`-jobs verloren in #172 hun `packages: write` en `needs: meta`; die
  versmalling is overgenomen, met de job-concurrency en `timeout-minutes` van deze PR erbovenop.
- De nieuwe job `cleanup-preview-images` heeft `timeout-minutes` gekregen.

**Nieuwe randgeval door het verplaatsen van de concurrency.** Een close-run wacht niet meer op een
nog lopende build van een eerdere push (venster ≈ 85 s). Sluit iemand de PR vlak na een push, dan
kan `cleanup-preview-images` klaar zijn vóór die build zijn tag pusht, en blijft één ghcr-versie
achter. De build-jobs kunnen niet in de cleanup-groep meedraaien — ze hebben er per service al
één, anders blokkeren ze elkaar. Gevolg is een weesversie, geen kapotte deploy; herdraaien van de
job ruimt hem op. Staat als bekende beperking in `deploy.yml` en als vervolgpunt in #176.

## Handmatige stap bij de merge

Branch protection op `main` moet in dezelfde beweging mee. Oud → nieuw:

| oud | nieuw |
|-----|-------|
| `test` | `checks-test / test` |
| `detekt-gate` | `checks-detekt / detekt-gate` |
| `infra-image-pins` | `checks-pins / infra-image-pins` |
| `PR` | `checks-fuzz / PR` |

Ongewijzigd: `Analyze (kotlin)`, `deploy-preview-uitvraag`, `deploy-preview-externe-stubs`,
`deploy-preview-magazijnen`, `strict: true`.

Volgorde: eerst de PR groen laten worden (de nieuwe contexts verschijnen dan op de head-SHA),
dán branch protection omzetten, dán mergen. Andersom blokkeert de oude contextnaam de merge.

De nieuwe namen bestaan alléén op branches die deze wijziging bevatten; open PR's die nog op de
workflows van `main` draaien produceren de oude. Er is dus geen moment waarop beide sets bestaan —
wie de knop omzet, blokkeert tijdelijk de andere groep. Omdat `strict: true` die PR's na de merge
sowieso tot rebasen dwingt, is het extra ongemak vooral timing: houd het venster tussen omzetten
en mergen kort en meld het. De volledige afweging met de drie routes staat in de beschrijving van
#174.

## Verificatie

Uitgevoerd op PR #174 en de wegwerp-PR #175 (10 augustus 2026).

1. **Besparing gemeten.** Over de vijf runs met de nieuwe workflow (vier op #174, één op #175)
   duurde `gate` 2–4 s, gemiddeld 3,4 s — tegenover 461 s gemiddeld over 51 runs ervoor:
   **99,3% minder runnertijd**, geprojecteerd ~389 min per week. Het aantal API-verzoeken van de
   gate gaat van ~120 per run naar 0. Ter controle draaide in hetzelfde tijdvak een run van een
   andere branch (31389684161) nog op de oude, gepollde gate uit main: 526 s.
   Doorlooptijd van run-start tot de eerste preview-deploy: 475 s en 655 s ná, tegenover
   gemiddeld 735 s (spreiding 434–936 s, n=38) ervóór — binnen de bestaande spreiding, dus geen
   verlenging. Die doorlooptijd wordt bepaald door de traagste check (`test` 420 s, fuzz 526 s),
   niet door de poort.
2. **De poort werkt nog.** Wegwerp-PR #175 forceerde detekt-bevindingen. Run 31387899696:
   `checks-detekt / detekt-gate` faalde, `gate` faalde na 3 s met
   `Verplichte check 'detekt-gate' eindigde als 'failure' — deploy geblokkeerd`, en alle zes
   deploy-jobs bleven `skipped`. Er is niets uitgerold.
3. **De merge-route werkt nog.** Run 31388950061 laat alle vier de nieuwe contexts op de head-SHA
   verschijnen (`checks-test / test`, `checks-detekt / detekt-gate`,
   `checks-pins / infra-image-pins`, `checks-fuzz / PR`), naast de ongewijzigde
   `deploy-preview-*` en `Analyze (kotlin)`. Voor de docs-only route is op #175 een run gedraaid
   waarin elke `changes`-job `run=false` rapporteerde (31388993368): alle vier de contexts
   verschenen als `skipped`, de deploy-jobs sloegen over en de run eindigde groen — merge-bare
   staat.
4. **Geen dubbele runs.** Op de branch van #174 draaiden alleen `Deploy ZAD` en `CodeQL`. `Test`,
   `detekt`, `Pin consistency` en `ClusterFuzzLite PR fuzzing` startten niet zelfstandig.
5. **Gestapelde PR's blijven getoetst zonder te deployen.** Wegwerp-PR #177 (base = de
   feature-branch) draaide `Test` (job `test`, 444 s, geslaagd), `detekt` en `Pin consistency`
   zelfstandig; `Deploy ZAD` en `ClusterFuzzLite PR fuzzing` startten niet.
6. **PR-close doet geen toetswerk meer dan nodig.** De close van #175 (run 31390338566) sloeg alle
   vier de `checks-*`-jobs en de `gate` over en draaide alleen de drie cleanup-jobs.
7. **De rechten kloppen door de aanroep heen.** De JaCoCo-coverage-comment (`pull-requests: write`
   via de caller-job), de ZAD-preview-comment en de detekt-SARIF-upload (`security-events: write`)
   werkten alle drie op #174.

Bij de eerste run op #174 faalde `deploy-preview-magazijnen` op
`timed out waiting for application to be created`. Dat is de bekende Argo-Application-wait aan
ZAD-zijde die in de kop van `deploy.yml` staat beschreven; de tweede run deployde hetzelfde
project zonder wijziging succesvol.
