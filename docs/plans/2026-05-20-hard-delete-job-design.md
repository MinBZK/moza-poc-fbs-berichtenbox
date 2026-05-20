**Status:** Uitgevoerd

# Hard-delete-job voor soft-deleted berichten (Issue #58)

## Context

`berichtenmagazijn` markeert berichten als soft-deleted via `berichten.verwijderd_op` (V2-migratie). Rijen blijven fysiek aanwezig zodat audit en herstel mogelijk blijven; ophaal-endpoints filteren `verwijderd_op IS NULL` weg.

Er bestaat nog geen retention-job die soft-deleted berichten na een bewaartermijn fysiek verwijdert. Dit ontwerp introduceert die job en maakt de minimale bewaartermijn configureerbaar — een vereiste vanuit de Archiefwet en de geldende selectielijst, die meerdere jaren voorschrijven.

FK's op `bijlagen` en `bericht_status` zijn RESTRICT (geen `ON DELETE CASCADE`); de job moet child-rijen daarom in de juiste volgorde opruimen.

## Doel

Een achtergrond-job die periodiek soft-deleted berichten fysiek verwijdert zodra ze aan twee onafhankelijke bewaardrempels voldoen, en die elke verwijdering vastlegt in LDV (Logboek Dataverwerkingen).

Niet in scope:
- Een REST-endpoint om de job handmatig te triggeren.
- Het terughalen ("undelete") van een soft-deleted bericht — bestaat al niet en blijft buiten scope.
- Retentie voor niet-soft-deleted (actieve) berichten — Archiefwet kent ook een maximum-bewaartermijn, maar dat valt buiten Issue #58.

## Architectuur

### Nieuwe componenten (package `nl.rijksoverheid.moz.fbs.berichtenmagazijn.retention`)

- **`RetentionConfig`** — `@ConfigMapping(prefix = "retentie.hard-delete")`-interface die properties bundelt en bij startup valideert.
- **`HardDeleteJob`** — CDI-bean met `@Scheduled(cron = "...", concurrentExecution = SKIP)` die per cron-tick `HardDeleteService.run()` aanroept.
- **`HardDeleteService`** — orchestreert claim + delete + LDV-log per bericht. Niet `@Transactional` op service-niveau; transactie-grenzen liggen één laag dieper.
- **`HardDeleteCandidaat`** — interne data class met `id: Long`, `berichtId: UUID`, `ontvangerType: String`, `ontvangerWaarde: String`, `tijdstipOntvangst: Instant`, `verwijderdOp: Instant` — projectie van de claim-query.

### Wijzigingen aan bestaande repositories

Drie bestaande repositories krijgen één extra methode:

- `BerichtRepository.claimVoorHardDelete(receiptDeadline, softDeleteDeadline, batchSize): List<HardDeleteCandidaat>` — native query met `FOR UPDATE SKIP LOCKED`.
- `BerichtRepository.hardDeleteByDbId(dbId: Long): Int` — bulk-DELETE.
- `BijlageRepository.deleteByBerichtDbId(berichtDbId: Long): Int`.
- `BerichtStatusRepository.deleteByBerichtDbId(berichtDbId: Long): Int`.

### Schema-migratie V4

De bestaande index `idx_berichten_ontvanger_actief` (V3) is een partial index met `WHERE verwijderd_op IS NULL` en dekt de claim-query daarom niet. Een nieuwe V4-migratie voegt een partial index toe op het inverse predicaat:

```sql
-- V4__hard_delete_index.sql
CREATE INDEX idx_berichten_retentie_kandidaat
    ON berichten (verwijderd_op, tijdstip_ontvangst)
    WHERE verwijderd_op IS NOT NULL;
```

Bijbehorend rollback-script onder `src/main/resources/db/rollback/V4__hard_delete_index.sql` (`DROP INDEX idx_berichten_retentie_kandidaat;`). Index is partial om compact te blijven — actieve berichten (het overgrote deel) zitten er niet in.

### Nieuwe Maven-dependency

```xml
<dependency>
  <groupId>io.quarkus</groupId>
  <artifactId>quarkus-scheduler</artifactId>
</dependency>
```

## Configuratie

Vier nieuwe properties in `services/berichtenmagazijn/src/main/resources/application.properties`:

