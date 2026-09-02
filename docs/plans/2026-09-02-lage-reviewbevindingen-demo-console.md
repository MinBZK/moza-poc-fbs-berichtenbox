> **Status:** Uitgevoerd

# De lage reviewbevindingen op het bedieningspaneel

## Context

Sluitstuk na [de hoge](2026-09-02-hoge-reviewbevindingen-demo-console.md) en
[de medium](2026-09-02-medium-reviewbevindingen-demo-console.md) bevindingen. Wat hier overblijft is
kleiner maar niet vrijblijvend: twee plekken waar het paneel iets toont dat niet klopt met wat het
weet, één tak die niet uitgevoerd kan worden, en een reeks comments die uit elkaar aan het lopen is.

## De bevindingen en hun aanpak

| # | Bevinding | Aanpak |
|---|---|---|
| 21 | Het groene vinkje staat er vóórdat de samenvatting geïnterpreteerd is | Merkteken pas ná `samenvatting()`, en `let-op` krijgt een eigen teken |
| 22 | `letOp` reist in drie antwoorden mee maar wordt nergens getoond | Eigen regel onder de melding; server-zijde gepind, en de twee getallen in de tekst krijgen een bewaker |
| 23 | Onbereikbare `?: 0`-tak in de volgnummer-telling | Expliciet tellen in plaats van `merge` met een tak die niet kan vuren |
| 24 | Eén globale `bezig`-vlag voor alle knoppen | Teller in plaats van boolean |
| 25 | Drie README-passages beschrijven gedrag dat de code niet heeft | Al meegenomen bij de medium-ronde |
| 26 | Comment-duplicatie en kleinere onnauwkeurigheden | Rationale één keer voluit, elders een regel; vier losse correcties |

## Ontwerpkeuzes

**Het merkteken volgt de melding, niet de statuscode.** `samenvatting()` degradeert naar `let-op`
wanneer het antwoord een andere vorm heeft dan de formatter verwacht. Stond het vinkje er dan al, dan
zei de knop "gelukt" boven een melding die twijfel uitsprak. De knop is volgens zijn eigen
toelichting het antwoord op "heb ik hem nou ingedrukt?", dus hij hoort hetzelfde te zeggen als de
tekst ernaast.

**`letOp` wordt getoond in plaats van geschrapt.** De KDoc van `LegenAntwoord` zegt dat de melding in
het antwoord hoort "want het paneel toont de uitkomst van een knop, en dát is het moment waarop
iemand kijkt". Dat was niet waar: geen enkele formatter las het veld. Het alternatief — schrappen —
haalt uitleg weg die een demonstrateur juist nodig heeft op het moment dat de Berichtenbox nog
achterloopt. Dus renderen, op een eigen regel zodat de samenvatting kort blijft.

**De twee getallen in die tekst krijgen een bewaker, geen herstructurering.** `HERSTELTIJD_MELDING`
zegt "drie storingen" en "een halve minuut"; die staan als `drempel=3` en `open-seconds=30` in de
uitvraag, waar deze module geen afhankelijkheid op heeft. De tekst uit configuratie opbouwen zou een
user-facing zin afhankelijk maken van twee properties uit een andere service. Een test die faalt
zodra die waarden veranderen, wijst de schrijver naar de zin die dan bijgewerkt moet worden — dat is
hier genoeg.

**Een teller in plaats van een boolean.** Twee knoppen kunnen elkaar overlappen; de eerste die
terugkomt zette de vlag voor beide terug, waarna de poll een tussenstand las die daarna weer
versprong.

## Verificatie

- `./mvnw clean test -pl demo/demo-console -am`
- `./mvnw detekt:check -pl demo/demo-console`
