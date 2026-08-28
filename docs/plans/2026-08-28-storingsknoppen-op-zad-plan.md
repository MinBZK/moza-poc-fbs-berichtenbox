**Status:** Concept

# De storingsknoppen op ZAD — plan

Uitvoering van stap 2 uit `2026-08-27-storingsknoppen-en-netwerkregels-op-zad.md`. Stap 1 (de
netwerkregels per deployment) staat; dit plan leunt erop en gebruikt hetzelfde script.

**Issue:** MinBZK/MijnOverheidZakelijk#1029. Met dit werk zijn alle vier de acceptatiecriteria
gedekt; stap 1 dekte er anderhalf.

## Wat het ontwerp niet wist

Drie dingen die uit `RijksICTGilde/RIG-Cluster` blijken en de opzet bijstellen. Ze zijn de reden dat
dit plan meer bevat dan "de console maakt zijn eigen proxies aan".

**Een component kán meer dan één poort.** `AddComponentRequest`/`UpdateComponentRequest` kennen
`ports: [8443, 9443]`, en `service.yaml.jinja` zet elke poort ná de eerste als extra Service-poort
(`p != application_port`). De Ingress pakt alleen `service_port`, en dat is `ports[0]`
(`project_manager.py`: `application_port = inbound_ports[0]`). De beperking "een ZAD-component
publiceert precies één poort" geldt dus voor de ingress, niet voor de Service. Daarmee kan één
Toxiproxy-component zijn stroom publiek dragen én zijn admin-API cluster-intern houden — het
ontwerp ging nog uit van één poort per component.

**De standaard-probe zou de uit-knop terugdraaien.** Zonder de `health-check`-dienst rendert
`deployment.yaml.jinja` een `tcpSocket`-probe op `application_port`, met `livenessProbe`
`periodSeconds: 30` en `failureThreshold: 3`. Een uitgezette proxy sluit precies die listener, dus
ongeveer anderhalve minuut na een druk op "uit" herstart de pod — en die herstart neemt álle
proxies mee, want Toxiproxy houdt ze in het geheugen. De demo zou zichzelf repareren op een manier
die niemand kan uitleggen. Daarom draagt elk Toxiproxy-component de `health-check`-dienst met
`port: 8474`: de probe volgt de admin-API, die altijd staat, en niet de stroom die je juist wilt
kunnen uitzetten.

**Eén regel opent één poort op één component bij één peer.** `merge.py` en `config_model.py` laten
geen lijst van componenten of poorten toe. Vier admin-hops zijn dus vier regels, elk met een
outbound in `mpfm-w3h` en een inbound in het project van de tegenpartij.

## Wat er op ZAD bij komt

Vier componenten, elk in dezelfde deployment als zijn upstream. De eerste poort draagt de stroom en
krijgt de ingress; 8474 is de admin-API en blijft cluster-intern.

| Component | Project | `ports` | Ingress | Upstream |
|---|---|---|---|---|
| `toxiproxy-profiel` | `mpfpsm-lcl` | `[18089, 8474]` | ja | `$DEPLOYMENT_NAME-profiel:8080` |
| `toxiproxy-notificatie` | `mpfpsm-lcl` | `[18084, 8474]` | ja | `$DEPLOYMENT_NAME-notificatie:8080` |
| `toxiproxy-aanmeld` | `mpfb-8wh` | `[18086, 8474]` | ja | `$DEPLOYMENT_NAME-uitvraag:8086` |
| `toxiproxy-redis` | `mpfb-8wh` | `[16379, 8474]` | nee | `$DEPLOYMENT_NAME-redis:6379` |

De poortnummers zijn dezelfde als lokaal, zodat er één set feiten rondgaat.

De drie stromen die vandaag over de publieke ingress lopen (profiel, notificatie, aanmeld) blijven
dat doen, nu met de Toxiproxy ertussen: de router termineert TLS en stuurt platte TCP naar de
listen-poort, Toxiproxy stuurt door naar zijn upstream in zijn eigen deployment. Daarmee blijft de
TLS-eis in de code overeind en vraagt de stroom géén netwerkregel. `toxiproxy-redis` staat in
dezelfde deployment als zijn aanroeper en heeft daarom noch ingress noch regel nodig — de
tenant-baseline laat verkeer binnen één deployment al toe.