```properties
# Bewaartermijn vanaf tijdstip_ontvangst. Default 7 jaar (administratieve standaard).
# MOET door operators afgestemd worden op de geldende selectielijst (Archiefwet).
retentie.hard-delete.minimale-leeftijd=P7Y

# Bewaartermijn vanaf verwijderd_op (soft-delete-coulance). Default identiek.
retentie.hard-delete.minimale-soft-delete-leeftijd=P7Y

# Cron-expressie (Quarkus Scheduler dialect, seconden-precies).
# Default: dagelijks 03:00, off-peak om locks/IO laag te houden.
retentie.hard-delete.cron=0 0 3 * * ?

# Max berichten per run. Job stopt bij batch-leeg of bij bereiken limiet
# (volgende cron-tick pikt eventuele overgebleven kandidaten op).
retentie.hard-delete.batch-grootte=1000
```

Bewaartermijnen zijn ISO-8601 `Period`-strings (`P7Y`, `P3M`, `P90D`). `Duration` (`PT…`) wordt niet ondersteund — bewaartermijnen zijn altijd in dagen/maanden/jaren, niet uren. `RetentionConfig` parseert en valideert dit bij startup; ongeldige waardes voorkomen dat de service start.

Geen feature-flag — de job draait altijd, ook in dev/test. Dat is acceptabel omdat (a) een lege test-DB geen kandidaten heeft, (b) cron `0 0 3 * * ?` zelden binnen een test-run valt, (c) tests die de job zelf onderzoeken roepen `HardDeleteService.run()` direct aan in plaats van te wachten op cron.

## Dataflow per job-run

```
HardDeleteJob.@Scheduled (cron)
  └── HardDeleteService.run()
        │
        │  receiptDeadline    = Instant.now() - minimaleLeeftijd
        │  softDeleteDeadline = Instant.now() - minimaleSoftDeleteLeeftijd
        │  totaal              = 0
        │
        ├── loop:
        │     claimed = claimBatch(batchGrootte, receiptDeadline, softDeleteDeadline)
        │     if claimed.isEmpty() → break
        │     claimed.forEach { deleteOne(it) }
        │     totaal += claimed.size
        │     if claimed.size < batchGrootte → break    // batch niet vol = geen meer kandidaten
        │     if totaal >= MAX_PER_RUN → break           // veiligheidsplafond (zie hieronder)
        │
        └── log "hard-delete run finished: totaal={totaal} durationMs={…}"
```

### claimBatch (één DB-transactie)

```sql
SELECT id, bericht_id, ontvanger_type, ontvanger_waarde,
       tijdstip_ontvangst, verwijderd_op
FROM berichten
WHERE verwijderd_op IS NOT NULL
  AND verwijderd_op       <= :softDeleteDeadline
  AND tijdstip_ontvangst  <= :receiptDeadline
ORDER BY verwijderd_op ASC
LIMIT :batchSize
FOR UPDATE SKIP LOCKED;
```

`FOR UPDATE SKIP LOCKED` zorgt dat parallelle pods niet dezelfde rijen claimen. `ORDER BY verwijderd_op ASC` garandeert FIFO-retentie: oudste-soft-deleted eerst weg, ook bij een drukke backlog.

De gekozen rij-IDs worden onthouden in de service; rijen blijven gelockt zolang deze claim-transactie open is. Direct na de claim opent `deleteOne` een eigen sub-transactie per bericht — daarom moet de claim-transactie blijven openstaan totdat de hele batch verwerkt is. Implementatie: één `EntityManager`-transactie omarmt de `forEach { deleteOne }`-loop, met sub-transacties via `@TransactionAttribute(REQUIRES_NEW)` op `deleteOne`. Bij commit van de buitenste transactie worden de row-locks vrijgegeven, ongeacht uitkomst per bericht.

### deleteOne (orchestreert sub-transactie + LDV)

`deleteOne(candidate)` is een *niet*-transactionele methode op `HardDeleteService` die twee dingen na elkaar doet:

```
1. deleteOneTransactional(candidate)   // @Transactional(REQUIRES_NEW), commit op return
     a. bijlageRepository.deleteByBerichtDbId(candidate.id)
     b. statusRepository.deleteByBerichtDbId(candidate.id)
     c. berichtRepository.hardDeleteByDbId(candidate.id)

2. ldv.log(VerwerkingHardDelete(
     dataSubjectId = candidate.ontvangerWaarde,
     extra = {
       "berichtId"         : candidate.berichtId,
       "ontvangerType"     : candidate.ontvangerType,
       "verwijderdOp"      : candidate.verwijderdOp,
       "tijdstipOntvangst" : candidate.tijdstipOntvangst,
       "reden"             : "retentie-verlopen"
     }))
```

LDV-write gebeurt expliciet ná de DB-commit (stap 2 staat buiten elke transactie-scope). Bij LDV-failure is het bericht al weg — alternatief (outbox-tabel met retry) is voor PoC overkill. Foutgedrag: zie volgende sectie.

