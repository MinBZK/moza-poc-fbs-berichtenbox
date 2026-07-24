**Status:** Concept

# Demo-platform fase 5 — technische verantwoording — ontwerp

**Overkoepelend ontwerp:** `docs/plans/2026-07-21-demo-platform-design.md`

**Doel:** de technische degradatie-scenario's 8–13 uit de eisen demonstreerbaar maken,
bediend vanuit de demo-console, zonder demo-logica in de productiecode. Zes van de zeven
technische punten; load/stress (#14) blijft fase 7.

## Context

Fase 3 zette Toxiproxy tussen de **uitvraag** en zijn afhankelijkheden (magazijn a/b,
redis, profiel) en bouwde de magazijn-knoppen (traag/uit). De Redis- en profiel-proxies
staan al klaar maar hebben nog geen knop. De **magazijn-downstreams** (notificatie,
aanmeld→uitvraag) lopen nu nog *direct*, niet door Toxiproxy — die moeten er nog bij.
Twee scenario's zijn geen netwerkstoring maar een payload-scenario en horen in de
demo-console zelf.

## Scenario-indeling

Fase 5 valt in drie soorten werk.

### A. Knop op een bestaande proxy

De uitvraag loopt al door Toxiproxy voor redis en profiel. Alleen een knop ontbreekt.

| # | Scenario | Mechanisme | Verwacht gedrag |
|---|---|---|---|
| 12 | Redis weg | `redis`-proxy `disable` | `GET /berichten` → Redis onbereikbaar → `SessiecacheException.Onbereikbaar` → in `UpstreamFault` non-4xx herverpakt tot **502 Bad Gateway** problem+json mét correlation-id. **Geen kale 500.** De console's cache-verval-knop blijft werken: die praat *direct* met Redis (`redis://redis:6379`), niet door Toxiproxy. |
| 9 | Profiel weg | `profiel`-proxy `disable` | Bij *Ophalen* resolvet de uitvraag de magazijnenlijst van de persona via de profielservice. Profiel weg → die resolutie faalt → gedegradeerd ophalen. De exacte status/gedraging wordt tijdens de Docker-run geverifieerd (geen harde claim in dit ontwerp). |

Geen codewijziging; alleen een generieke storing-endpoint + knoppen (soort B-werk hieronder
maakt ze generiek).

### B. Nieuwe magazijn-downstream-proxies

De magazijn-outbox levert na een succesvolle aanlevering **twee onafhankelijke deliveries**
af — één rij per downstream in `publicatie_deliveries`, elk met eigen status, pogingen-teller
en `volgende_poging` (UNIQUE op `(bericht_db_id, doel)`). Faalt de ene downstream, dan retryt
alleen díe rij; de andere wordt gewoon terminal `GEPUBLICEERD`. Dat maakt twee scherpe
verhalen mogelijk:

| # | Scenario | Wat je toont |
|---|---|---|
| 10 | Notificatieservice weg | `notificatie`-delivery retryt (WARN, exponentiële backoff), **aanmeld-delivery slaagt** → het bericht belandt tóch live in de sessiecache en verschijnt bij *Vernieuw*. Bewijst dat berichtbeschikbaarheid niet aan notificatie hangt. |
| 11 | Uitvraagsysteem eruit | **Interpretatie: de aanmeld-delivery (magazijn→uitvraag) valt uit.** Die retryt; notificatie slaagt. De live-push stopt → een nieuw bericht verschijnt **niet** bij *Vernieuw*. De ondernemer ziet zijn bestaande berichten nog wél (browser→uitvraag loopt niet door Toxiproxy). Re-enable → de outbox levert het gebufferde bericht alsnog af en het verschijnt. Bewijst outbox-buffering + eventual delivery. |

**Topologie-uitbreiding.** Twee proxies erbij:

```json
{ "name": "notificatie", "listen": "0.0.0.0:18084", "upstream": "notificatie-stub:8080",    "enabled": true },
{ "name": "aanmeld",     "listen": "0.0.0.0:18086", "upstream": "berichtenuitvraag:8086",    "enabled": true }
```

Op **beide** magazijnen (`berichtenmagazijn-a`/`-b`) wordt de env herrouteerd:

```yaml
NOTIFICATIE_URL: http://toxiproxy:18084/events
AANMELD_URL:     http://toxiproxy:18086/api/v1/aanmeldingen
```

en `depends_on: { toxiproxy: { condition: service_started } }` toegevoegd. Toxiproxy is een
transparante TCP-forwarder; bij normaal bedrijf verandert er niets. Startvolgorde is
acyclisch (toxiproxy → magazijnen → uitvraag → console); dat de uitvraag bij magazijn-boot
nog niet klaar is, maakt niet uit — de outbox levert asynchroon af.

**Poll-timing voor een live demo.** De outbox-poller draait op
`magazijn.publicatie.polling.interval` (default `60s`) en een gefaalde delivery gaat na
`max-pogingen` (default 5) terminal `MISLUKT`. Met 60s-polling duurt "uit → herstel" tot een
minuut, en met 5 pogingen kan een delivery terminal gaan terwijl de presentator uitlegt.
Beide worden in het demo-profiel via env verkort/verruimd op de magazijnen (Quarkus mapt elke
property op een env-var; geen `${VAR:default}` in de property nodig):

```yaml
MAGAZIJN_PUBLICATIE_POLLING_INTERVAL: 5s
MAGAZIJN_PUBLICATIE_DOWNSTREAMS_AANMELD_MAX_POGINGEN: "50"
MAGAZIJN_PUBLICATIE_DOWNSTREAMS_NOTIFICATIE_MAX_POGINGEN: "50"
```

Env-only, geen codewijziging; `%test`/`%prod` ongemoeid. De exacte env-mapping wordt tijdens
de Docker-run bevestigd (de eerste delivery-poging na publicatie zichtbaar binnen ~5s, en een
uitgeschakelde downstream blijft retryen tot re-enable i.p.v. terminal te gaan).

### C. Nieuwe demo-console-features

| # | Scenario | Aanpak |
|---|---|---|
| 8 | Foutieve aanlevering | De console POST't een ongeldige payload naar magazijn A `POST /berichten` (`application/json`) en toont de **400 RFC 9457 problem+json** in het paneel. Headline-fout: een ontvanger-BSN die de elfproef niet haalt → `DomainValidationException` → 400. |
| 13 | Ontdubbeling | De console bouwt één CloudEvent (`type=nl.rijksoverheid.fbs.bericht.gepubliceerd`, `specversion=1.0`, `source=urn:nld:…`, afzender=RVO-OIN, ontvanger=persona-BSN) en POST't hem **tweemaal met hetzelfde `id`** naar de uitvraag `POST /api/v1/aanmeldingen` (`application/cloudevents+json`). Dedup op het CloudEvents-`id` in keyspace `aanmeld:event:` → **één** bericht. |

**Foutieve aanlevering — raw JSON.** De bestaande `MagazijnAanleverClient` stuurt getypeerde,
geldige DTO's. Voor scenario 8 wordt een client-methode toegevoegd die een **rauwe
JSON-string** POST't, zodat de console de ongeldigheid volledig zelf bepaalt (ook vormen die
de getypeerde DTO niet kan uitdrukken, zoals een ontbrekend veld). De service bouwt de
ongeldige body, POST't, en geeft `{status, body}` terug — de 400 problem+json wordt letterlijk
in het paneel getoond. De headline is de elfproef-fout; een tweede variant (ontbrekend
verplicht veld) is een triviale uitbreiding en wordt genoemd maar niet standaard geknopt.

