# Hard-delete-job Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Status:** Uitgevoerd

**Goal:** Een periodieke achtergrond-job in `services/berichtenmagazijn` die soft-deleted berichten (incl. bijlagen + status) fysiek verwijdert zodra ze aan twee configureerbare bewaardrempels (Archiefwet-conform, default 7 jaar) voldoen, met per verwijderd bericht één LDV-record.

**Afwijkingen tijdens uitvoering:**
- `HardDeleteService.run()` delegeert claim én delete aan `HardDeleteTransactionalOps` (i.p.v. dat `claimVoorHardDelete` direct vanuit de service wordt aangeroepen). Reden: scheduler-thread heeft van zichzelf geen actieve CDI-/transactie-context; `claim()` heeft daarom een eigen `@Transactional(REQUIRES_NEW)`. Concurrency-test legde dit bloot.
- `HardDeleteLdvLogger` schrijft een gestructureerde INFO-log i.p.v. een `@Logboek`-call. De `@Logboek`-interceptor leest HTTP-headers voor W3C-trace-context-propagation en faalt buiten een REST-context. Volwaardige LDV-integratie via `ProcessingHandler.startSpan` is een vervolg-issue.

**Architectuur:** Quarkus Scheduler (`@Scheduled`) + Postgres `FOR UPDATE SKIP LOCKED` voor multi-pod-veilige rij-claim. Per geclaimd bericht een eigen sub-transactie (`REQUIRES_NEW`) die bijlagen → status → bericht in juiste volgorde wist (FK-RESTRICT). LDV-write ná commit via bestaand `@Logboek`-annotatie-pattern, met `@ActivateRequestContext` omdat `LogboekContext` request-scoped is.

**Tech Stack:** Kotlin / Quarkus 3 / Hibernate ORM Panache / PostgreSQL 18 / Flyway / Quarkus Scheduler / JUnit 5 + MockK + Testcontainers / `nl.mijnoverheidzakelijk.ldv:logboekdataverwerking-wrapper`.

**Bronspec:** `docs/plans/2026-05-20-hard-delete-job-design.md`.

---

## File Structure

**Nieuwe bestanden:**
- `services/berichtenmagazijn/src/main/resources/db/migration/V4__hard_delete_index.sql` — partial index op `(verwijderd_op, tijdstip_ontvangst) WHERE verwijderd_op IS NOT NULL`.
- `services/berichtenmagazijn/src/main/resources/db/rollback/V4__hard_delete_index.sql` — handmatige rollback.
- `services/berichtenmagazijn/src/main/kotlin/nl/rijksoverheid/moz/fbs/berichtenmagazijn/retention/RetentionConfig.kt` — `@ConfigMapping`.
- `services/berichtenmagazijn/src/main/kotlin/nl/rijksoverheid/moz/fbs/berichtenmagazijn/retention/HardDeleteCandidaat.kt` — projectie-data-class.
- `services/berichtenmagazijn/src/main/kotlin/nl/rijksoverheid/moz/fbs/berichtenmagazijn/retention/HardDeleteJob.kt` — `@Scheduled` entrypoint.
- `services/berichtenmagazijn/src/main/kotlin/nl/rijksoverheid/moz/fbs/berichtenmagazijn/retention/HardDeleteService.kt` — orchestratie (claim + loop + LDV).
- `services/berichtenmagazijn/src/main/kotlin/nl/rijksoverheid/moz/fbs/berichtenmagazijn/retention/HardDeleteTransactionalOps.kt` — `REQUIRES_NEW` per-bericht-delete.
- `services/berichtenmagazijn/src/main/kotlin/nl/rijksoverheid/moz/fbs/berichtenmagazijn/retention/HardDeleteLdvLogger.kt` — `@Logboek`-geannoteerde wrapper met `@ActivateRequestContext`.
- `services/berichtenmagazijn/src/test/kotlin/nl/rijksoverheid/moz/fbs/berichtenmagazijn/retention/HardDeleteServiceTest.kt` — unit-tests (MockK).
- `services/berichtenmagazijn/src/test/kotlin/nl/rijksoverheid/moz/fbs/berichtenmagazijn/retention/HardDeleteJobIntegrationTest.kt` — `@QuarkusTest`.
- `services/berichtenmagazijn/src/test/kotlin/nl/rijksoverheid/moz/fbs/berichtenmagazijn/retention/HardDeleteConcurrencyTest.kt` — parallelle threads.

**Te wijzigen bestanden:**
- `services/berichtenmagazijn/pom.xml` — `quarkus-scheduler` dependency.
- `services/berichtenmagazijn/src/main/resources/application.properties` — vier retentie-properties.
- `services/berichtenmagazijn/src/main/kotlin/nl/rijksoverheid/moz/fbs/berichtenmagazijn/opslag/BerichtRepository.kt` — `claimVoorHardDelete` + `hardDeleteByDbId`.
- `services/berichtenmagazijn/src/main/kotlin/nl/rijksoverheid/moz/fbs/berichtenmagazijn/opslag/BijlageRepository.kt` — `deleteByBerichtDbId`.
- `services/berichtenmagazijn/src/main/kotlin/nl/rijksoverheid/moz/fbs/berichtenmagazijn/opslag/BerichtStatusRepository.kt` — `deleteByBerichtDbId`.
- `services/berichtenmagazijn/src/main/kotlin/nl/rijksoverheid/moz/fbs/berichtenmagazijn/ProcessingActivities.kt` — `MAGAZIJN_RETENTIE` constante.

---

## Task 1: Maven-dependency `quarkus-scheduler`

**Files:**
- Modify: `services/berichtenmagazijn/pom.xml`

- [ ] **Step 1: Voeg dependency toe**

Open `services/berichtenmagazijn/pom.xml`. Direct ná de `quarkus-smallrye-fault-tolerance`-dependency, voeg toe:

```xml
        <dependency>
            <groupId>io.quarkus</groupId>
            <artifactId>quarkus-scheduler</artifactId>
        </dependency>
```

- [ ] **Step 2: Verifieer dat module compileert**

Run:
```bash
./mvnw -pl services/berichtenmagazijn -am compile -q
```

Expected: build succeeds, geen errors.

- [ ] **Step 3: Commit**

```bash
git add services/berichtenmagazijn/pom.xml
git commit -m "chore(magazijn): voeg quarkus-scheduler toe voor hard-delete-job"
```

---

## Task 2: V4-migratie + rollback

**Files:**
- Create: `services/berichtenmagazijn/src/main/resources/db/migration/V4__hard_delete_index.sql`
- Create: `services/berichtenmagazijn/src/main/resources/db/rollback/V4__hard_delete_index.sql`

- [ ] **Step 1: Schrijf de migratie**

Maak `services/berichtenmagazijn/src/main/resources/db/migration/V4__hard_delete_index.sql`:

```sql
-- Partial index op de kandidaten voor hard-delete (soft-deleted berichten).
--
-- De V3-index `idx_berichten_ontvanger_actief` is een partial index met
-- WHERE verwijderd_op IS NULL — die dekt de ophaal-query, maar niet de
-- retentie-claim die juist op WHERE verwijderd_op IS NOT NULL filtert.
--
-- De index is samengesteld op (verwijderd_op, tijdstip_ontvangst) zodat de
-- claim-query (ORDER BY verwijderd_op ASC + filter op tijdstip_ontvangst)
-- via een index-scan terechtkomt. Partial op `verwijderd_op IS NOT NULL`
-- houdt 'm compact — actieve berichten vormen het overgrote deel.

CREATE INDEX idx_berichten_retentie_kandidaat
    ON berichten (verwijderd_op, tijdstip_ontvangst)
    WHERE verwijderd_op IS NOT NULL;
```

- [ ] **Step 2: Schrijf het rollback-script**

Maak `services/berichtenmagazijn/src/main/resources/db/rollback/V4__hard_delete_index.sql`:

```sql
-- Rollback voor V4__hard_delete_index.sql.
-- Handmatig draaien + flyway_schema_history-rij verwijderen.

DROP INDEX IF EXISTS idx_berichten_retentie_kandidaat;
```

- [ ] **Step 3: Verifieer migratie via een test-run**

Run:
```bash
./mvnw -pl services/berichtenmagazijn -am test -Dtest='BerichtRepositoryIntegrationTest' -q
```

Expected: Flyway draait V4 zonder errors; bestaande integratietests blijven groen (de nieuwe index verandert geen gedrag, alleen plan).

- [ ] **Step 4: Commit**

```bash
git add services/berichtenmagazijn/src/main/resources/db/migration/V4__hard_delete_index.sql \
        services/berichtenmagazijn/src/main/resources/db/rollback/V4__hard_delete_index.sql
git commit -m "feat(magazijn): V4-migratie partial index voor retentie-claim-query"
```

