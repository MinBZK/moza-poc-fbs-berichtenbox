---
name: pr-reeks
description: Werk een lijst issues of een plan zelfstandig af als reeks draft-PR's, elk met een begrensde reviewloop (pr-review-toolkit plus projectagents, getoetst aan relevante overheidsskills) en een afsluitende reviewcomment
argument-hint: "<issues of plan> [rondes=4] [gestapeld]"
disable-model-invocation: true
---

# PR-reeks met reviewloop

Opdracht: $ARGUMENTS

Zegt de opdracht niets, dan geldt: maximaal 4 reviewrondes per PR, elke PR los op `main` tenzij
hij voortbouwt op de vorige.

De reeks loopt zonder tussentijdse communicatie met de opdrachtgever: geen vragen, geen
`AskUserQuestion`, geen wachten op een reactie. Verslag en open vragen staan per PR in de
afsluitcomment; de opdrachtgever leest die bij de review.

## 0. Voorbereiden

- `git fetch origin`; werk vanaf de actuele `origin/main`.
- Lees elk issue: `gh issue view <n> --repo MinBZK/MijnOverheidZakelijk --comments`.
- Kijk per issue of het al gerealiseerd is (main, gemergde PR's). Zo ja: geen PR, maar een
  issue-comment die per acceptatiecriterium aanwijst waar het staat. Sluiten beslist de opdrachtgever.
- Bepaal de snede. Horen issues bij elkaar, knip dan per stap in de keten in plaats van per issue:
  elke PR moet in één zitting te reviewen en te begrijpen zijn. Wordt een PR onderweg te groot,
  knip hem en meld dat.
- Vraagt een PR ontwerpkeuzes, maak dan eerst een plan (`superpowers:writing-plans`) in `docs/plans/`.
- Leg de snede en de volgorde vast in de body van de eerste PR, en begin.
- Loopt een issue vast op iets dat alleen de opdrachtgever kan beslissen, zet dan wat er staat als
  draft-PR met de vraag in de afsluitcomment, en ga door met het volgende issue dat er niet van
  afhangt.

## 1. Per PR: bouwen

- Eigen worktree; zet de sessie erin met `EnterWorktree` (`path`). Gebruik daar `/usr/bin/git`
  vanaf de worktree-root.
- Branch `feature/`, `fix/` of `chore/`. Gestapeld: de branch van de vorige PR is de basis.
- Implementeer met tests: happy en unhappy, en bij collecties leeg, één en meerdere.
- Lokaal groen: `./mvnw clean verify -pl <module> -am` per geraakte module; bij scripts de
  bijbehorende `test-*.sh`, shellcheck en actionlint. Triëer warnings (`/warnings-triage`).
- **Live testen is een standaard afweging, geen extra.** Toont de wijziging pas gedrag tegen echte
  infrastructuur (FSC-harness, demo-stack, compose, ZAD-scripts), draai het dan lokaal met podman.
  De host is gedeeld met andere sessies: stoor geen draaiende stack. Kan het niet, zet dan in de
  PR-body "Niet live getest" plus wat er na de merge te controleren is.
- Open de draft-PR met de skill `pr-klaarmaken` (Skill-tool). Stap 1-4 daarvan heb je hierboven al
  gedaan; `Closes` of `Refs MinBZK/MijnOverheidZakelijk#<n>` volgens stap 7.

## 2. Reviewronde

Commit eerst al het werk. Bewerk niets zolang er een reviewer loopt.

Kies de reviewers op basis van `git diff --name-only origin/<basis>...HEAD` en start ze
parallel in één bericht:

| Raakt de diff | Reviewers |
|---|---|
| altijd | `pr-review-toolkit:code-reviewer` |
| Kotlin | `pr-review-toolkit:silent-failure-hunter`, `kotlin-stijl-reviewer`, `taal-en-commentaar-reviewer` |
| tests | `test-dekkings-reviewer` (of `pr-review-toolkit:pr-test-analyzer`) |
| nieuwe types/invarianten | `pr-review-toolkit:type-design-analyzer` |
| `*-api.yaml` | `openapi-adr-reviewer` |
| `.github/` | `ci-workflow-reviewer` |
| migraties/entiteiten | `migratie-reviewer` |
| auth, tokens, crypto, input-parsing | `security-reviewer` |
| logging, endpoints, DTO's, LDV | `pii-log-auditor` |

Geef de reviewer de overheidsskills mee die bij het onderwerp horen:

| Onderwerp | Skills |
|---|---|
| REST/OpenAPI | `standaarden:ls-api`, `developer-overheid-nl-agent-skills:don-apis` |
| authenticatie, sessies, tokens | `standaarden:ls-iam`, `developer-overheid-nl-agent-skills:don-security` |
| FSC, outway/inway | `standaarden:ls-fsc`, `developer-overheid-nl-agent-skills:don-infra` |
| Logboek Dataverwerkingen | `standaarden:ls-logboek` |
| notificaties/CloudEvents | `standaarden:ls-notif` |
| persoonsgegevens | `nerds:nerds-privacy` |
| beveiliging, destructieve acties | `nerds:nerds-veiligheid` |
| CI, opruimen, resources | `nerds:nerds-duurzaamheid`, `developer-overheid-nl-agent-skills:don-leidraad` |
| demo-UI | `nerds:nerds-toegankelijkheid`, `developer-overheid-nl-agent-skills:don-front-end` |

Elke reviewer-prompt bevat:
- PR-nummer, worktree-pad, branch en het diff-commando. Vanaf ronde 2 ook de fix-commit van de
  vorige ronde (`git show <sha>`) als focus.
- "Laad `<skill>` met de Skill-tool en toets daaraan" voor elke gekozen overheidsskill.
- Letterlijk: "STRIKT READ-ONLY: wijzig geen bestand in de worktree en doe geen `git stash`,
  `checkout` of `reset`. Experimenten en mutaties alleen in een kopie onder /tmp, zonder `target/`."
- "Rapporteer Hoog/Medium/Laag met file:line, een concrete fix en of je het gereproduceerd hebt.
  Alleen echte problemen, geen lof. In het Nederlands."

Start de agents zelf in plaats van via `/pr-review-toolkit:review-pr`: die command geeft de
read-only-eis en de overheidsskills niet per agent mee. Zonder die eis bewerken en stashen
review-agents in de werkboom van de hoofdsessie, en gaat lopend werk verloren.

## 3. Verwerken

- Toets elke bevinding voordat je hem overneemt (`superpowers:receiving-code-review`). Klopt een
  bevinding niet, noteer dat met reden.
- Fix alle Hoog. Fix Medium en Laag als de fix klein is en weinig regressierisico heeft; anders
  "bewust gelaten", met reden. Een ontwerpwijziging voer je niet zelf door: die komt als open vraag
  in de afsluitcomment.
- Muteer voor elke nieuwe of gewijzigde test de code in een /tmp-kopie en bevestig dat de test
  faalt. Houd het aantal mutaties en vangsten bij.
- Opnieuw lokaal groen, één commit per ronde (`fix: reviewronde <N>, <kern>`), push.
- Iets buiten de scope beslis je niet zelf, maar leg je voor met afwegingen. Een nieuw issue dat we
  nu niet oppakken is werk op de backlog; direct meenemen kan tijd-efficiënter zijn, maar maakt de
  PR groter. Opties: meenemen in deze PR, een aparte PR zonder nieuw issue (met `Refs` naar het
  verwante issue), een comment op een bestaand issue, of een nieuw issue. Weeg omvang,
  regressierisico, reviewbaarheid en verwantschap met het lopende werk. Zet de opties met die
  afwegingen als open vraag in de afsluitcomment, en ga door binnen de scope.

## 4. Stoppen of doorgaan

- Stop zodra een ronde geen Hoog meer oplevert, of de resterende fixes zo klein zijn dat regressie
  onwaarschijnlijk is. Stop altijd bij het maximum aantal rondes.
- Een volgende ronde draait alleen met de reviewers die Hoog of Medium vonden, of wier terrein de
  fix raakt.
- Fixes na de laatste ronde zijn niet opnieuw gereviewd. Zeg dat in de afsluiting.

## 5. Afsluiten per PR

- Werk de PR-body bij met `gh api -X PATCH repos/MinBZK/moza-poc-fbs-berichtenbox/pulls/<n> -F body=@<bestand>`
  en controleer dat hij gewijzigd is.
- Plaats de afsluitcomment volgens [afsluiting.md](afsluiting.md).
- Volg `gh pr checks <n>`. Rood door eigen werk: fixen. Flaky: opnieuw draaien en het melden.
- Haal de PR nooit uit draft en voeg geen reviewer toe.

## 6. Naar de volgende PR

- Merget een basis-PR, zet de volgende dan over op `main`: de merge queue squasht, dus
  `git rebase --onto origin/main <oude-basis>` en daarna de base omzetten
  (`gh api -X PATCH repos/MinBZK/moza-poc-fbs-berichtenbox/pulls/<n> -f base=main`). Een gestapelde
  PR registreert `Closes` pas bij een merge naar `main`.
- Krijgt een PR conflicten doordat iets anders op `main` landde: merge `origin/main`. Is de merge
  schoon, push dan direct en laat de CI controleren; los je conflicten in eigen code op, dan eerst
  lokaal groen.

## 7. Eindmelding

Pas als de hele reeks klaar is, in de chat: een tabel PR | issue | rondes | Hoog gevonden/gefixt |
live getest | CI | open vragen (aantal, met link naar de afsluitcomment). De vragen zelf staan
op de PR; herhaal ze hier niet.

## Hervatten na compactie of sessieverlies

De stand staat in git en op de PR: commits `fix: reviewronde <N>` en de afsluitcomment. Bij
hervatten: `gh pr list --author @me --draft` en per worktree `git log --oneline origin/main..HEAD`.
