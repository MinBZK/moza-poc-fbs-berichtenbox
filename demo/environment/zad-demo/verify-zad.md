# De demo op ZAD verifiëren

De stappen na de eenmalige creatie uit `README.md`. Ze staan in de volgorde waarin een fout de
volgende stap onbruikbaar maakt, dus stop bij de eerste die niet klopt.

Het paneel staat achter de authorization-wall, dus doe dat deel in een browser waarin je bent
ingelogd. Een `curl` zonder sessie krijgt daar HTTP 403 met de inlogpagina terug — dat is de muur,
niet een kapot component. De proeftuin en de personadienst (stap 7) dragen bewust géén muur; daar is
403 juist wél een fout.

```
CONSOLE=https://democonsole-test-mpfm-w3h.rig.prd1.gn2.quattro.rijksapps.nl
```

## 0. Draait er iets, en staat de muur

Eerst de vraag die alle volgende stappen aan de verkeerde kant laat uitkomen als je hem overslaat:
**draait er een pod?** Een component dat op `replicas: 0` staat, meldt zich in de UI als
"uitgeschakeld: image ontbreekt" en in de logs als "No resources found in namespace" — het lijkt
een image-probleem, maar het is er geen. De gerenderde manifesten zijn de grond-waarheid:

```bash
gh api repos/RijksICTGilde/rig-cluster-application-test/contents/odcn-production/mpfm-w3h/test/democonsole-deployment.yaml \
  --jq '.content' | base64 -d | grep -E '^\s+(replicas|image):'
```

Verwacht `replicas: 1` en een tag van de vorm `main-<sha7>`. Faalt het `gh api`-commando zelf, dan
is dat het antwoord — niet een lege uitvoer die je als "niets bijzonders" leest. Herhaal dit voor
`magazijna`, `magazijnb`, `magazijnsimulator`, `demopersonas` en `proeftuin` zodra je die verderop
nodig hebt; in `mpfb-8wh` en `mpfpsm-lcl` gaat het pad langs die project-id in plaats van
`mpfm-w3h`.

Reactiveren van een uitgeschakeld component gaat **niet** met `:refresh` of `deployment update-image`
— die verhogen `replicas` niet. Lees eerst de waarschuwing onder "Als een component uitstaat" in
`README.md` voordat je aan de herstelroute begint; die is destructief.

Dan de muur:

```bash
curl -sS -o /dev/null -w '%{http_code}\n' "$CONSOLE/"
```

| Code | Betekenis |
|---|---|
| `403` | Goed: dat is de muur. |
| `200` | **Stop.** Het paneel staat open op het internet en iedereen die het adres kent kan de magazijnen legen. `keycloak` is niet aan het component gebonden — zie stap 2 van `README.md`. |
| `502`, `503` | De ingress staat, maar er draait niets achter. Dit zegt niets over de muur; ga terug naar de replicas-controle hierboven. |
| `000` | Geen verbinding: DNS of TLS. Ook dit zegt niets over de muur. |

## 1. De omgeving beschrijft zichzelf

Open `$CONSOLE/api/demo/omgeving`.

Verwacht:

- `uitvraagBasis` wijst naar de publieke uitvraag van dezelfde deployment, **inclusief** `/api/v1`.
  Zonder dat pad faalt elke aanroep vanaf de Berichtenbox-pagina zichtbaar voor de gebruiker.
- `storingen` bevat precies `aanmeld`, `notificatie`, `profiel` en `redis`, zodra stap 6 van
  `README.md` gedaan is. `magazijn-a` en `magazijn-b` horen er níet in te staan: op ZAD bestaan er
  geen `toxiproxy-magazijn*`-componenten, dus hun knop zou gegarandeerd falen. Lokaal houden A en B
  hun proxy wél — zie `demo/demo-console/src/main/resources/application.properties`.
- `sessiecache` is `true` zodra stap 5 van `README.md` gedaan is.
- `simulator` is `true` zodra `magazijn-simulator.md` §4 gedaan is. Staat hij op `false`, dan laat
  het paneel de hele groep "Gesimuleerde magazijnen" weg terwijl alles eronder werkt.
