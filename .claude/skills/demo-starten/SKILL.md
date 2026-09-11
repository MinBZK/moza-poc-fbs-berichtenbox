---
name: demo-starten
description: Start de demo-stack lokaal en rijd de keten door met de rookproef, inclusief de valkuilen die niet in de scripts staan
disable-model-invocation: true
---

# Demo starten

Deze workflow start containers en wordt daarom alleen op verzoek gedraaid.

De route is `bouwen → genereren → starten → rookproef`. De volledige rondleiding staat in
[`docs/demo-runbook.md`](../../../docs/demo-runbook.md); hier staat de kortste weg plus wat de
scripts zélf niet vertellen.

## 1. Bouw de images

De eigen services draaien als image en worden niet gepulld — die bouw je met jib. Sla je dit over,
dan meldt compose `denied: requested access to the resource is denied` op `fbs-demo/…:demo`, en die
melding wijst niet naar de overgeslagen bouwstap.

```bash
./mvnw clean package -DskipTests \
  -pl services/berichtenmagazijn,services/berichtenuitvraag,demo/demo-console,demo/demo-personas,demo/magazijn-simulator -am \
  -Dquarkus.container-image.build=true \
  -Dquarkus.container-image.group=fbs-demo -Dquarkus.container-image.tag=demo
```

Op Apple Silicon hoort `-Dquarkus.jib.platforms=linux/arm64` erbij: jib bouwt standaard amd64,
want de ZAD-cluster is amd64.

## 2. Genereer de stub-artefacten

```bash
python3 demo/genereer-magazijnen.py
```

Eén getal levert het magazijnregister, de set van de simulator en de vier ondernemers (3, 15, 45 en
100 aangesloten organisaties). Alles landt in `demo/generated/` en is git-ignored.

## 3. Start de stack

Docker:

```bash
docker compose --profile demo up -d
```

Podman:

```bash
demo/podman-prepare.sh bridge     # of: hostnet
demo/podman-up.sh                 # kiest zelf de werkbare netwerkmodus
```

Daarna staat de hele demo op één adres: <http://127.0.0.1:8097/bediening/> — de berichtenbox uit de
proeftuin met het bedieningspaneel ernaast.

## 4. Rijd de keten door

```bash
demo/smoke.sh
```

Health-endpoints bewijzen alleen dat processen leven. De rookproef levert een bericht aan bij
magazijn A én B en controleert dat beide via de uitvraag terugkomen — dat is geen luxe: de uitvraag
is by design degradatie-tolerant, dus met alleen A's bericht is de proef ook groen terwijl B
onbereikbaar is.

## De valkuilen die niet in de scripts staan

- **In `hostnet` binden de containers op loopback, en dat is niet te overrulen.** `DEMO_BIND` geldt
  alleen in bridge-modus; in een gedeelde netns is een wildcard-bind de hele machine. Wil je de
  demo van buiten laten zien, dan is bridge-modus met `DEMO_BIND=0.0.0.0 DEMO_HOST=<adres>` de
  route, of een forwarder op de externe interface.
- **Een kale HTTP 400 zonder body is Host-validatie, geen netwerkfout.** Benader je de demo op een
  ander adres dan de allowlist kent, dan weigert Quarkus het verzoek voordat er iets van de
  applicatie aan te pas komt. Zet dan `QUARKUS_HTTP_HOST_VALIDATION_ALLOWED_HOSTS` — en ga niet op
  zoek naar een proxy- of DNS-probleem.
- **Het bedieningspaneel blijft altijd op loopback** (poort 8095). Het heeft geen authenticatie en
  zijn `POST /api/demo/legen` doet een TRUNCATE op beide magazijn-databases.
- **De stack bezet poort 8081.** Elke `@QuarkusTest` faalt daarop met "Failed to start quarkus" /
  "Port already bound" — een melding die de oorzaak niet noemt. De stack hoeft niet plat: voeg
  `-Dquarkus.http.test-port=0` toe aan de Maven-aanroep.
- **Een storing uitlokken doe je hier, niet op ZAD.** De storingsknoppen van het magazijn bestaan
  daar niet, en een profiel-storing raakt de aanlevering er niet: die gaat direct, niet via
  toxiproxy.

## Herbouwen na een wijziging

| Gewijzigd | Wat er moet |
|-----------|-------------|
| Kotlin of resources in een service | jib-rebuild van dat ene image (stap 1, met `-pl services/<naam>`) → `docker compose --profile demo up -d <service>` |
| `demo/genereer-magazijnen.py` of `DEMO_MAGAZIJNEN` | Regenereren (stap 2) → `docker compose --profile demo up -d --force-recreate magazijn-simulator berichtenuitvraag` |

## Opruimen

```bash
docker compose --profile demo down -v
```
