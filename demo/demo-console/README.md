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

De gedeelde demo staat op `https://democonsole-demo-mpfm-w3h.rig.prd1.gn2.quattro.rijksapps.nl`,
achter Keycloak-SSO. Inloggen met je rijksaccount; daarna zijn zowel het paneel als de Berichtenbox
bereikbaar.

Eén knopgroep ontbreekt daar bewust, omdat de magazijnen hun gedrag in een volgende fase uit de
simulator krijgen: de storingsknoppen op magazijn A en B. Het paneel verbergt ze zelf op basis van
`GET /api/demo/omgeving`.

Uitrollen gaat met de hand via de workflow `deploy-demo.yml` (`workflow_dispatch`), niet automatisch
bij een merge — een demo-omgeving die halverwege een presentatie herstart is geen demo-omgeving.

## De knoppen

| Knop | Wat het doet |
|---|---|
| Herstel demo | Stroom stoppen, storingen resetten, legen, basisvulling — de knop aan het eind van een demo |
| Magazijnen legen | `TRUNCATE` op de berichten-, bijlage-, status- en outbox-tabellen van beide magazijnen, plus het logboek |
| Status | Aantal berichten per magazijn |
| Basisvulling laden | De vaste dataset uit `src/main/resources/dataset/basis.json` |
| Opvoeren | Een burst van *n* willekeurige berichten |
| Stroom starten / stoppen | Eén willekeurig bericht per interval; stopt vanzelf na 500 berichten of 60 minuten |
| Storingen | Zet een Toxiproxy traag of uit; "Alles normaal" herstelt elke instantie |
| Cache verlopen | Wist de sessiecache in Redis |
| Foutieve aanlevering, Ontdubbeling | Losse scenario's; zie het runbook |
| Veel magazijnen | Zet *k* van de *n* gegenereerde stub-magazijnen actief, of alle *n* weer aan; *n* ligt vast bij het draaien van `demo/genereer-magazijnen.py` |

## Configuratie

Alles gaat via env-vars met een lokale default, zodat de module zonder omgeving start.

| Variabele | Default | Waarvoor |
|---|---|---|
| `MAGAZIJN_A_URL`, `MAGAZIJN_B_URL` | `http://localhost:8090`, `:8091` | Aanleveren |
| `MAGAZIJN_A_DB_URL`, `MAGAZIJN_B_DB_URL` | localhost:5432, :5433 | Legen |
| `MAGAZIJN_A_DB_SCHEMA`, `MAGAZIJN_B_DB_SCHEMA` | `public` | Schema per magazijn; op ZAD delen beide dezelfde database |
| `TOXIPROXY_ADMIN_URL` | `http://localhost:8474` | Alle Toxiproxy-instanties tegelijk |
| `TOXIPROXY_<PROXY>_URL` | de waarde hierboven | Eén instantie apart; op ZAD staat elke stroom op een eigen adres |
| `UITVRAAG_BASIS` | leeg | Browser-zichtbaar adres van de uitvraag-API; leeg = afleiden uit de browser-locatie |
| `UITVRAAG_URL` | `http://localhost:8086` | Adres dat de console zélf aanroept voor de ontdubbeling-webhook |
| `REDIS_HOSTS` | `redis://localhost:6379` | Cache-verval-knop |
| `DEMO_MAGAZIJN_STUBS` | `12` | Aantal stub-magazijnen voor de veel-magazijnen-schuif |
| `MAGAZIJN_STUBS_ADMIN_URL` | `http://localhost:8092` | WireMock-admin van de stub-magazijnen, voor diezelfde schuif |
