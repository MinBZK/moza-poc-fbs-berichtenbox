# Sessiecache gekoppeld aan de inlogsessie — implementatieplan

**Status:** Concept

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Berichten in de sessiecache leven zolang de ontvanger een lopende inlogsessie heeft en worden actief en gelogd gewist bij de laatste uitlog of het verlopen daarvan.

**Architecture:** De library `fbs-berichtensessiecache` krijgt sessiebeheer (start/raak/beëindig/veeg) als atomaire Lua-scripts naast de bestaande berichtgegevens in Redis; de sliding TTL verdwijnt. De uitvraag krijgt `POST /api/v1/sessies` en `DELETE /api/v1/sessies/huidige`, eist `X-Sessie` op alle `/berichten`-paden via één request-filter, en draait een veegtaak die verlopen sessies opruimt en in het LDV logt.

**Tech Stack:** Kotlin, Quarkus 3 (RESTEasy Reactive, Redis client, Scheduler), Redis Stack (Lua, RediSearch), OpenAPI-first (`jaxrs-spec`), JUnit 5 + REST-assured + Testcontainers.

**Spec:** `docs/plans/2026-10-21-sessiecache-sessiegebonden-design.md`

## Global Constraints

- Config: `berichtensessiecache.sessie.inactiviteit=PT15M`, `berichtensessiecache.sessie.max-duur=PT12H`, `berichtensessiecache.sessie.vangnet-marge=PT1H`, `berichtensessiecache.sessie.max-per-ontvanger=5`, veeginterval `berichtensessiecache.sessie.veeg-interval=PT30S`. `berichtensessiecache.ttl` verdwijnt (geen achterwaartse compatibiliteit).
- Sessie-id: 16 bytes `SecureRandom`, base64url zonder padding; in Redis alleen `sha256(id)` hex.
- Sessie-id nooit in logs, URL's, LDV of foutmeldingen. BSN nooit in applicatie-logs, ook geen hash ervan.
- Header `X-Sessie`; `401` problem+json met foutcode `sessie-ongeldig` voor ontbrekend/onbekend/verlopen/andere-ontvanger, zonder onderscheid. `429` met `te-veel-sessies` bij de zesde sessie.
- Uitgezonderd van de sessie-eis: `POST /api/v1/aanmeldingen`, `GET /api/v1/openapi.json`, `/openapi.json`, `/q/*`, en `POST /api/v1/sessies` zelf.
- LDV: nieuwe activiteit `ProcessingActivities.UITVRAAG_SESSIE = "$NAMESPACE:sessie"`; start, beëindigen en opruimen (aanleiding `uitgelogd`/`verlopen`/`nieuwe-start`) gelogd met `dataSubjectId` = ontvanger.
- Kotlin-stijl en NL/EN-grens uit `CLAUDE.md`; detekt `maxIssues: 0`; JaCoCo 90 % via `@QuarkusTest`.
- Altijd `./mvnw clean test -pl <module> -am`.

## Review Focus

- Logout van de laatste sessie terwijl `_ophalen` loopt → na afronding van de ronde staat er niets (list/status/hashes) voor die ontvanger. Test in Task 3.
- Twee pods vegen tegelijk dezelfde verlopen ontvanger → één opruiming, één LDV-regel. Test in Task 5.
- Sessie van ontvanger A met `X-Ontvanger` van B → `401`, en B's gegevens worden niet geraakt. Test in Task 2 en Task 6.
- Herladen van de berichtenbox (zelfde sessie-id) binnen de inactiviteitstermijn → sessie loopt door, geen nieuwe ophaalronde nodig. Test in Task 2 (raak verlengt) en Task 8 (console bewaart id).
- Sessie start terwijl een andere loopt, ná 11 uur → bestaande gegevens krijgen een TTL voorbij de nieuwe sessie. Test in Task 3.

---

### Task 1: API-contract — spec, Bruno, foutcodes

Volg `/openapi-wijziging`. Dit is de eerste push; de moza-poc-sessie bouwt erop.

