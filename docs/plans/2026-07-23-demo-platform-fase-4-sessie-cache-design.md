**Status:** Concept

# Demo-platform fase 4 — sessie & cache — ontwerp

Onderdeel van het demo-platform (`docs/plans/2026-07-21-demo-platform-design.md`, fase 4).
Maakt de ondernemer-flows compleet: nieuwe berichten tijdens de sessie (flow 5) en het
verlopen van de cache-tijd (flow 6).

## Doel

Aantoonbaar maken dat de sessiecache tijdgebaseerd verloopt (niet sessie-gebaseerd), en dat
de ondernemer daardoor opnieuw moet ophalen. Plus: een instant-knop om dat verval in een
live demo te forceren zonder te wachten.

## Uitgangspunten (vastgesteld in overleg)

| Vraag | Keuze |
|---|---|
| Flow 5 (nieuwe berichten tijdens sessie) | Werkt al (aanmeld-fix + Vernieuw); geen nieuw werk |
| Cache-verval demonstreren | **Knop (instant) én korte TTL** |
| Doelgroep van de knop | Alle sessies tegelijk (patroon-match), geen per-persona hash-berekening |

## Kernbevindingen (verkenning van `fbs-berichtensessiecache`)

- **Sessie-keyscheme:** per ontvanger een basiskey `berichtensessiecache:v1:<sha256>` met
  afgeleide keys `:list` (berichtenlijst), `:status` (aggregation-status), `:lock`.
  Per-bericht-hashes staan los op `bericht:v1:<berichtId>`.
- **De ene key die telt:** het wissen van `...:status` is voldoende — `getAggregationStatus`
  geeft dan `null` en de leespaden gooien `NogNietGevuld` → **HTTP 409** "nog niet opgehaald".
- **Index-veilig:** een `DEL` van individuele keys haalt documenten uit de RediSearch-index
  (`berichten-idx`, `ON HASH PREFIX bericht:v1:`) maar dropt de index-definitie **niet**.
  Alleen `FT.DROPINDEX` of `FLUSHDB` zou dat doen — die gebruiken we dus **nooit**.
- **TTL:** `berichtensessiecache.ttl` (default `PT12H`), een `@ConfigProperty` zonder
  min-validatie → overschrijfbaar via env-var `BERICHTENSESSIECACHE_TTL` (bv. `PT2M`).
- **Sliding TTL:** elke succesvolle read verlengt `:list`/`:status` (en geraakte
  bericht-hashes). Bij een korte TTL verloopt de sessie dus pas na *inactiviteit*.

## A. Cache-verval-knop

De demo-console krijgt een Redis-client die **rechtstreeks** naar `redis:6379` verbindt —
niet via Toxiproxy, want dit is een beheeractie die losstaat van de uitvraag-storingen.

Een `SessieService` doet `KEYS berichtensessiecache:v1:*` en `DEL` op de treffers. Dat wist
alle sessie-keys (`:status`, `:list`, `:lock`) van alle ontvangers; het verlies van `:status`
is wat de uitvraag doet denken dat er geen actieve sessie is.

- **Alle sessies tegelijk** (patroon-match) i.p.v. één persona — vermijdt het narekenen van
  de SHA-256 van de canonieke ontvanger-string, en in een demo is er toch één actieve persona.
- **Index blijft heel** (geen `FT.DROPINDEX`/`FLUSHDB`).
- Endpoint `POST /api/demo/sessie/verlopen` → geeft het aantal gewiste keys terug.
- `KEYS` (i.p.v. `SCAN`) is verantwoord bij de kleine demo-keyspace; het is één commando.

## B. Korte TTL

Config-only: `BERICHTENSESSIECACHE_TTL=PT2M` in de `berichtenuitvraag`-env van het
demo-profiel. Geen codewijziging, geen min-validatie op de property. Door de sliding TTL
verloopt een sessie na ~2 min zonder reads; blijf je lezen, dan schuift hij mee.

2 minuten is een afweging: kort genoeg om in een demo te tonen door even te wachten, lang
genoeg om niet te storen tijdens het doorklikken. De env-var is triviaal aan te passen.

## De demo (flow 6)

1. Persona ophaalt → sessie GEREED, lijst zichtbaar.
2. Klik **Cache verlopen** (of wacht ~2 min zonder actie).
3. In de Berichtenbox: bericht openen of **Vernieuw** → **409-melding** "nog niet opgehaald".
4. **Ophalen** → sessie hersteld, lijst terug.

Aanvullend verhaal: is de cache verlopen, dan tonen nieuw aangeleverde berichten (aanmeld-
push) pas weer ná opnieuw ophalen — de push-weg vereist immers een actieve sessie.

## Componenten

| Bestand | Verantwoordelijkheid | Actie |
|---|---|---|
| `services/demo-console/pom.xml` | `quarkus-redis-client` toevoegen | Wijzigen |
| `.../demo-console/src/main/resources/application.properties` | `quarkus.redis.hosts` | Wijzigen |
| `.../democonsole/sessie/SessieService.kt` | KEYS + DEL sessie-keys | Aanmaken |
| `.../democonsole/sessie/SessieResource.kt` | endpoint `/api/demo/sessie/verlopen` | Aanmaken |
| `.../democonsole/sessie/SessieServiceTest.kt` | MockK-test op KEYS/DEL + lege-keyspace | Aanmaken |
| `.../META-INF/resources/index.html` | knop "Cache verlopen" | Wijzigen |
| `compose.yaml` | demo-console `REDIS_HOSTS`; uitvraag `BERICHTENSESSIECACHE_TTL` | Wijzigen |

Package-root: `nl.rijksoverheid.moz.fbs.democonsole.sessie`.

## Foutafhandeling

- Redis onbereikbaar vanuit de demo-console → de endpoint geeft een nette foutmelding; het
  paneel toont "Fout".
- Geen sessie-keys aanwezig → `verlopen` geeft `{aantal: 0}` terug (geen fout).

## Teststrategie

- **Kotlin (demo-console):** `SessieService` met MockK — `RedisDataSource.key().keys(patroon)`
  gemockt, verifieer `del(...)` met de treffers en de lege-keyspace-short-circuit (aantal 0,
  geen `del`). detekt schoon.
- **Augmentatie:** valideert de Redis-client-wiring in de demo-console.
- **Handmatig (Docker):** de volledige flow-6-doorloop, plus dat de korte TTL na inactiviteit
  vanzelf verloopt.

## Bewust buiten scope

- Per-persona verval (SHA-256-hash narekenen) — niet nodig voor een demo met één persona.
- Wijziging aan de productie-TTL-default (blijft PT12H); alleen het demo-profiel zet PT2M.
- Flow 5 — al functioneel; geen nieuw werk.

## Verificatie

| Stap | Verificatie |
|---|---|
| Redis-client wiring | Augmentatie (`mvn package`) groen |
| SessieService-logica | MockK-test: KEYS-patroon + DEL van treffers, en 0 bij lege keyspace |
| Verval-knop (Docker) | Na "Cache verlopen" geeft `GET /berichten` 409; Ophalen herstelt |
| Korte TTL (Docker) | Na ~2 min inactiviteit verloopt de sessie vanzelf (409 bij volgende actie) |
| Index intact (Docker) | Na verval + opnieuw Ophalen werkt zoeken/filteren nog (index niet gedropt) |
| Regressie | `./mvnw clean test -pl services/demo-console` groen; detekt schoon |
