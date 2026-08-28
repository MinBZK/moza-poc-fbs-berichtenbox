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
externe stubs. De console is daar het component `democonsole` in de deployment `test` van het
magazijnen-project `mpfm-w3h`, en rolt mee naar elke preview.

<https://democonsole-test-mpfm-w3h.rig.prd1.gn2.quattro.rijksapps.nl> — inloggen met je
rijksaccount. Een aanvraag zonder sessie krijgt HTTP 403 met de inlogpagina terug; dat is de
authorization-wall, niet een kapot component.

Elke preview draagt zijn eigen console op `democonsole-pr-<n>-mpfm-w3h…`, met de tag van díe PR.
Een wijziging aan de demo is daar dus te beoordelen vóór hij samengevoegd wordt, inclusief de
Berichtenbox-weergave: de uitvraag laat elke console-origin van dit project toe.

`docs/plans/2026-08-26-demo-op-zad-design.md` legt de topologie uit en waarom de demo in `test`
woont en niet in een eigen deployment. `demo/environment/zad-demo/` bevat de eenmalige OM-stappen en
de verificatie erna.

Knopgroepen waarvan de backend er niet is, verbergt het paneel zelf op basis van
`GET /api/demo/omgeving`: een proxy waarvan de URL leeg is verdwijnt uit de lijst, en een
onbereikbare sessiecache haalt de cache-verval-knop weg. Op ZAD raakt dat de storingen, de
cache-verval-knop en de veel-magazijnen-schuif — alle drie vragen cluster-intern verkeer naar een
ánder project, en zo'n netwerkregel noemt daar altijd één vaste deployment, dus hij volgt geen
preview.

Drie dingen horen bij het wonen in `test`. De demo rolt mee met elke merge naar main, dus de
omgeving kan tijdens een presentatie herstarten. Previews klonen `test` en krijgen de console dus
mee. En de legen-knop op de console ín `test` wist de database van `test`, waar nieuwe previews van
klonen — die knop is onomkeerbaar. Op een preview raakt legen alleen die preview: elke deployment
heeft zijn eigen database.

## De knoppen

| Knop | Wat het doet |
|---|---|
| Herstel demo | Stroom stoppen, storingen resetten, legen, basisvulling — de knop aan het eind van een demo |
| Magazijnen legen | `TRUNCATE` op de berichten-, bijlage-, status- en outbox-tabellen van beide magazijnen, plus het logboek |
| Status | Aantal berichten per magazijn |
| Basisvulling laden | De vaste dataset uit `src/main/resources/dataset/basis.json` |
| Opvoeren | Een burst van *n* willekeurige berichten |
| Stroom starten / stoppen | Eén willekeurig bericht per interval; stopt vanzelf na 500 berichten of 60 minuten |
| Stroom-status | Loopt de stroom, met welk interval en hoeveel berichten al geleverd |
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
| `TOXIPROXY_<PROXY>_URL` | de waarde hierboven | Eén instantie apart; op ZAD staat elke stroom op een eigen adres. Leeg zetten schakelt die proxy uit — het paneel verbergt dan zelf de bijbehorende knop (bv. `TOXIPROXY_MAGAZIJN_A_URL=` op ZAD) |
| `UITVRAAG_BASIS` | leeg | Browser-zichtbaar adres van de uitvraag-API, **inclusief** het `/api/v1`-pad (bv. `https://uitvraag.example/api/v1`); leeg = afleiden uit de browser-locatie. `berichtenbox.js` gebruikt de waarde ongewijzigd als request-basis en de paginering strípt `/api/v1` uit de HAL-links op die aanname — zonder het pad faalt elke call zichtbaar voor de gebruiker (foutmelding in het paneel of een `alert`) |
| `UITVRAAG_URL` | `http://localhost:8086` | Adres dat de console zélf aanroept voor de ontdubbeling-webhook |
| `REDIS_HOSTS` | `redis://localhost:6379` | Cache-verval-knop |
| `SESSIECACHE_BEREIKBAAR` | `true` | Op `false` laat het paneel de cache-verval-knop weg. Voor omgevingen waar Redis niet bereikbaar is; een knop die gegarandeerd faalt kost tijdens een demo uitleg die niets toevoegt |
| `DEMO_MAGAZIJN_STUBS` | `12` | Aantal stub-magazijnen voor de veel-magazijnen-schuif |
| `MAGAZIJN_STUBS_ADMIN_URL` | `http://localhost:8092` | WireMock-admin van de stub-magazijnen, voor diezelfde schuif |