---

## Task 3: `RetentionConfig` (config-mapping) — TDD

**Files:**
- Create: `services/berichtenmagazijn/src/main/kotlin/nl/rijksoverheid/moz/fbs/berichtenmagazijn/retention/RetentionConfig.kt`
- Modify: `services/berichtenmagazijn/src/main/resources/application.properties`
- Modify: `services/berichtenmagazijn/src/test/resources/application.properties`

- [ ] **Step 1: Schrijf de config-mapping**

Maak `services/berichtenmagazijn/src/main/kotlin/nl/rijksoverheid/moz/fbs/berichtenmagazijn/retention/RetentionConfig.kt`:

```kotlin
package nl.rijksoverheid.moz.fbs.berichtenmagazijn.retention

import io.smallrye.config.ConfigMapping
import io.smallrye.config.WithName
import java.time.Period

/**
 * Configuratie voor de hard-delete-job. Bewaartermijnen zijn ISO-8601 Period-strings
 * (`P7Y`, `P3M`, `P90D`); Duration (`PT…`) wordt niet ondersteund — bewaartermijnen
 * zijn altijd in dagen/maanden/jaren, niet uren.
 *
 * Default 7 jaar volgt de administratieve standaardbewaartermijn. Operators MOETEN
 * dit afstemmen op de geldende selectielijst (Archiefwet).
 */
@ConfigMapping(prefix = "retentie.hard-delete")
interface RetentionConfig {

    /** Minimale leeftijd van een bericht (sinds tijdstip_ontvangst) voor hard-delete. */
    @WithName("minimale-leeftijd")
    fun minimaleLeeftijd(): Period

    /** Minimale tijd sinds soft-delete (verwijderd_op) voor hard-delete. */
    @WithName("minimale-soft-delete-leeftijd")
    fun minimaleSoftDeleteLeeftijd(): Period

    /** Cron-expressie voor de scheduler (Quarkus dialect: seconden-precies). */
    fun cron(): String

    /** Max berichten per cron-run. Volgende tick pikt restant op. */
    @WithName("batch-grootte")
    fun batchGrootte(): Int
}
```

- [ ] **Step 2: Voeg properties toe**

Open `services/berichtenmagazijn/src/main/resources/application.properties`. Voeg onderaan toe (ná het LDV-blok):

```properties
# Hard-delete-job: retentie van soft-deleted berichten.
# Bewaartermijnen zijn ISO-8601 Period (P7Y, P3M, P90D). Default 7 jaar volgt
# de administratieve standaard; operators MOETEN afstemmen op de geldende
# selectielijst (Archiefwet).
retentie.hard-delete.minimale-leeftijd=P7Y
retentie.hard-delete.minimale-soft-delete-leeftijd=P7Y
retentie.hard-delete.cron=0 0 3 * * ?
retentie.hard-delete.batch-grootte=1000
```

- [ ] **Step 3: Verifieer dat startup slaagt**

Run:
```bash
./mvnw -pl services/berichtenmagazijn -am compile -q
```

Expected: succes.

- [ ] **Step 4: Schrijf een sanity-check integratietest**

Maak `services/berichtenmagazijn/src/test/kotlin/nl/rijksoverheid/moz/fbs/berichtenmagazijn/retention/RetentionConfigTest.kt`:

```kotlin
package nl.rijksoverheid.moz.fbs.berichtenmagazijn.retention

import io.quarkus.test.junit.QuarkusTest
import jakarta.inject.Inject
import org.junit.jupiter.api.Test
import java.time.Period
import kotlin.test.assertEquals

@QuarkusTest
class RetentionConfigTest {

    @Inject
    lateinit var config: RetentionConfig

    @Test
    fun `default minimale leeftijd is 7 jaar`() {
        assertEquals(Period.ofYears(7), config.minimaleLeeftijd())
    }

    @Test
    fun `default minimale soft-delete leeftijd is 7 jaar`() {
        assertEquals(Period.ofYears(7), config.minimaleSoftDeleteLeeftijd())
    }

    @Test
    fun `default batch-grootte is 1000`() {
        assertEquals(1000, config.batchGrootte())
    }

    @Test
    fun `cron-expressie heeft 6 velden`() {
        assertEquals(6, config.cron().trim().split(Regex("\\s+")).size)
    }
}
```

- [ ] **Step 5: Run de test**

Run:
```bash
./mvnw -pl services/berichtenmagazijn -am test -Dtest='RetentionConfigTest' -q
```

Expected: PASS — 4 tests.

- [ ] **Step 6: Commit**

```bash
git add services/berichtenmagazijn/src/main/kotlin/nl/rijksoverheid/moz/fbs/berichtenmagazijn/retention/RetentionConfig.kt \
        services/berichtenmagazijn/src/main/resources/application.properties \
        services/berichtenmagazijn/src/test/kotlin/nl/rijksoverheid/moz/fbs/berichtenmagazijn/retention/RetentionConfigTest.kt
git commit -m "feat(magazijn): RetentionConfig met Archiefwet-bewaartermijn"
```

---

## Task 4: `HardDeleteCandidaat` data class

**Files:**
- Create: `services/berichtenmagazijn/src/main/kotlin/nl/rijksoverheid/moz/fbs/berichtenmagazijn/retention/HardDeleteCandidaat.kt`

- [ ] **Step 1: Schrijf de data class**

Maak het bestand:

```kotlin
package nl.rijksoverheid.moz.fbs.berichtenmagazijn.retention

import java.time.Instant
import java.util.UUID

/**
 * Projectie van een rij uit de retentie-claim-query. Bevat alleen velden die
 * downstream nodig zijn (LDV-velden + surrogate PK voor de delete-cascade);
 * de volledige `BerichtEntity` (incl. tot 1 MiB `inhoud`) wordt bewust niet
 * geladen.
 */
data class HardDeleteCandidaat(
    val id: Long,
    val berichtId: UUID,
    val ontvangerType: String,
    val ontvangerWaarde: String,
    val tijdstipOntvangst: Instant,
    val verwijderdOp: Instant,
)
```

- [ ] **Step 2: Verifieer compileren**

Run:
```bash
./mvnw -pl services/berichtenmagazijn -am compile -q
```

Expected: succes.

- [ ] **Step 3: Commit**

```bash
git add services/berichtenmagazijn/src/main/kotlin/nl/rijksoverheid/moz/fbs/berichtenmagazijn/retention/HardDeleteCandidaat.kt
git commit -m "feat(magazijn): HardDeleteCandidaat projectie-data-class"
```

---

## Task 5: Repository-methoden — bijlagen + status (TDD)

**Files:**
- Modify: `services/berichtenmagazijn/src/main/kotlin/nl/rijksoverheid/moz/fbs/berichtenmagazijn/opslag/BijlageRepository.kt`
- Modify: `services/berichtenmagazijn/src/main/kotlin/nl/rijksoverheid/moz/fbs/berichtenmagazijn/opslag/BerichtStatusRepository.kt`
- Modify: `services/berichtenmagazijn/src/test/kotlin/nl/rijksoverheid/moz/fbs/berichtenmagazijn/opslag/BerichtRepositoryIntegrationTest.kt` (test-uitbreiding) of een nieuw test-bestand.

- [ ] **Step 1: Schrijf de falende test eerst**

Maak `services/berichtenmagazijn/src/test/kotlin/nl/rijksoverheid/moz/fbs/berichtenmagazijn/opslag/BijlageStatusDeleteIntegrationTest.kt`:

```kotlin
package nl.rijksoverheid.moz.fbs.berichtenmagazijn.opslag

import io.quarkus.test.junit.QuarkusTest
import jakarta.inject.Inject
import jakarta.transaction.Transactional
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID
import kotlin.test.assertEquals

@QuarkusTest
class BijlageStatusDeleteIntegrationTest {

    @Inject lateinit var berichtRepository: BerichtRepository
    @Inject lateinit var bijlageRepository: BijlageRepository
    @Inject lateinit var statusRepository: BerichtStatusRepository

    @BeforeEach
    @Transactional
    fun cleanup() {
        statusRepository.deleteAll()
        bijlageRepository.deleteAll()
        berichtRepository.deleteAll()
    }

    @Test
    @Transactional
    fun `deleteByBerichtDbId verwijdert alle bijlagen van een bericht`() {
        val bericht = saveBerichtMet(berichtId = UUID.randomUUID(), aantalBijlagen = 3)
        val dbId = berichtRepository.findDbIdByBerichtId(bericht.berichtId)!!

        val verwijderd = bijlageRepository.deleteByBerichtDbId(dbId)

        assertEquals(3, verwijderd)
        assertEquals(0, bijlageRepository.metadataVoorBericht(bericht.berichtId).size)
    }

    @Test
    @Transactional
    fun `deleteByBerichtDbId op berichtstatus verwijdert de status-rij`() {
        val bericht = saveBerichtMet(berichtId = UUID.randomUUID(), aantalBijlagen = 0)
        val dbId = berichtRepository.findDbIdByBerichtId(bericht.berichtId)!!
        statusRepository.upsert(
            berichtId = bericht.berichtId,
            patch = BerichtStatusPatch(gelezen = true, map = null),
            tijdstip = Instant.now(),
        )

        val verwijderd = statusRepository.deleteByBerichtDbId(dbId)

        assertEquals(1, verwijderd)
        assertEquals(null, statusRepository.findByBerichtId(bericht.berichtId))
    }

    @Test
    @Transactional
    fun `deleteByBerichtDbId is idempotent — tweede keer 0`() {
        val bericht = saveBerichtMet(berichtId = UUID.randomUUID(), aantalBijlagen = 1)
        val dbId = berichtRepository.findDbIdByBerichtId(bericht.berichtId)!!

        bijlageRepository.deleteByBerichtDbId(dbId)
        val tweedeKeer = bijlageRepository.deleteByBerichtDbId(dbId)

        assertEquals(0, tweedeKeer)
    }

    private fun saveBerichtMet(berichtId: UUID, aantalBijlagen: Int): Bericht {
        val bijlagen = (1..aantalBijlagen).map {
            Bijlage(
                bijlageId = UUID.randomUUID(),
                berichtId = berichtId,
                naam = "bijlage-$it.txt",
                mimeType = "text/plain",
                content = "inhoud-$it".toByteArray(),
            )
        }
        val bericht = Bericht(
            berichtId = berichtId,
            afzender = "00000000000000000001",
            ontvanger = Identificatienummer.bsn("000000012"),
            onderwerp = "Test",
            inhoud = "Inhoud",
            tijdstipOntvangst = Instant.now(),
            bijlagen = bijlagen.map { it.toMetadata() },
            status = null,
        )
        berichtRepository.save(bericht)
        bijlagen.forEach { bijlageRepository.save(it) }
        return bericht
    }
}
```

> **Let op:** kijk in bestaande integratietests (`BerichtRepositoryIntegrationTest`) voor de juiste constructor-syntax van `Bericht`/`Identificatienummer`/`Bijlage` en pas bovenstaande helper aan zodat alle verplichte velden kloppen. Eventuele afwijkingen in BSN-elfproef (`000000012` voldoet) kun je vervangen door de helpers die de bestaande tests gebruiken.

- [ ] **Step 2: Run de test om falen te zien**

Run:
```bash
./mvnw -pl services/berichtenmagazijn -am test -Dtest='BijlageStatusDeleteIntegrationTest' -q
```

Expected: FAIL — `deleteByBerichtDbId` bestaat nog niet (compile error).

- [ ] **Step 3: Implementeer `deleteByBerichtDbId` op `BijlageRepository`**

Open `services/berichtenmagazijn/src/main/kotlin/nl/rijksoverheid/moz/fbs/berichtenmagazijn/opslag/BijlageRepository.kt`. Voeg toe (zelfde class, naast andere methoden):

```kotlin
    /**
     * Verwijdert alle bijlagen van een bericht op basis van de surrogate PK
     * van de parent. Retourneert het aantal verwijderde rijen. Gebruikt door
     * de retentie-job; child-rijen moeten vóór de parent weg vanwege RESTRICT-FK.
     */
    fun deleteByBerichtDbId(berichtDbId: Long): Int =
        delete("bericht.id", berichtDbId).toInt()
```

- [ ] **Step 4: Implementeer `deleteByBerichtDbId` op `BerichtStatusRepository`**

Open `BerichtStatusRepository.kt`. Voeg toe:

```kotlin
    /**
     * Verwijdert de status-rij van een bericht op basis van de surrogate PK
     * van de parent. Retourneert het aantal verwijderde rijen (0 of 1; de
     * unique-constraint op bericht_db_id staat hooguit één rij toe).
     */
    fun deleteByBerichtDbId(berichtDbId: Long): Int =
        delete("bericht.id", berichtDbId).toInt()
```

- [ ] **Step 5: Run de test om passen te zien**

Run:
```bash
./mvnw -pl services/berichtenmagazijn -am test -Dtest='BijlageStatusDeleteIntegrationTest' -q
```

Expected: PASS — 3 tests.

- [ ] **Step 6: Commit**

```bash
git add services/berichtenmagazijn/src/main/kotlin/nl/rijksoverheid/moz/fbs/berichtenmagazijn/opslag/BijlageRepository.kt \
        services/berichtenmagazijn/src/main/kotlin/nl/rijksoverheid/moz/fbs/berichtenmagazijn/opslag/BerichtStatusRepository.kt \
        services/berichtenmagazijn/src/test/kotlin/nl/rijksoverheid/moz/fbs/berichtenmagazijn/opslag/BijlageStatusDeleteIntegrationTest.kt
git commit -m "feat(magazijn): deleteByBerichtDbId op Bijlage- en BerichtStatusRepository"
```

---

## Task 6: `BerichtRepository.claimVoorHardDelete` + `hardDeleteByDbId` (TDD)

**Files:**
- Modify: `services/berichtenmagazijn/src/main/kotlin/nl/rijksoverheid/moz/fbs/berichtenmagazijn/opslag/BerichtRepository.kt`
- Create: `services/berichtenmagazijn/src/test/kotlin/nl/rijksoverheid/moz/fbs/berichtenmagazijn/opslag/BerichtRetentieIntegrationTest.kt`

- [ ] **Step 1: Schrijf de falende test**

Maak `BerichtRetentieIntegrationTest.kt`:

```kotlin
package nl.rijksoverheid.moz.fbs.berichtenmagazijn.opslag

import io.quarkus.test.junit.QuarkusTest
import jakarta.inject.Inject
import jakarta.persistence.EntityManager
import jakarta.transaction.Transactional
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@QuarkusTest
class BerichtRetentieIntegrationTest {

    @Inject lateinit var berichtRepository: BerichtRepository
    @Inject lateinit var bijlageRepository: BijlageRepository
    @Inject lateinit var statusRepository: BerichtStatusRepository
    @Inject lateinit var em: EntityManager

    @BeforeEach
    @Transactional
    fun cleanup() {
        statusRepository.deleteAll()
        bijlageRepository.deleteAll()
        berichtRepository.deleteAll()
    }

    @Test
    @Transactional
    fun `claimVoorHardDelete vindt alleen rijen waarvan beide drempels gehaald zijn`() {
        // Te oud genoeg op beide drempels: kandidaat
        val kandidaatId = saveBericht(
            ontvangstOffsetDagen = -3000,
            verwijderdOpOffsetDagen = -3000,
        )
        // Wel oud genoeg ontvangst, maar net soft-deleted: geen kandidaat
        saveBericht(ontvangstOffsetDagen = -3000, verwijderdOpOffsetDagen = -1)
        // Wel oud genoeg soft-delete (theoretisch), maar net ontvangen: geen kandidaat
        saveBericht(ontvangstOffsetDagen = -1, verwijderdOpOffsetDagen = -3000)
        // Niet verwijderd: geen kandidaat
        saveBericht(ontvangstOffsetDagen = -3000, verwijderdOpOffsetDagen = null)

        val receiptDeadline = Instant.now().minus(7L * 365, ChronoUnit.DAYS)
        val softDeleteDeadline = Instant.now().minus(7L * 365, ChronoUnit.DAYS)

        val candidates = berichtRepository.claimVoorHardDelete(
            receiptDeadline = receiptDeadline,
            softDeleteDeadline = softDeleteDeadline,
            batchSize = 100,
        )

        assertEquals(1, candidates.size)
        assertEquals(kandidaatId, candidates[0].berichtId)
    }

    @Test
    @Transactional
    fun `claimVoorHardDelete sorteert oudste soft-delete eerst`() {
        val ouder = saveBericht(ontvangstOffsetDagen = -3000, verwijderdOpOffsetDagen = -3000)
        val jonger = saveBericht(ontvangstOffsetDagen = -3000, verwijderdOpOffsetDagen = -2900)

        val candidates = berichtRepository.claimVoorHardDelete(
            receiptDeadline = Instant.now().minus(7L * 365, ChronoUnit.DAYS),
            softDeleteDeadline = Instant.now().minus(7L * 365, ChronoUnit.DAYS),
            batchSize = 100,
        )

        assertEquals(listOf(ouder, jonger), candidates.map { it.berichtId })
    }

    @Test
    @Transactional
    fun `claimVoorHardDelete respecteert batchSize`() {
        repeat(3) {
            saveBericht(ontvangstOffsetDagen = -3000, verwijderdOpOffsetDagen = -3000 - it)
        }

        val candidates = berichtRepository.claimVoorHardDelete(
            receiptDeadline = Instant.now().minus(7L * 365, ChronoUnit.DAYS),
            softDeleteDeadline = Instant.now().minus(7L * 365, ChronoUnit.DAYS),
            batchSize = 2,
        )

        assertEquals(2, candidates.size)
    }

    @Test
    @Transactional
    fun `hardDeleteByDbId verwijdert de bericht-rij en retourneert 1`() {
        val berichtId = saveBericht(ontvangstOffsetDagen = -3000, verwijderdOpOffsetDagen = -3000)
        val dbId = berichtRepository.findDbIdByBerichtId(berichtId)!!

        val verwijderd = berichtRepository.hardDeleteByDbId(dbId)

        assertEquals(1, verwijderd)
        assertTrue(berichtRepository.findIncludingDeleted(berichtId) == null)
    }

    /**
     * Maakt een bericht met de gevraagde offsets in dagen. Na save updaten we
     * `tijdstip_ontvangst` en `verwijderd_op` rechtstreeks in de DB om jaren
     * terug in de tijd te gaan zonder wallclock-truc.
     */
    private fun saveBericht(
        ontvangstOffsetDagen: Long,
        verwijderdOpOffsetDagen: Long?,
    ): UUID {
        val berichtId = UUID.randomUUID()
        val bericht = Bericht(
            berichtId = berichtId,
            afzender = "00000000000000000001",
            ontvanger = Identificatienummer.bsn("000000012"),
            onderwerp = "Test",
            inhoud = "Inhoud",
            tijdstipOntvangst = Instant.now(),
            bijlagen = emptyList(),
            status = null,
        )
        berichtRepository.save(bericht)
        val ontvangst = Instant.now().plus(ontvangstOffsetDagen, ChronoUnit.DAYS)
        em.createNativeQuery(
            "UPDATE berichten SET tijdstip_ontvangst = :t WHERE bericht_id = :id",
        )
            .setParameter("t", ontvangst)
            .setParameter("id", berichtId)
            .executeUpdate()
        if (verwijderdOpOffsetDagen != null) {
            val verwijderd = Instant.now().plus(verwijderdOpOffsetDagen, ChronoUnit.DAYS)
            em.createNativeQuery(
                "UPDATE berichten SET verwijderd_op = :v WHERE bericht_id = :id",
            )
                .setParameter("v", verwijderd)
                .setParameter("id", berichtId)
                .executeUpdate()
        }
        em.flush()
        em.clear()
        return berichtId
    }
}
```

> **Let op:** controleer in `BerichtRepositoryIntegrationTest` of de constructor-syntax voor `Bericht`/`Identificatienummer` precies klopt; pas zo nodig aan.

- [ ] **Step 2: Run om falen te zien**

Run:
```bash
./mvnw -pl services/berichtenmagazijn -am test -Dtest='BerichtRetentieIntegrationTest' -q
```

Expected: FAIL — `claimVoorHardDelete` en `hardDeleteByDbId` bestaan niet.

- [ ] **Step 3: Implementeer beide methoden**

Open `BerichtRepository.kt`. Voeg bovenaan de bestaande imports toe:

```kotlin
import nl.rijksoverheid.moz.fbs.berichtenmagazijn.retention.HardDeleteCandidaat
import java.time.Instant
```

Voeg in de class (naast bestaande methoden) toe:

```kotlin
    /**
     * Claimt een batch soft-deleted berichten die aan beide retentie-drempels
     * voldoen. Native query met `FOR UPDATE SKIP LOCKED` zodat parallelle pods
     * disjuncte rij-sets claimen. Caller MOET claim+delete binnen één
     * top-level transactie houden (rijen blijven gelockt totdat die commit).
     */
    fun claimVoorHardDelete(
        receiptDeadline: Instant,
        softDeleteDeadline: Instant,
        batchSize: Int,
    ): List<HardDeleteCandidaat> {
        @Suppress("UNCHECKED_CAST")
        val rijen = getEntityManager()
            .createNativeQuery(
                """
                SELECT id, bericht_id, ontvanger_type, ontvanger_waarde,
                       tijdstip_ontvangst, verwijderd_op
                FROM berichten
                WHERE verwijderd_op IS NOT NULL
                  AND verwijderd_op      <= :softDeadline
                  AND tijdstip_ontvangst <= :receiptDeadline
                ORDER BY verwijderd_op ASC
                LIMIT :batchSize
                FOR UPDATE SKIP LOCKED
                """.trimIndent(),
            )
            .setParameter("softDeadline", softDeleteDeadline)
            .setParameter("receiptDeadline", receiptDeadline)
            .setParameter("batchSize", batchSize)
            .resultList as List<Array<Any?>>

        return rijen.map { row ->
            HardDeleteCandidaat(
                id = (row[0] as Number).toLong(),
                berichtId = row[1] as UUID,
                ontvangerType = row[2] as String,
                ontvangerWaarde = row[3] as String,
                tijdstipOntvangst = (row[4] as java.sql.Timestamp).toInstant(),
                verwijderdOp = (row[5] as java.sql.Timestamp).toInstant(),
            )
        }
    }

    /**
     * Hard-delete van de bericht-rij op de surrogate PK. Caller MOET eerst de
     * child-rijen (bijlagen, status) verwijderen — FK is RESTRICT.
     */
    fun hardDeleteByDbId(berichtDbId: Long): Int =
        delete("id", berichtDbId).toInt()
```

- [ ] **Step 4: Run de test**

Run:
```bash
./mvnw -pl services/berichtenmagazijn -am test -Dtest='BerichtRetentieIntegrationTest' -q
```

Expected: PASS — 4 tests.

- [ ] **Step 5: Commit**

```bash
git add services/berichtenmagazijn/src/main/kotlin/nl/rijksoverheid/moz/fbs/berichtenmagazijn/opslag/BerichtRepository.kt \
        services/berichtenmagazijn/src/test/kotlin/nl/rijksoverheid/moz/fbs/berichtenmagazijn/opslag/BerichtRetentieIntegrationTest.kt
git commit -m "feat(magazijn): claimVoorHardDelete + hardDeleteByDbId op BerichtRepository"
```

---

## Task 7: `MAGAZIJN_RETENTIE` ProcessingActivity

**Files:**
- Modify: `services/berichtenmagazijn/src/main/kotlin/nl/rijksoverheid/moz/fbs/berichtenmagazijn/ProcessingActivities.kt`

- [ ] **Step 1: Voeg de constante toe**

Open `ProcessingActivities.kt`. Voeg ná `MAGAZIJN_BEHEER` toe:

```kotlin
    const val MAGAZIJN_RETENTIE = "$NAMESPACE:retentie"
```

- [ ] **Step 2: Commit**

```bash
git add services/berichtenmagazijn/src/main/kotlin/nl/rijksoverheid/moz/fbs/berichtenmagazijn/ProcessingActivities.kt
git commit -m "feat(magazijn): MAGAZIJN_RETENTIE processing-activity-id"
```

---

## Task 8: `HardDeleteTransactionalOps` (REQUIRES_NEW)

**Files:**
- Create: `services/berichtenmagazijn/src/main/kotlin/nl/rijksoverheid/moz/fbs/berichtenmagazijn/retention/HardDeleteTransactionalOps.kt`

- [ ] **Step 1: Schrijf de bean**

Maak het bestand:

```kotlin
package nl.rijksoverheid.moz.fbs.berichtenmagazijn.retention

import jakarta.enterprise.context.ApplicationScoped
import jakarta.transaction.Transactional
import nl.rijksoverheid.moz.fbs.berichtenmagazijn.opslag.BerichtRepository
import nl.rijksoverheid.moz.fbs.berichtenmagazijn.opslag.BerichtStatusRepository
import nl.rijksoverheid.moz.fbs.berichtenmagazijn.opslag.BijlageRepository

/**
 * Per-bericht-delete in een eigen sub-transactie (`REQUIRES_NEW`) zodat een
 * fout op één bericht de andere niet meesleurt. Aparte bean (geen methode op
 * [HardDeleteService]) omdat `@Transactional` op een Kotlin-methode alleen
 * werkt via een CDI-proxy — een interne `this.x()`-call zou de transactie
 * niet starten.
 *
 * Volgorde: bijlagen → status → bericht. FK's zijn RESTRICT, dus child-rijen
 * moeten vóór de parent weg.
 */
@ApplicationScoped
class HardDeleteTransactionalOps(
    private val bijlageRepository: BijlageRepository,
    private val statusRepository: BerichtStatusRepository,
    private val berichtRepository: BerichtRepository,
) {

    /** Retourneert het aantal verwijderde bericht-rijen (0 of 1). */
    @Transactional(Transactional.TxType.REQUIRES_NEW)
    fun deleteOne(candidate: HardDeleteCandidaat): Int {
        bijlageRepository.deleteByBerichtDbId(candidate.id)
        statusRepository.deleteByBerichtDbId(candidate.id)
        return berichtRepository.hardDeleteByDbId(candidate.id)
    }
}
```

