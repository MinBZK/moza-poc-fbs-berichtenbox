# Beheer: dashboards en alertregels

Wat de berichtenketen als product meelevert om de diensten te bewaken. Een organisatie met een eigen
magazijn laadt deze bestanden op haar eigen voorziening.

| Pad | Wat |
|---|---|
| `dashboards/berichtenuitvraag.json` | Grafana-dashboard "Berichtenuitvraag" |
| `dashboards/berichtenmagazijn.json` | Grafana-dashboard "Berichtenmagazijn"; kies bij *Magazijn* welk magazijn je bekijkt (de OIN) |
| `dashboards/stelselregister.json` | Grafana-dashboard "Stelselregister" |
| `meldingen/<dienst>.yml` | Prometheus-alertregels per dienst (`rule_files`, of als Mimir/Grafana-regelgroep) |
| `meldingen/toetsen.yml` | De toetsen van die regels, voor `promtool test rules` |

Elk dashboard heeft verkeer, duur, fouten en beschikbaarheid, en daaronder de meetwaarden van het
domein: de publicatie en het wissen in het magazijn, de aanroepen van de magazijnen in de uitvraag,
de geldigheid van het stelseldocument en de sleutel in het stelselregister, en het logboek in
uitvraag en magazijn. Wat elke meetwaarde betekent: [`docs/operations/meetgegevens.md`](../docs/operations/meetgegevens.md).

## Aansluiten

- **De labels `dienst`, `omgeving` en `magazijn`** zet de dienst zelf op elke reeks (`Meetfilters`).
  Dashboards en regels kiezen daarop, dus ze werken met elke scrape-configuratie. `omgeving` is
  `DEPLOYMENT_NAME` (op ZAD `test` of `pr-<n>`); buiten ZAD zet je `FBS_MEETGEGEVENS_OMGEVING`.
  `magazijn` is de OIN van het magazijn, alleen op de reeksen van het magazijn.
- **Beschikbaarheid** leest het dashboard uit `process_uptime_seconds`: het aantal instanties dat nu
  meetgegevens levert, 0 als er geen enkele is. De regels melden op het ontbreken van meetgegevens:
  van de hele dienst (`absent`), van één omgeving of één magazijn dat de afgelopen zeven dagen wel
  leverde (`…Verdwenen`), en op een pod die steeds herstart (`…HerstartSteeds`). Een `…Verdwenen`
  die na zeven dagen vanzelf oplost, is geen herstel; een bewust opgeheven magazijn of omgeving, of
  een gewijzigde OIN, krijgt zolang een silence.
- **De scraper moet de beheerpoort bereiken**: de applicatiepoort met een `2` ervoor, pad
  `/q/metrics`. Eist de dienst daar een token, dan stuurt de scraper het mee.
- **Een datasource** kies je bij het openen (variabele *Bron*); de dashboards noemen geen vaste uid.
  De variabele *Omgeving* staat standaard op `test`; bestaat die niet, dan kiest Grafana de eerste.
- **Geen percentielen.** De diensten publiceren geen histogrammen, dus de duur is een gemiddelde en
  een maximum per scrape-interval.

## Meldingen

Elke regel heeft een `severity` (`critical` of `warning`, de gangbare waarden waarop
routeringsregels meestal kiezen), een `summary`, een `description` die zegt wat de beheerder moet
doen, en een `runbook_url` naar de sectie "Meldingen" van de operator-handleiding van de dienst.
Previews melden niet: elke selector heeft `omgeving!~"pr-.*"`. Elke regel behalve
`…LevertGeenMeetgegevens` telt per omgeving, en die van het magazijn ook per magazijn, zodat een
gezond magazijn een vastgelopen magazijn niet verbergt.

- **Uitgaande aanroepen tellen per `address`.** Staan alle magazijnen achter één FSC-outway, dan delen
  ze dat adres, en meldt `UitvraagMagazijnFaalt` pas als meer dan de helft van alle magazijnen erachter
  faalt. Een teller per magazijn (OIN) wacht op MinBZK/MijnOverheidZakelijk#1472.

