**Status:** Uitgevoerd

# Een gezondheidscontrole die klopt, per component — plan

**Issue:** MinBZK/MijnOverheidZakelijk#1061. Neemt MinBZK/MijnOverheidZakelijk#981 (de ruis op de
FSC-outway) mee: zonder een werkende probe voor de TLS-familie blijft elf van de zevenentwintig
componenten een open vraag, en dan dekt dit werk zijn eigen acceptatiecriterium niet.

ZAD kent de platform-dienst `health-check`. Zonder die dienst rendert het platform drie probes —
`startupProbe`, `livenessProbe`, `readinessProbe` — als blinde TCP-socket op `ports[0]`. Dat is voor
een deel van onze componenten precies verkeerd, voor een ander deel toevallig goed, en voor geen
enkel component een opgeschreven keuze. Dit plan maakt van alle zevenentwintig een keuze, legt die
vast, en zet ze op de drie `test`-deployments.

## Wat we vooraf hebben nagemeten

Vier dingen die het ontwerp bijstellen. Alle vier af te lezen uit de gerenderde manifests in
`RijksICTGilde/rig-cluster-application-test` — dat is wat Argo daadwerkelijk synct, en dus de
grond-waarheid boven de OM-UI.

**De `health-check`-dienst vult drie probes, niet één.** `liveness-path` voedt zowel de
`startupProbe` als de `livenessProbe`; `readiness-path` alleen de `readinessProbe`. De cadans staat
vast en is niet instelbaar:

| Probe | Interval | Drempel | Betekenis |
|---|---|---|---|
| `startupProbe` | 5s, na 5s | 36× | ruim drie minuten opstartbudget vóór liveness begint te tellen |
| `livenessProbe` | 30s, na 5s | 3× | ~90 seconden falen → herstart |
| `readinessProbe` | 2s, direct | 3× | ~6 seconden falen → geen verkeer meer |

Dat opstartbudget van ruim drie minuten is het antwoord op het acceptatiecriterium over migraties: zolang
de `startupProbe` loopt, herstart liveness niets. Flyway en de vulactie passen daarbinnen.

**De configuratie komt mee in een preview.** Het issue laat dit open. Nagemeten op
`toxiproxy-redis`: `mpfb-8wh/test/toxiproxy-redis-deployment.yaml` en
`mpfb-8wh/pr-290/toxiproxy-redis-deployment.yaml` dragen dezelfde drie httpGet-probes op 8474
`/version`. De dienstconfiguratie geldt dus in elke deployment die het component draait, en het runbook hoeft
er geen extra stap voor te dragen — wél de vaststelling, zodat niemand het opnieuw uitzoekt.

**De inventaris uit het issue klopt niet helemaal.** Drie afwijkingen, alle drie uit de gerenderde
manifests:

- `logius-fscctl` en `magazijna-fscctl` dragen `ports: [8080, 9443, 9444]` en worden dus op 8080
  geprobed — de controller-UI, plain HTTP. Daar is geen TLS-ruis, en een httpGet is er direct
  mogelijk zonder poortwijziging.
- `democonsole` draait twee containers: de app op 8095 en de authorization-wall (oauth2-proxy) op
  4180 met een eigen `httpGet /ping`. De `health-check`-dienst raakt de app-container; de wall houdt
  zijn eigen probe.
- `logius-fscbootstrap` en `magazijna-fscbootstrap` hebben geen inbound-poort en krijgen daardoor nu
  al géén probe gerenderd. `scheme=none` verandert daar niets aan het manifest; het legt de keuze
  vast.

**Twee dingen bleven onbewezen tot de eerste apply**, en de opzet hing van beide af. Poorten,
aliassen en diensten worden toegepast bij component-*creatie*; een tweede `component add` laat ze
staan. Of `zadctl service assign` op een bestaand component wél doorkomt, was daarmee een open
vraag — de dry-run wees de goede kant op (de configuratie gaat naar
`PUT /v2/projects/{p}/services/health-check/config/component/{c}`, een eigen laag bij OM), maar
bewijzen kon alleen een manifest dat verandert. Hetzelfde gold voor de tweede aanname, die alleen de
FSC-regels raakt: dat ZAD een probe rendert op een poort die niet in `ports.inbound` staat. Beide
zijn bij de eerste apply bevestigd — zie "Uitkomst van de eerste apply" onderaan.

