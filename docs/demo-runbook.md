# Demo-runbook — PoC Federatief Berichtenstelsel

Zo zet je de demo-stack op en speel je elk scenario. De demo draait volledig lokaal via Docker
Compose. Alle demo-bediening zit in de **wegwerp `demo-console`** (poort 8095); de bestaande services
bevatten géén demo-logica.

Ontwerp en achtergrond: `docs/plans/2026-07-21-demo-platform-design.md` (overkoepelend) en de fase-
documenten `docs/plans/2026-07-2*-demo-platform-fase-*.md`. Wil je niet demonstreren maar
ontwikkelen — tests, gates, linting, de services in dev-mode — zie [`ontwikkelen.md`](ontwikkelen.md).

---

## 1. Vereisten

- **Docker** + Docker Compose.
- **JDK 21** (de Maven-wrapper `./mvnw` regelt de rest). Geen lokale Maven nodig.
- **Apple Silicon (arm64):** jib bouwt standaard `amd64`, want de ZAD-cluster is amd64. Voeg aan
  élk image-build-commando `-Dquarkus.jib.platforms=linux/arm64` toe, anders start de container
  niet (of traag via emulatie). Zet die flag op de commandoregel en **niet** in de POM: vanuit de
  config maak je ook de CI- en ZAD-images arm64, en die draaien dan nergens.
- **Altijd `clean`** bij Maven-builds (we wisselen van branch op een bind-mount; stale `target/`
  geeft misleidende fouten).

---

## 2. Images bouwen (jib, geen Dockerfile)

De demo draait de drie eigen services als container-image (`fbs-demo/…:demo`). Bouw ze met jib:

```bash
./mvnw clean package -DskipTests \
  -pl services/berichtenmagazijn,services/berichtenuitvraag,demo/demo-console -am \
  -Dquarkus.container-image.build=true \
  -Dquarkus.container-image.group=fbs-demo -Dquarkus.container-image.tag=demo \
  -Dquarkus.jib.platforms=linux/arm64        # alleen op Apple Silicon
```

Na een codewijziging in één service volstaat dat ene `-pl services/<naam>` opnieuw te bouwen (zie
§8 voor wat welke wijziging vereist).

---

## 3. Stub-artefacten genereren (altijd, vóór de eerste start)

Eén getal `n` genereert het register, de profiel-stub en n WireMock-mappings in `demo/generated/`
(git-ignored). Dezelfde env-var voedt het script én de demo-console — dus altijd samen exporteren.
Alleen "veel magazijnen" (fase 6) gebruikt die stubs echt, maar de stap is onvoorwaardelijk: zonder
de gegenereerde bestanden start de uitvraag niet (zie de waarschuwing in §4):

```bash
export DEMO_MAGAZIJN_STUBS=40
python3 demo/genereer-magazijnen.py
docker compose --profile demo up -d
```

Wil je n wijzigen: pas `DEMO_MAGAZIJN_STUBS` aan, draai het script opnieuw, en herstart de
uitvraag + stub-magazijnen (§8). Zonder Docker verandert er niets aan de rest van de stack.

---

## 4. Stack starten

Er zijn twee modi met dezelfde compose:

| Commando | Wat draait | Wanneer |
|---|---|---|
| `docker compose up -d` | Alleen infra (Redis, de drie Postgres-instanties, WireMock-stubs) | **Ontwikkelen** — draai services zelf met `./mvnw quarkus:dev` (hot reload) |
| `docker compose --profile demo up -d` | Alles: infra + de drie services + demo-console + Toxiproxy + stub-magazijnen | **Demo** |

> **Draai altijd eerst §3**, óók als je "veel magazijnen" niet demonstreert. `demo/generated/` is
> git-ignored, dus na een verse checkout bestaat het niet. Compose maakt een ontbrekend mount-pad
> zelf aan — als **directory**. `magazijnen-stubs.properties` wordt dan een map,
> `SMALLRYE_CONFIG_LOCATIONS` wijst naar een map, de uitvraag start niet, en het generatiescript
> kan dat bestand daarna ook niet meer schrijven (eerst `rm -rf demo/generated/`). Het is één
> idempotent commando; sla het niet over.

Controleer de keten met een rookproef — aanleveren bij beide magazijnen en ophalen via de uitvraag:

```bash
./demo/smoke.sh
```

Openen na start:
- **Bedieningspaneel:** <http://localhost:8095/>
- **Berichtenbox (ondernemer):** <http://localhost:8095/berichtenbox.html>

Afsluiten: `docker compose --profile demo down` (voeg `-v` toe om de Postgres-volumes te wissen).

> **Waarom CORS geen build-flag is:** CORS is een runtime-property en staat uitsluitend als env-var
> in het demo-profiel van `compose.yaml`; de `application.properties` van `berichtenuitvraag` bevat
> geen CORS-config. Enabled zónder `origins` laat alleen same-origin door, en de UI op `:8095`
> roept de API op `:8086` aan — vandaar de allowlist ernaast in compose. Het prod-profiel zet CORS
> niet aan, dus de ZAD-images blijven CORS-loos zonder dat de build iets hoeft te weten.

### Podman in plaats van Docker

```bash
demo/podman-up.sh                        # kiest zelf de werkbare netwerkmodus
DEMO_HOST=10.0.0.5 demo/podman-up.sh     # ander adres dan localhost in de CORS-allowlist
```

De stack luistert standaard **alleen op `127.0.0.1`**: zonder die grens staan Redis (zonder
wachtwoord), drie PostgreSQL-instanties met demo-credentials en de WireMock-admin-API's op elke
interface van de machine — op een kantoor- of thuisnetwerk dus voor het hele subnet.

Wil je de demo aan iemand anders tonen, dan zet je hem bewust open, en heb je **beide** variabelen
nodig: `DEMO_BIND` voor de poorten en `DEMO_HOST` voor de CORS-allowlist.

```bash
DEMO_BIND=0.0.0.0 DEMO_HOST=10.0.0.5 demo/podman-up.sh
```

`DEMO_BIND` kent maar twee waarden: `127.0.0.1` (default) en `0.0.0.0`. Een specifiek adres wordt
geweigerd, omdat elke controle in `podman-up.sh` over 127.0.0.1 loopt en anders afloopt op een
timeout terwijl de stack gezond draait.

Twee beperkingen daarbij:

- **`DEMO_BIND` geldt alleen in bridge-modus.** In `hostnet` publiceert compose geen poorten en
  binden de containers zelf; die staan vast op loopback, want in een gedeelde netns is een
  wildcard-bind de hele machine — en botst hij bovendien met elke specifieke bind van een
  FSC-federatie in dezelfde netns.
- **Het bedieningspaneel blijft altijd op loopback** (`demo-console`, poort 8095): het heeft geen
  authenticatie en zijn `POST /api/demo/legen` doet een TRUNCATE op beide magazijn-databases.

Het script zoekt de podman-API-socket (start hem zo nodig), kiest een compose-implementatie en
controleert dat die de gestapelde bestanden aankan, controleert dat de drie demo-images gebouwd
zijn, genereert de stub-artefacten, en controleert na elke start dat elke container draait. Redis,
de drie Postgres-instanties, de profiel-stub, de stub-magazijnen, Toxiproxy en de vier services
worden daarnaast functioneel gepolld; de overige WireMock-stubs alleen op "draait".

Twee modi, automatisch bepaald met een probe die zowel het bridge-netwerk als naamresolutie test:

| Modus | Wanneer | Bestand |
|---|---|---|
| `bridge` | normale podman: Linux rootless, of podman machine op macOS/Windows | `compose.podman.yaml` (overlay op `compose.yaml`) |
| `hostnet` | omgevingen zonder bruikbaar bridge-netwerk, bv. podman-in-een-container | `compose.podman-hostnet.yaml` (derde bestand, bovenop de basis en de podman-overlay) |

Forceren kan met `MODUS=bridge` of `MODUS=hostnet`. Buiten Linux wordt `hostnet` nooit
automatisch gekozen: podman draait daar in een VM, dus de gedeelde namespace is die van de VM en
zonder gepubliceerde poorten komt er niets door naar je werkplek.

In `hostnet` delen alle containers één netwerknamespace: de zes Toxiproxy-proxy-listeners, die in
`bridge` alleen intern bestonden, zijn dan ook op de machine zelf zichtbaar. Ze binden — net als
alle andere services in deze modus — op `127.0.0.1`, dus ze zijn niet van buiten de machine
bereikbaar. Een CI-job (`demo-stack-op-loopback`) bewaakt dat: elke listener in de gerenderde merge
moet op loopback staan, anders faalt de build.