Implementatienotitie: omdat `@Transactional` op een Kotlin-methode alleen werkt bij CDI-proxy-doorroep (niet bij `this.foo()`), wordt `deleteOneTransactional` een aparte `@ApplicationScoped`-bean (`HardDeleteTransactionalOps`) die in `HardDeleteService` wordt geïnjecteerd.

### Veiligheidsplafond `MAX_PER_RUN`

Een interne constante `MAX_PER_RUN = 100_000`. Het claim-loop stopt na deze grens, zelfs als batches vol blijven. Reden: bij een eerste run op een grote backlog (jaren niet gedraaid) wil je niet dat de scheduler-thread uren bezig blijft. Volgende cron-tick pikt het restant op. Deze grens is hard-coded omdat het puur een veiligheidsklep is, niet operationeel afstembaar.

## Error handling

### Per bericht (binnen `deleteOne`)

| Scenario | Gedrag |
|---|---|
| FK-violation op `bijlagen` / `bericht_status` | Hoort niet voor te komen omdat we ze eerst verwijderen. Toch: sub-transactie rolt terug, `ERROR` met `berichtId`, run gaat door met volgend bericht. |
| Rij-count na `hardDeleteByDbId` is 0 | Sub-transactie rolt terug, `WARN` met `berichtId`, run gaat door. Verschijnt alleen bij datacorruptie of bij een race die `SKIP LOCKED` niet had moeten toelaten. |
| Andere `Exception` tijdens delete | Sub-transactie rolt terug, `ERROR` met stack-trace + `berichtId`, run gaat door. Eén kapot bericht blokkeert nooit de hele run. |
| LDV-write faalt | Bericht is al weg (commit gebeurd). `ERROR` met `berichtId` + `ontvangerType`, **zonder** `ontvangerWaarde` (BSN/PII-handling: type-only). Geen retry. |

### Per job-run

| Scenario | Gedrag |
|---|---|
| DB onbereikbaar bij `claimBatch` | Uncaught exception escaleert; Quarkus Scheduler logt zelf. Volgende cron-tick (24u later) probeert opnieuw. |
| Job-run duurt langer dan cron-interval | `concurrentExecution = SKIP` voorkomt dubbel-start binnen dezelfde JVM. Cross-pod: `FOR UPDATE SKIP LOCKED` voorkomt rij-overlap; één extra LDV-error bij race is acceptabel. |
| Pod-restart midden in run | Buitenste transactie rolt terug; row-locks worden vrijgegeven door verbinding-loss. Volgende cron-tick claimt opnieuw. |
| `batch-grootte` bereikt, run stopt | `INFO` met `"reached batch limit, X messages remaining within retention window may be processed next run"`. Ops kan cron-cadance verhogen of `batch-grootte` aanpassen. |
| `MAX_PER_RUN` bereikt | `INFO` met aantal verwerkt + `"global run limit reached, continuing next tick"`. |
| `minimale-leeftijd` / `cron` ongeldig | Startup-failure via `@ConfigMapping`-validatie. Liever geen service dan een verkeerde drempel. |

## BSN/PII-handling

- LDV-record bevat `dataSubjectId = ontvangerWaarde` (BSN/RSIN/KVK/OIN); dit is conform CLAUDE.md mits het LDV-endpoint TLS gebruikt. `LdvEndpointValidator` in `fbs-common` dwingt dit al af in `%prod`/`%staging`/`%acceptatie` — geen nieuwe controle nodig.
- Applicatie-logs (`Logger`) bevatten **alleen** `berichtId`, `ontvangerType`, `verwijderdOp`, `tijdstipOntvangst`. Nooit `ontvangerWaarde`. Conform CLAUDE.md "BSN nooit in applicatie-logs".
- `claimBatch`-query bevat geen WHERE-clauses op BSN-waardes; geen risico op SQL-log-lekken via `quarkus.hibernate-orm.log.sql`.

## Multi-instance veiligheid

Twee mechanismen samen:

1. **Binnen één JVM:** `@Scheduled(concurrentExecution = SKIP)` voorkomt dubbel-start.
2. **Tussen JVMs (pods):** `FOR UPDATE SKIP LOCKED` op de claim-query. Pods kunnen tegelijk vuren maar claimen disjuncte rij-sets. Pods die geen rijen krijgen lopen leeg met `claimed.isEmpty()` en stoppen direct.

Geen Postgres advisory lock nodig; rij-niveau locking is fijner-grained en heeft betere throughput bij hoge replica-counts.

## Tests

### Unit tests (MockK, geen Quarkus)

`HardDeleteServiceTest`:

- `claimBatch geeft lege lijst → run stopt zonder deletes`
- `claimBatch geeft 3 records → deleteOne 3x, LDV 3x`
- `deleteOne werpt RuntimeException op bericht 2 → bericht 1 en 3 wél verwerkt, ERROR-log`
- `LDV-call faalt → bericht is gewist, ERROR-log met type maar zonder waarde`
- `claimBatch retourneert exact batchSize → tweede claim-ronde wordt gestart`
- `claimBatch retourneert minder dan batchSize → run stopt`
- `MAX_PER_RUN bereikt → run stopt met INFO-log`
- `drempel-berekening klopt`: verify `claimBatch(receiptDeadline, softDeleteDeadline, batchSize)` met `now - minimaleLeeftijd` resp. `now - minimaleSoftDeleteLeeftijd`

`RetentionConfigTest`:

- `P7Y` → `Period.ofYears(7)`
- `PT24H` (Duration) → startup-failure
- Lege string → startup-failure
- Negatieve waarde → startup-failure (`P-1Y` mag niet)

### Integratietests (`@QuarkusTest` + Testcontainers Postgres)

`HardDeleteJobIntegrationTest`:

| Scenario | Verwacht |
|---|---|
| Bericht `tijdstip_ontvangst = now - 8j`, `verwijderd_op = now - 8j`, 2 bijlagen + status → `run()` | Alle 4 rijen (1 bericht, 2 bijlagen, 1 status) weg |
| Bericht `tijdstip_ontvangst = now - 8j`, `verwijderd_op = now - 1d` | Blijft staan (soft-delete-drempel niet gehaald) |
| Bericht `tijdstip_ontvangst = now - 1d`, `verwijderd_op = now - 8j` | Blijft staan (receipt-drempel niet gehaald) |
| Bericht `verwijderd_op IS NULL`, `tijdstip_ontvangst = now - 8j` | Blijft staan (niet soft-deleted) |
| `batch-grootte = 2`, drie kandidaten | Eerste run wist 2, tweede run wist 1 |
| FIFO: 3 kandidaten met `verwijderd_op` -10j / -9j / -8j, `batch-grootte=1` | Eerste run wist -10j, tweede -9j, derde -8j |
| LDV-bean spy | Voor elk gewist bericht één LDV-record met juiste `dataSubjectId` en `berichtId` |
| LDV-bean werpt exception | DB-deletes succesvol, ERROR gelogd, run gaat door |

### Concurrency-test (`@QuarkusTest`)

`HardDeleteConcurrencyTest`:

- 10 kandidaten in DB, `batch-grootte=10`, twee threads roepen `service.run()` tegelijk → alle 10 weg, geen dubbele deletes, totaal 10 LDV-records, geen exceptions.

### Wat we niet testen

- Echte `@Scheduled`-firing — Quarkus-eigen, vertrouwd. Tests roepen `run()` direct aan.
- 7-jaar wallclock — test-helper zet `tijdstip_ontvangst` en `verwijderd_op` direct in het verleden via SQL-update na save.

JaCoCo 90% line coverage staat al; deze testen dragen ruim bij. Geen wijziging aan coverage-config.

## Open punten

- **Eerste run na deploy**: bij oplevering staan er nog geen jaren oude soft-deleted berichten in productie (PoC). Backlog-overweging is dus theoretisch; toch is `MAX_PER_RUN` als veiligheidsklep ingebouwd.
- **Metrieken (Prometheus)**: zou nuttig zijn (`hard_delete_messages_total`, `hard_delete_run_duration_seconds`). Buiten scope van deze spec; vervolgwerk.
- **Eindigheid van LDV-records**: LDV-records zelf kennen ook een bewaartermijn (los traject). Buiten scope.

## Verificatie

Definitie van klaar:

- [ ] `quarkus-scheduler` toegevoegd aan `services/berichtenmagazijn/pom.xml`
- [ ] V4-migratie (`db/migration/V4__hard_delete_index.sql`) + rollback (`db/rollback/V4__hard_delete_index.sql`)
- [ ] `RetentionConfig`, `HardDeleteJob`, `HardDeleteService`, `HardDeleteTransactionalOps` aangemaakt
- [ ] Repository-methoden toegevoegd (claim + delete op bericht, bijlage, status)
- [ ] Vier properties in `application.properties`
- [ ] Unit tests + integratietests groen
- [ ] Concurrency-test groen
- [ ] `./mvnw test -pl services/berichtenmagazijn -am` slaagt
- [ ] JaCoCo blijft ≥ 90%
- [ ] Issue #58 in PR-omschrijving genoemd