## De inventaris, en de keuze per component

Zevenentwintig componenten, zes groepen. De stand die per groep beschreven wordt, is de gerenderde
stand op 2026-09-05.

### Groep 1 — De zes Kotlin/Quarkus-componenten

`uitvraag` (8086, `mpfb-8wh`), `magazijna` en `magazijnb` (8090, `mpfm-w3h`), `democonsole` (8095),
`demopersonas` (8098). Nu alle vijf blinde TCP; `magazijnsimulator` (8092) staat al goed en dient als
voorbeeld.

Keuze: `scheme=http`, `port=<eigen poort>`, `liveness-path=/q/health/live`,
`readiness-path=/q/health/ready`.

Liveness op `/q/health/live` en niet op `/q/health/ready`: readiness zakt mee met de datasource en de
berichtenopslag, en op liveness gezet zou een component herstarten dat alleen maar op zijn database
wacht — precies de storing die de controle moest opmerken, zelf veroorzaakt.

**De open keuze die deze PR expliciet maakt: readiness op `uitvraag` zakt mee met Redis.**
`quarkus-redis-client` levert standaard een readiness-check, en `REDIS_HOSTS` van de uitvraag loopt
op ZAD via `toxiproxy-redis`. Zet je de Redis-storingsknop uit, dan wordt de uitvraag binnen ~6
seconden `NotReady`, valt hij uit de endpoints, en antwoordt de ingress 503 in plaats van dat de
applicatie zelf een degradatie laat zien. Dat is bewust geaccepteerd: zonder berichtenopslag kán het
systeem niet functioneren, en 503 is wat er in productie zou gebeuren. Het staat als te bevestigen
keuze in de PR-beschrijving en in het runbook, met het alternatief erbij (een eigen health-group die
de bewust-breekbare afhankelijkheden uitsluit) voor het geval de demo de degradatie tóch wil tonen.

`democonsole` sluit zijn twee magazijn-datasources uit van de check
(`quarkus.datasource.<naam>.health-exclude=true`) en heeft er zelf geen. Wat overblijft in
readiness is Redis — het enige waar het paneel niet zonder kan. Dat is wat we willen; de
uitsluiting blijft staan, zodat een magazijn dat wegvalt het paneel niet uit de endpoints haalt.

### Groep 2 — De vier Toxiproxy's

`toxiproxy-aanmeld`, `toxiproxy-redis` (`mpfb-8wh`), `toxiproxy-profiel`, `toxiproxy-notificatie`
(`mpfpsm-lcl`). Staan al op `http` 8474 `/version`, liveness én readiness.

Keuze: ongewijzigd, en vastgelegd waarom. De probe wijst naar de admin-API en niet naar de stroom,
zodat een dichtgezette knop de pod niet herstart en de andere proxies niet meeneemt. Readiness op
8474 hoort daar juist bij: de pod blijft `Ready` terwijl de proxy dicht is, de router antwoordt 503,
en het magazijn ziet een dienst die wegviel — wat de demo wil laten zien.

### Groep 3 — De twee WireMock-stubs

`profiel` en `notificatie` (8080, `mpfpsm-lcl`), nu blinde TCP.

Keuze: `scheme=http`, `port=8080`, beide paden `/__admin/health`. Het image is
`wiremock/wiremock:3.13.2`; dat endpoint wordt vóór de stub-mappings afgehandeld, dus een catch-all
mapping kan het niet overnemen — en de mappings onder `wiremock/` definiëren er sowieso geen.
Nagemeten tegen de gepinde digest uit `wiremock/externe-stubs/Dockerfile`: HTTP 200 met
`{"status":"healthy"}`. (`/` geeft 403, dus dat is geen alternatief.)