**Files:**
- Modify: `services/berichtenuitvraag/src/main/resources/openapi/berichtenuitvraag-api.yaml`
- Modify: `libraries/fbs-common/src/main/kotlin/nl/rijksoverheid/moz/fbs/common/exception/Foutcode.kt`
- Create: `bruno/berichtenuitvraag/sessies/start-sessie.bru`, `bruno/berichtenuitvraag/sessies/beeindig-sessie.bru`
- Modify: alle `bruno/berichtenuitvraag/**/*.bru` die `/berichten` raken (header `X-Sessie: {{sessie}}`), `bruno/berichtenuitvraag/environments/lokaal.bru` (`sessie`-var)

**Interfaces:**
- Produces: `POST /api/v1/sessies` (header `X-Ontvanger`) → `201` body `Sessie { sessieId: string, uiterlijkGeldigTot: date-time, _links.self → /api/v1/sessies/huidige }`; `429`, `400`, `503`. `DELETE /api/v1/sessies/huidige` (headers `X-Ontvanger`, `X-Sessie`) → `204`. `components/parameters/SessieHeader` (`X-Sessie`, required, pattern `^[A-Za-z0-9_-]{22}$`), toegevoegd aan elke operatie onder `/berichten*`; `401` (bestaande `Unauthorized`) en bij `POST /sessies` een nieuwe `TooManyRequests`-response. `Foutcode.SESSIE_ONGELDIG("sessie-ongeldig", "Uw sessie is verlopen of ongeldig. Log opnieuw in.")`, `Foutcode.TE_VEEL_SESSIES("te-veel-sessies", "U bent op te veel plekken tegelijk ingelogd.")`.

- [ ] Step 1: Spec aanpassen; Spectral ADR-lint draaien: `npx @stoplight/spectral-cli lint services/berichtenuitvraag/src/main/resources/openapi/berichtenuitvraag-api.yaml --ruleset https://static.developer.overheid.nl/adr/ruleset.yaml` → geen errors.
- [ ] Step 2: `./mvnw clean compile -pl services/berichtenuitvraag -am` → gegenereerde interfaces compileren niet meer tegen `UitvraagResource` (nieuwe `xSessie`-parameter). Parameter toevoegen aan de overrides (nog ongebruikt; Task 6 bedraadt hem) en een stub `SessieResource` die `501` geeft — zodat de build groen is.
- [ ] Step 3: `SpecFoutcodesTest`, `RouteDekkingTest`, `OpenApiContractTest` draaien; aanpassen waar ze nieuwe routes/codes eisen.
- [ ] Step 4: Commit + push (`feat(uitvraag): contract voor inlogsessies`).

### Task 2: Library — sessieregister (start, raak, beëindig)

**Files:**
- Create: `libraries/fbs-berichtensessiecache/src/main/kotlin/.../sessie/SessieRegister.kt` (internal interface + `RedisSessieRegister`), `.../sessie/SessieScripts.kt` (Lua), `.../sessie/SessieConfig.kt` (`@ConfigMapping(prefix = "berichtensessiecache.sessie")`)
- Modify: `Sessiecache.kt`, `BlockingSessiecache.kt`, `SessiecacheException.kt`
- Test: `libraries/fbs-berichtensessiecache/src/test/kotlin/.../sessie/RedisSessieRegisterTest.kt` (`@QuarkusTest`, echte Redis)

**Redis-sleutels** (`k = BerichtenCache.cacheKey(ontvanger)`): `$k:sessies` ZSET (member `sha256(id)`, score verloop-ms), `$k:sessies:uiterlijk` HASH (member → start+max-duur ms), `$k:sleutels` SET (alle per-ontvanger datasleutels buiten de vaste namen — Task 3 vult hem), globale `berichtensessiecache:v4:verloop` ZSET (member `ontvanger.toCanonicalString()`, score = laatste verloop-ms van die ontvanger). Tijd komt uit Redis (`TIME`) in het script, niet uit de pod.

**Interfaces:**
- Produces (facade):
  - `fun startSessie(ontvanger: Identificatienummer): SessieStart` — `data class SessieStart(val sessieId: String, val uiterlijkGeldigTot: Instant, val restenOpgeruimd: Boolean)`; gooit `SessiecacheException.TeVeelSessies`.
  - `fun raakSessie(sessieId: String, ontvanger: Identificatienummer)` — gooit `SessiecacheException.SessieOngeldig`.
  - `fun beeindigSessie(sessieId: String, ontvanger: Identificatienummer): SessieEinde` — `enum class SessieEinde { ONBEKEND, BEEINDIGD, OPGERUIMD }`.
