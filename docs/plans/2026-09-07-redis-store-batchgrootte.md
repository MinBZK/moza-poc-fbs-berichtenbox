# Sequentiële Redis-batcher + Throwable-foutfilter — implementatieplan

**Status:** Concept

> **Voor agentische uitvoerders:** VEREISTE SUB-SKILL: gebruik `superpowers:subagent-driven-development`
> (aanbevolen) of `superpowers:executing-plans` om dit plan taak voor taak uit te voeren. Stappen
> gebruiken checkbox-syntaxis (`- [ ]`) voor voortgang.

**Doel:** Een ophaalronde van een ondernemer met honderd aangesloten organisaties rondt af en levert
zijn berichten, en een mislukte cache-schrijf bereikt de gebruiker als `OPHALEN_FOUT`-event in plaats
van een weggevallen SSE-verbinding.

**Architectuur:** Eén herbruikbare helper (`RedisBatching`) biedt Redis-commando's in batches aan in
plaats van allemaal tegelijk, zodat het aantal in-flight commando's losgekoppeld raakt van het aantal
organisaties. De MULTI/EXEC-transactie blijft ongewijzigd één transactie. Los daarvan wordt het
foutfilter op twee reactieve recover-punten verbreed van `Exception` naar `Throwable`, omdat
Vert.x-fouten `Throwable` zijn en nu langs de bestaande `OPHALEN_FOUT`-route glippen.

**Techniek:** Kotlin, Quarkus 3.38.x, Mutiny (`Uni`/`Multi`), Quarkus Reactive Redis Datasource,
JUnit 5 + MockK, Quarkus Dev Services (Testcontainers, `redis/redis-stack-server:7.4.0-v3`).

**Spec:** dit document (context, ontwerpkeuzes en verificatie staan hieronder; er is geen apart
ontwerpdocument).

