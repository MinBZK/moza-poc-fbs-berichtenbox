# Demo-runbook — FBS Berichtenbox

Zo zet je de demo-stack op en speel je elk scenario. De demo draait volledig lokaal via Docker
Compose. Alle demo-bediening zit in de **wegwerp `demo-console`** (poort 8095); de bestaande services
bevatten géén demo-logica.

Ontwerp en achtergrond: `docs/plans/2026-07-21-demo-platform-design.md` (overkoepelend) en de fase-
documenten `docs/plans/2026-07-2*-demo-platform-fase-*.md`.

---

## 1. Vereisten

- **Docker** + Docker Compose.
- **JDK 21** (de Maven-wrapper `./mvnw` regelt de rest). Geen lokale Maven nodig.
- **Apple Silicon (arm64):** jib bouwt standaard `amd64`. Voeg aan élk image-build-commando
  `-Dquarkus.jib.platforms=linux/arm64` toe, anders start de container niet (of traag via emulatie).
- **Altijd `clean`** bij Maven-builds (we wisselen van branch op een bind-mount; stale `target/`
  geeft misleidende fouten).

---

## 2. Images bouwen (jib, geen Dockerfile)

De demo draait de drie eigen services als container-image (`fbs-demo/…:demo`). Bouw ze met jib:

```bash
./mvnw clean package -DskipTests \
  -pl services/berichtenmagazijn,services/berichtenuitvraag,services/demo-console -am \
  -Dquarkus.container-image.build=true \
  -Dquarkus.container-image.group=fbs-demo -Dquarkus.container-image.tag=demo \
  -Dquarkus.jib.platforms=linux/arm64        # alleen op Apple Silicon
```

Na een codewijziging in één service volstaat dat ene `-pl services/<naam>` opnieuw te bouwen (zie
§8 voor wat welke wijziging vereist).

---

## 3. Stack starten

Er zijn twee modi met dezelfde compose:

| Commando | Wat draait | Wanneer |
|---|---|---|
| `docker compose up -d` | Alleen infra (Redis, Postgres, WireMock-stubs, ClickHouse) | **Ontwikkelen** — draai services zelf met `./mvnw quarkus:dev` (hot reload) |
| `docker compose --profile demo up -d` | Alles: infra + de drie services + demo-console + Toxiproxy + stub-magazijnen | **Demo** |

> **Belangrijk voor "veel magazijnen" (fase 6):** genereer eerst de stub-artefacten (§4), anders
> mounten de compose-volumes lege mappen. Voor alle andere scenario's is dat niet nodig.

Openen na start:
- **Bedieningspaneel:** <http://localhost:8095/>
- **Berichtenbox (ondernemer):** <http://localhost:8095/berichtenbox.html>

Afsluiten: `docker compose --profile demo down` (voeg `-v` toe om de Postgres-volumes te wissen).

---

## 4. "Veel magazijnen" voorbereiden (alleen voor fase 6)

Eén getal `n` genereert het register, de profiel-stub en n WireMock-mappings in `demo/generated/`
(git-ignored). Dezelfde env-var voedt het script én de demo-console — dus altijd samen exporteren:

```bash
export DEMO_MAGAZIJN_STUBS=40
python3 demo/genereer-magazijnen.py
docker compose --profile demo up -d
```

Wil je n wijzigen: pas `DEMO_MAGAZIJN_STUBS` aan, draai het script opnieuw, en herstart de
uitvraag + stub-magazijnen (§8). Zonder Docker verandert er niets aan de rest van de stack.

---

## 5. Onderdelen en poorten

| Component | Poort | Rol |
|---|---|---|
| demo-console | 8095 | Bedieningspaneel + Berichtenbox-UI |
| berichtenuitvraag | 8086 | Ophalen/tonen/beheren van berichten |
| berichtenmagazijn-a / -b | 8090 / 8091 | Twee echte magazijnen (RVO / Belastingdienst) |
| magazijn-stubs | 8092 | Eén WireMock met n pad-gebaseerde stub-magazijnen (`/mNN`) |
| toxiproxy | 8474 (admin) | Netwerkstoringen tussen uitvraag/magazijn en afhankelijkheden |
| profiel-service | 8089 | Profiel-stub (welke magazijnen per persona) |
| redis | 6379 | Sessiecache + ontdubbel-markers |
| aanmeld-stub / notificatie-stub | 8083 / 8084 | Downstreams van de publicatiestroom |
| postgres-a / -b | 5432 / 5433 | Databases van de echte magazijnen |
| clickhouse | 8123 | Logboek Dataverwerkingen (LDV) |

De uitvraag loopt in de demo door Toxiproxy voor redis, profiel en de twee echte magazijnen; de
magazijn-downstreams (aanmeld, notificatie) lopen óók door Toxiproxy zodat ze per knop uit kunnen.

---

## 6. Persona's (Berichtenbox → "Ingelogd als")

| Persona | Identificatie | Bevraagt |
|---|---|---|
| J. Pietersen (ZZP) | BSN `999993653` | RVO + Belastingdienst (beide echte magazijnen) |
| Bakkerij De Vroege Vogel | BSN `123456782` | RVO |
| Garage Van Dijk B.V. | KVK `12345678` | Belastingdienst |
| Grootbedrijf B.V. | KVK `90000001` | n stub-magazijnen (fase 6) |

Welke magazijnen een persona bevraagt, bepaalt de profiel-stub (opt-in per afzender-OIN). Kies een
persona, klik **Ophalen** (start de sessie + haalt op), daarna **Vernieuw** (leest alleen de cache).

---

## 7. Bedieningspaneel

**Beheer**
- *Magazijnen legen* — TRUNCATE op beide echte magazijn-databases (leeg vóór je opnieuw vult).
- *Status* — aantal berichten per magazijn.
- *Cache verlopen* — wist alle sessie-keys in Redis (`berichtensessiecache:v1:*`); de volgende
  `GET /berichten` geeft dan 409 tot je opnieuw ophaalt.

**Vullen**
- *Basisvulling laden* — vaste dataset via de echte aanlever-API (validatie + publicatieketen lopen mee).
- *Random berichten opvoeren* — N random berichten; tegelijk het "nieuwe berichten tijdens de sessie"-scenario.

**Storingen (fase 3)** — de twee echte magazijnen via Toxiproxy: A/B traag (~6 s) of uit; *reset* herstelt.

**Technische scenario's (fase 5)** — Redis/profiel/notificatie/aanmeld uit; foutieve aanlevering; ontdubbeling.
Herstellen via *Alles normaal (reset)* in de Storingen-sectie.

**Veel magazijnen (fase 6)** — *Actief aantal* zet magazijnen `k+1..n` op storing (503); *reset* zet alles weer aan.

---

## 8. Herbouwen na een wijziging

| Wat je wijzigde | Wat nodig is |
|---|---|
| Kotlin/resources in een service | jib-rebuild van dat image (§2, `-pl services/<naam>`) → `docker compose --profile demo up -d <service>` |
| `demo/genereer-magazijnen.py` of `DEMO_MAGAZIJN_STUBS` | Regenereer (§4) → `docker compose --profile demo up -d --force-recreate magazijn-stubs berichtenuitvraag` |
| `compose.yaml` (env/mount) | `docker compose --profile demo up -d --force-recreate <service>` (geen rebuild) |
| `toxiproxy/proxies.json` | `docker compose --profile demo up -d --force-recreate toxiproxy` |
| WireMock-mappings van een echt stub-magazijn | `docker compose restart magazijn-a` (of `-b`) |

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
