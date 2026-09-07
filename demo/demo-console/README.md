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
`GET /api/demo/omgeving`: een proxy waarvan de URL leeg is verdwijnt uit de lijst, een onbereikbare
sessiecache haalt de cache-verval-knop weg, en zonder simulator vallen de knoppen voor de
gesimuleerde magazijnen weg. De magazijn-storingen zelf hebben op ZAD geen proxy: daar staat naast de vier
bestaande Toxiproxy's geen vijfde en zesde voor A en B, dus hun `TOXIPROXY_MAGAZIJN_*_URL` blijven
leeg. Lokaal zijn die knoppen er wél.

De cache-verval-knop en de vier storingsknoppen wérken daar, ook op een preview. Ze vragen allemaal
cluster-intern verkeer naar een ander project, en zo'n netwerkregel noemt op ZAD altijd één vaste
deployment — daarom schrijven `deploy.yml` en `cleanup-preview.yml` ze per preview bij en weer weg.

De vier Toxiproxy's op ZAD dragen géén `proxies.json` — de KDoc van `ProxyBootstrap` legt uit waarom
een attachment daar niet werkt. De console maakt de proxies zelf aan via de admin-API en herhaalt dat
elke dertig seconden, want Toxiproxy houdt ze in het geheugen en verliest ze bij een herstart.
Lokaal gebeurt er niets: compose zet ze uit `toxiproxy/proxies.json`, die met dezelfde configuratie
overeenkomen, dus er valt niets aan te maken of te herbouwen.

Drie dingen horen bij het wonen in `test`. De demo rolt mee met elke merge naar main, dus de
omgeving kan tijdens een presentatie herstarten. Previews klonen `test` en krijgen de console dus
mee. En de legen-knop op de console ín `test` wist de database van `test`, waar nieuwe previews van
klonen — die knop is onomkeerbaar. Op een preview raakt legen alleen die preview: elke deployment
heeft zijn eigen database.

## De knoppen

Vier tabbladen. Bovenaan een toestandsbalk die zichzelf bijwerkt — berichten, stroom, storingen en
gesimuleerde magazijnen zonder storing — zodat je niet naar de toestand hoeft te vragen, en een melding met de
uitkomst van je laatste actie. De knop die je indrukte houdt zelf even een ✓ of ✗ vast.

| Tabblad | Knop | Wat het doet |
|---|---|---|
| Demo | Herstel demo | Stroom stoppen, storingen resetten, legen, basisvulling — de knop aan het eind van een demo. De gesimuleerde magazijnen gaan als laatste mee en krijgen daarna hun standaardvulling terug; zijn ze er niet of antwoorden ze niet, dan meldt de knop dat als overgeslagen in plaats van het hele herstel te laten mislukken |
| Demo | Berichtenbox verversen | Herlaadt het frame met de proeftuin erin |
| Demo | Basisvulling laden | De vaste dataset uit `src/main/resources/dataset/basis.json`: berichten in de twee echte magazijnen, voor elke persona die daar in de personadienst een `magazijnen`-regel voor heeft. Deze knop raakt de gesimuleerde magazijnen niet — die vult *Herstel demo* |
| Demo | Magazijnen legen | `TRUNCATE` op de berichten-, bijlage-, status- en outbox-tabellen van beide magazijnen, plus het logboek. De gesimuleerde magazijnen gaan als deelstap mee; zijn ze er niet of antwoorden ze niet, dan meldt de knop dat als overgeslagen |
| Demo | Random berichten opvoeren | Een burst van *n* willekeurige berichten, 1 tot 500 |
| Demo | Bericht plaatsen | *n* berichten (1 tot 100) voor de persona uit de keuzelijst; het magazijn is een willekeurige van de magazijnen waar die persona berichten van ontvangt. De keuzelijst komt uit `berichtPersonas` van `GET /api/demo/omgeving` en bevat alleen persona's mét magazijn |
| Demo | Stroom starten / stoppen | Eén willekeurig bericht per interval van 1 tot 3600 seconden; stopt vanzelf na 500 berichten of 60 minuten |
| Storingen | Traag (alleen magazijn A/B) / Uit per proxy | Zet een Toxiproxy traag of uit; "Alles normaal" herstelt elke instantie en meldt pas succes nadat het teruggelezen heeft dat alles normaal staat |
| Scenario's | Cache verlopen | Wist de sessiecache in Redis |
| Scenario's | Ongeldig bericht aanbieden, Tweemaal hetzelfde event sturen | Losse scenario's; zie het runbook |
| Scenario's | Gesimuleerde magazijnen | Zet *k* van de *n* zonder storing, zet berichten klaar, en leegt alles inclusief het gedrag; *n* vraagt de console aan de simulator zelf |
| Info | Gesimuleerde magazijnen | Toont hoe elk gesimuleerd magazijn zich gedraagt |
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
| `BERICHTENBOX_URL` | leeg | Browser-zichtbaar adres van de berichtenbox, voor het frame in het paneel. Leeg = het eigen pad `/moza/berichtenbox/`, dat lokaal achter de demo-proxy klopt; op een gedeelde omgeving de volledige URL van het proeftuin-component. Een geconfigureerd adres wordt niet vooraf getoetst: een HEAD naar een ander component strandt op CORS en dat is niet van onbereikbaar te onderscheiden |
| `UITVRAAG_URL` | `http://localhost:8086` | Adres dat de console zélf aanroept voor de ontdubbeling-webhook |
| `TOXIPROXY_<PROXY>_LISTEN`, `TOXIPROXY_<PROXY>_UPSTREAM` | de waarden uit `toxiproxy/proxies.json` | Waar de proxy luistert en naartoe stuurt; hiermee maakt de console hem aan. Op ZAD komt de upstream uit een alias, zodat `$DEPLOYMENT_NAME` de proxy naar de upstream van dezelfde deployment wijst |
| `TOXIPROXY_RECONCILE_INTERVAL` | `30s` | Hoe vaak de console controleert of de proxies er nog zijn |
| `REDIS_HOSTS` | `redis://localhost:6379` | Cache-verval-knop. Wijst bewust rechtstreeks op Redis en niet door de proxy: het is een beheeractie, die moet blijven werken terwijl je de Redis-stroom uitzet |
| `REDIS_PASSWORD` | leeg | Wachtwoord van diezelfde Redis. Leeg = geen AUTH, wat lokaal klopt; op een gedeelde omgeving vereist, anders geeft de knop `NOAUTH Authentication required` |
| `SESSIECACHE_BEREIKBAAR` | `true` | Op `false` laat het paneel de cache-verval-knop weg. Voor omgevingen waar Redis niet bereikbaar is; een knop die gegarandeerd faalt kost tijdens een demo uitleg die niets toevoegt |
| `MAGAZIJN_SIMULATOR_URL` | `http://localhost:8092` | Beheerpad van de magazijn-simulator: vullen, legen en gedrag bijstellen |
| `MAGAZIJN_SIMULATOR_BEHEER_TOKEN` | leeg | Token voor dat beheerpad. Leeg lokaal — dan blijft de header helemaal weg; op een gedeelde omgeving verplicht, anders geeft elke knop een 401 |
| `SIMULATOR_BEREIKBAAR` | `true` | Op `false` laat het paneel de knoppen en de chip voor de gesimuleerde magazijnen weg |