Dat is geen detail van deze modus maar de eis die hem werkbaar maakt. In een gedeelde namespace is
een wildcard-bind (`0.0.0.0`) de hele machine — Redis zonder wachtwoord, drie PostgreSQL-instanties
met demo-credentials — en botst hij bovendien met elke specifieke bind op dezelfde poort, zoals die
van een FSC-federatie die in dezelfde namespace draait.

Botst een van die poorten met iets dat al draait, dan hangt het van de poort af hoe dat zich
uit. Een service die zelf niet kan binden stopt, en het script meldt welke container dat is mét
het log waarin de bezette poort staat — voor de vier JVM-services duurt dat wel tot de wachttijd
verstreken is, omdat ze pas tijdens het opstarten struikelen. Botst een Toxiproxy-listener, dan
stopt die container níet: Toxiproxy laadt tot de mislukte proxy en draait door. Het script
vergelijkt daarom de geladen proxies met `proxies.json` en noemt de ontbrekende bij naam. De
overlay haalt de
gepubliceerde poorten en de healthchecks met `!reset` weg; dat vereist compose v2.24.4 of nieuwer
en werkt niet met `podman-compose`. Het script rendert daarom eerst de samengestelde configuratie
en controleert dat er geen gepubliceerde poort meer in staat — een implementatie die de tag
negeert in plaats van toepast, valt daar door de mand.

Afsluiten met dezelfde socket en dezelfde overlays als het script gebruikte:

```bash
export DOCKER_HOST="unix://${XDG_RUNTIME_DIR:-/run/user/$(id -u)}/podman/podman.sock"
docker compose -f compose.yaml -f compose.podman.yaml --profile demo down
# in hostnet: -f compose.podman-hostnet.yaml vóór --profile toevoegen
```

---

## 5. Onderdelen en poorten

| Component | Poort | Rol |
|---|---|---|
| demo-console | 8095 | Bedieningspaneel + Berichtenbox-UI |
| berichtenuitvraag | 8086 | Ophalen/tonen/beheren van berichten |
| berichtenmagazijn-a / -b | 8090 / 8091 | Twee echte magazijnen (RVO / Belastingdienst) |
| magazijn-stubs | 8092 | Eén WireMock met n pad-gebaseerde stub-magazijnen (`/mNN`) |
| magazijn-a / -b (WireMock) | 8081 / 8082 | Overblijfsel-stubs; starten óók zonder `--profile demo`, maar niets bevraagt ze |
| toxiproxy | 8474 (admin) | Netwerkstoringen tussen uitvraag/magazijn en afhankelijkheden |
| profiel-service | 8089 | Profiel-stub (welke magazijnen per persona) |
| redis | 6379 | Sessiecache + ontdubbel-markers |
| aanmeld-stub / notificatie-stub | 8083 / 8084 | Downstreams van de publicatiestroom |
| postgres-a / -b | 5432 / 5433 | Databases van de echte magazijnen, inclusief hun eigen logboek |
| postgres-uitvraag | 5434 | Logboek Dataverwerkingen (LDV) van de uitvraag |

De uitvraag loopt in de demo door Toxiproxy voor redis, profiel en de twee echte magazijnen; de
magazijn-downstreams (aanmeld, notificatie) lopen óók door Toxiproxy zodat ze per knop uit kunnen.

---

## 5b. Berichtenbox van de proeftuin

**Eén adres voor de hele demo: <http://127.0.0.1:8097/bediening/>.** Daar staat de berichtenbox van
de proeftuin met de bediening ernaast; "Bediening verbergen" geeft de berichtenbox de volle breedte
voor het moment waarop je laat zien wat de ondernemer ziet.

Dat adres is een kleine nginx (`demo-proxy`) die alles achter één origin zet: `/` naar de proeftuin,
`/bediening/` en `/api/demo/` naar de demo-console, `/api/v1/` naar de uitvraag. Zonder die gedeelde
origin komt de personalijst niet aan — binnen de proeftuin-container valt `/api/demo/personas` onder
zijn eigen `location /api/` en zou het bij de uitvraag uitkomen — en kan het paneel de berichtenbox
niet laten verversen. Online geldt dit niet: daar proxyt de proeftuin zelf.