- **Foutpercentage:** meer dan 5% 5xx over vijf minuten, vijf minuten lang. Een melding komt dus
  binnen tien minuten. Een 429 telt in teller noch noemer: begrenzing met een 429 is geen fout, en een
  vloed ervan mag een falende dienst niet verdunnen. Alleen de uitvraag geeft een 429
  (`te-veel-sessies`). Zijn plafonds per instantie (`te-veel-open-berichtenboxen`,
  `te-veel-gelijktijdige-downloads`) antwoorden met een 503 en tellen wél mee.
- **Herstel:** Prometheus stuurt een melding als `resolved` zodra de expressie niet meer waar is;
  het meldkanaal (Alertmanager, Grafana-contactpunt) bepaalt of die tweede melding verstuurd wordt.
  Zet `send_resolved: true`.
- **Drempels** staan in de regel zelf, met de reden in de `description` en hieronder. Een wijziging is
  een commit: herleidbaar en terug te draaien.

| Drempel | Waarom |
|---|---|
| Publicatie: oudste aanmelding > 15 min | Met de standaardinstellingen (vijf pogingen, backoff vanaf 1 s, een pollronde per minuut) is een retry-reeks binnen enkele minuten klaar; een `Retry-After` van de bestemming rekt hem op, tot hooguit een uur. Een kwartier is ruim langer dan een gewone storing |
| Telling verouderd > 10 min | De telling draait elke minuut; de eerste tien minuten na de start geldt nog geen oordeel |
| Wissen: oudste achterstand > 2 dagen | De cron draait dagelijks; twee rondes gemist is een stilstand, geen toeval |
| Wisronde > 36 uur geleden | Dezelfde cron plus een halve dag marge; de achterstand wordt alleen bij een ronde gemeten en bevriest dus als de cron stilvalt. De laatste ronde telt over drie dagen, zodat een herstart (die hem in het geheugen op 0 zet) hem niet wist |
| Drie herstarts in een halfuur | Een pod die steeds omvalt, levert tussendoor meetgegevens en ontloopt de regels op het ontbreken ervan; een uitrol met nieuwe pods telt niet |
| `…Verdwenen`: zeven dagen | Lang genoeg voor een weekend; daarna lost de melding vanzelf op |
| Stelseldocument < 1 uur geldig | Het document is 24 uur geldig en wordt ruim eerder vernieuwd |
| Sleutelketen < 30 dagen | Gelijk aan de waarschuwing in de log van de dienst |
| Sleutelketen < 7 dagen (critical) | De laatste week |
| Magazijn: > 50% van de aanroepen zonder antwoord of met een 5xx | Eén mislukte aanroep is geen uitval; de helft wel. Een 403, 404 of 410 is een gewoon antwoord |

## Toetsen

- **`BeheerbestandenTest` per dienst** leest het dashboard en de regels van die dienst, en eist:
  - dat elke genoemde meetwaarde met zijn labels en letterlijke labelwaarden in een echte scrape
    staat. Meters die pas bij het eerste geval ontstaan (een uitgaande aanroep, een afleverpoging),
    en waarden die in de test niet ontstaan, staan met de reden in een vrijstellingslijst. Een
    vrijstelling die niemand meer gebruikt, laat de toets ook falen;
  - dat elke meetwaarde als selector (`naam{...}`) genoemd wordt;
  - dat elke selector in de regels `dienst` en `omgeving!~"pr-.*"` kiest;
  - dat elke regel een `severity`, `summary`, `for` en een `runbook_url` naar de handleiding heeft.
- **`meldingen/toetsen.yml`** toetst het gedrag van elke regel met promtool:
  `.github/scripts/test-beheer-meldingen.sh`, in de CI via `ci-scripts`. Dat script eist dat elke
  regel minstens één toets heeft waarin hij afgaat, en dat een handvol mutaties op een kopie een
  toets laat falen.

## Wijzigen

Bewerk een dashboard gerust in Grafana, exporteer het (*Export → Save to file*, zonder "Export for
sharing externally") en vervang het bestand hier. Houd de variabelen `datasource`, `omgeving` en
`instance` (bij het magazijn `magazijn` in plaats van `instance`): de panelen filteren erop. De toets meldt een meetwaarde of
labelwaarde die de dienst niet levert.
