**Status:** Concept

# Profiel-service via de FSC-outway — `Fsc-Grant-Hash`-header (#730)

## Context / aanleiding

Op deze branch loopt de magazijn-call al via de FSC-outway: `Magazijninschrijving` kreeg een
optionele `grantHash`, `fbs-common` kreeg een `UuidV7`-generator en een `FscOutwayHeadersFilter`,
en beide magazijn-client-bouwplekken registreren die filter als er een grant-hash is
(zie `docs/plans/2026-07-17-fsc-outway-grant-hash-header.md`).

De **Profiel-service** moet nu langs dezelfde weg. Ook daar geldt: de OpenFSC-outway kiest de
doeldienst/inway op de `Fsc-Grant-Hash`-header (niet op het pad), en `fsc-outway serve` eist een
`Fsc-Transaction-Id` in UUID-v7-vorm. Zonder die headers antwoordt de outway met "service not
found" resp. "invalid uuid version, must be v7".

Twee services benaderen de Profiel-service, beide via dezelfde client uit `fbs-common`:

| Service | Flow | Config |
|---------|------|--------|
| `berichtenuitvraag` | `ProfielMagazijnResolver` (magazijn-set per ontvanger) | `quarkus.rest-client.profiel-service.url=${PROFIEL_SERVICE_URL}` |
| `berichtenmagazijn` | `BerichtValidatieService` (validatie bij aanleveren) | idem |

Beide moeten onafhankelijk van elkaar om kunnen schakelen.

## Het verschil met de magazijn-kant

De magazijn-clients worden **programmatisch** gebouwd (`MagazijnClientFactory.createClient`,
`MagazijnRouter.forMagazijn`), dus daar volstond `builder.register(FscOutwayHeadersFilter(hash))`
per client. `ProfielServiceClient` is een **declaratieve** `@RegisterRestClient(configKey =
"profiel-service")`-interface die via `@RestClient` geïnjecteerd wordt. Er is geen builder om op te
haken, en de client-bean draagt de MP-Fault-Tolerance-`@Retry` op `getPartij` — met zijn zorgvuldig
afgestemde `retryOn`/`abortOn`. Een programmatisch gebouwde client verliest die interceptor.

Daarom een andere registratie-route, maar hetzelfde header-contract.

## Ontwerpkeuzes (afgestemd)

1. **Registratie via `@RegisterProvider` op de interface**, niet via
   `quarkus.rest-client.profiel-service.providers` per service. Eén registratieplek in `fbs-common`
   die beide services erven; de registratie kan niet tussen services uit elkaar lopen, en een
   deployment hoeft alleen de grant-hash te zetten om om te schakelen.
2. **De filter leest zijn eigen config** in plaats van een constructor-arg. Anders dan bij
   magazijnen is er één Profiel-grant-hash, en een provider die door de rest-client-runtime
   geïnstantieerd wordt kan niet betrouwbaar een constructor-argument krijgen. Een no-arg
   constructor + `ConfigProvider.getConfig()` maakt de instantiatie onafhankelijk van CDI.
3. **Geen grant-hash = byte-identiek gedrag.** Afwezig, leeg of whitespace-only → de filter zet
   geen enkele header. Dat houdt dev/test (WireMock op `http://localhost:8089`) en elk nog niet
   omgeschakeld deployment ongewijzigd.
4. **Eén bron van waarheid voor het header-contract.** De header-set-logica wordt uit
   `FscOutwayHeadersFilter` getrokken naar een gedeelde helper; magazijn- en profiel-filter zijn
   beide dunne wrappers. Zonder dit staat het contract (headernamen, v7-eis, debug-log) op twee
   plekken en kan het driften.
5. **Eigen config-prefix** `profiel-service.grant-hash`, niet onder `quarkus.rest-client.
   profiel-service.*`. Dat is Quarkus-eigen namespace; een eigen sleutel daarin bijplaatsen is
   fragiel. (Vergelijk `magazijn-client.*`, dat om dezelfde reden náást `magazijnen.` staat.)