Geen Node of Eleventy nodig. De image-tag is gepind en met een env-var te wisselen:

```bash
PROEFTUIN_TAG=gebruikersonderzoeken-2026-08 docker compose --profile demo up -d proeftuin
```

De losse adressen blijven bestaan om te debuggen: de proeftuin zelf op `:8096` (in podman-hostnet
`:8080`, want die container kan zijn luisterpoort niet verzetten) en het kale paneel op `:8095`.
Open je het paneel daar, dan blijft het frame leeg met een verwijzing naar de proxy — de proeftuin
staat dan op een andere origin.

---

## 6. Persona's (Berichtenbox → "Ingelogd als")

Bron: `demo.personas.*` in `demo/demo-console/src/main/resources/application.properties`. De
berichtenbox haalt de lijst op bij `GET /api/demo/personas`, dus de keuzelijst volgt een wijziging
in dat bestand vanzelf — de tabel hieronder niet, die werk je met de hand bij.

| Persona | Identificatie | Bevraagt |
|---|---|---|
| J. Pietersen | BSN `999993653` | RVO + Belastingdienst (beide echte magazijnen) |
| Bakkerij De Vroege Vogel | BSN `999996666` | RVO |
| Garage Van Dijk B.V. | KVK `12345678` | Belastingdienst |
| Grootbedrijf B.V. | KVK `90000001` | n stub-magazijnen (fase 6) |

Welke magazijnen een persona bevraagt, bepaalt de profiel-stub (opt-in per afzender-OIN). Kies een
persona, klik **Ophalen** (start de sessie + haalt op), daarna **Vernieuw** (leest alleen de cache).

---

## 7. Bedieningspaneel

**Beheer**
- *Herstel demo* — stopt een lopende stroom, legt de magazijnen leeg en laadt de basisvulling
  opnieuw; brengt de omgeving in één klik terug naar de begintoestand.
- *Magazijnen legen* — TRUNCATE op beide echte magazijn-databases (leeg vóór je opnieuw vult).
- *Status* — aantal berichten per magazijn.
- *Cache verlopen* — wist alle sessie-keys in Redis (`berichtensessiecache:v1:*`); de volgende
  `GET /berichten` geeft dan 409 tot je opnieuw ophaalt.

**Vullen**
- *Basisvulling laden* — vaste dataset via de echte aanlever-API (validatie + publicatieketen lopen mee).
- *Random berichten opvoeren* — N random berichten; tegelijk het "nieuwe berichten tijdens de sessie"-scenario.
- *Stroom starten/stoppen/status* — levert elke *n* seconden (1–3600) automatisch één gegenereerd
  bericht aan, tot een handmatige stop of tot de ingebouwde grens (500 berichten of 60 minuten,
  wat het eerst komt). Een tweede *start* vervangt de lopende stroom in plaats van te stapelen.

**Storingen (fase 3)** — de twee echte magazijnen via Toxiproxy: A/B traag (~6 s) of uit; *reset* herstelt.

**Technische scenario's (fase 5)** — Redis/profiel/notificatie/aanmeld uit; foutieve aanlevering; ontdubbeling.
Herstellen via *Alles normaal (reset)* in de Storingen-sectie.

**Veel magazijnen (fase 6)** — *Actief aantal* zet magazijnen `k+1..n` op storing (503); *reset* zet alles weer aan.

---

## 8. Herbouwen na een wijziging

| Wat je wijzigde | Wat nodig is |
|---|---|
| Kotlin/resources in een service | jib-rebuild van dat image (§2, `-pl services/<naam>`) → `docker compose --profile demo up -d <service>` |
| `demo/genereer-magazijnen.py` of `DEMO_MAGAZIJN_STUBS` | Regenereer (§3) → `docker compose --profile demo up -d --force-recreate magazijn-stubs berichtenuitvraag` |
| `compose.yaml` (env/mount) | `docker compose --profile demo up -d --force-recreate <service>` (geen rebuild) |
| `toxiproxy/proxies.json` | `docker compose --profile demo up -d --force-recreate toxiproxy` |
| WireMock-mappings onder `wiremock/magazijn-a/` of `-b/` | `docker compose restart magazijn-a` (of `-b`) — raakt de demo niet; die containers worden door niets bevraagd |

