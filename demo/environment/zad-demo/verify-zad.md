# De demo op ZAD verifiëren

Zeven stappen na de eenmalige creatie uit `README.md`. Ze staan in de volgorde waarin een fout de
volgende stap onbruikbaar maakt, dus stop bij de eerste die niet klopt.

Alles gaat door de authorization-wall, dus doe dit in een browser waarin je bent ingelogd. Een
`curl` zonder sessie krijgt HTTP 403 met de inlogpagina terug — dat is de muur, niet een kapot
component.

```
CONSOLE=https://democonsole-test-mpfm-w3h.rig.prd1.gn2.quattro.rijksapps.nl
```

## 0. Staat de muur

```bash
curl -s -o /dev/null -w '%{http_code}\n' "$CONSOLE/"
```

Verwacht `403`. Krijg je `200`, stop dan: het paneel staat open op het internet en iedereen die het
adres kent kan de magazijnen legen. `keycloak` is dan niet aan het component gebonden — zie stap 2
van `README.md`.

## 1. De omgeving beschrijft zichzelf

Open `$CONSOLE/api/demo/omgeving`.

Verwacht:

- `uitvraagBasis` wijst naar de publieke uitvraag van dezelfde deployment, **inclusief** `/api/v1`.
  Zonder dat pad faalt elke aanroep vanaf de Berichtenbox-pagina zichtbaar voor de gebruiker.
- `storingen` bevat precies `aanmeld`, `notificatie`, `profiel` en `redis`, zodra stap 6 van
  `README.md` gedaan is. `magazijn-a` en `magazijn-b` horen er níet in te staan: die wachten op de
  magazijn-simulator (#938) en hun knop zou gegarandeerd falen.
- `sessiecache` is `true` zodra stap 5 van `README.md` gedaan is.
- `berichtenboxUrl` wijst naar de proeftuin van dezelfde deployment, inclusief het pad
  `/moza/berichtenbox/`. Leeg betekent dat het paneel het lokale pad probeert, dat hier niet
  bestaat: het frame blijft dan leeg.

Staat er een proxy in `storingen` die er niet hoort, dan is de bijbehorende `TOXIPROXY_*_URL` niet
leeg gezet. Ontbreekt er één die er wél hoort, dan is zijn alias niet aangekomen.

## 2. Vullen

Open `$CONSOLE/` en druk op **Herstel demo**. Dat stopt de stroom, reset de storingen, leegt de
magazijnen en laadt de basisvulling — in die volgorde.

Verwacht: een antwoord zonder fout, en daarna op **Status (aantal berichten)** een aantal groter dan
nul per magazijn.

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

De vier knoppen staan in de Storingen-sectie. Doe ze niet allemaal tegelijk: elke knop hoort een
eigen, herkenbaar effect te hebben.

**Zet `profiel` uit** en druk daarna op **Opvoeren**. Verwacht: de aanlevering faalt, want het
magazijn kan de voorkeuren van de ontvanger niet ophalen. Druk op **Alles normaal (reset)** en voer
opnieuw op — nu slaagt het.

**Zet `redis` traag** en haal in de Berichtenbox berichten op. Verwacht: het ophalen duurt zichtbaar
langer (de toxic voegt zes seconden toe) en slaagt daarna alsnog.

Verwacht bij elke knop een antwoord zonder fout. Een **verbindings- of timeoutfout** betekent dat de
`cross-domain-access`-regel voor deze deployment ontbreekt — de console komt dan niet bij de
admin-API. Een **404 op de proxy** betekent dat de proxy nog niet is aangemaakt; de console doet dat
zelf, maar pas nadat hij de Toxiproxy kan bereiken, dus dat wijst op dezelfde ontbrekende regel.

**Controleer daarna de upstream.** Dit is de stap die de stilste fout vangt: een preview die het
verkeer van `test` afhandelt. Open op een preview de proxy-lijst en kijk waar hij heen wijst:

```bash
curl -s "http://<toxiproxy>:8474/proxies" | grep upstream
```

De upstream moet de deployment van *deze* preview noemen (`pr-<n>-profiel`), niet `test-profiel`.
Klopt dat niet, dan is `TOXIPROXY_*_UPSTREAM` als gewone env-var gezet in plaats van als alias —
alleen een alias vult `$DEPLOYMENT_NAME` in.

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
curl -s -o /dev/null -w '%{http_code}\n' \
  "https://demopersonas-<deployment>-mpfm-w3h.rig.prd1.gn2.quattro.rijksapps.nl/api/demo/personas"
```

`200` hoort. `403` betekent dat er per ongeluk een `authorization-wall` op dat component staat — de
berichtenbox haalt dit pad server-side op en heeft geen sessie. `404` of niets betekent dat het
component er niet staat of zijn poort niet publiceert. `503` betekent dat het component er wél
staat maar niets luistert: kijk dan eerst welk image eronder hangt (`zadctl deployment describe
<deployment>`) — een image dat op een andere poort luistert dan de 8098 die het component
publiceert, komt nooit omhoog. Dat is ook wat je ziet zolang er nog een tijdelijk image onder hangt
dat op de eerste uitrol wacht.

Let op de tweede orde: wijst `BACKEND_DEMO` van de proeftuin naar een dienst die niet luistert, dan
geeft óók `proeftuin-<deployment>-…/api/demo/personas` een 503. De fout zit dan niet in de
proeftuin.

Klopt dat adres wel, toets dan hetzelfde pad via de proeftuin — dat is de route die de berichtenbox
zelf loopt, en die hangt aan `BACKEND_DEMO`:

```bash
curl -s -o /dev/null -w '%{http_code}\n' \
  "https://proeftuin-<deployment>-mpfm-w3h.rig.prd1.gn2.quattro.rijksapps.nl/api/demo/personas"
```

Een `403` hier terwijl de dienst zelf `200` geeft, betekent dat `BACKEND_DEMO` nog naar
`democonsole` wijst.

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

Wat de pod wél vervangt, is een uitrol die het manifest verandert. De eenvoudigste gelegenheid is er
al: **elke merge naar main** rolt de Toxiproxy-componenten opnieuw uit. Kijk daarna in de
console-log:

```bash
zadctl -p mpfm-w3h logs test -c democonsole -n 60 | grep -i "Proxy .* aangemaakt"
```

Verwacht: vier regels `Proxy <naam> aangemaakt: <listen> -> <deployment>-<upstream>`, binnen een
halve minuut na de herstart van de Toxiproxy-pod. Blijft dat uit, kijk dan op
`Proxy ... niet aan te maken` — een verbindings- of timeoutfout wijst op de netwerkregel, en een
waarschuwing over een ontbrekende listen of upstream op de configuratie.

Wil je het los van een merge afdwingen, zet dan de image van één Toxiproxy-component op een ándere
verwijzing en weer terug; dat verandert het manifest en vervangt de pod.

## Daarna

Laat de omgeving niet leeg achter: druk nog een keer op **Herstel demo**, zodat de volgende
bezoeker een gevulde demo aantreft.