- `personas` is een gevulde lijst. Is hij leeg, dan komt de personadienst niet door — dat is stap 7.
- `berichtenboxUrl` wijst naar de proeftuin van dezelfde deployment, inclusief het pad
  `/moza/berichtenbox/`. Leeg betekent dat het paneel het lokale pad probeert, dat hier niet
  bestaat: het frame blijft dan leeg.

Staat er een proxy in `storingen` die er niet hoort, dan is de bijbehorende `TOXIPROXY_*_URL` niet
leeg gezet. Ontbreekt er één die er wél hoort, kijk dan eerst of de gelijknamige **lege
omgevingsvariabele** uit stap 3 nog op het component staat: die wint van de alias uit stap 6.

## 2. Vullen

Open `$CONSOLE/` en druk op **Herstel demo**. Dat stopt de stroom, reset de storingen, leegt de
magazijnen en laadt de basisvulling — in die volgorde.

Verwacht: een antwoord zonder fout, en daarna onder **Berichten per magazijn** (tabblad Info) een
aantal groter dan nul per magazijn. Hetzelfde getal staat in `$CONSOLE/api/demo/status`.

Faalt het legen op een verbindingsfout, dan klopt een van de `MAGAZIJN_*_DB_*`-aliassen niet. Faalt
het vullen met een 403, dan kent de profielservice-stub de persona niet — controleer of de
externe-stubs van dezelfde deployment draaien.

## 3. De keten

Open `$CONSOLE/berichtenbox.html`, kies een persona en haal berichten op.

Verwacht: de berichten uit de basisvulling verschijnen, uit beide magazijnen. Dit is de enige stap
die de hele keten aanraakt — console → magazijn → uitvraag → sessiecache → terug.

Blijft de lijst leeg terwijl stap 2 wel berichten telde, kijk dan in de browserconsole naar een
CORS-fout: dan dekt de regex in `QUARKUS_HTTP_CORS_ORIGINS` op de uitvraag deze console-origin niet.

## 4. De schemacontrole

**Sla deze niet over.** Dit is de enige stap die een verkeerd `MAGAZIJN_*_DB_SCHEMA` aanwijst, en
een verkeerd schema faalt stil: de console leegt dan een leeg schema en meldt tevreden nul.

1. `$CONSOLE/api/demo/status` — noteer het aantal per magazijn. Moet groter dan nul zijn (stap 2).
2. Druk op **Magazijnen legen**.
3. `$CONSOLE/api/demo/status` opnieuw — moet nu nul per magazijn zijn.

Blijft het aantal in stap 3 gelijk aan dat van stap 1, terwijl het legen wél "gelukt" meldde, dan
wijst minstens één schema naar de verkeerde plek. Lees de juiste waarden af met
`zadctl env list -c magazijna` en `-c magazijnb`, en zet ze met `zadctl env set -c democonsole`.

## 5. De cache-verval-knop

Druk op tabblad **Scenario's** op **Cache verlopen** en haal daarna in de Berichtenbox opnieuw
berichten op.

Verwacht: de knop meldt geen fout, en de eerstvolgende `GET /berichten` geeft 409 tot je opnieuw
ophaalt — de sessie is weg.

Blijft de groep *Sessie* onzichtbaar, dan staat `SESSIECACHE_BEREIKBAAR` nog op `false`. Geeft hij
`NOAUTH Authentication required`, dan ontbreekt `REDIS_PASSWORD` of wijkt hij af van die van de
uitvraag — de verbinding kwám er dan wél doorheen, dus de netwerkregel staat. Geeft hij een
verbindings- of timeoutfout, dán ontbreekt de `cross-domain-access`-regel voor deze deployment; op
een preview zetten `deploy.yml` en `cleanup-preview.yml` die, op `test` staat hij met de hand.

## 6. De storingsknoppen

De vier knoppen staan in de groep **Omliggende diensten** op het tabblad Storingen. Doe ze niet
allemaal tegelijk: elke knop hoort een eigen, herkenbaar effect te hebben.

