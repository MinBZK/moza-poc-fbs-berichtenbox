# Demo-console

Bedieningspaneel voor de demo van het Federatief Berichtenstelsel: magazijnen legen en vullen,
storingen aanzetten, en de ondernemer-weergave van de Berichtenbox tonen. Wegwerpcode — geen
JaCoCo-gate, geen NL Design System, geen toegankelijkheidstraject.

## Lokaal starten

```bash
./mvnw clean package -DskipTests -pl demo/demo-console -am \
  -Dquarkus.container-image.build=true \
  -Dquarkus.container-image.group=fbs-demo -Dquarkus.container-image.tag=demo
docker compose --profile demo up -d
```

Open daarna <http://localhost:8095/> in de browser.

De eerste twee regels bouwen het image dat compose verwacht: `fbs-demo/fbs-demo-console:demo`.
Group en tag horen er allebei bij — zonder die twee `-D`-vlaggen krijgt jib een andere naam en tag
mee, en trekt `docker compose --profile demo up -d` daar niet in. Bouw je ook de twee services
opnieuw, volg dan `../../docs/demo-runbook.md`; die beschrijft de volledige stack inclusief Podman,
stub-generatie en de scenario's.

Vul altijd een lege omgeving: het magazijn kent eigen bericht-ID's toe, dus twee keer vullen zonder
legen levert het dubbele aantal berichten op.

## Op ZAD

De demo draait op de keten die al op ZAD staat: de bestaande uitvraag, de twee magazijnen en de
externe stubs in de deployment `test`. De console wordt daar één component bij, achter Keycloak-SSO;
inloggen met je rijksaccount, daarna zijn zowel het paneel als de Berichtenbox bereikbaar.

Dit is de beoogde situatie: het `democonsole`-component bestaat op het moment van schrijven nog niet
op ZAD. Zie `docs/plans/2026-08-26-demo-op-zad-design.md` voor de topologie en voor de reden waarom
de demo in `test` woont en niet in een eigen deployment.

Knopgroepen waarvan de backend er niet is, verbergt het paneel zelf op basis van
`GET /api/demo/omgeving` — een proxy waarvan de URL leeg is, verdwijnt uit de lijst en zijn knop
daarmee uit het paneel.

Drie dingen horen bij het wonen in `test`. De demo rolt mee met elke merge naar main, dus de
omgeving kan tijdens een presentatie herstarten. Previews klonen `test` en krijgen de console dus
mee. En de legen-knop wist de database van `test`, waar nieuwe previews van klonen — die knop is
onomkeerbaar.

## De knoppen

Vier tabbladen. Bovenaan een toestandsbalk die zichzelf bijwerkt — berichten, stroom, storingen en
actieve stub-magazijnen — zodat je niet naar de toestand hoeft te vragen, en een melding met de
uitkomst van je laatste actie. De knop die je indrukte houdt zelf even een ✓ of ✗ vast.

| Tabblad | Knop | Wat het doet |
|---|---|---|
| Demo | Herstel demo | Stroom stoppen, storingen resetten, legen, basisvulling — de knop aan het eind van een demo |
| Demo | Berichtenbox verversen | Herlaadt het frame met de proeftuin erin |
| Demo | Basisvulling laden | De vaste dataset uit `src/main/resources/dataset/basis.json` |
| Demo | Magazijnen legen | `TRUNCATE` op de berichten-, bijlage-, status- en outbox-tabellen van beide magazijnen, plus het logboek |
| Demo | Random berichten opvoeren | Een burst van *n* willekeurige berichten |
| Demo | Stroom starten / stoppen | Eén willekeurig bericht per interval; stopt vanzelf na 500 berichten of 60 minuten |
| Storingen | Traag / Uit per proxy | Zet een Toxiproxy traag of uit; "Alles normaal" herstelt elke instantie |
| Scenario's | Cache verlopen | Wist de sessiecache in Redis |
| Scenario's | Ongeldig bericht aanbieden, Tweemaal hetzelfde event sturen | Losse scenario's; zie het runbook |
| Scenario's | Veel magazijnen | Zet *k* van de *n* gegenereerde stub-magazijnen actief, of alle *n* weer aan; *n* ligt vast bij het draaien van `demo/genereer-magazijnen.py` |
| Info | Uitlezen | De losse `GET`-endpoints, met de ruwe JSON eronder |

Een refresh laat je staan waar je was: het paneel bewaart het actieve tabblad, de in-/uitgeklapte
stand en de invoervelden in `sessionStorage`. Sluit je het tabblad, dan is het weg — een volgende
demo begint schoon.

De opmaak staat los van de proeftuin: een eigen tokenlaag (`--bediening-*`) in `bediening.css`, met
de opbouw van NL Design System maar eigen waarden en een donkere chrome. Wie tijdens een demo
meekijkt, moet het paneel niet aanzien voor het product dat ernaast in het frame staat.

## Configuratie

Alles gaat via env-vars met een lokale default, zodat de module zonder omgeving start.

| Variabele | Default | Waarvoor |
|---|---|---|
| `MAGAZIJN_A_URL`, `MAGAZIJN_B_URL` | `http://localhost:8090`, `:8091` | Aanleveren |
| `MAGAZIJN_A_DB_URL`, `MAGAZIJN_B_DB_URL` | localhost:5432, :5433 | Legen |
| `MAGAZIJN_A_DB_SCHEMA`, `MAGAZIJN_B_DB_SCHEMA` | `public` | Schema per magazijn; op ZAD delen beide dezelfde database |
| `TOXIPROXY_ADMIN_URL` | `http://localhost:8474` | Alle Toxiproxy-instanties tegelijk |
| `TOXIPROXY_<PROXY>_URL` | de waarde hierboven | Eén instantie apart; op ZAD staat elke stroom op een eigen adres. Leeg zetten schakelt die proxy uit — het paneel verbergt dan zelf de bijbehorende knop (bv. `TOXIPROXY_MAGAZIJN_A_URL=` op ZAD) |
| `UITVRAAG_BASIS` | leeg | Browser-zichtbaar adres van de uitvraag-API, **inclusief** het `/api/v1`-pad (bv. `https://uitvraag.example/api/v1`); leeg = afleiden uit de browser-locatie. `berichtenbox.js` gebruikt de waarde ongewijzigd als request-basis en de paginering strípt `/api/v1` uit de HAL-links op die aanname — zonder het pad faalt elke call zichtbaar voor de gebruiker (foutmelding in het paneel of een `alert`) |
| `UITVRAAG_URL` | `http://localhost:8086` | Adres dat de console zélf aanroept voor de ontdubbeling-webhook |
| `REDIS_HOSTS` | `redis://localhost:6379` | Cache-verval-knop |
| `DEMO_MAGAZIJN_STUBS` | `12` | Aantal stub-magazijnen voor de veel-magazijnen-schuif |
| `MAGAZIJN_STUBS_ADMIN_URL` | `http://localhost:8092` | WireMock-admin van de stub-magazijnen, voor diezelfde schuif |
