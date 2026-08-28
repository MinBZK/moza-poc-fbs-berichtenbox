# De magazijn-simulator op ZAD

**Status: voorbereiding.** Deze stap (MinBZK/MijnOverheidZakelijk#1013) is geblokkeerd door
MinBZK/MijnOverheidZakelijk#936. Zonder bediening en zonder Berichtenbox op de gedeelde omgeving kan
hij technisch slagen — de simulator draait, het aantal organisaties klopt — terwijl er voor een
stakeholder niets te zien is. Dit bestand beschrijft wat er dan te doen staat, en legt de keuzes vast
die nu al te maken zijn. Het is niet uitgevoerd: geen van de commando's hieronder is tegen een
cluster gedraaid.

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
SmallRye vult de expressie in bij het lezen van het register. **Te verifiëren bij de eerste uitrol:**
dat SmallRye die expansie ook toepast op een bestand dat via `SMALLRYE_CONFIG_LOCATIONS` binnenkomt.
Zo niet, dan is het alternatief het register in het uitvraag-image bakken en per deployment
genereren — duurder, want dan hangt de fan-out aan een image-build.

**Een eigen schema in de gedeelde database.** ZAD levert één database en één user per deployment, en
de simulator draagt tabelnamen die ook bij de magazijnen bestaan. `DB_SCHEMA` is daarom verplicht;
zonder die variabele start hij niet (`%prod` heeft geen default).

**`/beheer` is bereikbaar zodra het component publiceert.** Het beheerpad zit op dezelfde poort als
de magazijnen — een component publiceert alleen `ports[0]`, en dat is die poort. De bescherming is
het token: `BEHEER_TOKEN` is buiten dev/test verplicht en de service weigert te starten zonder.
Zet dus een echt geheim, geen demo-waarde.

## 1. Het component aanmaken

Eenmalig, en in één keer compleet: ZAD past aliassen, poorten en diensten alleen bij creatie toe,
niet bij een re-POST op een bestaand component.

```bash
zadctl login
zadctl project use mpfm-w3h

zadctl component add magazijnsimulator \
  --image ghcr.io/minbzk/fbs-magazijn-simulator:main-<sha7> \
  --deployment test \
  --port 8092 \
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
nooit zelf dicht, dus het zou werken — maar een echte probe op `/q/health/ready` wacht ook op Flyway
en de database, en dat is precies wat je bij het vullen wilt weten.

```bash
zadctl env add -c magazijnsimulator \
  DB_SCHEMA=magazijnsimulator \
  BEHEER_TOKEN=<geheim> \
  DB_POOL_MAX=120
```

`DB_POOL_MAX=120` is geen fijnslijperij: dit is één service die er honderd voorstelt, dus elke
per-service-default komt op een honderdste van zijn bedoelde last uit. De meting achter
MinBZK/MijnOverheidZakelijk#1012 staat in
`docs/plans/2026-08-21-magazijn-simulator-design.md` onder "Meting (stap 6)". Let op dat de
PostgreSQL van het platform genoeg verbindingen toelaat; lokaal staat hij daarvoor op 200.

## 2. De set die hij voorstelt

`demo/generated/magazijn-simulator.properties` (twee regels per magazijn: naam en volgnummer) gaat
als attachment mee. Die inhoud is deployment-onafhankelijk, dus hier speelt het
substitutie-probleem niet.

```bash
zadctl attachment add -c magazijnsimulator \
  --file demo/generated/magazijn-simulator.properties \
  --provide-as file --path /config/magazijn-simulator.properties
zadctl env add -c magazijnsimulator \
  SMALLRYE_CONFIG_LOCATIONS=/config/magazijn-simulator.properties
```

Het volgnummer bepaalt het gedrag van elk magazijn — traag, haperend, onbereikbaar. Die verdeling
zit in de simulator zelf en is deterministisch, dus de gedeelde omgeving gedraagt zich hetzelfde als
een laptop. Dat is de bedoeling: een demo die je thuis oefent moet daar hetzelfde doen.

## 3. Het register op de uitvraag

In het uitvraag-project (`mpfb-8wh`), op het component `uitvraag`:

```bash
zadctl project use mpfb-8wh
zadctl attachment add -c uitvraag \
  --file demo/generated/magazijnen-register.properties \
  --provide-as file --path /config/magazijnen-register.properties
```

Daarnaast drie omgevingsvariabelen. `MAGAZIJN_SIMULATOR_URL` moet een alias zijn — alleen aliassen
kennen `$DEPLOYMENT_NAME`:

```bash
# alias, bij de creatie van het component of via een hercreatie:
#   MAGAZIJN_SIMULATOR_URL: https://magazijnsimulator-$DEPLOYMENT_NAME-mpfm-w3h.rig.prd1.gn2.quattro.rijksapps.nl

zadctl env add -c uitvraag \
  SMALLRYE_CONFIG_LOCATIONS=/config/magazijnen-register.properties \
  BERICHTENSESSIECACHE_MAGAZIJN_BULKHEAD_MAX_CONCURRENT=120
```

Die laatste is de knop uit de meting: bij de standaardwaarde van twintig krijgt een ondernemer met
honderd organisaties er twintig te zien en tachtig afwijzingen. Wat er in het echt hoort te gebeuren
staat als MinBZK/MijnOverheidZakelijk#1038 op de backlog; tot die tijd zet de demo de grens boven de
grootste fan-out.

Staat er al een `SMALLRYE_CONFIG_LOCATIONS` op het component, voeg dan toe in plaats van te
vervangen — de waarde is een lijst.

## 4. De vier ondernemers

De profiel-stubs van de vier ondernemers (`demo/generated/profiel/ondernemer-*.json`) horen in het
`fbs-externe-stubs`-image; het generatiescript draait dan in CI vóór `docker build`, met het aantal
magazijnen uit een repository-variable. Zo blijft de fan-out van de gedeelde omgeving gelijk aan die
van een laptop zonder dat er per organisatie handwerk bij komt.

Die stubs hebben voorrang 1 en winnen daarmee van de handgeschreven persona-stubs (voorrang 5), die
alleen de twee echte magazijnen dragen. Dat blijft zo: zonder gegenereerde stubs werkt de demo nog
steeds, met een fan-out van twee.

## 5. Netwerkregels

Cluster-intern verkeer naar een ánder project loopt over een `cross-domain-access`-regel, en zo'n
regel noemt altijd één concrete peer-deployment — een regel met een open kant wordt bij het
genereren overgeslagen. Eén regel opent bovendien één poort op één component bij één peer, dus elke
hop is een eigen regel, per deployment bijgeschreven.

Voor de simulator is dat er één die telt: `uitvraag` (`mpfb-8wh`) naar `magazijnsimulator`
(`mpfm-w3h`), poort 8092. Het bedieningspaneel zit in hetzelfde project als de simulator en heeft
dus geen regel nodig.

Wil je de uitvraag over de publieke ingress laten praten in plaats van cluster-intern, dan volgt het
adres wél de preview (dat is precies wat de alias hierboven doet) en is er geen netwerkregel nodig.
Dat is de goedkopere route, met als prijs dat honderd bevragingen per ophaalronde over de ingress
gaan. Begin daarmee; verplaats het naar cluster-intern verkeer zodra dat knelt.

## 6. Daarna: de deploy-workflow

Pas als het component bestaat, kan `.github/workflows/deploy.yml` de image-tag gaan bijwerken. Dat is
één build-job voor `fbs-magazijn-simulator` (jib, zoals de andere services) en één extra regel in de
`components`-payload van `deploy-test-magazijnen` en `deploy-preview-magazijnen`. Eerder toevoegen
werkt niet: de deploy-action wijst een niet-bestaand component af.

## Wat er dan nog te doen is

- Nagaan hoeveel geheugen het component nodig heeft. Lokaal staat de simulator met 98 magazijnen op
  ongeveer 450 MB; `zadctl resource tune` stelt het bij op werkelijk gebruik.
- De verificatie uit `verify-zad.md` uitbreiden met de fan-out: vier ondernemers, 3 / 15 / 45 / 100
  organisaties, gemeten met `demo/meet-fanout.sh` tegen de ZAD-URL.
- Bepalen of previews hun eigen gevulde simulator krijgen of die van `test` delen. Met een eigen
  database per deployment is het eerste vanzelf zo, maar dan moet elke preview ook gevuld worden —
  de vul-knop op het bedieningspaneel doet dat, en dat is één handeling.