- Script-semantiek: START ruimt eerst verlopen leden op; is de set dan leeg → purge (Task 3 levert `purge`; tot dan alleen sessie-sleutels) en `restenOpgeruimd = purge verwijderde iets`; bij `ZCARD >= max` → weigering; anders ZADD (now+inactiviteit), HSET uiterlijk, `ZADD GT` op de verloopindex. RAAK: score ontbreekt of ≤ now → ongeldig; anders score = `min(now+inactiviteit, uiterlijk)` en `ZADD GT` op de verloopindex. EINDE: ZREM + HDEL + verlopen leden weg; leeg → purge + ZREM verloopindex → `OPGERUIMD`.

- [ ] Step 1: Tests schrijven: `start levert unieke id's van 22 tekens`, `raak met onbekende id gooit SessieOngeldig`, `raak met id van andere ontvanger gooit SessieOngeldig`, `raak na inactiviteit gooit SessieOngeldig` (config `inactiviteit=PT2S` via TestProfile), `raak verlengt niet voorbij max-duur` (`max-duur=PT3S`), `zesde start gooit TeVeelSessies en na een einde lukt het weer`, `@ParameterizedTest(1, 2, 3 sessies)`: alle-op-één-na beëindigen → `BEEINDIGD`, laatste → `OPGERUIMD`, `einde van onbekende id → ONBEKEND`, `Redis bevat de sessie-id nergens in klare tekst` (SCAN + waarden).
- [ ] Step 2: Draaien → FAIL (ontbrekende types).
- [ ] Step 3: Implementeren.
- [ ] Step 4: `./mvnw clean test -pl libraries/fbs-berichtensessiecache -am` → PASS.
- [ ] Step 5: Commit.

### Task 3: Library — purge, vaste vangnet-TTL, schrijven-kruist-uitloggen

**Files:**
- Modify: `.../berichten/BerichtenCache.kt` (store, `AANMELD_SCRIPT`/createBericht, delete-tombstone, verwijder `renewSessionTtl`/`renewReadTtl*`/`verlengTtls`/`verlengSessie`), `.../berichten/BerichtensessiecacheService.kt` (na `store`+GEREED en na `createBericht`: `ruimOpZonderSessie`), `BlockingSessiecache.schrijfBericht`
- Modify: `SessieScripts.kt` (PURGE-script, ook aangeroepen door START/EINDE)
- Test: `.../sessie/SessieOpruimenTest.kt`, bestaande `BerichtenCache`-tests die sliding TTL toetsen herschrijven

**Interfaces:**
- Produces: `internal fun SessieRegister.ruimOpZonderSessie(cacheKey: String): Uni<Boolean>` (atomair: verlopen leden weg; geen lopende sessie → purge; `true` als er opgeruimd is). Datasleutel-TTL overal `maxDuur + vangnetMarge` (één `val dataTtl: Duration` uit `SessieConfig`). START met al lopende sessie: PEXPIRE `dataTtl` op `:list`, `:sleutels` en zijn leden, en `:status` tenzij die BEZIG is.
- PURGE verwijdert: `:list`, `:status`, `:lock`, `:sessies`, `:sessies:uiterlijk`, `:sleutels` en al zijn leden (in stukken van 1000 via `unpack`).

- [ ] Step 1: Tests: `@ParameterizedTest(0, 1, 3 berichten)` uitloggen laatste sessie → geen `bericht:v4:*`, geen `:list`/`:status`, `FT.SEARCH` voor de ontvanger leeg; `hashes uit een eerdere ophaalronde worden ook opgeruimd`; `einde laatste sessie tijdens ophalen laat na afronding niets achter` (magazijn-stub die wacht op een latch); `aanmelding na uitloggen schrijft niets`; `aanmelding die kruist met uitloggen laat niets achter` (purge tussen statuscheck en schrijven via test-hook of directe volgorde); `start zonder lopende sessie ruimt resten op en meldt restenOpgeruimd`; `start naast lopende sessie trekt TTL op tot boven nieuwe uiterlijk`; `TTL na store is maxDuur+marge, lezen verlengt niet`.
- [ ] Step 2: FAIL. Step 3: implementeren. Step 4: module-tests PASS (ook alle bestaande). Step 5: commit.

