**Status:** Concept
**Datum:** 2026-04-22
**Module:** `services/berichtenmagazijn`

# Toekomstbestendige typering `Identificatienummer`

## Context

`Identificatienummer.parse(...)` (`services/berichtenmagazijn/src/main/kotlin/nl/rijksoverheid/moz/berichtenmagazijn/opslag/Identificatienummer.kt`) leidt het identifier-type puur af van de **lengte** van de string (8 → KVK, 9 → BSN, 20 → OIN). Dat is kwetsbaar:

- **RSIN** (9 cijfers, elfproef-gevalideerd) wordt nu silently als `Bsn` geclassificeerd en passeert zelfs de elfproef. Elke ontvanger die in werkelijkheid een rechtspersoon met RSIN is, komt vervuild in de opslag.
- **Vestigingsnummer** (NHR, 12 cijfers) past nu nergens en zou handmatige uitbreiding vergen zodra het nodig is.
- **KVK-nummer** is vandaag 8 cijfers, maar lengte-uitbreiding is in de sector een realistisch scenario — dan botst het met BSN of vestigingsnummer.
- **Pseudo-ID / eIDAS-eID / BSNk** (opaque strings) passen structureel niet in een length-based schema.
- **Leading-zero-verlies** (Excel, JSON-als-Long, legacy-clients): een 9-cijferige BSN met leidende `0` wordt een 8-cijferig "KVK" en passeert zonder alarm.
- De client heeft geen manier om **intentie** te signaleren; ambiguïteit wordt altijd stilzwijgend door de server opgelost.

Oplossing: expliciet type-tagging, conform het idioom van OIDC/SAML-claims en de NL API Design Rules (type+waarde-object in JSON).

De applicatie wordt nog niet in productie gebruikt — geen clients, geen persistente data. Dat betekent:
- De wijziging kan in één keer doorgevoerd worden; geen deprecated-transitiepad nodig.
- Geen CHANGELOG-communicatie of ADR nodig (wordt later geschreven als er breaking changes voor bestaande gebruikers ontstaan).

## Samenvatting bevindingen

| # | Ernst | Onderwerp | Locatie |
|---|-------|-----------|---------|
| 1 | **Hoog** | RSIN (9 cijfers) wordt als BSN opgeslagen — inhoudelijk foute categorisatie | `Identificatienummer.kt:23-33` |
| 2 | Medium | Length-based `parse` is niet uitbreidbaar naar nieuwe typen (RSIN, vestigingsnummer, pseudo-ID, eIDAS) | idem |
| 3 | Medium | API-contract dwingt client niet tot expliciete typekeuze; intentie gaat verloren | `openapi/berichtenmagazijn-api.yaml:72-82` |
| 4 | Laag | DB-kolom `ontvanger` bewaart alleen de waarde, niet het type — bij hydratatie opnieuw geraden via `parse` | `BerichtEntity.kt:36-37, 52` |
| 5 | Laag | Leading-zero-verlies in transport kan type silently doen flippen (BSN → KVK) | `Identificatienummer.kt:24-28` |

## Scope

