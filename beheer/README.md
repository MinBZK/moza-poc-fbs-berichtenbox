# Beheer: dashboards en alertregels

Wat de berichtenketen als product meelevert om de diensten te bewaken. Een organisatie met een eigen
magazijn laadt deze bestanden op haar eigen voorziening; de lokale demo-stack laadt ze ook.

| Pad | Wat |
|---|---|
| `dashboards/berichtenuitvraag.json` | Grafana-dashboard "Berichtenuitvraag" |
| `dashboards/berichtenmagazijn.json` | Grafana-dashboard "Berichtenmagazijn"; kies bij *Magazijn* welk magazijn je bekijkt |
| `dashboards/stelselregister.json` | Grafana-dashboard "Stelselregister" |
| `meldingen/<dienst>.yml` | Prometheus-alertregels per dienst (`rule_files`, of als Mimir/Grafana-regelgroep) |

Elk dashboard heeft verkeer, duur, fouten en beschikbaarheid, en daaronder de meetwaarden van het
domein: de publicatie en het wissen in het magazijn, de aanroepen van de magazijnen in de uitvraag,
de geldigheid van het stelseldocument en de sleutel in het stelselregister, en het logboek in
uitvraag en magazijn. Wat elke meetwaarde betekent: [`docs/operations/meetgegevens.md`](../docs/operations/meetgegevens.md).

## Aansluiten

- **De labels `dienst` en `omgeving`** zet de dienst zelf op elke reeks (`Meetfilters`). Dashboards
  en regels kiezen daarop, dus ze werken met elke scrape-configuratie. `omgeving` is
  `DEPLOYMENT_NAME` (op ZAD `test` of `pr-<n>`), anders `lokaal`.
- **Beschikbaarheid** leest het dashboard uit `process_uptime_seconds` (een instantie die meetgegevens
  levert) en uit `up{dienst=...}`. Die tweede heeft alleen waarde als de scrape-configuratie de
  target het label `dienst` geeft; de alertregels gebruiken hem daarom niet en melden op het
  ontbreken van meetgegevens (`absent(...)`).
- **De beheerpoort eist een bearer-token** (`METRICS_AUTH_TOKEN`); de scraper moet het meesturen.
  Zie "Wie mag lezen" in `meetgegevens.md`.
- **Een datasource** kies je bij het openen (variabele *Bron*); de dashboards noemen geen vaste uid.
- **Geen percentielen.** De diensten publiceren geen histogrammen, dus de duur is een gemiddelde en
  een maximum per scrape-interval.

## Meldingen

Elke regel heeft een `severity` (`waarschuwing` of `kritiek`), een `summary`, een `description` die
zegt wat de beheerder moet doen, en een `runbook_url` naar de sectie "Meldingen" van de
operator-handleiding van de dienst. Previews melden niet: elke expressie sluit `omgeving=~"pr-.*"`
uit.

- **Foutpercentage:** meer dan 5% 5xx over vijf minuten, vijf minuten lang. Een melding komt dus
  binnen tien minuten. Een 429 telt niet mee: een bovengrens tegen overbelasting is begrenzing.
- **Herstel:** Prometheus stuurt een melding als `resolved` zodra de expressie niet meer waar is;
  het meldkanaal (Alertmanager, Grafana-contactpunt) bepaalt of die tweede melding verstuurd wordt.
  Zet `send_resolved: true`.
- **Drempels** staan in de regel zelf, met de reden in de `description` en hieronder. Een wijziging is
  een commit: herleidbaar en terug te draaien.

| Drempel | Waarom |
|---|---|
| Publicatie: oudste aanmelding > 15 min | Een retry-reeks duurt minuten; een kwartier is ruim langer dan een gewone storing bij de bestemming |
| Telling verouderd > 10 min | De telling draait elke minuut |
| Wissen: oudste achterstand > 2 dagen | De cron draait dagelijks; twee rondes gemist is een stilstand, geen toeval |
| Stelseldocument < 1 uur geldig | Het document is 24 uur geldig en wordt ruim eerder vernieuwd |
| Sleutelketen < 30 dagen (en < 7 dagen kritiek) | Gelijk aan de waarschuwing in de log van de dienst |
| Magazijn: > 50% van de aanroepen mislukt | Eén mislukte aanroep is geen uitval; de helft wel |

## Toetsen

`BeheerbestandenTest` per dienst leest het dashboard en de regels van die dienst en eist dat elke
genoemde meetwaarde in een echte scrape staat. Meters die pas bij het eerste geval ontstaan (een
uitgaande aanroep, een afleverpoging) staan daar met de reden in een vrijstellingslijst, en een
vrijstelling die niemand meer gebruikt, laat de toets ook falen. De toets eist verder bij elke regel
een `severity`, `summary`, `for` en een `runbook_url` naar de handleiding.

## Wijzigen

Bewerk een dashboard gerust in Grafana, exporteer het (*Export → Save to file*, zonder "Export for
sharing externally") en vervang het bestand hier. Houd de variabelen `datasource`, `omgeving` en
`instance`; de toets meldt een meetwaarde die er niet is.
