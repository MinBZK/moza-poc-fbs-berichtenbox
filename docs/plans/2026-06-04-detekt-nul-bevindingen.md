# detekt naar nul bevindingen (afronding PR #84)

**Status:** Uitgevoerd

## Context

PR #84 introduceert detekt (Kotlin static analysis) met een aangescherpte ruleset
(complexity, potential-bugs, exceptions, empty-blocks, curated style/naming). De
gate-aanpak is in de loop van de PR gewijzigd van *baseline-bestanden* naar
*`maxIssues: 0` zonder baseline*: elke bevinding wordt écht opgelost; bewuste,
onvermijdelijke uitzonderingen krijgen een inline `@Suppress("Rule")` met motivatie.
De vorige werksessie strandde (token-limiet) halverwege: de configuratie staat, maar
18 bevindingen in `berichtensessiecache` (13) en `berichtenmagazijn` (5) zijn nog
open en laten CI (detekt-workflow + CIFuzz-build) falen. `fbs-common` en
`berichtenuitvraag` zijn schoon.

## Bevindingen en aanpak

Alle refactors zijn gedrag-behoudend (extract-method/extract-helper); de bestaande
test-suites zijn het vangnet.

### berichtensessiecache

| Locatie | Bevinding(en) | Aanpak |
|---|---|---|
| `BerichtensessiecacheService` constructor | LongParameterList 10/8 | `@Suppress` met motivatie: 4 collaborators + 6 `@ConfigProperty`-waarden over 3 verschillende config-prefixen; groeperen zou een kunstmatige producer-indirectie vergen zonder leesbaarheidswinst. |
| `BerichtensessiecacheService.haalBerichtenOp` | LongMethod 126, Cyclomatic 19, Cognitive 15 | Extract: lock-acquire (incl. fout-classificatie → `WebApplicationException`-mapping), resolver-await (sealed uitkomst: ids óf CONFIG_DRIFT-event-stream), drift-check. Orkestratie-methode houdt alleen de hoofdflow. |
| `BerichtensessiecacheService.bouwMagazijnStream` | LongMethod 69 | Extract result→event-mapping (`naarVoltooidEvent`) en fault→foutmelding-tabel. |
| `BerichtensessiecacheService.aggregeerEnSlaOp` | LongMethod 60 | Extract failure-recovery (FOUT-status + OPHALEN_FOUT-event) naar helper. |
| `BerichtenCache.probeerUpdateMetadata` | LongMethod 90, Cyclomatic 21, Cognitive 36 | Extract read-fase (`bepaalUpdatePlan`), write-fase (`voerUpdatePlanUit`) en result-afhandeling; de transactie-structuur (WATCH/EXEC + retry) blijft identiek. |
| `BerichtensessiecacheResource.toResponse` | Cyclomatic 15, Cognitive 15, NestedBlockDepth 4 | Extract paginatie-links-builder en aggregatie-mapping. |
| `BerichtenOphalenResource.haalBerichtenOp` | Cognitive 21 | Extract subscriber-callbacks (event/fout/completion) uit de emitter-lambda. |

### berichtenmagazijn

| Locatie | Bevinding(en) | Aanpak |
|---|---|---|
| `AanleverResource.leverBerichtAan` | LongMethod 73, NestedBlockDepth 4 | Extract response-bouw (`naarBerichtResponse`) en de finally-afhandeling (LDV-koppeling + span-end). |
| `PublicatieClaimVerwerker.verwerkClaim` | LongMethod 110 | Extract: ontbrekend-bericht-pad, downstream-URL-bepaling, geslaagd-/mislukt-afhandeling. |
| `HardDeleteService.run` | LongMethod 64, Cognitive 20 | Extract per-kandidaat-verwerking (delete + LDV-log) met uitkomst-telling. |

## Ontwerpkeuzes

- **Refactor boven Suppress:** alleen de service-constructor krijgt `@Suppress`
  (cross-prefix config-injectie is een CDI-idioom, geen ontwerpfout). Alle overige
  bevindingen zijn oprecht te lange/complexe methodes die baat hebben bij opsplitsen.
- **Geen gedragwijziging:** extractie verplaatst code; foutpaden, logregels,
  statuscodes en volgordes blijven byte-voor-byte gelijk.
- **Helpers privaat en doelgericht benoemd** (Nederlands, conform bestaande naamgeving).

## Verificatie

1. `./mvnw detekt:check` → BUILD SUCCESS, 0 bevindingen, alle modules.
2. Volledige testsuites (sessiecache + magazijn, Testcontainers) groen.
3. CLAUDE.md Tooling-notitie bijgewerkt (baseline-verwijzing weg; 0-bevindingen-gate
   + `@Suppress`-uitzonderingsregel beschreven).
4. PR-body #84 bijgewerkt naar de definitieve gate-aanpak.
5. CI-checks op PR #84 groen (detekt, test, CIFuzz).
