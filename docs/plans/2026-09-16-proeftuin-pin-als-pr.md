# De berichtenbox-pin komt als PR in plaats van als melding

**Status:** Uitgevoerd

## Context

De berichtenbox van de demo (`MinBZK/moza-poc`) staat in `compose.yaml` op digest gepind. Dat
bijwerken lag bij Dependabot; `pin-consistency.yml` was het vangnet en plaatste bij een
achterlopende pin een melding op elke openstaande PR.

Beide helften werkten niet meer zoals bedoeld.

**Dependabot is structureel geblokkeerd.** Zijn joblog (run `35069928159`, 16-09 07:43Z, en de
handmatig gestarte run `35077434391` om 09:06Z) haalt de nieuwe digest op, vergelijkt hem met de
gepinde, en besluit:

```
INFO Checking if minbzk/moza-poc latest needs updating
INFO Pull request #271 already exists for minbzk/moza-poc with latest version latest
```

In de job-definitie van dezelfde run staat `"existing-pull-requests":[{"pr-number":271,
"dependencies":[{"dependency-name":"minbzk/moza-poc","dependency-version":"latest",
"directory":"/"}]}]` met `"ignore-conditions":[]`. PR #271 is op 3 september gesloten zonder merge
(de bump ging via verzamel-PR #276). Omdat de "versie" van dit image altijd `latest` heet, matcht
die vastlegging elke volgende bump. `@dependabot recreate` antwoordt dat de branch verwijderd is en
verandert niets; de knop "Check for updates" op de Dependency graph loopt op hetzelfde vast; een
`ignore`-conditie is er niet om op te heffen; en een PR is niet te verwijderen. Er is dus geen route
terug.

**De melding stond in de weg.** Hun main loopt door, dus de melding verscheen binnen enkele uren op
élke openstaande PR — ook op PR's die niets met de demo te maken hebben. In de week van 14 tot 16
september stond hij op vijf PR's tegelijk.

## Wat er verandert

- **Nieuw: `.github/scripts/proeftuin-pin-pr.sh`** — handelt naar de status van `proeftuin-pin.sh`:
  bij `verouderd` zet het de regel in `compose.yaml` en opent of ververst het één PR op de vaste
  branch `chore/proeftuin-pin`; bij `ok` sluit het die PR en ruimt het de branch op; bij `preview`,
  `ontbreekt` en `oncontroleerbaar` doet het niets en laat het een openstaande PR staan; bij
  `pin-onvindbaar`, `bron-weg` en `geen-pin` eindigt het rood.
- **Nieuw: `.github/workflows/proeftuin-pin.yml`** — draait dat script op werkdagen (`23 14 * * 1-5`)
  en op `workflow_dispatch`, geserialiseerd op één concurrency-group.
- **`pin-consistency.yml`** verliest de comment-stap, `pull-requests: write` en de
  `melding`-opbouw. De statusbeoordeling blijft ongewijzigd: `pin-onvindbaar`, `bron-weg` en
  `geen-pin` blokkeren nog steeds een PR.
- **`deploy.yml`** geeft `checks-pins` daarmee alleen nog `contents: read`.
- **`dependabot.yml`** negeert `minbzk/moza-poc` expliciet, met de reden erbij — anders staat er een
  ecosysteem-regel die stilzwijgend niets doet.
- Documentatie bijgewerkt: `compose.yaml`, `docs/ontwikkelen.md`, `demo/environment/zad-demo/README.md`
  en `CLAUDE.md`.

## Ontwerpkeuzes

**Eén PR op een vaste branch, force-push per run.** Hetzelfde model als `fuzz-basis-pin.sh`. Een
doorgroeiende branch zou na een week een reeks pin-commits dragen; nu is er altijd precies één
commit bovenop main, en is de PR een momentopname van de huidige stand.

**Merge blijft een oordeel.** De PR draagt geen automerge. Hun main draagt ook halfaf werk; de
PR-body vraagt om de demo door te klikken vóór de merge. Achterlopen is geen kapotte build.

**`FUZZ_PIN_TOKEN`, geen `GITHUB_TOKEN`.** GitHub start geen workflows op events die `GITHUB_TOKEN`
veroorzaakt, dus een PR van dat token krijgt zijn verplichte checks nooit en is met strict main niet
te mergen. Er is dus een PAT nodig: fine-grained, alleen deze repo, `Contents: write` en
`Pull requests: write` — precies wat `FUZZ_PIN_TOKEN` al draagt. Een eigen secret zou zuiverder
heten, maar vraagt een aanvraag bij MinBZK en levert verder niets op. De naam dekt de bredere rol
daarmee niet meer, en een rotatie raakt voortaan twee workflows; dat staat bij beide genoteerd.

**`verouderd` blokkeert nergens, vier statussen wél.** Een verdwenen image (`pin-onvindbaar`) laat de
eerstvolgende herstart van het component vastlopen op `ImagePullBackOff`; een verdwenen bron-repo of
een onleesbare pin betekent dat de controle niets meer meet. Die drie maken zowel de PR-check als de
geplande run rood.

`oncontroleerbaar` is de vierde, en alleen in de geplande run. "Er is deze run niets vastgesteld" is
op een PR een tijdelijke hik die een ongerelateerde wijziging niet hoort te blokkeren, maar in de
geplande run is het de enige plek waar het zichtbaar wordt: blijft ghcr of de GitHub-API knijpen,
dan waarschuwt die run weken achtereen op groen terwijl de bump uitblijft, en niemand opent de
annotaties van een geslaagde cron-run.

**Geen melding op de PR, ook niet bij `preview`.** Een preview-pin is nog niet gemergd werk van hun
kant; die tag verdwijnt uit ghcr zodra hun PR sluit. Tot deze wijziging waarschuwde een comment
daarover op de PR die de preview-pin droeg. Die comment kwam op élke openstaande PR terecht en stond
daar in de weg, dus hij is weg — met als restrisico dat een preview-pin alleen nog als
`::warning::` in de job-log staat. Wie zo'n pin zet, ziet hem daar; wie hem reviewt, niet.

**Het oordeel staat op één plek.** Beide workflows draaien `proeftuin-pin.sh`; het nieuwe script
bepaalt zelf niets over de stand van de pin, het handelt er alleen naar.

**Het PR-onderhoud is gedeeld met het fuzz-pad.** `fuzz-basis-pin.sh` deed hetzelfde werk al: token
eisen, de eigen PR vinden zonder een fork-PR te raken, de branch als één commit bovenop main
neerzetten, en de PR opruimen zodra hij niets meer verandert. Die twee delen staan nu in
`pin-pr-lib.sh` en `pin-pr-teststubs.sh`; wat "verouderd" betekent en welke regelvorm vervangen wordt
blijft per pad. Dat is het eerste gesourcete bestand onder `.github/scripts/` — `shellcheck -x` in
`ci-scripts.yml` volgt `source`, dus de dekking blijft. De harness heet bewust niet `test-*.sh`:
`ci-scripts.yml` draait elk bestand met die naam als suite en eist er een `ASSERTIES=`-regel van.

Het delen kost wel iets: waar eerst elke suite zijn eigen stubs had, hangt de onderscheidende kracht
van beide suites nu aan één bestand dat zelf niet gedraaid werd. Eén regel volstond om ze allebei
betekenisloos te maken — `bevat() { ok "$1"; }` en beide suites blijven groen op exact hun
ondergrens. Vandaar `test-pin-pr-teststubs.sh`: die legt elke assertie-functie in beide richtingen
vast, plus de stubs en de afsluiting.

## Verificatie

- `bash .github/scripts/test-proeftuin-pin-pr.sh` → `Alle tests geslaagd. ASSERTIES=87`. De suite
  dekt per geval de gedane gh- en git-aanroepen, niet alleen de exitcode: de vier PR-toestanden,
  alle acht statussen, een image-referentie in commentaar, nul of twee image-regels, een lege of
  afgekapte regel, een fork-PR met dezelfde branchnaam, falende `gh`/`git`-aanroepen, een ontbrekend
  token en een onleesbare `compose.yaml`.
- `shellcheck -x -S warning` schoon op beide nieuwe scripts.
- Drempel in `ci-scripts.yml` gezet op 87.

## Wat de review opleverde

De review van deze PR bracht drie dingen aan het licht die hier zijn opgelost:

- **De `workflow_dispatch` miste een branch-guard.** Vanaf een feature branch zou de force-push die
  hele branch op `chore/proeftuin-pin` zetten en als PR naar main aanbieden. `fuzz-base-image.yml`
  dekte dat al af; bij het overnemen van de gedeelde functie was de guard eromheen blijven liggen.
- **`grep -c … || true` faalde open.** Exitcode 2 ("kon niet zoeken") leverde een lege telling,
  waarna de toets die op precies één regel bewaakt werd overgeslagen — juist wanneer er niets gemeten
  was. Beide pin-scripts onderscheiden nu "niets gevonden" van "niet kunnen zoeken".
- **De vervanging sloeg de regel plat.** Inspringing, aanhalingstekens en een toelichting achter de
  pin gingen verloren; een geciteerde regel brak de bump zelfs hard, terwijl `proeftuin-image.sh` die
  vorm juist ondersteunt. Alleen de referentie zelf wordt nu vervangen.

Twee eigenschappen van `proeftuin-pin.sh` bleven staan en zijn het vermelden waard, omdat de nieuwe
suite ze zichtbaar maakt zonder ze te bevriezen:

- `preview` betekent feitelijk "een ander ghcr-pad dan hun main-repository", niet specifiek hun
  preview-repository. Aan de PR-kant wordt dat nu afgevangen door de pad-guard in
  `proeftuin-pin-pr.sh`.
- De preview-tak staat ná de digest-lookup van de gepinde tag. Een preview-pin waarvan de tag al is
  opgeruimd komt dus langs als `pin-onvindbaar`, niet als `preview`. Dat is hier de juiste uitkomst
  — die demo start niet meer op — maar de melding noemt de oorzaak dan niet.

## Nog te doen na de merge

1. De eerste run met de hand starten (`workflow_dispatch`) en controleren dat de PR verschijnt.
   `FUZZ_PIN_TOKEN` staat er al, dus er hoeft geen secret bij.
2. De achtergebleven `<!-- proeftuin-pin -->`-meldingen op openstaande PR's opruimen; die verdwijnen
   niet vanzelf, omdat de stap die ze wiste met deze wijziging verdwijnt.
De gelijkenis met `fuzz-basis-pin.sh` is in deze wijziging zelf opgelost; zie de ontwerpkeuzes
hierboven.