De uitvraag leest het stub-register uit een gemount bestand (`SMALLRYE_CONFIG_LOCATIONS`) bij boot —
regenereren vraagt dus een uitvraag-herstart. WireMock laadt mappings alleen bij startup, dus
magazijn-stubs herstarten na regenereren.

---

## 9. De scenario's spelen

De demo dekt de 14 scenario's uit de eisen. 13 zijn nu speelbaar; #14 (load/stress) en de rode vlag
volgen in fase 7.

| # | Scenario | Zo speel je het |
|---|---|---|
| 1 | Berichten succesvol opgehaald | Basisvulling → persona Pietersen → **Ophalen** |
| 2 | Trager dan normaal (>5 s) | *Magazijn A/B traag* → Ophalen; magazijn meldt pas na ~6 s "voltooid" |
| 3 | Magazijnen onbereikbaar (weinig/veel) | Echte: *Magazijn A/B uit*. Veel: persona Grootbedrijf → *Actief aantal* op bv. 2 → Ophalen → n−2 FOUT + partiële lijst |
| 4 | Enkele magazijnen antwoorden laat | *Magazijn A traag* terwijl B normaal → Ophalen |
| 5 | Nieuwe berichten tijdens de sessie | Persona haalt op → *Random opvoeren* → **Vernieuw** toont de nieuwe berichten |
| 6 | Cache-tijd verloopt | *Cache verlopen* (knop), of ~2 min niets doen (demo-TTL is `PT2M`) → volgende actie geeft 409 |
| 7 | Bijlage wordt niet opgehaald | *Magazijn A uit* → open een RVO-bericht (uit de cache) → bijlage-download faalt |
| 8 | Foutieve aanlevering | *Foutieve aanlevering* → 400 RFC 9457 problem+json in het paneel |
| 9 | Profielservice weg | *Profielservice uit* → Ophalen kan de magazijnenlijst niet resolven |
| 10 | Notificatieservice weg | *Notificatie uit* → *Random opvoeren* → bericht verschijnt tóch via Vernieuw (aanmeld slaagt; notificatie retryt) |
| 11 | Uitvraagsysteem eruit | *Uitvraag/aanmeld uit* → *Random opvoeren* → bericht verschijnt níet bij Vernieuw; reset → outbox levert alsnog af |
| 12 | Redis weg | *Redis uit* → `GET /berichten` geeft 502 problem+json (geen kale 500); *Cache verlopen* blijft werken |
| 13 | Ontdubbeling | Persona **eerst Ophalen** (actieve sessie!) → *Ontdubbeling* → precies één nieuw bericht |
| 14 | Load/stress | **Fase 7** — k6-script, nog te bouwen |

**Rode vlag (markeren als belangrijk):** nog te bouwen (fase 7 — productiecode door de hele keten).

Na een storingsscenario altijd *Alles normaal (reset)* (Storingen-sectie) en voor veel-magazijnen
*Alle magazijnen aan (reset)*.

---

## 10. Valkuilen

- **Genereer vóór `up`** voor veel-magazijnen; anders zijn de mounts leeg (geen stubs/register).
- **`export DEMO_MAGAZIJN_STUBS=N`** voedt script én console; laat ze niet uiteenlopen (anders klopt
  de k-schuif niet met het aantal magazijnen).
- **Bulkhead** staat in de demo op 60 (`BERICHTENSESSIECACHE_MAGAZIJN_BULKHEAD_MAX_CONCURRENT`). Bij
  n > 60 wijst de uitvraag de overtollige magazijn-calls direct af als "systeem druk" (OVERBELAST) —
  dat is bewust fail-fast-gedrag, geen bug.
- **Demo-cache-TTL is 2 minuten.** Pauzeer je langer tussen Ophalen en een vervolgactie, dan is de
  sessie verlopen (409). Realistisch (flow 6), maar hou er rekening mee tijdens het presenteren.
- **Ontdubbeling en de live-push** vereisen een actieve sessie: laat de persona eerst **Ophalen**.
- **Twee keer vullen zonder legen** geeft dubbele berichten (het magazijn kent eigen ID's toe) —
  daarom eerst *Magazijnen legen*.
