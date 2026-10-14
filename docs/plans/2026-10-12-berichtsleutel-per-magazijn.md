# Berichtsleutel per magazijn — implementatieplan

**Status:** Uitgevoerd

> **Voor agentische uitvoerders:** gebruik superpowers:subagent-driven-development of
> superpowers:executing-plans om dit plan taak voor taak uit te voeren. Stappen gebruiken
> checkbox-syntax (`- [ ]`).

**Doel:** twee organisaties die hetzelfde berichtnummer uitdelen, leveren twee losse, afzonderlijk
te openen, bij te werken en te verwijderen berichten op ([MinBZK/MijnOverheidZakelijk#1004](https://github.com/MinBZK/MijnOverheidZakelijk/issues/1004)).

**Architectuur:** het paar (`magazijnId`, `berichtId`) wordt overal de identiteit van een bericht:
in de Redis-sleutels van de sessiecache, in de `Sessiecache`-facade, in de uitvraag-API (verplichte
`magazijnId`-query op élke operatie op één bericht), in de aanmeld-ontdubbeling en in de
demo-berichtenbox. Alle sessiecache-sleutels gaan in één keer naar `v4`, zodat geen handmatige
Redis-stap nodig is.

**Stack:** Kotlin, Quarkus 3, Redis (RediSearch, Lua, pub/sub), OpenAPI-first (`jaxrs-spec`), JUnit 5 + MockK + REST-assured.

## Context

Elke organisatie deelt haar eigen berichtnummers uit (UUID, `UNIQUE` per magazijn-database). Het
stelsel kan niet afdwingen dat twee losstaande organisaties nooit hetzelfde nummer kiezen. Vandaag:

- `BerichtenCache.berichtKey(berichtId)` = `bericht:v3:<berichtId>`: een ophaalronde met twee
  magazijnen die hetzelfde nummer leveren, laat de tweede hash de eerste overschrijven. De lijst
  toont beide, maar beide verwijzen naar dezelfde hash.
- `GET /berichten/{berichtId}` en de bijlage-download kennen geen `magazijnId`: de ondernemer opent
  het ene bericht en ziet de inhoud van het andere.
- `PATCH`/`DELETE` hebben al een verplichte `magazijnId`, en schrijven dus naar het juiste magazijn,
  maar de cache-update daarna (`werkBerichtBij`/`verwijder`) gaat op alleen het berichtnummer en
  raakt het andere bericht. Magazijn en cache lopen uit elkaar tot de TTL.
- Het lijstmatchen (`herbouwLijst`, `AANMELD_SCRIPT`, `pruneListEnDelHash`) en de tombstone gaan op
  alleen `berichtId`. Een aanmelding van organisatie B met het nummer van een bericht van A wordt
  door het Lua-script als "al aanwezig" gezien.
- Pub/sub (`Aanmeldingen`) draagt alleen `berichtId`; `SessieVolger` zoekt daarmee op.
- De aanmeld-ontdubbeling (`aanmeld:event:<eventId>`) kent geen bron. CloudEvents-id's zijn alleen
  uniek per `source`, en het magazijn maakt de id als UUIDv5 van `berichtId|doel` — zonder OIN. Een
  tweede organisatie die hetzelfde nummer aanmeldt, wordt stil als dubbel weggegooid.

## Ontwerpkeuzes

1. **Verplichte `?magazijnId=` op `GET /berichten/{berichtId}` en `GET …/bijlagen/{bijlageId}`**,
   gelijk aan `PATCH`/`DELETE` (bestaande `MagazijnIdQuery`). Afgewezen: optionele parameter
   (tweede codepad dat de fout uitstelt i.p.v. voorkomt) en magazijn in het pad (breekt ook
   `PATCH`/`DELETE` en alle bestaande afnemers). Brekend voor afnemers: spec-versie `0.3.1 → 0.4.0`
   (0.x-semver, zoals bij eerdere brekende wijzigingen), pad blijft `/api/v1`.
2. **HAL `self` draagt de `magazijnId`:** `/api/v1/berichten/<berichtId>?magazijnId=<OIN>`. Wie de
   link volgt, hoeft niets te weten. De OIN is publiek en geen PII; URL-encoden blijft (het veld
   is in de spec een vrije string).
3. **Facade: extra parameter `magazijnId: String`** op `bericht`, `werkBerichtBij`, `verwijder`,
   in dezelfde volgorde als de API (`ontvanger, magazijnId, berichtId, …`). Géén publiek
   sleuteltype: de facade volgt de vorm van de API. Intern in de library één
   `internal data class BerichtSleutel(magazijnId, berichtId)` zodat lijsten van sleutels
   (TTL-verlenging, pub/sub) niet als `Pair` rondgaan.
4. **Alle sessiecache-versies tegelijk naar `v4`:** `cacheKey` (`berichtensessiecache:v4:`),
   `BERICHT_PREFIX` (`bericht:v4:`), `SEARCH_INDEX` (`berichten-idx-v4`), `Aanmeldingen.KANAAL`
   (`berichtensessiecache:v4:aanmeldingen`). Prefix en indexnaam samen bumpen is volgens
   `docs/operations/redisearch-schema-bump.md` de variant zónder handmatige stap. Gevolg: een
   sessie die over de uitrol heen loopt, moet één nieuwe ophaalronde doen (zelfde als bij v3).
   De sessie-key en het kanaal móeten mee: een v3-pod en een v4-pod delen anders een lijst
   waarvan de matching verschilt, en het kanaal-bericht krijgt een andere vorm.
5. **Berichthash:** `bericht:v4:<magazijnId>:<berichtId>`. **Tombstone:**
   `<cacheKey>:verwijderd:<magazijnId>:<berichtId>`. De lijst-blobs dragen `magazijnId` al; alleen
   het matchen gaat op het paar.
6. **Pub/sub-bericht:** `"<cacheKey> <berichtId> <magazijnId>"`; probes blijven `"probe <uuid>"`.
   `magazijnId` achteraan zodat een `magazijnId` nooit de cacheKey-parsing raakt; een
   bericht-aanmelding zonder derde deel is onleesbaar en wordt genegeerd (zoals nu).
7. **Aanmeld-ontdubbeling:** sleutel `aanmeld:event:<magazijnId>:<eventId>`, met de
   `magazijnId` die `AanmeldService.parse` al afleidt (vóór de idempotentie-claim). Het magazijn
   zelf blijft ongewijzigd: uniek per magazijn volstaat zodra de ontvanger per magazijn ontdubbelt.
   Oude markers (`aanmeld:event:<eventId>`) verlopen via hun TTL; in die korte overgang kan één
   her-aflevering opnieuw verwerkt worden, en dat vangt het `AANMELD_SCRIPT` ("al aanwezig") af.
8. **Magazijn-simulator:** de seed die botsingen bewust vermijdt blijft; alleen README-tekst over
   #1004 bijwerken. `V1__init.sql` van de simulator noemt #1004 in een comment — **niet wijzigen**
   (Flyway-migraties zijn immutable). Geen seed-optie om botsingen af te dwingen (YAGNI).
9. **Buiten deze PR:** de proeftuin-berichtenbox (`MinBZK/moza-poc`, `berichtenbox-keten.js`)
   stuurt bij openen en bijlage-download geen `magazijnId` mee en sleutelt zijn eigen state op
   alleen `berichtId`. Aparte PR daar; de pin in `compose.yaml` pas daarna bijwerken. Tot die PR
   gemerged is, werkt "bericht openen" in de gedeelde demo-omgeving niet: deze PR pas mergen
   als de proeftuin-PR klaarstaat.

## Globale randvoorwaarden

- Altijd `./mvnw clean test …` (nooit zonder `clean`). Docker/Testcontainers vereist voor de
  sessiecache, uitvraag en simulator.
- `magazijnId` (OIN) mag in logs en URL's; `ontvanger.waarde` (BSN) nooit.
- Kotlin-stijl: lege regel vóór en ná elk multi-line blok en elk zelfstandig control-statement.
- Comments: het *waarom*, kort, NL-domeinbegrippen, Engelse technische idiomen; geen verwijzing
  naar review-labels of CLAUDE.md; geen "PoC".
- Gegenereerde code onder `target/generated-sources/` nooit handmatig wijzigen.
- JaCoCo ≥ 90% per module; detekt `maxIssues: 0`.

## Review-focus

Invoer en faalwijzen die de spec impliceert maar die het makkelijkst ontsnappen; elk heeft een test
in de taak die de code bezit:

1. **Onbekende of vreemde `magazijnId` in de query** (een niet-bestaande OIN, of een waarde met
   `:`) → 404 `BERICHT_ONBEKEND`, geen 500 en geen magazijn-aanroep. (Taak 3)
2. **`GET` zonder `magazijnId`** → 400 Problem, gevalideerd tegen het schema. (Taak 3)
3. **Twee magazijnen, zelfde nummer, beide in één ophaalronde** → lijst heeft twee entries, beide
   openen met eigen inhoud, PATCH/DELETE op het ene laat het andere aantoonbaar ongemoeid
   (hash én lijst-entry). (Taak 1, Taak 3)
4. **Aanmelding van organisatie B met het nummer van een bestaand bericht van A** → toegevoegd, niet
   "al aanwezig"; een gevolgde sessie krijgt het bericht van B door. (Taak 1, Taak 2, Taak 4)
5. **Tombstone van A's bericht** → het bericht van B met hetzelfde nummer blijft 200, niet 410.
   (Taak 1)

---

### Taak 1: Sessiecache-opslag sleutelt op (magazijnId, berichtId)

**Bestanden** (onder `libraries/fbs-berichtensessiecache/src/`):
- Wijzig: `main/kotlin/.../berichten/BerichtenCache.kt` (interface, companion, `RedisBerichtenCache`)
- Wijzig: `main/kotlin/.../berichten/Bericht.kt` (extensie `sleutel` op `Bericht` en `BerichtSamenvatting`)
- Wijzig: `main/kotlin/.../berichten/BerichtensessiecacheService.kt` (pass-through-signaturen)
- Wijzig: `test/kotlin/.../berichten/MockBerichtenCache.kt` (maps op `BerichtSleutel`)
- Test: `test/kotlin/.../berichten/BerichtenCacheKeyTest.kt`, `RedisBerichtenCacheIntegrationTest.kt`,
  `BerichtensessiecacheServiceTest.kt`

**Interfaces:**
- Produceert:
  ```kotlin
  internal data class BerichtSleutel(val magazijnId: String, val berichtId: UUID)
  internal val Bericht.sleutel: BerichtSleutel
  internal val BerichtSamenvatting.sleutel: BerichtSleutel

  // BerichtenCache
  fun getById(sleutel: BerichtSleutel, ontvanger: Identificatienummer): Uni<Bericht?>
  fun updateBerichtMetadata(sleutel: BerichtSleutel, ontvanger: Identificatienummer, status: String?, map: String?): Uni<Bericht?>
  fun delete(sleutel: BerichtSleutel, ontvanger: Identificatienummer): Uni<Void>
  fun isVerwijderdVoor(sleutel: BerichtSleutel, ontvanger: Identificatienummer): Uni<Boolean>
  // companion
  fun berichtKey(sleutel: BerichtSleutel) = "$BERICHT_PREFIX${sleutel.magazijnId}:${sleutel.berichtId}"
  fun verwijderdKey(ontvanger: Identificatienummer, sleutel: BerichtSleutel) =
      "${cacheKey(ontvanger)}:verwijderd:${sleutel.magazijnId}:${sleutel.berichtId}"
  const val BERICHT_PREFIX = "bericht:v4:"
  const val SEARCH_INDEX = "berichten-idx-v4"
  // cacheKey → "berichtensessiecache:v4:<sha256>"

  // BerichtensessiecacheService: getBerichtById / isBerichtVerwijderd / updateBerichtMetadata /
  // verwijderBericht nemen (sleutel: BerichtSleutel, ontvanger, …)
  ```
- `CacheContentieException(sleutel: BerichtSleutel)`.

- [ ] **Stap 1: falende sleuteltest.** In `BerichtenCacheKeyTest` de verwachting aanpassen en een
  geval toevoegen:

  ```kotlin
  @Test
  fun `berichtKey bevat magazijn en berichtnummer`() {
      val id = UUID.fromString("11111111-1111-1111-1111-111111111111")

      assertEquals(
          "bericht:v4:00000001000000000001:$id",
          BerichtenCache.berichtKey(BerichtSleutel("00000001000000000001", id)),
      )
  }

  @Test
  fun `zelfde berichtnummer bij twee magazijnen geeft twee sleutels`() {
      val id = UUID.randomUUID()

      assertNotEquals(
          BerichtenCache.berichtKey(BerichtSleutel("00000001000000000001", id)),
          BerichtenCache.berichtKey(BerichtSleutel("00000001000000000002", id)),
      )
  }
  ```
  Pas ook de bestaande `cacheKey`-verwachting aan naar `berichtensessiecache:v4:`.

- [ ] **Stap 2: falende integratietests** in `RedisBerichtenCacheIntegrationTest`, naast de
  bestaande BSN/RSIN-isolatietest (rond regel 1010). Eén testbericht-factory met twee magazijnen
  (`MAGAZIJN_A = "00000001000000000001"`, `MAGAZIJN_B = "00000001000000000002"`) en een gedeeld
  `val gedeeld = UUID.randomUUID()`, onderwerp `"van A"` / `"van B"`, zelfde ontvanger:
  - `store` met beide → `getById(BerichtSleutel(A, gedeeld))` geeft onderwerp `"van A"`, en voor B
    `"van B"`; `getPage` geeft twee entries.
  - `updateBerichtMetadata(BerichtSleutel(A, gedeeld), …, status = "gelezen", map = "Belasting")` →
    B's hash én B's lijst-entry ongewijzigd (status `null`, map `null`); A's lijst-entry bijgewerkt.
  - `delete(BerichtSleutel(A, gedeeld))` → `getById` A `null`, B nog aanwezig; `getPage` één entry
    (van B); `isVerwijderdVoor` A `true`, B `false`.
  - `createBericht` van B terwijl alleen A in de lijst staat → lijst twee entries, hash van B
    bestaat (Lua-script ziet het paar, niet alleen het nummer).
  - `createBericht` van A opnieuw (zelfde paar) → nog steeds één entry van A ("al aanwezig").
  Pas de bestaande tests aan op de nieuwe signaturen; de test "delete-prune verwijdert alle
  list-entries die naar hetzelfde berichtId matchen" (rond regel 620) wordt "… naar hetzelfde
  paar matchen" en houdt een entry van een ander magazijn met hetzelfde nummer over.
  De test met een achtergebleven v1-entry (rond regel 175) blijft geldig: v1 noch v3 valt onder
  de v4-prefix.

- [ ] **Stap 3: draai en zie falen.**
  `./mvnw clean test -pl libraries/fbs-berichtensessiecache -am -Dtest='BerichtenCacheKeyTest,RedisBerichtenCacheIntegrationTest' -Dsurefire.failIfNoSpecifiedTests=false`
  Verwacht: compilatiefout op `BerichtSleutel` (zolang die niet bestaat).

- [ ] **Stap 4: implementeer.**
  - `BerichtSleutel` + extensies in `Bericht.kt` (KDoc: één regel waarom — een berichtnummer is
    alleen uniek binnen het magazijn dat het uitdeelde).
  - Companion: `cacheKey` → `v4`, `berichtKey(sleutel)`, `BERICHT_PREFIX = "bericht:v4:"`,
    `verwijderdKey(ontvanger, sleutel)`, `SEARCH_INDEX = "berichten-idx-v4"`. Vervang het
    v3-commentaar door een v4-regel: de sleutel draagt voortaan het magazijn, en een v3-pod
    zou een v4-lijst op alleen het nummer matchen — dus disjuncte sleutels.
  - `store`: `BerichtenCache.berichtKey(bericht.sleutel)`.
  - `getById`, `isVerwijderdVoor`, `schrijfTombstone`, `delete`: `sleutel` i.p.v. `berichtId`.
    Logregels loggen `sleutel` (OIN + UUID, geen PII).
  - Matchen op het paar: vervang `herbouwLijst`/`blobMatchtBerichtId` door één
    `private fun blobMatcht(json: String, sleutel: BerichtSleutel, context: String): Boolean`
    die `berichtId` **én** `magazijnId` uit de blob vergelijkt (onparsebaar → `false` + warn,
    zoals nu). Comment "berichtId is uniek" → "het paar is uniek".
  - `idVan` → `sleutelVan(json): BerichtSleutel?`; `renewReadTtl`/`renewReadTtlSamenvatting`/
    `verlengTtls`/`verlengHeleSessie` werken met `List<BerichtSleutel>` (`berichten.map { it.sleutel }`).
  - `AANMELD_SCRIPT`: ARGV wordt `berichtId, magazijnId, JSON, TTL, velden…` (velden vanaf
    `ARGV[5]`); treffer alleen als `bericht.berichtId == ARGV[1] and bericht.magazijnId == ARGV[2]`.
    Pas de `for i = 4` naar `for i = 5` en `unpack(ARGV, 4)` naar `unpack(ARGV, 5)` aan, en de KDoc
    ("ARGV: berichtId, magazijnId, …").
  - `BerichtensessiecacheService`: de vier pass-through-methodes nemen `sleutel: BerichtSleutel`.
  - `MockBerichtenCache`: `byId`/`tombstones` op `BerichtSleutel`.

- [ ] **Stap 5: draai en zie slagen** (zelfde commando als stap 3, plus `BerichtensessiecacheServiceTest`).
  Verwacht: `BUILD SUCCESS` — let op: compileert pas volledig na Taak 2 (facade); voer Taak 1 en 2
  zonodig samen uit vóór de eerste groene run.

- [ ] **Stap 6: commit** — `fix(sessiecache): berichtsleutel draagt het magazijn (v4)`.

### Taak 2: Facade en volgen dragen de magazijnId

**Bestanden** (onder `libraries/fbs-berichtensessiecache/src/`):
- Wijzig: `main/kotlin/.../Sessiecache.kt`, `BlockingSessiecache.kt`
- Wijzig: `main/kotlin/.../berichten/Aanmeldingen.kt`, `SessieVolger.kt`, `SessieGebeurtenis.kt` (KDoc)
- Test: `BlockingSessiecacheTest.kt`, `SessieVolgerTest.kt`, `RedisAanmeldingenIntegrationTest.kt`,
  `TombstoneFoutpadenTest.kt`

**Interfaces:**
- Consumeert: `BerichtSleutel`, `Bericht.sleutel` (Taak 1).
- Produceert (publiek, gebruikt door Taak 3):
  ```kotlin
  fun bericht(ontvanger: Identificatienummer, magazijnId: String, berichtId: UUID): Bericht?
  fun werkBerichtBij(ontvanger: Identificatienummer, magazijnId: String, berichtId: UUID, status: Leesstatus?, map: String?): Bericht?
  fun verwijder(ontvanger: Identificatienummer, magazijnId: String, berichtId: UUID)
  ```
- Intern: `Aanmeldingen.meld(cacheKey: String, sleutel: BerichtSleutel)`,
  `registreer(cacheKey, opBericht: (BerichtSleutel) -> Unit, …)`,
  `SessieVolger.meldAan(ontvanger, sleutel: BerichtSleutel)`,
  `Aanmeldingen.KANAAL = "berichtensessiecache:v4:aanmeldingen"`.

- [ ] **Stap 1: falende tests.**
  - `BlockingSessiecacheTest`: `bericht(ontvanger, MAGAZIJN_B, gedeeld)` vraagt de service met
    `BerichtSleutel(MAGAZIJN_B, gedeeld)` (MockK `verify`), ook voor de tombstone-lookup na een
    misser; idem `werkBerichtBij` en `verwijder`.
  - `RedisAanmeldingenIntegrationTest`: `meld(cacheKey, BerichtSleutel(MAGAZIJN_A, id))` komt bij
    de luisteraar aan als exact die sleutel; twee meldingen met hetzelfde id en verschillende
    magazijnen komen beide door.
  - `verdeel` (internal): `"<cacheKey> <uuid>"` zónder magazijn → genegeerd met de bestaande
    warn, geen exception; `"probe <uuid>"` → probe afgehandeld zoals nu.
  - `SessieVolgerTest`: een aanmelding voor `BerichtSleutel(MAGAZIJN_B, gedeeld)` leidt tot
    `getById(BerichtSleutel(MAGAZIJN_B, gedeeld), …)` en een `BerichtBijgekomen` met B's bericht.

- [ ] **Stap 2: draai en zie falen.**
  `./mvnw clean test -pl libraries/fbs-berichtensessiecache -am`
  Verwacht: compilatie- of assertiefouten op de nieuwe signaturen.

- [ ] **Stap 3: implementeer.**
  - Facade-signaturen zoals hierboven; KDoc van `bericht` krijgt één zin: een berichtnummer is
    alleen uniek binnen het magazijn dat het uitdeelde, dus het magazijn hoort bij de vraag.
  - `BlockingSessiecache` bouwt `BerichtSleutel(magazijnId, berichtId)` en geeft die door;
    `meldAanGevolgdeSessies` gebruikt `bericht.sleutel`.
  - `Aanmeldingen.publiceer`: `"$sleutel$SCHEIDING${id.berichtId}$SCHEIDING${id.magazijnId}"`;
    `verdeel` splitst op `SCHEIDING`: precies drie delen voor een aanmelding, twee voor een probe;
    alles anders → bestaande warn. `KANAAL` → `v4` (comment: zelfde versie als de sessie-keys, en
    de berichtvorm is veranderd).
  - `SessieVolger.stuurDoor(sleutel, …)` → `berichtenCache.getById(sleutel, ontvanger)`; KDoc
    regel 24 "ontdubbelt hij op `berichtId`" → "op (`magazijnId`, `berichtId`)". Idem
    `SessieGebeurtenis.kt:10`.

- [ ] **Stap 4: draai en zie slagen** — `./mvnw clean test -pl libraries/fbs-berichtensessiecache -am`.
  Verwacht `BUILD SUCCESS`, JaCoCo-gate gehaald.

- [ ] **Stap 5: commit** — `fix(sessiecache): facade en volgen kennen het bericht aan magazijn en nummer`.

### Taak 3: Uitvraag-API opent, wijzigt en verwijdert per (magazijnId, berichtId)

**Bestanden** (onder `services/berichtenuitvraag/src/`):
- Wijzig: `main/resources/openapi/berichtenuitvraag-api.yaml`
- Wijzig: `main/kotlin/.../uitvraag/UitvraagResource.kt`, `BerichtOphaalService.kt`,
  `BerichtBeheerService.kt`, `UitvraagDtoMapper.kt`
- Wijzig: `test/kotlin/.../uitvraag/MockSessiecache.kt` (map op `Pair(magazijnId, berichtId)`)
- Test: `OpenApiContractTest.kt`, `BerichtOphaalServiceTest.kt`, `BerichtBeheerServiceTest.kt`,
  `DualWriteFaultTest.kt`, `UitvraagDtoMapperTest.kt`, `VolgSseContractTest.kt`, `e2e/UitvraagKetenE2eTest.kt`
- Wijzig: `bruno/berichtenuitvraag/uitvraag/4-bericht-ophalen.bru`, `5-bijlage-ophalen.bru`

**Interfaces:**
- Consumeert: facade uit Taak 2.
- Produceert (gegenereerd uit de spec): `getBerichtById(berichtId: UUID, xOntvanger: String, magazijnId: String)`,
  `getBijlage(berichtId: UUID, bijlageId: UUID, xOntvanger: String, magazijnId: String)` — exacte
  parametervolgorde na `generate-sources` aflezen uit `target/generated-sources/openapi/`.
  `BerichtOphaalService.haalBericht(xOntvanger, magazijnId, berichtId)`,
  `haalBijlage(xOntvanger, magazijnId, berichtId, bijlageId)`.

- [ ] **Stap 1: spec.** Gebruik de skill `/openapi-wijziging`.
  - `info.version: 0.4.0`.
  - `$ref: '#/components/parameters/MagazijnIdQuery'` toevoegen aan `GET /berichten/{berichtId}`
    en `GET /berichten/{berichtId}/bijlagen/{bijlageId}`.
  - Beschrijving `MagazijnIdQuery`: een berichtnummer is alleen uniek binnen het magazijn dat het
    uitdeelde; staat het paar niet in de sessie, dan 404. Neem de waarde uit `magazijnId` van de
    berichtenlijst of volg `_links.self`.
  - `_volgen`-beschrijving (rond regel 217): ontdubbelen op (`magazijnId`, `berichtId`).
  - Tekst "De opzoeking is per ontvanger gesleuteld" (rond regel 63) nalopen en waar nodig
    aanvullen met het magazijn.
  - `npx @stoplight/spectral-cli lint services/berichtenuitvraag/src/main/resources/openapi/berichtenuitvraag-api.yaml --ruleset https://static.developer.overheid.nl/adr/ruleset.yaml`
    → geen nieuwe bevindingen.

- [ ] **Stap 2: falende tests.**
  - `OpenApiContractTest`: bestaande GET-by-id- en bijlage-cases krijgen `?magazijnId=`; nieuw:
    - `GET /berichten/{id}` zonder `magazijnId` → 400 Problem (schema-gevalideerd).
    - `GET /berichten/{id}?magazijnId=<onbekende OIN>` → 404 Problem; `magazijnId=a:b` → 404.
    - Twee berichten met hetzelfde id in `MockSessiecache` (magazijn A en B) → elk openen geeft
      eigen `magazijnId` en onderwerp; `_links.self.href` eindigt op `?magazijnId=<OIN>`.
  - `BerichtOphaalServiceTest`: `haalBericht(x, MAGAZIJN_B, gedeeld)` vraagt
    `sessiecache.bericht(ontvanger, MAGAZIJN_B, gedeeld)` en routeert naar B; bij een misser géén
    `magazijnRouter`-aanroep.
  - `BerichtBeheerServiceTest`: `patch(x, gedeeld, MAGAZIJN_B, …)` → `werkBerichtBij(ontvanger,
    MAGAZIJN_B, gedeeld, …)`; idem `verwijder` en de compensatie-invalidate in `DualWriteFaultTest`.
  - `UitvraagDtoMapperTest`: `self.href == "/api/v1/berichten/$id?magazijnId=$oin"` voor detail
    én samenvatting.

- [ ] **Stap 3: draai en zie falen.**
  `./mvnw clean test -pl services/berichtenuitvraag -am -Dtest='OpenApiContractTest,BerichtOphaalServiceTest,BerichtBeheerServiceTest,UitvraagDtoMapperTest' -Dsurefire.failIfNoSpecifiedTests=false`

- [ ] **Stap 4: implementeer.**
  - `UitvraagResource`: geef `magazijnId` door aan `haalBericht`/`haalBijlage`.
  - `BerichtOphaalService`: `zoekBerichtInCache(xOntvanger, magazijnId, berichtId)` →
    `sessiecache.bericht(ontvanger, magazijnId, berichtId)`. Routering blijft op
    `bericht.magazijnId` uit de cache (die is nu per definitie gelijk aan de query); het
    bestaande comment "niet op iets uit het verzoek" bijwerken: de query bepaalt wélk bericht,
    de cache blijft de autoriteit dat het van deze ontvanger is.
  - `BerichtBeheerService`: `werkBerichtBij(ontvangerId, magazijnId, berichtId, …)`,
    `verwijder(ontvangerId, magazijnId, berichtId)`, ook in `invalideerCacheNaMagazijnWrite`
    (krijgt `magazijnId` erbij).
  - `UitvraagDtoMapper.berichtLinks(magazijnId, berichtId)`:
    ```kotlin
    private fun berichtLinks(magazijnId: String, berichtId: UUID): BerichtLinks = BerichtLinks().apply {
        val magazijn = URLEncoder.encode(magazijnId, StandardCharsets.UTF_8)
        self = Link().apply { href = "${ApiInfo.BASE_PATH}/berichten/$berichtId?magazijnId=$magazijn" }
    }
    ```
  - `MockSessiecache`: opslag op het paar.
  - Bruno: `?magazijnId={{magazijnId}}` in requests 4 en 5.

- [ ] **Stap 5: draai de hele module** — `./mvnw clean verify -pl services/berichtenuitvraag -am`.
  Verwacht `BUILD SUCCESS` (JaCoCo + detekt).

- [ ] **Stap 6: commit** — `fix(uitvraag)!: bericht openen en bijlage ophalen vragen de magazijnId`.

### Taak 4: Aanmeld-ontdubbeling per magazijn

**Bestanden** (onder `services/berichtenuitvraag/src/`):
- Wijzig: `main/kotlin/.../aanmeld/AanmeldDeduplicatie.kt`, `RedisAanmeldDeduplicatie.kt`, `AanmeldService.kt`
- Test: `aanmeld/AanmeldServiceTest.kt`, `aanmeld/RedisAanmeldDeduplicatie*Test.kt`

**Interfaces:**
- Produceert: `fun eerstgezien(magazijnId: String, eventId: String): Boolean`,
  `fun verwijder(magazijnId: String, eventId: String)`; sleutel `aanmeld:event:<magazijnId>:<eventId>`.

- [ ] **Stap 1: falende tests.**
  - `RedisAanmeldDeduplicatie*Test`: `eerstgezien(A, "e1")` → `true`; daarna `eerstgezien(B, "e1")`
    → `true`; `eerstgezien(A, "e1")` → `false`; `verwijder(A, "e1")` laat B's marker staan.
  - `AanmeldServiceTest`: twee events met hetzelfde `id` en `data.berichtId` maar afzenders van
    twee magazijnen → `sessiecache.schrijfBericht` twee keer aangeroepen, met
    `magazijnId` A resp. B.

- [ ] **Stap 2: draai en zie falen** —
  `./mvnw clean test -pl services/berichtenuitvraag -am -Dtest='AanmeldServiceTest,RedisAanmeldDeduplicatie*Test' -Dsurefire.failIfNoSpecifiedTests=false`.

- [ ] **Stap 3: implementeer.** Interface + Redis-implementatie op `(magazijnId, eventId)`;
  `AanmeldService.verwerk` geeft `gepubliceerd.magazijnId` mee aan `eerstgezien` en `verwijder`.
  KDoc van `AanmeldDeduplicatie`: een CloudEvents-`id` is alleen uniek binnen zijn bron, en de bron
  is hier het magazijn.

- [ ] **Stap 4: draai en zie slagen** — `./mvnw clean verify -pl services/berichtenuitvraag -am`.

- [ ] **Stap 5: commit** — `fix(uitvraag): ontdubbel aanmeldingen per magazijn`.

### Taak 5: Demo-berichtenbox en documentatie

**Bestanden:**
- Wijzig: `demo/demo-console/src/main/resources/META-INF/resources/berichtenbox.js`
- Wijzig: `demo/magazijn-simulator/README.md` (alinea rond regel 182-187)
- Wijzig: `docs/operations/redisearch-schema-bump.md` (tabel rond regel 140: rij v3→v4 met reden;
  de bestaande inconsistentie "prefix v1→v2 / index v3" rechtzetten)
- Wijzig: `docs/operator-handleiding-uitvraag.md` (rond regel 233-238: het kanaal draagt nu
  sessie-key, berichtId en magazijnId; nog steeds geen berichtgegevens)
- Wijzig: `docs/architecture/workspace.dsl:146` alleen als de tekst "hetzelfde gepubliceerde
  bericht" zonder magazijn misleidend wordt.

- [ ] **Stap 1: `berichtenbox.js`.** `magazijnPerBericht` wordt overbodig: geef bij elke knop en
  elke detail-/bijlage-aanroep het hele bericht (of `magazijnId` + `berichtId`) door.
  `toonDetail(magazijnId, berichtId)` → `GET /api/v1/berichten/<id>?magazijnId=<oin>`
  (`encodeURIComponent` op beide); `downloadBijlage` idem. Sleutel interne state (geselecteerd
  bericht, DOM-id's) op `magazijnId + '/' + berichtId`.
- [ ] **Stap 2:** `./mvnw clean test -pl demo/demo-console -am` → `BUILD SUCCESS`. Handmatig: de
  demo-stack starten (`/demo-starten`) en een bericht openen, verplaatsen en verwijderen.
- [ ] **Stap 3:** docs bijwerken zoals hierboven.
- [ ] **Stap 4: commit** — `fix(demo): demo-berichtenbox opent berichten per magazijn`.

### Taak 6: Verificatie en oplevering

- [ ] `./mvnw clean verify -pl libraries/fbs-berichtensessiecache,services/berichtenuitvraag,demo/demo-console,demo/magazijn-simulator -am`
  → `BUILD SUCCESS`; warnings nalopen met `/warnings-triage`.
- [ ] `./mvnw clean test -pl services/berichtenmagazijn -am` (de magazijn-kant is niet gewijzigd,
  maar deelt `fbs-common`; bevestigt dat er niets meekwam).
- [ ] Reviewer-subagents: `kotlin-stijl-reviewer`, `taal-en-commentaar-reviewer`,
  `pii-log-auditor` (OIN in logs/URL mag, BSN niet), `openapi-adr-reviewer`,
  `test-dekkings-reviewer`. Hoge bevindingen direct oplossen.
- [ ] Plan op `**Status:** Uitgevoerd` zetten.
- [ ] Draft-PR via `/pr-klaarmaken`, body verwijst naar `MinBZK/MijnOverheidZakelijk#1004`,
  geen reviewer. In de body expliciet: brekende API-wijziging (0.4.0), proeftuin-PR vereist vóór
  merge, en dat lopende sessies na uitrol één nieuwe ophaalronde nodig hebben.
- [ ] Vervolg (buiten deze PR, apart te bevestigen): PR in `MinBZK/moza-poc` voor
  `berichtenbox-keten.js` (`haalInhoud` en bijlage-download met `?magazijnId=`, state en
  detailpagina op het paar), daarna de pin in `compose.yaml`.

## Verificatie

De acceptatiecriteria van #1004:

| Criterium | Bewijs |
|---|---|
| Beide berichten met eigen inhoud | `RedisBerichtenCacheIntegrationTest` (store/getById per paar), `OpenApiContractTest` (twee magazijnen, zelfde id) |
| Gelezen/verplaatsen/verwijderen raakt alleen het bedoelde bericht | `RedisBerichtenCacheIntegrationTest` (update/delete laat B ongemoeid, hash én lijst), `BerichtBeheerServiceTest` |
| Test die de samenloop nabootst en faalt op de huidige implementatie | De nieuwe integratietests uit Taak 1 stap 2, eerst rood gezien in stap 3 |

## Uitvoering

Afwijkingen van het plan, ontstaan tijdens de uitvoering en de review:

- **`MagazijnIdQuery` eist een OIN** (`pattern: '^[0-9]{20}$'`) in plaats van `minLength: 1`. De
  waarde uit de query komt in Redis-sleutels en logregels terecht; zonder patroon kon daar een BSN of
  een regelafbreking in belanden. Gevolg: een `magazijnId` die geen OIN is geeft op alle vier de
  operaties een 400; een onbekende OIN blijft 404 (ophalen) of 502 (`PATCH`/`DELETE`).
- **Proeftuin** (`MinBZK/moza-poc`, branch `fix/1004-bericht-per-magazijn`): de keten-bron geeft een
  bericht als `id` het paar `magazijnId:berichtId`, zodat de render-laag, de state en de detailpagina
  ongewijzigd per bericht blijven rekenen. Elk verzoek over één bericht stuurt `?magazijnId=` mee.
  De nginx-route voor bijlagen gaf bewust alleen `$uri` door; die laat nu precies `magazijnId` door.
- **Volgorde van mergen omgekeerd:** een uitvraag zonder de verplichte parameter negeert een
  onbekende query-parameter, dus de proeftuin-wijziging werkt al tegen de huidige uitvraag. Eerst de
  proeftuin mergen en de pin in `compose.yaml` bijwerken, daarna deze PR.
- Taak 1 en 2 kwamen in één commit: de library compileert alleen met beide.