### Groep 4 — Wat geen HTTP spreekt

`redis` (6379, `mpfb-8wh`), `logius-fscpg` en `magazijna-fscpg` (5432).

Keuze: expliciet `scheme=tcp` op de eigen poort. Het gerenderde manifest verandert niet; de keuze
wordt opgeschreven in plaats van overgeërfd van de standaard. Een TCP-connect op Redis en PostgreSQL
is bovendien een eerlijke probe: beide protocollen beginnen met een connect die het serverproces
zelf accepteert, en geen van beide logt een afgebroken poging als fout.

`proeftuin` (8080, het image van MinBZK/moza-poc) valt in dezelfde groep, maar om een andere reden:
zijn `/health` proxyt naar een chat-backend die in dit project niet bestaat, dus een httpGet daarop
faalt gegarandeerd en herstart de pod na anderhalve minuut. Expliciet `tcp`, met die reden erbij.

### Groep 5 — De FSC-familie op TLS

`logius-fsc{mgr,ctl,inway,outway,txlog}` en `magazijna-fsc{mgr,ctl,inway,txlog}` — negen componenten,
waarvan er zeven op hun eerste poort (8443) TLS spreken; de twee controllers luisteren daar met
plain HTTP op 8080. De standaardcontrole opent daar elke twee seconden een
socket en sluit hem meteen weer, wat de Go-server logt als `http: TLS handshake error ... EOF` — een
regel per twee seconden die geen fout is (#981).

Keuze: `scheme=http` op de monitoring-poort (8080 voor de manager, 8081 voor de rest),
`liveness-path=/health/live`, `readiness-path=/health/ready`.

#981 concludeerde dat er op de monitoring-poort niets te vinden was, maar toetste `/health`,
`/healthz`, `/ready` en `/livez`. De paden die de FSC-images wél bedienen zijn `/health/live` en
`/health/ready` — te zien in de stringtabel van alle vijf de binaries (`manager`, `controller`,
`inway`, `outway`, `txlog-api`), inclusief de format-strings `http://%s/health/live` waarmee FSC
peers onderling probet. Kaal `/health` geeft inderdaad 404, en dat verklaart de eerdere conclusie.

Gemeten in de lokale harness (`demo/environment/logius/deploy/local/`, v2.5.2, alle zes de
componenten van de peer):

| | manager-directory | manager | controller | inway | outway | txlog |
|---|---|---|---|---|---|---|
| `/health/live`, gezond | 200 | 200 | 200 | 200 | 200 | 200 |
| `/health/ready`, gezond | 200 | 200 | 200 | 200 | 200 | 200 |
| `/metrics` | 200 | 200 | 200 | 200 | 200 | 200 |
| `/health` | 404 | 404 | 404 | 404 | 404 | 404 |

En met een afhankelijkheid weggehaald — de txlog-api stilgezet:

| | manager | inway | outway |
|---|---|---|---|
| `/health/live` | 200 | 200 | 200 |
| `/health/ready` | 200 | 503 | 503 |

Beide komen terug op 200 zodra de txlog er weer is. Readiness is dus een echt signaal en geen
constante, en liveness blijft staan terwijl readiness zakt — precies de scheiding die het
acceptatiecriterium vraagt. Een losse outway zonder controller gaf eerder `live=200 ready=503`, dus
het geldt ook voor een component dat nooit gezond is geweest.

De monitoring-poort staat niet in `ports.inbound`. Kubernetes staat een httpGet naar elke geopende
poort toe, en de dienstbeschrijving van `health-check` noemt "je gezondheidsendpoint zit op een
andere poort dan je functionele poort" zelfs als reden om de dienst te kiezen. Of ZAD zo'n poort ook
werkelijk rendert, deed op het moment van schrijven geen enkel component in deze projecten voor;
stap 4 heeft het afgelezen en het antwoord is ja. Was het nee geweest, dan had die poort als extra
inbound-poort gemoeten — 8081, en 8080 op de twee managers — en dat vraagt een hercreatie.

Daarmee is #981 beantwoord: de ruis verdwijnt zonder het signaal in te leveren.

`logius-fscctl` en `magazijna-fscctl` worden nu op 8080 geprobed — de plain-HTTP controller-UI, dus
zonder TLS-ruis. Ze volgen niettemin dezelfde keuze: hun monitoring-poort is 8081, en `/health/ready`
zegt daar meer dan een open UI-poort.

### Groep 6 — Wat geen poort heeft

`logius-fscbootstrap` en `magazijna-fscbootstrap`: eenmalige bootstrap-containers zonder inbound
poort, nu zonder enige probe.

Keuze: expliciet `scheme=none`.

## De uitvoering

### Stap 1 — Meten wat we nog niet weten

Twee metingen zijn gedaan en hierboven verwerkt: de WireMock-stubs en de FSC-familie. Wat overblijft
vraagt een ingelogde `zadctl` en valt daarmee samen met stap 3 — het script draait zijn preflight
tegen OM, en het gerenderde manifest van het eerste component dat je aanraakt beantwoordt de twee
openstaande vragen (slaat de dienst aan op een bestaand component; rendert ZAD een probe op een
poort buiten `ports.inbound`). Begin daarom met `apply mpfpsm-lcl`: die twee stubs zijn het minst
kritieke paar, en ze beantwoorden de eerste vraag al.

### Stap 2 — De scriptvorm

Eén script `demo/environment/zad-demo/gezondheidscontrole.sh`, met dezelfde vorm als
`proeftuin-component.sh` ernaast: `plan | apply`, `--dry-run` in plan-modus, `--strict` op elke
aanroep, expliciete `-p <project>` in plaats van vertrouwen op `.env.zadctl`, en een bash-4.4-guard.
Het tweede argument versmalt tot één project of één deployment; dat laatste is wat de FSC-runbooks
nodig hebben, die over `fsc-logius` en `fsc-magazijna` gaan en niet over het hele project. Het
script draagt de tabel van hierboven als data, met per regel de reden in een comment — dat is de
plek waar "vastgelegd waarom" niet kan verjaren, omdat het naast het commando staat dat het
uitvoert.

De tabel is handwerk, dus het script toetst hem vóór er één aanroep uitgaat: veldaantal, bekend
project, geldig scheme, poort binnen 1024-65535, en paden precies wanneer het scheme ze kent. Daarna
haalt het per deployment op wat er staat. Dat vangt drie dingen die anders halverwege een reeks
boven komen: een verlopen login (`--dry-run` bereikt OM niet, dus `plan` zou het niet merken), een
regel die een component noemt dat niet bestaat, en — de andere richting — een component dát er staat
zonder regel. Dat laatste is precies het geval dat stilzwijgend de standaardcontrole houdt, dus het
wordt luid gemeld.

Waarom een script en niet twintig regels in het runbook: de keuze moet herhaalbaar zijn voor een
nieuw project, een herstelde deployment en een hercreëerd component. Knip-plakwerk uit een README
drift.

Drie eigenschappen van de CLI bepalen de vorm van de aanroepen, alle drie uit `zadctl service
describe health-check` en de `--help` van de gebruikte commando's. `service config set` schrijft het
hele document — een veld dat je niet noemt wordt verwijderd — dus een tcp- of none-regel laat zijn
paden gewoon weg, en `--yes` hoort erbij omdat het commando bevestiging vraagt vóór het iets
weggooit. `service assign` selecteert de dienst meteen op projectniveau, dus een losse `service add`
is niet nodig. En beide rollen standaard meteen uit; met `--no-rollout` plus één `project refresh`
per project aan het eind worden dat geen 54 uitrollen, en bestaat het moment waarop een component de
dienst draagt zonder configuratie alleen in de opgeslagen stand, niet in de cluster.

Het script komt onder de shellcheck-sweep van `fsc-harness-overlays.yml` te vallen door
`demo/environment/zad-demo/*.sh` aan de globs toe te voegen. `proeftuin-component.sh` staat daar nu
buiten en is shellcheck-schoon, dus de glob levert geen bestaande schuld op.

`demo/environment/lib/test-gezondheidscontrole.sh` toetst de tabelvalidatie en de dekkingscontrole
met een `zadctl` vooraan op PATH. Die logica bestaat volledig uit weigeren, en een operator die
`plan` draait toetst per definitie een tabel die klopt — het weigeren zelf wordt dus door geen enkele
handmatige run geraakt. De bash-unittest-lus van dezelfde workflow pikt `lib/test-*.sh` al op, dus
dat vraagt geen workflow-wijziging.

### Stap 3 — Toepassen

In deze volgorde, met tussen elke stap een blik op het gerenderde manifest:

1. `apply mpfpsm-lcl` — de twee stubs en hun twee Toxiproxy's, laagste risico.
2. `apply mpfm-w3h` — de magazijnen, het paneel, de personadienst, de simulator, de proeftuin **en**
   de zes `magazijna-fsc*`-componenten: een projectfilter kan die niet scheiden.
3. `apply mpfb-8wh` — de uitvraag, Redis, de twee Toxiproxy's **en** de zeven `logius-fsc*`.
4. Wil je de federatie apart houden, gebruik dan de deploymentnaam: `apply fsc-logius` en
   `apply fsc-magazijna` vóór de twee projectstappen hierboven.

Het script groepeert zijn tabel op soort en niet op project — de reden hoort bij de soort — dus een
kale `apply` wisselt tussendoor van project, en de uitrol komt pas ná alle mutaties. Er is dan geen
tussenstand om in het manifest te bekijken; vandaar de filter.

Niet doen terwijl er een deploy loopt: OM vergrendelt op project, en een gelijktijdige taak overruled
de wachtstap van de uitrol. `gh run list --workflow "Deploy ZAD"` eerst.

### Stap 4 — Verifiëren

Het bewijs zit in de gerenderde manifests en in de logs:

- Per component het gerenderde `*-deployment.yaml` teruglezen: draagt het de bedoelde probe?
- De twee openstaande vragen: draagt een component dat al bestond nu een `httpGet` (dan slaat de
  dienst aan zonder hercreatie), en draagt `logius-fscoutway` er een op 8081 (dan rendert ZAD een
  probe op een poort buiten `ports.inbound`)?
- De uitvraag: `/q/health/ready` opvragen, dan de Redis-knop dicht, dan opnieuw — de ingress moet
  503 geven en de pod moet *niet* herstarten. Knop weer open, readiness terug `UP`. Dit is meteen
  het bewijs voor het acceptatiecriterium "meldt zichzelf niet-gereed maar wordt niet herstart".
- De storingsknoppen: alle vier de proxies uit en weer aan, en controleren dat geen enkele pod
  herstart en dat de andere knoppen ongemoeid blijven.
- In rust de logs van de FSC-componenten aftappen op TLS-handshake-fouten — nul regels is het
  criterium uit #981.

Deze stappen komen als hoofdstuk in `demo/environment/zad-demo/verify-zad.md`, naast de bestaande
verificaties.

### Stap 5 — Vastleggen

- `demo/environment/zad-demo/README.md`: een eigen hoofdstuk met de tabel en de redenen, plus een
  regel in het bestaande "component aanmaken"-hoofdstuk dat de `health-check`-keuze bij de creatie
  hoort en niet erna. Dat laatste is het acceptatiecriterium "een nieuw onderdeel krijgt de keuze bij
  het aanmaken mee".
- `demo/environment/zad-demo/magazijn-simulator.md`: de bestaande passage aanvullen met de verwijzing
  naar het nieuwe hoofdstuk, zodat er één plek is waar de tabel staat.
- `demo/environment/{logius,magazijn-a}/deploy/zad/README.md`: de FSC-keuze en waarom de probe niet
  op de functionele poort staat; de waarden zelf blijven in hoofdstuk 9, zodat ze op één plek staan.
- `demo/environment/logius/deploy/zad/cutover-interne-outway.md`: dat runbook leert nu nog dat de
  TLS-fout elke twee seconden "hoort zo". Met de probe op de monitoring-poort is nul het criterium,
  dus die passage moet mee.
- `CLAUDE.md`: de bestaande `health-check`-alinea onder "ZAD deploy & GitOps" bijstellen — nu zegt ze
  alleen wat er zonder de dienst gebeurt.

## Uitkomst van de eerste apply (2026-09-06)

Alle 27 componenten zijn ingesteld en uitgerold; `zadctl project pending` staat op nul voor alle
drie de projecten. De preflight meldde `27 regels, 0 component(en) zonder regel` — daarmee is het
acceptatiecriterium "geen onderdeel houdt de standaardcontrole bij gebrek aan een keuze" niet
beredeneerd maar gemeten.

**De twee openstaande aannames zijn allebei bevestigd.** De dienst slaat aan op een component dat al
bestond: `profiel` ging van `tcpSocket` naar `httpGet /__admin/health` zonder hercreatie. En ZAD
rendert een probe op een poort buiten `ports.inbound`: `magazijna-fscmgr` draagt
`ports: [8443, 9443, 9444, 1234]` en kreeg `httpGet port: 8080`. Was één van beide anders
uitgevallen, dan had elk betrokken component herschapen moeten worden.

**#981 is opgelost.** `logius-fscoutway` draagt nu `httpGet :8081 /health/live`, en zijn logboek
telt over twintig minuten nul handshake-fouten waar het er eerst één per twee seconden produceerde.

**Twee dingen die alleen een echte run kon vinden**, allebei verwerkt in het script en in de tests:

- De API-key is per project. `zadctl -p <ander project>` met de key van een ander project geeft
  `401 Invalid API key`. Het script haalt nu per project zijn eigen key op met `zadctl project use`
  in een eigen tijdelijke map, en geeft hem via de omgeving mee in plaats van op de commandoregel.
  Het actieve project van de aanroeper blijft daarbij staan waar het stond.
- `--strict` maakte van een idempotente waarschuwing een fout. Zodra de dienst op projectniveau
  geselecteerd is, meldt `service assign` "Service 'health-check' already exists on the project", en
  met `--strict` is dat exitcode 1 — dus elke tweede aanroep brak af. De vlag staat nu op beide
  aanroepen, met precies die ene melding als toegestane uitzondering; elke andere waarschuwing
  blijft een fout.

  Bij het opschrijven bleek de motivatie erachter niet te kloppen. `--strict` maakt een waarschuwing
  non-zero, maar een taak die door een gelijktijdige uitrol overruled is meldt `status: superseded`
  en is volgens `zadctl guide` een succes met exit 0 — géén waarschuwing. Daarvoor is `zadctl
  project pending` het instrument, en dat is wat het script na een uitrol aanraadt.

**Eén waarneming voor het runbook:** `project refresh` reconcilieert het hele project. Bij
`mpfm-w3h` faalde die stap op image-pull-timeouts van de ZAD-mirror voor twee componenten die niets
met dit werk te maken hadden, terwijl onze instellingen wél in de gerenderde manifests stonden. Een
mislukte uitrol is dus niet hetzelfde als een mislukte instelling; `project pending` op nul is het
bewijs.

Wat nog met de hand moet: de gedragscontroles uit stap 10(b) en 10(c) van `verify-zad.md` — de
Redis-storingsknop en de vier storingsknoppen — want die vragen bediening via het paneel.

## Wat hier bewust niet in zit

- **#882** (een uitrol hard laten falen bij een ongezond onderdeel) profiteert hiervan maar valt
  erbuiten.
- **Een health-group in de Kotlin-code** om de bewust-breekbare afhankelijkheden uit readiness te
  houden. Dat is het alternatief bij de open keuze hierboven; het wordt pas gebouwd als de
  bevestiging de andere kant op valt.
- **De `pr-<n>`-deployments apart bijwerken.** De configuratielaag hangt aan het component binnen
  het project (`components[*]/services{health-check}`), niet aan een deployment, dus elke preview
  leest dezelfde instelling en pikt hem op bij zijn eerstvolgende sync.