### Task 4: Library — `_volgen` op de sessie

**Files:**
- Modify: `Sessiecache.volg` → `fun volg(ontvanger: Identificatienummer, sessieId: String): Multi<SessieGebeurtenis>`, `.../berichten/SessieVolger.kt` (hartslag = `raak`; ongeldig → `SessieVerlopen` en sluiten; opstartcheck `hartslag < inactiviteit / 2`)
- Test: bestaande `SessieVolger`-tests aanpassen; nieuw `volgen eindigt met SessieVerlopen nadat de sessie elders is beëindigd`, `hartslag houdt sessie langer dan inactiviteit in leven`.

- [ ] Step 1–5 als boven (test, FAIL, implementatie, PASS, commit).

### Task 5: Library — verlopen sessies vegen

**Files:**
- Modify: `Sessiecache.kt`: `fun ruimVerlopenSessiesOp(max: Int = 100): List<Opruiming>` — `data class Opruiming(val ontvanger: Identificatienummer, val gelukt: Boolean)`.
- Modify: `SessieScripts.kt` (VEEG per ontvanger: score verloopindex > now → overslaan; nog lopende leden → score bijwerken naar hun max, overslaan; anders purge + ZREM → opgeruimd)
- Test: `.../sessie/SessieVegenTest.kt`

Een purge die faalt (Redis-fout) levert `Opruiming(gelukt = false)` en laat de ontvanger in de index staan. Ontvanger parsen uit de canonical string via `Identificatienummer.fromHeader`; een onleesbaar lid wordt gelogd (zonder waarde) en verwijderd.

- [ ] Step 1: Tests: `verlopen laatste sessie wordt opgeruimd`, `ontvanger met nog één lopende sessie blijft staan en krijgt de juiste score`, `twee gelijktijdige vegers ruimen één keer op` (twee coroutines/threads, tel `gelukt`-opruimingen = 1), `max begrenst de batch`. Step 2–5 als boven.

### Task 6: Uitvraag — sessie-endpoints, sessiefilter, foutmapping, LDV

**Files:**
- Create: `services/berichtenuitvraag/src/main/kotlin/.../sessie/SessieResource.kt` (implementeert gegenereerde `SessiesApi`), `.../sessie/SessieFilter.kt` (`@ServerRequestFilter` of `ContainerRequestFilter`, `@Priority(Priorities.AUTHENTICATION)`, alleen paden onder `/api/v1/berichten`), `.../sessie/SessieLogboek.kt`
- Modify: `ProcessingActivities.kt` (`UITVRAAG_SESSIE`), `uitvraag/UpstreamFault.kt` (`SessieOngeldig` → `401 SESSIE_ONGELDIG`, `TeVeelSessies` → `429 TE_VEEL_SESSIES`), `VolgenSseResource.kt` (geef `X-Sessie` door aan `volg`), `UitvraagResource.kt`/`OphalenSseResource.kt` (parameter alleen doorgeven, controle zit in het filter), `src/main/resources/application.properties` (sessie-config, `ttl` weg; demo/dev-profielen)
- Test: `.../sessie/SessieResourceTest.kt`, `.../sessie/SessieFilterTest.kt`, `MockSessiecache.kt`, `OpenApiContractTest.kt` (401 en 429 tegen Problem-schema), `SessiecacheFoutMappingTest.kt`, `UitvraagKetenE2eTest.kt` (start sessie eerst; nieuw scenario uitloggen → 401 + Redis leeg), `LdvSubjectRegistrationTest.kt`

`@Logboek(name = "uitvraag-sessie-start" | "uitvraag-sessie-einde", processingActivityId = UITVRAAG_SESSIE)` op de resource-methodes. Bij `OPGERUIMD`/`restenOpgeruimd` een extra regel via `SessieLogboek.opgeruimd(ontvanger, aanleiding)`.

