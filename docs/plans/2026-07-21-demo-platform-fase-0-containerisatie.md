**Status:** Uitgevoerd

> **Uitvoerstatus.** Alle codewijzigingen gemaakt (commits `750e09c`, `77931e5`,
> `f434bc7`) en op een machine mét Docker volledig geverifieerd door de gebruiker:
> images gebouwd (jib), `./mvnw clean test` groen, `docker compose --profile demo up -d`
> gestart en `./demo/smoke.sh` doorlopen — de keten werkt end-to-end.
>
> De codewijzigingen zijn in de ontwikkelomgeving (zonder container-runtime) al
> voorbereid en gecontroleerd met `mvn package` inclusief augmentatie en een YAML-/
> profielcheck; de container-afhankelijke verificatie is daarna door de gebruiker gedaan.

# Demo-platform fase 0 — containerisatie — implementatieplan

> **Voor agentic workers:** VERPLICHTE SUB-SKILL: gebruik `superpowers:subagent-driven-development`
> (aanbevolen) of `superpowers:executing-plans` om dit plan taak voor taak uit te voeren.
> Stappen gebruiken checkbox-syntax (`- [ ]`) voor voortgang.

**Ontwerp:** `docs/plans/2026-07-21-demo-platform-design.md` (fase 0)

**Doel:** de bestaande keten volledig in containers laten draaien met één commando,
zonder het gedrag van `%test` of `%prod` te wijzigen.

**Architectuur:** de `%dev`-regels van beide services krijgen een env-var-met-default,
zodat dezelfde image lokaal (default grijpt) én in compose (env-var overschrijft) werkt.
Containers draaien met `QUARKUS_PROFILE=dev` omdat `OutboundTlsValidator` alleen in
`dev`/`test` plaintext http toestaat. Images via jib; geen Dockerfiles.

**Tech stack:** Quarkus 3.x, Kotlin, Docker Compose, jib (`quarkus-container-image-jib`),
PostgreSQL 18, Redis, WireMock.

## Global Constraints

- **Geen wijziging aan `%test`- of `%prod`-regels.** De testsuite en ZAD moeten
  aantoonbaar ongemoeid blijven.
- **Geen nieuw Quarkus-profiel.** `OutboundTlsValidator.PROFIELEN_ZONDER_TLS_EIS` bevat
  alleen `dev` en `test`; een `%demo`-profiel zou https eisen op alle stub-URL's.
- **Env-varnamen hergebruiken uit `%prod`** waar ze al bestaan: `DB_JDBC_URL`,
  `DB_USERNAME`, `DB_PASSWORD`, `MAGAZIJN_OIN`, `AANMELD_URL`, `NOTIFICATIE_URL`,
  `PROFIEL_SERVICE_URL`, `LDV_CLICKHOUSE_ENDPOINT`, `REDIS_HOSTS`, `MAGAZIJN_A_URL`,
  `MAGAZIJN_B_URL`.
- **Altijd `clean` vóór `test`/`verify`** (projectregel: stale `target/` van een andere
  branch geeft misleidende fouten).
- **Nederlands** in comments en commit-messages; Engelse technische idiomen blijven Engels.
- **Nooit direct naar `main`.** Werk op een feature branch.
- Bestaande compose-servicenamen `magazijn-a`/`magazijn-b` zijn **WireMock-stubs** voor
  `%test`. De echte magazijnen krijgen daarom de namen `berichtenmagazijn-a`/`-b`.

## Bestandsoverzicht

| Bestand | Verantwoordelijkheid | Actie |
|---|---|---|
| `services/berichtenmagazijn/src/main/resources/application.properties` | `%dev`-regels overschrijfbaar maken | Wijzigen |
| `services/berichtenuitvraag/src/main/resources/application.properties` | idem | Wijzigen |
| `compose.yaml` | drie app-containers onder profiel `demo` | Wijzigen |
| `demo/smoke.sh` | rookproef tegen de draaiende demo-stack | Aanmaken |
| `README.md` | opstartinstructies voor beide modi | Wijzigen |

## Waarom hier geen unit-tests bij komen

Dit is uitsluitend configuratie- en compose-werk. Een unit-test die controleert dat
`${DB_JDBC_URL:...}` naar zijn default resolvet, test Quarkus' config-implementatie en
niet onze code — dat is ceremonie zonder waarde.

