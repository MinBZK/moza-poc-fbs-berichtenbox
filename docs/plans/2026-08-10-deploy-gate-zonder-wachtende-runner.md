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

### `test.yml`, `detekt.yml`, `pin-consistency.yml`, `cflite_pr.yml`

- `workflow_call:` toegevoegd.
- `pull_request` beperkt tot `branches-ignore: [main]` (test, detekt, pins). `cflite_pr.yml`
  verliest zijn `pull_request`-trigger volledig: fuzzing draaide al alleen voor PR's naar main.
- `push: branches: [main]` verwijderd waar `deploy.yml` die dekking nu levert.

## Bewust niet gedaan

- **De vier `changes`-jobs samenvoegen.** `deploy.yml`, `test.yml`, `detekt.yml` en `cflite_pr.yml`
  detecteren elk zelf of er iets buiten documentatie is gewijzigd: ~211 jobs per week van ~4 s
  (~14 min) plus evenzoveel API-calls. Dedupliceren vergt een optionele `workflow_call`-input plus
  `always()`-conditie in elke aangeroepen workflow, omdat ze óók standalone moeten draaien voor
  gestapelde PR's. Die conditielogica is precies waar CI stil kapot gaat. De verhouding
  (14 min tegenover de 392 min die deze PR wegneemt) rechtvaardigt dat risico niet.
- **`build` achter de gate zetten.** Zou ~170 s runnertijd besparen bij een falende testrun, maar
  kost 83 s extra wachttijd op élke geslaagde run. Tests falen zelden genoeg om die ruil te maken.
- **De per-commit image-tag (#171/#172)** is niet aangeraakt.

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

## Verificatie

1. **Besparing gemeten** — dezelfde meting vóór en ná over een vergelijkbaar aantal runs.
2. **De poort werkt nog** — een revisie met een falende verplichte check deployt niet.
3. **De merge-route werkt nog** — alle required contexts verschijnen op de head-SHA; een docs-only
   PR haalt een merge-bare staat.
4. **Geen dubbele runs** — de tests draaien één keer per revisie.
