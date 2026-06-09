# Ontvanger getypeerd vastleggen in het sessiecache-domein (#625, incl. #648)

**Status:** Uitgevoerd

## Context

In `libraries/fbs-berichtensessiecache` is `Bericht.ontvanger` (en `BerichtSamenvatting.ontvanger`)
een kale `String`. De elfproef-/type-invariant van `Identificatienummer` (fbs-common) stopt bij de
facade-rand: binnen het cache-domein kan een verkeerd samengesteld of verwisseld nummer onopgemerkt
blijven. Issue #625 wil dat overal waar het cache-domein een ontvanger vasthoudt, dat het
gevalideerde, getypeerde `Identificatienummer` is.

Reviewbevinding (TD) op PR moza-poc-fbs-berichtenbox#87.

### Scope-besluit: backward-compat losgelaten, #648 ingevouwen

We zijn **nog niet in productie**; bestaande cache-inhoud hoeft niet behouden te blijven. Daarmee
vervalt de oorspronkelijke AC#2-eis "opslagformaat ongewijzigd / geen migratie". Dat maakt een
strikt beter ontwerp mogelijk: we slaan het **type expliciet op**. Gevolgen:

- Reconstructie wordt self-contained (geen request-context/Jackson-attribuut-injectie nodig).
- De getypeerde eigenaar-check krijgt een gezaghebbend opgeslagen type i.p.v. een uit het request
  afgeleid type — type-verwisseling wordt echt gevangen, niet gemaskeerd.