- [ ] **Step 2: Compileer**

Run:
```bash
./mvnw -pl services/berichtenmagazijn -am compile -q
```

Expected: succes.

- [ ] **Step 3: Commit**

```bash
git add services/berichtenmagazijn/src/main/kotlin/nl/rijksoverheid/moz/fbs/berichtenmagazijn/retention/HardDeleteTransactionalOps.kt
git commit -m "feat(magazijn): HardDeleteTransactionalOps voor per-bericht REQUIRES_NEW"
```

---

## Task 9: `HardDeleteLdvLogger` (LDV-wrapper, request-context-activated)

**Files:**
- Create: `services/berichtenmagazijn/src/main/kotlin/nl/rijksoverheid/moz/fbs/berichtenmagazijn/retention/HardDeleteLdvLogger.kt`

- [ ] **Step 1: Schrijf de bean**

Maak het bestand:

```kotlin
package nl.rijksoverheid.moz.fbs.berichtenmagazijn.retention

import io.quarkus.arc.runtime.InterceptorBindings
import jakarta.enterprise.context.ApplicationScoped
import jakarta.enterprise.context.control.ActivateRequestContext
import nl.mijnoverheidzakelijk.ldv.logboekdataverwerking.Logboek
import nl.mijnoverheidzakelijk.ldv.logboekdataverwerking.LogboekContext
import nl.rijksoverheid.moz.fbs.berichtenmagazijn.ProcessingActivities

/**
 * Schrijft één LDV-record per hard-delete. Wrapper omdat:
 *  1. `LogboekContext` is `@RequestScoped` — de scheduler-thread heeft geen
 *     actieve request, dus we activeren er handmatig één met
 *     `@ActivateRequestContext`.
 *  2. `@Logboek` werkt via een CDI-interceptor; die schiet alleen aan bij
 *     een proxy-call, dus de methode moet op een aparte bean staan (niet
 *     intern in [HardDeleteService]).
 *
 * `dataSubjectId = ontvangerWaarde` is toegestaan zolang het LDV-endpoint TLS
 * gebruikt (BIO 13.2.1 / CLAUDE.md "BSN/PII-handling"); `LdvEndpointValidator`
 * uit `fbs-common` dwingt dit al af in %prod/%staging/%acceptatie.
 */
@ApplicationScoped
class HardDeleteLdvLogger(
    private val logboekContext: LogboekContext,
) {

    @ActivateRequestContext
    @Logboek(
        name = "hard-delete-bericht",
        processingActivityId = ProcessingActivities.MAGAZIJN_RETENTIE,
    )
    fun logHardDelete(candidate: HardDeleteCandidaat) {
        logboekContext.dataSubjectId = candidate.ontvangerWaarde
        logboekContext.dataSubjectType = candidate.ontvangerType
        // De Logboek-interceptor leest de context bij span-close; daarvoor moet
        // de waarde gezet zijn vóór deze methode return.
    }
}
```

- [ ] **Step 2: Verifieer dat `InterceptorBindings`-import niet nodig is**

Als de bovenstaande import niet gebruikt is, verwijder de regel `import io.quarkus.arc.runtime.InterceptorBindings`. Compile-check:

Run:
```bash
./mvnw -pl services/berichtenmagazijn -am compile -q
```

Expected: succes (eventueel met warning over ongebruikte import — verwijder).

- [ ] **Step 3: Commit**

```bash
git add services/berichtenmagazijn/src/main/kotlin/nl/rijksoverheid/moz/fbs/berichtenmagazijn/retention/HardDeleteLdvLogger.kt
git commit -m "feat(magazijn): HardDeleteLdvLogger met @Logboek + @ActivateRequestContext"
```

---

## Task 10: `HardDeleteService` — unit tests + implementatie (TDD)

**Files:**
- Create: `services/berichtenmagazijn/src/test/kotlin/nl/rijksoverheid/moz/fbs/berichtenmagazijn/retention/HardDeleteServiceTest.kt`
- Create: `services/berichtenmagazijn/src/main/kotlin/nl/rijksoverheid/moz/fbs/berichtenmagazijn/retention/HardDeleteService.kt`

- [ ] **Step 1: Schrijf de unit tests**

Maak `HardDeleteServiceTest.kt`:

```kotlin
package nl.rijksoverheid.moz.fbs.berichtenmagazijn.retention

import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import nl.rijksoverheid.moz.fbs.berichtenmagazijn.opslag.BerichtRepository
import org.junit.jupiter.api.Test
import java.time.Instant
import java.time.Period
import java.util.UUID
import kotlin.test.assertEquals

class HardDeleteServiceTest {

    private val berichtRepository = mockk<BerichtRepository>()
    private val ops = mockk<HardDeleteTransactionalOps>(relaxed = true)
    private val ldv = mockk<HardDeleteLdvLogger>(relaxed = true)
    private val config = mockk<RetentionConfig> {
        every { minimaleLeeftijd() } returns Period.ofYears(7)
        every { minimaleSoftDeleteLeeftijd() } returns Period.ofYears(7)
        every { batchGrootte() } returns 2
        every { cron() } returns "0 0 3 * * ?"
    }

    private val service = HardDeleteService(berichtRepository, ops, ldv, config)

    private fun kandidaat(id: Long = 1L) = HardDeleteCandidaat(
        id = id,
        berichtId = UUID.randomUUID(),
        ontvangerType = "BSN",
        ontvangerWaarde = "000000012",
        tijdstipOntvangst = Instant.now().minusSeconds(3000L * 86400),
        verwijderdOp = Instant.now().minusSeconds(3000L * 86400),
    )

    @Test
    fun `lege claim — geen deletes`() {
        every { berichtRepository.claimVoorHardDelete(any(), any(), any()) } returns emptyList()

        val result = service.run()

        assertEquals(0, result.totaalVerwijderd)
        verify(exactly = 0) { ops.deleteOne(any()) }
        verify(exactly = 0) { ldv.logHardDelete(any()) }
    }

    @Test
    fun `claim van 2 — beide verwijderd en gelogd`() {
        val a = kandidaat(1L)
        val b = kandidaat(2L)
        every { ops.deleteOne(any()) } returns 1
        every { berichtRepository.claimVoorHardDelete(any(), any(), any()) } returnsMany listOf(
            listOf(a, b),
            emptyList(),
        )

        val result = service.run()

        assertEquals(2, result.totaalVerwijderd)
        verify(exactly = 1) { ops.deleteOne(a) }
        verify(exactly = 1) { ops.deleteOne(b) }
        verify(exactly = 1) { ldv.logHardDelete(a) }
        verify(exactly = 1) { ldv.logHardDelete(b) }
    }

    @Test
    fun `delete-failure op één bericht — overige worden verwerkt`() {
        val a = kandidaat(1L)
        val b = kandidaat(2L)
        every { ops.deleteOne(a) } throws RuntimeException("simuleer FK-violation")
        every { ops.deleteOne(b) } returns 1
        every { berichtRepository.claimVoorHardDelete(any(), any(), any()) } returnsMany listOf(
            listOf(a, b),
            emptyList(),
        )

        val result = service.run()

        assertEquals(1, result.totaalVerwijderd)
        assertEquals(1, result.fouten)
        verify(exactly = 1) { ldv.logHardDelete(b) }
        verify(exactly = 0) { ldv.logHardDelete(a) }
    }

    @Test
    fun `ldv-failure — bericht blijft als verwijderd geteld`() {
        val a = kandidaat(1L)
        every { ops.deleteOne(a) } returns 1
        every { ldv.logHardDelete(a) } throws RuntimeException("LDV down")
        every { berichtRepository.claimVoorHardDelete(any(), any(), any()) } returnsMany listOf(
            listOf(a),
            emptyList(),
        )

        val result = service.run()

        assertEquals(1, result.totaalVerwijderd)
        assertEquals(1, result.ldvFouten)
    }

    @Test
    fun `volle batch leidt tot tweede claim-ronde`() {
        val batchVol = listOf(kandidaat(1L), kandidaat(2L))
        every { ops.deleteOne(any()) } returns 1
        every { berichtRepository.claimVoorHardDelete(any(), any(), 2) } returnsMany listOf(
            batchVol,
            emptyList(),
        )

        val result = service.run()

        assertEquals(2, result.totaalVerwijderd)
        verify(exactly = 2) { berichtRepository.claimVoorHardDelete(any(), any(), 2) }
    }

    @Test
    fun `niet-volle batch stopt de loop`() {
        val onvol = listOf(kandidaat(1L))
        every { ops.deleteOne(any()) } returns 1
        every { berichtRepository.claimVoorHardDelete(any(), any(), 2) } returns onvol

        val result = service.run()

        assertEquals(1, result.totaalVerwijderd)
        verify(exactly = 1) { berichtRepository.claimVoorHardDelete(any(), any(), 2) }
    }

    @Test
    fun `MAX_PER_RUN stopt de loop ook bij volle batches`() {
        // Forceer batchGrootte=1 zodat we maar 1 per ronde verwerken, en simuleer
        // dat er meer dan MAX_PER_RUN rondes zouden kunnen draaien.
        every { config.batchGrootte() } returns 1
        every { ops.deleteOne(any()) } returns 1
        every { berichtRepository.claimVoorHardDelete(any(), any(), 1) } answers {
            listOf(kandidaat(System.nanoTime()))
        }

        val result = service.run()

        assertEquals(HardDeleteService.MAX_PER_RUN, result.totaalVerwijderd)
    }
}
```

