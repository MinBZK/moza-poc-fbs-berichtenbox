# De demo-console op ZAD

De demo draait op de keten die al op ZAD staat: de bestaande uitvraag, de twee magazijnen en de
externe stubs. Er komt één component bij, `democonsole` in het magazijnen-project `mpfm-w3h`. Dit
bestand beschrijft de **eenmalige creatie** van dat component; de doorlopende image-tag-updates
lopen daarna via `deploy-test-magazijnen` en `deploy-preview-magazijnen` in
`.github/workflows/deploy.yml`.

Waarom de console in `mpfm-w3h` woont en niet in een eigen deployment staat in
`docs/plans/2026-08-26-demo-op-zad-design.md`. Kort: `postgresql-database` is deployment-gebonden,
dus alleen een component ín dezelfde deployment als de magazijnen erft hun database-secret — en dat
secret is wat de legen-knop mogelijk maakt.

## Waarom dit handwerk is

ZAD past component-configuratie — `aliases`, `user-env-vars`, poorten, diensten — alleen toe bij
**creatie**, niet bij een re-POST op een bestaand component. Het component moet dus in één keer
compleet worden aangemaakt. Daarna draagt de deploy-workflow alleen nog `name` + `image`.

Wijzigt er later een alias, dan is verwijderen-en-opnieuw-aanmaken de enige route. Het component
draagt geen attachments, dus daar raak je niets mee kwijt.

## Vooraf

```bash
zadctl login                 # SSO; de ZAD_API_KEY_*-secrets zijn niet lokaal leesbaar
zadctl project use mpfm-w3h  # schrijft .env.zadctl (0600, gitignored) in de werkmap
```

Draai de rest van dit runbook vanuit deze map, zodat `.env.zadctl` gevonden wordt. Zet `--dry-run`
achter elk commando om eerst de request te zien zonder iets te versturen.

## 1. Keycloak en de authorization-wall

`authorization-wall` zet een oauth2-proxy-sidecar vóór het component; bezoekers loggen in met hun
rijksaccount. De dienst noemt zelf drie voorwaarden (`zadctl service describe authorization-wall`):
`publish-on-web`, `keycloak`, en een `restrict-access`-blok op die Keycloak. De eerste twee worden
volgens de beschrijving automatisch meegeselecteerd; selecteer ze desnoods expliciet:

```bash
zadctl service add keycloak
zadctl service add authorization-wall
```

`restrict-access` is de enige inhoudelijke keuze: laat je iedereen met een rijksaccount binnen, of
alleen wie een rol draagt? Voor een demo die stakeholders zelf moeten kunnen openen is het eerste
het uitgangspunt:

```bash
zadctl service config set keycloak --set 'restrict-access.enabled=false'
```

Wil je het wél beperken, zet dan `restrict-access.enabled=true` met een `restrict-access.realm-role`
en deel die rol uit in Keycloak.

## 2. Het component aanmaken

De aliassen hieronder gebruiken `$DEPLOYMENT_NAME` en de platformvariabelen van de
`postgresql-database`-dienst. Enkele aanhalingstekens zijn nodig: de shell mag de `$` niet
uitbreiden, ZAD doet dat bij het uitrollen.

`UITVRAAG_BASIS` en `UITVRAAG_URL` zijn met opzet twee sleutels. De eerste belandt in een browser
en moet publiek zijn, **inclusief** het `/api/v1`-pad; de tweede roept de console zelf server-side
aan voor de ontdubbeling-webhook. Op ZAD delen ze hun host — de uitvraag staat in een ánder project,
dus cluster-interne DNS is er niet zonder netwerkregel, en die noemt een vaste deployment en volgt
dus geen preview. Over de publieke ingress klopt het adres in elke deployment.