### Buiten scope (vervolg)
- Dynamische grant-hash-discovery (`Fsc-Grant-Hash: discover` / manager-API) — gelijk aan de
  magazijn-kant.
- De gegenereerde `Fsc-Transaction-Id` óók als veld in de LDV-audittrail (ClickHouse) opnemen.
- Expliciete trust-store voor PKIoverheid-certificaatvalidatie (bestaande `TODO(#552)` bij de
  Profiel-config; los van deze wijziging).

## Deploy-voorwaarde (geen code)

`ProfielServiceEndpointValidator` eist `https://` in `%prod`/`%staging`/`%acceptatie` omdat de
Profiel-client BSN/RSIN/KVK in het URL-pad zet (extern contract). `PROFIEL_SERVICE_URL` moet dus
naar een **https**-outway-ingress wijzen — precies zoals `MAGAZIJN_A_URL` op deze branch. De
validator blijft ongewijzigd; een http-outway-ingress zou de boot terecht laten falen.

## Structuur (bestanden)

- Maak: `libraries/fbs-common/.../fsc/FscOutwayHeaders.kt` — gedeelde header-set-logica.
- Wijzig: `libraries/fbs-common/.../fsc/FscOutwayHeadersFilter.kt` — wrapper om de helper.
- Maak: `libraries/fbs-common/.../fsc/ProfielFscOutwayHeadersFilter.kt` — config-lezende filter.
- Wijzig: `libraries/fbs-common/.../profiel/ProfielServiceClient.kt` — `@RegisterProvider`.
- Wijzig: `services/berichtenuitvraag/src/main/resources/application.properties` — grant-hash-key.
- Wijzig: `services/berichtenmagazijn/src/main/resources/application.properties` — idem.
- Wijzig: `CLAUDE.md` — `PROFIEL_SERVICE_GRANT_HASH` in de env-var-tabel.
- Tests bij elke module (zie stappen).

## Stappen

> TDD per module. **Altijd `clean` vóór `test`.** Commit per afgeronde stap.

### Stap 1 — header-logica extraheren in `fbs-common`

Pure extractie, geen gedragswijziging: de bestaande `FscOutwayHeadersFilterTest` moet ongewijzigd
groen blijven — dat is de regressie-check op deze stap.

**Implementatie:**
- `FscOutwayHeaders`: `fun zet(requestContext: ClientRequestContext, grantHash: String)` — zet
  `Fsc-Grant-Hash`, genereert `Fsc-Transaction-Id` via `UuidV7.generate()`, debug-logt uri +
  transaction-id.
- `FscOutwayHeadersFilter(grantHash)` delegeert naar de helper; KDoc-rationale blijft staan.

**Test toevoegen:** `FscOutwayHeadersTest` — headers gezet met de meegegeven hash, transaction-id
parseert als UUID **v7**, twee invocaties leveren verschillende transaction-ids.

**Verificatie:** `./mvnw clean test -pl libraries/fbs-common -am` groen; JaCoCo ≥ 90%; detekt schoon.
Commit: `refactor(common): FSC-header-logica naar gedeelde FscOutwayHeaders (#730)`.

### Stap 2 — `ProfielFscOutwayHeadersFilter`

**Test eerst** (`libraries/fbs-common/src/test/...`), `ProfielFscOutwayHeadersFilterTest` met een
`ClientRequestContext`-fake. De config-waarde moet per test te sturen zijn — geef de filter daarom
een `internal` secundaire constructor of een injecteerbare `grantHashProvider: () -> String?`, zodat
de no-arg productie-constructor (`ConfigProvider`) én de test-variant dezelfde code raken.

Cardinaliteiten van "niet geconfigureerd" — `@ParameterizedTest` over afwezig (`null`), lege string
en whitespace-only: **géén** `Fsc-Grant-Hash` én **géén** `Fsc-Transaction-Id` op de request. Plus:
gevulde hash → beide headers, hash exact doorgegeven, transaction-id parseert als v7. Plus: een hash
met omringende spaties wordt getrimd doorgegeven (spiegelt de `grantHash`-trim in
`ConfigMagazijnregister`).

