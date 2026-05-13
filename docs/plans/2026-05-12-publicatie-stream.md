**Status:** Concept

# Publicatie Stream — outbox-patroon voor berichtenmagazijn

Implementatie van het publicatie-deel van [Issue 412](https://github.com/MinBZK/MijnOverheidZakelijk/issues/412). De validatie-helft van issue 412 (`BerichtValidatieService`, `ToestemmingControle`) valt buiten deze iteratie.

## Doel

Na succesvolle opslag van een bericht moet het magazijn op (of na) de publicatiedatum een CloudEvent (`nl.rijksoverheid.fbs.bericht.gepubliceerd`, NL GOV CloudEvents v1.1) versturen naar elke geconfigureerde downstream-service (PoC: WireMock-stubs voor Aanmeld Service en Notificatie Service). Faalde bezorging wordt opnieuw geprobeerd met exponentiële backoff; per downstream onafhankelijk zodat een gevallen Notificatie Service de Aanmeld-flow niet blokkeert.

## Scope

- Outbox-tabel `publicatie_deliveries` (één rij per (bericht, downstream))
- `@Scheduled` poller (default elke 60s, configureerbaar) die rijen claimt via een port/adapter
- Postgres-adapter met `SELECT ... FOR UPDATE SKIP LOCKED` (domein-code blijft DB-onafhankelijk)
- CloudEvent in NL GOV-profiel v1.1, structured mode (`application/cloudevents+json`)
- Per-downstream retry met exponentiële backoff, `MISLUKT` na max-pogingen
- Optioneel `publicatieDatum`-veld in Aanlever API (default = `now()`)
- WireMock-stubs in `compose.yaml` voor lokaal draaien
- Unit-, integratie- en end-to-end-tests

**Buiten scope:** validatie/toestemming, FSC-/mTLS-koppeling, JWT-authenticatie naar downstreams, dead-letter UI.

## Beslissingen (uit brainstorm-sessie 2026-05-12)

| # | Keuze | Reden |
|---|-------|-------|
| 1 | `publicatieDatum` optioneel in Aanlever API | Aanleveraar kan publicatie uitstellen zonder API-breuk |
| 2 | Twee downstreams (Aanmeld + Notificatie) als WireMock | Volgt issue 412 letterlijk; C4-relaties E1/E2 |
| 3 | Per-downstream status + retry met backoff | Partial failure mag de andere downstream niet blokkeren |
| 4 | Port/adapter voor claim-mechanisme | Domein-code mag niet direct van Postgres `FOR UPDATE SKIP LOCKED` afhangen |
| 5 | Volledige bericht-payload in CloudEvent `data` | PoC-eenvoud; PII-duplicatie expliciet als risico genoteerd |
| 6 | Impliciete koppeling: opslag plant deliveries; geen `planPublicatie()` op stream-bean | Minder coupling; outbox-patroon is per definitie pull, niet push |
| 7 | Polling-interval 60s default, configureerbaar | Uitgestelde publicaties hoeven niet op de seconde te zijn |

## Architectuur

```
Aanleveraar
   │ POST /api/v1/berichten { ..., publicatieDatum? }
   ▼
AanleverResource
   │
   ▼
BerichtOpslagService.opslaanBericht(...)        @Transactional
   ├─► BerichtRepository.save(bericht)              -- berichten-rij
   └─► PublicatieOutbox.planDeliveries(berichtId, publicatieDatum)
          └─► voor elke downstream-key in config:
                PublicatieDeliveryRepository.persist(rij(status=TE_PUBLICEREN, volgende_poging=publicatieDatum))

PublicatieStream  @Scheduled(every=60s)
   ├─► claimer.claimNuVerwerkbaar(batch)            -- SKIP LOCKED in adapter
   ├─► voor elke claim:
   │    ├─ repo.vind(berichtId)
   │    ├─ event = CloudEventBuilder.bouw(bericht, doel, oin)
   │    ├─ downstreamClient.lever(doel, event)       -- POST application/cloudevents+json
   │    └─ claimer.markeerGeslaagd(...) of markeerMislukt(..., volgendePoging)
   └─ alle binnen één @Transactional per claim, zodat lock vrijkomt na status-update
```

## Datamodel

### Bericht (domein)

Eén nieuw veld:

```kotlin
data class Bericht(
    val berichtId: UUID,
    val afzender: Oin,
    val ontvanger: Identificatienummer,
    val onderwerp: String,
    val inhoud: String,
    val tijdstipOntvangst: Instant,
    val publicatieDatum: Instant,   // nieuw; default in service = tijdstipOntvangst
)
```

Invariant: `publicatieDatum` mag niet voor `tijdstipOntvangst - 1s` liggen (1s slack tegen klok-skew). Geen bovengrens — uitgestelde publicaties mogen ver in de toekomst.

### `publicatie_deliveries`

```sql
CREATE TABLE publicatie_deliveries (
    id              BIGINT       GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    bericht_id      UUID         NOT NULL REFERENCES berichten(bericht_id) ON DELETE CASCADE,
    doel            VARCHAR(64)  NOT NULL,
    status          VARCHAR(16)  NOT NULL,
    pogingen        INT          NOT NULL DEFAULT 0,
    volgende_poging TIMESTAMP    NOT NULL,
    laatste_fout    TEXT         NULL,
    gepubliceerd_op TIMESTAMP    NULL,
    aangemaakt_op   TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uq_delivery_bericht_doel UNIQUE (bericht_id, doel)
);

CREATE INDEX idx_deliveries_claim
    ON publicatie_deliveries (status, volgende_poging)
    WHERE status = 'TE_PUBLICEREN';
```

`status ∈ { TE_PUBLICEREN, GEPUBLICEERD, MISLUKT }`. Geen `BEZIG`-status: `SELECT ... FOR UPDATE SKIP LOCKED` houdt de rij gelockt binnen de claim-transactie tot de stream `markeerGeslaagd`/`markeerMislukt` heeft gecommit.

`berichten` krijgt:

```sql
ALTER TABLE berichten ADD COLUMN publicatie_datum TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP;
```

Migratie `V2__publicatie_outbox.sql` zet beide.

## Componenten

| Bestand | Rol |
|---------|-----|
| `publicatie/PublicatieConfig.kt` | `@ConfigMapping("magazijn.publicatie")` — downstreams (map), max-pogingen, backoff-basis, batch-grootte, oin |
| `publicatie/PublicatieDoel.kt` | Value class voor downstream-key (`AANMELD`, `NOTIFICATIE`, ...) |
| `publicatie/PublicatieClaim.kt` | Data class met (`claimId`, `berichtId`, `doel`, `pogingen`) — wat de stream nodig heeft |
| `publicatie/PublicatieClaimer.kt` | Port-interface: `claimNuVerwerkbaar`, `markeerGeslaagd`, `markeerMislukt` |
| `publicatie/PublicatieOutbox.kt` | API voor opslag: `planDeliveries(berichtId, publicatieDatum)` |
| `publicatie/PublicatieDeliveryEntity.kt` | JPA-entity (`internal`) |
| `publicatie/PublicatieDeliveryRepository.kt` | Panache repository (`internal`) |
| `publicatie/postgres/PostgresPublicatieClaimer.kt` | Adapter; `SELECT ... FOR UPDATE SKIP LOCKED` |
| `publicatie/CloudEventBuilder.kt` | NL GOV v1.1 CloudEvent uit `Bericht` + doel + OIN |
| `publicatie/DownstreamClient.kt` | REST-client; URL uit config per `doel` |
| `publicatie/PublicatieStream.kt` | `@Scheduled`-bean (Quarkus Scheduler) |
| `publicatie/RetryBeleid.kt` | Pure functie: `volgendePoging(pogingen, basis): Instant?` (null = MISLUKT) |

## CloudEvent-formaat (NL GOV v1.1)

```http
POST /events HTTP/1.1
Content-Type: application/cloudevents+json

{
  "specversion": "1.0",
  "id": "<deterministic UUID per (berichtId, doel)>",
  "source": "urn:nld:oin:00000001823288444000:systeem:fbs-magazijn",
  "type": "nl.rijksoverheid.fbs.bericht.gepubliceerd",
  "subject": "<berichtId>",
  "time": "<gepubliceerd_op, ISO-8601 UTC>",
  "datacontenttype": "application/json",
  "data": {
    "berichtId": "<UUID>",
    "afzender": "00000001003214345000",
    "ontvanger": { "type": "BSN", "waarde": "999993653" },
    "onderwerp": "...",
    "inhoud": "...",
    "tijdstipOntvangst": "...",
    "publicatieDatum": "..."
  }
}
```

`id` = `UUIDv5(namespace, "$berichtId|$doel|$pogingPrefix")` zodat een retry exact dezelfde event-id krijgt en downstream-idempotency mogelijk blijft.

`subject` = `berichtId`, niet de BSN/RSIN — context-attributen mogen volgens NL GOV-richtlijn geen persoonsgegevens bevatten.

> ⚠️ **PII-risico:** `data.inhoud` en `data.ontvanger.waarde` (BSN/RSIN) gaan onversleuteld naar twee externe systemen. PoC-acceptabel; in productie versleutelen of Claim Check-pattern toepassen via toekomstige Ophaal API.

## Retry-beleid

`volgendePoging(pogingen, basis = 1s)` = `now() + basis * 2^pogingen`, plus 0-25% jitter (deterministisch per claim-id zodat tests reproduceerbaar zijn). Bij `pogingen >= maxPogingen` (default 5): `null` → status `MISLUKT`.

Sequentie pogingen 1..5 met basis 1s: ~1s, 2s, 4s, 8s, 16s. Cap op 1u om runaway-backoff te voorkomen.

## Configuratie

```properties
# application.properties
magazijn.publicatie.organisatie.oin=00000001003214345000
magazijn.publicatie.polling.interval=60s
magazijn.publicatie.batch-grootte=50
magazijn.publicatie.max-pogingen=5
magazijn.publicatie.backoff.basis=PT1S
magazijn.publicatie.backoff.cap=PT1H
magazijn.publicatie.downstreams.aanmeld.url=http://localhost:8083/events
magazijn.publicatie.downstreams.notificatie.url=http://localhost:8084/events

%test.magazijn.publicatie.polling.interval=1s
%test.magazijn.publicatie.backoff.basis=PT0.05S
```

WireMock-stubs voor downstreams worden via `compose.yaml` gestart op poorten 8083/8084.

## Foutgedrag

| Situatie | Gedrag |
|----------|--------|
| Downstream geeft 2xx | `markeerGeslaagd`, `gepubliceerd_op = now()` |
| Downstream geeft 4xx | `markeerMislukt(...)`. 4xx duidt op contract-fout — telt mee voor pogingen-counter. |
| Downstream geeft 5xx of timeout | `markeerMislukt(...)` met backoff. Telt mee. |
| `pogingen + 1 >= max` na fout | Status `MISLUKT`, geen retry meer; geregistreerd voor handmatige inspectie |
| Bericht ontbreekt bij claim-verwerking | Sla claim over, log foutboodschap; markeer `MISLUKT` (referentiefout) |

## Tests

| Niveau | Wat |
|--------|-----|
| Unit | `RetryBeleid` (backoff-curve, jitter-range, cap, max-pogingen → null) |
| Unit | `CloudEventBuilder` (NL GOV-attributen, deterministische `id`, geen PII in context-attributen) |
| Unit | `PublicatieOutbox.planDeliveries` (één rij per geconfigureerde downstream) |
| Unit | `BerichtOpslagService` met mocked outbox (verifieert dat planDeliveries na save wordt aangeroepen) |
| Integratie (`@QuarkusTest`) | `PostgresPublicatieClaimerTest`: claim respecteert `volgende_poging`, `SKIP LOCKED` voorkomt dubbel-claim |
| Integratie | `AanleverPlanDeliveriesTest`: aanleveren → twee deliveries-rijen in DB |
| End-to-end | `PublicatieStreamE2ETest`: aanleveren met `publicatieDatum=now()`, polling-interval 1s, WireMock op random port → beide stubs zien event |
| End-to-end | Faalpath: WireMock geeft 500, retry-attempt 2 slaagt, status uiteindelijk GEPUBLICEERD |
| Contract | OpenAPI-contracttest valideert request met optioneel `publicatieDatum` |

## Implementatie-volgorde

1. OpenAPI-spec uitbreiden + Bruno-collectie bijwerken
2. Flyway V2-migratie
3. `Bericht`/`BerichtEntity`/`BerichtRepository` uitbreiden met `publicatieDatum`
4. `publicatie/`-package: config, port, entity, repository, adapter, outbox
5. CloudEventBuilder + RetryBeleid (puur, eerst test)
6. DownstreamClient (REST-client per doel)
7. PublicatieStream (`@Scheduled`)
8. `BerichtOpslagService` koppelen aan `PublicatieOutbox`
9. `compose.yaml` + WireMock-mappings
10. Tests (zie tabel)
11. `./mvnw test -pl services/berichtenmagazijn -am` groen + JaCoCo ≥ 90%
