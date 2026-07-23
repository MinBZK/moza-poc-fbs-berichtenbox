**Status:** Concept

# Demo-platform fase 3 — Toxiproxy + storingsscenario's — ontwerp

Onderdeel van het demo-platform (`docs/plans/2026-07-21-demo-platform-design.md`, fase 3).
Voegt het degradatieverhaal toe: de ondernemer ziet trage/uitgevallen magazijnen, en de
bijlage die niet opgehaald wordt.

## Doel

Aantoonbaar maken hoe het stelsel degradeert wanneer een magazijn traag is of uitvalt —
zonder demo-logica in de productiecode. Storingen worden op netwerkniveau opgewekt met
Toxiproxy; de demo-console bedient ze.

## Uitgangspunten (vastgesteld in overleg)

| Vraag | Keuze |
|---|---|
| Toxiproxy-scope | **Alle uitvraag-afhankelijkheden** nu bedraden (magazijn A/B, Redis, profiel) |
| Fase-3-knoppen | Alleen de magazijn-scenario's (traag, uit). Redis/profiel-knoppen volgen in fase 5 |
| Latency "traag" | 6 s — boven de 5s-zichtbaarheidsdrempel, onder de 10s-query-timeout |
| Bijlage onbereikbaar | Gedekt door "magazijn uit" (lijst uit cache blijft, live bijlage-call faalt) |

## Architectuur

Eén Toxiproxy-container (`ghcr.io/shopify/toxiproxy`, multi-arch — draait op Apple Silicon)
tussen de uitvraag en al zijn afhankelijkheden. De proxies staan vast bij opstart via een
config-bestand; tijdens de demo voegt de demo-console alleen *toxics* toe/weg via de
Toxiproxy-admin-API (:8474).

```
berichtenuitvraag ──> toxiproxy ──> berichtenmagazijn-a   (:18090 → 8090)
                             ├─────> berichtenmagazijn-b   (:18091 → 8090)
                             ├─────> redis                 (:16379 → 6379)
                             └─────> profiel-service       (:18089 → 8080)
demo-console ──(admin :8474)──> toxiproxy    (toxics aan/uit)
```

### Toxiproxy-proxies (startup-config)

Een gemount `toxiproxy/proxies.json` definieert vier proxies zonder toxics:

| Proxy | listen | upstream |
|---|---|---|
| `magazijn-a` | `0.0.0.0:18090` | `berichtenmagazijn-a:8090` |
| `magazijn-b` | `0.0.0.0:18091` | `berichtenmagazijn-b:8090` |
| `redis` | `0.0.0.0:16379` | `redis:6379` |
| `profiel` | `0.0.0.0:18089` | `profiel-service:8080` |

Toxiproxy verbindt lazy met de upstream (pas bij de eerste client-connectie), dus de
volgorde van opstarten maakt niet uit.

### Uitvraag-env (compose demo-profiel)

De uitvraag wijst voortaan naar Toxiproxy in plaats van rechtstreeks:

```yaml
MAGAZIJN_A_URL: http://toxiproxy:18090
MAGAZIJN_B_URL: http://toxiproxy:18091
REDIS_HOSTS:    redis://toxiproxy:16379
PROFIEL_SERVICE_URL: http://toxiproxy:18089
```

Bij normaal bedrijf (geen toxics) is Toxiproxy transparant — de keten werkt als voorheen.
Redis/profiel lopen nu ook door Toxiproxy, maar krijgen in fase 3 nog geen knoppen.

## Scenario's en toxics (fase 3)

De uitvraag-timeouts bepalen de grenzen: de per-magazijn query-timeout is **10 s**
(`berichtensessiecache.magazijn-query-timeout-seconds`), de socket-read 12 s.

| Knop | Toxic / actie | Waargenomen effect |
|---|---|---|
| **Magazijn A/B traag** | `latency` 6000 ms op die proxy | Ophalen duurt >5 s voor dat magazijn maar slaagt (6 s < 10 s query-timeout) — "trager dan normaal" |
| **Magazijn A/B uit** | proxy `enabled: false` | Connectie geweigerd → dat magazijn geeft **FOUT** in de ophaal-voortgang; lijst laadt met alleen het andere magazijn (partial success) |
| **Alles normaal** | alle toxics weg, alle proxies enabled | Herstel |

### Wat de knoppen aantonen

