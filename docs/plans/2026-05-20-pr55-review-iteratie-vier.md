**Status:** Uitgevoerd

# Plan: Review-iteratie 4 op PR #55 (Ophaal- en Beheer-API)

## Context

Vierde multi-agent review-iteratie op `feature/berichtenmagazijn-ophaal-beheer-api`. Vorige iteraties (commit 034c0ec, 5dbdf60, b62cf17, 96e11bc) hadden alle eerder bekende kritiek- en medium-bevindingen opgelost; deze ralph-loop iteratie kijkt of er resterende HOOG-bevindingen zijn die in de eerdere rondes zijn ontsnapt.

Reviewers: code-reviewer, silent-failure-hunter, comment-analyzer, pr-test-analyzer, type-design-analyzer, security-reviewer, digital-waste & ADR-lint.

## Bevindingen geconsolideerd (HOOG)

| ID | Bevinding | Bron |
|---|---|---|
| H-SEC-1 | Stored-XSS via bijlage-download: geen `Content-Disposition: attachment`, MIME ongeverifieerd | Security |
| H-SEC-2 | HSTS gezet, maar geen startup-validator die TLS in prod/staging/acceptatie afdwingt | Security |
| H-SF-1 | `softDelete` bool combineert auth-violation / hard-delete / race-tak ondergedifferentieerd | Silent-failure |
| H-TYP-1 | `Bijlage.content: ByteArray` zonder defensive copy lekt DB-rij | Type-design |
| H-CMT-1..6 | Zes KDoc/test-comments met PoC-refs, "later vervangen", feitelijke rot | Comments |
| H-TEST-1 | PATCH/DELETE met zelfde-waarde-ander-type (RSIN vs BSN) niet getest | Tests |
| H-TEST-2 | `pageSize=0`/negatief, `page=-1` boundary niet getest | Tests |
| H-TEST-3 | `softDelete=false` race-pad alleen unit-gemockt, niet via @QuarkusTest | Tests |
| H-WASTE-1 | `BerichtenLijst` retourneert volledige `inhoud` per item → ~100 MiB DoS-amplification + AVG-data-minimalisatie | Digital waste + Security M-2 |
| H-CONS-1 | PATCH 404 vs DELETE 403 op andermans soft-deleted = existence-disclosure | Code-review + Security M-1 |

## Doorgevoerd

### Security
- **H-SEC-1**: `BijlageContentTypeFilter` zet altijd `Content-Disposition: attachment` op de bijlage-response. Spec bijgewerkt (response-header gedocumenteerd). Geen filename in de header — sanitatie/RFC 5987-quoting omzeild door de filename in de afzonderlijke `BijlageMetadata`-call te laten.
- **H-SEC-2**: nieuwe `HttpTlsValidator` in `fbs-common`. Faalt-fail-fast als `quarkus.http.insecure-requests=enabled` of geen keystore in non-dev/test profielen. Mesh-terminatie kan worden gedeclareerd via `fbs.http.tls.termination=mesh`.

### Correctness
- **H-SF-1 + H-CONS-1**:
  - `BerichtBeheerService.wijzigStatus` gebruikt nu `findIncludingDeleted`: 403 op andermans soft-deleted bericht (consistent met DELETE), 404 op eigen soft-deleted.
  - `BerichtBeheerService.verwijder` re-checkt na `softDelete=false`: bericht moet nog steeds bestaan, bij dezelfde ontvanger horen, én verwijderd zijn. Anders: `IllegalStateException` — geen silent 204 die een gestolen DELETE kan maskeren.

### Type-design / KDoc
- **H-TYP-1**: KDoc op `Bijlage.content` documenteert expliciet dat de bytes niet defensive worden gekopieerd en niet gemuteerd mogen worden. Volledige refactor (defensive copy of wrapper) is bewust niet doorgevoerd vanwege de heap-impact bij 25 MiB-bijlagen.
- **H-CMT-1..6**: PoC-refs, "later vervangen", historische framing en feitelijke rot uit zes locaties verwijderd (BerichtOpslagService, BerichtBeheerService, BerichtAutorisatie, BerichtStatus, OphaalResourceIntegrationTest, AanleverResourceIntegrationTest).

### Tests
- **H-TEST-1**: PATCH/DELETE met `RSIN:999993653` tegen een BSN-bericht → 403 (BeheerResourceIntegrationTest).
- **H-TEST-2**: `pageSize=0`, `page=-1`, `pageSize=999` boundary's → 400 (OphaalResourceIntegrationTest).
- **H-TEST-3**: `BerichtRepositoryIntegrationTest`: tweede `softDelete` op zelfde rij → false zonder exception; `softDelete` door verkeerde ontvanger → false zonder mutatie. Nieuwe `BerichtBeheerServiceRaceQuarkusTest` dekt het race-tak in `verwijder` via `QuarkusMock.installMockForType(BerichtRepository)`.
- Aanvullend: `wijzigStatus` op eigen vs andermans soft-deleted bericht (unit-tests in `BerichtBeheerServiceTest`).

### Digital waste / data minimalisatie
- **H-WASTE-1**: nieuw `BerichtSamenvatting`-schema. `BerichtenLijst.berichten[]` is nu lichtgewicht (`berichtId`, `afzender`, `ontvanger`, `onderwerp`, `tijdstipOntvangst`, `aantalBijlagen`, `status`, `_links`) — geen `inhoud`, geen bijlagen-metadata. Volledige representatie alleen via `GET /berichten/{id}`. Spec bijgewerkt; `BerichtDtoMapper.toBerichtSamenvatting`; integratie- en mapper-tests aangepast.

## Verificatie

`./mvnw clean verify` exit 0 op het hele monorepo. **Berichtenmagazijn**: JaCoCo lijn-coverage **91.0%** (≥ 90% drempel), alle tests groen. **fbs-common**: alle tests groen incl. nieuwe `HttpTlsValidatorTest` (8 tests).

## Bewust niet aangepakt (follow-up issues)

- **B-TYP-3 ByteArray defensive copy via wrapper**: zou de heap-druk bij 25 MiB-bijlagen verdubbelen; KDoc-waarschuwing is een acceptabele middenweg.
- **Bericht.copy() in mapper** (Type-design HOOG-2 in raw report): perf-issue voor pagina-iteratie. Niet meer relevant na `BerichtSamenvatting`-refactor — de samenvatting maakt geen `Bericht.copy()` met bijlagen-verrijking nodig.
- **M-6 BerichtStatus.map als sealed type**, **M-7 correlation-id audit-log**: significante refactors met spec/MDC-impact; ongewijzigd uit eerdere iteratie.
