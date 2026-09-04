# Reden bij een mislukte aanlevering in het demo-paneel

**Status:** Uitgevoerd

Issue: [MinBZK/MijnOverheidZakelijk#1067](https://github.com/MinBZK/MijnOverheidZakelijk/issues/1067)

## Context

Mislukt een aanlevering vanuit het bedieningspaneel, dan meldt het paneel alleen een aantal:
*"0 van 1 berichten aangeleverd, 1 mislukt"*. De reden staat uitsluitend in de logs van de
draaiende dienst — tijdens een demonstratie onbereikbaar.

Vier oorzaken vallen daar samen en vragen om verschillende reacties:

| Oorzaak | Wat de bediener moet doen |
|---|---|
| Geen magazijn-URL voor de afzender-OIN | de omgeving is niet compleet ingericht |
| `ProcessingException` (magazijn onbereikbaar) | zet de storing weer uit |
| HTTP 403 uit de deelnemerscontrole | die ondernemer staat daar niet geregistreerd |
| HTTP 400 op de validatie | het bericht zelf deugt niet |
| Elke andere status | doorgeven, met de status erbij |

Het kanaal om dit te tonen ligt klaar en is ongebruikt: `bediening.js` heeft `letOp(body)` en toont
dat op een eigen regel (`melding__letop` in `index.html`).

## Basis

Op `main`, niet gestapeld op [#280](https://github.com/MinBZK/moza-poc-fbs-berichtenbox/pull/280).
`AanleverService`, de vier faalmodi en het `letOp`-kanaal staan alle drie al op `main`; het adres
`POST /api/demo/bericht` uit #280 geeft dezelfde `AanleverResultaat` terug en erft de reden dus
zodra die PR merget. Stapelen zou alleen de preview-opruiming compliceren.

## Stappen

1. **`aanlever/Faalreden.kt` (nieuw)** — pure vertaling van een faalmodus naar één leesbare zin, en
   de samenvatting van een lijst redenen tot één regel:
   - één onderscheiden reden → `Reden: <zin>.`
   - meerdere → `Meest voorkomende reden (<n> van de <m>): <zin>.`

   Zo blijft de melding bij honderd berichten één regel. De winnaar is de eerst-aangetroffen van de
   meest voorkomende, dus deterministisch bij gelijke stand.

2. **`AanleverResultaat` krijgt `letOp: String?`** (default `null`), gevuld door `AanleverService`.
   Naam gelijk aan het bestaande veld op `HerstelResultaat`/`LegenAntwoord`, want `bediening.js`
   leest precies die sleutel.

3. **`AanleverService`** verzamelt per opdracht een reden in plaats van alleen `mislukt++`. `lever`
   geeft geen `String?` meer terug maar een `Aanlevering` (gelukt met berichtId, of mislukt met
   reden). De bestaande logregels blijven staan: het paneel toont de korte zin, het log de details.

4. **`MagazijnClients` (nieuw)** neemt het bouwen van de REST-clients uit `AanleverService` over.
   Dat was de enige stap daar die een draaiende omgeving nodig had; met die bedrading apart zijn de
   faalmodi met vaste dubbels te testen, zonder magazijn en zonder Docker.

5. **`HerstelResultaat.letOp` wordt afgeleid** in plaats van een vaste tekst: eerst de reden van de
   vulling, dan de hersteltijd-melding die deze knop altijd draagt. Het paneel toont één
   let-op-regel, dus zonder dit verdwijnt de reden achter die zin. Server-side en niet in
   `bediening.js`, want daar valt het niet te testen.

## Wat er niet in gaat

- `markeringMislukt` houdt geen reden. Dat telt als geslaagd — het bericht staat in het magazijn —
  en het issue gaat over berichten die niet aankwamen.
- Geen consistentietest bijgebouwd: `DemoDatasetConsistentieTest` houdt de magazijn-lijst per
  persona al tegen de profielstubs, precies de scheefgroei die het issue noemt.

## Verificatie

- `./mvnw clean test -pl demo/demo-console -am`
- Nieuwe tests: `FaalredenTest` (pure, alle faalmodi en de samenvattings-cardinaliteiten leeg/één/
  meerdere/gelijkspel) en `AanleverServiceTest` (de faalmodi door de echte service heen, plus de
  grens met `markeringMislukt`). `HerstelServiceTest` krijgt de twee regels van de herstelknop.
- Geen identificatienummer in de melding: de reden krijgt alleen de organisatie-OIN mee, nooit de
  ontvanger. Een test pint dat.