- [ ] **Step 2: Run de tests om compileerfout te zien**

Run:
```bash
./mvnw -pl services/berichtenmagazijn -am test -Dtest='HardDeleteServiceTest' -q
```

Expected: FAIL — `HardDeleteService` bestaat nog niet.

- [ ] **Step 3: Implementeer `HardDeleteService`**

Maak `services/berichtenmagazijn/src/main/kotlin/nl/rijksoverheid/moz/fbs/berichtenmagazijn/retention/HardDeleteService.kt`:

```kotlin
package nl.rijksoverheid.moz.fbs.berichtenmagazijn.retention

import jakarta.enterprise.context.ApplicationScoped
import nl.rijksoverheid.moz.fbs.berichtenmagazijn.opslag.BerichtRepository
import org.jboss.logging.Logger
import java.time.Instant

/**
 * Orchestreert de retentie-run: claim batches via [BerichtRepository.claimVoorHardDelete],
 * verwijder per bericht via [HardDeleteTransactionalOps.deleteOne] en log één
 * LDV-record per bericht via [HardDeleteLdvLogger.logHardDelete].
 *
 * De loop stopt zodra:
 *  - een batch leeg is, of
 *  - een batch niet vol is (impliciet: minder kandidaten dan `batchGrootte` over), of
 *  - het totaal-geteld `MAX_PER_RUN` bereikt (veiligheidsplafond tegen ongelimiteerde runs).
 *
 * Multi-pod-veiligheid: [BerichtRepository.claimVoorHardDelete] gebruikt
 * `FOR UPDATE SKIP LOCKED`. Pods kunnen dus simultaan vuren maar claimen
 * disjuncte rij-sets. Een lege claim → andere pod is sneller / niets te doen.
 */
@ApplicationScoped
class HardDeleteService(
    private val berichtRepository: BerichtRepository,
    private val ops: HardDeleteTransactionalOps,
    private val ldv: HardDeleteLdvLogger,
    private val config: RetentionConfig,
) {

    private val log = Logger.getLogger(HardDeleteService::class.java)

    data class RunResultaat(
        val totaalVerwijderd: Int,
        val fouten: Int,
        val ldvFouten: Int,
        val durationMs: Long,
    )

    fun run(): RunResultaat {
        val start = Instant.now()
        val receiptDeadline = start.minus(config.minimaleLeeftijd())
        val softDeleteDeadline = start.minus(config.minimaleSoftDeleteLeeftijd())
        val batchSize = config.batchGrootte()

        var totaal = 0
        var fouten = 0
        var ldvFouten = 0

        loop@ while (totaal < MAX_PER_RUN) {
            val candidates = berichtRepository.claimVoorHardDelete(
                receiptDeadline = receiptDeadline,
                softDeleteDeadline = softDeleteDeadline,
                batchSize = remainingBatchSize(totaal, batchSize),
            )
            if (candidates.isEmpty()) break

            for (candidate in candidates) {
                val deletedRows = try {
                    ops.deleteOne(candidate)
                } catch (ex: Exception) {
                    log.errorf(
                        ex,
                        "hard-delete failed berichtId=%s ontvangerType=%s",
                        candidate.berichtId,
                        candidate.ontvangerType,
                    )
                    fouten++
                    continue
                }
                if (deletedRows == 0) {
                    log.warnf(
                        "hard-delete affected 0 rows berichtId=%s — overgeslagen door andere pod?",
                        candidate.berichtId,
                    )
                    continue
                }
                totaal++
                try {
                    ldv.logHardDelete(candidate)
                } catch (ex: Exception) {
                    log.errorf(
                        ex,
                        "LDV-write failed na hard-delete berichtId=%s ontvangerType=%s",
                        candidate.berichtId,
                        candidate.ontvangerType,
                    )
                    ldvFouten++
                }
                if (totaal >= MAX_PER_RUN) {
                    log.infof("hard-delete reached MAX_PER_RUN (%d) — restant in volgende cron-tick", MAX_PER_RUN)
                    break@loop
                }
            }
            if (candidates.size < batchSize) break
        }

        val durationMs = java.time.Duration.between(start, Instant.now()).toMillis()
        log.infof(
            "hard-delete run finished totaalVerwijderd=%d fouten=%d ldvFouten=%d durationMs=%d",
            totaal,
            fouten,
            ldvFouten,
            durationMs,
        )
        return RunResultaat(totaal, fouten, ldvFouten, durationMs)
    }

    private fun remainingBatchSize(totaal: Int, batchSize: Int): Int =
        minOf(batchSize, MAX_PER_RUN - totaal)

    companion object {
        /** Veiligheidsplafond: nooit meer dan dit aantal verwijderingen per cron-run. */
        const val MAX_PER_RUN = 100_000
    }
}
```

- [ ] **Step 4: Run de tests**

Run:
```bash
./mvnw -pl services/berichtenmagazijn -am test -Dtest='HardDeleteServiceTest' -q
```

Expected: PASS — 7 tests.

> Als de `MAX_PER_RUN`-test te traag is in CI (100k iteraties): vervang in de test `MAX_PER_RUN` gebruik door een aparte override-constructor parameter, óf accepteer dat de test ~paar seconden duurt. MockK-calls met `every…answers` zijn ~10 µs per call → 100k = ~1s; acceptabel.

- [ ] **Step 5: Commit**

```bash
git add services/berichtenmagazijn/src/main/kotlin/nl/rijksoverheid/moz/fbs/berichtenmagazijn/retention/HardDeleteService.kt \
        services/berichtenmagazijn/src/test/kotlin/nl/rijksoverheid/moz/fbs/berichtenmagazijn/retention/HardDeleteServiceTest.kt
git commit -m "feat(magazijn): HardDeleteService — claim-loop + LDV-orchestratie"
```

---

## Task 11: `HardDeleteJob` (`@Scheduled`)

**Files:**
- Create: `services/berichtenmagazijn/src/main/kotlin/nl/rijksoverheid/moz/fbs/berichtenmagazijn/retention/HardDeleteJob.kt`

- [ ] **Step 1: Schrijf de scheduler-bean**

Maak het bestand:

```kotlin
package nl.rijksoverheid.moz.fbs.berichtenmagazijn.retention

import io.quarkus.scheduler.Scheduled
import io.quarkus.scheduler.Scheduled.ConcurrentExecution
import jakarta.enterprise.context.ApplicationScoped

/**
 * Cron-entrypoint voor de hard-delete-job. Quarkus Scheduler leest de
 * cron-expressie uit `retentie.hard-delete.cron` (default dagelijks 03:00).
 *
 * `concurrentExecution = SKIP` zorgt dat een tweede tick binnen dezelfde JVM
 * niet start voordat de vorige klaar is. Cross-pod-veiligheid komt uit
 * `FOR UPDATE SKIP LOCKED` in [HardDeleteService].
 */
@ApplicationScoped
class HardDeleteJob(
    private val service: HardDeleteService,
) {

    @Scheduled(
        cron = "{retentie.hard-delete.cron}",
        concurrentExecution = ConcurrentExecution.SKIP,
        identity = "hard-delete-soft-deleted-berichten",
    )
    fun fire() {
        service.run()
    }
}
```

