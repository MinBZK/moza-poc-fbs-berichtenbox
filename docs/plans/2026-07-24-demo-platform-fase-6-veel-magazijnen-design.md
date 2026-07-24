**Status:** Uitgevoerd

# Demo-platform fase 6 — veel magazijnen — ontwerp

**Overkoepelend ontwerp:** `docs/plans/2026-07-21-demo-platform-design.md`

**Doel:** scenario 3 ("magazijnen onbereikbaar — weinig/veel") volledig demonstreerbaar maken:
een variabel aantal lichtgewicht magazijn-stubs waar de uitvraag naar fan-out, met een live
schuif voor het actieve aantal. Zonder codewijziging in de uitvraag of het magazijnregister.

## Context — de keten (uit de codebase geverifieerd)

De uitvraag ontdekt en bevraagt magazijnen zo:

1. **Register** — `Magazijnregister` leest `magazijnen."<OIN>".{url,naam}` als een `Map`
   (`MagazijnregisterConfig`, `@WithParentName`). Het aantal is structureel variabel; A/B staan
   als property-regels vast maar niet in Kotlin. `ConfigMagazijnregister` valideert fail-fast bij
   boot: elke OIN-key 20 cijfers (géén elfproef, niet geheel nul), URL http(s)+host. `http` is
   toegestaan onder profiel `dev`/`test` (`OutboundTlsValidator.PROFIELEN_ZONDER_TLS_EIS`).