De echte regressietest is dat **`./mvnw clean test` groen blijft**: dat bewijst dat de
`%test`-regels ongemoeid zijn. Het echte gedragsbewijs is `demo/smoke.sh` (taak 5), die
de volledige keten door de containers heen rijdt. Beide zijn opgenomen als verplichte
stappen.

---

### Taak 1: Magazijn-config overschrijfbaar maken

Zonder deze taak kan magazijn B niet uit dezelfde image draaien: `%dev` pint database
én organisatie-OIN hard op magazijn A.

**Bestanden:**
- Wijzigen: `services/berichtenmagazijn/src/main/resources/application.properties`
  (regels 62-64, 119, 138, 156-157, 190)

**Interfaces:**
- Produceert (env-vars die taak 4 zet): `DB_JDBC_URL`, `DB_USERNAME`, `DB_PASSWORD`,
  `MAGAZIJN_OIN`, `AANMELD_URL`, `NOTIFICATIE_URL`, `PROFIEL_SERVICE_URL`,
  `LDV_CLICKHOUSE_ENDPOINT`

- [ ] **Stap 1: Leg de huidige testuitslag vast als vergelijkingspunt**

```bash
./mvnw clean test -pl services/berichtenmagazijn -am 2>&1 | tail -20
```

Verwacht: `BUILD SUCCESS`. Noteer het aantal tests (`Tests run: N`) — dat getal moet na
de wijziging identiek zijn.

- [ ] **Stap 2: Vervang de acht `%dev`-regels**

Vervang exact deze regels (laat commentaar eromheen staan):

```properties
%dev.quarkus.datasource.jdbc.url=${DB_JDBC_URL:jdbc:postgresql://localhost:5432/berichtenmagazijn}
%dev.quarkus.datasource.username=${DB_USERNAME:berichtenmagazijn}
%dev.quarkus.datasource.password=${DB_PASSWORD:berichtenmagazijn}
%dev.logboekdataverwerking.clickhouse.endpoint=${LDV_CLICKHOUSE_ENDPOINT:http://localhost:8123}
%dev.magazijn.publicatie.organisatie.oin=${MAGAZIJN_OIN:00000001003214345000}
%dev.magazijn.publicatie.downstreams.aanmeld.url=${AANMELD_URL:http://localhost:8083/events}
%dev.magazijn.publicatie.downstreams.notificatie.url=${NOTIFICATIE_URL:http://localhost:8084/events}
%dev.quarkus.rest-client.profiel-service.url=${PROFIEL_SERVICE_URL:http://localhost:8089}
```

Voeg boven het blok deze toelichting toe:

```properties
# Env-var met default: buiten containers grijpt de default (ongewijzigd dev-gedrag),
# in de demo-compose overschrijft de env-var naar container-DNS. Dezelfde varnamen als
# %prod, zodat lokaal en ZAD dezelfde knoppen hebben. Een eigen %demo-profiel kan niet:
# OutboundTlsValidator staat plaintext http alleen toe in dev en test.
```

- [ ] **Stap 3: Bevestig dat de defaults ongewijzigd gedrag geven**

```bash
./mvnw clean test -pl services/berichtenmagazijn -am 2>&1 | tail -20
```

Verwacht: `BUILD SUCCESS` met hetzelfde aantal tests als in stap 1.

- [ ] **Stap 4: Bevestig dat dev-mode nog steeds zonder env-vars start**

```bash
docker compose up -d postgres-a clickhouse profiel-service aanmeld-stub notificatie-stub
./mvnw quarkus:dev -pl services/berichtenmagazijn
```

Verwacht in de log: `Listening on: http://localhost:8090` en géén
`Failed to start quarkus`. Controleer in een tweede terminal:

```bash
curl -s localhost:8090/q/health/ready
```

Verwacht: JSON met `"status": "UP"`. Stop dev-mode daarna met Ctrl-C.

- [ ] **Stap 5: Commit**

```bash
git add services/berichtenmagazijn/src/main/resources/application.properties
git commit -m "config(magazijn): %dev-regels overschrijfbaar via env-vars

Zodat twee magazijn-instanties uit dezelfde container-image kunnen draaien:
database en organisatie-OIN stonden hard op magazijn A."
```

---

### Taak 2: Uitvraag-config overschrijfbaar maken

**Bestanden:**
- Wijzigen: `services/berichtenuitvraag/src/main/resources/application.properties`
  (regels 55, 156, 213-214, 254)

**Interfaces:**
- Produceert (env-vars die taak 4 zet): `REDIS_HOSTS`, `MAGAZIJN_A_URL`, `MAGAZIJN_B_URL`,
  `PROFIEL_SERVICE_URL`, `LDV_CLICKHOUSE_ENDPOINT`

