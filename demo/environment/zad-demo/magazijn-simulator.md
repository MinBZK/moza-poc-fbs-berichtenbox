# De magazijn-simulator op ZAD

**Status: §1 tot en met §4 zijn op 2026-08-31 uitgevoerd voor de deployment `test`.** Wat er nog
open staat, staat onderaan. De commando's hieronder zijn dus geen voornemen meer maar een verslag —
op §5 na, die op het stubs-image wacht.

Deze stap (MinBZK/MijnOverheidZakelijk#1013) wachtte op MinBZK/MijnOverheidZakelijk#936; dat issue is
gesloten, dus die volgorde staat niets meer in de weg.

**Draai alles vanuit deze map.** `zadctl login` schrijft `.env.zadctl` in de werkmap van dat moment
en leest hem nergens anders; vanuit de repository-root krijg je "no API key" terwijl je gewoon
ingelogd bent.

## De volgorde die ertoe doet

Twee dingen moeten in deze volgorde, en allebei falen ze hard als je ze omdraait:

1. **Eerst het component definiëren, dan pas een image.** `component add --deployment` eist een
   `--image`, en dat image bestaat pas nadat de deploy-workflow het gebouwd heeft. Laat
   `--deployment` dus weg: dat definieert het component zonder het te draaien, en de deploy-workflow
   hangt het er later aan. Een component aanmaken met een tag die niet bestaat, levert een
   ImagePullBackOff op en in het slechtste geval een uitgeschakeld component dat alleen met
   verwijderen-en-opnieuw-aanmaken terugkomt.
2. **Eerst de alias op de uitvraag, dan het register.** Het register bevat
   `${MAGAZIJN_SIMULATOR_URL}` en SmallRye vult dat in bij het lezen. Staat de variabele er nog niet,
   dan start de uitvraag niet meer — `SRCFG00011: Could not expand value` — en ligt de hele
   gedeelde keten plat.

De simulator komt als één component `magazijnsimulator` in het magazijnen-project `mpfm-w3h`, naast
`magazijna`, `magazijnb` en `democonsole`. Waarom daar: `postgresql-database` is
deployment-gebonden, en het bedieningspaneel dat de simulator vult en leegt staat er al. Een eigen
deployment zou beide kwijtraken.

## Wat er anders is dan bij de andere componenten

**De simulator draait niet door FSC.** Dat blijft op de twee echte magazijnen. Eén gesimuleerd
magazijn als extra dienst op een bestaande inway is later een goedkope toevoeging — één dienst, één
contract, één grant-hash — maar honderd grant-hashes en honderd handmatige omgevingsvariabelen
schalen niet.

**Het register is te groot voor omgevingsvariabelen.** Bij 98 magazijnen zijn dat 196 regels. Ze
gaan als attachment mee met `provide-as: file`, plus `SMALLRYE_CONFIG_LOCATIONS` op de uitvraag.
Precedent: `logius-internal-ca-root-cert` staat er al zo op.

**Een attachment wordt ongewijzigd gemount.** Er is dus geen `$DEPLOYMENT_NAME`-substitutie, en een
register met een hard adres zou in élke preview naar de simulator van `test` wijzen. Daarom schrijft
`demo/genereer-magazijnen.py` bij een gezette `SIMULATOR_URL` geen adres maar een
configuratie-expressie:

```bash
SIMULATOR_URL='${MAGAZIJN_SIMULATOR_URL}' DEMO_MAGAZIJNEN=98 python3 demo/genereer-magazijnen.py
```

Dat levert regels als `magazijnen."<OIN>".url=${MAGAZIJN_SIMULATOR_URL}/magazijn/<OIN>`. De
variabele komt uit een alias op het uitvraag-component, en aliassen kennen `$DEPLOYMENT_NAME` wél;
SmallRye vult de expressie in bij het lezen van het register. Dat die expansie ook geldt voor een
bestand uit `SMALLRYE_CONFIG_LOCATIONS` is lokaal vastgesteld: een uitvraag die zo'n register kreeg
zónder de variabele weigerde te starten met `SRCFG00011: Could not expand value
MAGAZIJN_SIMULATOR_URL`. Hij probeert de expansie dus wel degelijk, en faalt luidruchtig als de
variabele ontbreekt in plaats van het adres letterlijk te nemen.

**Een eigen schema in de gedeelde database.** ZAD levert één database en één user per deployment, en
de simulator draagt tabelnamen die ook bij de magazijnen bestaan. `DB_SCHEMA` is daarom verplicht;
zonder die variabele start hij niet (`%prod` heeft geen default).

**Publiceren zet meer open dan alleen het beheerpad.** Een component publiceert `ports[0]`, en
daarop zitten zowel `/magazijn/<OIN>/api/v1/berichten` als `/beheer`. Voor het beheerpad is de
bescherming het token: `BEHEER_TOKEN` is buiten dev en test verplicht en de service weigert te
starten zonder. Zet dus een echt geheim, geen demo-waarde, en zet dezelfde waarde op de
console — die stuurt hem mee als `X-Beheer-Token`, anders geeft elke knop een 401 terwijl de keten
verder gezond is.

Het magazijnpad zelf staat er dan onbeschermd naast: wie de URL en een `X-Ontvanger`-header heeft,
haalt de demoberichten op. Een `authorization-wall` zoals de console die heeft, kan hier niet — de
uitvraag komt over diezelfde ingress binnen en zou er zelf op stuklopen. Dat is te verdedigen zolang
er uitsluitend demodata in staat, en dat is precies wat de simulator vult; maar het is een keuze en
geen gegeven. Zet er nooit echte persoonsgegevens in.

## 1. Het component aanmaken

Poorten en diensten horen in één keer goed te staan: een re-POST op een bestaand component draagt
ze niet opnieuw. Aliassen en omgevingsvariabelen zijn wél later bij te stellen — zie §4.

Zonder `--deployment` wordt het component alleen gedefinieerd en draait er nog niets. Dat is precies
wat we willen: het image bestaat nog niet, en de deploy-workflow hangt het component er straks aan.

```bash
zadctl login
zadctl project use mpfm-w3h

zadctl component add magazijnsimulator \
  --ports 8092 \
  --service postgresql-database \
  --service publish-on-web \
  --service health-check \
  --aliases '
DB_JDBC_URL: jdbc:postgresql://$DATABASE_SERVER_HOST:5432/$DATABASE_DB
DB_USERNAME: $DATABASE_SERVER_USER
DB_PASSWORD: $DATABASE_PASSWORD
'
```

`health-check` staat er bewust bij: zonder die dienst probeert Kubernetes een TCP-socket op de eerste
poort, met een liveness-probe die de pod na anderhalve minuut herstart. De simulator zet die poort
nooit zelf dicht, dus het zou werken — maar een echte probe wacht ook op Flyway en de database, en
dat is precies wat je bij het vullen wilt weten.

De dienst binden is niet genoeg: zonder configuratie staan schema, poort en paden leeg.

```bash
zadctl service config set health-check -c magazijnsimulator \
  --set scheme=http --set port=8092 \
  --set liveness-path=/q/health/live --set readiness-path=/q/health/ready
```

Liveness op `/q/health/live` en niet op `/q/health/ready`: dat laatste zakt mee met de database, en
dan herstart Kubernetes een pod die alleen maar wacht.

```bash
zadctl env add -c magazijnsimulator \
  DB_SCHEMA=magazijnsimulator \
  BEHEER_TOKEN=<geheim>
```

`DB_POOL_MAX` hoeft er niet bij: de simulator staat zelf al op 120 connecties. Dit is één service die
er honderd voorstelt, dus elke per-service-default komt op een honderdste van zijn bedoelde last uit;
de meting daarachter staat in `docs/plans/2026-08-21-magazijn-simulator-design.md` onder
"Meting (stap 6)".

**Ga wel vóór de eerste vulronde na hoeveel verbindingen de PostgreSQL van het platform toelaat.** Die
database is gedeeld met `magazijna`, `magazijnb` en `democonsole`, en 120 connecties voor één
component is dan een reëel beslag. Is de grens te krap, verlaag dan `DB_POOL_MAX` en accepteer dat
een volle fan-out langzamer wordt — dat is beter dan een grens raken die zich als een onbereikbaar
magazijn voordoet. Lokaal staat de database daarvoor op 200 verbindingen.

## 2. De set die hij voorstelt

`demo/generated/magazijn-simulator.properties` (twee regels per magazijn: naam en volgnummer) gaat
als attachment mee. Die inhoud is deployment-onafhankelijk, dus hier speelt het
substitutie-probleem niet.

Toevoegen en toewijzen zijn twee stappen: `add` legt het bestand in het project, `assign` hangt het
aan een component en bepaalt hoe het binnenkomt.

```bash
zadctl attachment add magazijn-simulator-set \
  --from-file demo/generated/magazijn-simulator.properties
zadctl attachment assign magazijn-simulator-set -c magazijnsimulator \
  --provide-as file --mount-path /config/magazijn-simulator.properties
zadctl env add -c magazijnsimulator \
  SMALLRYE_CONFIG_LOCATIONS=/config/magazijn-simulator.properties
```

Het volgnummer bepaalt het gedrag van elk magazijn — traag, haperend, onbereikbaar. Die verdeling
zit in de simulator zelf en is deterministisch, dus de gedeelde omgeving gedraagt zich hetzelfde als
een laptop. Dat is de bedoeling: een demo die je thuis oefent moet daar hetzelfde doen.

## 3. Het register op de uitvraag

**Zet eerst de alias, dan pas het register** — zie de volgorde bovenaan. In het uitvraag-project
(`mpfb-8wh`), op het component `uitvraag`:

```bash
zadctl project use mpfb-8wh
zadctl attachment add magazijnen-register \
  --from-file demo/generated/magazijnen-register.properties
zadctl attachment assign magazijnen-register -c uitvraag \
  --provide-as file --mount-path /config/magazijnen-register.properties
```

Daarnaast twee omgevingsvariabelen, plus een alias. `MAGAZIJN_SIMULATOR_URL` móet een alias zijn:
alleen aliassen kennen `$DEPLOYMENT_NAME`, en aliassen worden alleen bij de creatie van een component
toegepast — bestaat `uitvraag` al zonder, dan is hercreëren de enige route.

```bash
# alias, bij de creatie van het component of via een hercreatie:
#   MAGAZIJN_SIMULATOR_URL: https://magazijnsimulator-$DEPLOYMENT_NAME-mpfm-w3h.rig.prd1.gn2.quattro.rijksapps.nl

zadctl env set -c uitvraag \
  SMALLRYE_CONFIG_LOCATIONS=/config/magazijnen-register.properties \
  BERICHTENSESSIECACHE_MAGAZIJN_BULKHEAD_MAX_CONCURRENT=120
```

Die laatste is de knop uit de meting: bij de standaardwaarde van twintig krijgt een ondernemer met
honderd organisaties er twintig te zien en tachtig afwijzingen. Wat er in het echt hoort te gebeuren
staat als MinBZK/MijnOverheidZakelijk#1038 op de backlog; tot die tijd zet de demo de grens boven de
grootste fan-out.

`env set` en niet `env add`: die laatste ziet een bestaande sleutel als een conflict en breekt af.
Draagt het component al een `SMALLRYE_CONFIG_LOCATIONS`, zet dan de samengevoegde lijst — de waarde
is een lijst, en overschrijven zou de bestaande bron eruit gooien.

## 4. Het bedieningspaneel laten weten waar hij staat

De console vult en leegt de simulator, en die twee moeten elkaar kunnen vinden. Ze zitten in
hetzelfde project, dus dat kan cluster-intern. `MAGAZIJN_SIMULATOR_URL` moet een alias zijn: een
gewone omgevingsvariabele kan `$DEPLOYMENT_NAME` niet invullen en zou elke preview naar de simulator
van `test` sturen.

```bash
zadctl project use mpfm-w3h
zadctl alias add -c democonsole 'MAGAZIJN_SIMULATOR_URL=http://$DEPLOYMENT_NAME-magazijnsimulator:8092'
zadctl env add -c democonsole MAGAZIJN_SIMULATOR_BEHEER_TOKEN=<hetzelfde geheim>
```

**Een alias erbij zetten kan gewoon op een bestaand component**, met `zadctl alias add`. Het
zusterrunbook (`README.md` §2) schrijft dat aliassen alleen bij creatie worden toegepast en dat
verwijderen-en-opnieuw-aanmaken de enige route is; voor aliassen klopt dat niet meer. Dat scheelt
hier het herscheppen van `democonsole` — de duurste handeling van de hele operatie. Poorten en
diensten zijn niet getoetst en horen nog steeds in één keer goed te staan.

Zonder de token-variabele wijst niets erop dat het misgaat aan de console-kant: het paneel laadt, de
knoppen staan er, en elke druk levert een 401 uit de simulator.

## 5. De vier ondernemers

De profiel-stubs van de vier ondernemers (`demo/generated/profiel/ondernemer-*.json`) horen in het
`fbs-externe-stubs`-image; het generatiescript draait dan in CI vóór `docker build`, met het aantal
magazijnen uit een repository-variable. Zo blijft de fan-out van de gedeelde omgeving gelijk aan die
van een laptop zonder dat er per organisatie handwerk bij komt.

Die stubs hebben voorrang 1 en winnen daarmee van de handgeschreven persona-stubs (voorrang 5), die
alleen de twee echte magazijnen dragen. Dat blijft zo: zonder gegenereerde stubs werkt de demo nog
steeds, met een fan-out van twee.

## 6. Netwerkregels

Cluster-intern verkeer naar een ánder project loopt over een `cross-domain-access`-regel, en zo'n
regel noemt altijd één concrete peer-deployment — een regel met een open kant wordt bij het
genereren overgeslagen. Eén regel opent bovendien één poort op één component bij één peer, dus elke
hop is een eigen regel, per deployment bijgeschreven.

**Voor de uitvraag naar de simulator is er geen regel, en die keuze staat vast.** Het register van de
uitvraag eist https buiten dev en test (`ConfigMagazijnregister`, fail-fast bij boot), en
cluster-intern verkeer is http — een adres als `http://<deployment>-magazijnsimulator:8092` laat de
uitvraag dus niet meer starten. De alias uit §3 wijst daarom naar de publieke ingress, en die volgt
de preview vanzelf. De prijs is dat honderd bevragingen per ophaalronde over de ingress gaan.

Het bedieningspaneel zit wél in hetzelfde project als de simulator; dat verkeer blijft binnen de
deployment en heeft geen regel nodig.

## 7. Daarna: de deploy-workflow

Pas als het component bestaat, kan `.github/workflows/deploy.yml` er een image naartoe sturen. Dat is
gedaan in dezelfde PR als dit runbook: `build-democonsole` heet nu `build-demo-images` en bouwt beide
demo-modules in één Maven-aanroep, en `magazijnsimulator` staat in de `components`-payload van
`deploy-test-magazijnen` en `deploy-preview-magazijnen`. Die payload is ook wat het component aan de
deployment hangt — daarom hoefde `component add` geen `--deployment`.

Bij de eerste uitrol na de merge verschijnt het component in `test`; daarna klonen previews het mee
via `clone-from: test`.

## Wat er nog open staat

- **§5, de vier ondernemers.** De persona-mappings moeten in het `fbs-externe-stubs`-image gebakken
  worden. Zolang dat niet gebeurd is, kennen de persona's op de gedeelde omgeving alleen de twee
  echte magazijnen: het register is er dan wel, maar niemands profiel verwijst naar de gesimuleerde
  magazijnen. De demo werkt, alleen de fan-out ontbreekt.
- **De eerste uitrol afwachten en verifiëren.** Het component is gedefinieerd maar draait nog niet;
  `zadctl deployment describe test` toont hem pas nadat de deploy-workflow een image heeft geleverd.
  Breid `verify-zad.md` daarna uit met de fan-out: vier ondernemers, 3 / 15 / 45 / 100 organisaties,
  gemeten met `demo/meet-fanout.sh` tegen de ZAD-URL.
- Nagaan hoeveel geheugen het component nodig heeft. Lokaal staat de simulator met 98 magazijnen op
  ongeveer 450 MB; `zadctl resource tune` stelt het bij op werkelijk gebruik.
- Bepalen of previews hun eigen gevulde simulator krijgen of die van `test` delen. Met een eigen
  database per deployment is het eerste vanzelf zo, maar dan moet elke preview ook gevuld worden —
  de vul-knop op het bedieningspaneel doet dat, en dat is één handeling.
- Nagaan of Flyway het schema zelf mag aanmaken. Dat doet hij standaard, mits de databasegebruiker
  `CREATE` mag; zo niet, dan het schema vooraf aanmaken en `quarkus.flyway.create-schemas=false`
  zetten.
