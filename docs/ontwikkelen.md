# Ontwikkelen aan de PoC Federatief Berichtenstelsel

Alles wat je lokaal nodig hebt om te bouwen, testen en handmatig tegen de API's aan te praten.
Voor het opzetten van de demo-stack: [`demo-runbook.md`](demo-runbook.md). Voor draaien in
productie is er een operator-handleiding per service:
[magazijn](operator-handleiding.md), [uitvraag](operator-handleiding-uitvraag.md) en
[stelselregister](operator-handleiding-stelselregister.md).

## Een endpoint wijzigen: OpenAPI-first

De OpenAPI-spec per service is de bron van waarheid, niet de Kotlin-code. De volgorde is daarom
altijd dezelfde:

1. Wijzig de spec — `services/<service>/src/main/resources/openapi/<service>-api.yaml`.
2. Bouw. De `openapi-generator-maven-plugin` genereert met `jaxrs-spec` en `interfaceOnly=true`
   JAX-RS-interfaces plus DTO's naar `target/generated-sources/openapi/`. Die map nooit met de
   hand aanpassen: elke build overschrijft hem.
3. Pas de Kotlin-resource aan die de interface implementeert. Wijkt je implementatie af van de
   spec, dan faalt de compilatie — dat is de bedoelde vangrail.
4. Voeg een `.bru`-request toe aan de bijbehorende Bruno-collectie, zodat die de spec blijft
   spiegelen.
5. Lint de spec (zie hieronder) en draai de tests.

## Tests draaien

Draai altijd `clean` vóór `test` of `verify`: een achtergebleven `target/` van een andere
branch-state laat Surefire stale `.class`-bestanden draaien, wat misleidende fouten geeft in
ongewijzigde code.

```bash
./mvnw clean test -pl libraries/fbs-kern -am                  # pure JVM
./mvnw clean test -pl libraries/fbs-common -am                # pure JVM
./mvnw clean test -pl libraries/fbs-magazijnregister -am      # pure JVM
./mvnw clean test -pl demo/demo-console -am                   # Docker vereist voor één Redis-test (Testcontainers)
./mvnw clean test -pl demo/demo-personas -am                   # pure JVM + één @QuarkusTest, geen Docker
./mvnw clean test -pl demo/magazijn-simulator -am              # Docker vereist (Testcontainers)
./mvnw clean test -pl libraries/fbs-berichtensessiecache -am  # Docker vereist (Testcontainers)
./mvnw clean test -pl services/berichtenmagazijn -am          # Docker vereist
./mvnw clean test -pl services/berichtenuitvraag -am          # Docker vereist
./mvnw clean test -pl services/stelselregister -am            # geen Docker; maakt testsleutels met keytool
```

De modules die Docker vereisen draaien hun infrastructuur via Quarkus Dev Services
(Testcontainers) — je hoeft `docker compose up -d` daar niet apart voor te starten. De demo-console
start zijn Redis rechtstreeks met Testcontainers (`LaatVerlopenRedisTest`): hij wijst
`quarkus.redis.hosts` altijd zelf aan, en dan start Quarkus geen Dev Service. Zijn andere tests
draaien zonder Docker.

### Tijdens het werk: alleen de geraakte testklassen

Een volledige run van de uitvraag met zijn bibliotheken duurt een paar minuten. Draai tussendoor
alleen de klassen die je wijziging raakt, en de volledige suite vóór je pusht (CI draait hem
sowieso):

```bash
./mvnw clean test -pl services/berichtenuitvraag -am \
  -Dtest='BijlageStreamenTest,*Volgen*Test' \
  -Dsurefire.failIfNoSpecifiedTests=false -Djacoco.skip=true
```

- `-Dtest` neemt klassenamen met wildcards, en `Klasse#methode` voor één test.
- `-Dsurefire.failIfNoSpecifiedTests=false` is nodig door `-am`: de bibliotheken die meebouwen
  hebben geen klasse die op het filter past, en Surefire faalt dan.
- `-Djacoco.skip=true`, omdat een deel van de tests nooit aan de 90%-grens komt. De gate hoort bij
  de volledige suite.
