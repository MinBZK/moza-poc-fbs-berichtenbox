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
`GET /api/demo/omgeving` en laat een knop weg zodra zijn proxy niet geconfigureerd is. Stap 6 vult er
vier van in zodra de Toxiproxy-componenten staan; de twee magazijn-proxies blijven leeg, want hun
storingsgedrag komt uit de magazijn-simulator (#938). `SESSIECACHE_BEREIKBAAR=false` doet hetzelfde
voor de cache-verval-knop; stap 5 zet hem aan zodra het verkeer naar de sessiecache openstaat.

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

## 5. De sessiecache openzetten

De cache-verval-knop wist de sessies in Redis, en Redis staat in `mpfb-8wh` terwijl de console in
`mpfm-w3h` woont. Cluster-intern verkeer tussen projecten bestaat alleen met een
`cross-domain-access`-regel, en zo'n regel noemt altijd één concrete peer-deployment — blijft die
open, dan slaat Operations Manager hem bij het genereren over.

Daarom: de regel zelf één keer op projectniveau **zonder** peer-deployment, en per deployment een
patch die hem invult. Voor `test` doe je dat hier met de hand; voor previews doen `deploy.yml` en
`cleanup-preview.yml` het met `.github/scripts/cross-domain-preview.sh`.

De ontvanger beslist, dus beide projecten dragen een regel — één kant alleen zet niets open.

```bash
API=https://operations-manager.rig.prd1.gn2.quattro.rijksapps.nl/api

# mpfb-8wh: hier staat Redis, dus hier hoort de inbound-regel.
curl -s -X PATCH -H "X-API-Key: $SLEUTEL_UITVRAAG" -H 'Content-Type: application/json' \
  "$API/v2/projects/mpfb-8wh/services/cross-domain-access/config/project/inbound" \
  -d '{"add":[{"name":"democonsole-naar-redis","to":{"component":"redis","port":6379},"from":{"project":"mpfm-w3h","component":"democonsole"}}]}'

# mpfm-w3h: hier staat de console, dus hier hoort de outbound-regel.
curl -s -X PATCH -H "X-API-Key: $SLEUTEL_MAGAZIJNEN" -H 'Content-Type: application/json' \
  "$API/v2/projects/mpfm-w3h/services/cross-domain-access/config/project/outbound" \
  -d '{"add":[{"name":"democonsole-naar-redis","from":{"component":"democonsole"},"to":{"project":"mpfb-8wh","component":"redis","port":6379}}]}'
```

`zadctl service config set cross-domain-access` kan dit niet: dat is een PUT over de héle
configuratie en zou de bestaande regels overschrijven. De `PATCH …/inbound` en `…/outbound`
hierboven zijn add/remove per regelnaam.

Die twee projectregels moeten er staan vóór een deployment ze invult. Operations Manager laat het
genereren namelijk nooit falen op een kapotte regel: een deployment-patch zonder projectregel wordt
een regel op zichzelf, mist component en poort, en verdwijnt met een waarschuwing in de log. De API
accepteert de patch wel. Het script hieronder controleert daarom eerst of de projectregel bestaat —
anders zou het groen melden over een netwerkregel die er nooit komt.

Daarna de invulling voor `test`, met hetzelfde script dat CI voor previews gebruikt:

```bash
ZAD_API_KEY=$SLEUTEL_UITVRAAG   .github/scripts/cross-domain-preview.sh zet mpfb-8wh test inbound  democonsole-naar-redis
ZAD_API_KEY=$SLEUTEL_MAGAZIJNEN .github/scripts/cross-domain-preview.sh zet mpfm-w3h test outbound democonsole-naar-redis
```

En de console erheen wijzen. Het adres is cross-namespace, dus met de volledige servicenaam; de
namespace van een project is zijn eigen naam met het cluster-voorvoegsel ervoor:

```bash
zadctl alias add -c democonsole \
  'REDIS_HOSTS=redis://$DEPLOYMENT_NAME-redis.rig-prd-mpfb-8wh.svc.cluster.local:6379'
zadctl env set -c democonsole SESSIECACHE_BEREIKBAAR=true
```

Zonder die laatste regel blijft het paneel de knop verbergen, ook al werkt hij dan.

**En het wachtwoord.** De Redis op ZAD eist er een; zonder komt de verbinding er wél doorheen — de
netwerkregel doet zijn werk — maar antwoordt Redis `NOAUTH Authentication required` op de eerste
opdracht. Dat is een fout die pas bij een druk op de knop verschijnt.

De waarde staat in de `user-env-vars` van `uitvraag` in `mpfb-8wh` en is via de API niet te lezen
(`(set, not returned by the API)`); haal hem uit Operations Manager en zet dezelfde waarde hier:

```bash
zadctl env add -c democonsole REDIS_PASSWORD=<dezelfde waarde als bij uitvraag>
```

Loopt die waarde ooit uiteen met die van de uitvraag, dan faalt alleen deze knop, en pas op het
moment dat iemand hem gebruikt.

## 6. De storingsknoppen

Vier stromen lopen op ZAD langs een eigen Toxiproxy: `profiel` en `notificatie` in `mpfpsm-lcl`,
`aanmeld` en `redis` in `mpfb-8wh`. Elke proxy staat in dezelfde deployment als zijn upstream, zodat
een preview zijn eigen stubs aanspreekt en niet die van `test`.

Er komt géén `proxies.json` mee. De inhoud van een attachment wordt ongewijzigd gemount, dus zo'n
bestand zou in elke preview naar de upstream van `test` wijzen — en dat is de stilste faalwijze die
er is. De console maakt de proxies daarom zelf aan via de admin-API, met een upstream uit een alias;
aliassen kennen `$DEPLOYMENT_NAME` wél. Hij herhaalt dat elke dertig seconden, want Toxiproxy houdt
zijn proxies in het geheugen en verliest ze bij een herstart.

### Twee poorten per component

Een component publiceert één ingress, maar het mág meer poorten dragen: `service.yaml.jinja` zet elke
poort ná de eerste als extra Service-poort, en de Ingress pakt alleen de eerste. Zo draagt de eerste
poort de stroom (publiek, TLS door de router) en blijft 8474 — de admin-API — cluster-intern, alleen
bereikbaar via de netwerkregel hieronder.

De volgorde is dus niet vrij: de listen-poort eerst, 8474 daarna.

| Component | Project | `--ports` | `publish-on-web` | Upstream |
|---|---|---|---|---|
| `toxiproxy-profiel` | `mpfpsm-lcl` | 18089, 8474 | ja | `$DEPLOYMENT_NAME-profiel:8080` |
| `toxiproxy-notificatie` | `mpfpsm-lcl` | 18084, 8474 | ja | `$DEPLOYMENT_NAME-notificatie:8080` |
| `toxiproxy-aanmeld` | `mpfb-8wh` | 18086, 8474 | ja | `$DEPLOYMENT_NAME-uitvraag:8086` |
| `toxiproxy-redis` | `mpfb-8wh` | 16379, 8474 | nee | `$DEPLOYMENT_NAME-redis:6379` |

De drie ingressen zijn geen luxe. Het magazijn weigert buiten dev een downstream die geen `https`
draagt of die op een intern adres uitkomt (`DownstreamClient.valideerUrl`: TLS-eis uit BIO 13.2.1
plus de SSRF-blocklist). `aanmeld` en `notificatie` móéten dus over de publieke ingress, en dan is
`profiel` ernaast houden eenvoudiger dan één stroom apart bedraden. `toxiproxy-redis` heeft er geen
nodig: hij staat in dezelfde deployment als de uitvraag, en dáár laat de tenant-baseline het verkeer
al toe.

### De probe moet naar 8474, niet naar de stroom

Zonder de `health-check`-dienst probeert Kubernetes een component op zijn eerste inbound-poort, met
een TCP-probe. Precies die poort sluit Toxiproxy zodra je een proxy uitzet — dus ongeveer anderhalve
minuut na een druk op "uit" zou de pod herstarten, mét verlies van álle proxies. De knop zou zichzelf
ongedaan maken en de keten meenemen.

Richt de probe daarom op de admin-API, die altijd staat:

```bash
zadctl service assign health-check -c toxiproxy-profiel
zadctl service config set health-check -c toxiproxy-profiel \
  --set scheme=http --set port=8474 --set liveness-path=/version --set readiness-path=/version
```

Readiness op 8474 is precies goed: de pod blijft `Ready` terwijl de stroom dicht is, dus de router
antwoordt een 503 en het magazijn ziet een dienst die wegviel — wat de demo wil laten zien.

### De vier componenten aanmaken

Toxiproxy start met zijn eigen default-`CMD` (`-host=0.0.0.0`) prima op met nul proxies. Er is dus
geen startcommando nodig, en dat scheelt het UI-handwerk dat een hercreatie niet overleeft.

**Het image wordt op de amd64-child gepind, niet op de tag.** De ZAD-mirror geeft op
`rcr.rijksapps.nl/ghcr-rig/shopify/toxiproxy:2.12.0` een HTTP 500: de manifest list van Toxiproxy
draagt `linux/arm/v6` twee keer met dezelfde digest, en de Quay-pull-through-cache schendt daarop een
unique constraint. Een child is een gewoon manifest zónder kinderen, dus daar struikelt hij niet
over. `TOXIPROXY_IMAGE` in `deploy.yml` draagt de volledige verwijzing; de tag staat er alleen bij
zodat `pin-consistency.yml` hem aan `compose.yaml` kan binden.

```bash
# Dezelfde waarde als TOXIPROXY_IMAGE in deploy.yml; die is de bron.
TOXIPROXY_IMAGE='ghcr.io/shopify/toxiproxy:2.12.0@sha256:a3e244375123dad8849091bcc59775e188624d3f602db01901f9af855682fef8'

for c in profiel:18089 notificatie:18084; do
  naam=toxiproxy-${c%%:*}
  zadctl -p mpfpsm-lcl component add "$naam" \
    --image "$TOXIPROXY_IMAGE" \
    --deployment test \
    --ports "${c##*:}" --ports 8474 \
    --service publish-on-web \
    --service health-check
  zadctl -p mpfpsm-lcl service config set health-check -c "$naam" \
    --set scheme=http --set port=8474 --set liveness-path=/version --set readiness-path=/version
done

for c in aanmeld:18086; do
  naam=toxiproxy-${c%%:*}
  zadctl -p mpfb-8wh component add "$naam" \
    --image "$TOXIPROXY_IMAGE" \
    --deployment test \
    --ports "${c##*:}" --ports 8474 \
    --service publish-on-web \
    --service health-check
  zadctl -p mpfb-8wh service config set health-check -c "$naam" \
    --set scheme=http --set port=8474 --set liveness-path=/version --set readiness-path=/version
done

zadctl -p mpfb-8wh component add toxiproxy-redis \
  --image "$TOXIPROXY_IMAGE" \
  --deployment test \
  --ports 16379 --ports 8474 \
  --service health-check
zadctl -p mpfb-8wh service config set health-check -c toxiproxy-redis \
  --set scheme=http --set port=8474 --set liveness-path=/version --set readiness-path=/version
```

`pin-consistency.yml` houdt de tag in `deploy.yml` gelijk aan die in `compose.yaml`; de digest
ernaast beweegt bij een versiebump mee, en `deploy.yml` legt uit hoe je hem opzoekt. De verwijzing in
de OM-projectspec valt buiten die guard (andere repository, net als bij Redis), maar die beweegt
vanzelf mee met elke uitrol.

Alleen amd64: ZAD draait daarop. `compose.yaml` houdt bewust de multi-arch-tag, zodat de demo lokaal
ook op arm werkt.

Haalt upstream de dubbele child weg, of komt de Quay-dedupe (PROJQUAY-10068) in een release, dan kan
de digest weg en volstaat de tag.

### De netwerkregels voor de admin-API's

Vier hops, dus vier regels — één regel opent één poort op één component bij één peer. Elke regel
draagt twee kanten: de ontvanger beslist, dus één kant alleen zet niets open.

```bash
API=https://operations-manager.rig.prd1.gn2.quattro.rijksapps.nl/api

inbound() {  # project, sleutel, regel, component
  curl -s -X PATCH -H "X-API-Key: $2" -H 'Content-Type: application/json' \
    "$API/v2/projects/$1/services/cross-domain-access/config/project/inbound" \
    -d "{\"add\":[{\"name\":\"$3\",\"to\":{\"component\":\"$4\",\"port\":8474},\"from\":{\"project\":\"mpfm-w3h\",\"component\":\"democonsole\"}}]}"
}

outbound() {  # project van de peer, regel, component
  curl -s -X PATCH -H "X-API-Key: $SLEUTEL_MAGAZIJNEN" -H 'Content-Type: application/json' \
    "$API/v2/projects/mpfm-w3h/services/cross-domain-access/config/project/outbound" \
    -d "{\"add\":[{\"name\":\"$2\",\"from\":{\"component\":\"democonsole\"},\"to\":{\"project\":\"$1\",\"component\":\"$3\",\"port\":8474}}]}"
}

inbound mpfpsm-lcl "$SLEUTEL_PROFIEL"  democonsole-naar-toxiproxy-profiel     toxiproxy-profiel
inbound mpfpsm-lcl "$SLEUTEL_PROFIEL"  democonsole-naar-toxiproxy-notificatie toxiproxy-notificatie
inbound mpfb-8wh   "$SLEUTEL_UITVRAAG" democonsole-naar-toxiproxy-aanmeld     toxiproxy-aanmeld
inbound mpfb-8wh   "$SLEUTEL_UITVRAAG" democonsole-naar-toxiproxy-redis       toxiproxy-redis

outbound mpfpsm-lcl democonsole-naar-toxiproxy-profiel     toxiproxy-profiel
outbound mpfpsm-lcl democonsole-naar-toxiproxy-notificatie toxiproxy-notificatie
outbound mpfb-8wh   democonsole-naar-toxiproxy-aanmeld     toxiproxy-aanmeld
outbound mpfb-8wh   democonsole-naar-toxiproxy-redis       toxiproxy-redis
```

Daarna de invulling voor `test`, met hetzelfde script dat CI voor previews gebruikt. Het neemt
meerdere regelnamen in één patch:

```bash
ZAD_API_KEY=$SLEUTEL_PROFIEL .github/scripts/cross-domain-preview.sh zet mpfpsm-lcl test inbound \
  democonsole-naar-toxiproxy-profiel democonsole-naar-toxiproxy-notificatie

ZAD_API_KEY=$SLEUTEL_UITVRAAG .github/scripts/cross-domain-preview.sh zet mpfb-8wh test inbound \
  democonsole-naar-toxiproxy-aanmeld democonsole-naar-toxiproxy-redis

ZAD_API_KEY=$SLEUTEL_MAGAZIJNEN .github/scripts/cross-domain-preview.sh zet mpfm-w3h test outbound \
  democonsole-naar-toxiproxy-profiel democonsole-naar-toxiproxy-notificatie \
  democonsole-naar-toxiproxy-aanmeld democonsole-naar-toxiproxy-redis
```

### Twee dingen die in deze volgorde moeten

**De componenten eerst, de netwerkregels daarna.** Operations Manager rendert een regel waarvan het
peer-component niet bestaat gewoon niet — met een waarschuwing in een log die niemand leest. De
regel staat dan wél in de configuratie en de deployment meldt `Healthy`, maar de NetworkPolicy mist
die egress-regel en het verkeer loopt in een timeout. Zet je de regels vóór de componenten, draai dan
daarna `zadctl deployment refresh <deployment>` en controleer de gerenderde policy:

```bash
gh api repos/RijksICTGilde/rig-cluster-application-test/contents/odcn-production/mpfm-w3h/test/test-cross-domain-access-democonsole-network-policy.yaml \
  --jq '.content' | base64 -d | grep -E 'app:|port:'
```

Er horen vijf peers in te staan: `redis` plus de vier `toxiproxy-*`. Staat alleen `redis` er, dan is
precies dit gebeurd.

**De keten pas omhangen als de console overal draait.** De stap hieronder leidt het verkeer door de
proxies, maar die proxies bestaan pas nadat de console ze heeft aangemaakt — en dat doet alleen een
console-image dat `ProxyBootstrap` kent. Hang je de keten om terwijl er nog een ouder image draait,
dan wijst de uitvraag naar een Redis-proxy die niemand aanmaakt en ligt de demo plat. Wacht dus tot
de merge naar main `deploy-test-*` heeft laten draaien.

### De keten door de proxies leiden

Welke dienst achter welke proxy hoort, staat vast in `compose.yaml`; ZAD spiegelt dat, anders doet
dezelfde knop lokaal iets anders dan op de demo.

| Proxy | Wie roept aan | Sleutel om te verzetten |
|---|---|---|
| `profiel` | `uitvraag` (`mpfb-8wh`) | `PROFIEL_SERVICE_URL` |
| `notificatie` | `magazijna`, `magazijnb` (`mpfm-w3h`) | `NOTIFICATIE_URL` |
| `aanmeld` | `magazijna`, `magazijnb` (`mpfm-w3h`) | `AANMELD_URL` |
| `redis` | `uitvraag` (`mpfb-8wh`) | `REDIS_HOSTS` |

Let op de eerste rij: het is de **uitvraag** die de profielservice bevraagt om te bepalen welke
magazijnen een ontvanger gebruikt. De magazijnen hebben óók een `PROFIEL_SERVICE_URL` — die blijft
rechtstreeks, precies zoals in `compose.yaml`. Zet je die ook om, dan haalt "profiel uit" twee
verschillende dingen tegelijk onderuit en is niet meer te vertellen wat de demo laat zien.

Op `uitvraag` in `mpfb-8wh`. `REDIS_HOSTS` houdt het schema dat er staat — dit verzet alleen host en
poort:

```bash
zadctl -p mpfb-8wh alias set -c uitvraag \
  'PROFIEL_SERVICE_URL=https://toxiproxy-profiel-$DEPLOYMENT_NAME-mpfpsm-lcl.rig.prd1.gn2.quattro.rijksapps.nl' \
  'REDIS_HOSTS=redis://$DEPLOYMENT_NAME-toxiproxy-redis:16379'
```

Op `magazijna` én `magazijnb` in `mpfm-w3h`:

```bash
for m in magazijna magazijnb; do
  zadctl -p mpfm-w3h alias set -c "$m" \
    'NOTIFICATIE_URL=https://toxiproxy-notificatie-$DEPLOYMENT_NAME-mpfpsm-lcl.rig.prd1.gn2.quattro.rijksapps.nl/events' \
    'AANMELD_URL=https://toxiproxy-aanmeld-$DEPLOYMENT_NAME-mpfb-8wh.rig.prd1.gn2.quattro.rijksapps.nl/api/v1/aanmeldingen'
done
```

**Alleen een preview omhangen kan ook**, zonder `test` te raken. Aliassen kunnen dat niet — die
bestaan alleen op componentniveau (`Service 'aliases' has no values at target
'deployment-component'`) — maar `user-env-vars` wél:

```bash
zadctl env add -c uitvraag --deployment pr-<n> \
  'REDIS_HOSTS=redis://pr-<n>-toxiproxy-redis:16379'
```

Dat werkt omdat het gerenderde `envFrom` het user-secret ná het platform-secret zet, en bij gelijke
sleutels wint de laatste. Geen `$DEPLOYMENT_NAME` hierin: de waarde geldt maar voor één deployment,
dus de naam mag er hard in. Zo is een preview volledig te demonstreren terwijl `test` nog op een
oudere console draait.

**Let op bij `test`:** dat draagt zelf al een deployment-override voor `PROFIEL_SERVICE_URL`. Een
alias op componentniveau komt daar dus niet doorheen — verzet daar de deployment-waarde, niet de
alias.

**De console blijft rechtstreeks op Redis staan.** Zijn `REDIS_HOSTS` uit stap 5 verandert niet: de
cache-verval-knop is een beheeractie, en die hoort te blijven werken terwijl je de Redis-stroom
uitzet. Loopt hij mee door de proxy, dan valt met "redis uit" ook de knop weg waarmee je het verhaal
vertelt.

### En de console erheen wijzen

```bash
zadctl alias add -c democonsole \
  'TOXIPROXY_PROFIEL_URL=http://$DEPLOYMENT_NAME-toxiproxy-profiel.rig-prd-mpfpsm-lcl.svc.cluster.local:8474' \
  'TOXIPROXY_NOTIFICATIE_URL=http://$DEPLOYMENT_NAME-toxiproxy-notificatie.rig-prd-mpfpsm-lcl.svc.cluster.local:8474' \
  'TOXIPROXY_AANMELD_URL=http://$DEPLOYMENT_NAME-toxiproxy-aanmeld.rig-prd-mpfb-8wh.svc.cluster.local:8474' \
  'TOXIPROXY_REDIS_URL=http://$DEPLOYMENT_NAME-toxiproxy-redis.rig-prd-mpfb-8wh.svc.cluster.local:8474' \
  'TOXIPROXY_PROFIEL_UPSTREAM=$DEPLOYMENT_NAME-profiel:8080' \
  'TOXIPROXY_NOTIFICATIE_UPSTREAM=$DEPLOYMENT_NAME-notificatie:8080' \
  'TOXIPROXY_AANMELD_UPSTREAM=$DEPLOYMENT_NAME-uitvraag:8086' \
  'TOXIPROXY_REDIS_UPSTREAM=$DEPLOYMENT_NAME-redis:6379'
```

De vier `TOXIPROXY_*_URL` waren in stap 3 leeg gezet; deze aliassen vullen ze. `zadctl env set -c
democonsole TOXIPROXY_MAGAZIJN_A_URL= TOXIPROXY_MAGAZIJN_B_URL=` blijft staan — die twee wachten op
de magazijn-simulator (#938), en het paneel laat hun knoppen weg zolang de waarde leeg is.

De listen-poorten hebben geen alias nodig; hun defaults in `application.properties` zijn al de
poorten uit de tabel hierboven. De upstreams wél: die noemen een deployment, en alleen een alias
vult `$DEPLOYMENT_NAME` in.

**De upstream wordt in de Toxiproxy-pod opgelost, niet in die van de console.** Daarom een korte
servicenaam (`$DEPLOYMENT_NAME-profiel`) en geen volledige cross-namespace-naam: de proxy staat in
dezelfde namespace als zijn upstream. De admin-URL's zijn andersom — die lost de console zelf op, en
dus staan daar de volledige namen.

## 7. Uitrollen en verifiëren

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

**De magazijn-storingen.** De twee `TOXIPROXY_MAGAZIJN_*_URL` blijven leeg en het paneel laat die
knoppen weg. Ze wachten op de magazijn-simulator (#938), die het storingsgedrag van een magazijn
zelf levert; een Toxiproxy vóór elk magazijn zou datzelfde werk dubbel doen.

**De veel-magazijnen-schuif.** Zelfde afhankelijkheid (#938).

De andere knoppen werken wél. **De cache-verval-knop** sinds stap 5, **de vier storingsknoppen**
sinds stap 6. Ze vragen allebei cluster-intern verkeer naar een ander project, en zo'n netwerkregel
noemt op ZAD altijd één vaste deployment — daarom schrijven `deploy.yml` en `cleanup-preview.yml` de
per-deployment invulling zelf bij en weer weg.

Wat daarbij níet meekomt is een `proxies.json`: de inhoud van een attachment wordt ongewijzigd
gemount, dus zo'n bestand zou in elke preview de upstream van `test` noemen — een preview die
stilzwijgend het verkeer van een ander magazijn afhandelt. De console maakt de proxies daarom zelf
aan. Dat schrapt meteen het `command` dat Toxiproxy naar zo'n bestand zou wijzen, en dat scheelt
UI-handwerk dat een hercreatie niet overleeft.

## Wat het wonen in `test` met zich meebrengt

- De demo rolt mee met elke merge naar main, dus de omgeving kan tijdens een presentatie herstarten.
- De legen-knop op de console ín `test` wist de database van `test`, waar nieuwe previews van
  klonen. Op een preview raakt legen alleen die preview: elke deployment heeft zijn eigen database.
- Elke openstaande PR draagt de console mee, ongeveer 250 Mi, plus vier Toxiproxy's van ~32 Mi en
  drie ingressen.
- Het ketenverkeer loopt voortaan altijd door die proxies, ook als niemand demonstreert. Dat is een
  extra hop in het pad, bewust geaccepteerd omdat de knoppen anders niet bestaan.