Alleen het admin-verkeer van de console kruist een projectgrens, en dat zijn de vier regels:

| Regel | inbound in | outbound in |
|---|---|---|
| `democonsole-naar-toxiproxy-profiel` | `mpfpsm-lcl` | `mpfm-w3h` |
| `democonsole-naar-toxiproxy-notificatie` | `mpfpsm-lcl` | `mpfm-w3h` |
| `democonsole-naar-toxiproxy-aanmeld` | `mpfb-8wh` | `mpfm-w3h` |
| `democonsole-naar-toxiproxy-redis` | `mpfb-8wh` | `mpfm-w3h` |

`mpfpsm-lcl` draagt vandaag geen enkele regel; die krijgt er nu twee, en daarmee ook een opruim-leg
in `cleanup-preview.yml`.

## De reconcile-lus

Op ZAD is er geen `proxies.json` meer, dus de proxies staan alleen in het geheugen van Toxiproxy.
Herstart die pod — node-drain, redeploy, OOM — dan is de keten dood: al het profiel-, notificatie-,
aanmeld- en Redis-verkeer loopt erdoorheen. Een bootstrap die alleen bij het starten van de console
draait, laat dat gat open.

Daarom een `@Scheduled`-reconcile naast de bootstrap. Hij maakt alleen ontbrekende proxies aan; een
bestaande blijft staan, dus een bewust uitgezette proxy blijft uit. Alleen een lege Toxiproxy wordt
opnieuw gevuld, en dat is precies het geval dat je wilt herstellen. De scheduler staat al op
`start-mode=forced`.

Dat de bestaande `reset()`-guard op "kent geen enkele proxy" blijft staan, wint hierdoor aan
betekenis: nul proxies terwijl de reconcile draait, wijst op een Toxiproxy die onbereikbaar is, niet
op een die net herstartte.

## Twee bronnen, één test

Lokaal blijft `toxiproxy/proxies.json` staan: compose zet de keten daarmee meteen neer, zonder te
wachten op de console. De console kent nu dezelfde feiten (listen en upstream per proxy) uit
`application.properties`, en dat zijn twee bronnen voor hetzelfde. Een pure-JVM-test vergelijkt ze
en faalt zodra ze uiteenlopen — anders zou een verkeerde upstream pas op ZAD zichtbaar worden, en
dan als een demo die stilletjes het verkeerde magazijn aanspreekt.

## Stappen

### 1. De console maakt zijn eigen proxies aan

- [ ] `ToxiproxyConfig.Instantie` krijgt `listen()` en `upstream()`, beide `Optional<String>` om
      dezelfde reden als `url()`: een leeggezette env-var is bij smallrye-config "niet gezet".
- [ ] `ProxyDefinities` naast `ToxiproxyAdressen` — een pure klasse die per proxy de drie waarden
      bij elkaar houdt en alleen een volledige definitie (url + listen + upstream) doorlaat. Los van
      het bouwen van REST-clients, zodat de beslissing toetsbaar blijft zonder draaiende Quarkus.
- [ ] `ToxiproxyClient` krijgt `maakProxy(ProxyVerzoek)` op `POST /proxies`.
- [ ] `ProxyBootstrap`: idempotent aanmaken (HTTP 409 = bestaat al = goed), bij `StartupEvent` en
      daarna elke 30 seconden. Fouten loggen, niet gooien — een onbereikbare Toxiproxy mag het
      starten van de console niet blokkeren.
- [ ] `application.properties`: `listen` en `upstream` per proxy, met de lokale waarden als default.

### 2. Tests

- [ ] `ProxyDefinitiesTest` — leeg, één, meerdere; ontbrekende listen of upstream; lege url.
- [ ] `ProxyBootstrapTest` — maakt ontbrekende proxies, laat bestaande staan, 409 is geen fout, een
      falende instantie blokkeert de andere niet.
- [ ] `ToxiproxyProxiesConsistentieTest` — de defaults uit `application.properties` tegen
      `../../toxiproxy/proxies.json`: dezelfde namen, dezelfde listen, dezelfde upstream.
