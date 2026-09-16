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

**`verouderd` blokkeert nergens, drie statussen wél.** Een verdwenen image (`pin-onvindbaar`) laat de
eerstvolgende herstart van het component vastlopen op `ImagePullBackOff`; een verdwenen bron-repo of
een onleesbare pin betekent dat de controle niets meer meet. Die drie maken zowel de PR-check als de
dagelijkse run rood.

**Het oordeel staat op één plek.** Beide workflows draaien `proeftuin-pin.sh`; het nieuwe script
bepaalt zelf niets over de stand van de pin, het handelt er alleen naar.

## Verificatie

- `bash .github/scripts/test-proeftuin-pin-pr.sh` → `Alle tests geslaagd. ASSERTIES=87`. De suite
  dekt per geval de gedane gh- en git-aanroepen, niet alleen de exitcode: de vier PR-toestanden,
  alle acht statussen, een image-referentie in commentaar, nul of twee image-regels, een lege of
  afgekapte regel, een fork-PR met dezelfde branchnaam, falende `gh`/`git`-aanroepen, een ontbrekend
  token en een onleesbare `compose.yaml`.
- `shellcheck -x -S warning` schoon op beide nieuwe scripts.
- Drempel in `ci-scripts.yml` gezet op 87.

## Nog te doen na de merge

1. De eerste run met de hand starten (`workflow_dispatch`) en controleren dat de PR verschijnt.
   `FUZZ_PIN_TOKEN` staat er al, dus er hoeft geen secret bij.
2. De achtergebleven `<!-- proeftuin-pin -->`-meldingen op openstaande PR's opruimen; die verdwijnen
   niet vanzelf, omdat de stap die ze wiste met deze wijziging verdwijnt.
3. Overwegen wat er van dit script en `fuzz-basis-pin.sh` gedeeld kan worden: hun PR-onderhoud
   (openzoeken, branch publiceren, opruimen) en vooral hun teststubs lopen grotendeels gelijk op.