```bash
zadctl component add democonsole \
  --image ghcr.io/minbzk/fbs-demo-console:main-<sha7> \
  --deployment test \
  --port 8095 \
  --service postgresql-database \
  --service publish-on-web \
  --service authorization-wall \
  --aliases '
MAGAZIJN_A_URL: http://$DEPLOYMENT_NAME-magazijna:8090
MAGAZIJN_B_URL: http://$DEPLOYMENT_NAME-magazijnb:8090
MAGAZIJN_A_DB_URL: jdbc:postgresql://$DATABASE_SERVER_HOST:5432/$DATABASE_DB
MAGAZIJN_A_DB_USER: $DATABASE_SERVER_USER
MAGAZIJN_A_DB_PASSWORD: $DATABASE_PASSWORD
MAGAZIJN_B_DB_URL: jdbc:postgresql://$DATABASE_SERVER_HOST:5432/$DATABASE_DB
MAGAZIJN_B_DB_USER: $DATABASE_SERVER_USER
MAGAZIJN_B_DB_PASSWORD: $DATABASE_PASSWORD
UITVRAAG_BASIS: https://uitvraag-$DEPLOYMENT_NAME-mpfb-8wh.rig.prd1.gn2.quattro.rijksapps.nl/api/v1
UITVRAAG_URL: https://uitvraag-$DEPLOYMENT_NAME-mpfb-8wh.rig.prd1.gn2.quattro.rijksapps.nl
'
```

Kies een tag die echt bestaat: `deploy.yml` pusht `main-<sha7>` en `pr-<n>-<sha7>`, nooit een kale
`:main`. De eerstvolgende merge naar main werkt hem alsnog bij.

**Bind daarna `keycloak` óók aan het component.** `authorization-wall` noemt `keycloak` bij zijn
voorwaarden, maar `--service authorization-wall` trekt de binding op het component niet mee: de
dienst wordt wél op projectniveau geselecteerd, en daar blijft het bij. Zonder deze stap rendert
er geen oauth2-proxy-sidecar en **staat het paneel open op het internet**, met de legen-knop erop.

```bash
zadctl service assign keycloak -c democonsole
```

Controleer het meteen, en niet pas bij de verificatie:

```bash
curl -s -o /dev/null -w '%{http_code}\n' https://democonsole-test-mpfm-w3h.rig.prd1.gn2.quattro.rijksapps.nl/
```

`403` is goed: dat is de muur. `200` betekent dat hij er niet staat — bind dan `keycloak` alsnog, of
haal `publish-on-web` van het component tot het klopt.

## 3. De omgevingsvariabelen die geen alias kunnen zijn

```bash
zadctl env add -c democonsole \
  MAGAZIJN_A_DB_SCHEMA=<schema van magazijna> \
  MAGAZIJN_B_DB_SCHEMA=<schema van magazijnb> \
  SESSIECACHE_BEREIKBAAR=false \
  TOXIPROXY_PROFIEL_URL= \
  TOXIPROXY_NOTIFICATIE_URL= \
  TOXIPROXY_AANMELD_URL= \
  TOXIPROXY_REDIS_URL= \
  TOXIPROXY_MAGAZIJN_A_URL= \
  TOXIPROXY_MAGAZIJN_B_URL=
```

**De twee schema's moeten exact gelijk zijn aan de `DB_SCHEMA` van `magazijna` respectievelijk
`magazijnb` in dezelfde deployment.** Lees ze af met `zadctl env list -c magazijna`. Wijken ze af,
dan leegt de console een leeg schema en meldt nul verwijderde berichten zónder te klagen — stap 4
van `verify-zad.md` is er om dat te vangen.

De zes lege `TOXIPROXY_*`-waarden schakelen de storingsknoppen uit: het paneel leest
`GET /api/demo/omgeving` en laat een knop weg zodra zijn proxy niet geconfigureerd is. Er staat op
ZAD geen Toxiproxy; waarom niet, staat onderaan bij "Wat er bewust niet meekomt".
`SESSIECACHE_BEREIKBAAR=false` doet hetzelfde voor de cache-verval-knop.

## 4. CORS op de uitvraag

De Berichtenbox-pagina wordt vanaf de console geserveerd en roept de uitvraag cross-origin aan. In
het uitvraag-project:

Elke deployment heeft een eigen console-origin (`democonsole-test-…`, `democonsole-pr-<n>-…`), en
previews komen en gaan. Eén regex dekt ze allemaal; Quarkus leest een waarde tussen schuine strepen
als reguliere expressie.

