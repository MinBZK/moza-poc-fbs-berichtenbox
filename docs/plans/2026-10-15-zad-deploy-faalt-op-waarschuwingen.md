# Een uitrol die half lukt, is niet groen — implementatieplan

**Status:** Uitgevoerd (stap A), stap B verworpen — upstream uitgebracht in zad-actions v4.4.0
(2026-10-23), stap A in dezelfde PR als dit plan (#382). De meting van 2026-10-28 t/m 2026-11-04
(zie "Meting") liet zien dat de waarschuwing bij vrijwel elke uitrol komt en dat OM het component
daarna niet uitzet; `fail-on-warnings` aanzetten zou vrijwel elke deploy rood maken. De controle
die wél ontbreekt, volgt in MinBZK/MijnOverheidZakelijk#1327.

**Doel:** een deploy-job in `deploy.yml` faalt zodra Operations Manager meldt dat de uitrol wel is
uitgevoerd maar dat componenten ongezond zijn. Nu gaat zo'n job groen, en ziet niemand het tot een
afhankelijke deployment erover struikelt.

Issue: [MinBZK/MijnOverheidZakelijk#1324](https://github.com/MinBZK/MijnOverheidZakelijk/issues/1324). Upstream: [RijksICTGilde/zad-actions#77](https://github.com/RijksICTGilde/zad-actions/pull/77).

## Aanleiding

PR #376, run 38705795285 (2026-10-15). Tijdens een storing op het cluster (Kyverno-webhook in
time-out, `rcr.rijksapps.nl` gaf 503) rolde `deploy-preview-uitvraag` om 08:35 uit. OM antwoordde:

```
"headline": "The operation succeeded, but some components are unhealthy.",
"details": ["uitvraag (image_pull): Registry kon de container image niet leveren"]
```

De job meldde "Deployment successful" en ging groen. `pr-376-redis` en de Services van `uitvraag`
en `toxiproxy-aanmeld` zijn nooit aangemaakt. Pas `deploy-preview-magazijnen` faalde, op een
readiness-time-out van de democonsole (Redis onbereikbaar), en wees zo naar het verkeerde project.
Drie keer `gh run rerun --failed` hielp niet: de groene uitvraag-job draaide nooit opnieuw.

## Waar de controle ontbreekt

- `RijksICTGilde/zad-actions/deploy` (v4.3.2) roept `zad --output json deployment create …` aan en
  kijkt alleen naar de exitcode. `zadctl` kent `--strict` ("Exit non-zero when an operation succeeds
  but reports warnings"), maar de action geeft die vlag niet mee en heeft er geen input voor; ook
  `main` van zad-actions niet. De waarschuwingen gaan in JSON-modus naar stderr en komen alleen als
  platte tekst in het log, zonder annotatie.
- Ook met `--strict` blijft één pad open: de overname. Eindigt de create als `superseded`, dan volgt
  de action de opvolger met `zad --output json task wait`. Dat commando geeft het eindresultaat
  weer maar roept `surface_warnings` nooit aan (zad-cli v0.12.0 t/m v0.13.4), en `_poll_task` laat
  een `completed` taak met `processing.component_failures` gewoon slagen. Een uitrol die door een
  `project refresh` wordt overgenomen, precies het herstel dat `zad-gitops.md` aanraadt, gaat dus
  groen met ongezonde componenten.
- Ons eigen vangnet staat uit: `wait-for-ready: 'false'` op elke preview-job, omdat die wachtlus één
  health-pad op álle componenten afvraagt en Redis daar nooit op antwoordt (`deploy.yml:889-894`).
- `uitrol-poort` leest alleen job-uitkomsten en erft het gat.

Dit raakt alle tien `zad-actions/deploy`-aanroepen in `deploy.yml`, ook die naar `test`.

Wat dit plan níet vangt: resources die bij de Argo-sync niet ontstaan. In de aanleiding meldde OM
alleen `uitvraag (image_pull)`; dat `pr-376-redis` en de Services ontbraken door de Kyverno-time-out
stond niet in de waarschuwing. Zonder die image-pull was de job ook met `--strict` groen gebleven.
Een gezondheidscontrole per component (sync- en health-status uit `zadctl deployment describe`)
is een eigen vervolg, buiten dit plan: [MinBZK/MijnOverheidZakelijk#1327](https://github.com/MinBZK/MijnOverheidZakelijk/issues/1327).

## Ontwerp

### 1. Upstream: input `fail-on-warnings` in `zad-actions/deploy`

- Nieuwe input `fail-on-warnings`, default `'false'` (bestaande aanroepers merken niets), gevalideerd
  op `true`/`false`.
- `true` zet `--strict` vóór het subcommando: `zad --output json --strict deployment create …`.
- De stderr van de create gaat naar een tijdelijk bestand en wordt daarna ongewijzigd doorgegeven
  aan het log. Het warnings-object (`json.dumps(indent=2)`, dus `{` en `}` op een eigen regel)
  wordt eruit gelicht en met `jq` gelezen.
- Faalt de create op `--strict`, dan meldt de action per waarschuwing `headline`, `details` en
  `next_steps` als `::error::`. Zonder die vertaling kwam de bestaande `report_zad_error` uit op
  "failed with no HTTP status code", want stdout is dan een geslaagd taakresultaat.
- Zonder `fail-on-warnings` worden dezelfde waarschuwingen `::warning::`-annotaties. Zo staan ze
  in de samenvatting van de run, niet alleen diep in het log.
- Na een overname leest de action het eindresultaat van de keten zelf (`task_result_warnings` in
  `scripts/zad-common.sh`), zoals `degraded_diagnoses` in zad-cli dat doet: component failures,
  `warnings`, of een status `partial`/`degraded`. Dezelfde `::error::`/`::warning::`-vertaling,
  vóór de adressen worden opgevraagd. Een overnemende taak kan projectbreed zijn en meldt dan
  elke deployment die hij raakte; een failure die een andere deployment noemt, telt niet mee.
- Smoke-tests (`.github/workflows/smoke.yml`, stub `tests/stubs/zad`): de stub krijgt
  `ZAD_STUB_CREATE_STDERR`, faalt alleen onder `--strict`, en de argv wordt gelogd. Zes gevallen:
  - met `fail-on-warnings: 'true'` faalt de stap, wordt geen `url` gepubliceerd, en is `--strict`
    meegegeven;
  - zonder die input slaagt de stap, en wordt `--strict` níet meegegeven;
  - een ongeldige waarde faalt bij de invoercontrole;
  - een overname die eindigt met een ongezond component van déze deployment faalt met de input,
    publiceert geen `url` en vraagt geen adressen op;
  - dezelfde overname slaagt zonder de input, met `::warning::`-annotaties;
  - een overname waarvan de enige failure bij een andere deployment hoort, slaagt met de input.
- README-tabel en CHANGELOG (`[Unreleased]` → `Added`).

PR vanaf een fork; wij hebben geen schrijfrechten op de repo.

**Uitgevoerd:** gemerged als zad-actions#77, uitgebracht in
[v4.4.0](https://github.com/RijksICTGilde/zad-actions/releases/tag/v4.4.0) (commit `4217cea`). Twee
verschillen met het ontwerp hierboven: een annotatie noemt ook de `summary` van een waarschuwing
(daar zet de CLI de reden van een geweigerde goedkeuring of een `partial`-status), en elke tekst wordt
gecodeerd zoals workflow-commando's dat eisen, zodat een kubelet-melding met een regeleinde geen eigen
`::`-commando kan worden. De release zet ook `astral-sh/setup-uv` van v6 naar v7.

### 2. Deze repo, na de release van zad-actions

In twee stappen, omdat `test` hierdoor vaak rood wordt (zie Afweging):

**Stap A: meten.** De pin van `zad-actions/deploy` in `deploy.yml` naar de nieuwe release (SHA +
versie-comment), zonder de input. De nieuwe versie maakt van elke waarschuwing al een
`::warning::`-annotatie, ook na een overname. Een week lang tellen per job hoe vaak dat gebeurt en
of een tweede poging met dezelfde tag het oplost. `zad-actions/cleanup` in `cleanup-preview.yml`
gaat mee naar dezelfde release, zodat beide pins één versie blijven noemen.

Tellen, vanaf de dag van de merge (2026-10-28): één regel per ongezond component per test-job, met
de run erbij om een herhaling te kunnen opzoeken.

```bash
repo=MinBZK/moza-poc-fbs-berichtenbox
gh run list --repo "$repo" --workflow "Deploy ZAD" --branch main --event push \
  --created ">=2026-10-28" --limit 200 --json databaseId --jq '.[].databaseId' |
while read -r run; do
  gh api "repos/$repo/actions/runs/$run/jobs?per_page=100" \
    --jq '.jobs[] | select(.name | startswith("deploy-test")) | "\(.id)\t\(.name)"' |
  while IFS=$'\t' read -r job naam; do
    gh api "repos/$repo/check-runs/$job/annotations" \
      --jq '.[] | select(.annotation_level == "warning")
                | select(.message | test("^Details: [^ ]+ \\([a-z_]+\\): ")) | .message' |
      sed "s|^Details: |$run $naam: |"
  done
done
```

Het filter pakt alleen de `Details`-regels in de vorm `<component> (<failure_type>): …`; de
logregels die de action na een overname meestuurt, beginnen met spaties en vallen erbuiten. GitHub
bewaart per stap hooguit tien warning-annotaties. Noemt een job er tien, lees dan het log.

**Stap B: afdwingen.** *Verworpen na de meting, zie "Meting". Het ontwerp blijft hieronder staan
zodat de afweging na te lezen is.*
- `fail-on-warnings: 'true'` op alle tien aanroepen.
- Een maatregel tegen de achterlopende registry-mirror, gekozen op de meting uit stap A. Voorkeur:
  één herhaalstap per deploy-job met dezelfde tag. Een upsert heft een uitschakeling door een
  mislukte image-pull op (`zad-gitops.md`). De eerste stap krijgt `continue-on-error: true`, de
  herhaling draait met `if: steps.<eerste>.outcome == 'failure'` en zonder `continue-on-error`,
  anders bepaalt de groene eerste poging de uitkomst van de job.
- `test-uitrol-poort.sh` eist dat elke `zad-actions/deploy`-stap `fail-on-warnings: 'true'` draagt,
  met de lijst uit `workflow-jobs.py --uitrol`/`--with`. Zonder die controle valt een elfde
  deploy-stap, of een kopie van een oude, stil terug op de default `false`. Het verwachte aantal
  asserties in `VERWACHTE_ASSERTIES` (`ci-scripts.yml`) groeit mee.

**Doc, met stap A:** `docs/operations/zad-gitops.md`, een nieuw blok "Gelukt, maar ongezond" vóór
de valkuilen:
- `gh run rerun --failed` draait een groene deploy-job niet opnieuw. Na een storing die de
  waarschuwing van een groene job verborg: op een PR de hele run opnieuw draaien.
- Op `main` alleen de nieuwste run opnieuw draaien. Een push-tag heeft geen poging-suffix
  (`main-<sha7>`), en de concurrency-groepen `zad-*-test` zetten jobs achter elkaar, niet op
  volgorde van commit. Een oudere run rolt `test` dus groen terug naar oude images; de volgende
  uitrol naar `test` herstelt een uitgeschakeld component vanzelf.
- Bij de overname-faalgevallen: een overname wordt nu ook op waarschuwingen beoordeeld.

**Doc, met stap B:** in hetzelfde blok wordt "de job blijft groen" een rode job, en volstaat
`rerun --failed` voor die job.

**Doc, na de meting (in plaats van stap B):** het blok "Gelukt, maar ongezond" zei dat OM een
component na een `image_pull` uitzet tot de volgende uitrol. Dat wordt het gemeten gedrag: de
melding komt bij vrijwel elke uitrol, OM zette in de meetweek geen component uit, en
`fail-on-warnings` staat bewust uit. Opnieuw uitrollen blijft het herstel voor een component dat
blijvend niet draait.

Dependabot bood dezelfde bump aan in #406, maar een bot-PR rolt niet uit (`skip-bot-prs`), dus die
levert geen preview met de nieuwe action op. Stap A doet de bump daarom bewust zelf, als menselijke
PR met een preview; #406 vervalt met de merge.

## Meting (stap A), 2026-10-28 t/m 2026-11-04

Issue: [MinBZK/MijnOverheidZakelijk#1406](https://github.com/MinBZK/MijnOverheidZakelijk/issues/1406).

**Hoe vaak.** Zeven runs rolden echt uit: vier op `main` (`a6b8f36`, `c0a06a6`, `29776e2`,
`816d13a`) en drie previews van `chore/proeftuin-pin` (runs 40867345436, 41048972265, 41455894352).
De overige runs in die week waren geannuleerd, kwamen uit de merge queue of sloegen de deploy over.
Geteld met het commando hierboven; voor de previews op hun eigen runs, met `deploy-preview` in plaats
van `deploy-test`:

| | Deploy-jobs met een `image_pull`-waarschuwing |
|---|---|
| `main` (`test`) | 15 van 16 |
| previews | 11 van 12 |

Elke waarschuwing had dezelfde tekst: "Registry kon de container image niet leveren". Per component
op `main`: `uitvraag` en `stelselregister` 4 van 4; `profiel`, `demopersonas` en
`magazijna-fscbootstrap` 3 van 4; `logius-fscbootstrap` 2 van 4; `magazijnsimulator`,
`notificatie`, `magazijna` en `magazijnb` 1 van 4. Elk geraakt image is een eigen, net gebouwd
image; `democonsole`, ook een eigen image, bleef die week vrij. Images van derden met een vaste pin
(toxiproxy, redis, de FSC-componenten) kregen de waarschuwing nooit. Geen enkele stap kwam aan de
grens van tien annotaties.

**Wat het gevolg is.** Bepalend is het gerenderde manifest in `rig-cluster-application-test`. Daar
stond na de laatste run elk geraakt component op `replicas: 1` met de nieuwe tag `main-816d13a`, en
elk component met een publiek adres op `test` antwoordde. De commit-historie van die manifests
sinds 2026-10-01 bevat voor geen van die tien componenten een `replicas: 0`, en ook niet voor
`democonsole`. Die periode omvat de twee main-runs van 2026-10-14 onder "Afweging"; de manifests
van de twee preview-runs daar (`pr-<n>`) zijn niet nagekeken. De melding van OM zegt het nu zelf:
"het ophalen wordt vanzelf opnieuw geprobeerd en herstelt zodra de registry weer werkt".

**Niet vastgesteld.** Hoe lang het duurt tot de nieuwe pod draait. De manifests gebruiken
`RollingUpdate` met één replica, dus de oude pod blijft antwoorden tot de nieuwe klaar is; een
groene health-check bewijst de nieuwe versie niet. De pod-events vragen een SSO-login bij OM, de
API-key per project geeft alleen logs. Of een tweede poging met dezelfde tag helpt, is ook niet
gemeten: niemand heeft in die week een deploy-job herhaald.

**Besluit.** Stap B vervalt. Met `fail-on-warnings` aan zou vrijwel elke deploy-job rood worden,
op een melding die in de meetweek nergens tot een uitgeschakeld component leidde; zonder die input
heeft een herhaalstap geen functie. Stap A blijft: de annotatie houdt de melding zichtbaar. Wat
"groen maar draait niet" echt vangt, is een controle per component ná de uitrol, en die controle
moet ook nagaan dat de draaiende pod de nieuwe tag heeft: MinBZK/MijnOverheidZakelijk#1327.

Bijvangst: sinds v4.4.0 (`astral-sh/setup-uv` v7) heeft elke deploy-stap ook de annotatie "Empty
workdir detected". Die komt uit de `setup-uv`-stap binnen `zad-actions/deploy`, die
`ignore-empty-workdir` niet zet en er geen input voor doorgeeft; dempen kan dus alleen upstream.

## Afweging

Een image-pull-waarschuwing is geen uitzondering. In de logs van vier groene runs van de week
vóór de aanleiding stonden er zes deploy-jobs met "succeeded, but some components are unhealthy",
alle `image_pull`:

- main-run 38560906604: `stelselregister`, `democonsole`, `magazijna-fscbootstrap` en
  `logius-fscbootstrap`, verspreid over drie van de vier test-jobs;
- main-run 38570113788: `profiel`;
- preview-runs 38574463179 en 38544152443: `stelselregister`.

Vermoedelijk loopt de pull-through-mirror `rcr.rijksapps.nl` vlak na een push achter. De
automatische herverwerking probeert een uitgeschakeld component niet opnieuw, dus die componenten
stonden tot de volgende push op `replicas: 0`, terwijl de run groen was. Dat pleit vóór het plan,
maar met de input direct aan wordt `main` structureel vaak rood. Vandaar eerst meten (stap A) en
een herhaalstap (stap B).

*De meting weerlegt de zin over `replicas: 0` voor de twee main-runs: de manifests van die
componenten op `test` stonden die week op `replicas: 1`. Zie "Meting".*

"Groen maar draait niet" kost meer dan een rode job, zie de aanleiding. Alternatief was een eigen
`zadctl deployment describe`-wachtstap na elke deploy-stap: tien keer meer code met polling, en
die stap zou overdoen wat OM al meldt. Voor de Kyverno-helft (zie "Waar de controle ontbreekt") is
zo'n controle wel de enige weg; dat vervolg staat in MinBZK/MijnOverheidZakelijk#1327.

## Verificatie

- Upstream: de smoke-suite is groen, en de nieuwe gevallen falen met de wijziging teruggedraaid
  (de stap slaagt dan ondanks de waarschuwing). Het overnamepad is lokaal tegen de stub gedraaid:
  eigen failure met en zonder input, een failure van een andere deployment, en een schoon resultaat.
- Stap A: een preview-run op de PR is groen; na de merge staan waarschuwingen van de test-jobs
  (ook `fsc-logius` en `fsc-magazijna`) als annotatie in de samenvatting van de main-run. De telling
  van die week bepaalt stap B. *Gedaan: de annotaties staan in de samenvatting, ook die van
  `logius-fscbootstrap` en `magazijna-fscbootstrap`; uitkomst onder "Meting".*
- Stap B (vervallen): `actionlint`, `ci-workflow-reviewer` op `deploy.yml`, en
  `test-uitrol-poort.sh` faalt zodra één deploy-stap de input mist. Een echte waarschuwing is niet
  op afroep te maken, dus het rode pad steunt op de upstream smoke-test; de zes test-aanroepen
  draaien pas na de merge voor het eerst met de input, dus de eerste main-run na stap B wordt bewust
  nagekeken.