**Druk op `Profielservice uit`** en haal daarna in de Berichtenbox berichten op. Verwacht: het
ophalen faalt, want de **uitvraag** kan niet bepalen welke magazijnen bij deze ontvanger horen. Druk
op **Alles normaal** en haal opnieuw op — nu slaagt het.

> Toets dit níet met opvoeren. De magazijnen bevragen de profielservice ook, maar hún
> `PROFIEL_SERVICE_URL` gaat bewust rechtstreeks en niet door de proxy; het aanleveren blijft dus
> gewoon slagen. Zie de tabel in `README.md` §6, "De keten door de proxies leiden".

**Druk op `Redis uit`** en haal opnieuw op. Verwacht: het ophalen faalt op de sessiecache. De
`Traag`-knoppen staan alleen bij Magazijn A en B, en die twee groepen zijn op ZAD verborgen — een
traag-toets voor de omliggende diensten is er dus niet via het paneel. Wil je de latency-toxic toch
zien, dan kan dat rechtstreeks:

```bash
curl -sS -o /dev/null -w '%{http_code}\n' -X POST "$CONSOLE/api/demo/storing/redis/traag"
```

Verwacht bij elke knop een antwoord zonder fout. Een **verbindings- of timeoutfout** betekent dat de
`cross-domain-access`-regel voor deze deployment ontbreekt — de console komt dan niet bij de
admin-API. Een **404 op de proxy** betekent dat de proxy nog niet is aangemaakt; de console doet dat
zelf, maar pas nadat hij de Toxiproxy kan bereiken, dus dat wijst op dezelfde ontbrekende regel.

**Controleer daarna de upstream.** Dit is de stap die de stilste fout vangt: een preview die het
verkeer van `test` afhandelt. De admin-API op 8474 is bewust cluster-intern — de ingress publiceert
alleen `ports[0]` — dus die is van een werkplek niet te bevragen. Lees het antwoord af waar het wél
staat, in de log van de console die de proxies aanmaakt:

```bash
zadctl -p mpfm-w3h logs <deployment> -c democonsole --since 1h -n 200 | grep "aangemaakt:"
```

Verwacht regels `Proxy <naam> aangemaakt: <listen> -> <deployment>-<upstream>`, waarin de upstream de
deployment van *deze* preview noemt (`pr-<n>-profiel`), niet `test-profiel`. Klopt dat niet, dan is
`TOXIPROXY_*_UPSTREAM` als gewone env-var gezet in plaats van als alias — alleen een alias vult
`$DEPLOYMENT_NAME` in. Levert het commando niets op, dan is dat geen bewijs dat het goed staat: de
console logt alleen bij een daadwerkelijke aanmaak, dus doe eerst stap 8.

## 7. De berichtenbox in het frame

Open `$CONSOLE/`. Verwacht links de berichtenbox van de proeftuin, met de bediening ernaast; niet
het blok "Berichtenbox niet bereikbaar".

Zie je dat blok wél, dan is `BERICHTENBOX_URL` niet gezet — het paneel toetst een geconfigureerd
adres niet, dus een leeg frame komt hier nooit door een mislukte toets. Staat de variabele er wel,
open het adres dan los in de browser: geeft het daar een pagina, dan is het frame het probleem;
geeft het een fout, dan staat het proeftuin-component niet of wijst de alias mis.

Kies daarna in de berichtenbox een testaccount van het stelsel — `Garage Van Dijk B.V.` is er een
met een KVK-nummer. Verwacht berichten. Krijg je "Er gaat iets mis met het ophalen van uw berichten
bij de bronnen", kijk dan eerst hier:

```bash
curl -sS -o /dev/null -w '%{http_code}\n' \
  "https://demopersonas-<deployment>-mpfm-w3h.rig.prd1.gn2.quattro.rijksapps.nl/api/demo/personas"
```

