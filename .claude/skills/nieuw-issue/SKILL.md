---
name: nieuw-issue
description: Maak één of meer issues aan in MinBZK/MijnOverheidZakelijk met de juiste labels, een functionele beschrijving en een sub-issue-koppeling aan de juiste groep onder epic #238. Alleen gebruiken als de gebruiker expliciet om een issue vraagt.
---

# Nieuw issue

Deze workflow maakt iets aan in een andere repository. Draai hem alleen als de gebruiker om een
issue vraagt — met `/nieuw-issue` of in eigen woorden ("maak hier issues voor aan") — en nooit op
eigen initiatief. Hoort er een issue bij dat nog niet bestaat, vraag het dan na in plaats van het
zelf aan te maken.

Vier dingen gaan hier standaard mis: het issue landt in de verkeerde repo, de labels kloppen niet,
de titel is technisch waar de Product Owner meeleest, en de koppeling aan de groep ontbreekt omdat
`gh` daar geen commando voor heeft.

## 1. Bestaat het issue al?

```bash
gh issue list --repo MinBZK/MijnOverheidZakelijk --search "<trefwoord>" --state all --limit 20
```

Een bestaand issue aanvullen is beter dan een tweede aanmaken.

## 2. Schrijf de tekst

**Titel en inleiding zijn functioneel en niet-technisch.** De Product Owner leest mee en moet
aanleiding, effect voor gebruikers, wenselijk gedrag en acceptatiecriteria kunnen volgen zonder
Kotlin-, Quarkus- of Redis-kennis. Dus geen klasse-namen, geen `file:line`, geen framework-jargon
in het bovenste deel.

**Technische details horen in een aparte sectie verderop** — "Technische context" of
"Oplossingsrichtingen". Daar mogen code-locaties, klasse-namen, libraries en concrete
refactor-opties wél staan.

**Acceptatiecriteria formuleer je als gedrag**, voor de gebruiker of het systeem: latency-grenzen,
foutgedrag, beschikbaarheid. Niet als implementatie ("gebruik X-pattern").

Schrijf de body naar een bestand; dat scheelt quoting-ellende en je kunt hem opnieuw gebruiken als
`gh` struikelt.

## 3. Kies de groep

Alles hangt onder epic
[#238](https://github.com/MinBZK/MijnOverheidZakelijk/issues/238), en daarbinnen onder de groep
waar het werk bij hoort. Welke groepen er zijn, verandert: haal ze elke keer op in plaats van uit
een vaste lijst te kiezen. Een groep is een **open** sub-issue van #238 met het label `feature`.

```bash
gh api graphql -f query='{repository(owner:"MinBZK",name:"MijnOverheidZakelijk"){issue(number:238){subIssues(first:100){nodes{number title state body labels(first:10){nodes{name}}}}}}}' \
  --jq '.data.repository.issue.subIssues.nodes[]
        | select(.state == "OPEN" and any(.labels.nodes[]; .name == "feature"))
        | "#\(.number) \(.title)\n\(.body // "" | .[0:400])\n"'
```

Lees titel én het begin van de body: daar staat waarvoor de groep bedoeld is. Er is geen
standaardkeuze — kies de groep die de **aanleiding** raakt. Noemt de gebruiker zelf een groep,
dan geldt die (controleer wel dat het nummer in de lijst staat). Past het bij geen enkele groep,
hang het dan rechtstreeks onder #238 en meld dat bij het opleveren.

## 4. Maak het issue aan

Labels: `Lovelace` (teambord) + precies één van `Story` (wens of gedragsverandering) of `Taak`
(uitvoerend, ondersteunend werk) + `refine` (blijft staan tot de refinement hem eraf haalt).
`feature` is voorbehouden aan de groep-issues zelf; gebruik dat hier niet.

```bash
gh issue create --repo MinBZK/MijnOverheidZakelijk \
  --title "<functionele titel>" \
  --body-file <bestand> \
  --label Lovelace --label Story --label refine
```

Het issue komt vanzelf in project
[MijnOverheid Zakelijk (#40)](https://github.com/orgs/MinBZK/projects/40). De projectvelden
(Status, Priority, Size, Werkstroom) zet het team bij de refinement — laat die met rust.

## 5. Koppel aan de groep

Via de sub-issue-relatie, niet via een `> Onderdeel van #N.`-regel in de tekst. `gh` (2.46) kent
daar geen commando voor, dus via GraphQL:

```bash
issue_id() { gh api graphql -f query="{repository(owner:\"MinBZK\",name:\"MijnOverheidZakelijk\"){issue(number:$1){id}}}" --jq '.data.repository.issue.id'; }
gh api graphql -f query='mutation($p:ID!,$c:ID!){addSubIssue(input:{issueId:$p,subIssueId:$c}){subIssue{number}}}' \
  -f p="$(issue_id <groep>)" -f c="$(issue_id <n>)"
```

Controleer daarna dat de relatie er echt staat: de mutatie levert het sub-issue-nummer terug.

## Meerdere issues tegelijk

Levert de gebruiker een lijst (een artifact, een plan, een opsomming), werk dan per item stap 1
tot en met 5 af, met een eigen body-bestand per issue. Neem de inhoud van de bron over; verzin geen
acceptatiecriteria die er niet staan, maar formuleer wat er staat wel functioneel. Hebben de items
onderling een volgorde of afhankelijkheid, noem die dan in de body met het issuenummer
(`Na #<n>`), en maak ze daarom in die volgorde aan.

## 6. Meld het terug

Geef per issue het nummer, de gekozen groep en waaróm die groep. Ging er iets niet — een label dat niet
bestaat, een mutatie die faalde — zeg dat, in plaats van het stil over te slaan.

Hoort er een PR bij, dan is de sluitregel in de PR-body `Closes MinBZK/MijnOverheidZakelijk#<n>`,
mét `owner/repo`: een kaal `#<n>` wijst naar een PR in déze repo.
