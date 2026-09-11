---
name: pr-klaarmaken
description: Zet het werk op de huidige branch klaar als draft-PR — sync met main, verify per geraakte module, warnings triëren, PR openen en aan het issue koppelen
disable-model-invocation: true
---

# PR klaarmaken

Deze workflow heeft zij-effecten (push, PR openen) en wordt daarom alleen op verzoek gedraaid.

De volgorde is niet vrijblijvend: stap 1 vóór stap 3 scheelt een tweede CI-ronde, omdat `main`
branch-protection met `strict: true` heeft. Loopt de branch achter, dan draait CI en moet daarna
alsnog gesynct worden — inclusief de trage deploy-preview.

## 1. Sync met main

```bash
git fetch origin
git rebase origin/main
```

Zijn er conflicten, los die dan eerst op. Doorgaan met een achterlopende branch kost een volledige
tweede CI-ronde.

## 2. Bepaal wat er geraakt is

```bash
git diff --name-only origin/main...HEAD
```

Bepaal daaruit welke Maven-modules geraakt zijn. Alleen documentatie of workflows? Dan is stap 3
niet nodig; zeg dat expliciet in plaats van het stil over te slaan.

## 3. Verify per geraakte module

```bash
./mvnw clean verify -pl <module> -am
```

`clean` hoort erbij: we wisselen vaak van branch op een gedeelde bind mount, en een achtergebleven
`target/` laat Surefire stale `.class`-bestanden draaien.

Dit draait de volle gate: JaCoCo 90% line coverage en detekt op `maxIssues: 0`. Bedenk bij een
coverage-tekort dat alleen `@QuarkusTest`-coverage meetelt — pure MockK-unittests tellen niet mee
voor de drempel.

Sla `*Integration*`- of `*E2E*`-tests hier niet over; precies die vangen wat lokaal groen lijkt en
in CI omvalt.

## 4. Triëer de warnings

Loop de build-output na op waarschuwingen. Nieuwe, onverklaarde warnings blokkeren de PR: los ze op
of accepteer ze bewust met reden. Al geaccepteerd (buiten eigen beheer, uit de Maven-wrapper en
transitieve libraries): jansi's `System::load`, guava's `sun.misc.Unsafe`, en de JBoss
`LogManager accessed before ...`-melding uit de test-bootstrap.

## 5. Controleer het commentaar en de taalgrens

Draai de subagent `taal-en-commentaar-reviewer` over de diff. Raakt de wijziging logging,
endpoints, DTO's, exception mappers of LDV-code, draai dan ook `pii-log-auditor`.

## 6. Open de PR

```bash
git push -u origin <branch>
gh pr create --draft --base main --title "<type>: <beschrijving>" --body-file <bestand>
```

Vast in dit project:

- **Altijd `--draft`.** De opdrachtgever reviewt eerst zelf en zet de PR daarna met de hand ready
  for review. Haal een PR nooit op eigen initiatief uit draft.
- **Nooit een reviewer toevoegen.**
- **Nooit direct naar `main` pushen.** Branch-prefix `feature/`, `fix/` of `chore/`.

## 7. Koppel het issue

Issues staan in `MinBZK/MijnOverheidZakelijk`, niet in deze repo. De sluitregel in de PR-body moet
`owner/repo` bevatten, want een kaal `#<n>` wijst naar een PR in déze repo:

```
Closes MinBZK/MijnOverheidZakelijk#<n>
```

Bestaat er nog geen issue terwijl er wel één hoort te zijn, vraag dat dan na bij de opdrachtgever —
maak er zelf geen aan.

Werkt `gh pr edit` niet (dat faalt op projects-classic met exit 1 en een ongewijzigde body),
gebruik dan:

```bash
gh api -X PATCH repos/MinBZK/moza-poc-fbs-berichtenbox/pulls/<n> -F body=@<bestand>
```

en controleer achteraf of de body echt gewijzigd is.

## 8. Volg CI

```bash
gh pr checks <n>
gh run watch <run-id> --exit-status
```

Bij falen: `gh run view <id> --log-failed`.