```bash
zadctl -p mpfb-8wh env add -c uitvraag \
  QUARKUS_HTTP_CORS_ENABLED=true \
  'QUARKUS_HTTP_CORS_ORIGINS=/https://democonsole-.+-mpfm-w3h.rig.prd1.gn2.quattro.rijksapps.nl/'
```

**Geen backslashes in die waarde.** Een geëscapete variant
(`https:\/\/democonsole-…\.rig\.…`) laat de SOPS-stap van Operations Manager falen op
`uitvraag-user-secret.to-sops.yaml`, en dan blokkeert élke uitrol van dit project tot de waarde
terug is. De ongeëscapete punten matchen breder dan strikt nodig, maar alleen binnen een domein dat
we zelf beheren.

Methods en headers blijven ongezet; Quarkus spiegelt die uit het preflight-verzoek, zoals compose
het lokaal ook doet. De property heet `quarkus.http.cors.enabled` en niet `quarkus.http.cors`.

Controleer het met een preflight, en toets meteen dat het géén wildcard werd:

```bash
for o in https://democonsole-test-mpfm-w3h.rig.prd1.gn2.quattro.rijksapps.nl https://kwaadaardig.example; do
  curl -s -o /dev/null -D - -X OPTIONS -H "Origin: $o" -H 'Access-Control-Request-Method: GET' \
    https://uitvraag-test-mpfb-8wh.rig.prd1.gn2.quattro.rijksapps.nl/api/v1/berichten |
    grep -i access-control-allow-origin || echo "geweigerd: $o"
done
```

## 5. Uitrollen en verifiëren

```bash
zadctl deployment refresh test
zadctl deployment url test -c democonsole
```

Open die URL in een browser en log in. **Met `curl` lijkt het component stuk:** de
authorization-wall antwoordt een niet-ingelogde aanvraag met HTTP 403 en de inlogpagina in de body,
niet met een 302. Achter deze muur is 403 het teken dát de muur staat.

Loop daarna `verify-zad.md` af. Sla stap 4 daar niet over: dat is de enige controle die een verkeerd
schema aanwijst.

## Wat er bewust niet meekomt

**De storingsknoppen (Toxiproxy).** Drie eigenschappen van ZAD maken de opzet uit het ontwerp
onbruikbaar zodra previews meetellen:

- De inhoud van een attachment wordt ongewijzigd gemount; er is geen `$DEPLOYMENT_NAME`-substitutie.
  Een `proxies.json` moet dus een vaste upstream noemen, en die is in een preview de verkeerde.
- Het `command` dat Toxiproxy naar dat bestand wijst, staat niet in `AddComponentRequest` of
  `UpdateComponentRequest` en kent `zadctl` niet: het is UI-handwerk dat elke hercreatie overleeft
  noch meeklont.
- Een `cross-domain-access`-regel noemt altijd één concrete peer-deployment; een regel waarvan die
  open blijft, wordt bij het genereren overgeslagen. De console kan de admin-API's in een preview
  dus niet bereiken.

Het gevolg zonder maatregelen is de slechtste soort: een preview zou zijn keten-verkeer stilzwijgend
door de proxy van `test` sturen. Zie het vervolgontwerp voor de route die dat wél oplost — de console
maakt de proxies zelf aan via de admin-API, en de netwerkregels komen per preview mee uit de
deploy-workflow.

**De cache-verval-knop.** Dezelfde netwerkregel-beperking: Redis staat in `mpfb-8wh`. Het paneel
verbergt de knop op grond van `SESSIECACHE_BEREIKBAAR=false`.

**De veel-magazijnen-schuif.** Wacht op de magazijn-simulator (#938).

## Wat het wonen in `test` met zich meebrengt

- De demo rolt mee met elke merge naar main, dus de omgeving kan tijdens een presentatie herstarten.
- De legen-knop op de console ín `test` wist de database van `test`, waar nieuwe previews van
  klonen. Op een preview raakt legen alleen die preview: elke deployment heeft zijn eigen database.
- Elke openstaande PR draagt de console mee, ongeveer 250 Mi.
