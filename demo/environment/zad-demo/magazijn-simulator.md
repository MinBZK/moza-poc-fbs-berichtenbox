# De magazijn-simulator op ZAD

**Status: §1 tot en met §5 zijn uitgevoerd** — §1 tot en met §4 op 2026-08-31 voor de deployment
`test`, §5 op 2026-09-01 in de deploy-workflow. De commando's hieronder zijn dus geen voornemen meer
maar een verslag. Wat er nog te doen is, staat onderaan als vijf af te vinken stappen; daarna kan
MinBZK/MijnOverheidZakelijk#1013 dicht.

Deze stap (MinBZK/MijnOverheidZakelijk#1013) wachtte op MinBZK/MijnOverheidZakelijk#936; dat issue is
gesloten, dus die volgorde staat niets meer in de weg.

**Draai alles vanuit de repository-root.** De commando's hieronder verwijzen naar `demo/generated/…`
en `demo/genereer-magazijnen.py`, dus alleen daar kloppen de paden. `zadctl login` schrijft
`.env.zadctl` in de werkmap van dat moment en leest hem nergens anders — log dus ook vanuit de root
in, anders krijg je "no API key" terwijl je gewoon ingelogd bent. Zie §Vooraf in `README.md`.

## De volgorde die ertoe doet

Twee dingen moeten in deze volgorde, en allebei falen ze hard als je ze omdraait:

1. **Eerst het component definiëren, dan pas een image.** `component add --deployment` eist een
   `--image`, en dat image bestaat pas nadat de deploy-workflow het gebouwd heeft. Laat
   `--deployment` dus weg: dat definieert het component zonder het te draaien, en de deploy-workflow
   hangt het er later aan. Een component aanmaken met een tag die niet bestaat, levert een
   ImagePullBackOff op en in het slechtste geval een door Operations Manager uitgeschakeld
   component. Dat laatste is een deadlock die alleen met het **herscheppen van de deployment**
   doorbroken wordt, en dat is in dit project destructief — lees "Als een component uitstaat" in
   `README.md` vóór je die route inslaat.
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

`DB_POOL_MAX` hoeft er niet bij: de simulator staat zelf al op vijftig connections. Dit is één
service die er honderd voorstelt, dus elke per-service-default komt op een honderdste van zijn
bedoelde last uit; de meting daarachter staat in
`docs/plans/2026-08-21-magazijn-simulator-design.md` onder "Meting (stap 6)".

**Houd die vijftig onder wat de PostgreSQL van het platform per service overhoudt.** Die database is
gedeeld met `magazijna`, `magazijnb` en `democonsole`. Een pool die ruimer is dan de database
toelaat helpt niet en verplaatst de storing alleen: gemeten met 120 op een database van twintig
vielen van zestig gelijktijdige bevragingen er vijf om met "sorry, too many clients already", en die
weigering telt niet mee in de tellers van de pool — van binnen ziet hij er dan gezond uit. Is de
ruimte krapper, verlaag dan `DB_POOL_MAX` en accepteer dat een volle fan-out langzamer wordt; dat is
beter dan een grens raken die zich als een onbereikbaar magazijn voordoet. Wat er werkelijk gebeurt,
staat in de log — zie "Zicht op de connection pool" in `demo/magazijn-simulator/README.md`. Lokaal
staat de database op 200 verbindingen.

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
alleen aliassen kennen `$DEPLOYMENT_NAME`. Een alias is los bij te werken op een bestaand component,
dus `uitvraag` hoeft hier niet voor herschapen te worden:

```bash
zadctl -p mpfb-8wh alias add -c uitvraag \
  'MAGAZIJN_SIMULATOR_URL=https://magazijnsimulator-$DEPLOYMENT_NAME-mpfm-w3h.rig.prd1.gn2.quattro.rijksapps.nl'

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
zadctl env set -c democonsole \
  MAGAZIJN_SIMULATOR_BEHEER_TOKEN=<hetzelfde geheim> \
  SIMULATOR_BEREIKBAAR=true
```

**`SIMULATOR_BEREIKBAAR` is de knop die de groep zichtbaar maakt.** Zolang er geen simulator was
stond hij op `false` en liet het paneel de hele groep "Gesimuleerde magazijnen" weg — knoppen die
gegarandeerd falen kosten tijdens een demo uitleg die niets toevoegt. Vergeet je hem, dan draait de
console met een werkende verbinding naar de simulator terwijl er geen enkele knop te zien is, en
lijkt de bediening simpelweg te ontbreken. `env set` en niet `env add`: stap 3 van `README.md` heeft
beide sleutels al aangemaakt, en `add` ziet een bestaande sleutel als een conflict. Sloeg je die
stap over, dan is het hier eenmalig `env add` — de default van `SIMULATOR_BEREIKBAAR` in de console
is `true`, dus zonder token toont het paneel dan knoppen die elk een 401 opleveren.

**Een alias erbij zetten kan gewoon op een bestaand component**, met `zadctl alias add`. Dat scheelt
hier het herscheppen van `democonsole` — de duurste handeling van de hele operatie. Poorten en
diensten zijn niet getoetst en horen nog steeds in één keer goed te staan.

Zonder de token-variabele wijst niets erop dat het misgaat aan de console-kant: het paneel laadt, de
knoppen staan er, en elke druk levert een 401 uit de simulator.

## 5. De vier ondernemers

De profiel-stubs van de vier ondernemers (`demo/generated/profiel/ondernemer-*.json`) zitten in het
`fbs-demo-profiel`-image — niet in het gedeelde `fbs-externe-stubs`, want de persona's dragen
elfproef-geldige nummers en dat image staat alleen ongeldige toe. De stap `Build + push
externe-stubs` in `deploy.yml` draait het generatiescript vóór `docker build`, kopieert de vier
bestanden naar de build-context (`wiremock/demo-profiel/generated/`, gitignored op de `.gitkeep` na)
en controleert daarna op het draaiende image dat Landelijk Concern werkelijk 100 organisaties
teruggeeft. Zo blijft de fan-out van de gedeelde omgeving gelijk aan die van een laptop, zonder
handwerk per organisatie.

Die stubs hebben voorrang 1 en winnen daarmee van de handgeschreven persona-stubs (voorrang 5), die
alleen de twee echte magazijnen dragen. Dat blijft zo: zonder gegenereerde stubs werkt de demo nog
steeds, met een fan-out van twee.

**Het aantal komt niet uit een repository-variable, maar uit de default van het script (98).** Dat
getal zit vast aan de twee attachments uit §2 en §3, die met de hand geüpload zijn en met hetzelfde
script gegenereerd. Een afwijkend getal in CI levert profielen met scopes naar magazijnen die de
simulator niet kent; de uitvraag slaat die stil over, en de demo draait door met een fan-out die
niemand heeft ingesteld. Wie het getal verandert, regenereert en heruploadt dus ook beide
attachments.

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

Vijf stappen, in deze volgorde: elke volgende gaat uit van de vorige. Vier ervan zijn af te vinken
zonder iets te kiezen; de laatste vraagt één besluit. Staan ze alle vijf, dan kan
MinBZK/MijnOverheidZakelijk#1013 dicht en mag de statusregel van
`docs/plans/2026-08-21-magazijn-simulator-design.md` mee.

### 1. Vaststellen dat de eerste uitrol geland is

§5 wijzigde de workflow; die levert pas een image bij de eerstvolgende merge naar main. Kijk niet
naar de UI-melding maar naar het gerenderde manifest — dat is de grond-waarheid, en een bevroren
ImagePullBackOff-event in "Technische details" kan een oude tag noemen terwijl de sync allang klopt:

```bash
gh api repos/RijksICTGilde/rig-cluster-application-test/contents/odcn-production/mpfm-w3h/test/magazijnsimulator-deployment.yaml \
  --jq '.content' | base64 -d | grep -E '^\s+(replicas|image):'

zadctl -p mpfm-w3h deployment describe test
```

**Klaar wanneer:** `replicas: 1` en een tag van de vorm `main-<sha7>`, en `describe` het component
toont. Staat er `replicas: 0`, lees dan eerst "Als een component uitstaat" in `README.md` — de
herstelroute is destructief en `:refresh` reactiveert niets.

### 2. Bewijzen dat de simulator ook doorkomt

```bash
OIN=$(grep -oE '"[0-9]{20}"' demo/generated/magazijn-simulator.properties | head -1 | tr -d '"')

curl -sS -w '\n%{http_code}\n' -H "X-Ontvanger: KVK:90000003" \
  "https://magazijnsimulator-test-mpfm-w3h.rig.prd1.gn2.quattro.rijksapps.nl/magazijn/$OIN/api/v1/berichten"
```

Neem een OIN die werkelijk in de set van dít component zit — vandaar dat het commando er een uit het
gegenereerde bestand leest in plaats van er een te noemen. Dat de eerste regel gepakt wordt is geen
toeval: volgnummer 1 staat in de gedragsverdeling op *normaal*, dus een fout hier is een echte fout
en niet een magazijn dat hoort te haperen.

**Klaar wanneer:** een `200` met een berichtenlijst (leeg mag, dat is stap 3). Dan staan de ingress,
het pad-prefix en de databaseverbinding. Een `404` betekent dat deze OIN niet in de set van dít
component zit — dan is het attachment uit §2 niet, of met een ander aantal, geüpload; een `503` dat
de pod draait maar zijn database niet vindt.

### 3. De fan-out meten en de uitkomst vastleggen

Doorloop stap 9 van `verify-zad.md` ("De fan-out van de vier ondernemers"). Die stap zegt wat je
vooraf controleert, hoe je het meetscript langs de authorization-wall krijgt, en welke uitkomsten
horen bij de gedragsverdeling.

**Klaar wanneer:** de vier ondernemers 3, 15, 45 en 100 organisaties bevraagd tonen zonder de
waarschuwing over een afwijkend aantal, en de meting als comment onder
MinBZK/MijnOverheidZakelijk#1013 staat.

### 4. Het geheugen op werkelijk gebruik zetten

Lokaal staat de simulator met 98 magazijnen op ongeveer 450 MB. Wat hij hier nodig heeft, hangt af
van de fan-out die stap 3 er net doorheen heeft gehaald — draai dit dus ná die meting, anders stel je
af op een component dat nog niets gedaan heeft:

```bash
zadctl -p mpfm-w3h resource tune --dry-run
zadctl -p mpfm-w3h resource tune
```

**Klaar wanneer:** de aanpassing is uitgerold en het component daarna nog steeds antwoordt (stap 2
herhalen volstaat). Zag je in stap 3 magazijnen omvallen die op *normaal* staan, kijk dan eerst naar
`DB_POOL_MAX` (default 50) en het aantal verbindingen dat de database toelaat — dat is een pool-grens
en geen geheugenprobleem.

### 5. Flyway, en het besluit over de previews

```bash
zadctl -p mpfm-w3h logs test -c magazijnsimulator --since 1h -n 300 | grep -iE 'flyway|migrat'
```

**Klaar wanneer:** de log meldt dat de twee migraties zijn toegepast. Zegt hij dat het schema niet
aangemaakt kan worden, dan mag de databasegebruiker geen `CREATE`: maak het schema uit `DB_SCHEMA`
vooraf aan en zet `quarkus.flyway.create-schemas=false`.

En de vraag die daarna nog openstaat: **krijgen previews hun eigen gevulde simulator, of delen ze die
van `test`?** Met een eigen database per deployment is het eerste vanzelf zo, maar dan moet elke
preview ook gevuld worden. Stel het vast door de console van een lopende preview te openen en onder
*Berichten per magazijn* te kijken of er nul staat; is dat zo, dan is één druk op **Herstel demo** de
hele handeling. Schrijf de uitkomst bij §4, waar de koppeling van een preview aan zijn eigen
simulator staat — anders ontdekt de volgende preview-bezoeker opnieuw dat zijn magazijnen leeg zijn.

**Klaar wanneer:** dat besluit — vullen per preview, of delen — in dit runbook staat.