| Code | Betekenis |
|---|---|
| `200` | Goed. |
| `403` | Er staat per ongeluk een `authorization-wall` op dit component — de berichtenbox haalt dit pad server-side op en heeft geen sessie. |
| `404` | Het component staat er niet, of publiceert zijn poort niet. |
| `502`, `503` | Er draait niets. Kijk **eerst** naar `replicas` in het gerenderde manifest (stap 0); pas als dat `1` is, is het image de volgende verdachte — bijvoorbeeld een image dat op een andere poort luistert dan de 8098 die het component publiceert, of een tijdelijk image dat nog op de eerste uitrol wacht. |
| `000` | Geen verbinding: DNS of TLS, niet het component. |

Let op de tweede orde: wijst `BACKEND_PERSONAS` van de proeftuin naar een dienst die niet luistert,
dan geeft óók `proeftuin-<deployment>-…/api/demo/personas` een 503. De fout zit dan niet in de
proeftuin.

Klopt dat adres wel, toets dan hetzelfde pad via de proeftuin — dat is de route die de berichtenbox
zelf loopt, en die hangt aan `BACKEND_PERSONAS`:

```bash
curl -sS -o /dev/null -w '%{http_code}\n' \
  "https://proeftuin-<deployment>-mpfm-w3h.rig.prd1.gn2.quattro.rijksapps.nl/api/demo/personas"
```

Een `403` hier terwijl de dienst zelf `200` geeft, betekent dat `BACKEND_PERSONAS` ontbreekt en de
proeftuin op `BACKEND_DEMO` terugvalt — dus op het bedieningspaneel, dat wél een muur draagt.
`BACKEND_DEMO` hoort naar `democonsole` te blijven wijzen; verzet die niet.

Druk daarna in de berichtenbox op **Ophalen** en laat de ronde helemaal uitlopen, ook als er tussen
twee organisaties lang niets komt. Breekt de stream na ongeveer een minuut stilte af, dan loopt het
keten-verkeer nog door de catch-all `/api/` van de proeftuin, met zijn leestijdslimiet van zestig
seconden: die deployment draait dan op een image van vóór de splitsing van `/api/v1/`, of
`BACKEND_KETEN` staat er niet — zie stap 7 van `README.md`.

## 8. Herstart-bestendigheid

Toxiproxy houdt zijn proxies in het geheugen, dus elke herstart laat de keten dood achter tot de
console ze opnieuw aanmaakt. Deze stap toetst dat die reconcile echt draait.

**Niet met `zadctl deployment refresh`.** Dat reconcilet vanuit git en herstart geen pods zolang het
gerenderde manifest gelijk blijft. De proxies staan er daarna nog steeds — maar om een reden die
niets met de reconcile te maken heeft, dus de stap zou groen melden terwijl de reconcile stuk is.

**En ook niet door op een merge naar main te wachten.** `TOXIPROXY_IMAGE` in `deploy.yml` staat op
een vaste digest, en elke uitrol stuurt exact diezelfde waarde mee. Het gerenderde manifest
verandert dus niet, Argo synct niet, en de pod blijft staan.

Wat de pod wél vervangt, is een verandering in dat manifest. Dwing die af door de image van één
Toxiproxy-component op de tag zónder digest te zetten en meteen weer terug:

```bash
zadctl -p mpfb-8wh --strict deployment update-image test -c toxiproxy-redis \
  --image ghcr.io/shopify/toxiproxy:2.12.0
zadctl -p mpfb-8wh --strict deployment update-image test -c toxiproxy-redis \
  --image "<de volledige waarde van TOXIPROXY_IMAGE uit deploy.yml>"
```

Kijk daarna in de console-log, begrensd op de tijd sinds die herstart — zonder `--since` tel je
regels van dagen geleden mee en meldt de stap groen zonder iets getoetst te hebben:

```bash
zadctl -p mpfm-w3h logs test -c democonsole --since 5m -n 200 | grep -c "aangemaakt:"
```

Verwacht `4`, binnen een halve minuut na de herstart. Blijft dat uit, kijk dan naar de drie meldingen
die `ProxyBootstrap` bij een mislukking logt, want elk wijst een andere laag aan:

| Melding | Oorzaak |
|---|---|
| `Proxies ... niet te verzoenen: ...` | Verbinding of timeout naar de admin-API — de netwerkregel. |
| `Proxy ... niet aangemaakt: HTTP <code>` | De Toxiproxy antwoordde, maar weigerde — meestal de poort of een dubbele naam. |
| `Geen listen/upstream voor proxy ...` | Een `TOXIPROXY_*_URL` of `*_UPSTREAM` ontbreekt in de configuratie. |

## 9. De fan-out van de vier ondernemers

Deze stap toetst waar de simulator voor bestaat: dat een ondernemer met veel aangesloten
organisaties er ook werkelijk veel bevraagd krijgt. Wat je hier vaststelt is het **aantal** — 3, 15,
45 en 100 — en dat de lijst meteen begint te vullen. Niet de tijden van de laptopmeting: op ZAD gaat
elke bevraging over de publieke ingress, dus die getallen horen hoger te liggen. Ze zijn een
vergelijkingspunt, geen norm.

Vooraf drie dingen, want elk ervan laat de meting anders stil verkeerd uitkomen:

```bash
# a. Draait de simulator, en met welke tag?
gh api repos/RijksICTGilde/rig-cluster-application-test/contents/odcn-production/mpfm-w3h/test/magazijnsimulator-deployment.yaml \
  --jq '.content' | base64 -d | grep -E '^\s+(replicas|image):'

# b. Staat de bulkhead boven de grootste fan-out? Bij de standaard 20 meet je die grens en niet de keten.
zadctl -p mpfb-8wh env list -c uitvraag | grep BULKHEAD    # verwacht 120

# c. Is de simulator gevuld? Anders bevraagt de uitvraag honderd lege magazijnen.
```

Voor (c): open `$CONSOLE/`, tabblad **Scenario's**, en druk op **Vullen** in de groep *Gesimuleerde
magazijnen* — of op **Herstel demo**, dat het ook doet.

Dan de meting zelf, vanuit de repository-root:

```bash
UITVRAAG=https://uitvraag-test-mpfb-8wh.rig.prd1.gn2.quattro.rijksapps.nl \
CONSOLE=https://democonsole-test-mpfm-w3h.rig.prd1.gn2.quattro.rijksapps.nl \
CONSOLE_COOKIE='<het sessiecookie van je ingelogde browser>' \
  demo/meet-fanout.sh 3
```

**Het cookie is geen franje.** Het script leegt vóór elke ronde de sessiecache via het paneel, en dat
paneel staat achter de muur: zonder cookie krijgt die aanroep 403 en stopt de meting met "de
sessiecache legen mislukte".

Welk cookie: de muur is een **oauth2-proxy**-sidecar, en die zet zijn sessie in `_oauth2_proxy`. De
sidecar geeft geen `--cookie-name` mee, dus dat is de standaardnaam. Is de sessie groot, dan splitst
oauth2-proxy hem over `_oauth2_proxy_0`, `_oauth2_proxy_1`, … — dan moeten die stukken **allemaal**
mee, in dezelfde volgorde. Kopieer daarom niet één waarde maar de hele kopregel: open de devtools van
een browser waarin je op `$CONSOLE` bent ingelogd, tabblad Netwerk, klik een verzoek aan de console
aan en kopieer bij *Request Headers* de waarde van `Cookie`. Die vorm — `naam=waarde; naam=waarde` —
is precies wat `CONSOLE_COOKIE` verwacht, en hij klopt ook als de naam ooit verandert.

Het is een geldige sessie op jouw rijksaccount: hou hem uit shell-geschiedenis, issues en logs, en
haal een verse op als je meting stukloopt op een 403 (hij verloopt).

Zonder cookie kan het ook met de hand — `demo/meet-fanout.sh 1` per ondernemer, en tussen de rondes
in de browser op **Cache verlopen** — maar dan is de meting niet in één handeling te herhalen.

Verwacht per ondernemer, met de laptopmeting uit
`docs/plans/2026-08-21-magazijn-simulator-design.md` ernaast:

