**Status:** Uitgevoerd

# Plan: Review-bevindingen PR #65 (Berichtenuitvraag)

## Context

PR [#65](https://github.com/MinBZK/moza-poc-fbs-berichtenbox/pull/65) (`feature/issue-413-berichtenuitvraag`) staat op **CHANGES_REQUESTED** door @ericwout-overheid (35 inline-opmerkingen) en heeft 7 aanvullende `COMMENTED`-punten van @mreuvekamp. De PR voegt de nieuwe `services/berichtenuitvraag` toe en raakt en passant `berichtensessiecache` (TTL, naamgeving, comments) en `berichtenmagazijn` (Bruno-collectie).

Dit plan groepeert de 42 opmerkingen tot 9 werkblokken en levert per blok concrete file-/regelverwijzingen, codewijzigingen en testimpact. Blok B (berichtenuitvraag als losstaande service → module/package) wordt **niet** in deze PR opgepakt; daar komt een apart issue voor (suggestie reviewer zelf). Blok A beperkt zich tot het verhogen van de TTL; of dat genoeg is om reviewer-zorg C24 (update-/delete afhankelijk van cache) weg te nemen, wordt eerst teruggelegd bij de reviewer voordat we het pad "updates losweken van cache" inplannen.

Reviewers: @ericwout-overheid (HOOG: A, B, E, F; MEDIUM: C, D; LAAG: G, H, I, J), @mreuvekamp (naming + unused import).

## Bevindingen geconsolideerd

| Blok | Onderwerp | Comments | Severity |
|---|---|---|---|
| A | TTL te kort + obsolete self-healing-comments | C1, C13, C15, C16 | Hoog |
| C | Magie-getallen configureerbaar | C7, C17, C28 | Medium |
| D | Backwards-compat dood gewicht | C6, C12 | Medium |
| E | Velden in MagazijnBericht / Bericht + cache-samenvatting-type | C9 (#272), C25, C26, C27a, C33 | Medium |
| F | `BerichtensessiecacheService.ophalenBerichten` te lang + betere naam | C20, C31 | Medium |
| G | Methode-/variabelenaamgeving | C8, C30, C34, C35, C36 | Laag/Medium |
| H | Comment- en spec-tekst opschonen | C14, C19, C22, C29, C30 (Ontvanger), C32, C33 (spec) | Laag |
| I | Files opruimen (.gitkeep, unused import) | C3, C4, C37 | Laag |
| J | Bruno-/README-documentatie aanvullen | C2, C5 | Laag |
| K | "kromme URI"-comment verduidelijken | C27 (MagazijnRouter) | Laag |

**Niet in dit plan (apart issue / aparte PR):**
- **Blok B** — C23 (`berichtenuitvraag` als module i.p.v. losse service); reviewer stelt zelf voor dit in een aparte PR te doen. Aanmaken issue volgt na merge van deze PR.
- **C24** — update-/delete-acties afhankelijk van cache-state (de 409 die de reviewer in Bruno zag). Eerst terugleggen bij reviewer of de TTL-verhoging (A1) voldoende is. Zo niet: vervolg-issue voor "PATCH/DELETE losweken van cache" (opties: fan-out naar alle magazijnen, of `magazijnId` als spec-parameter accepteren).

---

## Blok A — TTL fors omhoog + self-healing-comments opschonen

**Doel:** de TTL zo verhogen dat reviewer-test-ervaring (en straks portaal-sessies) niet binnen 60 seconden ontspoort. Sliding-TTL "self-healing"-kanttekeningen (C13, C15, C16) worden bij een 24u-TTL feitelijk onjuist en moeten herzien.

**Bewust niet in dit blok:** PATCH/DELETE losweken van cache (C24). Eerst afstemmen met reviewer of de TTL-verhoging volstaat. Zie "Bewust uitgesteld".

### A1. TTL verhogen naar 24 uur (configureerbaar)

**Files:**
- Wijzig: `services/berichtensessiecache/src/main/resources/application.properties:29-31`
- Wijzig (KDoc consistent): `services/berichtensessiecache/src/main/kotlin/nl/rijksoverheid/moz/fbs/berichtensessiecache/berichten/BerichtenCache.kt:61` (default in `@ConfigProperty`)
- Wijzig (KDoc/comments die "60s" of "tot TTL ≤60s" zeggen): `BerichtenCache.kt:633`, `services/berichtenuitvraag/src/main/resources/openapi/berichtenuitvraag-api.yaml:151` ("60s sliding TTL").

**Stappen:**

1. Vervang properties-block (regel 29-31) door:

   ```properties
   # Cache TTL (sliding): elke succesvolle read verlengt TTL op sessie-keys en betroffen
   # berichthashes. Default 24u dekt een werkdag-sessie zonder herhaalde ophaal-flow; korter
   # zetten kan via `berichtensessiecache.ttl=PT1H` per omgeving wanneer geheugendruk daar om vraagt.
   berichtensessiecache.ttl=PT24H
   ```

2. Pas `@ConfigProperty(name = "berichtensessiecache.ttl", defaultValue = "PT60S")` aan naar `defaultValue = "PT24H"` in `RedisBerichtenCache` (regel 61). Default is fall-back voor tests zonder properties-override; consistent met productie-default voorkomt verwarrende test-failures.

3. Verwijder alle absolute-tijd-vermeldingen ("≤60s", "60s sliding TTL") uit comments en spec. Vervang door "tot TTL" of "de geconfigureerde TTL".

4. Voeg integratietest toe die de sliding-renew niet meet op een wallclock van 60s — bestaande TTL-tests gebruiken een test-profile met een eigen TTL; controleer dat geen test hard `PT60S` of `60` veronderstelt.

**Testimpact:**
- `services/berichtensessiecache/src/test/kotlin/.../BerichtenCacheTtlTest.kt` (of equivalent): controleer dat de tests een eigen `TestProfile` met korte TTL gebruiken (`PT2S` o.i.d.); zo niet, splits af naar een `TestProfile.SHORT_TTL` zodat de prod-default verhoogd kan worden zonder testduur te verlengen.

### A2. Self-healing/TTL-comments opschonen

**Files & wijzigingen:**
- `BerichtenCache.kt:404-405`: verwijder de "zelf-helend via TTL"-zin uit de update-KDoc — bij 24u TTL is dat geen oplossing meer. Vervang door: *"Bij afbreken herhalen we tot een klein plafond; daarna laten we de cache met rust en levert de operatie een retriable 503 op zodat de client opnieuw kan proberen."* (de gedragsregel staat al in `updatePoging` regel 504-510, dit klopt nu echt.)
- `BerichtenCache.kt:548-550`: vervang `"hoeft te dragen"` en `"exhaustief"` door Nederlands: `"Sealed zodat de 'niets te doen'-tak geen lege lijst meeneemt en de write-fase op alle toestanden moet matchen."`
- `BerichtenCache.kt:567-569`: verwijder "(zelf-helend via TTL)" uit de delete-KDoc; vervang door: *"daarna laten we de cache met rust en logt deze service een alertbare desync-melding ([ALERT_CACHE_DESYNC] in BerichtBeheerService)"*.
- `BerichtenCache.kt:630-637`: vervang de comment door:
  ```kotlin
  // errorf (niet warnf): de invalidate is niet toegepast en de stale entry blijft tot
  // de geconfigureerde TTL. Onder de uitvraag dual-write-compensatie betekent dit een
  // tijdelijk stale cache ná een geslaagde magazijn-mutatie — die call krijgt hier een
  // 2xx (idempotente delete), dus dit log is het enige alertbare signaal van de
  // mislukte invalidate.
  ```

---

## Blok C — Magie-getallen configureerbaar

### C1. `Bericht.MAX_BIJLAGEN` en `BijlageSamenvatting.NAAM_MAX_LENGTE` (C7)

**Files:**
- Wijzig: `services/berichtensessiecache/src/main/kotlin/nl/rijksoverheid/moz/fbs/berichtensessiecache/berichten/Bericht.kt:54-59, 72-74`
- Wijzig: `services/berichtensessiecache/src/main/resources/application.properties`

**Ontwerpkeuze:** waarden blijven defensieve grenzen, maar worden via `@ConfigProperty` ingelezen op een aparte `BerichtLimieten` `@Singleton`-bean die in de `init`-blocks van `Bericht`/`BijlageSamenvatting` wordt geraadpleegd via een CDI-lookup-injectie (`Arc.container().select(...)`) of — schoner — door de validatie naar een aparte `BerichtValidator` te verplaatsen die in services wordt geïnjecteerd. **Aanpak:** validatie verplaatsen voorkomt CDI-magie in data classes.

**Stappen:**

1. Voeg toe aan `application.properties`:
   ```properties
   # Defensieve grenzen op binnenkomende cache-entries (cache-store en magazijn-mapping).
   # Geen functionele limieten — alleen tegen pathologische/kwaadaardige input.
   berichtensessiecache.bericht.max-bijlagen=100
   berichtensessiecache.bericht.bijlage-naam-max-lengte=255
   ```

2. Maak `services/berichtensessiecache/src/main/kotlin/.../berichten/BerichtLimieten.kt`:
   ```kotlin
   package nl.rijksoverheid.moz.fbs.berichtensessiecache.berichten

   import io.smallrye.config.ConfigMapping

   @ConfigMapping(prefix = "berichtensessiecache.bericht")
   interface BerichtLimieten {
       fun maxBijlagen(): Int
       fun bijlageNaamMaxLengte(): Int
   }
   ```

3. Verplaats de `MAX_BIJLAGEN`/`NAAM_MAX_LENGTE`-checks uit de `init`-blocks naar een `BerichtFactory.fromMagazijn(...)`-functie die de limieten inspuit, óf — pragmatischer voor PoC-fase — laat de constants in `companion object` maar markeer ze `@JvmStatic`-getters die lazy uit `Arc.container().select(BerichtLimieten::class.java).get()` lezen.

   **Concrete pragmatische keuze (minimale wijziging):** behoud `const val`-syntaxis maar pas applicatieve property-overrides toe via test-profielen; documenteer in de KDoc dat productie-overrides eerst een refactor naar `BerichtFactory` vereisen. **Open vraag voor reviewer: pakken we de full-refactor of de pragmatische tussenstap?**

4. Tests:
   - Unit-test in `BerichtTest`: bouw met `maxBijlagen=2` en bevestig `IllegalArgumentException` bij 3 bijlagen.
   - QuarkusTest-profile met override `berichtensessiecache.bericht.max-bijlagen=2`.

### C2. `MagazijnRouter` connect-/read-timeouts configureerbaar (C28)

**Files:**
- Wijzig: `services/berichtenuitvraag/src/main/kotlin/.../MagazijnRouter.kt:88-96`
- Wijzig: `services/berichtenuitvraag/src/main/resources/application.properties`

**Stappen:**

1. Voeg toe:
   ```properties
   magazijnen.client.connect-timeout=PT2S
   magazijnen.client.read-timeout=PT10S
   ```

2. Injecteer via constructor in `MagazijnRouter`:
   ```kotlin
   @ApplicationScoped
   class MagazijnRouter(
       private val config: MagazijnenConfig,
       @param:ConfigProperty(name = "magazijnen.client.connect-timeout", defaultValue = "PT2S") private val connectTimeout: Duration,
       @param:ConfigProperty(name = "magazijnen.client.read-timeout",    defaultValue = "PT10S") private val readTimeout: Duration,
   )
   ```
   Verwijder de twee `companion object`-constanten.

3. Tests: bestaande `MagazijnRouterTest`-suite gebruikt waarschijnlijk default-config; voeg geen nieuwe test (constanten-naar-config is geen gedragwijziging).

### C3. Comment over constanten in `BerichtensessiecacheResource` (C17, C18)

Zie [Blok H](#blok-h--commentaar--spec-opschonen).

---

## Blok D — Backwards-compat dood gewicht

### D1. `Bericht.kt:45-47` — comment "backwards-compat met oude cache-entries"

**File:** `services/berichtensessiecache/src/main/kotlin/.../berichten/Bericht.kt:45-47`

Vervang regels 45-47 door:

```kotlin
// `inhoud` mag leeg zijn: niet elk magazijn levert een inhoudssamenvatting op de lijst-respons
// (zie MagazijnBericht). Voor consumers blijft `inhoud` in de OpenAPI-spec required zodat de
// veld-aanwezigheid stabiel is — alleen de waarde kan leeg-string zijn.
```

**Onderbouwing:** de "oude cache-entries"-uitleg is niet meer geldig zonder live data; de leeg-string-toestaan-keuze heeft een geldige magazijn-respons-reden die niet over BC gaat.

### D2. `BerichtenCache.kt:352-353` — comment "backwards-compat: oude hash-entries"

**File:** `services/berichtensessiecache/src/main/kotlin/.../berichten/BerichtenCache.kt:352-353`

Vervang regels 352-353 door:

```kotlin
// Niet-kritieke velden (inhoud/bijlagen/map) zijn optioneel in het hash-schema: ontbreken
// betekent "magazijn leverde dit veld niet", niet "corrupte entry". Vandaar default leeg/null
// in plaats van fail-loud zoals bij berichtId/afzender/ontvanger/onderwerp/publicatietijdstip.
```

---

## Blok E — `MagazijnBericht` / `Bericht`: velden heroverwegen

**Context discussie reviewer:** "altijd-lege inhoud" suggereert dat het schema gewoon `BerichtSamenvatting` had moeten zijn (C25, C33). `mimeType` en `grootte` op bijlagen zouden handig zijn voor de frontend (C26). `gewijzigdOp` ontbreekt (C27a).

### E1. Inhoud op de lijst — wat levert het magazijn echt?

**File:** `services/berichtenmagazijn/src/main/resources/openapi/berichtenmagazijn-api.yaml:574-590` (`BerichtSamenvatting`)

Reviewer (mreuvekamp, C33): *"'inhoud' zit ook in de berichtsamenvatting die uit het magazijn komt toch?"*

Verificatie: regel 583 toont dat `inhoud` **wel** required is in `BerichtSamenvatting`. De default-empty-string in `MagazijnBericht.inhoud` (`= ""`) is dus dood gewicht — een spec-conforme magazijn-respons heeft altijd `inhoud`.

**Stappen:**

1. Verwijder de default uit `MagazijnBericht.kt:33`:
   ```kotlin
   @param:JsonProperty("inhoud") val inhoud: String,
   ```

2. Verwijder de hele comment-block "regels 30-32" en de default `= ""`.

3. Test: bestaande `MagazijnBerichtTest`/`BerichtensessiecacheServiceTest` controleren of spec-conforme fixture nog parset; voeg test toe die parse-failure verwacht zonder `inhoud`.

### E2. Bijlage-`mimeType` en `grootteInBytes` op de samenvatting

**Files:**
- Magazijn-spec: `services/berichtenmagazijn/src/main/resources/openapi/berichtenmagazijn-api.yaml` — zoek het bestaande `BijlageSamenvatting`-schema.
- Sessiecache-spec: `services/berichtensessiecache/src/main/resources/openapi/berichtensessiecache-api.yaml`
- Sessiecache-domein: `MagazijnBericht.MagazijnBijlage`, `Bericht.BijlageSamenvatting`, `BerichtenCache.berichtToHash`/`hashToBericht`.

**Stappen:**

1. Stel vast wat `BerichtSamenvatting` in het magazijn vandaag levert per bijlage (regel 583 toont `bijlagen[]` met `bijlageId` + `naam`; checken of `mimeType`/`grootteInBytes` daar al staan).
2. Als magazijn ze al levert: aanvullen in `MagazijnBericht.MagazijnBijlage` (`mimeType: String?`, `grootteInBytes: Long?`), `Bericht.BijlageSamenvatting` en de hash-mapping.
3. Als magazijn ze niet levert: apart issue aanmaken voor magazijn-uitbreiding; in deze PR uitsluitend de NB-comment ("alleen download-handles") aanscherpen tot "alleen download-handles tot bijlage-metadata in spec is uitgebreid (#TODO-issue)".

### E3. `gewijzigdOp` doorgeven aan de frontend

Reviewer wil weten of `gewijzigdOp` naar de frontend komt (C27a). Vandaag wordt het in `MagazijnBerichtStatus` (regel 75) bewust **niet** overgenomen.

**Keuze:** opnemen in `Bericht.status` als `gewijzigdOp: Instant?`. Frontend-mockups zullen het waarschijnlijk willen tonen ("ongelezen sinds X"). Bij twijfel: vraag aan @ericwout-overheid of frontend dit veld concreet nodig heeft vóór we cache-payload vergroten.

**Stappen (als groen licht):**

1. `MagazijnBericht.MagazijnBerichtStatus` — voeg `@JsonProperty("gewijzigdOp") val gewijzigdOp: Instant? = null` toe.
2. `Bericht` — voeg `val gewijzigdOp: Instant? = null` toe.
3. `BerichtenCache.berichtToHash`/`hashToBericht` — serialiseer/deserialiseer als ISO-8601 string-veld (niet in `SAMENVATTING_VELDEN`? wél, want frontend toont dit op de lijst).
4. Sessiecache + uitvraag spec: `Bericht.gewijzigdOp` toevoegen (optional).
5. Tests: roundtrip cache write/read in `BerichtenCacheIntegrationTest`; mapper-test in `MagazijnBerichtTest`.

---

### E4. FT.SEARCH-samenvatting-projectie: maak het type-zichtbaar dat velden ontbreken (C9)

**File:** `services/berichtensessiecache/src/main/kotlin/.../berichten/BerichtenCache.kt:262-271` (`samenvattingQueryArgs`) en regels 366-378 (`documentToBericht`).

**Reviewer-zorg:** `samenvattingQueryArgs` haalt alleen een subset velden uit RediSearch; `documentToBericht` vult `inhoud=""`, `bijlagen=emptyList()` en `aantalBijlagen=0` met defaults. Caller kan niet onderscheiden of de cache het bericht echt zonder inhoud heeft opgeslagen of dat de samenvatting-projectie het veld weggelaten heeft. Bij een latere `getById` op hetzelfde berichtId-key levert de hash-fetch wél de volledige inhoud — twee paden, twee waardes, één type. Dat is de "verwarrende oplossing" die de reviewer signaleert.

**Ontwerpkeuze:** introduceer `BerichtSamenvatting` als apart cache-domein-type (analoog aan het magazijn dat dit al doet, zie E1):

```kotlin
data class BerichtSamenvatting(
    val berichtId: UUID,
    val afzender: String,
    val ontvanger: String,
    val onderwerp: String,
    val publicatietijdstip: Instant,
    val magazijnId: String,
    val aantalBijlagen: Int,
    val map: String? = null,
    val status: Leesstatus? = null,
)
```

`BerichtenPage` wordt dan `BerichtenPage(berichten: List<BerichtSamenvatting>, ...)`. `getById` blijft `Bericht` (volledige representatie). De spec-`BerichtSamenvatting` (`berichtensessiecache-api.yaml`) hoeft niet te wijzigen — die levert al de lichtgewicht vorm; alleen de in-memory mapper hoeft consistent te zijn.

**Stappen:**

1. Voeg `BerichtSamenvatting` toe in `Bericht.kt` (zelfde file, voor cohesie).
2. Voeg `documentToSamenvatting(doc)` toe in `BerichtenCache.kt` — alleen de samenvatting-velden, géén defaults voor ontbrekende velden.
3. Pas `getPage`/`getPageFiltered`/`search`-signaturen aan naar `Uni<SamenvattingPage?>` (of een gunite type-alias).
4. `BerichtensessiecacheService.getBerichten`/`zoekBerichten`/return-types meeschuiven.
5. `BerichtensessiecacheResource.toResponse()` mapper voor `BerichtSamenvatting` → spec-DTO; bestaande `Bericht`-mapper blijft voor `getById`.
6. Verwijder de defaults `?: ""`, `?: emptyList()`, `?: 0` uit `documentToBericht` — voor de volledige `getById`-pad zijn ze nu fail-loud (`error("hash mist 'inhoud'...")`) consistent met de overige kernvelden.
7. Tests: bestaande `BerichtenCacheIntegrationTest` controleert dat `getPage` velden teruggeeft; pas assertions aan op `BerichtSamenvatting`-vorm.

**Onderbouwing:** dit is een meer-werk-blok (raakt service-laag + resource + tests), maar het is de echte oplossing van de "verwarring lege/ontbrekende" die de reviewer aankaart. Anders blijven we 3 type-onzekerheden in de mapper houden (inhoud, bijlagen, aantalBijlagen) die elk een eigen subtiele bug-vector zijn.

---

## Blok F — Lange functie + naamgeving (`ophalenBerichten` → `haalBerichtenOp`)

### F1. `BerichtensessiecacheService.haalBerichtenOp` opdelen (C20, C31)

**File:** `services/berichtensessiecache/src/main/kotlin/.../berichten/BerichtensessiecacheService.kt:60-214`

Huidige `ophalenBerichten` is 154 regels. Opdelen in:

```kotlin
fun haalBerichtenOp(ontvanger: String): Multi<MagazijnEvent> {
    val cacheKey = BerichtenCache.cacheKey(ontvanger)
    val clients = clientFactory.getAllClients()
    val accumulator = OphaalAccumulator(clients.size)

    setBezigStatusOfFaalMet409(cacheKey, accumulator)

    val perMagazijnStreams = clients.map { (id, client) -> magazijnStream(id, client, accumulator) }
    val voltooiingsEvent = voltooiingsPipeline(cacheKey, accumulator, clients.size).toMulti()

    return Multi.createBy().concatenating().streams(
        Multi.createBy().merging().streams(perMagazijnStreams),
        voltooiingsEvent,
    )
}
```

Met deze privé-functies (één verantwoordelijkheid per stuk):

- `private fun setBezigStatusOfFaalMet409(cacheKey: String, acc: OphaalAccumulator): Unit` — bevat regels 73-90 (lock + 409).
- `private fun magazijnStream(id: String, client: MagazijnClient, acc: OphaalAccumulator): Multi<MagazijnEvent>` — bevat regels 92-153 (één magazijn-bevraging + result-mapping).
- `private fun voltooiingsPipeline(cacheKey: String, acc: OphaalAccumulator, totaal: Int): Uni<MagazijnEvent>` — bevat regels 157-212 (cache-store + GEREED/FOUT-event).
- `private class OphaalAccumulator(val totaalMagazijnen: Int)` — wrapper voor `ConcurrentLinkedQueue`, `geslaagd`, `mislukt`-counters (vervangt de drie lokale variabelen).

**Stappen:**

1. **Hernoem eerst** `ophalenBerichten` naar `haalBerichtenOp` (single-call site in `BerichtenOphalenResource`; pas die mee aan). Commit.
2. Schrijf eerst een integratietest die de huidige `Multi<MagazijnEvent>`-output zwart-doos-vergelijkt met een fixture (volgorde, MAGAZIJN_BEVRAGING_GESTART/VOLTOOID + OPHALEN_GEREED). Als die test al bestaat: hergebruik.
3. Voer de splitsing door, hou de bestaande integratietest groen.
4. Commit per logische sub-stap (lock → magazijnStream → voltooiingsPipeline → accumulator-klasse), zodat een review per commit mogelijk is.

### F2. Naamgeving — overige

| File:regel | Huidig | Nieuw | Comment |
|---|---|---|---|
| `BerichtenCache.kt:573` | `deletePoging` | `probeerDelete` of `verwijderPoging` | C30 — "Poging" suggereerde "verwijderd"; mreuvekamp wil duidelijker. **Keuze:** `probeerVerwijderen` (parallel met `probeerUpdate` hierboven). |
| `BerichtenCache.kt:410` | `updatePoging` | `probeerUpdate` | Parallel met F2 hierboven (mreuvekamp-stijl). |
| `BerichtenCache.update` | `update(...)` | `werkBerichtBij(...)` | C8 — "update" is te breed (suggereert inhoud); `werkBerichtBij` is consistent met de service-laag. Service `updateBericht` → `werkBerichtBij`. |
| `BerichtBeheerService` velden | `xOntvanger` | `ontvanger` (parameter), `xOntvangerHeader` in HTTP-laag | C34 — `x` is alleen relevant in HTTP-spec; service-laag werkt met domein-ontvanger. Pas overal aan; let op `splitOntvanger`-call-sites. |
| `BerichtBeheerService.kt:112` | `compensatieInvalidate` | `invalideerCacheNaCompensatie` of `compensatieInvalidatieCache` | C35 — naam onduidelijk. **Keuze:** `invalideerCacheNaMagazijnWrite`. |
| `BerichtenlijstService.kt:37,49,50,51` | `paginatie` | `paginering` | C36 — mreuvekamp: paginering is gangbaarder Nederlands. Globaal in deze file. |

**Aanpak:** één commit per hernoeming; gebruik IDE-refactor om alle call-sites mee te nemen. Compileer + test na elk.

---

## Blok H — Commentaar & spec opschonen

### H1. `BerichtensessiecacheResource.kt:132-146` te uitgebreide comment (C18)

Vervang regels 132-146 door één regel:

```kotlin
// Onbekende status-enum komt als null binnen (Jackson); zonder deze check zou een lege patch een no-op succes lijken.
if (berichtStatusUpdate.status == null && berichtStatusUpdate.map == null) { ... }
```

Verwijder regels 143-146 (commentaar over weggelaten lengte-check) — dode info.

### H2. `Ontvanger.kt` — "voetkanon" + dubbele KDoc (C29, C30)

**File:** `services/berichtenuitvraag/src/main/kotlin/.../uitvraag/Ontvanger.kt`

- Regel 26: vervang `"geen .first/.second-voetkanon"` door `"geen .first/.second-verwarring"`. ("Voetkanon" is jargon dat een toekomstige lezer niet kent.)
- Regels 5-11 + 23-28: condenseer de twee KDoc-blokken die overlap hebben (beide beschrijven dezelfde validatie-bron en hetzelfde #414-doel). Eén KDoc op de file-constant `ONTVANGER_PATTERN`, één korte op `OntvangerHeader`.
- Regels 44-50 (KDoc op `registreerLdvSubject`): inkorten tot 2-3 regels — leg het "wat" uit, laat het uitgebreide "waarom geen return" verhuizen naar een 1-regel comment in de body.

### H3. `BijlageContentTypeFilter.kt:43` herhaling van KDoc (C26-uitvraag)

**File:** regels 38-46.

De KDoc (regels 18-30) zegt al "fail-closed met octet-stream + attachment". De body-comment (regels 39-46) herhaalt dat. Inkorten tot:

```kotlin
val effectief = parsed ?: MediaType.APPLICATION_OCTET_STREAM_TYPE.also {
    log.warnf("BIJLAGE_MIME_TYPE_PROPERTY ongeldig (%s); fallback naar octet-stream + attachment (fail-closed).", mimeType)
}
```

### H4. `MagazijnRouter.kt:71` "kromme URI" verduidelijken (C27)

**File:** `services/berichtenuitvraag/src/main/kotlin/.../MagazijnRouter.kt:71-74`

Vervang "kromme URI" door "ongeldige URI". Pas dezelfde term aan in regel 28 ("kromme `magazijnen.urls`" → "ongeldige `magazijnen.urls`").

### H5. Spec-tekst: geen sessiecache-internals (C32, C33)

**File:** `services/berichtenuitvraag/src/main/resources/openapi/berichtenuitvraag-api.yaml`

- Regel 123 (`getBerichtById.description`): vervang "via de sessiecache" door "volledig bericht inclusief inhoud en bijlage-metadata".
- Regels 145-154 (PATCH-description): vervang door:
  ```
  Werk status (`gelezen`/`ongelezen`) en/of `map` van een bericht bij. Idempotent.
  ```
  Verwijder verwijzingen naar dual-write, sessiecache, TTL en `Idempotency-Key`-rationale; dat zijn implementatiedetails.
- Pas dezelfde refactor toe op de DELETE-description (regel 179+).

### H6. `BerichtenCache.kt` overige Nederlands (C14)

Zie A3 hierboven — al gedekt.

---

## Blok I — Files opruimen

### I1. `.gitkeep`-files verwijderen (C3, C4)

```bash
git rm services/berichtenuitvraag/src/main/kotlin/nl/rijksoverheid/moz/fbs/berichtenuitvraag/uitvraag/.gitkeep
git rm services/berichtenuitvraag/src/test/kotlin/nl/rijksoverheid/moz/fbs/berichtenuitvraag/uitvraag/.gitkeep
```

Beide directories bevatten al Kotlin-bestanden; `.gitkeep` is dood gewicht.

### I2. Unused import in `BerichtBeheerService.kt:8` (C37)

**File:** `services/berichtenuitvraag/src/main/kotlin/.../BerichtBeheerService.kt:8`

Regel 8: `import jakarta.ws.rs.core.Response` — verwijder als IDE bevestigt dat er geen call-site is. (`Response`-type komt nu alleen voor in de comments.)

---

## Blok J — Documentatie

### J1. Bruno-script voor magazijn-`GET /berichten` (C2)

**Files:**
- Maak: `bruno/berichtenmagazijn/berichten/get-berichten.bru` (of vergelijkbaar pad — sluit aan bij bestaande structuur in `bruno/berichtenmagazijn/`).
- Wijzig (zo nodig): `bruno/berichtenmagazijn/bruno.json` (collectie-metadata).

**Stappen:**

1. Kijk in `bruno/berichtenmagazijn/` voor bestaande `.bru`-stijl (auth-header, env-variable voor base-URL).
2. Voeg request toe voor `GET /api/v1/berichten?pagina=0&paginaGrootte=20` met `X-Ontvanger: BSN:{{testBsn}}`.

### J2. README — sectie over Bruno (C5)

**File:** `README.md` (rond regel 62, sectie "Lokaal draaien" of "Testen").

Voeg sectie toe:

```markdown
### API-requests handmatig uitvoeren (Bruno)

De `bruno/`-folder bevat per service een collectie van voorbeeld-requests die je
tegen de lokale dev-mode kunt uitvoeren met [Bruno](https://www.usebruno.com/).

- `bruno/berichtensessiecache/` — sessiecache (lijst, zoek, ophalen-SSE, detail, PATCH/DELETE)
- `bruno/berichtenmagazijn/` — aanlever- en beheer-API
- `bruno/berichtenuitvraag/` — frontend-facade

Open in Bruno, kies environment `lokaal` en run requests. De collectie spiegelt de
OpenAPI-spec — nieuwe endpoints in de spec krijgen direct een `.bru`-request.
```

---

## Ontwerpkeuzes

1. **TTL 24u i.p.v. 1u of "session-bound":** session-bound TTL vereist client-driven invalidatie of een session-store-koppeling — dat is een groter ontwerp. 24u sluit aan bij portaal-gebruikspatronen ("inloggen 's ochtends, weer terugkomen 's avonds") en is genoeg om de reviewer-frustratie weg te nemen zonder de geheugendruk significant te verhogen (een sessie houdt enkele MB's vast; 24u × N actieve gebruikers blijft beheersbaar zolang we het in observability volgen).
2. **Update-/delete-afhankelijkheid van cache (C24) niet in deze PR aangepakt:** de auteur wil eerst met de reviewer afstemmen of de TTL-verhoging volstaat. Mogelijke vervolgopties (apart issue): fan-out van PATCH/DELETE naar alle magazijnen bij cache-miss, óf `magazijnId` als expliciete parameter in de spec accepteren zodat de cache geen routerings-rol meer heeft.
3. **Comment-blok E ("inhoud altijd leeg"):** als de magazijn-spec `inhoud` als required markeert (verificatie in E1), dan was de default `""` defensieve overhead op een onmogelijke conditie — weghalen volgt YAGNI uit CLAUDE.md ("Don't add error handling, fallbacks, or validation for scenarios that can't happen").
4. **Constanten configureerbaar (Blok C):** geen volledige refactor naar `BerichtFactory` in deze PR — pragmatische tussenstap met `ConfigMapping` + KDoc-aanwijzing. Volledige factory-refactor in vervolg-issue indien productie-overrides nodig blijken.
5. **Bewust niet aangepakt in deze PR:**
   - **Blok B** (berichtenuitvraag als module) — apart issue ná merge.
   - **C24** (PATCH/DELETE losweken van cache) — eerst overleg met reviewer of TTL-verhoging volstaat.
   - **E2** (bijlage-`mimeType`/`grootte`) — afhankelijk van magazijn-spec-uitbreiding; als die ontbreekt, alleen KDoc-comment aanpassen.
   - **E3** (`gewijzigdOp`) — afhankelijk van expliciete bevestiging frontend-behoefte; bij twijfel: apart issue na user-feedback.

## Verificatie

Per blok lokaal:

```bash
./mvnw clean test -pl services/berichtensessiecache       # A, C1, D, F, H1, H6
./mvnw clean test -pl services/berichtenuitvraag          # C2, G (xOntvanger), H2-H5, I2
./mvnw clean test -pl services/berichtenmagazijn          # E1 (als magazijn-spec wijzigt)
./mvnw clean verify -pl services/berichtenuitvraag -am    # Volledige suite incl. JaCoCo
npx @stoplight/spectral-cli lint services/berichtenuitvraag/src/main/resources/openapi/berichtenuitvraag-api.yaml \
  --ruleset https://static.developer.overheid.nl/adr/ruleset.yaml   # H5
```

Acceptatiecriteria per blok:

- **A:** bestaande TTL-tests gebruiken een test-profile met korte TTL (niet `PT24H`); KDoc/comments noemen geen absolute seconden meer.
- **C, D, G, H, I:** geen test-regressies; coverage ≥90% (was: ja vóór deze PR).
- **E1:** `MagazijnBerichtTest` faalt bij ontbrekende `inhoud` in fixture; bestaande happy-path-fixtures aangepast.
- **F:** `BerichtensessiecacheServiceQuarkusTest.haalBerichtenOp_*` (5 bestaande scenario's) groen na elke commit van de opdeling.
- **J1, J2:** Bruno-collectie laadt zonder errors; README rendert correct in GitHub-preview.

Globale verificatie vóór PR-review-iteratie:

```bash
./mvnw clean verify                                       # alle modules + JaCoCo ≥90%
git diff --stat main..HEAD                                # impact overzien
gh pr view 65 --json reviews                              # bevestig dat eerdere comments resolved zijn
```

## Wijzigingen tijdens uitvoering

De volgende afwijkingen op het oorspronkelijke plan zijn na overleg of voortschrijdend
inzicht doorgevoerd:

| Onderwerp | Plan | Uitgevoerd | Reden |
|---|---|---|---|
| TTL-waarde | `PT24H` | `PT12H` (later bijgesteld) | Halve werkdag is voldoende voor portaal-gebruik; minder geheugendruk |
| C1 — `MAX_MAPNAAM_LENGTE` | Configureerbaar via `BerichtLimieten` | Constant `Bericht.MAX_MAPNAAM_LENGTE = 128` (was 64) + Flyway `V7` op `bericht_status.map` | Magazijn-DB-kolom is bron van waarheid; configureerbaar maken zou divergentie met de DB toestaan. Ook hernoemd "map" → "mapnaam" (semantisch correcter). Naamgeving van overige limieten geharmoniseerd naar `MAX_*`-prefix-stijl (consistent met magazijn + common). |
| C8 — naamgeving cache-update | `werkBerichtBij` | `updateBerichtMetadata` (cache + service + spec operationId in 2 modules) | "update" was nog te generiek (suggereert ook inhoud-mutatie); metadata is precies wat de operatie raakt. |
| A2 — delete-pad bij grote TTL | Comments herzien (gedrag ongewijzigd) | **Delete-pad herontworpen met LREM** in plaats van WATCH+retry-loop | Bij 12u TTL is "self-healing via TTL" geen acceptabele fallback meer; LREM is per-call atomair en raakt alleen exact-matchende values, dus geen retry-loop nodig en geen lost-update-risico met concurrent `addBericht`. `DeletePlan`/`probeerVerwijderen`/`MAX_DELETE_POGINGEN` verwijderd. |
| C24 — PATCH/DELETE losweken van cache | Uitgesteld voor overleg | **Opgelost via `magazijnId`-query in spec** | `BerichtSamenvatting` en `Bericht` hadden `magazijnId` al als required output; verplichte query-param op PATCH/DELETE laat de client de routing-info teruggeven en elimineert de cache-lookup vóór de write. `BerichtBeheerService.resolveMagazijn` weg; gedrag is nu: magazijn eerst (bron van waarheid), cache daarna best-effort. |
| C1 — `BerichtLimieten` afdwingen | Alleen in `addBericht` (POST) | Ook in magazijn-aggregatie (`magazijnStream` via `valideerOrLogAndDrop`) | De hoofdroute waar berichten in de cache komen is `haalBerichtenOp`, niet `POST /berichten`. Validator zonder magazijn-pad zou de config-grenzen alleen op een zelden-gebruikte route afdwingen. |

## Bewust uitgesteld (vervolg-issues)

| Item | Reden | Aanmaken issue |
|---|---|---|
| Blok B — uitvraag als module/package | Reviewer-voorstel voor aparte PR | Ja, na merge van deze PR |
| E2 — bijlage `mimeType`/`grootte` als magazijn-spec dat niet levert | Vereist eerst magazijn-spec-uitbreiding | Voorwaardelijk |
| E3 — `gewijzigdOp` als frontend-behoefte niet bevestigd | Vermijd cache-payload-vergroting zonder duidelijke use-case | Voorwaardelijk |
| Volledige `BerichtFactory`-refactor (C1) | `BerichtLimieten` + `BerichtValidator`-aanpak volstaat voor PoC | Pas bij eerste productie-override-behoefte |