**Ontdubbeling — observatie via de UI.** Beide POST's geven `202 Accepted` zonder body; de
console kan uit het antwoord niet zien wélke gededupliceerd is. Het bewijs is zichtbaar in de
Berichtenbox: na twee identieke pushes staat er **één** nieuw bericht. Dat werkt alleen als de
ontvanger een **actieve sessie** heeft — anders geeft de dedup de marker weer vrij en verschijnt
er niets (i.p.v. één). De demo-instructie is daarom expliciet: persona eerst laten *Ophalen*,
dán de knop. De ontvanger-BSN is een parameter (default J. Pietersen, `999993653`); het `id` en
`berichtId` worden per klik vers gegenereerd (anders dedupt de klik tegen de vorige, want de
marker leeft 24 u), maar zijn binnen één klik voor beide POST's gelijk.

## Contracten (uit de codebase geverifieerd)

**Aanmeld-webhook** — `POST /api/v1/aanmeldingen`, `Content-Type: application/cloudevents+json`,
antwoord `202` zonder body. Verplicht (afgedwongen in `AanmeldService.parse()`): `id` niet blank,
`specversion == "1.0"`, `source` begint met `urn:nld:`, `type == nl.rijksoverheid.fbs.bericht.gepubliceerd`,
`data` met `{berichtId, afzender (geldig OIN van een geconfigureerd magazijn), ontvanger{type,waarde}
(geldig identificatienummer), onderwerp, inhoud, publicatietijdstip}`. De afzender-OIN moet bij een
geconfigureerd magazijn horen (RVO `00000001003214345000` of Belastingdienst `00000001823288444000`).
Dedup: `SET aanmeld:event:<id> 1 NX EX <ttl>`, TTL default PT24H.