- [ ] **Stap 1: Leg de huidige testuitslag vast**

```bash
./mvnw clean test -pl services/berichtenuitvraag -am 2>&1 | tail -20
```

Verwacht: `BUILD SUCCESS`. Noteer `Tests run: N`.

- [ ] **Stap 2: Vervang de vijf `%dev`-regels**

```properties
%dev.quarkus.redis.hosts=${REDIS_HOSTS:redis://localhost:6379}
%dev.quarkus.rest-client.profiel-service.url=${PROFIEL_SERVICE_URL:http://localhost:8089}
%dev.magazijnen."00000001003214345000".url=${MAGAZIJN_A_URL:http://localhost:8090}
%dev.magazijnen."00000001823288444000".url=${MAGAZIJN_B_URL:http://localhost:8091}
%dev.logboekdataverwerking.clickhouse.endpoint=${LDV_CLICKHOUSE_ENDPOINT:http://localhost:8123}
```

**Let op:** overschrijf de magazijn-URL's uitsluitend via deze `${...}`-vorm, níet via
impliciete env-var-mapping (`MAGAZIJNEN__..._URL`). De mapping van env-varnamen naar
map-sleutels met aanhalingstekens is niet gegarandeerd; de expliciete vorm werkt zeker
en is bovendien leesbaar in het bestand zelf.

Laat `%test.magazijnen.*` (regels 118-119, poorten 8081/8082) ongemoeid — die wijzen
naar de WireMock-stubs en horen bij de testsuite.

- [ ] **Stap 3: Bevestig ongewijzigd gedrag**

```bash
./mvnw clean test -pl services/berichtenuitvraag -am 2>&1 | tail -20
```

Verwacht: `BUILD SUCCESS` met hetzelfde aantal tests als in stap 1.

- [ ] **Stap 4: Commit**

```bash
git add services/berichtenuitvraag/src/main/resources/application.properties
git commit -m "config(uitvraag): %dev-regels overschrijfbaar via env-vars

Redis, magazijn-URL's, profielservice en LDV-endpoint krijgen een env-var met
default, zodat de container-variant dezelfde image kan gebruiken."
```

---

### Taak 3: Container-images bouwen

**Bestanden:** geen — jib zit al in beide POM's (`quarkus-container-image-jib`), met
`quarkus.container-image.name` ingevuld. Group en tag komen van de commandoregel, net
als in `.github/workflows/deploy.yml`.

**Interfaces:**
- Produceert (image-namen die taak 4 gebruikt): `fbs-demo/fbs-berichtenmagazijn:demo`,
  `fbs-demo/fbs-berichtenuitvraag:demo`

- [ ] **Stap 1: Bouw beide images**

```bash
./mvnw clean package -DskipTests \
  -pl services/berichtenmagazijn,services/berichtenuitvraag -am \
  -Dquarkus.container-image.build=true \
  -Dquarkus.container-image.group=fbs-demo \
  -Dquarkus.container-image.tag=demo
```

Verwacht: `BUILD SUCCESS` en per module een regel als
`Created container image fbs-demo/fbs-berichtenmagazijn:demo`.

- [ ] **Stap 2: Bevestig dat de images bestaan**

```bash
docker images --filter=reference='fbs-demo/*:demo'
```

Verwacht: twee regels, `fbs-berichtenmagazijn` en `fbs-berichtenuitvraag`, beide met tag
`demo`.

- [ ] **Stap 3: Leg het bouwcommando vast in de README**

Voeg onder "Snel starten" toe:

````markdown
### Demo-stack (alles in containers)

Bouw eerst de images (opnieuw nodig na elke codewijziging):

```bash
./mvnw clean package -DskipTests \
  -pl services/berichtenmagazijn,services/berichtenuitvraag -am \
  -Dquarkus.container-image.build=true \
  -Dquarkus.container-image.group=fbs-demo \
  -Dquarkus.container-image.tag=demo
```
````

- [ ] **Stap 4: Commit**

```bash
git add README.md
git commit -m "docs(readme): bouwcommando voor de demo-images"
```

---

### Taak 4: Compose-profiel `demo`

**Bestanden:**
- Wijzigen: `compose.yaml` (drie services toevoegen vóór het `volumes:`-blok)

**Interfaces:**
- Consumeert: de env-vars uit taak 1 en 2, de images uit taak 3
- Produceert (voor taak 5): `berichtenuitvraag` op `localhost:8086`,
  `berichtenmagazijn-a` op `:8090`, `berichtenmagazijn-b` op `:8091`