2. **Profiel-resolutie** — bij een BSN/RSIN/KVK-ontvanger bevraagt de uitvraag **alleen** de
   magazijnen die de profielservice voor die persona teruggeeft: fan-out =
   (opted-in afzender-OIN's uit het profiel) ∩ register (`ProfielMagazijnResolver`). Een profiel-
   voorkeur telt mee als `voorkeurType == OntvangViaBerichtenbox`, opt-in, met scopes
   `{partij:{identificatieType: OIN, identificatieNummer: <OIN>}}`.
3. **Aggregatie** — per magazijn: `GET {register-url}/api/v1/berichten` met header `X-Ontvanger`;
   response = `BerichtenLijst` (`berichten[]` verplicht). Fan-out is parallel (`Multi.merge`),
   met per-magazijn query-timeout (10 s), circuit breaker (drempel 3, open 30 s) en bulkhead
   (max-concurrent 20). Elke substream emit `MAGAZIJN_BEVRAGING_GESTART` → `..._VOLTOOID` met
   status `OK | TIMEOUT | FOUT`; een traag/uitgevallen magazijn faalt alleen z'n eigen substream
   (partial). Dat is precies scenario 3.

Eén WireMock-container serveert n stubs via **Host-gebaseerde** routering. (Oorspronkelijk ontwerp:
pad-gebaseerd met `baseUri(.../mNN)`. Bij de runtime-verificatie bleek de uitvraag-rest-client het
base-URL-subpad te laten vallen — elke magazijn-call gaat naar `/api/v1/berichten`, ongeacht het
register-pad. Het enige per-stub-onderscheid dat de client meestuurt is de **hostnaam**.) Elke stub
krijgt daarom een eigen docker-netwerk-alias (`mNN`, alle wijzend naar dezelfde container); het
register gebruikt `http://mNN:8080` en WireMock matcht op de `Host`-header (`mNN(:8080)?`) + het vaste
pad `/api/v1/berichten`. Het aantal aliassen in `compose.yaml` (`m01..m50`) begrenst n op 50.

## Ontwerpkeuzes (vastgesteld in overleg)

| Vraag | Keuze |
|---|---|
| Ingericht aantal n | **Generatie-script → gemounte files.** Geen vaste lijst in application.properties (overkoepelend ontwerp). |
| Actief aantal k | **Live schuif "k van n"** via WireMock-admin vanuit de demo-console. |
| Persona | **Nieuwe, geïsoleerde persona** (KVK) — Pietersen c.s. en de 2 echte magazijnen ongemoeid. |

## Architectuur

```
Berichtenbox: persona "Grootbedrijf B.V." (KVK 90000001)
  └─> uitvraag  ── profiel(grootbedrijf) → n stub-OIN's ──┐
                                                          │  (register kent dezelfde n)
        fan-out GET /mNN/api/v1/berichten  ───────────────┴─> magazijn-stubs (1 WireMock, n subpaden)

demo-console
  └─> /api/demo/veel-magazijnen/actief/{k} ─> WireMock-admin: mNN>k → 503-overlay, mNN≤k → OK
      /api/demo/veel-magazijnen/reset       ─> WireMock-admin: mappings van schijf herladen

genereer-magazijnen.py  (n) ─> demo/generated/{magazijnen-stubs.properties, profiel/…, magazijn-stubs-mappings/…}
```

### Generatie — `demo/genereer-magazijnen.py`

Uit één getal `n` (arg of `DEMO_MAGAZIJN_STUBS`, default 12). OIN-patroon
`f"0000000900000000{i:04d}"` (20 cijfers, synthetisch blok `00000009…`, distinct van de echte
OIN's `00000001…`). Pad `f"/m{i:02d}"`. Schrijft naar `demo/generated/` (in `.gitignore`):

- **`magazijnen-stubs.properties`** — per i: `magazijnen."<OIN>".url=http://magazijn-stubs:8080/mNN`
  + `.naam=Stub-magazijn i`. Geen `%dev.`-prefix (geldt in alle profielen; onder dev mag http).
- **`profiel/grootbedrijf-kvk.json`** — WireMock-mapping `GET /api/profielservice/v1/KVK/90000001`
  → 200, één `OntvangViaBerichtenbox`-voorkeur met n scopes (één per stub-OIN, `identificatieType: OIN`).
- **`magazijn-stubs-mappings/mNN.json`** — per i: `GET /mNN/api/v1/berichten`, header `X-Ontvanger`
  matcht `.+`, priority 5 → 200 `BerichtenLijst` met 1 bericht (`magazijnId=<OIN>`, ontvanger
  `{KVK, 90000001}`, herkenbaar onderwerp "Stub-magazijn i").

`demo/generated/` gitignoren houdt de "geen vaste lijst"-belofte. Gevolg: **het script draaien is
de gedocumenteerde eerste stap** vóór `docker compose --profile demo up`; anders mounten de compose-
volumes lege dirs. Python (geen build/coverage/detekt) volstaat: deterministische templating.

### Live "k van n actief" — demo-console `veelmagazijnen/`

- **`WireMockAdminClient`** (`@RegisterRestClient(configKey="magazijnstubs")`):
  `PUT /__admin/mappings/{id}` (overlay upsert), `DELETE /__admin/mappings/{id}` (overlay weg),
  `POST /__admin/mappings/reset` (herlaad van schijf).
- **`VeelMagazijnenService(config)`** met `n` uit `demo.veel-magazijnen.aantal` (`${DEMO_MAGAZIJN_STUBS:12}`):
  - `zetActief(k)`: voor i in 1..n → i≤k: overlay verwijderen (`DELETE`, 404 = al actief, negeren);
    i>k: 503-overlay plaatsen (`PUT` met `{priority:1, request:{GET /mNN/api/v1/berichten}, response:{status:503}}`).
    Priority 1 wint van de base (priority 5). Overlay-id deterministisch uit i:
    `"00000000-0000-0000-0000-%012d".format(i)`.
  - `reset()`: `POST /__admin/mappings/reset` → alle base-mappings terug, alles actief.
- **`VeelMagazijnenResource`**: `POST /api/demo/veel-magazijnen/actief/{k}`, `POST /api/demo/veel-magazijnen/reset`.
- Config: `quarkus.rest-client.magazijnstubs.url=${MAGAZIJN_STUBS_ADMIN_URL:http://localhost:8092}`,
  `demo.veel-magazijnen.aantal=${DEMO_MAGAZIJN_STUBS:12}`.

Waarom een 503-overlay i.p.v. de base-mapping vervangen: de demo-console hoeft de (grote) 200-body
niet te kennen; overlay plaatsen/verwijderen is stateless en per stub deterministisch.

### Compose (delta)

- Nieuwe service **`magazijn-stubs`** (`wiremock/wiremock:3.13.2`, `profiles:[demo]`, `8092:8080`),
  mount `./demo/generated/magazijn-stubs-mappings` → `/home/wiremock/mappings`.
- **`berichtenuitvraag`**: mount `./demo/generated/magazijnen-stubs.properties` →
  `/demo-config/magazijnen-stubs.properties:ro` + env
  `SMALLRYE_CONFIG_LOCATIONS: /demo-config/magazijnen-stubs.properties` (additionele config-bron;
  SmallRye accepteert een absoluut filesystem-pad en merge't de n stub-entries bij de baked A/B).
  Geen `depends_on` op magazijn-stubs nodig (het register valideert alleen de URL-vorm, niet
  bereikbaarheid).
- **`profiel-service`**: extra mount `./demo/generated/profiel` → `/home/wiremock/mappings/demo-profiel-generated`.
- **`demo-console`**: env `MAGAZIJN_STUBS_ADMIN_URL: http://magazijn-stubs:8080`, `DEMO_MAGAZIJN_STUBS: 12`.

### UI

- **Berichtenbox** (`berichtenbox.js`): persona "Grootbedrijf B.V. (KVK 90000001)" → `X-Ontvanger: KVK:90000001`.
- **Bedieningspaneel** (`index.html`): sectie "Veel magazijnen (fase 6)" — getalveld `k` →
  `POST /api/demo/veel-magazijnen/actief/{k}` + reset-knop, met een korte hint dat n bij het
  genereren/opstarten vastligt (`DEMO_MAGAZIJN_STUBS`).

## Foutafhandeling

- 503-stub → `HTTP_5XX` → substream-status **FOUT**, partiële lijst; herhaald falen opent de per-
  magazijn circuit breaker (`CIRCUIT_OPEN`). Beide zijn realistisch en gewenst voor de demo.
- `zetActief(k)` met k buiten `0..n` → 400 (BadRequest) in de resource.
- Ontbrekende `demo/generated/`-files bij `up` → lege mounts (geen stubs/register-entries). Dit
  is een bedieningsfout, geen codepad: de README documenteert het script als eerste stap.

## Testen

- **`VeelMagazijnenServiceTest`** (MockK): `zetActief(k)` bij n=5 → `DELETE` voor m01..mk, `PUT`
  503 voor m(k+1)..m5, met de deterministische overlay-id's; grenswaarden `k=0` (alles uit) en
  `k=n` (alles aan); `k` buiten bereik → fout; `reset()` → `POST …/reset`.
- Augmentatie wiret de `magazijnstubs`-rest-client (config aanwezig).
- Het generatie-script wordt lokaal gevalideerd (JSON parse, n entries, 20-cijferige distincte
  OIN's, profiel-scopes = register-OIN's).

## Verificatie

**Lokaal (zonder Docker):** `python3 demo/genereer-magazijnen.py 5` → files valide (register-OIN's ==
profiel-scope-OIN's == mapping-magazijnId's, alle 20 cijfers, distinct); compose valide;
`VeelMagazijnenServiceTest` groen; augmentatie ok; detekt schoon.

**Docker-run (door de gebruiker):**
- `DEMO_MAGAZIJN_STUBS=12 python3 demo/genereer-magazijnen.py` → `demo/generated/` gevuld.
- `docker compose --profile demo up -d`.
- Login als Grootbedrijf → **Ophalen** → 12 substreams, alle OK, lijst met 12 berichten.
- Paneel "actief = 2" → 10 magazijnen **FOUT** in de voortgang, lijst met ~2 berichten (partieel).
- "actief = 12" (of reset) → alles OK weer.
- Test met n=2, 10 en 25 (herstart na regenereren) om variabiliteit te bevestigen.
- **Aandachtspunt:** de `SMALLRYE_CONFIG_LOCATIONS`-mount is de enige onzekere plek — bevestig dat
  de uitvraag de stub-entries leest (`GET {uitvraag}/q/…` of ophalen-fan-out toont 12). Valt hij
  tegen: fallback = de gegenereerde regels alsnog committen in `%dev`-properties.

## Bewust buiten scope

- **Traag-varianten op de stubs** (fixedDelay) — de eisen vragen "onbereikbaar"; niet toevoegen
  zonder aanleiding (de 2 echte magazijnen dekken "traag" al via Toxiproxy).
- **Load/stress (#14)** → fase 7.
- **Mengen van stubs met de 2 echte magazijnen in één persona** — bewust gescheiden gehouden.