- [ ] **Step 2: Compile**

Run:
```bash
./mvnw -pl services/berichtenmagazijn -am compile -q
```

Expected: succes.

- [ ] **Step 3: Commit**

```bash
git add services/berichtenmagazijn/src/main/kotlin/nl/rijksoverheid/moz/fbs/berichtenmagazijn/retention/HardDeleteJob.kt
git commit -m "feat(magazijn): HardDeleteJob met @Scheduled cron-entrypoint"
```

---

## Task 12: Integratietest — end-to-end (Postgres + LDV-stub)

**Files:**
- Create: `services/berichtenmagazijn/src/test/kotlin/nl/rijksoverheid/moz/fbs/berichtenmagazijn/retention/HardDeleteJobIntegrationTest.kt`

- [ ] **Step 1: Schrijf de integratietest**

Maak het bestand:

```kotlin
package nl.rijksoverheid.moz.fbs.berichtenmagazijn.retention

import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import io.quarkus.test.InjectMock
import io.quarkus.test.junit.QuarkusTest
import jakarta.inject.Inject
import jakarta.persistence.EntityManager
import jakarta.transaction.Transactional
import nl.rijksoverheid.moz.fbs.berichtenmagazijn.opslag.Bericht
import nl.rijksoverheid.moz.fbs.berichtenmagazijn.opslag.BerichtRepository
import nl.rijksoverheid.moz.fbs.berichtenmagazijn.opslag.BerichtStatusPatch
import nl.rijksoverheid.moz.fbs.berichtenmagazijn.opslag.BerichtStatusRepository
import nl.rijksoverheid.moz.fbs.berichtenmagazijn.opslag.Bijlage
import nl.rijksoverheid.moz.fbs.berichtenmagazijn.opslag.BijlageRepository
import nl.rijksoverheid.moz.fbs.berichtenmagazijn.opslag.Identificatienummer
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID
import kotlin.test.assertEquals

@QuarkusTest
class HardDeleteJobIntegrationTest {

    @Inject lateinit var service: HardDeleteService
    @Inject lateinit var berichtRepository: BerichtRepository
    @Inject lateinit var bijlageRepository: BijlageRepository
    @Inject lateinit var statusRepository: BerichtStatusRepository
    @Inject lateinit var em: EntityManager

    @InjectMock lateinit var ldv: HardDeleteLdvLogger

    @BeforeEach
    @Transactional
    fun cleanup() {
        statusRepository.deleteAll()
        bijlageRepository.deleteAll()
        berichtRepository.deleteAll()
        every { ldv.logHardDelete(any()) } returns Unit
    }

    @Test
    fun `bericht met bijlagen en status, beide drempels gehaald, wordt volledig gewist`() {
        val berichtId = saveBerichtMetBijlagenEnStatus(
            ontvangstOffsetDagen = -3000,
            verwijderdOpOffsetDagen = -3000,
            aantalBijlagen = 2,
        )

        val result = service.run()

        assertEquals(1, result.totaalVerwijderd)
        assertEquals(null, berichtRepository.findIncludingDeleted(berichtId))
        assertEquals(0, bijlageRepository.metadataVoorBericht(berichtId).size)
        assertEquals(null, statusRepository.findByBerichtId(berichtId))
        verify(exactly = 1) { ldv.logHardDelete(match { it.berichtId == berichtId }) }
    }

    @Test
    fun `recent soft-deleted bericht blijft staan`() {
        val berichtId = saveBerichtMetBijlagenEnStatus(
            ontvangstOffsetDagen = -3000,
            verwijderdOpOffsetDagen = -1,
            aantalBijlagen = 0,
        )

        val result = service.run()

        assertEquals(0, result.totaalVerwijderd)
        assert(berichtRepository.findIncludingDeleted(berichtId) != null)
    }

    @Test
    fun `niet-soft-deleted bericht blijft staan ongeacht leeftijd`() {
        val berichtId = saveBerichtMetBijlagenEnStatus(
            ontvangstOffsetDagen = -3000,
            verwijderdOpOffsetDagen = null,
            aantalBijlagen = 0,
        )

        val result = service.run()

        assertEquals(0, result.totaalVerwijderd)
        assert(berichtRepository.findIncludingDeleted(berichtId) != null)
    }

    @Test
    fun `FIFO — oudste soft-delete eerst`() {
        val ouder = saveBerichtMetBijlagenEnStatus(-3000, -3000, 0)
        val jonger = saveBerichtMetBijlagenEnStatus(-3000, -2900, 0)

        // Pin batchGrootte=1 via service-aanroep: helaas niet via config-spy mogelijk
        // in @QuarkusTest zonder restart. We vertrouwen erop dat de default volgorde
        // klopt: één run met batchGrootte=1000 verwijdert beide, maar dan testen we
        // FIFO niet. Daarom: run twee keer met intern batchGrootte=1 via reflectie
        // OF: laat beide in één run weg en check enkel volgorde van LDV-calls.
        service.run()

        // Beide weg, maar LDV-call-volgorde respecteert FIFO:
        val order = mutableListOf<UUID>()
        verify {
            ldv.logHardDelete(match {
                order.add(it.berichtId)
                true
            })
        }
        assertEquals(listOf(ouder, jonger), order)
    }

    @Test
    fun `LDV-failure laat DB-delete intact en wordt gerapporteerd`() {
        val berichtId = saveBerichtMetBijlagenEnStatus(-3000, -3000, 0)
        every { ldv.logHardDelete(any()) } throws RuntimeException("LDV down")

        val result = service.run()

        assertEquals(1, result.totaalVerwijderd)
        assertEquals(1, result.ldvFouten)
        assertEquals(null, berichtRepository.findIncludingDeleted(berichtId))
    }

    private fun saveBerichtMetBijlagenEnStatus(
        ontvangstOffsetDagen: Long,
        verwijderdOpOffsetDagen: Long?,
        aantalBijlagen: Int,
    ): UUID {
        val berichtId = UUID.randomUUID()
        val bijlagen = (1..aantalBijlagen).map {
            Bijlage(
                bijlageId = UUID.randomUUID(),
                berichtId = berichtId,
                naam = "b-$it.txt",
                mimeType = "text/plain",
                content = "x".toByteArray(),
            )
        }
        val bericht = Bericht(
            berichtId = berichtId,
            afzender = "00000000000000000001",
            ontvanger = Identificatienummer.bsn("000000012"),
            onderwerp = "T",
            inhoud = "I",
            tijdstipOntvangst = Instant.now(),
            bijlagen = bijlagen.map { it.toMetadata() },
            status = null,
        )
        runInTx {
            berichtRepository.save(bericht)
            bijlagen.forEach { bijlageRepository.save(it) }
            statusRepository.upsert(
                berichtId = berichtId,
                patch = BerichtStatusPatch(gelezen = false, map = null),
                tijdstip = Instant.now(),
            )

            val ontvangst = Instant.now().plus(ontvangstOffsetDagen, ChronoUnit.DAYS)
            em.createNativeQuery("UPDATE berichten SET tijdstip_ontvangst = :t WHERE bericht_id = :id")
                .setParameter("t", ontvangst)
                .setParameter("id", berichtId)
                .executeUpdate()
            if (verwijderdOpOffsetDagen != null) {
                val v = Instant.now().plus(verwijderdOpOffsetDagen, ChronoUnit.DAYS)
                em.createNativeQuery("UPDATE berichten SET verwijderd_op = :v WHERE bericht_id = :id")
                    .setParameter("v", v)
                    .setParameter("id", berichtId)
                    .executeUpdate()
            }
            em.flush()
            em.clear()
        }
        return berichtId
    }

    /** Helper omdat @Transactional op private methodes niet werkt via CDI-proxy. */
    @Transactional
    open fun runInTx(block: () -> Unit) = block()
}
```

> **Let op:** `@InjectMock` van `HardDeleteLdvLogger` vereist dat de bean niet `final` is — Kotlin classes zijn dat per default niet door de `all-open`-plugin (vanwege `@ApplicationScoped`), dus dit zou moeten werken. Als toch nodig: voeg `open class` toe.

- [ ] **Step 2: Run de test**

Run:
```bash
./mvnw -pl services/berichtenmagazijn -am test -Dtest='HardDeleteJobIntegrationTest' -q
```

Expected: PASS — 5 tests.

> Bij stipper Quarkus Dev Services-issues (Postgres start traag): zet `quarkus.test.continuous-testing=disabled` voor de run.

- [ ] **Step 3: Commit**