- [ ] **Stap 1: Voeg de drie services toe**

```yaml
  # Demo-stack: alleen actief met `--profile demo`. Zonder profiel start compose de
  # infra en draai je de services met `quarkus:dev` (hot reload behouden).
  # QUARKUS_PROFILE=dev is bewust: OutboundTlsValidator staat plaintext http alleen toe
  # in dev en test, en de stubs spreken geen https.
  berichtenmagazijn-a:
    image: fbs-demo/fbs-berichtenmagazijn:demo
    profiles: [demo]
    ports:
      - "8090:8090"
    depends_on:
      postgres-a:
        condition: service_healthy
    environment:
      QUARKUS_PROFILE: dev
      QUARKUS_HTTP_HOST: 0.0.0.0
      DB_JDBC_URL: jdbc:postgresql://postgres-a:5432/berichtenmagazijn
      MAGAZIJN_OIN: "00000001003214345000"
      AANMELD_URL: http://aanmeld-stub:8080/events
      NOTIFICATIE_URL: http://notificatie-stub:8080/events
      PROFIEL_SERVICE_URL: http://profiel-service:8080
      LDV_CLICKHOUSE_ENDPOINT: http://clickhouse:8123

  berichtenmagazijn-b:
    image: fbs-demo/fbs-berichtenmagazijn:demo
    profiles: [demo]
    ports:
      - "8091:8090"
    depends_on:
      postgres-b:
        condition: service_healthy
    environment:
      QUARKUS_PROFILE: dev
      QUARKUS_HTTP_HOST: 0.0.0.0
      DB_JDBC_URL: jdbc:postgresql://postgres-b:5432/berichtenmagazijn
      MAGAZIJN_OIN: "00000001823288444000"
      AANMELD_URL: http://aanmeld-stub:8080/events
      NOTIFICATIE_URL: http://notificatie-stub:8080/events
      PROFIEL_SERVICE_URL: http://profiel-service:8080
      LDV_CLICKHOUSE_ENDPOINT: http://clickhouse:8123

  berichtenuitvraag:
    image: fbs-demo/fbs-berichtenuitvraag:demo
    profiles: [demo]
    ports:
      - "8086:8086"
    depends_on:
      redis:
        condition: service_healthy
      berichtenmagazijn-a:
        condition: service_started
      berichtenmagazijn-b:
        condition: service_started
    environment:
      QUARKUS_PROFILE: dev
      QUARKUS_HTTP_HOST: 0.0.0.0
      REDIS_HOSTS: redis://redis:6379
      MAGAZIJN_A_URL: http://berichtenmagazijn-a:8090
      MAGAZIJN_B_URL: http://berichtenmagazijn-b:8090
      PROFIEL_SERVICE_URL: http://profiel-service:8080
      LDV_CLICKHOUSE_ENDPOINT: http://clickhouse:8123
```

**Twee dingen om te weten bij deze YAML:**

`QUARKUS_HTTP_HOST: 0.0.0.0` staat er als verzekering. In `NORMAL` launch mode is
`0.0.0.0` al de default, maar Quarkus bindt in *dev mode* op localhost — en de combinatie
"profiel dev, launch mode normal" is precies de rand waar dat verwarrend wordt. Bindt de
service op localhost in de container, dan is de gepubliceerde poort dood. Expliciet zetten
kost niets en sluit die twijfel uit.

Beide magazijn-containers luisteren intern op **8090**; alleen de gepubliceerde poort
verschilt. Daarom wijst `MAGAZIJN_B_URL` naar `berichtenmagazijn-b:8090` en niet naar 8091.

- [ ] **Stap 2: Controleer dat het standaardgedrag ongewijzigd is**

```bash
docker compose config --services
docker compose --profile demo config --services
```

Verwacht: de eerste lijst bevat **niet** `berichtenuitvraag`, `berichtenmagazijn-a` of
`berichtenmagazijn-b`; de tweede lijst bevat ze alle drie. Dat bewijst dat
`docker compose up -d` de infra-modus blijft.

- [ ] **Stap 3: Start de volledige stack**

```bash
docker compose --profile demo up -d
docker compose --profile demo ps
```

Verwacht: alle containers `running`; `postgres-a`/`postgres-b`/`redis` tonen `(healthy)`.

- [ ] **Stap 4: Controleer de health-endpoints**