| Ondernemer | Bevraagd | Geslaagd | Op de laptop: eerste / compleet |
|---|---|---|---|
| kleine-eenmanszaak | 3 | 3 | 43 ms / 0,13 s |
| klein-bedrijf | 15 | 15 | 49 ms / 1,5 s |
| grootbedrijf | 45 | 41 | 94 ms / 10,1 s → 3,0 s |
| landelijk-concern | 100 | 91 | 137 ms / 10,1 s → 2,7 s |

De uitvallers horen erbij: de gedragsverdeling zet er twee uit, drie op serverfout, één op weigeren
en één op onbruikbaar antwoord, plus vier die de helft van de tijd haperen. Dat "compleet" in de
eerste rondes rond de tien seconden ligt is ook goed — dat is de query-timeout op één organisatie die
niet reageert, en na drie storingen slaat de circuit breaker hem dertig seconden over, waarna dezelfde
ronde naar een paar seconden zakt.

Wat wél een bevinding is:

| Wat je ziet | Wat het betekent |
|---|---|
| `WAARSCHUWING: <n> organisaties bevraagd, verwacht <m>` | Het register op de uitvraag en de set van de simulator lopen uiteen — beide attachments komen uit hetzelfde script met hetzelfde getal, dus één is niet opnieuw geüpload. |
| Veel meer mislukt dan de tabel, verspreid over alle ondernemers | Kijk eerst naar de simulator: zijn database-pool draagt honderd magazijnen tegelijk, en een te lage waarde laat magazijnen omvallen die op *normaal* staan. |
| Precies 20 geslaagd bij 45 en 100 | De bulkhead staat nog op de standaardwaarde: controle (b) hierboven. |
| `de uitvraag op … is niet gezond` | De meting is niet begonnen; dit zegt niets over de fan-out. |

Bewaar de uitkomst als hij afwijkt van de tabel hierboven: het TSV-bestand uit `$UITVOER` (standaard
`/tmp/fanout-meting.tsv`) is het bewijsstuk. Leg hem vast waar hij navolgbaar blijft — bij de meting
in `docs/plans/2026-08-21-magazijn-simulator-design.md`, of onder het issue waaraan je werkt. Een
meting die alleen in een terminal heeft gestaan, is een volgende keer niet te vergelijken.

## 10. De gezondheidscontrole per component

Deze stap toetst dat elk component gecontroleerd wordt op een manier die bij dat component past —
hoofdstuk 9 van `README.md` draagt de keuze per component en de reden erbij.

**(a) Staat de gekozen probe in het gerenderde manifest?** Dat manifest is wat Argo synct, en dus
het enige dat telt; de UI kan een bevroren, verouderde melding tonen. Loop álle vijf de deployments
af, niet alleen die van de uitvraag:

```bash
basis=repos/RijksICTGilde/rig-cluster-application-test/contents/odcn-production
probe() {
  echo "-- $1/$2 $3"
  gh api "$basis/$1/$2/$3-deployment.yaml" --jq '.content' | base64 -d \
    | grep -A6 -E 'livenessProbe|readinessProbe'
}
probe mpfpsm-lcl test profiel                    # httpGet /__admin/health :8080
probe mpfm-w3h   test demopersonas               # httpGet /q/health/live :8098
probe mpfb-8wh   test uitvraag                   # httpGet /q/health/live :8086
probe mpfb-8wh   fsc-logius logius-fscoutway     # httpGet /health/live :8081
probe mpfm-w3h   fsc-magazijna magazijna-fscmgr  # httpGet /health/live :8080
```

Staat er nog een `tcpSocket` waar een `httpGet` hoort, dan is de instelling niet aangekomen. Kijk
eerst of er een uitrol liep (`gh run list --workflow "Deploy ZAD"`) en draai
`gezondheidscontrole.sh apply` opnieuw.

Zes componenten kun je zo niet controleren: de vier `tcp`-regels (`redis`, `proeftuin` en de twee
`fscpg`) renderen hetzelfde manifest als de standaard, en de twee `none`-regels renderen nog steeds
niets. Wat daar in OM staat lees je met `zadctl -p <project> service config get health-check` — dat
commando toont de dienst over alle lagen en kent geen componentvlag, dus je zoekt het component in
de uitvoer op.