**Issue:** [MinBZK/MijnOverheidZakelijk#1077](https://github.com/MinBZK/MijnOverheidZakelijk/issues/1077)

**Basisbranch:** `feature/magazijn-bulkhead-wachtrij` (PR
[#283](https://github.com/MinBZK/moza-poc-fbs-berichtenbox/pull/283)) — **niet** `main`. Dit werk is
pas zichtbaar geworden door de wachtrij uit #283 en de doorpaginering, en hoort er in dezelfde
volgorde achteraan.

---

## Context

### Wat er misgaat

`RedisBerichtenCache.store` (`BerichtenCache.kt:147-177`) schrijft per bericht twee commando's
(`HSET` + `EXPIRE`) en biedt ze allemaal tegelijk aan met `Uni.join().all(stores).andFailFast()`
(regel 172). `Uni.join()` subscribet op alle Unis tegelijk, dus alle `2×N` commando's gaan in één
keer de connection-wachtrij in.

De Vert.x-Redis-client begrenst die wachtrij op `quarkus.redis.max-waiting-handlers`
(bevestigd aanwezig als `RedisClientConfig.maxWaitingHandlers()` in quarkus-redis-client 3.38.3),
default **2048**. Bij honderd organisaties × 27 berichten zijn dat ruim 4300 commando's; de
configuratie staat bovendien `berichtensessiecache.max-berichten-per-magazijn=500` toe, dus het
theoretische plafond is 100 × 500 × 2 = 100.000 commando's. Een hogere `max-waiting-handlers`
verplaatst de grens daarom alleen — acceptatiecriterium 3 van het issue vraagt expliciet om een
grens die niet met het aantal organisaties meegroeit.

### Waarom de gebruiker geen foutmelding krijgt

`io.vertx.core.impl.NoStackTraceThrowable` — het type waarmee de Vert.x-Redis-client
"Redis waiting queue is full" meldt — erft van `java.lang.Throwable`, **niet** van
`java.lang.Exception`. Geverifieerd met `javap` op vertx-core-4.5.30:

```
public class io.vertx.core.impl.NoStackTraceThrowable extends java.lang.Throwable
```

Twee reactieve recover-punten filteren op `Exception` en laten dit type dus door:

| Plek | Gevolg vandaag |
|---|---|
| `BerichtensessiecacheService.kt:848` (`aggregeerEnSlaOp`) | de `OPHALEN_FOUT`-route wordt overgeslagen, de fout bereikt de SSE-emitter, RESTEasy kapt de response af zonder afsluitende chunk → `curl` exit 18, browser `TypeError: network error` |
| `BerichtensessiecacheService.kt:621` (per-magazijn recover in de bulkhead-taak) | een Vert.x-`Throwable` uit een magazijn-call valt door naar het vangnet op regel ~946 en wordt daar als `OVERBELAST` geclassificeerd in plaats van als de werkelijke fault |

**Afbakening:** de `catch (e: Exception)`-sites op de *blocking* paden (bv. `legeResultaten`,
`BerichtenCache.init`) zijn niet stuk. `await().atMost(...)` verpakt een niet-`RuntimeException`
in een `java.util.concurrent.CompletionException`, die wél een `Exception` is. Dat is een aanname
die dit plan niet onbewezen laat: taak 4 pint hem met een test.

### Ontwerpkeuzes

**Batchen binnen één transactie, niet erbuiten.** Redis antwoordt op elk commando binnen een
MULTI met `+QUEUED` en voert pas bij EXEC uit. Sequentieel aanbieden begrenst dus het *aanbieden*
zonder de atomiciteit te raken: de hele berichtenlijst blijft één transactie. De zorg uit het issue
("raakt wel de transactie-semantiek") geldt alleen voor de variant met een transactie per batch, en
die kiezen we niet.

**Eén herbruikbare helper, drie aanroepers.** Het `Uni.join()`-fan-out-patroon staat op drie
plekken in `BerichtenCache.kt` (regels 172, 444, 830). Alleen regel 172 is vandaag onbegrensd, maar
alle drie krijgen dezelfde helper zodat het patroon niet opnieuw kan terugkeren met een
lijstlengte die later wél meegroeit.

**Batchgrootte configureerbaar, niet hardcoded.** Een operator die `max-waiting-handlers`
verhoogt of verlaagt moet de batchgrootte kunnen meebewegen. Fail-fast gevalideerd bij boot.

**Terminologie.** `batch` blijft Engels: het is een vast technisch idioom uit de
client-/pipelining-documentatie, net als `retry`, `timeout` en `permit`. Taak 6 voegt het toe aan de
idioomlijst in `CLAUDE.md`.

---

## Globale randvoorwaarden

Deze gelden voor élke taak hieronder.

- **Werkdirectory:** de worktree `/home/claude/projects/moza-poc-fbs-berichtenbox/.claude/worktrees/redis-store-batch`,
  branch `fix/redis-store-batchgrootte`. Niet in de hoofdcheckout werken.
- **Taal:** communicatie, commit-messages, KDoc en comments in het Nederlands; vaste technische
  idiomen (`batch`, `retry`, `timeout`, `connection`, `stream`, `permit`) blijven Engels.
- **Comments:** leg het *waarom* vast, niet het *wat*. Geen verwijzingen naar review-labels,
  issue-nummers in proza (alleen `TODO(#ticket)`), of naar `CLAUDE.md`.
- **Kotlin-stijl:** lege regel vóór én ná elk multi-line accolade-blok en elk zelfstandig
  control-statement (`if`, `when`, `for`, `try`). Geen lege regel tussen twee opeenvolgende
  openings- of sluit-accolades, en niet bij een control-expressie in een assignment/return.
- **detekt:** `maxIssues: 0` zonder baseline. Élke bevinding faalt de build. Bewuste uitzondering
  = inline `@Suppress("Rule")` met motivatie-comment.
- **Coverage:** JaCoCo-gate 90% line coverage. `quarkus-jacoco` telt alleen `@QuarkusTest`-paden;
  pure MockK-unittests dragen niet bij. Daarom draagt élke nieuwe testklasse in
  `libraries/fbs-berichtensessiecache` een `@QuarkusTest`-annotatie, conform het bestaande patroon
  in `BerichtensessiecacheServiceTest`.
- **Altijd `clean` vóór `test`/`verify`.** Zonder `clean` draait Surefire stale `.class`-bestanden
  van een andere branch-state.
- **Docker-tests via de MCP Maven host-runner** (`mcp__maven__run_maven`) met
  `PROJECT_DIR=/home/claude/projects/moza-poc-fbs-berichtenbox/.claude/worktrees/redis-store-batch`;
  de sandbox kan zelf geen sibling-containers starten.
- **Draaiende demo-stack bezet poort 8081** (WireMock) en laat elke `@QuarkusTest` falen met
  "Failed to start quarkus" / "Port already bound". Draai dan met `-Dquarkus.http.test-port=0`.
- **Build-warnings:** nieuwe, onverklaarde waarschuwingen blokkeren. Bekende geaccepteerde
  waarschuwingen (jansi `System::load`, guava `Unsafe::objectFieldOffset`, `LogManager accessed
  before ...`) mogen blijven staan.
- **Mutatietesten is verplicht en handmatig.** Er is geen pitest in deze repo. Voor **élke** test
  die dit plan toevoegt of aanraakt — óók de tests die vóór de fix geschreven worden — geldt: pas
  de bijbehorende mutant toe in productiecode, draai de test, bevestig ROOD, draai de mutant terug,
  bevestig GROEN. Elke taak heeft daarvoor een eigen mutatietabel. Noteer de uitkomst; taak 7
  rapporteert ze in de PR-body.

---

## Bestandsoverzicht

| Bestand | Verantwoordelijkheid |
|---|---|
| **Nieuw** `libraries/fbs-berichtensessiecache/src/main/kotlin/.../berichten/RedisBatching.kt` | De sequentiële batcher. Pure Mutiny-compositie, geen Redis- of CDI-afhankelijkheid. |
| **Nieuw** `libraries/fbs-berichtensessiecache/src/test/kotlin/.../berichten/RedisBatchingTest.kt` | Unittests op de batcher: in-flight-piek, volgorde, foutpropagatie, cardinaliteiten. |
| **Nieuw** `libraries/fbs-berichtensessiecache/src/test/kotlin/.../berichten/KleineBatchRedisTestProfile.kt` | TestProfile met kleine batchgrootte én kleine `max-waiting-handlers`, zodat de productie-storing op testschaal reproduceerbaar is. |
| **Nieuw** `libraries/fbs-berichtensessiecache/src/test/kotlin/.../berichten/RedisBerichtenCacheBatchIntegrationTest.kt` | `@QuarkusTest` tegen echte Redis: `store` van een lijst die de wachtrij zou overschrijden. |
| **Wijzig** `libraries/fbs-berichtensessiecache/src/main/kotlin/.../berichten/BerichtenCache.kt` | Nieuwe config-property + guard; `store`, `renewBerichtTtls` en `pruneListEnDelHash` via de batcher. |
| **Wijzig** `libraries/fbs-berichtensessiecache/src/main/kotlin/.../berichten/BerichtensessiecacheService.kt` | Twee reactieve foutfilters van `Exception` naar `Throwable`. |
| **Wijzig** `libraries/fbs-berichtensessiecache/src/test/kotlin/.../berichten/RedisBerichtenCacheInitTest.kt` | Guard op de batchgrootte. |
| **Wijzig** `libraries/fbs-berichtensessiecache/src/test/kotlin/.../berichten/BerichtensessiecacheServiceTest.kt` | Regressietests op de `Throwable`-paden. |
| **Wijzig** `services/berichtenuitvraag/src/main/resources/application.properties` | Batchgrootte-default + expliciete `max-waiting-handlers`. |
| **Wijzig** `docs/operator-handleiding-uitvraag.md` | Nieuwe sectie: de knop en de rekensom. |
| **Wijzig** `CLAUDE.md` | `batch` toevoegen aan de lijst met Engelse technische idiomen. |

---

## Taak 1: De sequentiële batcher

**Bestanden:**
- Aanmaken: `libraries/fbs-berichtensessiecache/src/main/kotlin/nl/rijksoverheid/moz/fbs/berichtensessiecache/berichten/RedisBatching.kt`
- Test: `libraries/fbs-berichtensessiecache/src/test/kotlin/nl/rijksoverheid/moz/fbs/berichtensessiecache/berichten/RedisBatchingTest.kt`

**Interfaces:**
- Verbruikt: niets uit eerdere taken.
- Levert: `internal object RedisBatching { fun <T> inBatches(items: List<T>, batchgrootte: Int, commando: (T) -> Uni<Void>): Uni<Void> }`
  — taken 2 en 3 roepen precies deze signatuur aan.

- [ ] **Stap 1: Schrijf de falende test**

Maak `RedisBatchingTest.kt`:

```kotlin
package nl.rijksoverheid.moz.fbs.berichtensessiecache.berichten

import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.junit.TestProfile
import io.smallrye.mutiny.Uni
import io.smallrye.mutiny.helpers.test.UniAssertSubscriber
import io.smallrye.mutiny.subscription.UniEmitter
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource

/**
 * De batcher bestaat om het aantal in-flight Redis-commando's los te koppelen van de lengte van de
 * lijst. Deze tests meten daarom niet of het resultaat klopt (dat doet de integratietest), maar of
 * de piek aan gelijktijdig aangeboden commando's daadwerkelijk begrensd blijft.
 */
// @QuarkusTest zodat deze MockK-loze unit-coverage in jacoco-quarkus.exec terechtkomt
// (quarkus-jacoco telt alleen @QuarkusTest-paden mee).
@QuarkusTest
@TestProfile(MockedDependenciesProfile::class)
class RedisBatchingTest {

    /**
     * Registreert per aangeboden item een emitter die pas op commando afrondt. Zo staat het
     * aantal openstaande emitters gelijk aan het aantal in-flight commando's en is de piek
     * meetbaar zonder timing-afhankelijkheid.
     */
    private class BatchSpion {
        private val openstaand = mutableListOf<UniEmitter<in Void>>()
        val aangeboden = mutableListOf<Int>()
        var piek = 0
            private set

        fun commando(item: Int): Uni<Void> = Uni.createFrom().emitter { emitter ->
            aangeboden.add(item)
            openstaand.add(emitter)

            if (openstaand.size > piek) piek = openstaand.size
        }

        /** Rondt precies de nu-openstaande commando's af; dat laat de volgende batch los. */
        fun rondHuidigeBatchAf() {
            val huidige = openstaand.toList()

            openstaand.clear()
            huidige.forEach { it.complete(null) }
        }

        /** Laat het eerste openstaande commando falen; de rest blijft hangen. */
        fun laatEersteFalen(fout: Throwable) {
            val eerste = openstaand.first()

            openstaand.clear()
            eerste.fail(fout)
        }

        fun openstaand(): Int = openstaand.size
    }

    @Test
    fun `piek aan in-flight commando's blijft op de batchgrootte, niet op de lijstlengte`() {
        val spion = BatchSpion()
        val subscriber = RedisBatching.inBatches((1..100).toList(), batchgrootte = 4) { spion.commando(it) }
            .subscribe().withSubscriber(UniAssertSubscriber.create<Void>())

        assertEquals(4, spion.openstaand(), "alleen de eerste batch mag aangeboden zijn")

        repeat(25) { spion.rondHuidigeBatchAf() }

        subscriber.assertCompleted()
        assertEquals(4, spion.piek, "piek moet de batchgrootte zijn, niet de lijstlengte")
        assertEquals(100, spion.aangeboden.size)
    }

    @Test
    fun `volgorde van aanbieden volgt de lijstvolgorde`() {
        val spion = BatchSpion()
        val subscriber = RedisBatching.inBatches((1..10).toList(), batchgrootte = 3) { spion.commando(it) }
            .subscribe().withSubscriber(UniAssertSubscriber.create<Void>())

        repeat(4) { spion.rondHuidigeBatchAf() }

        subscriber.assertCompleted()
        assertEquals((1..10).toList(), spion.aangeboden)
    }

    @ParameterizedTest(name = "{0} items bij batchgrootte {1} levert {2} batches")
    @CsvSource("0, 4, 0", "1, 4, 1", "4, 4, 1", "5, 4, 2", "9, 4, 3", "10, 1, 10")
    fun `elke cardinaliteit rondt af en biedt elk item precies eenmaal aan`(
        aantal: Int,
        batchgrootte: Int,
        verwachteBatches: Int,
    ) {
        val spion = BatchSpion()
        val subscriber = RedisBatching.inBatches((1..aantal).toList(), batchgrootte) { spion.commando(it) }
            .subscribe().withSubscriber(UniAssertSubscriber.create<Void>())

        repeat(verwachteBatches) { spion.rondHuidigeBatchAf() }

        subscriber.assertCompleted()
        assertEquals((1..aantal).toList(), spion.aangeboden)
        assertTrue(spion.piek <= batchgrootte, "piek ${spion.piek} overschreed batchgrootte $batchgrootte")
    }

    @Test
    fun `een fout in een batch propageert en stopt het aanbieden van volgende batches`() {
        val spion = BatchSpion()
        val fout = RuntimeException("Redis waiting queue is full")
        val subscriber = RedisBatching.inBatches((1..20).toList(), batchgrootte = 5) { spion.commando(it) }
            .subscribe().withSubscriber(UniAssertSubscriber.create<Void>())

        spion.rondHuidigeBatchAf()
        spion.laatEersteFalen(fout)

        subscriber.assertFailedWith(RuntimeException::class.java, "Redis waiting queue is full")
        assertEquals(10, spion.aangeboden.size, "batch 3 en verder mogen niet meer aangeboden zijn")
    }

    @ParameterizedTest
    @CsvSource("0", "-1")
    fun `een batchgrootte van 0 of lager wordt geweigerd`(batchgrootte: Int) {
        val ex = assertThrows<IllegalArgumentException> {
            RedisBatching.inBatches(listOf(1, 2, 3), batchgrootte) { Uni.createFrom().voidItem() }
        }

        assertTrue(ex.message!!.contains("batchgrootte"), "Was: ${ex.message}")
    }
}
```

- [ ] **Stap 2: Draai de test en bevestig dat hij faalt**

```bash
mcp__maven__run_maven: clean test -pl libraries/fbs-berichtensessiecache -am -Dtest=RedisBatchingTest
```

Verwacht: COMPILATIEFOUT — `Unresolved reference: RedisBatching`.

- [ ] **Stap 3: Schrijf de minimale implementatie**

Maak `RedisBatching.kt`:

```kotlin
package nl.rijksoverheid.moz.fbs.berichtensessiecache.berichten

import io.smallrye.mutiny.Uni

/**
 * Biedt Redis-commando's in batches aan in plaats van allemaal tegelijk.
 *
 * De Vert.x-Redis-client houdt per connection een wachtrij bij van commando's waarvan het antwoord
 * nog moet komen, begrensd door `quarkus.redis.max-waiting-handlers`. Een `Uni.join().all(...)`
 * over een lijst subscribet op alle Unis tegelijk en schrijft dus alle commando's in één keer weg;
 * bij een fan-out die met het aantal organisaties meegroeit loopt die wachtrij vol en faalt de hele
 * keten met "Redis waiting queue is full".
 *
 * Deze helper koppelt het aantal in-flight commando's los van de lijstlengte: een batch wordt pas
 * aangeboden zodra de vorige beantwoord is. Binnen een MULTI/EXEC blijft dat volledig atomair —
 * Redis antwoordt op elk commando in de transactie met `+QUEUED` en voert pas bij EXEC uit, dus
 * batchen begrenst enkel het aanbieden en niet de transactie zelf.
 */
internal object RedisBatching {

    /**
     * Past [commando] toe op elk item in [items], in batches van [batchgrootte]. Binnen een batch
     * lopen de commando's parallel en fail-fast; batches volgen elkaar sequentieel op. Een lege
     * [items] levert een direct voltooide `Uni` zonder Redis te raken.
     *
     * @throws IllegalArgumentException als [batchgrootte] niet groter is dan 0 — bij 0 zou
     *   `chunked` werpen met een melding die de configuratiesleutel niet noemt.
     */
    fun <T> inBatches(items: List<T>, batchgrootte: Int, commando: (T) -> Uni<Void>): Uni<Void> {
        require(batchgrootte > 0) { "batchgrootte ($batchgrootte) moet groter zijn dan 0" }

        if (items.isEmpty()) return Uni.createFrom().voidItem()

        return items.chunked(batchgrootte).fold(Uni.createFrom().voidItem()) { voorgaande, batch ->
            voorgaande.chain { _ ->
                Uni.join().all(batch.map(commando)).andFailFast().replaceWithVoid()
            }
        }
    }
}
```

- [ ] **Stap 4: Draai de test en bevestig dat hij slaagt**

```bash
mcp__maven__run_maven: clean test -pl libraries/fbs-berichtensessiecache -am -Dtest=RedisBatchingTest
```

Verwacht: alle tests GROEN.

- [ ] **Stap 5: Mutatietest elke nieuwe test**

Pas per rij de mutant toe in `RedisBatching.kt`, draai `-Dtest=RedisBatchingTest`, bevestig ROOD,
draai terug, bevestig GROEN.

| # | Mutant | Test die ROOD moet worden |
|---|---|---|
| M1 | `items.chunked(batchgrootte)` → `listOf(items)` | `piek aan in-flight commando's ...` (piek wordt 100), `elke cardinaliteit ...` |
| M2 | `voorgaande.chain { ... }` → `Uni.join().all(...)` direct zonder `chain` (alle batches tegelijk) | `piek aan in-flight commando's ...` |
| M3 | `.andFailFast()` weglaten | `een fout in een batch propageert ...` (aangeboden telt door naar 20) |
| M4 | `require(batchgrootte > 0)` → `require(batchgrootte >= 0)` | `een batchgrootte van 0 of lager wordt geweigerd` (rij `0`) |
| M5 | `if (items.isEmpty()) return ...` weglaten | `elke cardinaliteit ...` rij `0, 4, 0` |
| M6 | `items.chunked(batchgrootte)` → `items.chunked(batchgrootte).reversed()` | `volgorde van aanbieden volgt de lijstvolgorde` |

Als een mutant GROEN blijft: de test dekt dat gedrag niet. Voeg een assertie toe voordat je verder gaat.

- [ ] **Stap 6: detekt**

```bash
mcp__maven__run_maven: detekt:check -pl libraries/fbs-berichtensessiecache
```

Verwacht: 0 bevindingen.

- [ ] **Stap 7: Commit**

```bash
git add libraries/fbs-berichtensessiecache/src/main/kotlin/nl/rijksoverheid/moz/fbs/berichtensessiecache/berichten/RedisBatching.kt \
        libraries/fbs-berichtensessiecache/src/test/kotlin/nl/rijksoverheid/moz/fbs/berichtensessiecache/berichten/RedisBatchingTest.kt
git commit -m "feat(sessiecache): bied Redis-commando's in batches aan"
```

---

## Taak 2: `store` gebruikt de batcher

**Bestanden:**
- Wijzig: `libraries/fbs-berichtensessiecache/src/main/kotlin/nl/rijksoverheid/moz/fbs/berichtensessiecache/berichten/BerichtenCache.kt:70-96` (constructor + `init`) en `:147-177` (`store`)
- Wijzig: `libraries/fbs-berichtensessiecache/src/test/kotlin/nl/rijksoverheid/moz/fbs/berichtensessiecache/berichten/RedisBerichtenCacheInitTest.kt`
- Aanmaken: `libraries/fbs-berichtensessiecache/src/test/kotlin/nl/rijksoverheid/moz/fbs/berichtensessiecache/berichten/KleineBatchRedisTestProfile.kt`
- Aanmaken: `libraries/fbs-berichtensessiecache/src/test/kotlin/nl/rijksoverheid/moz/fbs/berichtensessiecache/berichten/RedisBerichtenCacheBatchIntegrationTest.kt`

**Interfaces:**
- Verbruikt: `RedisBatching.inBatches(items, batchgrootte, commando)` uit taak 1.
- Levert: constructor-parameter `redisBatchgrootte: Int` op `RedisBerichtenCache` (positie: ná
  `startupRedisearchTimeoutSeconds`), gevoed door `berichtensessiecache.redis-batchgrootte`
  (default `256`). Taak 3 gebruikt hetzelfde veld; taak 5 documenteert de property.

- [ ] **Stap 1: Schrijf de falende guard-test**

Voeg toe aan `RedisBerichtenCacheInitTest.kt`. Pas eerst de bestaande `cache(...)`-helper aan zodat
hij de nieuwe parameter kent:

```kotlin
    private fun cache(startupTimeoutSeconds: Long = 5L, redisBatchgrootte: Int = 256) = RedisBerichtenCache(
        redis = redis,
        objectMapper = ObjectMapper(),
        ttl = Duration.ofHours(12),
        aggregationLockTtl = Duration.ofMinutes(2),
        startupRedisearchTimeoutSeconds = startupTimeoutSeconds,
        redisBatchgrootte = redisBatchgrootte,
    )
```

De twee bestaande tests worden dan `cache(startupTimeoutSeconds = 0)` en
`cache(startupTimeoutSeconds = -1)`. Voeg daarna toe:

```kotlin
    @ParameterizedTest
    @ValueSource(ints = [0, -1])
    fun `redis-batchgrootte van 0 of lager wordt geweigerd vóór Redis geraakt wordt`(batchgrootte: Int) {
        // Zonder ondergrens zou een 0 pas bij de eerste store falen, midden in een ophaalronde,
        // met een melding uit `chunked` die de configuratiesleutel niet noemt.
        val ex = assertThrows<IllegalArgumentException> { cache(redisBatchgrootte = batchgrootte).init() }

        assertTrue(ex.message!!.contains("redis-batchgrootte"), "Was: ${ex.message}")
        verify(exactly = 0) { redis.search() }
    }
```

Imports erbij: `org.junit.jupiter.params.ParameterizedTest`, `org.junit.jupiter.params.provider.ValueSource`.

- [ ] **Stap 2: Draai en bevestig dat hij faalt**

```bash
mcp__maven__run_maven: clean test -pl libraries/fbs-berichtensessiecache -am -Dtest=RedisBerichtenCacheInitTest
```

Verwacht: COMPILATIEFOUT — `No value passed for parameter 'redisBatchgrootte'`.

- [ ] **Stap 3: Voeg de property en de guard toe**

In `BerichtenCache.kt`, ná de `startupRedisearchTimeoutSeconds`-parameter (regel 86):

```kotlin
    // Begrenst het aantal Redis-commando's dat tegelijk in de connection-wachtrij staat
    // (`quarkus.redis.max-waiting-handlers`, default 2048). Zonder deze grens groeit een
    // store-transactie mee met het aantal organisaties van de ontvanger en loopt die wachtrij
    // vol — de ophaalronde faalt dan pas in de laatste stap, ná alle bevragingen. Elke bericht
    // kost twee commando's (HSET + EXPIRE), dus de piek is 2 × deze waarde.
    @param:ConfigProperty(name = "berichtensessiecache.redis-batchgrootte", defaultValue = "256")
    private val redisBatchgrootte: Int,
```

In `init()`, ná de bestaande `require` op `startupRedisearchTimeoutSeconds`:

```kotlin
        // Moet > 0: 0 laat `chunked` pas bij de eerste store werpen, midden in een ophaalronde,
        // met een melding die de configuratiesleutel niet noemt.
        require(redisBatchgrootte > 0) {
            "berichtensessiecache.redis-batchgrootte ($redisBatchgrootte) moet groter zijn dan 0"
        }
```

- [ ] **Stap 4: Draai en bevestig dat hij slaagt**

```bash
mcp__maven__run_maven: clean test -pl libraries/fbs-berichtensessiecache -am -Dtest=RedisBerichtenCacheInitTest
```

Verwacht: GROEN.

- [ ] **Stap 5: Schrijf het TestProfile en de falende integratietest**

Maak `KleineBatchRedisTestProfile.kt`:

```kotlin
package nl.rijksoverheid.moz.fbs.berichtensessiecache.berichten

import io.quarkus.test.junit.QuarkusTestProfile

/**
 * Reproduceert de productie-storing op testschaal: `max-waiting-handlers` staat hier op 64 in
 * plaats van de Vert.x-default 2048, zodat een handvol honderden berichten al genoeg is om de
 * wachtrij te laten vollopen wanneer alle commando's tegelijk aangeboden worden. De batchgrootte
 * staat er ruim onder, zodat de test de begrenzing meet en niet een toevallig gehaalde marge.
 *
 * De TTL's staan bewust ruim (in tegenstelling tot RealRedisTestProfile): deze test schrijft
 * honderden berichten en controleert daarna de sleutels, wat langer duurt dan een TTL van 2s.
 */
class KleineBatchRedisTestProfile : QuarkusTestProfile {

    override fun getEnabledAlternatives(): Set<Class<*>> = setOf(
        MockMagazijnClientFactory::class.java,
    )

    override fun getConfigOverrides(): Map<String, String> = mapOf(
        "quarkus.redis.devservices.enabled" to "true",
        "quarkus.redis.devservices.image-name" to "redis/redis-stack-server:7.4.0-v3",
        "quarkus.redis.max-waiting-handlers" to "64",
        "berichtensessiecache.redis-batchgrootte" to "16",
        "berichtensessiecache.ttl" to "PT5M",
        "berichtensessiecache.aggregation-lock-ttl" to "PT5M",
    )
}
```

Maak `RedisBerichtenCacheBatchIntegrationTest.kt`:

```kotlin
package nl.rijksoverheid.moz.fbs.berichtensessiecache.berichten

import io.quarkus.redis.datasource.ReactiveRedisDataSource
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.junit.TestProfile
import jakarta.inject.Inject
import nl.rijksoverheid.moz.fbs.common.identificatie.Oin
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Duration
import java.time.Instant
import java.util.UUID

/**
 * De storing die dit adresseert trad pas op bij honderd organisaties: alle HSET+EXPIRE-commando's
 * werden tegelijk aangeboden en overschreden de Vert.x-wachtrij, waarna de ophaalronde in de
 * laatste stap omviel. Deze test reproduceert dat mechanisme zonder honderd magazijnen na te
 * bootsen: het profiel verlaagt `max-waiting-handlers` naar 64, zodat 200 berichten (400
 * commando's) al ruim over de grens gaan wanneer ze ongebatcht aangeboden worden.
 */
@QuarkusTest
@TestProfile(KleineBatchRedisTestProfile::class)
class RedisBerichtenCacheBatchIntegrationTest {

    @Inject
    internal lateinit var berichtenCache: BerichtenCache

    @Inject
    lateinit var redis: ReactiveRedisDataSource

    // OIN als test-ontvanger: geen elfproef-vereiste, uniek per test-run.
    private val ontvanger = Oin(System.nanoTime().toString().padStart(20, '0').takeLast(20))
    private val cacheKey = BerichtenCache.cacheKey(ontvanger)

    private fun bericht(index: Int) = Bericht(
        berichtId = UUID.randomUUID(),
        afzender = "00000001800866472000",
        ontvanger = ontvanger,
        onderwerp = "Bericht $index",
        inhoud = "Inhoud van bericht $index",
        publicatietijdstip = Instant.parse("2026-01-01T00:00:00Z").plusSeconds(index.toLong()),
    )

    @Test
    fun `store van meer berichten dan de wachtrij aankan slaagt en bewaart alles`() {
        val berichten = (1..200).map { bericht(it) }

        berichtenCache.store(cacheKey, berichten).await().atMost(Duration.ofSeconds(30))

        val opgeslagen = redis.list(String::class.java)
            .lrange("$cacheKey:list", 0, -1)
            .await().atMost(Duration.ofSeconds(5))

        assertEquals(200, opgeslagen.size, "de volledige lijst moet bewaard zijn")
    }

    @Test
    fun `elke per-bericht hash krijgt een TTL, ook voorbij de eerste batch`() {
        val berichten = (1..200).map { bericht(it) }

        berichtenCache.store(cacheKey, berichten).await().atMost(Duration.ofSeconds(30))

        // Het laatste bericht valt in de laatste batch: als alleen de eerste batch verwerkt zou
        // zijn, mist juist deze hash of zijn TTL.
        val laatste = berichten.last()
        val ttl = redis.key().ttl(BerichtenCache.berichtKey(laatste.berichtId))
            .await().atMost(Duration.ofSeconds(5))

        assertTrue(ttl > 0, "hash van het laatste bericht moet bestaan met een TTL; was: $ttl")
    }

    @Test
    fun `de transactie blijft atomair over batches heen`() {
        // Alles of niets: na een geslaagde store moet elk bericht individueel opvraagbaar zijn,
        // niet alleen de kop van de lijst.
        val berichten = (1..200).map { bericht(it) }

        berichtenCache.store(cacheKey, berichten).await().atMost(Duration.ofSeconds(30))

        val gevonden = berichten.count { b ->
            berichtenCache.getById(b.berichtId, ontvanger).await().atMost(Duration.ofSeconds(5)) != null
        }

        assertEquals(200, gevonden, "elk bericht uit elke batch moet opvraagbaar zijn")
    }
}
```

> **Let op bij het schrijven:** controleer de daadwerkelijke constructor van `Bericht` in
> `libraries/fbs-berichtensessiecache/src/main/kotlin/.../berichten/Bericht.kt` en die van
> `BerichtenCache.getById` voordat je dit overneemt; vul verplichte velden aan die hierboven
> ontbreken en laat optionele velden weg. De testintentie blijft ongewijzigd.

- [ ] **Stap 6: Draai de integratietest tegen de ONGEWIJZIGDE `store` en bevestig dat hij faalt**

Doe dit vóór stap 7 — dit is het bewijs dat de test de storing daadwerkelijk vangt.

```bash
mcp__maven__run_maven: clean test -pl libraries/fbs-berichtensessiecache -am -Dtest=RedisBerichtenCacheBatchIntegrationTest
```

Verwacht: ROOD met `Redis waiting queue is full` (of een `CompletionException` met die melding als
oorzaak). Krijg je in plaats daarvan GROEN, dan is 64 nog te ruim of 200 te weinig: verhoog het
aantal berichten tot de test rood wordt, en noteer het gekozen aantal.

- [ ] **Stap 7: Laat `store` de batcher gebruiken**

Vervang in `BerichtenCache.kt` het `.chain`-blok op regel 164-173:

```kotlin
                .chain { _ ->
                    RedisBatching.inBatches(sorted, redisBatchgrootte) { bericht ->
                        val berichtKey = BerichtenCache.berichtKey(bericht.berichtId)
                        val fields = berichtToHash(bericht)

                        txHash.hset(berichtKey, fields)
                            .chain { _ -> txKey.expire(berichtKey, ttl) }
                            .replaceWithVoid()
                    }
                }
```

De rest van `store` (de `del`, `rpush`, `expire` op de listKey en de logging) blijft ongewijzigd —
de `withTransaction` blijft dus één MULTI/EXEC.

- [ ] **Stap 8: Draai de integratietest en bevestig dat hij slaagt**

```bash
mcp__maven__run_maven: clean test -pl libraries/fbs-berichtensessiecache -am -Dtest=RedisBerichtenCacheBatchIntegrationTest
```

Verwacht: GROEN.

- [ ] **Stap 9: Draai de volledige moduletest**

```bash
mcp__maven__run_maven: clean verify -pl libraries/fbs-berichtensessiecache -am
```

Verwacht: GROEN, JaCoCo ≥ 90%, detekt 0 bevindingen, geen nieuwe warnings.

- [ ] **Stap 10: Mutatietest elke test uit deze taak**

| # | Mutant | Test die ROOD moet worden |
|---|---|---|
| M7 | `RedisBatching.inBatches(sorted, redisBatchgrootte)` → `inBatches(sorted, Int.MAX_VALUE)` | alle drie in `RedisBerichtenCacheBatchIntegrationTest` |
| M8 | `inBatches(sorted, ...)` → `inBatches(sorted.take(redisBatchgrootte), ...)` | `store van meer berichten ...`, `elke per-bericht hash ...`, `de transactie blijft atomair ...` |
| M9 | `.chain { _ -> txKey.expire(berichtKey, ttl) }` weglaten | `elke per-bericht hash krijgt een TTL ...` |
| M10 | `require(redisBatchgrootte > 0)` → `require(redisBatchgrootte >= 0)` | `redis-batchgrootte van 0 of lager wordt geweigerd` (rij `0`) |
| M11 | guard-`require` verplaatsen tot ná de `ft_list`-aanroep | `redis-batchgrootte van 0 of lager ...` (de `verify(exactly = 0) { redis.search() }`) |

Ook de twee bestaande tests in `RedisBerichtenCacheInitTest` opnieuw mutatietesten (mutant:
`require(startupRedisearchTimeoutSeconds > 0)` → `>= 0`), want hun helper is aangeraakt.

- [ ] **Stap 11: Commit**

```bash
git add libraries/fbs-berichtensessiecache/src/main/kotlin/nl/rijksoverheid/moz/fbs/berichtensessiecache/berichten/BerichtenCache.kt \
        libraries/fbs-berichtensessiecache/src/test/kotlin/nl/rijksoverheid/moz/fbs/berichtensessiecache/berichten/
git commit -m "fix(sessiecache): begrens de in-flight commando's van een store"
```

---

## Taak 3: `renewBerichtTtls` en `pruneListEnDelHash` op dezelfde batcher

**Bestanden:**
- Wijzig: `libraries/fbs-berichtensessiecache/src/main/kotlin/nl/rijksoverheid/moz/fbs/berichtensessiecache/berichten/BerichtenCache.kt:435-449` en `:818-834`

**Interfaces:**
- Verbruikt: `RedisBatching.inBatches(...)` uit taak 1 en het veld `redisBatchgrootte` uit taak 2.
- Levert: niets nieuws.

Deze twee zijn vandaag *niet* stuk — `renewBerichtTtls` is begrensd door `pageSize` (max ~102
commando's) en `pruneListEnDelHash` doet in de praktijk 0 of 1 `LREM`. Ze gaan mee zodat het
onbegrensde fan-out-patroon nergens meer in dit bestand staat en niet terugkeert zodra een van beide
lijsten later wél meegroeit.

- [ ] **Stap 1: Schrijf de falende test**

Voeg toe aan `RedisBerichtenCacheBatchIntegrationTest.kt`:

```kotlin
    @Test
    fun `een read verlengt de TTL van elk geraakt bericht, ook voorbij de eerste batch`() {
        // renewBerichtTtls batcht de EXPIRE-commando's; zonder batching zou een pagina met meer
        // berichten dan de wachtrij toelaat de TTL-verlenging laten omvallen. De verlenging is
        // best-effort en slikt fouten, dus we meten de TTL zelf en niet het uitblijven van een fout.
        val berichten = (1..200).map { bericht(it) }

        berichtenCache.store(cacheKey, berichten).await().atMost(Duration.ofSeconds(30))

        val pagina = berichtenCache.getPage(cacheKey, 0, 200, null, ontvanger, null)
            .await().atMost(Duration.ofSeconds(30))

        assertEquals(200, pagina!!.berichten.size)

        val laatste = pagina.berichten.last()
        val ttl = redis.key().ttl(BerichtenCache.berichtKey(laatste.berichtId))
            .await().atMost(Duration.ofSeconds(5))

        assertTrue(ttl > 0, "TTL van het laatste bericht op de pagina moet verlengd zijn; was: $ttl")
    }
```

> **Let op:** `getPage` heeft een `pageSize`-plafond dat via
> `berichtensessiecache.magazijn-page-size` of een eigen constante begrensd kan zijn. Controleer
> `getPage` voordat je 200 gebruikt; is het plafond lager, kies dan een pageSize onder dat plafond en
> verlaag de `max-waiting-handlers` in `KleineBatchRedisTestProfile` navenant, zodat de test nog
> steeds de grens raakt.

- [ ] **Stap 2: Draai en bevestig dat hij faalt zonder de wijziging**

```bash
mcp__maven__run_maven: clean test -pl libraries/fbs-berichtensessiecache -am -Dtest=RedisBerichtenCacheBatchIntegrationTest#'een read verlengt de TTL van elk geraakt bericht, ook voorbij de eerste batch'
```

Verwacht: ROOD (TTL is -1 of -2, want de ongebatchte transactie viel om en de renew is best-effort).

- [ ] **Stap 3: Zet beide aanroepplekken om**

`renewBerichtTtls` (regel 435-449) wordt:

```kotlin
    private fun renewBerichtTtls(cacheKey: String, ids: List<UUID>): Uni<Void> {
        val listKey = listKey(cacheKey)
        val statusKey = statusKey(cacheKey)

        return redis.withTransaction { tx ->
            val txKey = tx.key()
            val sleutels = listOf(listKey, statusKey) + ids.map { BerichtenCache.berichtKey(it) }

            RedisBatching.inBatches(sleutels, redisBatchgrootte) { sleutel ->
                txKey.expire(sleutel, ttl).replaceWithVoid()
            }
        }.replaceWithVoid()
            .onFailure().invoke { e -> log.warnf(e, "Sliding TTL renewReadTtl mislukt voor cacheKey=%s (read geslaagd, TTL niet verlengd)", cacheKey) }
            .onFailure().recoverWithNull().replaceWithVoid()
    }
```

De KDoc erboven blijft staan, maar de zin over "alle EXPIRE-commands ... in één round-trip" klopt
niet meer. Vervang die door: *"alle EXPIRE-commands in één transactie, in batches aangeboden zodat
een grote pagina de connection-wachtrij niet vult."*

`pruneListEnDelHash` (regel 826-830) wordt:

```kotlin
                    RedisBatching.inBatches(matching, redisBatchgrootte) { blob ->
                        redis.list(String::class.java).lrem(listKey, 0, blob).replaceWithVoid()
                    }
```

De bestaande comment ("Eén LREM per match (in praktijk 0 of 1, want berichtId is uniek). count=0:
verwijder alle exact-matchende voorkomens.") blijft staan.

- [ ] **Stap 4: Draai en bevestig dat hij slaagt**

```bash
mcp__maven__run_maven: clean verify -pl libraries/fbs-berichtensessiecache -am
```

Verwacht: GROEN. Let specifiek op de bestaande `RedisBerichtenCacheIntegrationTest` — die dekt
`delete` en de TTL-verlenging op de bestaande manier en mag niet regresseren.

- [ ] **Stap 5: Mutatietest**

| # | Mutant | Test die ROOD moet worden |
|---|---|---|
| M12 | `inBatches(sleutels, redisBatchgrootte)` → `inBatches(sleutels, Int.MAX_VALUE)` | `een read verlengt de TTL ...` |
| M13 | `listOf(listKey, statusKey) + ids.map { ... }` → `ids.map { ... }` (sessie-keys weggelaten) | een bestaande test in `RedisBerichtenCacheIntegrationTest` die de sliding TTL op de listKey controleert — zoek hem op; bestaat hij niet, voeg hem toe voordat je verder gaat |
| M14 | `inBatches(matching, ...)` → `inBatches(matching.take(1), ...)` in `pruneListEnDelHash` | de bestaande delete-test in `RedisBerichtenCacheIntegrationTest`; blijft die groen, voeg een test toe met twee list-entries voor hetzelfde `berichtId` |

- [ ] **Stap 6: Commit**

```bash
git add libraries/fbs-berichtensessiecache/src/main/kotlin/nl/rijksoverheid/moz/fbs/berichtensessiecache/berichten/BerichtenCache.kt \
        libraries/fbs-berichtensessiecache/src/test/kotlin/nl/rijksoverheid/moz/fbs/berichtensessiecache/berichten/RedisBerichtenCacheBatchIntegrationTest.kt \
        libraries/fbs-berichtensessiecache/src/test/kotlin/nl/rijksoverheid/moz/fbs/berichtensessiecache/berichten/RedisBerichtenCacheIntegrationTest.kt
git commit -m "refactor(sessiecache): batch ook de TTL-verlenging en de delete-prune"
```

---

## Taak 4: Een Vert.x-`Throwable` bereikt de gebruiker als foutmelding

**Bestanden:**
- Wijzig: `libraries/fbs-berichtensessiecache/src/main/kotlin/nl/rijksoverheid/moz/fbs/berichtensessiecache/berichten/BerichtensessiecacheService.kt:621` en `:848`
- Wijzig: `libraries/fbs-berichtensessiecache/src/test/kotlin/nl/rijksoverheid/moz/fbs/berichtensessiecache/berichten/BerichtensessiecacheServiceTest.kt`

**Interfaces:**
- Verbruikt: niets uit eerdere taken (onafhankelijk van taken 1-3).
- Levert: niets nieuws.

Deze taak staat los van de batcher: ook ná taak 2 kan een cache-schrijf om andere redenen falen met
een Vert.x-`Throwable` (connection reset, pod-shutdown), en dan moet de gebruiker nog steeds een
uitleg krijgen in plaats van een weggevallen verbinding.

- [ ] **Stap 1: Schrijf de falende tests**

Voeg toe aan `BerichtensessiecacheServiceTest.kt`, direct ná de bestaande test
`lege resolver-set met store-failure emit OPHALEN_FOUT-event met referentie en zet FOUT-status`
(regel 372):

```kotlin
    @Test
    fun `een Vert-x-fout uit store levert een OPHALEN_FOUT-event, geen gefaalde stream`() {
        // De Vert.x-Redis-client meldt een volgelopen wachtrij met NoStackTraceThrowable, dat
        // rechtstreeks van Throwable erft. Een recover die op Exception filtert laat dat type
        // door naar de SSE-emitter, waarna RESTEasy de response afkapt zonder afsluitende chunk:
        // de gebruiker ziet een weggevallen verbinding in plaats van een uitleg.
        every { berichtenCache.trySetAggregationStatus(cacheKey, any()) } returns Uni.createFrom().item(true)
        every { resolver.resolve(ontvanger) } returns Uni.createFrom().item(emptySet<String>())
        every { clientFactory.getAllClients() } returns emptyMap()
        every { berichtenCache.store(cacheKey, emptyList()) } returns
            Uni.createFrom().failure(NoStackTraceThrowable("Redis waiting queue is full"))
        every { berichtenCache.storeAggregationStatus(cacheKey, any()) } returns Uni.createFrom().voidItem()

        val events = service.haalBerichtenOp(ontvanger).collect().asList()
            .await().atMost(Duration.ofSeconds(5))

        assertEquals(1, events.size)
        assertInstanceOf(OphalenFout::class.java, events[0])
        verify {
            berichtenCache.storeAggregationStatus(
                cacheKey,
                match { it.status == OphalenStatus.FOUT },
            )
        }
    }
```

En een test op het aggregatiepad (`aggregeerEnSlaOp`), waar de storing uit het issue daadwerkelijk
zit. Kopieer daarvoor de opzet van een bestaande test die één magazijn met succes bevraagt (zoek in
dit bestand op `filterIsInstance<MagazijnBevragingVoltooid>` voor een sjabloon) en laat de
`store`-mock falen met `NoStackTraceThrowable`:

```kotlin
    @Test
    fun `een Vert-x-fout bij het opslaan na bevraging levert OPHALEN_FOUT met referentie`() {
        // Alle organisaties zijn bevraagd en hebben geantwoord; alleen het bewaren mislukt. De
        // gebruiker moet horen dat het ophalen niet bewaard kon worden en dat hij opnieuw moet
        // ophalen — niet in een afgekapte verbinding blijven hangen.
        <opzet: één magazijn dat één bericht teruggeeft, zoals in de bestaande VOLTOOID-tests>
        every { berichtenCache.store(cacheKey, any()) } returns
            Uni.createFrom().failure(NoStackTraceThrowable("Redis waiting queue is full"))
        every { berichtenCache.storeAggregationStatus(cacheKey, any()) } returns Uni.createFrom().voidItem()

        val events = service.haalBerichtenOp(ontvanger).collect().asList()
            .await().atMost(Duration.ofSeconds(10))

        val fout = assertInstanceOf(OphalenFout::class.java, events.last())

        assertTrue(
            fout.foutmelding.contains("(ref: ${fout.referentie})"),
            "Foutmelding moet de (ref: <UUID>)-suffix dragen; was: ${fout.foutmelding}",
        )
        assertTrue(
            events.filterIsInstance<OphalenGereed>().isEmpty(),
            "een mislukte opslag mag niet als GEREED eindigen",
        )
    }

    @Test
    fun `een Vert-x-fout uit een magazijn-bevraging levert FOUT, niet NIET_OPGEHAALD`() {
        // De per-magazijn recover filterde op Exception; een Throwable viel door naar het
        // vangnet en werd daar als OVERBELAST geclassificeerd. Dat zegt "niet bevraagd" terwijl
        // het magazijn wél bevraagd is en gefaald heeft — een onjuiste uitspraak richting de
        // gebruiker.
        <opzet: één magazijn waarvan de client een NoStackTraceThrowable werpt>

        val events = service.haalBerichtenOp(ontvanger).collect().asList()
            .await().atMost(Duration.ofSeconds(10))

        val voltooid = events.filterIsInstance<MagazijnBevragingMislukt>().single()

        assertEquals(MagazijnFoutStatus.FOUT, voltooid.status)
    }
```

Vul de `<opzet: ...>`-regels in aan de hand van de bestaande tests in dit bestand — laat geen
placeholder achter. Import erbij: `io.vertx.core.impl.NoStackTraceThrowable`.

- [ ] **Stap 2: Voeg de test toe die de blocking-aanname pint**

Deze test moet nu al GROEN zijn en legt vast waaróm de `catch (e: Exception)`-sites niet mee
hoeven te veranderen:

```kotlin
    @Test
    fun `een Throwable op het blocking lege-magazijn-pad wordt door de bestaande catch gevangen`() {
        // `await().atMost(...)` verpakt een niet-RuntimeException in een CompletionException, die
        // wél een Exception is. Daarom volstaat `catch (e: Exception)` op de blocking paden, terwijl
        // de reactieve recovers op Throwable moeten filteren. Deze test bewaakt die aanname: valt
        // hij ooit om, dan moeten de blocking catches mee verbreed worden.
        every { berichtenCache.trySetAggregationStatus(cacheKey, any()) } returns Uni.createFrom().item(true)
        every { resolver.resolve(ontvanger) } returns Uni.createFrom().item(emptySet<String>())
        every { clientFactory.getAllClients() } returns emptyMap()
        every { berichtenCache.store(cacheKey, emptyList()) } returns
            Uni.createFrom().failure(NoStackTraceThrowable("Redis waiting queue is full"))
        every { berichtenCache.storeAggregationStatus(cacheKey, any()) } returns Uni.createFrom().voidItem()

        val events = service.haalBerichtenOp(ontvanger).collect().asList()
            .await().atMost(Duration.ofSeconds(5))

        assertInstanceOf(OphalenMisluktVoorBevraging::class.java, events.single())
    }
```

> Deze en de eerste test uit stap 1 raken hetzelfde pad; houd alleen de variant die het scherpst
> uitdrukt wat je bewaakt, en verwijder de andere. Welke dat is blijkt uit welke recover het
> lege-magazijn-pad daadwerkelijk gebruikt — lees `legeResultaten` (regel ~517) voordat je kiest.

- [ ] **Stap 3: Draai en bevestig welke tests falen**

```bash
mcp__maven__run_maven: clean test -pl libraries/fbs-berichtensessiecache -am -Dtest=BerichtensessiecacheServiceTest
```

Verwacht: de tests op het aggregatiepad en het per-magazijn-pad zijn ROOD; de blocking-test is GROEN.
Noteer welke precies rood zijn — dat is het bewijs dat de fix nodig is en waar.

- [ ] **Stap 4: Verbreed de twee reactieve filters**

`BerichtensessiecacheService.kt:848`:

```kotlin
            // Ongetypeerd: Vert.x meldt clientfouten (zoals een volgelopen commando-wachtrij) met
            // NoStackTraceThrowable, dat rechtstreeks van Throwable erft. Een filter op Exception
            // laat dat type door naar de SSE-emitter, waarna de verbinding wegvalt zonder dat de
            // gebruiker hoort dat zijn ophaalronde niet bewaard is.
            .onFailure().recoverWithUni { error ->
```

`BerichtensessiecacheService.kt:621`:

```kotlin
                            // Ongetypeerd, om dezelfde reden als bij het opslaan na aggregatie: een
                            // Vert.x-Throwable moet hier geclassificeerd worden als de fout die hij
                            // is. Viel hij door naar het vangnet, dan zou het magazijn als
                            // "niet opgehaald" gerapporteerd worden terwijl het wél bevraagd is.
                            .onFailure().recoverWithItem { error ->
```

Controleer of `classifyMagazijnFault(error)` een `Throwable` accepteert; zo niet, verruim de
signatuur van `Throwable` en pas `ClassifyMagazijnFaultTest` daarop aan.

- [ ] **Stap 5: Draai en bevestig dat alles slaagt**

```bash
mcp__maven__run_maven: clean verify -pl libraries/fbs-berichtensessiecache -am
```

Verwacht: GROEN.

- [ ] **Stap 6: Mutatietest elke test uit deze taak**

| # | Mutant | Test die ROOD moet worden |
|---|---|---|
| M15 | `.onFailure().recoverWithUni { ... }` (regel 848) → `.onFailure(Exception::class.java).recoverWithUni { ... }` | `een Vert-x-fout bij het opslaan na bevraging ...` |
| M16 | `.onFailure().recoverWithItem { ... }` (regel 621) → `.onFailure(Exception::class.java).recoverWithItem { ... }` | `een Vert-x-fout uit een magazijn-bevraging levert FOUT ...` |
| M17 | in `herstelNaAggregatieCacheFout`: `OphalenMisluktNaBevraging(...)` → `OphalenGereed(...)` | `een Vert-x-fout bij het opslaan na bevraging ...` (de `OphalenGereed`-assertie) |
| M18 | `catch (ex: Exception)` in `legeResultaten` → `catch (ex: RuntimeException)` | de blocking-aanname-test uit stap 2 |

Mutatietest ook de bestaande test
`lege resolver-set met store-failure emit OPHALEN_FOUT-event met referentie en zet FOUT-status`
opnieuw (mutant M17), want het pad eronder is aangeraakt.

- [ ] **Stap 7: Commit**

```bash
git add libraries/fbs-berichtensessiecache/src/main/kotlin/nl/rijksoverheid/moz/fbs/berichtensessiecache/berichten/BerichtensessiecacheService.kt \
        libraries/fbs-berichtensessiecache/src/test/kotlin/nl/rijksoverheid/moz/fbs/berichtensessiecache/berichten/BerichtensessiecacheServiceTest.kt
git commit -m "fix(uitvraag): laat een Vert.x-fout de gebruiker als foutmelding bereiken"
```

---

## Taak 5: Foutfilters repo-breed nalopen

**Bestanden:**
- Onderzoek: `libraries/fbs-berichtensessiecache/src/main/**`, `services/berichtenuitvraag/src/main/**`, `services/berichtenmagazijn/src/main/**`, `libraries/fbs-common/src/main/**`
- Wijzig: wat het onderzoek oplevert

**Interfaces:**
- Verbruikt: het onderscheid uit taak 4 (reactief = `Throwable`, blocking-na-`await` = `Exception` volstaat).
- Levert: niets nieuws.

- [ ] **Stap 1: Inventariseer**

```bash
git grep -n "onFailure(Exception::class.java)\|onFailure(RuntimeException::class.java)" -- '*/src/main/**'
git grep -n "catch (\w*: Exception)\|catch (\w*: RuntimeException)" -- '*/src/main/**'
```

Maak per treffer een regel in een tabel: pad:regel, reactief of blocking, staat er een `await()`
tussen de fout en de `catch`, en of de fout uit een Vert.x-/Redis-/reactieve client kan komen.

- [ ] **Stap 2: Beoordeel per treffer**

Beslisregel:
- **Reactief** (`.onFailure(...)` op een `Uni`/`Multi`) met een Vert.x- of Redis-bron stroomopwaarts
  → verbreden naar het ongetypeerde `.onFailure()`.
- **Blocking** (`catch` na `await().atMost(...)`) → laten staan; `CompletionException` is een
  `Exception`. Zet er alleen een comment bij als de reden niet ter plaatse zichtbaar is.
- **Reactief zonder reactieve client stroomopwaarts** (bv. puur Jackson-mapping) → laten staan,
  en noteer waarom in de tabel.

- [ ] **Stap 3: Pas aan wat aanpassing behoeft, met een test per aanpassing**

Elke aanpassing krijgt een test volgens hetzelfde patroon als taak 4: een `NoStackTraceThrowable`
door dat pad, assertie op de bedoelde uitkomst. Schrijf de test eerst, bevestig ROOD, pas dan aan.

Levert het onderzoek niets op, dan is dat de uitkomst: noteer in de PR-body welke treffers
beoordeeld zijn en waarom ze bleven staan. Voeg dan géén tests toe voor ongewijzigde code.

- [ ] **Stap 4: Draai de volle suite van elke geraakte module**

```bash
mcp__maven__run_maven: clean verify -pl libraries/fbs-berichtensessiecache -am
mcp__maven__run_maven: clean verify -pl services/berichtenuitvraag -am
```

Alleen modules draaien die je daadwerkelijk gewijzigd hebt.

- [ ] **Stap 5: Mutatietest elke test die je in stap 3 hebt toegevoegd**

Mutant per test: draai het verbrede filter terug naar `onFailure(Exception::class.java)`, bevestig
ROOD, zet terug.

- [ ] **Stap 6: Commit**

```bash
git add -A
git commit -m "fix: verbreed reactieve foutfilters naar Throwable"
```

Sla de commit over als stap 3 niets opleverde.

---

## Taak 6: Configuratie, operator-handleiding en CLAUDE.md

**Bestanden:**
- Wijzig: `services/berichtenuitvraag/src/main/resources/application.properties`
- Wijzig: `docs/operator-handleiding-uitvraag.md`
- Wijzig: `CLAUDE.md`

**Interfaces:**
- Verbruikt: de property `berichtensessiecache.redis-batchgrootte` uit taak 2.
- Levert: niets nieuws.

- [ ] **Stap 1: Zet de defaults expliciet in `application.properties`**

Voeg toe in het Redis-blok (na regel ~101, bij de overige `quarkus.redis.*`-sleutels):

```properties
# Bovengrens op het aantal commando's per connection waarvan het antwoord nog moet komen. Dit is
# de Vert.x-default, hier expliciet gezet omdat berichtensessiecache.redis-batchgrootte eraan
# afgemeten is: een store kost 2 commando's per bericht, dus de piek is 2 x batchgrootte en moet
# hieronder blijven. Wie deze waarde verlaagt, verlaagt de batchgrootte mee.
quarkus.redis.max-waiting-handlers=2048
```

En in het `berichtensessiecache.*`-blok (na `berichtensessiecache.aggregation-lock-ttl`):

```properties
# Aantal berichten waarvan de Redis-commando's tegelijk aangeboden worden bij het bewaren van een
# ophaalronde. Elk bericht kost een HSET en een EXPIRE, dus de piek is 2 x deze waarde en blijft
# daarmee ruim onder max-waiting-handlers hierboven — onafhankelijk van het aantal organisaties
# waar de ontvanger bij aangesloten is. Hoger = minder round-trips maar een hogere piek.
# Moet > 0 (afgedwongen in RedisBerichtenCache.init).
berichtensessiecache.redis-batchgrootte=256
%prod,staging,acceptatie.berichtensessiecache.redis-batchgrootte=${REDIS_BATCHGROOTTE:256}
```

- [ ] **Stap 2: Documenteer de knop in de operator-handleiding**

Voeg in `docs/operator-handleiding-uitvraag.md` een sectie toe direct ná
`## Hoeveel berichten per organisatie` (regel 157):

````markdown
## Hoeveel Redis-commando's tegelijk

Het bewaren van een ophaalronde kost twee Redis-commando's per bericht (de hash zelf en zijn
vervaltermijn). Die commando's gaan in batches naar Redis, zodat er nooit meer tegelijk onderweg
zijn dan de client aankan — ongeacht bij hoeveel organisaties de ondernemer is aangesloten.

| Property | Default | Wat |
|---|---|---|
| `berichtensessiecache.redis-batchgrootte` | 256 | Berichten per batch |
| `quarkus.redis.max-waiting-handlers` | 2048 | Commando's per connection waarvan het antwoord nog moet komen |

De invariant die je moet bewaken:

```
2 x berichtensessiecache.redis-batchgrootte  <  quarkus.redis.max-waiting-handlers
```

Bij de defaults is dat 512 tegen 2048. Verlaag je `max-waiting-handlers`, verlaag dan de
batchgrootte mee; verhoog je de batchgrootte, controleer dan of de bovengrens nog past. Er is geen
startup-controle op deze invariant: de twee sleutels wonen in verschillende extensies en de
Vert.x-waarde is bij boot niet uit de sessiecache-configuratie te lezen.

Wordt de invariant tóch overschreden, dan faalt een ophaalronde in de laatste stap — ná alle
bevragingen — met `Redis waiting queue is full` in de log en een `OPHALEN_FOUT`-event richting de
ondernemer. De ophaalronde is dan niet bewaard; opnieuw ophalen is de herstelactie.

Een hogere batchgrootte betekent minder round-trips naar Redis (een ronde van 2700 berichten kost
er 11 bij 256) maar een hogere piek. Er is geen reden om hem aan te passen zolang
`max-waiting-handlers` op de default staat.
````

- [ ] **Stap 3: Voeg `batch` toe aan de idioomlijst in CLAUDE.md**

In de sectie "Grens tussen NL en EN", in de opsomming die begint met "Voorbeelden: circuit breaker,
bulkhead, ...", voeg `batch` toe achter `backoff`:

```
Voorbeelden: circuit breaker, bulkhead, (half-open) probe, acquire/release pairing, starvation,
retry, backoff, batch, timeout, permit, semaphore, stream, connection, push, **root**.
```

En breid de "niet"-opsomming in dezelfde alinea uit met `batch` → niet `partij`/`stapel`.

- [ ] **Stap 4: Controleer dat de service nog start met de nieuwe properties**

```bash
mcp__maven__run_maven: clean verify -pl services/berichtenuitvraag -am
```

Verwacht: GROEN. Een typefout in een property-naam laat de `@ConfigProperty`-injectie falen bij het
opstarten van elke `@QuarkusTest`, dus dit is de controle.

- [ ] **Stap 5: Commit**

```bash
git add services/berichtenuitvraag/src/main/resources/application.properties \
        docs/operator-handleiding-uitvraag.md CLAUDE.md
git commit -m "docs: documenteer de batchgrootte en zijn invariant"
```

---

## Taak 7: Verificatie op de demo-stack en de PR

**Bestanden:**
- Aanmaken: geen code; wel het plan-document bijwerken en de PR openen.

**Interfaces:**
- Verbruikt: alles uit taken 1-6.

- [ ] **Stap 1: Draai de volledige suite van alle geraakte modules**

```bash
mcp__maven__run_maven: clean verify -pl libraries/fbs-berichtensessiecache -am
mcp__maven__run_maven: clean verify -pl services/berichtenuitvraag -am
mcp__maven__run_maven: clean verify -pl services/berichtenmagazijn -am
```

Loop de output na op waarschuwingen. Nieuwe, onverklaarde waarschuwingen blokkeren; trieer ze of
los ze op voordat je verder gaat.

- [ ] **Stap 2: Start de demo-stack en vul honderd organisaties**

Volg `docs/demo-runbook.md` voor het starten van de stack en het kiezen van een persona met honderd
aangesloten organisaties. Gebruik de standaardvulling van 27 berichten per organisatie, zoals in de
meting in het issue.

```bash
docker compose up -d
```

- [ ] **Stap 3: Meet drie ophaalronden**

Per ronde: start het ophalen via de berichtenbox (of `curl` op
`GET /api/v1/berichten/_ophalen` met de `X-Ontvanger`-header) en noteer of het slotevent
(`OPHALEN_GEREED`) binnenkomt en of de lijst daarna gevuld is.

Verwacht: **3 van 3** met slotevent en gevulde lijst. Gebruik dezelfde tabelvorm als het issue:

| Organisaties | Berichten in de ronde | Slotevent |
|---|---|---|
| 100 | ... | ? van 3 |

Haal je geen 3 van 3, dan is de fix niet af — ga terug naar taak 2 en zoek uit welke grens nu
geraakt wordt (log op `Redis waiting queue is full` of op een andere melding).

- [ ] **Stap 4: Meet één ronde met een opzettelijk falende cache-schrijf**

Zet Redis tijdelijk stil (`docker compose stop redis`) nadat de bevragingen zijn gestart, of gebruik
de toxiproxy-knop uit het demo-runbook. Verwacht: een `OPHALEN_FOUT`-event met een referentie, en in
de berichtenbox een melding dat het ophalen niet bewaard kon worden — **niet** "Stream afgebroken".

Noteer de waargenomen melding letterlijk; die gaat in de PR-body.

- [ ] **Stap 5: Werk het plan-document bij**

Zet `**Status:** Uitgevoerd` bovenaan dit bestand en vul onder een nieuwe kop `## Uitkomst` in:
de mutatietabellen met hun werkelijke uitkomst per mutant, de demo-meting uit stap 3, en de
waargenomen foutmelding uit stap 4.

```bash
git add docs/plans/2026-09-07-redis-store-batchgrootte.md
git commit -m "docs: leg de uitkomst van de batchgrootte-verificatie vast"
```

- [ ] **Stap 6: Synchroniseer met de basisbranch**

```bash
git fetch origin
git merge origin/feature/magazijn-bulkhead-wachtrij
```

Los eventuele conflicten op en draai daarna opnieuw
`clean verify -pl libraries/fbs-berichtensessiecache -am`.

- [ ] **Stap 7: Push en open de draft-PR**

```bash
git push -u origin fix/redis-store-batchgrootte
gh pr create --draft \
  --base feature/magazijn-bulkhead-wachtrij \
  --title "fix(uitvraag): begrens de Redis-commando's per ophaalronde" \
  --body-file /tmp/pr-body.md
```

Geen reviewer toevoegen. De PR-body bevat:

- **Wat er aan de hand was** — functioneel: de ondernemer met de meeste aansluitingen kreeg niets te
  zien en geen uitleg.
- **Wat er nu gebeurt** — de batcher, met de invariant `2 × batchgrootte < max-waiting-handlers`.
- **De tweede oorzaak** — `NoStackTraceThrowable extends Throwable`, met de `javap`-uitvoer als
  bewijs, en waarom de blocking `catch (e: Exception)`-sites wél goed waren.
- **Knoppen** — de tabel met `redis-batchgrootte` en `max-waiting-handlers`.
- **Mutatietesten** — de tabellen M1-M18 met hun werkelijke uitkomst.
- **Demo-verificatie** — de meting uit stap 3 en de foutmelding uit stap 4.
- Sluitregel: `Closes MinBZK/MijnOverheidZakelijk#1077`
- De attributie-blokken (`🤖 Generated with [Claude Code]` + sessielink).

Let op: `gh pr edit` faalt op deze repo (projects-classic); gebruik voor latere body-wijzigingen
`gh api -X PATCH repos/MinBZK/moza-poc-fbs-berichtenbox/pulls/<n> -F body=@bestand` en verifieer
achteraf.

- [ ] **Stap 8: Volg CI**

```bash
gh pr checks <PR#>
gh run watch <run-id> --exit-status
```

Bij falen: `gh run view <id> --log-failed`.

---

## Zelfcontrole op dit plan

**Dekking van de acceptatiecriteria uit het issue:**

| Acceptatiecriterium | Taak |
|---|---|
| Ondernemer met 100 organisaties rondt af en ziet zijn berichten, herhaalbaar | Taak 1-2 (mechanisme), taak 7 stap 3 (meting) |
| Lukt het bewaren niet, dan een foutmelding in plaats van een weggevallen verbinding | Taak 4, taak 7 stap 4 |
| De grens groeit niet mee met het aantal organisaties | Taak 1-3 (batcher), taak 6 (invariant gedocumenteerd) |

**Oplossingsrichtingen uit het issue:**

| Richting | Besluit |
|---|---|
| `max-waiting-handlers` verhogen | Niet gedaan als oplossing; wel expliciet vastgelegd als bovengrens waar de batchgrootte aan afgemeten is (taak 6) |
| `store` in delen aanbieden | Gedaan (taak 2), zonder de transactie-semantiek te raken |
| Mislukte cache-schrijf als `OPHALEN_FOUT` | Gedaan (taak 4), inclusief de oorzaak waarom dat pad niet geraakt werd |

**Consistentie:** `RedisBatching.inBatches(items, batchgrootte, commando)` heeft in taken 1, 2 en 3
dezelfde signatuur. Het veld heet overal `redisBatchgrootte`, de property overal
`berichtensessiecache.redis-batchgrootte`.

**Bekende open punten die de uitvoerder ter plaatse moet oplossen** (bewust, niet als placeholder):
de exacte constructor van `Bericht` (taak 2 stap 5), het `pageSize`-plafond van `getPage`
(taak 3 stap 1), de opzet van een bevraging met één magazijn (taak 4 stap 1), en de vraag of
`classifyMagazijnFault` een `Throwable` accepteert (taak 4 stap 4). Elk daarvan is een lokale
lees-actie in bestaande code, geen ontwerpbeslissing.