**Aanlever-API** — `POST /berichten`, `Content-Type: application/json`. `required:
[afzender, ontvanger, onderwerp, inhoud]`; `afzender` `^[0-9]{20}$`; `ontvanger` `{type∈[BSN,RSIN,KVK,OIN],
waarde ^[0-9]{1,20}$}` met elfproef/lengte per type in de service-laag; `onderwerp` 1..255; `inhoud`
1..1048576. 400's komen als `application/problem+json` uit `ProblemExceptionMapper` (gesaneerde `detail`,
`instance: urn:uuid:<errorId>`).

## Architectuur (delta t.o.v. fase 3)

```
demo-console (:8095)
  ├── /api/demo/storing/{proxy}/uit  (nieuw: profiel|redis|notificatie|aanmeld) ─┐
  ├── /api/demo/storing/reset        (bestaand — dekt de nieuwe proxies mee)     │→ Toxiproxy admin (:8474)
  ├── /api/demo/foutieve-aanlevering (nieuw) ──> magazijn-a POST /berichten (raw JSON) → 400
  └── /api/demo/ontdubbeling         (nieuw) ──> uitvraag POST /aanmeldingen (2×, zelfde id) → 202,202

Toxiproxy proxies: magazijn-a, magazijn-b, redis, profiel (bestaand) + notificatie, aanmeld (nieuw)
magazijn-a/b env: NOTIFICATIE_URL/AANMELD_URL door Toxiproxy; poll-interval 5s; max-pogingen 50
```

De generieke `StoringService.uit(proxy)`/`reset()` uit fase 3 zijn al proxy-agnostisch; de nieuwe
infra-knoppen hoeven alleen een resource-endpoint met een **allowlist** (`profiel`, `redis`,
`notificatie`, `aanmeld`) zodat het paneel geen willekeurige proxy-naam kan uitschakelen. `reset()`
schakelt álle proxies weer in en wist toxics, dus de nieuwe proxies herstellen automatisch mee.

## Taken

1. **Magazijn-downstream-proxies + reroute + poll-tuning** — `proxies.json` (2 proxies), `compose.yaml`
   (magazijn-a/b env + `depends_on`). Validatie: compose/JSON parseren; Docker-run bevestigt de reroute.
2. **Infra-storingsknoppen** — generieke, allowlist-bewaakte `POST /api/demo/storing/{proxy}/uit` in
   `StoringResource`; unittest op de allowlist-guard (onbekende proxy → 400). Paneelknoppen.
3. **Foutieve aanlevering** — raw-JSON client-methode + `FoutieveAanleverService` + endpoint; unittest op
   de payload-opbouw. Paneelknop toont de 400 problem+json.
4. **Ontdubbeling** — `OntdubbelingService` (CloudEvent-opbouw + REST-client naar de uitvraag) + endpoint;
   unittest dat beide POST's hetzelfde `id` dragen. Paneelknop + persona-BSN-veld.

Plus een paneelsectie "Technische scenario's (fase 5)" die A/B/C bundelt.

## Verificatie

**Lokaal (zonder Docker):** compose/JSON valide; `StoringService`-allowlist, `FoutieveAanleverService`
en `OntdubbelingService` MockK-getest; augmentatie wiret de nieuwe REST-clients; detekt schoon;
paneel in de jar.

**Docker-run (door de gebruiker):**
- 12: `redis`-proxy uit → `GET /berichten` geeft 502 problem+json met correlation-id (geen 500); cache-verval-knop werkt nog.
- 9: `profiel`-proxy uit → *Ophalen* degradeert (exacte gedraging noteren).
- 10: `notificatie` uit → nieuw random bericht verschijnt tóch bij *Vernieuw*; magazijn-log toont notificatie-retry (WARN).
- 11: `aanmeld` uit → nieuw bericht verschijnt **niet** bij *Vernieuw*, bestaande berichten wél; re-enable → bericht verschijnt alsnog (outbox-recovery). Retry binnen ~5s zichtbaar.
- 8: foutieve-aanlevering-knop → 400 problem+json in het paneel.
- 13: persona ophaalt → ontdubbeling-knop → precies één nieuw bericht bij *Vernieuw*.
- `reset` herstelt alle zes proxies. `./mvnw clean verify -pl services/demo-console -am` groen.

## Bewust buiten scope

- **Load/stress (#14)** → fase 7.
- **Traag-varianten op infra-proxies** (redis/profiel latency) — de eisen vragen "weg", niet "traag";
  niet toevoegen zonder aanleiding.
- **Foutieve-aanlevering tweede variant** (ontbrekend veld) — genoemd, niet standaard geknopt.
- **Redis-marker-terugmelding bij ontdubbeling** — UI-observatie volstaat (afgestemd).