- [ ] Step 1: Tests: `start geeft 201 met sessieId en uiterlijkGeldigTot`, `@ParameterizedTest` over alle `/berichten`-routes: zonder `X-Sessie` → 401 `sessie-ongeldig`; met sessie van andere ontvanger → 401; `/aanmeldingen` en `/api/v1/openapi.json` werken zonder sessie; `einde geeft 204, ook twee keer`; `zesde start → 429`; LDV-record per start/einde met activiteit `sessie`. Step 2–5.

### Task 7: Uitvraag — veegtaak met LDV

**Files:**
- Create: `.../sessie/SessieVeger.kt` (`@Scheduled(every = "{berichtensessiecache.sessie.veeg-interval}", concurrentExecution = SKIP)`, `@ActivateRequestContext` indien `LogboekContext` dat eist)
- Modify: `services/berichtenuitvraag/pom.xml` (`quarkus-scheduler` als die ontbreekt)
- Test: `.../sessie/SessieVegerTest.kt`

Per `Opruiming`: LDV-regel onder `UITVRAAG_SESSIE`, aanleiding `verlopen`, uitkomst gelukt/mislukt. Mislukt → `log.warnf("Opruimen verlopen sessie mislukt; volgende ronde probeert opnieuw")` zonder ontvanger. Controleer of de LDV-wrapper buiten een HTTP-verzoek een record schrijft; zo niet, gebruik de programmatische API van de wrapper.

- [ ] Step 1: Tests: `verlopen sessie wordt binnen twee veegintervallen opgeruimd en gelogd` (inactiviteit PT2S, interval PT1S), `mislukte opruiming logt waarschuwing zonder BSN` (log-capture, assert geen waarde van de ontvanger). Step 2–5.

### Task 8: Demo — console-berichtenbox, cache-knop, demo-profiel

**Files:**
- Modify: `demo/demo-console/src/main/resources/META-INF/resources/berichtenbox.js` (start sessie bij personakeuze, `sessionStorage` per tab onder sleutel `fbs-sessie:<ontvanger>`, `X-Sessie` in `api()`, beëindigen in `wisPersonaState`, `401` → nieuwe sessie + `_ophalen`)
- Modify: `demo/demo-console/src/main/kotlin/.../sessie/SessieService.kt` (ook `bericht:v4:*` wissen; de verloopindex valt al onder `berichtensessiecache:*`)
- Modify: demo-/dev-profielconfig die `berichtensessiecache.ttl` zette (zoek in `demo/`, `compose*.yaml`, `demo/environment/**`) → `berichtensessiecache.sessie.inactiviteit`
- Modify: `demo/smoke.sh` als die `/berichten` aanroept
- Test: bestaande `SessieService`-test uitbreiden met `bericht:v4:*`

- [ ] Step 1–5 als boven; plus handmatig: `/demo-starten`, persona kiezen, wisselen, Redis leeg voor de vorige persona.

### Task 9: Documentatie en pin

**Files:**
- Modify: `docs/operator-handleiding-uitvraag.md` (nieuwe sleutels, `ttl` weg), `docs/ontwikkelen.md`, `docs/demo-runbook.md` (cache-knop, sessies), `CLAUDE.md` (Caching-regel: geen sliding TTL meer; sessiegebonden), design-doc + dit plan `**Status:** Uitgevoerd`
- Modify (bij uitrol, na merge moza-poc-PR): `compose.yaml` pin naar het main-image van moza-poc

- [ ] Step 1: Docs bijwerken. Step 2: `./mvnw clean verify -pl services/berichtenuitvraag -am` en `-pl libraries/fbs-berichtensessiecache -am` → groen, waarschuwingen getrieerd (`/warnings-triage`). Step 3: commit.

### Task 10: Overdracht naar moza-poc

**Files:**
- Create: `docs/plans/2026-10-21-sessiecache-sessiegebonden-moza-poc-overdracht.md`

Prompt voor een Claude-sessie in `/home/claude/projects/moza-poc`: contract (Task 1, verwijzing naar de spec op deze branch), gedrag per moment (persona kiezen, wisselen, "Uitloggen", herladen, `401`), de bestanden uit het ontwerp (`personas.js`, `berichtenbox-keten.js`, `server/keten-proxy.js`), PR als draft met verwijzing naar de FBS-PR, merge-volgorde.

- [ ] Step 1: Schrijven na Task 1. Step 2: commit + push; aan de gebruiker geven.