- **Trager dan normaal (ondernemer-flow 2):** de SSE-voortgang (fase 2a) toont het trage
  magazijn dat pas na ~6 s "voltooid" meldt.
- **Niet alle berichten (flow 3):** met magazijn A uit toont de voortgang RVO als FOUT en
  laadt de lijst met alleen Belastingdienst-berichten — degradatie, geen totale mislukking.
- **Bijlage onbereikbaar (flow 7):** de lijst blijft staan (uit de Redis-sessiecache), maar
  een bijlage-download gaat live naar het magazijn en faalt → één "magazijn uit"-knop dekt
  dit scenario mee.
- **Bonus — circuit breaker:** herhaald Ophalen met een magazijn uit opent na de drempel de
  per-magazijn circuit breaker; een technisch talking-point, geen aparte knop.

## Demo-console

### Toxiproxy-admin-client (nieuw, Kotlin)

Een kleine client tegen de admin-API (`http://toxiproxy:8474`):

- Latency-toxic toevoegen: `POST /proxies/{proxy}/toxics` met
  `{"type":"latency","attributes":{"latency":6000}}`.
- Proxy uit/aan: `POST /proxies/{proxy}` met `{"enabled":false|true}`.
- Toxics wissen: `GET /proxies/{proxy}` → per toxic `DELETE /proxies/{proxy}/toxics/{naam}`.

### Endpoints

| Endpoint | Actie |
|---|---|
| `POST /api/demo/storing/magazijn/{a\|b}/traag` | latency-toxic |
| `POST /api/demo/storing/magazijn/{a\|b}/uit` | proxy disabled |
| `POST /api/demo/storing/reset` | alle toxics weg + alle proxies enabled |

De reset is belangrijk: de demo moet altijd terug naar een schone toestand kunnen.

### Bedieningspaneel

Een sectie **Storingen** in `index.html` met knoppen per scenario, plus **Alles normaal**.

## Toxiproxy-config-bron (proxies) vs runtime (toxics)

Bewuste splitsing: de vier **proxies** staan vast in `toxiproxy/proxies.json` (structuur,
versiebeheerd), de **toxics** worden puur tijdens de demo runtime toegevoegd/verwijderd via
de admin-API. Zo is de storing-besturing reproduceerbaar en laat de config zien welke paden
door Toxiproxy lopen.

## Foutafhandeling

- Toxiproxy onbereikbaar vanuit de demo-console → de storing-endpoints geven een nette 502/
  foutmelding; het paneel toont de fout.
- Onbekende proxy-naam → 404 van Toxiproxy → duidelijke foutregel.
- De UI-kant (Berichtenbox) heeft geen wijziging nodig: de degradatie komt al binnen via de
  bestaande SSE-voortgang en de foutmeldingen die fase 2a afhandelt.

## Teststrategie

- **Kotlin (demo-console):** de admin-client krijgt een unittest op de request-opbouw
  (paden, JSON-body voor latency/enabled) met een WireMock- of stub-admin. detekt schoon.
- **Handmatig (Docker):** per knop de waargenomen degradatie — trage voortgang, FOUT-magazijn
  met partial list, gefaalde bijlage-download — en reset terug naar normaal.
- De demo-console valt buiten de coveragegate (wegwerp), dus geen 90%-eis; wel een zinnige
  unittest op de client.

## Bewust buiten scope (fase 3)

- Redis/profiel-**knoppen** (de proxies staan wel klaar) → fase 5.
- Magazijn-downstreams (notificatie, aanmeld=uitvraag) — andere netwerkrichting → fase 5.
- Veel-magazijnen-volume (scenario 3 op schaal) → fase 6.

## Verificatie

| Stap | Verificatie |
|---|---|
| Toxiproxy draait, proxies geladen | `curl toxiproxy:8474/proxies` toont de 4 proxies |
| Normaal bedrijf | Zonder toxics werkt Ophalen/lijst/detail als in fase 2 |
| Magazijn traag | Ophalen toont dat magazijn ~6 s later "voltooid", lijst compleet |
| Magazijn uit | Voortgang toont FOUT voor dat magazijn; lijst met alleen het andere; bijlage-download van een bericht uit het uitgevallen magazijn faalt |
| Reset | Na "Alles normaal" werkt alles weer; `curl .../proxies` toont geen toxics |
| Regressie | `./mvnw clean test -pl services/demo-console` groen; detekt schoon |