```bash
curl -sf localhost:8090/q/health/ready && echo " magazijn-a OK"
curl -sf localhost:8091/q/health/ready && echo " magazijn-b OK"
curl -sf localhost:8086/q/health/ready && echo " uitvraag OK"
```

Verwacht: drie regels met `OK`.

Bij een falende container: `docker compose logs berichtenmagazijn-b`. De twee meest
waarschijnlijke oorzaken zijn een niet-resolvende container-DNS-naam (typefout in een
env-var) en Flyway die de migraties op een lege database nog niet af heeft.

- [ ] **Stap 5: Commit**

```bash
git add compose.yaml
git commit -m "build(compose): demo-profiel met beide magazijnen en uitvraag

Zonder profiel blijft compose de infra-modus voor quarkus:dev; met
--profile demo draait de volledige keten in containers."
```

---

### Taak 5: Rookproef over de keten

Health-endpoints bewijzen dat processen leven, niet dat de keten werkt. Deze taak levert
het bewijs dat een aangeleverd bericht ook via de uitvraag terugkomt — precies het gedrag
waarop fase 1 gaat bouwen.

**Bestanden:**
- Aanmaken: `demo/smoke.sh`
- Wijzigen: `README.md`

**Interfaces:**
- Consumeert: de draaiende stack uit taak 4
- Produceert: `demo/smoke.sh`, exit 0 bij succes — herbruikbaar als regressiecheck in
  latere fases

- [ ] **Stap 1: Schrijf de rookproef**

```bash
#!/usr/bin/env bash
# Rookproef voor de demo-stack: levert een bericht aan bij magazijn A en controleert
# dat het via de uitvraag terugkomt. Draait tegen `docker compose --profile demo up -d`.
set -euo pipefail

MAGAZIJN_A="${MAGAZIJN_A:-http://localhost:8090}"
UITVRAAG="${UITVRAAG:-http://localhost:8086}"
ONTVANGER="${ONTVANGER:-BSN:999993653}"

echo "1/3 health"
for url in "$MAGAZIJN_A" "$UITVRAAG"; do
    curl -sf "$url/q/health/ready" > /dev/null || { echo "FOUT: $url niet gezond"; exit 1; }
done

echo "2/3 bericht aanleveren"
# Het magazijn kent het berichtId zelf toe; de aanlever-request bevat er geen. We
# herkennen ons bericht daarom aan een uniek onderwerp.
onderwerp="Rookproef demo-stack $(date +%s)"
status=$(curl -s -o /tmp/smoke-aanlever.json -w '%{http_code}' \
    -X POST "$MAGAZIJN_A/api/v1/berichten" \
    -H 'Content-Type: application/json' \
    -d "{
          \"afzender\": \"00000001003214345000\",
          \"ontvanger\": {\"type\": \"BSN\", \"waarde\": \"999993653\"},
          \"onderwerp\": \"$onderwerp\",
          \"inhoud\": \"Aangemaakt door demo/smoke.sh\"
        }")

if [[ "$status" != "201" ]]; then
    echo "FOUT: aanleveren gaf HTTP $status (verwacht 201)"
    cat /tmp/smoke-aanlever.json
    exit 1
fi

bericht_id=$(grep -o '"berichtId"[[:space:]]*:[[:space:]]*"[^"]*"' /tmp/smoke-aanlever.json \
    | head -1 | sed 's/.*"\([^"]*\)"$/\1/')
echo "    magazijn kende berichtId $bericht_id toe"

echo "3/3 ophalen via uitvraag"
# De sessiecache vult zich pas na een ophaal-ronde; GET /berichten leest alleen de cache.
curl -sf -N --max-time 30 "$UITVRAAG/api/v1/berichten/_ophalen" \
    -H "X-Ontvanger: $ONTVANGER" > /dev/null

curl -sf "$UITVRAAG/api/v1/berichten" -H "X-Ontvanger: $ONTVANGER" \
    | grep -q "$onderwerp" \
    || { echo "FOUT: '$onderwerp' niet gevonden in de uitvraag"; exit 1; }

echo "OK: keten werkt end-to-end"
```

- [ ] **Stap 2: Maak het script uitvoerbaar en draai het**

```bash
chmod +x demo/smoke.sh
./demo/smoke.sh
```

Verwacht: `OK: keten werkt end-to-end`, exit code 0.