- [ ] `ApplicationPropertiesTest` erbij: elke proxy die een url draagt, draagt ook listen en
      upstream.

### 3. De netwerkregels per preview

- [ ] `cross-domain-preview.sh` accepteert meerdere regelnamen in één aanroep en zet ze in één
      patch. Vier regels als vier aanroepen zou vier keer de projectregel-controle en vier keer het
      wachten op een taak kosten; de API neemt een lijst.
- [ ] `deploy.yml`: de vier regels erbij, verdeeld over `deploy-preview-uitvraag` (twee inbound),
      `deploy-preview-externe-stubs` (twee inbound, plus de `actions/checkout` die die job nog niet
      had) en `deploy-preview-magazijnen` (vier outbound, naast de bestaande).
- [ ] `cleanup-preview.yml`: `mpfpsm-lcl` krijgt een richting in de matrix, en elke leg ruimt zijn
      volledige set regels op.
- [ ] De vier Toxiproxy-images in de component-lijsten van de preview-deploys, zoals `redis` dat al
      doet — een preview die het component niet noemt, krijgt het niet.
- [ ] `test-cross-domain-preview.sh` uitbreiden: meerdere regels in één patch, en de bewaking dat
      `deploy.yml` en `cleanup-preview.yml` dezelfde verzameling regelnamen noemen.

### 4. Het runbook en de eenmalige creatie op ZAD

- [ ] `demo/environment/zad-demo/README.md`: een stap voor de vier componenten (met `--ports` en de
      `health-check`-configuratie), de acht projectregels, de invulling voor `test`, en het omhangen
      van `PROFIEL_SERVICE_URL`, `NOTIFICATIE_URL`, `AANMELD_URL` en `REDIS_HOSTS` naar de proxies.
- [ ] De `TOXIPROXY_*_URL`-waarden op de console vullen; de twee magazijn-proxies blijven leeg
      (hun storingsgedrag komt uit de simulator, TODO(#938)).
- [ ] De creatie zelf uitvoeren op `test`, met een ingelogde `zadctl`.
- [ ] `verify-zad.md`: een stap voor de storingsknoppen, inclusief wat een verkeerde upstream laat
      zien.

### 5. Documentatie bijwerken

- [ ] `demo/demo-console/README.md`, `docs/demo-runbook.md`, `demo/README.md`: de storingsknoppen
      van "ontbreekt op ZAD" naar "werkt, ook op een preview".
- [ ] Het ontwerp van 27 augustus: stap 2 op uitgevoerd, en de drie bevindingen hierboven erin.
- [ ] `CLAUDE.md`: de componentlijst per project, en de multi-poort-eigenschap bij de drie
      ZAD-eigenschappen — die derde ("een component publiceert één poort") staat er nu te absoluut.

## Verificatie

- `./mvnw clean verify -pl demo/demo-console -am` groen, zonder nieuwe waarschuwingen; detekt 0.
- De bash-suites en `shellcheck -x -S warning` schoon.
- Op ZAD, op `test` én op een preview: elke storingsknop zichtbaar, "traag" meetbaar in de
  responstijd van de Berichtenbox, "uit" zichtbaar als een falende stroom, en "Alles normaal" die
  het weer herstelt.
- De gerenderde NetworkPolicies van een preview wijzen naar de Toxiproxy-componenten van díe
  preview, niet naar die van `test`.
- Een Toxiproxy-pod met de hand herstarten en zien dat de proxies binnen een halve minuut
  terugkomen.
- Een preview sluiten en controleren dat er in geen van de drie projecten een regel achterblijft.

## Wat dit plan bewust niet doet

- **De magazijn-storingen.** Die vragen een Toxiproxy vóór elk magazijn in `mpfm-w3h`; hun
  storingsgedrag komt uit de magazijn-simulator (#938). De twee `TOXIPROXY_MAGAZIJN_*_URL` blijven
  leeg en het paneel laat die knoppen weg.
- **De veel-magazijnen-schuif.** Zelfde afhankelijkheid (#938).
- **De extra hop wegnemen bij normaal bedrijf.** Het verkeer loopt voortaan altijd door Toxiproxy,
  ook als niemand demonstreert. Dat is de prijs die het ontwerp al benoemde en accepteerde.