**Implementatie:**
- `class ProfielFscOutwayHeadersFilter : ClientRequestFilter` met no-arg constructor.
- Grant-hash lazy uit `ConfigProvider.getConfig().getOptionalValue(CONFIG_KEY, String::class.java)`,
  getrimd, blank → `null`. Eén keer lezen en cachen: config is boot-statisch, en dit zit op de
  hot-path van elke Profiel-call.
- `filter(ctx)`: `grantHash?.let { FscOutwayHeaders.zet(ctx, it) }`.
- `CONFIG_KEY = "profiel-service.grant-hash"` als `const` in de companion, zodat de sleutel niet als
  string-literal in properties-tests gedupliceerd wordt.

**Verificatie:** `./mvnw clean test -pl libraries/fbs-common -am` groen; JaCoCo ≥ 90%; detekt schoon.
Commit: `feat(common): ProfielFscOutwayHeadersFilter met optionele grant-hash (#730)`.

### Stap 3 — registratie + config

**Implementatie:**
- `ProfielServiceClient`: `@RegisterProvider(ProfielFscOutwayHeadersFilter::class)` naast de
  bestaande `@RegisterRestClient`. Korte KDoc-regel bij de annotatie: waarom hier en niet per
  service (één registratieplek, geen drift).
- Beide `application.properties`: `profiel-service.grant-hash=${PROFIEL_SERVICE_GRANT_HASH:}` in het
  Profiel-blok, met een comment dat leeg = geen FSC-headers = directe call.
- `CLAUDE.md`: rij `PROFIEL_SERVICE_GRANT_HASH` in de env-var-tabel.

**Verificatie:** beide services compileren en hun bestaande suites blijven groen — de filter zit nu
in de chain van elke Profiel-call, dus dit is de check dat de no-op écht no-op is.
Commit: `feat(profiel): FSC-outway-headers op de Profiel-service-client (#730)`.

### Stap 4 — integratietest per service: de header komt aan

**Test** (WireMock, `@QuarkusTest`) in **beide** services, spiegelend op
`FscOutwayHeadersWireMockTest` aan de magazijn-kant:
- Met een gezette `profiel-service.grant-hash` (via een `TestProfile`-override): roep de flow aan en
  `verify(getRequestedFor(...).withHeader("Fsc-Grant-Hash", equalTo("<hash>"))
  .withHeader("Fsc-Transaction-Id", matching("<v7-regex>")))`.
- Negatief geval: zónder grant-hash → `withoutHeader("Fsc-Grant-Hash")` én
  `withoutHeader("Fsc-Transaction-Id")`.

De `<v7-regex>` moet de version-nibble pinnen (`...-7[0-9a-f]{3}-[89ab]...`), niet enkel
UUID-vorm — de outway wijst v4 expliciet af, dus een test die alleen "is een UUID" controleert zou
de bug die dit voorkomt niet vangen.

**Verificatie:** `./mvnw clean test -pl services/berichtenuitvraag -am` en
`./mvnw clean test -pl services/berichtenmagazijn -am` groen.
Commit: `test(fsc): integratietest FSC-outway-headers op de Profiel-call (#730)`.

## Verificatie (geheel)

- Per module `./mvnw clean test -pl <module> -am` groen; volledige suite `./mvnw clean verify` groen.
- JaCoCo ≥ 90% line coverage in elke geraakte module.
- `./mvnw detekt:check` schoon; geen nieuwe, onverklaarde build-/test-warnings.
- Handmatig (ZAD, ná deploy): `PROFIEL_SERVICE_URL` = de https-outway-ingress +
  `PROFIEL_SERVICE_GRANT_HASH` = de grant-hash van het valide Profiel-contract. De SSE-curl
  `GET /api/v1/berichten/_ophalen` (X-Ontvanger: BSN:999273127) levert een resultaat dat de
  ontvanger-voorkeuren weerspiegelt in plaats van een 503 uit de resolver.

## Git-werkwijze

Feature branch `feature/fsc-outway-grant-hash` (voortzetting), draft-PR, geen reviewer, geen
auto-close-keyword — issue #730 blijft open.
