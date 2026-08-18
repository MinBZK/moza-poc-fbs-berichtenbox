# PoC MOZa Berichtenbox

![Project Status](https://img.shields.io/badge/life_cycle-pre_alpha-red)
[![Test](https://github.com/MinBZK/moza-poc-fbs-berichtenbox/actions/workflows/test.yml/badge.svg)](https://github.com/MinBZK/moza-poc-fbs-berichtenbox/actions/workflows/test.yml)
[![detekt](https://github.com/MinBZK/moza-poc-fbs-berichtenbox/actions/workflows/detekt.yml/badge.svg)](https://github.com/MinBZK/moza-poc-fbs-berichtenbox/actions/workflows/detekt.yml)
[![CodeQL](https://github.com/MinBZK/moza-poc-fbs-berichtenbox/actions/workflows/codeql.yml/badge.svg)](https://github.com/MinBZK/moza-poc-fbs-berichtenbox/actions/workflows/codeql.yml)
![OpenSSF Scorecard](https://api.securityscorecards.dev/projects/github.com/MinBZK/moza-poc-fbs-berichtenbox/badge)

Proof of Concept Berichtenbox voor MijnOverheid Zakelijk (MOZa) binnen het Federatief Berichtenstelsel (FBS).

## Inleiding

Dit project is een Proof of Concept voor de Berichtenbox binnen het Federatief Berichtenstelsel,
beschreven op
<https://www.logius.nl/onze-dienstverlening/interactie/federatief-berichten-stelsel>.

## Doel

Dit Open Source project is opgezet als PoC voor het ontvangen, opslaan en ophalen van berichten
binnen MijnOverheid Zakelijk. Het stelsel is federatief: elke deelnemende organisatie houdt haar
eigen berichten in haar eigen magazijn, en de uitvraag haalt ze bij een sessie op en aggregeert ze
voor het portaal.

- **Berichtenmagazijn** — decentrale opslag per organisatie; ontvangt aangeleverde berichten
  (Aanlever-API) en levert ze uit aan de uitvraag.
- **Berichtenuitvraag** — frontend-API voor het portaal: bevraagt alle magazijnen van de ontvanger,
  streamt voortgang via SSE, bedient lijst, zoeken, detail en bijlagen, en neemt aanmeldingen
  van magazijnen aan.
- **Demo-console** — bedieningspaneel voor demonstraties (magazijnen legen, dataset laden,
  berichten opvoeren). Draait alleen mee in de demo-stack.

De uitvraag heeft geen losse berichtensessiecache-service meer: die is opgegaan in
`berichtenuitvraag` als in-process library, met Redis als gedeelde backing store.
De notificatievoorkeuren en toestemming van de ontvanger komen van een externe Profiel-service:
die wordt hier wel bevraagd (`libraries/fbs-common`, package `profiel`), maar niet gebouwd —
lokaal en op de testomgeving draait die, net als de notificatiedienst, als stub.

## Repostructuur

De belangrijkste paden:

| Pad                                   | Wat                                                                             |
|---------------------------------------|---------------------------------------------------------------------------------|
| `services/berichtenmagazijn/`         | Magazijn-service (PostgreSQL + Flyway, Aanlever-API)                             |
| `services/berichtenuitvraag/`         | Uitvraag-service (frontend-API, aggregatie, SSE)                                 |
| `services/demo-console/`              | Demo-bedieningspaneel                                                            |
| `libraries/fbs-common/`               | Gedeelde JAX-RS filters, exception mappers, identificatienummers (BSN/RSIN/KvK/OIN), Profiel-client |
| `libraries/fbs-magazijnregister/`     | Koppeling afzender-OIN ↔ magazijn (`Magazijnregister`-facade)                    |
| `libraries/fbs-berichtensessiecache/` | In-process sessiecache op Redis (`Sessiecache`-facade)                           |
| `bruno/`                              | Bruno-collecties met voorbeeldrequests per service                                |
| `demo/`                               | Demo-stack: stubgenerator, rookproef; `demo/environment/` bevat de FSC-federatieharness |
| `docs/`                               | Architectuur (C4/Structurizr), runbooks, plannen, verantwoording                  |
| `wiremock/`, `toxiproxy/`             | Stubs en fault-injection voor de lokale keten                                     |
| `compose.yaml`                        | Lokale infrastructuur en de volledige demo-stack (`--profile demo`)               |

## Vereisten

- Java 21+
- Maven 3.9+ (of gebruik de meegeleverde Maven wrapper `./mvnw`)
- Docker (voor lokale services: Redis, WireMock, PostgreSQL)
- Python 3 — alleen voor de demo-stack (genereert de magazijn-stubs)

## Snel starten

```bash
# Start lokale services (Redis, WireMock magazijnen, PostgreSQL)
docker compose up -d
```

De services draaien elk in hun eigen Quarkus-dev-mode. Start ze in **aparte terminals**
zodat elke instantie live-reload en de devconsole houdt:

```bash
# Terminal 1 — berichtenmagazijn (poort 8090)
./mvnw compile quarkus:dev -pl services/berichtenmagazijn -am

# Terminal 2 — berichtenuitvraag (poort 8086, bevat de in-process sessiecache)
./mvnw compile quarkus:dev -pl services/berichtenuitvraag -am
```

De `compile`-fase vóór `quarkus:dev` zorgt dat de gedeelde modules onder `libraries/`
(via `-am`) eerst gebouwd worden; zonder `compile` draait Maven alleen het `quarkus:dev`-goal
en faalt de resolution van bijvoorbeeld `fbs-common-<versie>.jar` zolang die niet in de
lokale Maven-repository staat.

| Service              | API                                              | OpenAPI                                 |
|----------------------|--------------------------------------------------|-----------------------------------------|
| berichtenmagazijn    | `http://localhost:8090/api/v1/berichten`         | `http://localhost:8090/openapi.json`    |
| berichtenuitvraag    | `http://localhost:8086/api/v1/berichten`         | `http://localhost:8086/openapi.json`    |

De uitvraag verwacht in dev óók een magazijn B op 8091. Draai je alleen A, dan meldt het ophalen
een gedeeltelijke storing — dat is correct gedrag, geen defect. Zie
[een tweede magazijn draaien](docs/ontwikkelen.md#een-tweede-magazijn-draaien).

## Demo-stack

Voor demonstraties draait de volledige keten in containers — images bouwen met jib, stubs
genereren, `docker compose --profile demo up -d`, en een bedieningspaneel op
<http://localhost:8095>. Het [demo-runbook](docs/demo-runbook.md) beschrijft de opzet, de
persona's, alle knoppen en de scenario's stap voor stap.

Zónder `--profile demo` start compose alleen de infrastructuur (Redis, de drie Postgres-instanties
en de WireMock-stubs voor de externe diensten). Gebruik die modus tijdens het ontwikkelen en draai
de services zelf met `quarkus:dev` zoals hierboven — in een container kost elke codewijziging een
image-build. Draai de twee modi niet tegelijk: dat geeft een poortconflict. Het runbook zet beide
modi naast elkaar in [§4](docs/demo-runbook.md).

## Ontwikkelen

[`docs/ontwikkelen.md`](docs/ontwikkelen.md) beschrijft het lokale werk: tests per module, de
kwaliteitsgates (JaCoCo, detekt), de OpenAPI-specs linten, een tweede magazijn draaien, de
Bruno-collecties en de configuratie van het magazijnregister.

## Architectuur en achtergrond

Het C4-model staat als Structurizr DSL in [`docs/architecture/`](docs/architecture/) en wordt
gepubliceerd op <https://minbzk.github.io/moza-poc-fbs-berichtenbox/>; die site wordt ververst
zodra er iets in `docs/architecture/` wijzigt (per PR ook als preview).

Verder lezen:

- [Aanpak en keuzes van de PoC](docs/aanpak-en-keuzes.md) — waarom federatief, welke standaarden
- [Demo-runbook](docs/demo-runbook.md) — de demo-stack en alle scenario's
- [Operator-handleiding magazijn](docs/operator-handleiding.md) — verplichte productie-overrides, LDV, outbox
- [Operator-handleiding uitvraag](docs/operator-handleiding-uitvraag.md) — sessiecache-TLS, timeout-invarianten, cache-TTL's
- [`docs/operations/`](docs/operations/) — runbooks per operationele procedure (alerts, schema-bumps)
- [Vergelijking VoRijk (Blauwe Knop) vs. FBS Berichtenbox](docs/vergelijking-fbs-vorijk.md)
- [Analyse: architectuur voor uniforme bronontsluiting](docs/analyse-architectuur-uniforme-bronontsluiting.md)
- [`docs/plans/`](docs/plans/) — implementatieplannen met de gemaakte ontwerpkeuzes

## Bijdragen

Wijzigingen gaan altijd via een feature branch en een Pull Request; er wordt niet direct naar
`main` gepusht. Een PR die code raakt draait tests met coverage-rapportage en detekt; wijzigt hij
alleen documentatie, dan slaat hij die over. Is `main` de doelbranch, dan komen daar CodeQL en een
eigen preview-omgeving op ZAD bij — een gestapelde PR op een andere branch wordt dus wél getoetst,
maar niet geanalyseerd of uitgerold. CodeQL draait daarbij bewust ook op documentatie-only PR's:
het analyseert de hele snapshot in plaats van de diff, en de OpenSSF-Scorecard telt een
overgeslagen analyse als een ongedekte PR. Zie [SUPPORT.md](SUPPORT.md) voor contact en
[GOVERNANCE.md](GOVERNANCE.md) voor besluitvorming.

## Licentie

Dit project is gelicenseerd onder de [EUPL-1.2](LICENSE).

## AI-verantwoording

De code in deze PoC is grotendeels gegenereerd met generatieve AI (Claude Code);
alle niet-testcode wordt menselijk gereviewd en testcode wordt functioneel
beproefd. Zie [DISCLAIMER.md](DISCLAIMER.md) voor de
disclaimer en [docs/ai-verantwoording.md](docs/ai-verantwoording.md) voor de
volledige verantwoording, getoetst aan de Overheidsbrede handreiking voor de
verantwoorde inzet van generatieve AI. In
[docs/ai-ervaringen.md](docs/ai-ervaringen.md) delen we onze praktische ervaringen
met het bouwen van deze PoC met AI.

## Ondersteuning

Zie [SUPPORT.md](SUPPORT.md) voor informatie over hoe en waar je hulp kunt krijgen.

## Governance

Zie [GOVERNANCE.md](GOVERNANCE.md) voor informatie over de governance-structuur van dit project.
