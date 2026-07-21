**Status:** Concept

# berichtenuitvraag naar magazijn via de FSC-outway — `Fsc-Grant-Hash`-header (#552)

## Context / aanleiding

De FSC-consumer-peer `uitvraag-org` draait (outway `uvrout` + manager/controller/txlog op ZAD,
project `mpfuc-84g`). De datatunnel is bewezen: `curl → outway → inway magazijn-a → berichtenmagazijn`
geeft een 200. Om berichtenuitvraag magazijn-a via die outway te laten lopen is de magazijn-URL al
config-only om te zetten (`MAGAZIJN_A_URL` → de outway-ingress). Wat nog ontbreekt is de
**routering**: de OpenFSC-outway kiest de doeldienst/inway op de **`Fsc-Grant-Hash`-header**, niet op
het pad, en `fsc-outway serve` (v1.43.7) eist bovendien een **`Fsc-Transaction-Id` in UUID-v7-vorm**.
Zonder die headers antwoordt de outway met "service not found" resp. "invalid uuid version, must be v7".

`MagazijnClient` markeert dit al: `TODO(#552): vervangen door FSC outway zodra de federatieve
connectiviteit op MOZ-niveau is vastgesteld`.

### Twee client-bouwplekken (beide raken magazijnen)

Beide bouwen een REST-client met `Magazijninschrijving.url` als base-URI en kunnen een JAX-RS
`ClientRequestFilter` registreren:

| Plek | Bestand | Rol |
|------|---------|-----|
| sessiecache-aggregatie (`_ophalen`/SSE) | `libraries/fbs-berichtensessiecache/.../magazijn/MagazijnClientFactory.kt` (`createClient`) | bulk-lijst per magazijn |
| losse bericht-operaties | `services/berichtenuitvraag/.../uitvraag/MagazijnRouter.kt` (`forMagazijn`) | get-by-id / patch / delete / bijlage |

Beide moeten de FSC-headers meesturen voor een magazijn dat achter een outway zit.

## Ontwerpkeuzes (afgestemd)

1. **Grant-hash uit statische config.** Nieuw, **optioneel** veld `magazijnen."<OIN>".grantHash`
   (env-var, net als `url`). Aanwezig = "dit magazijn is via een outway; stuur FSC-headers".
   Afwezig = ongewijzigd gedrag (bv. magazijn-b, nog direct). Dynamisch ophalen = vervolg.
2. **`Fsc-Transaction-Id` zelf genereren als UUID v7** en meesturen (voor LDV-correlatie
   outway↔inway↔app-log). De JDK kent geen v7 → een kleine generator in `fbs-common`.
3. **Headers alléén als `grantHash` gezet is.** De filter wordt per client geregistreerd mét de
   grant-hash; magazijnen zonder grant-hash krijgen geen filter en blijven byte-identiek.
4. **Gedeelde plaatsing.** De `grantHash` hoort bij het register-domein (`Magazijninschrijving`);
   de header-filter + v7-generator zijn client-infrastructuur en horen in `fbs-common` (waar beide
   consumers al van afhangen). Zo is er één filter-implementatie voor beide bouwplekken.