- Gebruik geen `-Dsurefire.runOrder`. Surefire zet daarmee ook de JUnit-volgorde van de
  testklassen, en Quarkus weigert dan te starten (`Critical failure ... the Quarkus JUnit
  configuration has been overridden`), omdat het die volgorde zelf nodig heeft om per testprofiel
  te groeperen.

### De volledige suite: parallel

```bash
./mvnw clean verify -pl services/berichtenuitvraag,demo/demo-console -am -T 1C
```

`-T 1C` bouwt onafhankelijke modules naast elkaar: de demo-modules lopen dan naast de keten
fbs-common → sessiecache → uitvraag. Binnen fbs-berichtensessiecache en berichtenuitvraag draaien
de testklassen bovendien over drie Surefire-forks, elk met een eigen Quarkus en eigen Dev
Services-containers. Een fout die je in één JVM wilt nalopen, draai je met `-Dfbs.test.forks=1`.

Over die forks:

- Surefire geeft een fork één testklasse tegelijk, en elke `@QuarkusTest`-klasse start daardoor
  een eigen Quarkus — ook als de vorige klasse in die fork hetzelfde testprofiel had. Een extra
  testprofiel kost in een fork dus niets extra; een extra `@QuarkusTest`-klasse kost een
  opstart van enkele seconden.
- JaCoCo schrijft per fork een eigen databestand (`target/jacoco-quarkus-<n>.exec`); de build voegt
  ze samen vóór de gate. Lees de dekking zoals altijd in `target/site/jacoco/`.
- Elke fork moet zijn eigen poort kunnen pakken. Een testconfiguratie met een vaste
  `quarkus.http.test-port` of een vaste WireMock-poort laat forks op elkaar botsen; gebruik
  `quarkus.http.test-port=0` en `wireMockConfig().dynamicPort()`.

## Kwaliteitsgates

Er zijn twee gates, elk gebonden aan een andere Maven-fase:

- **JaCoCo, minimaal 90% line coverage** — draait in de fase `test`, dus de commando's hierboven
  bewaken hem al. Geldt voor beide services en alle libraries; de demo-console heeft geen
  coverage-gate.
- **detekt, `maxIssues: 0` zonder baseline** — draait pas in de fase `verify`, of los via het
  eigen goal.

```bash
./mvnw clean verify -pl services/berichtenmagazijn -am   # tests + coverage + detekt op die module
./mvnw detekt:check                                      # alleen detekt, repo-breed
```

Een detekt-bevinding faalt de build en hoort echt opgelost te worden; een bewuste, onvermijdelijke
uitzondering krijgt een inline `@Suppress("Rule")` met motivatie, zodat de afweging in review
zichtbaar is.

## OpenAPI-specs linten

