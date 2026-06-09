# Ontvanger getypeerd vastleggen in het sessiecache-domein (#625, incl. #648)

**Status:** Concept

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