**Twee dingen die deze stap voor het eerst bewijst.** Beide staan in hoofdstuk 9 van `README.md` als
verwacht-maar-ongemeten, en dit is de plek waar ze waar of onwaar worden:

- **Slaat de dienst aan op een component dat al bestond?** Poorten en aliassen doen dat niet. Zie je
  bij `demopersonas` een `httpGet` op `/q/health/live`, dan is het antwoord ja. Blijft het
  `tcpSocket`, dan moet elk component herschapen worden (`component remove` + `component add`;
  nooit `deployment delete` — dat wist in `mpfm-w3h` de gedeelde database). Neem hiervoor niet
  `democonsole`: dat draait naast de app een authorization-wall die zijn eigen `httpGet /ping` op
  4180 rendert, dus daar staat een httpGet in het manifest of de instelling nu is aangekomen of niet.
- **Rendert ZAD een probe op een poort die niet in `ports.inbound` staat?** Alleen de FSC-regels
  hangen daarvan af. Draagt `logius-fscoutway` een `httpGet` op 8081, dan is het antwoord ja. Zo
  niet, dan moet de monitoring-poort als extra inbound-poort op die componenten — 8081, en 8080 op
  de twee managers — en dát vraagt een hercreatie.

**(b) Zakt readiness mee zonder herstart?** Dit is de kern van de stap. De uitvraag publiceert zijn
health-endpoints op dezelfde poort als zijn API, dus je kunt ze over de ingress bevragen:

```bash
uitvraag=https://uitvraag-test-mpfb-8wh.rig.prd1.gn2.quattro.rijksapps.nl
curl -sS -w '\n-> %{http_code}\n' "$uitvraag/q/health/ready"
```

Gezond: `200` met `"status": "UP"` en een lijst checks waarin de berichtenopslag staat. Zet nu de
Redis-storingsknop dicht via het paneel (tabblad **Storingen**) en herhaal het.

Verwacht binnen enkele seconden een **503** — en let op waar die vandaan komt: zodra de pod
`NotReady` is haalt de router hem uit de endpoints, dus je krijgt de 503 van de ingress en niet meer
het JSON-antwoord van de applicatie. Dat is het bewijs dat readiness meezakt.

Verwacht **geen** herstart: liveness staat op `/q/health/live` en zegt alleen iets over het proces.
`zadctl -p mpfb-8wh logs test -c uitvraag -n 20` hoort geen verse opstartregels te tonen. Zet de
knop weer open; binnen enkele seconden hoort het `200` met `"status": "UP"` terug te zijn. Blijft
het 503, dan zakt readiness ergens anders op mee.

Dat de ingress hier 503 antwoordt in plaats van dat de uitvraag zelf zijn degradatie laat zien, is
een bewuste keuze — hoofdstuk 9 van `README.md` legt uit waarom, en wat het alternatief zou zijn.

**(c) Blijven de storingsknoppen werken?** Zet alle vier de proxies uit en weer aan. Geen enkele
Toxiproxy-pod hoort te herstarten, en het uitzetten van de ene knop hoort de andere drie niet mee te
nemen. Stap 6 hierboven beschrijft de knoppen zelf; hier gaat het alleen om de vraag of de probe ze
met rust laat.

**(d) Staat het logboek in rust stil?** Bij de FSC-componenten was de blinde TCP-probe goed voor een
`TLS handshake error` per twee seconden. Met de probe op de monitoring-poort hoort dat er nul te
zijn:

```bash
zadctl -p mpfb-8wh logs fsc-logius -c logius-fscoutway -n 200 --since 10m \
  | grep -c 'handshake error' || true
```

Verwacht `0`. De `|| true` staat er omdat `grep -c` met nul treffers zelf exitcode 1 geeft — precies
bij de uitkomst die je wilt.

## Daarna

Laat de omgeving niet leeg achter: druk nog een keer op **Herstel demo**, zodat de volgende
bezoeker een gevulde demo aantreft.