De specs zijn de bron van waarheid voor de API's en moeten voldoen aan de NL API Design Rules. Dat
toetst de [DON Checker](https://github.com/developer-overheid-nl/don-checker) van
developer.overheid.nl, die op OpenLint draait (de opvolger van Spectral). `tools/adr-lint/` pint
hem met een lockfile, en Dependabot biedt nieuwe versies aan:

```bash
npm ci --prefix tools/adr-lint                 # eenmalig, en na elke bump
npm run lint --prefix tools/adr-lint           # alle drie de specs
npm run lint:stelselregister --prefix tools/adr-lint   # of één: lint:berichtenmagazijn, lint:berichtenuitvraag
```

Alleen een error faalt de run. De warning `nlgov:servers-use-https` op elke spec is een bewuste
afwijking (relatieve `servers`-URL achter een TLS-terminerende gateway; de motivatie staat in de
spec). Een bump van de checker kan een nieuwe ADR-versie met strengere regels meebrengen; draai de
lint dus opnieuw op zo'n Dependabot-PR.

## Een tweede magazijn draaien

Het stelsel is federatief, dus interessant gedrag (aggregatie, partial failure) zie je pas met meer
dan één magazijn. Een tweede instantie is een tweede *organisatie* en heeft daarom een eigen OIN en
een eigen database nodig — zonder die twee overrides publiceert hij onder de identiteit van magazijn
A en deelt hij diens opslag, en aggregeer je dus twee views op één magazijn:

```bash
MAGAZIJN_OIN=00000001823288444000 \
DB_JDBC_URL=jdbc:postgresql://localhost:5433/berichtenmagazijn \
./mvnw compile quarkus:dev -pl services/berichtenmagazijn -am \
  -Dquarkus.http.port=8091 -Ddebug=5006
```

`localhost:5433` is de `postgres-b`-instantie uit `compose.yaml` (start mee met
`docker compose up -d`); `-Ddebug=5006` voorkomt een conflict op de debug-poort van de eerste
dev-mode. Voor meer dan twee magazijnen is de demo-stack handiger dan losse dev-modes.

## Lokale opstellingen starten

Er zijn drie lokale opstellingen. Twee ervan start je op dezelfde manier: met één `up.sh` dat zelf
de modus kiest (`docker`, `bridge` of `hostnet`), die vastlegt voor de vervolgcommando's, en met de
oorzaak stopt als een modus op deze machine niet kan werken. `MODUS=<modus>` forceert.

| Opstelling | Wat | Starten | Draait op |
|---|---|---|---|
| Demo-stack | De hele keten met berichtenbox en bedieningspaneel | `demo/up.sh` | Docker, of podman (Linux, podman machine) |
| FSC-peer | Eén peer (`logius` of `magazijn-a`) met eigen directory | `demo/environment/<peer>/deploy/local/up.sh` | Docker, of podman (Linux, podman machine) |
| Federatie | De peers samen: contracten, data-pad, discovery | `demo/environment/federatie/federatie.sh up` | Alleen Linux met podman; altijd `hostnet` |

Elk `up.sh` kent dezelfde commando's: zonder argument starten, `ps` voor de status,
`compose <args…>` voor elk ander compose-commando, `down` (met `-v` voor de volumes) om af te
breken. De federatie heeft er `stop`, `start`, `restart <service>` en `status` bij.

Wat je vooraf nodig hebt:

- **Demo-stack:** de images, gebouwd met jib ([`demo-runbook.md`](demo-runbook.md) §2). De
  stub-artefacten en de sleutelketen van het stelselregister maakt het script zelf.
- **FSC-peer:** de PKI van die peer (`cfssl`) en een `deploy/local/.env` — zie de README in
  `deploy/local/` van de peer.
- **Federatie:** het draaiboek in [`demo/environment/federatie/README.md`](../demo/environment/federatie/README.md):
  `.env` en PKI van elke peer, één gedeelde group-CA (`deel-groep-ca.sh`), en
  `net.ipv4.ip_unprivileged_port_start` op 443 of lager.

Gemeenschappelijk: `docker compose` v2.24.4 of nieuwer (tegen podman praat die met de
podman-socket), en `timeout` — op macOS uit coreutils (`brew install coreutils`).

Een losse peer en de federatie gebruiken dezelfde containers en sluiten elkaar daarom uit; het
script weigert de ene zolang de andere draait. De demo-stack en een losse peer gebruiken deels
dezelfde poorten (8081, 8090, 8091, en in hostnet ook 5432) en draaien dus evenmin naast elkaar;
`up.sh` noemt bij een eerste start de poorten die al bezet zijn, in elke modus, voordat er een
container start. Alleen de infra, zonder services, om die zelf in dev-mode te draaien: `docker
compose up -d` (zie [Tests draaien](#tests-draaien) en
[een tweede magazijn draaien](#een-tweede-magazijn-draaien)).

## De demo draaien met de proeftuin als berichtenbox

De berichtenbox die de ondernemer ziet komt uit de proeftuin (`MinBZK/moza-poc`) en draait als
container mee in de demo-stack. Node, npm of Eleventy zijn daarvoor niet nodig.

De eigen services wél: die draaien als image en worden niet gepulld. Bouw ze eerst met jib en
genereer de stub-artefacten — [`demo-runbook.md`](demo-runbook.md), §2 en §3. Sla je dat over, dan
meldt compose `denied: requested access to the resource is denied` op `fbs-demo/…:demo`, en die
melding wijst niet naar de overgeslagen bouwstap.

```bash
demo/up.sh
```

Daarna staat de hele demo op één adres: <http://127.0.0.1:8097/bediening/> — de berichtenbox met het
bedieningspaneel ernaast. Welke versie van de proeftuin meedraait staat in `compose.yaml`, gepind op
digest: `latest` alleen zou stil onder een lopende demo door verschuiven. Die ene regel is ook wat
`deploy.yml` en `proeftuin-component.sh` lezen (via `.github/scripts/proeftuin-image.sh`), dus een
demo op de eigen machine en een demo op ZAD tonen dezelfde berichtenbox. Bijwerken doet de workflow
`proeftuin-pin.yml`: die vergelijkt de pin op werkdagen met hun main en zet een achterlopende pin
als PR klaar, die zichzelf weer sluit zodra de pin bij is. Mergen blijft een oordeel — hun main draagt
ook werk dat halfaf kan zijn. `pin-consistency.yml` blokkeert daarnaast een PR zodra de gepinde
digest niet meer te trekken is. Een andere versie draaien zonder de pin aan te raken kan met de
overlay; die neemt een hele referentie, want hun nog niet gemergde werk staat in een ander
ghcr-repository:

```bash
PROEFTUIN_IMAGE=ghcr.io/minbzk/moza-poc:gebruikersonderzoeken-2026-08 demo/up.sh compose up -d proeftuin
```

De rondleiding langs de knoppen staat in [`demo-runbook.md`](demo-runbook.md), sectie 5b.

### De berichtenbox op een andere keten-omgeving richten

De bestemming is een instelling en geen aparte versie van de proeftuin. Hun nginx splitst het
API-verkeer per pad: `/api/v1/` naar de uitvraag (`BACKEND_KETEN`), `/api/demo/personas` naar de
personadienst (`BACKEND_PERSONAS`) en de rest van `/api/demo/` naar het bedieningspaneel
(`BACKEND_DEMO`). Waar je die zet, hangt af van de opstelling:

| Opstelling | Waar de bestemming vandaan komt |
|---|---|
| Alles lokaal | Niets te doen. `compose.yaml` zet de drie variabelen op containernamen, en vóór de container onderschept `demo-proxy` dezelfde paden al met zijn eigen `UITVRAAG_UPSTREAM`, `PERSONAS_UPSTREAM` en `CONSOLE_UPSTREAM` |
| Lokale berichtenbox, keten elders | Overschrijf `BACKEND_KETEN` en `BACKEND_PERSONAS` in de shell en open de container zelf op `:8096` (zie het voorbeeld hieronder). Via `:8097` heeft dat geen effect: daar beslist de proxy waar `/api/` heen gaat, niet de container |
| Proeftuin op een gedeelde omgeving | Zes aliassen op het component: de drie variabelen plus hun `*_HOST`-tegenhangers, want de ingress ervóór routeert op de Host-header. `demo/environment/zad-demo/proeftuin-component.sh` zet ze — zie [de ZAD-handleiding](../demo/environment/zad-demo/README.md) |

Een lokale berichtenbox tegen de keten op ZAD ziet er dan zo uit. De `*_HOST`-variabelen horen
erbij zodra de bestemming achter een ingress staat die op de Host-header routeert: zonder die
variabelen stuurt de proeftuin de host van de browser mee, en dan komt het verzoek bij de verkeerde
vhost uit.

```bash
Z=rig.prd1.gn2.quattro.rijksapps.nl
BACKEND_KETEN=https://uitvraag-test-mpfb-8wh.$Z \
BACKEND_KETEN_HOST=uitvraag-test-mpfb-8wh.$Z \
BACKEND_PERSONAS=https://demopersonas-test-mpfm-w3h.$Z \
BACKEND_PERSONAS_HOST=demopersonas-test-mpfm-w3h.$Z \
  demo/up.sh compose up -d proeftuin
```

Zet `BACKEND_KETEN` altijd expliciet, en let op het verschil tussen leeg zetten en weglaten.
Leeggemaakt (`BACKEND_KETEN=`) antwoordt de proeftuin met een 502 die de variabelenaam noemt in
plaats van het verkeer stil bij een andere dienst af te leveren — een onvolledig ingerichte omgeving
is daardoor te onderscheiden van een storing. Weggelaten pakt hij de bestemming die sinds de
padsplitsing in hun image gebakken zit: een `uitvraag-<deployment>-mpfb-8wh`-adres van ons. Wijst
dat naar een omgeving die niet meer bestaat, dan leest dat als een storing en niet als een
configuratiefout, want de variabele *is* gezet. `compose.yaml` en `proeftuin-component.sh` zetten
hem daarom allebei.

### Een beperking die tijdens een demonstratie opvalt

Zolang [#1038](https://github.com/MinBZK/MijnOverheidZakelijk/issues/1038) openstaat:

- Bij tientallen aangesloten organisaties valt een deel buiten de lijst, met de melding "tijdelijk
  niet beschikbaar" terwijl er niets stuk is. Vermoedelijk treft dat steeds dezelfde organisaties,
  want ze worden in vaste volgorde bevraagd. Zichtbaar bij de persona's met 45 en 100 organisaties.

## API-requests handmatig uitvoeren (Bruno)

De `bruno/`-folder bevat per service een collectie van voorbeeld-requests die je tegen de lokale
dev-mode kunt uitvoeren met [Bruno](https://www.usebruno.com/).

- `bruno/berichtenmagazijn/` — aanlever- en beheer-API
- `bruno/berichtenuitvraag/` — frontend-facade (lijst, zoek, ophalen-SSE, detail, bijlage,
  PATCH/DELETE) en de aanmeld-webhook
- `bruno/stelselregister/` — het stelseldocument, de sleutelset en `security.txt`

Open de folder in Bruno, kies environment `lokaal` en run requests.
Start voor requests onder `/berichten` eerst een sessie (`bruno/berichtenuitvraag/sessies/`,
`POST /api/v1/sessies`); dat request zet het id in de runtime-variabele `sessie`, en de requests
sturen het als `X-Sessie`. Met curl: zelfde `POST`, daarna `-H "X-Sessie: <id>"` meegeven. De collectie spiegelt de
OpenAPI-spec: nieuwe endpoints in de spec krijgen direct een bijbehorende `.bru`-request.

## Configuratie

Het magazijnregister staat in
`services/berichtenuitvraag/src/main/resources/application.properties` (zoek op `magazijnen."`).
Per deelnemende organisatie zijn er drie sleutels — `url`, `naam` en `grantHash` — met de
afzender-OIN als map-key:

```properties
magazijnen."00000000000000100000".url=${MAGAZIJN_A_URL}
magazijnen."00000000000000100000".naam=RVO
magazijnen."00000000000000100000".grantHash=${MAGAZIJN_A_GRANT_HASH:}
```

Omdat de map-key de OIN is, zijn dubbele OIN's structureel onmogelijk. `ConfigMagazijnregister`
valideert keys, URL's en namen bij het opstarten en weigert buiten dev/test een niet-https-adres. In `%dev`
staan de URL's op `http://localhost:8090` en `:8091` als default, zodat dezelfde configuratie in een
container naar container-DNS wijst zonder de basisregels te hoeven overschrijven. Een lege
`grantHash` betekent: geen FSC-outway, roep het magazijn rechtstreeks aan.

Het stelselregister draagt dezelfde twee basisregels in zijn eigen `application.properties`, zonder
`grantHash`, en publiceert ze als stelseldocument. Een test in die module
(`RegisterGelijkAanUitvraagTest`) faalt zodra OIN's, namen of omgevingsvariabelen uiteenlopen:
wijzig je het register van de uitvraag, wijzig dan dat van het stelselregister mee.

### Het stelselregister lokaal

In dev-mode ondertekent het stelselregister met een wegwerpketen die het bij het opstarten zelf
maakt; er is niets voor in te richten. Een gebouwd image doet dat niet — ook niet onder het
dev-profiel — en heeft de keystore uit `demo/environment/stelselregister/pki/maak-keten.sh` nodig.
Dat script levert ook `out/root.pem`, de root waartegen je het document verifieert.

Voor productie is er per service een operator-handleiding:
[magazijn](operator-handleiding.md) (verplichte overrides, LDV-TLS, outbox, monitoring) en
[uitvraag](operator-handleiding-uitvraag.md) (sessiecache-TLS, timeout-invarianten, sessietermijnen).
Het [stelselregister](operator-handleiding-stelselregister.md) heeft er een over de ondertekensleutel.
De eerste twee beschrijven ook de bewust-onveilige kleppen en de alert-regels die daarbij horen.
