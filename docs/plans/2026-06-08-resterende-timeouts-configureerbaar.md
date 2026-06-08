# Resterende hardcoded timeouts in sessiecache configureerbaar maken

**Status:** Uitgevoerd

Issue: MinBZK/MijnOverheidZakelijk#482

## Context

De meeste timeouts in `fbs-berichtensessiecache` zijn al per omgeving instelbaar
(`magazijn-query-timeout-seconds`, `cache-await-timeout-seconds`,
`profiel.resolver.inner-timeout-seconds`, `profiel.resolver.outer-await-seconds`,
met `require(...)`-invarianten in `BerichtensessiecacheService.valideerTimeouts()`).

Wat resteert zijn twee plekken met een vaste `Duration.ofSeconds(5)` die niet per
omgeving aanpasbaar zijn:

1. `BlockingSessiecache.kt` — `TIMEOUT = Duration.ofSeconds(5)`: de begrensde
   blocking-await op de Mutiny-pijplijn van de facade (alle lees-/schrijfpaden van
   het `Sessiecache`-contract). Overschrijden → 503.
2. `BerichtenCache.kt` (`RedisBerichtenCache.init()`) — twee maal
   `Duration.ofSeconds(5)` op de RediSearch-bootstrap (`ft_list` presence-check en
   `ftCreate` index-aanmaak). Overschrijden → fail-fast `IllegalStateException` bij
   startup.

## Ontwerpkeuzes

- **Twee nieuwe sleutels, prefix `berichtensessiecache.`** (consistent met bestaande):
  - `berichtensessiecache.facade-await-timeout-seconds` (default `5`) — facade-await.
  - `berichtensessiecache.startup-redisearch-timeout-seconds` (default `5`) — dekt
    bóide RediSearch-bootstrap-awaits (één knop; beide zijn dezelfde lokale
    Redis-startup-latency).
- **Default `5`s, motivatie = lokale Redis-latency.** Beide awaits raken een
  lokaal/naast-de-pod draaiende Redis (geen remote-magazijn-RTT), dus dezelfde
  ondergrens als `cache-await-timeout-seconds` (ook 5s). Geconfigureerd via
  `@ConfigProperty(defaultValue = "5")` met verklarend comment op de injectie-site
  (de library heeft geen eigen `application.properties`; consumers documenteren de
  waarde in hun eigen properties).
- **Geen cross-invariant** met de andere timeouts: net als `cache-await` zijn dit
  losse knoppen. Wel een ondergrens `require(> 0)` per knop, zodat een 0/negatieve
  waarde de bescherming niet stil uitschakelt (Mutiny `atMost(ZERO)` wacht onbegrensd).
- `facade-await` validatie in een `init {}`-blok van `BlockingSessiecache`;
  `startup-redisearch` validatie bovenaan `RedisBerichtenCache.init()` (vóór Redis
  geraakt wordt, zodat de guard zonder live Redis testbaar is).

## Stappen

1. `BlockingSessiecache.kt`: constructor-param + `init { require(> 0) }`, companion
   `TIMEOUT` → instance `timeout: Duration`, gebruik + KDoc bijwerken.
2. `BerichtenCache.kt`: constructor-param + `require(> 0)` bovenaan `init()`, beide
   `Duration.ofSeconds(5)` vervangen.
3. `services/berichtenuitvraag/src/main/resources/application.properties`: beide
   sleutels met verklarend comment + `%prod` env-var-override (stijl van
   `cache-await-timeout-seconds`).
4. Tests:
   - `FacadeAwaitTimeoutWiringTest` (`@QuarkusTest`): property-override `=1` +
     vertraagde `getAggregationStatus` (3s) → facade-await slaat aan → 503.
     Pint de property-wiring (typo zou stil op default 5 terugvallen) én het
     degradatiegedrag.
   - `BlockingSessiecacheTest`: constructor-aanroepen bijwerken; unit-test dat
     `facade-await-timeout-seconds = 0` wordt geweigerd.
   - `RedisBerichtenCacheInitTest` (unit, directe constructie met mockk-Redis):
     `startup-redisearch-timeout-seconds = 0` wordt geweigerd vóór Redis geraakt wordt.

## Verificatie

- `./mvnw clean test -pl libraries/fbs-berichtensessiecache -am` (Docker vereist).
- `./mvnw detekt:check`.
- Build-output op nieuwe waarschuwingen controleren.
