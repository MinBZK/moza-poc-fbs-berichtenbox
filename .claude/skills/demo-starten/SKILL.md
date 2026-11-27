---
name: demo-starten
description: Start de demo-stack lokaal en rijd de keten door met de rookproef, inclusief de valkuilen die niet in de scripts staan
disable-model-invocation: true
---

# Demo starten

Deze workflow start containers en wordt daarom alleen op verzoek gedraaid.

De route is `bouwen → starten → rookproef`. De volledige rondleiding staat in
[`docs/demo-runbook.md`](../../../docs/demo-runbook.md); hier staat de kortste weg plus wat de
scripts zélf niet vertellen.

## 1. Bouw de images

De eigen services draaien als image en worden niet gepulld — die bouw je met jib. Sla je dit over,
dan meldt compose `denied: requested access to the resource is denied` op `fbs-demo/…:demo`, en die
melding wijst niet naar de overgeslagen bouwstap.

```bash
./mvnw clean package -DskipTests \
  -pl services/berichtenmagazijn,services/berichtenuitvraag,services/stelselregister,demo/demo-console,demo/demo-personas,demo/magazijn-simulator -am \
  -Dquarkus.container-image.build=true \
  -Dquarkus.container-image.group=fbs-demo -Dquarkus.container-image.tag=demo
```

Op Apple Silicon hoort `-Dquarkus.jib.platforms=linux/arm64` erbij: jib bouwt standaard amd64,
want de ZAD-cluster is amd64.

## 2. Start de stack

```bash
demo/up.sh
```

Op Docker en op podman hetzelfde commando: het script kiest zelf de modus (`docker`, `bridge` of
`hostnet`), genereert de stub-artefacten en de sleutelketen van het stelselregister, en wacht tot
elke service gezond is. Daarna staat de hele demo op één adres: <http://127.0.0.1:8097/bediening/> —
de Berichtenbox van het MOZa-portaal met het bedieningspaneel ernaast.

## 3. Rijd de keten door

```bash
demo/smoke.sh
```

Health-endpoints bewijzen alleen dat processen leven. De rookproef levert een bericht aan bij
magazijn A én B en controleert dat beide via de uitvraag terugkomen — dat is geen luxe: de uitvraag
is by design degradatie-tolerant, dus met alleen A's bericht is de proef ook groen terwijl B
onbereikbaar is.

De laatste stap vraagt de Berichtenbox van het portaal op via de demo-proxy (`:8097`), met alleen
het persona-cookie, zoals een browser dat doet. Daarna start hij via het portaal een sessie, leest
de lijst met het sessie-cookie dat het portaal zet (200 of 409), en beëindigt de sessie weer.

## De valkuilen die niet in de scripts staan

- **In `hostnet` binden de containers op loopback, en `DEMO_BIND` verandert daar niets aan.**
  `DEMO_BIND` geldt alleen in `docker` en `bridge`; in een gedeelde netns is een wildcard-bind de
  hele machine. Alleen de demo-proxy volgt `DEMO_BEDIENING_BIND`. Wil je de demo van buiten laten
  zien, gebruik dan een tunnel naar `127.0.0.1:8097`. Het portaal zet zijn persona-cookie met
  `Secure`, en dat bewaart een browser over gewone HTTP alleen voor loopback; via
  `DEMO_BEDIENING_BIND=0.0.0.0` op een ander adres onthoudt de Berichtenbox geen persona.
- **Een kale HTTP 400 zonder body is Host-validatie, geen netwerkfout.** Roep je een magazijn
  rechtstreeks op zijn poort aan via een ander adres dan localhost, bijvoorbeeld het adres uit het
  stelseldocument, dan weigert Quarkus het verzoek voordat er iets van de
  applicatie aan te pas komt. Zet dan `QUARKUS_HTTP_HOST_VALIDATION_ALLOWED_HOSTS` — en ga niet op
  zoek naar een proxy- of DNS-probleem.
- **Het bedieningspaneel blijft standaard op loopback** (poort 8095), ook met `DEMO_BIND`; alleen
  `DEMO_BEDIENING_BIND` zet het open. Het heeft geen authenticatie en zijn `POST /api/demo/legen`
  doet een TRUNCATE op beide magazijn-databases.
- **Een storing uitlokken doe je hier, niet op ZAD.** De storingsknoppen van het magazijn bestaan
  daar niet, en een profiel-storing raakt de aanlevering er niet: die gaat direct, niet via
  toxiproxy.

## Herbouwen na een wijziging

| Gewijzigd | Wat er moet |
|-----------|-------------|
| Kotlin of resources in een service | jib-rebuild van dat ene image (stap 1, met `-pl services/<naam>`) → `demo/up.sh compose up -d <service>` |
| `demo/genereer-magazijnen.py` of `DEMO_MAGAZIJNEN` | `DEMO_MAGAZIJNEN=<n> demo/up.sh` → `demo/up.sh compose up -d --force-recreate magazijn-simulator berichtenuitvraag`. Niet `python3 demo/genereer-magazijnen.py` los: dat schrijft containernamen, en die resolven in `hostnet` niet |

## Opruimen

```bash
demo/up.sh down -v        # met de compose-bestanden van de laatste start
```