```bash
git add services/berichtenmagazijn/src/test/kotlin/nl/rijksoverheid/moz/fbs/berichtenmagazijn/retention/HardDeleteJobIntegrationTest.kt
git commit -m "test(magazijn): HardDeleteJobIntegrationTest end-to-end met Postgres"
```

---

## Task 13: Concurrency-test — `FOR UPDATE SKIP LOCKED`

**Files:**
- Create: `services/berichtenmagazijn/src/test/kotlin/nl/rijksoverheid/moz/fbs/berichtenmagazijn/retention/HardDeleteConcurrencyTest.kt`

- [ ] **Step 1: Schrijf de test**

Maak het bestand:

```kotlin
package nl.rijksoverheid.moz.fbs.berichtenmagazijn.retention

import io.mockk.every
import io.quarkus.test.InjectMock
import io.quarkus.test.junit.QuarkusTest
import jakarta.inject.Inject
import jakarta.persistence.EntityManager
import jakarta.transaction.Transactional
import nl.rijksoverheid.moz.fbs.berichtenmagazijn.opslag.Bericht
import nl.rijksoverheid.moz.fbs.berichtenmagazijn.opslag.BerichtRepository
import nl.rijksoverheid.moz.fbs.berichtenmagazijn.opslag.BerichtStatusRepository
import nl.rijksoverheid.moz.fbs.berichtenmagazijn.opslag.BijlageRepository
import nl.rijksoverheid.moz.fbs.berichtenmagazijn.opslag.Identificatienummer
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals

@QuarkusTest
class HardDeleteConcurrencyTest {

    @Inject lateinit var service: HardDeleteService
    @Inject lateinit var berichtRepository: BerichtRepository
    @Inject lateinit var bijlageRepository: BijlageRepository
    @Inject lateinit var statusRepository: BerichtStatusRepository
    @Inject lateinit var em: EntityManager

    @InjectMock lateinit var ldv: HardDeleteLdvLogger

    @BeforeEach
    @Transactional
    fun cleanup() {
        statusRepository.deleteAll()
        bijlageRepository.deleteAll()
        berichtRepository.deleteAll()
        every { ldv.logHardDelete(any()) } returns Unit
    }

    @Test
    fun `twee parallele runs verwijderen elk bericht hooguit eens`() {
        // 10 kandidaten — beide drempels ruim gehaald.
        repeat(10) { saveOudKandidaat() }

        val pool = Executors.newFixedThreadPool(2)
        val r1 = pool.submit { service.run() }
        val r2 = pool.submit { service.run() }
        val resultaat1 = r1.get(60, TimeUnit.SECONDS)
        val resultaat2 = r2.get(60, TimeUnit.SECONDS)
        pool.shutdown()

        assertEquals(10, resultaat1.totaalVerwijderd + resultaat2.totaalVerwijderd,
            "Som van beide runs moet exact 10 zijn (geen overlap, geen verlies)")
        assertEquals(0, resultaat1.fouten + resultaat2.fouten)
    }

    private fun saveOudKandidaat() {
        val berichtId = UUID.randomUUID()
        val bericht = Bericht(
            berichtId = berichtId,
            afzender = "00000000000000000001",
            ontvanger = Identificatienummer.bsn("000000012"),
            onderwerp = "T",
            inhoud = "I",
            tijdstipOntvangst = Instant.now(),
            bijlagen = emptyList(),
            status = null,
        )
        runInTx {
            berichtRepository.save(bericht)
            val ts = Instant.now().minus(3000, ChronoUnit.DAYS)
            em.createNativeQuery(
                "UPDATE berichten SET tijdstip_ontvangst = :t, verwijderd_op = :v WHERE bericht_id = :id",
            )
                .setParameter("t", ts)
                .setParameter("v", ts)
                .setParameter("id", berichtId)
                .executeUpdate()
            em.flush()
            em.clear()
        }
    }

    @Transactional
    open fun runInTx(block: () -> Unit) = block()
}
```

- [ ] **Step 2: Run de test**

Run:
```bash
./mvnw -pl services/berichtenmagazijn -am test -Dtest='HardDeleteConcurrencyTest' -q
```

Expected: PASS — 1 test, beide runs samen 10 deletes.

- [ ] **Step 3: Commit**

```bash
git add services/berichtenmagazijn/src/test/kotlin/nl/rijksoverheid/moz/fbs/berichtenmagazijn/retention/HardDeleteConcurrencyTest.kt
git commit -m "test(magazijn): HardDeleteConcurrencyTest verifieert SKIP LOCKED"
```

---

## Task 14: Volledige test-run + coverage-check

- [ ] **Step 1: Run alle magazijn-tests**

Run:
```bash
./mvnw -pl services/berichtenmagazijn -am test -q
```

Expected: alle tests PASS, JaCoCo `check` slaagt met ≥ 90% line coverage.

- [ ] **Step 2: Bij coverage-failure — bekijk JaCoCo-rapport**

Run:
```bash
open services/berichtenmagazijn/target/jacoco-report/index.html
```

Of inspecteer het rapport in CLI. Vermijd het toevoegen van `# noqa`-uitsluitingen — schrijf liever een ontbrekende test.

- [ ] **Step 3: Spec/branch-check**

Run:
```bash
git log --oneline main..HEAD
```

Verifieer dat alle 13 voorgaande tasks elk een commit hebben opgeleverd.

- [ ] **Step 4: Geen extra commit nodig**

Als alles groen is: door naar de PR.

---

## Task 15: Pull Request aanmaken

- [ ] **Step 1: Push branch**

Run:
```bash
git push -u origin feature/berichtenmagazijn-ophaal-beheer-api
```

> Branch heeft een minder ideale naam voor dit werk, maar deze sessie bouwt voort op de bestaande branch. Als de gebruiker liever een aparte branch (bv. `feature/hard-delete-job`) wil: vraag eerst voor je een nieuwe branch aanmaakt.

- [ ] **Step 2: Maak PR (geen reviewer)**

```bash
gh pr create --title "feat(magazijn): hard-delete-job voor soft-deleted berichten (#58)" --body "$(cat <<'EOF'
## Summary
- Quarkus Scheduler-job die soft-deleted berichten ouder dan de configureerbare Archiefwet-bewaartermijn (default 7 jaar) fysiek verwijdert
- Twee onafhankelijke drempels: leeftijd vanaf ontvangst én vanaf soft-delete
- Multi-pod-veilig via `FOR UPDATE SKIP LOCKED`; per-bericht `REQUIRES_NEW`-transactie + LDV-record na commit

Closes #58.

## Test plan
- [ ] `./mvnw -pl services/berichtenmagazijn -am test` lokaal groen
- [ ] JaCoCo coverage ≥ 90%
- [ ] CI groen
EOF
)"
```

- [ ] **Step 3: Volg CI**

Run:
```bash
gh pr checks
```

Bij falen:
```bash
gh run view --log-failed
```

---

## Self-Review (uitgevoerd vóór save)

**Spec coverage:**
- Architectuur (Sectie "Architectuur" in spec) → Tasks 8, 9, 10, 11 ✅
- Config (Sectie "Configuratie") → Task 3 ✅
- Dataflow (claim + deleteOne + LDV) → Tasks 6 (claim), 8 (deleteOne), 9 (LDV), 10 (orchestratie) ✅
- Schema-migratie V4 → Task 2 ✅
- Error handling per bericht/per run → unit tests in Task 10 + integratietest in Task 12 ✅
- BSN/PII (LDV-endpoint TLS via fbs-common) → spec-tekst, niet expliciet getest; bestaande `LdvEndpointValidator` dekt dit af ✅
- Multi-instance veiligheid → Task 13 (concurrency) ✅
- Tests (unit, integratie, concurrency) → Tasks 10, 12, 13 ✅
- Verificatie-checklist uit spec → Task 14 ✅

**Placeholder scan:** geen TBD/TODO/"similar to". Een handful `> Let op:` notities zijn expliciette caveats, niet placeholders.

**Type-consistentie:**
- `HardDeleteCandidaat`-velden: `id: Long`, `berichtId: UUID`, `ontvangerType: String`, `ontvangerWaarde: String`, `tijdstipOntvangst: Instant`, `verwijderdOp: Instant` — consistent gebruikt in Tasks 4, 6, 8, 9, 10.
- `claimVoorHardDelete(receiptDeadline, softDeleteDeadline, batchSize)` — consistent in Tasks 6, 10.
- `RunResultaat(totaalVerwijderd, fouten, ldvFouten, durationMs)` — consistent in Tasks 10, 12, 13.
- `HardDeleteService.MAX_PER_RUN` — Task 10 definieert, Task 10-test gebruikt.