De payload volgt `BerichtAanleverenRequest` uit
`services/berichtenmagazijn/src/main/resources/openapi/berichtenmagazijn-api.yaml:339`:
verplicht zijn `afzender` (OIN, 20 cijfers), `ontvanger`, `onderwerp` en `inhoud`. Dat
schema is gelezen, maar het script is **niet uitgevoerd** — dit is de eerste taak waarin
het draait.

Twee dingen die kunnen tegenvallen:

- **HTTP 403 bij aanleveren.** Het magazijn controleert via de Profiel-service of de
  ontvanger een `OntvangViaBerichtenbox`-voorkeur heeft voor deze afzender-OIN. De
  WireMock-stub geeft die standaard voor OIN `00000001003214345000` — vandaar precies
  die afzender in de payload. Wijk daar niet van af zonder de stub-mapping in
  `wiremock/externe-stubs/mappings/` mee te veranderen.
- **BSN afgekeurd.** `999993653` is met de hand tegen de elfproef gecontroleerd
  (gewogen som 352 = 11 × 32) en zou moeten worden geaccepteerd. Wordt hij toch
  geweigerd, pak dan een BSN uit de bestaande tests
  (`grep -rn "BSN" services/berichtenmagazijn/src/test`).

- [ ] **Stap 3: Documenteer beide opstartmodi in de README**

Voeg toe onder de sectie uit taak 3:

````markdown
Start daarna de volledige stack:

```bash
docker compose --profile demo up -d   # alles in containers — demo
./demo/smoke.sh                       # rookproef over de keten
```

Zonder `--profile demo` start compose alleen de infrastructuur (Redis, Postgres,
WireMock, ClickHouse); draai de services dan met `./mvnw quarkus:dev` zodat hot reload
behouden blijft. Gebruik die modus tijdens het ontwikkelen — in een container kost elke
codewijziging een image-build.
````

- [ ] **Stap 4: Volledige testsuite als eindcontrole**

```bash
./mvnw clean test 2>&1 | tail -30
```

Verwacht: `BUILD SUCCESS`. Dit is het sluitstuk van de belofte dat `%test` en `%prod`
ongemoeid zijn gebleven. Controleer ook op nieuwe waarschuwingen: die blokkeren volgens
de projectregels een PR tot ze getrieerd zijn.

- [ ] **Stap 5: Commit**

```bash
git add demo/smoke.sh README.md
git commit -m "test(demo): rookproef over de containerketen

Health-endpoints bewijzen alleen dat processen leven; deze proef levert een
bericht aan en controleert dat het via de uitvraag terugkomt."
```

---

## Definition of done

- [ ] `docker compose up -d` start onveranderd alleen de infrastructuur
- [ ] `docker compose --profile demo up -d` start de volledige keten
- [ ] `./demo/smoke.sh` eindigt met exit 0
- [ ] De bestaande Bruno-collectie (`bruno/berichtenuitvraag/`, `bruno/berichtenmagazijn/`)
      draait groen tegen de containers met de omgeving `lokaal` — de poorten zijn
      identiek aan dev-mode, dus dit hoort zonder aanpassing te werken
- [ ] `./mvnw clean test` is groen, met hetzelfde aantal tests als vóór fase 0
- [ ] `./mvnw quarkus:dev` werkt nog zonder env-vars te zetten
- [ ] Geen wijziging in `%test`- of `%prod`-regels (`git diff main` controleren)
- [ ] README beschrijft beide opstartmodi

## Risico's

| Risico | Signaal | Mitigatie |
|---|---|---|
| Service bindt in de container op localhost | Poort gepubliceerd maar `curl` weigert verbinding | `QUARKUS_HTTP_HOST=0.0.0.0` staat al in de compose-config |
| Flyway nog bezig bij eerste start | `berichtenmagazijn-b` faalt kort na `up` | Herstart de container; `depends_on` wacht op Postgres-health, niet op migraties |
| Aanlever-payload afgekeurd | HTTP 400 met `Problem`-body | Payload corrigeren aan de hand van het antwoord (zie taak 5, stap 2) |
| Ontvanger heeft geen berichtenbox-voorkeur | HTTP 403 bij aanleveren | Afzender-OIN `00000001003214345000` aanhouden; de profiel-stub geeft daarvoor een actieve voorkeur |
| Poortconflict tussen dev-mode en containers | `port is already allocated` | Beide modi gebruiken dezelfde poorten; draai er één tegelijk |

## Niet in deze fase

Toxiproxy (fase 3), de demo-console-module (fase 1), de Berichtenbox-UI (fase 2) en de
stub-magazijnen (fase 6). Fase 0 levert uitsluitend een containeriseerbare bestaande keten.