### Buiten scope (vervolg)
- Dynamische grant-hash-discovery (`Fsc-Grant-Hash: discover` / manager-API).
- De gegenereerde `Fsc-Transaction-Id` óók als veld in de LDV-audittrail (ClickHouse) opnemen —
  nu loggen we 'm alleen bij de uitgaande call, zodat hij via logs correleerbaar is. Volledige
  LDV-veldkoppeling is een aparte wijziging (raakt `LogboekContext`/de LDV-schema's).
- magazijn-b via een eigen outway (heeft eigen peer/contract nodig).

## Structuur (bestanden)

- Wijzig: `libraries/fbs-magazijnregister/.../MagazijnregisterConfig.kt` — `grantHash()` op `Inschrijving`.
- Wijzig: `libraries/fbs-magazijnregister/.../Magazijnregister.kt` — `grantHash` op `Magazijninschrijving`.
- Wijzig: `libraries/fbs-magazijnregister/.../ConfigMagazijnregister.kt` — `grantHash` doorgeven + valideren.
- Maak: `libraries/fbs-common/.../fsc/UuidV7.kt` — UUID-v7-generator.
- Maak: `libraries/fbs-common/.../fsc/FscOutwayHeadersFilter.kt` — `ClientRequestFilter` die
  `Fsc-Grant-Hash` + `Fsc-Transaction-Id` zet.
- Wijzig: `libraries/fbs-berichtensessiecache/.../magazijn/MagazijnClientFactory.kt` — filter registreren als `grantHash != null`.
- Wijzig: `services/berichtenuitvraag/.../uitvraag/MagazijnRouter.kt` — filter registreren als `grantHash != null`.
- Wijzig: `services/berichtenuitvraag/src/main/resources/application.properties` — `magazijnen."<OIN>".grantHash=${MAGAZIJN_A_GRANT_HASH:}`.
- Tests bij elke module (zie stappen).

## Stappen

> TDD per module. **Altijd `clean` vóór `test`.** Commit per afgeronde stap.

### Stap 1 — `grantHash` in de magazijnregister-library

**Test eerst** (`libraries/fbs-magazijnregister/src/test/...`):
- `MagazijnregisterConfigTest` (of het bestaande config/register-testbestand): een config met
  `magazijnen."<OIN>".grantHash=abc123` levert een `Magazijninschrijving.grantHash == "abc123"`;
  zónder de key levert `grantHash == null`. Dek de cardinaliteiten: 0 magazijnen (bestaande
  boot-faalcheck), 1 mét grantHash, meerdere waarvan één mét en één zónder.
- Grens: een lege/whitespace-`grantHash` moet fail-fast bij boot (net als een lege URL), met een
  duidelijke `magazijnen."<OIN>".grantHash`-melding.

**Implementatie:**
- `MagazijnregisterConfig.Inschrijving`: `fun grantHash(): Optional<String>`.
- `Magazijninschrijving`: veld `val grantHash: String?` (na `naam`); in `init` een
  `require(grantHash == null || grantHash.isNotBlank()) { ... }`.
- `ConfigMagazijnregister.init`: `grantHash = entry.grantHash().map { it.trim() }.orElse(null)`
  doorgeven; bij blank → `IllegalStateException` met de configkey (spiegelt de URL-validatie).

**Verificatie:** `./mvnw clean test -pl libraries/fbs-magazijnregister -am` groen; JaCoCo ≥ 90%.
Commit: `feat(magazijnregister): optionele grantHash per magazijn (#552)`.

### Stap 2 — v7-generator + header-filter in `fbs-common`

**Test eerst** (`libraries/fbs-common/src/test/...`):
- `UuidV7Test`: `UuidV7.generate()` levert een `UUID` met **version == 7** en **variant == 2**
  (RFC 4122); twee opeenvolgende waarden zijn verschillend en, gegeven een oplopende klok,
  lexicografisch niet-dalend (tijd-geordend). Test met een injecteerbare `Clock`/`epochMilli`-
  parameter zodat het timestamp-deel deterministisch is.
- `FscOutwayHeadersFilterTest`: een `ClientRequestContext`-mock/-fake; na `filter(ctx)` bevat
  `ctx.headers` een `Fsc-Grant-Hash` == de meegegeven hash én een `Fsc-Transaction-Id` die als
  UUID v7 parseert (version 7). Twee invocaties → twee verschillende transaction-ids.

**Implementatie:**
- `UuidV7`: object met `fun generate(epochMilli: Long = System.currentTimeMillis(), rnd: java.util.Random = java.security.SecureRandom()): UUID`.
  48-bit ms-timestamp in de hoge bits, `version = 7` (nibble op bit 12–15 van time_hi), `variant = 0b10`,
  rest random. Één plek, goed getest.
- `FscOutwayHeadersFilter(private val grantHash: String) : jakarta.ws.rs.client.ClientRequestFilter`
  met `@Provider` NIET nodig (handmatig geregistreerd). In `filter(ctx)`:
  `ctx.headers.putSingle("Fsc-Grant-Hash", grantHash)` en
  `ctx.headers.putSingle("Fsc-Transaction-Id", UuidV7.generate().toString())`. Log op `debug` de
  transaction-id + magazijn zodat de call correleerbaar is met de outway/inway-logs.

**Verificatie:** `./mvnw clean test -pl libraries/fbs-common -am` groen; JaCoCo ≥ 90%; detekt schoon.
Commit: `feat(common): UuidV7 + FscOutwayHeadersFilter (Fsc-Grant-Hash/-Transaction-Id) (#552)`.

### Stap 3 — filter registreren in de sessiecache-factory

**Test eerst** (`libraries/fbs-berichtensessiecache/src/test/...`):
- Omdat `createClient` `protected open` is en de echte builder buiten `@QuarkusTest` faalt: dek de
  **registratie-beslissing** apart. Refactor de filter-keuze naar een testbare helper (bv.
  `fscFilterVoor(inschrijving): FscOutwayHeadersFilter?` → non-null als `grantHash != null`, anders
  null) en test die met een inschrijving mét en zónder grantHash. Plus een `@QuarkusTest`/WireMock-
  integratietest (zie stap 5) die bewijst dat de header daadwerkelijk op de uitgaande call staat.

**Implementatie:**
- In `createClient`: `val b = QuarkusRestClientBuilder.newBuilder().baseUri(...).connectTimeout(...).readTimeout(...)`;
  `inschrijving.grantHash?.let { b.register(FscOutwayHeadersFilter(it)) }`; `b.build(MagazijnClient::class.java)`.

**Verificatie:** `docker compose up -d`; `./mvnw clean test -pl libraries/fbs-berichtensessiecache -am`
groen; JaCoCo ≥ 90%.
Commit: `feat(sessiecache): FSC-outway-headers op magazijn-aggregatie-client (#552)`.

### Stap 4 — filter registreren in `MagazijnRouter` + config

**Test eerst** (`services/berichtenuitvraag/src/test/...`):
- `MagazijnRouterTest`: identieke aanpak — de filter-keuze in een testbare helper, getest met/zonder
  grantHash. `%test`-profiel-config: voeg een `grantHash` toe aan één test-magazijn en assert dat de
  gebouwde client 'm meekrijgt (of via de integratietest van stap 5).

**Implementatie:**
- In `forMagazijn`'s `RestClientBuilder`-keten: `inschrijving.grantHash?.let { builder.register(FscOutwayHeadersFilter(it)) }`
  vóór `.build(...)`.
- `application.properties`: `magazijnen."00000001003214345000".grantHash=${MAGAZIJN_A_GRANT_HASH:}`
  (lege default → header uit tenzij de env-var gezet is; magazijn-b krijgt géén grantHash). Documenteer
  de env-var in de env-var-tabel van `CLAUDE.md`.

**Verificatie:** `./mvnw clean test -pl services/berichtenuitvraag -am` groen; JaCoCo ≥ 90%.
Commit: `feat(uitvraag): FSC-outway-headers op MagazijnRouter + grantHash-config (#552)`.

### Stap 5 — integratietest: de header komt aan

**Test** (WireMock-contracttest, `@QuarkusTest`, in de sessiecache- of uitvraag-laag waar WireMock al
draait): stub het magazijn; roep de flow aan met een inschrijving die een grantHash heeft; verifieer
via `WireMock.verify(getRequestedFor(...).withHeader("Fsc-Grant-Hash", equalTo("<hash>"))
.withHeader("Fsc-Transaction-Id", matching("<uuid-regex>")))`. Én een negatief geval: een magazijn
**zonder** grantHash → géén `Fsc-Grant-Hash`-header op de call.

**Verificatie:** de betreffende module groen incl. de nieuwe integratietest.
Commit: `test(fsc): integratietest FSC-outway-headers op de magazijn-call (#552)`.

## Verificatie (geheel)

- Per module `./mvnw clean test -pl <module> -am` groen; volledige suite `./mvnw clean verify` groen.
- JaCoCo ≥ 90% line coverage in elke geraakte module; gegenereerde code uitgesloten.
- `./mvnw detekt:check` schoon; geen nieuwe, onverklaarde build-/test-warnings.
- Handmatig (ZAD, ná deploy): `MAGAZIJN_A_URL` = de outway-ingress + `MAGAZIJN_A_GRANT_HASH` = de
  grant-hash van het valide contract; de SSE-curl
  `GET /api/v1/berichten/_ophalen` (X-Ontvanger: BSN:999273127) geeft voor magazijn-a een geldig
  (leeg-of-gevuld) resultaat i.p.v. een fout-event.

## Git-werkwijze

Feature branch `feature/fsc-outway-grant-hash`, draft-PR (CODEOWNERS bestaat), geen reviewer, geen
auto-close-keyword (issue #552 blijft open). Plan + implementatie op dezelfde branch.