**In scope voor deze PR-iteratie:** 1, 2, 3, 4, 5 — in één samenhangende wijziging, omdat de API-contractaanpassing (#3) en de DB-kolom (#4) pas zin hebben in combinatie met de domein-wijziging (#1, #2).

**Buiten scope:**
- Toevoegen van `PseudoId`/`EidasId`/andere EU-varianten — pas nodig zodra FBS eIDAS integreert.

## Voorgestelde vorm

### API-contract (bron van waarheid: OpenAPI)

```yaml
Identificatienummer:
  type: object
  required: [type, waarde]
  properties:
    type:
      type: string
      enum: [BSN, RSIN, KVK, OIN]
      description: Type identificatienummer
    waarde:
      type: string
      # per-type regex wordt server-side afgedwongen; hier een ruim sjabloon
      pattern: '^[0-9A-Za-z\-_.]{1,64}$'
      minLength: 1
      maxLength: 64
  example:
    type: BSN
    waarde: "123456782"
```

`AanleverBerichtRequest.afzender` blijft een `string` met OIN-pattern (afzender is per FBS-spec altijd OIN; zie doc-comment in `Bericht.kt:11`). `AanleverBerichtRequest.ontvanger` wordt `$ref: '#/components/schemas/Identificatienummer'`.

### Domein

`Identificatienummer` blijft een `sealed interface`. `parse(String)` wordt **vervangen** door `of(type, waarde)` — de oude signature verdwijnt volledig. Extra variant `Rsin` toevoegen.

### Persistentie

`berichten.ontvanger` wordt gesplitst in `ontvanger_type` (VARCHAR, enum-string) en `ontvanger_waarde` (VARCHAR). Zo wordt hydratatie deterministisch: geen gissen op lengte bij lezen.

---

## Stappen

### Stap 1 — API-schema aanpassen (bevinding 3)

**Bestand:** `services/berichtenmagazijn/src/main/resources/openapi/berichtenmagazijn-api.yaml`

1. Voeg component `Identificatienummer` toe zoals hierboven.
2. Wijzig `AanleverBerichtRequest.ontvanger` van `string` naar `$ref: '#/components/schemas/Identificatienummer'`.
3. Wijzig `BerichtResponse.ontvanger` navenant.
4. `afzender` blijft ongewijzigd (`string` met OIN-pattern).
5. Documenteer in de `description` dat onbekende `type`-waarden met 400 worden afgewezen (geen stilzwijgende fallback).

### Stap 2 — Domein uitbreiden (bevindingen 1, 2, 5)

**Bestand:** `services/berichtenmagazijn/src/main/kotlin/nl/rijksoverheid/moz/berichtenmagazijn/opslag/Identificatienummer.kt`

1. Voeg enum `IdentificatienummerType { BSN, RSIN, KVK, OIN }` toe.
2. Voeg value class `Rsin` toe — 9 cijfers, elfproef gedeeld met BSN. Hef de elfproef-helper op companion-level naar de `Identificatienummer`-companion (of file-scoped `private fun`), zodat `Bsn` en `Rsin` er samen gebruik van maken zonder copy-paste.
3. Vervang `companion object.parse(String)` door:
   ```kotlin
   fun of(type: IdentificatienummerType, waarde: String): Identificatienummer = when (type) {
       IdentificatienummerType.BSN  -> Bsn(waarde.trim())
       IdentificatienummerType.RSIN -> Rsin(waarde.trim())
       IdentificatienummerType.KVK  -> Kvk(waarde.trim())
       IdentificatienummerType.OIN  -> Oin(waarde.trim())
   }
   ```
4. `parse(String)` wordt **verwijderd** — alle aanroepers (service, entity, tests) migreren direct naar `of(...)` in deze PR.
5. Elke value-class krijgt een `val type: IdentificatienummerType`-property (via `when` op de sealed interface of als directe property per class), zodat `fromDomain`-mapping naar de DB simpel is.

**Tests:** `IdentificatienummerTest.kt` herschrijven met:
- `of(BSN, "...")` happy + elfproef-fail.
- `of(RSIN, "...")` — expliciete 9-cijferige RSIN die elfproef-valide is, check dat het resultaat een `Rsin` is (niet `Bsn`).
- `of(KVK, "...")` / `of(OIN, "...")` happy + length-mismatch.
- Round-trip: `of(type, waarde)` → resultaat heeft altijd het gegeven type, ongeacht ambiguïteit.

### Stap 3 — Database-schema splitsen (bevinding 4)

**Bestand:** `services/berichtenmagazijn/src/main/kotlin/nl/rijksoverheid/moz/berichtenmagazijn/opslag/BerichtEntity.kt`

1. Vervang `var ontvanger: String` door:
   ```kotlin
   @Column(name = "ontvanger_type", nullable = false, length = 32)
   @Enumerated(EnumType.STRING)
   var ontvangerType: IdentificatienummerType = IdentificatienummerType.OIN

   @Column(name = "ontvanger_waarde", nullable = false, length = 64)
   var ontvangerWaarde: String = ""
   ```
2. `fromDomain` vult `ontvangerType` uit het concrete subtype.
3. `toDomain` roept `Identificatienummer.of(ontvangerType, ontvangerWaarde)` aan.
4. `afzender` blijft `String` van lengte 20 (altijd OIN).
5. H2 draait `drop-and-create` in %dev/%test, dus geen migratiescript nodig. %prod is nog niet geconfigureerd — niets te migreren.

**Tests:** `BerichtRepositoryIntegrationTest.kt` aanpassen: voeg een RSIN-rij in en verifieer dat hydratatie een `Rsin`-instance teruggeeft (niet `Bsn`).

### Stap 4 — Service + resource aanpassen (bevinding 3)

**Bestanden:**
- `services/berichtenmagazijn/src/main/kotlin/nl/rijksoverheid/moz/berichtenmagazijn/aanlever/BerichtOpslagService.kt`
- `services/berichtenmagazijn/src/main/kotlin/nl/rijksoverheid/moz/berichtenmagazijn/aanlever/AanleverResource.kt`

1. `BerichtOpslagService.opslaanBericht(...)` — parameter `ontvanger: String` wordt `ontvangerType: IdentificatienummerType, ontvangerWaarde: String`. Intern `Identificatienummer.of(...)`.
2. `AanleverResource` — mapt het gegenereerde `Identificatienummer`-DTO (uit OpenAPI) naar het enum-paar.
3. `afzender` blijft een string, `Oin(afzender)` direct.

### Stap 5 — Sessiecache alignen

**Bestanden:** `services/berichtensessiecache/src/main/resources/openapi/berichtensessiecache-api.yaml` + consumers + magazijn-client.

De sessiecache leest uit magazijnen. Haar eigen `ontvanger`-velden en de magazijn-client moeten het nieuwe `Identificatienummer`-schema adopteren om het contract consistent te houden.

1. Neem hetzelfde `Identificatienummer`-component op in de sessiecache-spec (duplicatie acceptabel; gedeelde spec-module is buiten scope).
2. `MagazijnClient`-mappings aanpassen op het nieuwe type.
3. WireMock-fixtures en integratietests aanpassen.

Gebeurt in dezelfde PR zodat beide API's consistent blijven.

---

## Verificatie

```bash
# Domein + mapping
./mvnw test -pl services/berichtenmagazijn -am
./mvnw test -pl services/berichtensessiecache -am

# End-to-end
docker compose up -d
./mvnw quarkus:dev -pl services/berichtenmagazijn -am
# curl test met type-tagged payload:
#  {"afzender": "00000001...", "ontvanger": {"type": "RSIN", "waarde": "123456782"}, ...}
# → 201; GET terug: ontvanger { type: RSIN, waarde: ... }
```

**OpenApiContractTest** moet de nieuwe schemas valideren; bestaande contracttests op 400/404 ongewijzigd.

## Ontwerpkeuzes

- **`type + waarde`-object boven URN-notatie (`urn:nl:bsn:...`):** URN's zijn elegant maar in JSON minder ergonomisch voor form-validatie; een object laat `oneOf/discriminator` later mogelijk en sluit aan bij hoe OIDC/SAML-claims getypeerd worden.
- **`afzender` blijft een string:** per FBS-spec is afzender per definitie een OIN; object-wrappen voegt daar geen informatie toe. Houden we eenvoudig totdat FBS iets anders voorschrijft.
- **`parse(String)` direct verwijderen** i.p.v. deprecaten: geen bestaande clients of persistente data, dus geen transitiepad nodig — big-bang-refactor is hier goedkoper dan parallelle API's onderhouden.
- **`Rsin` introduceren vóór `Vestigingsnummer`/`PseudoId`:** RSIN is het enige concreet kapotte geval vandaag; andere typen zijn speculatief. We maken de architectuur klaar (`sealed interface` + enum), maar implementeren alleen wat nodig is.
- **DB-kolom `ontvanger_type` als `@Enumerated(EnumType.STRING)`:** leesbaarheid in de database en forward-compatible met nieuwe enum-waarden; ordinale opslag is fragiel bij enum-herordening.

## Uit scope

- `PseudoId`/`EidasId`-ondersteuning (FBS-brede architectuurbeslissing, pas als eIDAS-integratie aan de orde komt).
- Generieke, gedeelde `Identificatienummer`-component in `libraries/fbs-common` of een aparte `fbs-domain-model`-module: zinvol zodra meerdere services het type delen; nu nog te vroeg.