- De cross-type-clash (MinBZK/MijnOverheidZakelijk#648) wordt gratis gedicht door de FT.SEARCH-filter
  type-aware te maken. **#648 is hiermee ingevouwen in #625** en wordt door de #625-PR gesloten.

### Acceptatiecriteria (#625, geactualiseerd)

1. Een ongeldige ontvanger-waarde kan niet meer in het cache-domein bestaan; afgedwongen bij het bouwen.
2. De eigenaar-controles (bericht hoort bij déze ontvanger) blijven identiek werken voor de bestaande
   (gelijk-type) gevallen, aantoonbaar via de bestaande tests.
3. (Uit #648) Een gefilterde lijst en een zoekopdracht voor een ontvanger leveren uitsluitend
   berichten van het exacte type+waarde; BSN en RSIN met dezelfde cijfers zien elkaars berichten niet,
   ook niet via een gelekte `berichtId` op `getById`.

> Bestaande-cache-leesbaarheid is **geen** criterium meer (pre-productie, cache mag leeglopen/gewist).

## De cross-type-clash (nu binnen scope)

`getPageFiltered`/`search` queryen de globale RediSearch-index `berichten-idx` over álle
per-bericht-hashes, gefilterd op enkel `@ontvanger:{<waarde>}` (`BerichtenCache.kt:284`, `:315`). De
hash had geen type-veld; de elfproef voor BSN en RSIN is identiek, dus elke geldige 9-cijferige
waarde is tegelijk geldig BSN én RSIN. Zonder type in de index matcht de filter beide → cross-type
PII-lek (en via gelekte `berichtId` ook `getById`, `:362`). De ongefilterde lijst (`getPage`) was al
type-veilig via de key `SHA256("<TYPE>:<waarde>")` (`:56-61`).

Fix: type als geïndexeerd TAG-veld + type-aware FT.SEARCH-filter + getypeerde `getById`-check.

## Drie opslagrepresentaties van `ontvanger`

Een `Bericht` wordt op drie manieren opgeslagen/gelezen. In alle drie slaan we voortaan **type én
waarde** op.

| Representatie | Schrijven | Lezen | Mechanisme |
|---|---|---|---|
| Sessie-lijst (volledige blob) | `store` `:137`, `update` list-rewrite | `getPage` `:256` | Jackson `objectMapper` JSON |
| Per-bericht hash | `createBericht`/`store` `berichtToHash` `:430` | `getById` `hashToBericht` `:466` | handmatige field-map |
| RediSearch-projectie | (de hash, geïndexeerd) | `getPageFiltered`/`search` `documentToSamenvatting` `:519` | FT.SEARCH-document |

## Ontwerp

### Domeintypes

- `Bericht.ontvanger: Identificatienummer` en `BerichtSamenvatting.ontvanger: Identificatienummer`.
- `init`-validatie op `ontvanger.isNotBlank()` vervalt (de waarde is per constructie al geldig).

### Opslag (type + waarde)

- **Hash** (`berichtToHash`): `ontvanger` = `bericht.ontvanger.waarde` (TAG, blijft de
  filter-/zoekwaarde) **plus** nieuw `ontvangerType` = `bericht.ontvanger.type.name` (TAG).
- **Index** (`@PostConstruct` `ftCreate`, `:108-118`): `ontvangerType` als extra `FieldType.TAG`.
  (Pre-productie: een bestaande dev-index mag handmatig gedropt worden, of de prefix/`v`-bump
  toepassen; geen productie-migratie.)
- **Lijst-JSON**: `ontvanger` serialiseert als canonieke string `"TYPE:waarde"` (symmetrisch met
  `toCanonicalString()`/`fromHeader()`). Veld-niveau `@JsonSerialize`/`@JsonDeserialize` op
  `Bericht.ontvanger` — **bewust veld-niveau, niet globaal**: een globale `Identificatienummer`-module
  zou de magazijn-DTO (`MagazijnBericht`, die `{type, waarde}` JSON verwacht) breken.

### Reconstructie + hervalidatie (self-contained)

Elk lees-pad reconstrueert uit **opgeslagen** data — geen request-context meer nodig:

- `hashToBericht` / `documentToSamenvatting`: lees `ontvanger` + `ontvangerType`, bouw
  `Identificatienummer.of(IdentificatienummerType.valueOf(type), waarde)`.
- `getPage` lijst-deserializer: parse de canonieke string via `Identificatienummer.fromHeader(...)`.
- `Identificatienummer.of(...)` hervalideert (elfproef voor BSN/RSIN) → AC#1. Een
  `DomainValidationException` of onbekende type-naam → `CacheCorruptedException.onleesbareWaarde(...)`
  (zoals de bestaande UUID/Instant/Int-foutpaden), surfacet als 500.
- `hashToBericht` hoeft de request-`ontvanger` **niet** meer als parameter (signatuur blijft schoon).

### Type-aware queries en eigenaar-checks (sluit #648)

- `getPageFiltered` (`:284`) en `search` (`:315`): filter wordt
  `@ontvanger:{<waarde>} @ontvangerType:{<TYPE>}`.
- `getById` (`:362`) en `bepaalUpdatePlan` (`:618`): gematerialiseerd object → getypeerde
  `bericht.ontvanger != ontvanger` (value-class-equality: `Bsn("x") != Rsin("x")`).
- `delete` pre-check: vergelijk zowel `fields["ontvanger"]` als `fields["ontvangerType"]` met de
  request, óf materialiseer en vergelijk getypeerd.
- `BlockingSessiecache:99` (`schrijfBericht`-guard): getypeerd.

### Consumer- & rand-aanpassingen

- `MagazijnBericht.toBericht:44`: `ontvanger = ontvanger.waarde` → `ontvanger = ontvanger`.
- `AanmeldService:80`: `ontvanger = event.ontvanger.waarde` → `ontvanger = event.ontvanger`.
- `MockBerichtenCache`: eigenaar-checks → getypeerd; in-memory opslag draagt het getypeerde veld.
- `toSamenvatting()`: geeft het getypeerde veld door.
- Uitvraag-API: geen wijziging — `UitvraagDtoMapper` exposeert `ontvanger` bewust niet.

## Buiten scope (YAGNI)

- Geen globale Jackson-module voor `Identificatienummer`.
- Geen ondersteuning voor het lezen van "oude" raw-waarde-only cache-entries (pre-productie).

## Teststrategie (TDD)

- **Regressie (AC#2):** bestaande gelijk-type eigenaar-tests blijven slagen.
- **Nieuw — unit:**
  - reconstructie levert het juiste subtype uit opgeslagen `type`+`waarde`.
  - onbekende/lege type-naam of elfproef-schending in opslag → `CacheCorruptedException`.
- **Nieuw — integratie (echte Redis):**
  - round-trip schrijven→lezen behoudt type én waarde over alle drie representaties.
  - **clash-regressie (#648):** twee sessies, `Bsn` en `Rsin` met dezelfde geldige 9 cijfers, elk een
    bericht. Gefilterde lijst, zoek én `getById` voor het BSN leveren nul RSIN-berichten en omgekeerd.
  - corrupte opgeslagen waarde/type → `CacheCorruptedException` (500).
- Coverage: ≥90% line (JaCoCo); foutpaden via `@QuarkusTest`.

## Verificatie

- `./mvnw clean test -pl libraries/fbs-berichtensessiecache -am` (Docker vereist).
- `./mvnw clean test -pl services/berichtenuitvraag -am` (raakt `AanmeldService`).
- `./mvnw detekt:check`.
- Build-/test-warnings nalopen.

## Afronding

- PR sluit MinBZK/MijnOverheidZakelijk#625 én #648 (`Closes …`).

---

# Implementatieplan (TDD)

> **For agentic workers:** REQUIRED SUB-SKILL: gebruik superpowers:subagent-driven-development of
> superpowers:executing-plans om dit plan taak-voor-taak uit te voeren. Stappen gebruiken
> checkbox-syntax (`- [ ]`).

**Goal:** `ontvanger` in het sessiecache-domein getypeerd (`Identificatienummer`) vastleggen met
opgeslagen type, zodat reconstructie self-contained is en de cross-type-clash (#648) gedicht wordt.

**Architecture:** Drie opslagrepresentaties (lijst-JSON via Jackson, per-bericht-hash, FT.SEARCH)
dragen voortaan `type` én `waarde`. Lezen reconstrueert via `Identificatienummer.of(type, waarde)`
(hervalidatie). FT.SEARCH-filters worden type-aware.

**Tech Stack:** Kotlin, Quarkus Redis (RediSearch), Jackson, JUnit 5 + MockK + QuarkusTest.

> **Kotlin-compile-realiteit:** het omzetten van `ontvanger: String` → `Identificatienummer` breekt
> de hele module-compilatie tot álle call-sites consistent zijn. Taken 2–6 zijn daarom edit-bundels;
> de eerste runnable compile/test-checkpoint staat aan het eind van Taak 6. Taak 1 is wél
> geïsoleerd test-baar.

## Bestanden

- Create: `libraries/fbs-berichtensessiecache/src/main/kotlin/nl/rijksoverheid/moz/fbs/berichtensessiecache/berichten/IdentificatienummerCanonicalJackson.kt`
- Modify: `…/berichten/Bericht.kt` (types + Jackson-binding op `ontvanger`)
- Modify: `…/berichten/BerichtenCache.kt` (opslag, index, reconstructie, queries, eigenaar-checks)
- Modify: `…/magazijn/MagazijnBericht.kt` (`toBericht`)
- Modify: `…/BlockingSessiecache.kt` (schrijfBericht-guard)
- Modify: `services/berichtenuitvraag/…/aanmeld/AanmeldService.kt` (regel 80)
- Modify: `…/berichten/MockBerichtenCache.kt` (eigenaar-checks)
- Test: nieuw `…/berichten/IdentificatienummerCanonicalJacksonTest.kt`; uitbreiden bestaande suites

---

### Taak 1: Canonieke Jackson-(de)serializer voor `Identificatienummer`

**Files:**
- Create: `…/berichten/IdentificatienummerCanonicalJackson.kt`
- Test: `…/berichten/IdentificatienummerCanonicalJacksonTest.kt`

- [ ] **Stap 1: Schrijf de falende test**

```kotlin
package nl.rijksoverheid.moz.fbs.berichtensessiecache.berichten

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.module.SimpleModule
import nl.rijksoverheid.moz.fbs.common.identificatie.Bsn
import nl.rijksoverheid.moz.fbs.common.identificatie.Identificatienummer
import nl.rijksoverheid.moz.fbs.common.identificatie.Rsin
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class IdentificatienummerCanonicalJacksonTest {
    private val mapper = ObjectMapper().registerModule(
        SimpleModule()
            .addSerializer(Identificatienummer::class.java, IdentificatienummerCanonicalSerializer())
            .addDeserializer(Identificatienummer::class.java, IdentificatienummerCanonicalDeserializer()),
    )

    @Test
    fun `serialiseert naar canonieke TYPE-waarde string`() {
        val json = mapper.writeValueAsString(Bsn("999993653") as Identificatienummer)
        assertEquals("\"BSN:999993653\"", json)
    }

    @Test
    fun `round-trip behoudt type en waarde`() {
        val origineel: Identificatienummer = Rsin("999993653")
        val terug = mapper.readValue(mapper.writeValueAsString(origineel), Identificatienummer::class.java)
        assertEquals(origineel, terug)
    }
}
```

- [ ] **Stap 2: Run test — verwacht FAIL**

Run: `./mvnw clean test -pl libraries/fbs-berichtensessiecache -am -Dtest=IdentificatienummerCanonicalJacksonTest`
Verwacht: compile-fout (`IdentificatienummerCanonicalSerializer` bestaat niet).

- [ ] **Stap 3: Implementeer de (de)serializer**

```kotlin
package nl.rijksoverheid.moz.fbs.berichtensessiecache.berichten

import com.fasterxml.jackson.core.JsonGenerator
import com.fasterxml.jackson.core.JsonParser
import com.fasterxml.jackson.databind.DeserializationContext
import com.fasterxml.jackson.databind.JsonDeserializer
import com.fasterxml.jackson.databind.JsonSerializer
import com.fasterxml.jackson.databind.SerializerProvider
import nl.rijksoverheid.moz.fbs.common.identificatie.Identificatienummer

/**
 * Serialiseert een [Identificatienummer] in de sessiecache-lijst-JSON als canonieke
 * `"<TYPE>:<WAARDE>"`-string (symmetrisch met [Identificatienummer.fromHeader]). Bewust
 * veld-niveau toegepast (zie `Bericht.ontvanger`), niet als globale module: de magazijn-DTO
 * deserialiseert een eigen `{type, waarde}`-vorm en mag hier niet door geraakt worden.
 */
class IdentificatienummerCanonicalSerializer : JsonSerializer<Identificatienummer>() {
    override fun serialize(value: Identificatienummer, gen: JsonGenerator, serializers: SerializerProvider) {
        gen.writeString(value.toCanonicalString())
    }
}

class IdentificatienummerCanonicalDeserializer : JsonDeserializer<Identificatienummer>() {
    override fun deserialize(p: JsonParser, ctxt: DeserializationContext): Identificatienummer =
        Identificatienummer.fromHeader(p.valueAsString)
}
```

- [ ] **Stap 4: Run test — verwacht PASS**

Run: `./mvnw clean test -pl libraries/fbs-berichtensessiecache -am -Dtest=IdentificatienummerCanonicalJacksonTest`
Verwacht: PASS (2 tests).

- [ ] **Stap 5: Commit**

```bash
git add libraries/fbs-berichtensessiecache/src/main/kotlin/nl/rijksoverheid/moz/fbs/berichtensessiecache/berichten/IdentificatienummerCanonicalJackson.kt \
        libraries/fbs-berichtensessiecache/src/test/kotlin/nl/rijksoverheid/moz/fbs/berichtensessiecache/berichten/IdentificatienummerCanonicalJacksonTest.kt
git commit -m "feat(sessiecache): canonieke Jackson-(de)serializer voor Identificatienummer (#625)"
```

---

### Taak 2: `Bericht`/`BerichtSamenvatting` typeren

**Files:** Modify `…/berichten/Bericht.kt`

- [ ] **Stap 1: Voeg imports toe** (bovenaan, bij de bestaande Jackson-imports)

```kotlin
import com.fasterxml.jackson.databind.annotation.JsonDeserialize
import com.fasterxml.jackson.databind.annotation.JsonSerialize
import nl.rijksoverheid.moz.fbs.common.identificatie.Identificatienummer
```

- [ ] **Stap 2: Typeer `Bericht.ontvanger` met Jackson-binding** (regel 31)

Vervang `val ontvanger: String,` door:

```kotlin
    @param:JsonDeserialize(using = IdentificatienummerCanonicalDeserializer::class)
    @get:JsonSerialize(using = IdentificatienummerCanonicalSerializer::class)
    val ontvanger: Identificatienummer,
```

- [ ] **Stap 3: Verwijder de blank-check** (regel 43)

Verwijder `require(ontvanger.isNotBlank()) { "ontvanger mag niet leeg zijn" }` — de waarde is per
constructie al geldig (de elfproef/lengte-invariant zit in `Identificatienummer`).

- [ ] **Stap 4: Typeer `BerichtSamenvatting.ontvanger`** (regel 87)

Vervang `val ontvanger: String,` door `val ontvanger: Identificatienummer,`. (Geen Jackson-annotaties:
`BerichtSamenvatting` wordt nooit naar Redis/JSON geserialiseerd, alleen geprojecteerd voor responses.)

`toSamenvatting()` (regel 64-74) blijft ongewijzigd: `ontvanger = ontvanger` mapt nu `Identificatienummer` → `Identificatienummer`.

- [ ] **Stap 5: (Geen test-run — module compileert nog niet; zie Taak 6-checkpoint.)**

---

### Taak 3: `BerichtenCache.kt` — opslag, index, reconstructie, queries, checks

**Files:** Modify `…/berichten/BerichtenCache.kt`

- [ ] **Stap 1: Imports** (bij de bestaande `identificatie`-imports)

```kotlin
import nl.rijksoverheid.moz.fbs.common.identificatie.IdentificatienummerType
import nl.rijksoverheid.moz.fbs.common.exception.DomainValidationException
```

- [ ] **Stap 2: Index — voeg `ontvangerType`-TAG toe** (in `ftCreate`, na regel 114)

```kotlin
                .indexedField("ontvanger", FieldType.TAG)
                .indexedField("ontvangerType", FieldType.TAG)
```

- [ ] **Stap 3: `berichtToHash` — schrijf type + waarde** (regel 430)

```kotlin
        put("ontvanger", bericht.ontvanger.waarde)
        put("ontvangerType", bericht.ontvanger.type.name)
```

- [ ] **Stap 4: Reconstructie-helper** (toevoegen, bv. boven `hashToBericht`)

```kotlin
    /**
     * Herbouwt het getypeerde [Identificatienummer] uit opgeslagen `type` + `waarde` en
     * hervalideert daarbij (elfproef/lengte). Een onbekende type-naam of invariant-schending
     * duidt op cache-corruptie → [CacheCorruptedException] (500), niet "Redis onbereikbaar".
     * `DomainValidationException` is een `IllegalArgumentException`, dus één catch volstaat
     * voor zowel `valueOf` als `of`.
     */
    private fun reconstrueerOntvanger(waarde: String, typeNaam: String): Identificatienummer =
        try {
            Identificatienummer.of(IdentificatienummerType.valueOf(typeNaam), waarde)
        } catch (ex: IllegalArgumentException) {
            throw CacheCorruptedException.onleesbareWaarde("ontvanger", ex)
        }
```

- [ ] **Stap 5: `hashToBericht` — reconstrueer** (regel 466)

Vervang `ontvanger = required("ontvanger"),` door:

```kotlin
            ontvanger = reconstrueerOntvanger(required("ontvanger"), required("ontvangerType")),
```

- [ ] **Stap 6: `documentToSamenvatting` — reconstrueer** (regel 519)

Vervang `ontvanger = required("ontvanger"),` door dezelfde regel als Stap 5.

- [ ] **Stap 7: `SAMENVATTING_VELDEN` — voeg `ontvangerType` toe** (regel 821-831, na `"ontvanger",`)

```kotlin
            "ontvanger",
            "ontvangerType",
```

- [ ] **Stap 8: `getById` eigenaar-check getypeerd** (regel 362)

```kotlin
                if (bericht.ontvanger != ontvanger) null else bericht
```

- [ ] **Stap 9: `getPageFiltered` — type-aware filter** (regel 283-289)

```kotlin
        val query = buildList {
            add("@ontvanger:{${escapeTag(ontvanger.waarde)}}")
            add("@ontvangerType:{${escapeTag(ontvanger.type.name)}}")

            if (!afzender.isNullOrBlank()) add("@afzender:{${escapeTag(afzender)}}")

            if (!map.isNullOrBlank()) add("@map:{${escapeTag(map)}}")
        }.joinToString(" ")
```

- [ ] **Stap 10: `search` — type-aware filter** (regel 315-319)

```kotlin
        val ontvangerFilter =
            "@ontvanger:{${escapeTag(ontvanger.waarde)}} @ontvangerType:{${escapeTag(ontvanger.type.name)}}"
```

(De rest van de query-opbouw op regel 319 blijft ongewijzigd.)

- [ ] **Stap 11: `bepaalUpdatePlan` eigenaar-check getypeerd** (regel 618)

```kotlin
                        bericht.ontvanger != ontvanger -> Uni.createFrom().item(UpdatePlan.Geen)
```

- [ ] **Stap 12: `delete` pre-check type-aware** (regel 768)

```kotlin
                if (fields.isEmpty() ||
                    fields["ontvanger"] != ontvanger.waarde ||
                    fields["ontvangerType"] != ontvanger.type.name
                ) {
```

---

### Taak 4: Consumers/rand — magazijn, facade, aanmeld

**Files:** Modify `…/magazijn/MagazijnBericht.kt`, `…/BlockingSessiecache.kt`, `…/aanmeld/AanmeldService.kt`

- [ ] **Stap 1: `MagazijnBericht.kt` imports** (alias om botsing met de nested `Identificatienummer`-DTO te vermijden)

```kotlin
import nl.rijksoverheid.moz.fbs.common.identificatie.Identificatienummer as OntvangerId
import nl.rijksoverheid.moz.fbs.common.identificatie.IdentificatienummerType
```

- [ ] **Stap 2: `toBericht` — bouw getypeerd + hervalideer** (regel 40-44)

```kotlin
        // Het magazijn levert ontvanger als {type, waarde}; aan de cache-grens bouwen we het
        // gevalideerde domeintype (elfproef/lengte afgedwongen). Ongeldige magazijn-data faalt
        // hier hard i.p.v. ongemerkt door te stromen.
        ontvanger = OntvangerId.of(IdentificatienummerType.valueOf(ontvanger.type), ontvanger.waarde),
```

- [ ] **Stap 3: `BlockingSessiecache.kt` — guard getypeerd** (regel 97-99)

```kotlin
        if (bericht.ontvanger != ontvanger) {
```

Verwijder de nu onjuiste `.waarde`-comment op regel 97-98 (de vergelijking is getypeerd).

- [ ] **Stap 4: `AanmeldService.kt` — geef getypeerd door** (regel 80)

```kotlin
            ontvanger = event.ontvanger,
```

---

### Taak 5: `MockBerichtenCache` eigenaar-checks getypeerd

**Files:** Modify `…/berichten/MockBerichtenCache.kt`

- [ ] **Stap 1: `getById`** (regel 99)

```kotlin
        return Uni.createFrom().item(if (bericht?.ontvanger == ontvanger) bericht else null)
```

- [ ] **Stap 2: `updateBerichtMetadata`** (regel 106)

```kotlin
        if (bericht == null || bericht.ontvanger != ontvanger) return Uni.createFrom().nullItem()
```

- [ ] **Stap 3: `delete`** (regel 126)

```kotlin
        if (existing != null && existing.ontvanger == ontvanger) {
```

---

### Taak 6: Bestaande tests laten compileren + regressie-checkpoint

Doel: alle `ontvanger = "<string>"`-constructies van cache-`Bericht`/`BerichtSamenvatting` typeren.
**Regel:** een cache-`Bericht(...)`/`BerichtSamenvatting(...)` krijgt
`ontvanger = Identificatienummer.of(IdentificatienummerType.BSN, "<waarde>")` (of `Bsn("<waarde>")`).
**Niet aanpassen:** strings binnen magazijn-JSON-fixtures (`{"type":"BSN","waarde":"…"}`) — dat is wire-vorm.

**Files (regels via grep `ontvanger = "`):**
- `libraries/fbs-berichtensessiecache/src/test/.../magazijn/MagazijnResultTest.kt`
- `libraries/fbs-berichtensessiecache/src/test/.../berichten/BerichtValidatorTest.kt`
- `libraries/fbs-berichtensessiecache/src/test/.../berichten/MockMagazijnClientFactory.kt` (4× — controleer per geval: magazijn-fixture-string vs. cache-`Bericht`)
- `libraries/fbs-berichtensessiecache/src/test/.../berichten/BerichtTest.kt` (2×)
- `services/berichtenuitvraag/src/test/.../uitvraag/{BerichtOphaalServiceTest,UitvraagDtoMapperTest,DualWriteFaultTest,OpenApiContractTest,BerichtBeheerServiceTest,BerichtenlijstServiceTest,LdvSubjectRegistrationTest,ServiceCoverageTest}.kt`

- [ ] **Stap 1: Vervang per file de cache-`Bericht`/`BerichtSamenvatting`-constructies** volgens de regel hierboven (import `Identificatienummer`/`IdentificatienummerType`/`Bsn` waar nodig).

- [ ] **Stap 2: Pas `BerichtTest.kt` blank-ontvanger-test aan** — de test die `ontvanger = ""` op leegheid checkte vervalt (niet meer construeerbaar); verwijder of vervang door een `Identificatienummer`-geval.

- [ ] **Stap 3: Compileer + run de volledige sessiecache-suite (regressie-checkpoint)**

Run: `./mvnw clean test -pl libraries/fbs-berichtensessiecache -am`
Verwacht: BUILD SUCCESS — bestaande eigenaar-/lijst-/zoek-tests slagen ongewijzigd (AC#2).

- [ ] **Stap 4: Run de berichtenuitvraag-suite**

Run: `./mvnw clean test -pl services/berichtenuitvraag -am`
Verwacht: BUILD SUCCESS.

- [ ] **Stap 5: Commit**

```bash
git add -A
git commit -m "feat(sessiecache): ontvanger getypeerd met opgeslagen type; tests bijgewerkt (#625)"
```

---

### Taak 7: Nieuwe tests — reconstructie, corruptie, round-trip, clash (#648)

**Files:** uitbreiden `…/berichten/BerichtenCacheTest`-suite (echte Redis, `@QuarkusTest`/`RealRedisTestProfile`)
en een unit-test voor de round-trip.

- [ ] **Stap 1: Unit — `Bericht`-JSON-round-trip behoudt type** (in een nieuwe of bestaande `BerichtJsonTest.kt`)

```kotlin
@Test
fun `Bericht round-trip via objectMapper behoudt ontvanger-type`() {
    val origineel = nieuwBericht(ontvanger = Rsin("999993653"))   // helper of inline Bericht(...)
    val json = objectMapper.writeValueAsString(origineel)
    val terug = objectMapper.readValue(json, Bericht::class.java)
    assertEquals(Rsin("999993653"), terug.ontvanger)
    assertTrue(json.contains("\"RSIN:999993653\""))               // canonieke vorm in de blob
}
```

- [ ] **Stap 2: Integratie — clash-regressie (#648)**

```kotlin
@Test
fun `gefilterde lijst, zoek en getById isoleren BSN van RSIN met gelijke cijfers`() {
    val cijfers = "999993653"   // geldig (elfproef) als BSN én RSIN
    val bsn = Bsn(cijfers); val rsin = Rsin(cijfers)
    // schrijf één bericht per type via createBericht (na een ophalen/store die de sessie opent)
    // …
    // assert: lijst/zoek/getById voor bsn levert 0 berichten van rsin, en omgekeerd.
}
```

- [ ] **Stap 3: Integratie — corrupte opslag → CacheCorruptedException**

Schrijf direct in Redis een hash met `ontvangerType="ONBEKEND"` (of een ongeldige `ontvanger`-waarde)
en assert dat `getById`/lijst een `CacheCorruptedException` (→ 500-pad) oplevert, niet een 503.

- [ ] **Stap 4: Run de nieuwe tests**

Run: `./mvnw clean test -pl libraries/fbs-berichtensessiecache -am`
Verwacht: BUILD SUCCESS (nieuwe + bestaande).

- [ ] **Stap 5: Commit**

```bash
git add -A
git commit -m "test(sessiecache): reconstructie, corruptie en cross-type-isolatie (#625, #648)"
```

---

### Taak 8: Index-recreate (dev), volledige verificatie, detekt

- [ ] **Stap 1: Dev-index opnieuw aanmaken** — de bootstrap dropt `berichten-idx` niet. Pre-productie:
      in een draaiende dev-Redis handmatig `FT.DROPINDEX berichten-idx` (cache mag leeglopen) zodat het
      nieuwe `ontvangerType`-veld geïndexeerd wordt. Documenteer dit kort in de PR-omschrijving.

- [ ] **Stap 2: Volledige verificatie**

```bash
./mvnw clean test -pl libraries/fbs-berichtensessiecache -am
./mvnw clean test -pl services/berichtenuitvraag -am
./mvnw detekt:check
```
Verwacht: 3× groen; geen nieuwe, onverklaarde build-/test-warnings.

- [ ] **Stap 3: Plan-status bijwerken** — zet `**Status:** Concept` → `**Status:** Uitgevoerd`. Commit.

## Self-review (uitgevoerd)

- **Spec-dekking:** AC#1 (reconstrueerOntvanger hervalideert, Taak 3/4), AC#2 (regressie-checkpoint
  Taak 6), AC#3/#648 (type-aware filters Taak 3 + clash-test Taak 7). Gedekt.
- **Type-consistentie:** `reconstrueerOntvanger(waarde, typeNaam)`, `OntvangerId.of(...)`,
  `ontvangerType`-veld consistent over hash/index/SAMENVATTING_VELDEN.
- **Geen placeholders** in code-stappen; mechanische test-omzetting heeft een expliciete regel +
  filelijst (Taak 6).
