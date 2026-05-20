-- Outbox-patroon voor de Publicatie Stream + schema-consistency.
--
-- Doel: na succesvolle opslag plant het magazijn één delivery-rij per geconfigureerde
-- downstream-service (Aanmeld, Notificatie, ...). Een @Scheduled poller in
-- PublicatieStream claimt rijen waarvan `volgende_poging` is verstreken, levert het
-- CloudEvent af en werkt status bij. Per-downstream retry met exponentiële backoff
-- voorkomt dat één gevallen downstream de andere blokkeert.
--
-- Ontwerpkeuzes:
--  * Geen `BEZIG`-status: claim-adapter (PostgresPublicatieClaimer) gebruikt
--    `SELECT ... FOR UPDATE SKIP LOCKED` zodat het row-level lock zelf de
--    exclusiviteit garandeert binnen de claim-transactie.
--  * Tweede unique key (bericht_id, doel) voorkomt dubbele insert per (bericht,
--    downstream) bij retries van de aanlever-transactie.
--  * Partial index op (status, volgende_poging) WHERE status = 'TE_PUBLICEREN':
--    de claim-query scant alleen openstaande rijen; voltooide / mislukte rijen
--    blijven uit de index. Houdt de index klein bij groei van de tabel.
--  * `ON DELETE CASCADE`: als een bericht ooit verwijderd wordt, verdwijnen ook
--    de bijbehorende deliveries — geen dangling outbox-state.
--  * `publicatiedatum` op `berichten`: bestaande rijen krijgen via backfill de
--    `tijdstip_ontvangst`-waarde i.p.v. `CURRENT_TIMESTAMP`, zodat pre-migratie
--    rijen een betekenisvolle publicatiedatum (= ontvangstmoment) houden i.p.v.
--    het migratiemoment, en de NOT NULL-constraint sluit.
--  * Alle nieuwe TIMESTAMPS staan met TIME ZONE (`TIMESTAMPTZ`). De V1-kolom
--    `tijdstip_ontvangst` was nog `TIMESTAMP` en wordt hier gelijkgetrokken voor
--    schema-consistentie. Hibernate 6 hanteert beide via `Instant` correct op
--    Postgres, maar één type per `Instant`-veld voorkomt impliciete sessie-
--    tijdzone-afhankelijkheid bij toekomstige tooling-integraties.
--  * View `publicatie_deliveries_oud` markeert GEPUBLICEERD-rijen ouder dan 30
--    dagen als kandidaten voor opruimen door [PublicatieDeliveriesOpschoner].
--    `MISLUKT`-rijen blijven staan voor forensisch onderzoek (juridische
--    bewaartermijnen). View i.p.v. inline DELETE in deze migratie houdt het
--    script idempotent en data-veilig — de daadwerkelijke DELETE draait
--    @Scheduled buiten Flyway om.

-- TIMESTAMPTZ-cast: Hibernate schrijft `Instant` als UTC mits `hibernate.jdbc.time_zone`
-- niet expliciet anders is gezet. We gebruiken die default, dus de bestaande V1-rijen
-- zijn UTC en `AT TIME ZONE 'UTC'` is een no-op qua waarde. Indien deze service ooit
-- wordt vervangen / hersteld vanuit een DB-snapshot waar `hibernate.jdbc.time_zone`
-- wél anders stond, moet de operator pre-migratie de waardes eerst converteren.
ALTER TABLE berichten
    ALTER COLUMN tijdstip_ontvangst TYPE TIMESTAMPTZ
    USING tijdstip_ontvangst AT TIME ZONE 'UTC';

ALTER TABLE berichten
    ADD COLUMN publicatiedatum TIMESTAMPTZ;

UPDATE berichten SET publicatiedatum = tijdstip_ontvangst WHERE publicatiedatum IS NULL;

ALTER TABLE berichten
    ALTER COLUMN publicatiedatum SET NOT NULL;

CREATE TABLE publicatie_deliveries (
    id              BIGINT       GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    bericht_id      UUID         NOT NULL,
    doel            VARCHAR(64)  NOT NULL,
    status          VARCHAR(16)  NOT NULL,
    pogingen        INT          NOT NULL DEFAULT 0,
    volgende_poging TIMESTAMPTZ  NOT NULL,
    laatste_fout    TEXT         NULL,
    gepubliceerd_op TIMESTAMPTZ  NULL,
    aangemaakt_op   TIMESTAMPTZ  NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uq_delivery_bericht_doel UNIQUE (bericht_id, doel),
    CONSTRAINT fk_delivery_bericht
        FOREIGN KEY (bericht_id) REFERENCES berichten(bericht_id) ON DELETE CASCADE
);

CREATE INDEX idx_deliveries_claim
    ON publicatie_deliveries (status, volgende_poging)
    WHERE status = 'TE_PUBLICEREN';

-- Partial index voor de cleanup-DELETE in `PublicatieDeliveriesOpschoner`.
-- Zonder deze index doet de cleanup een seq-scan over de hele tabel om
-- GEPUBLICEERD-rijen ouder dan 30 dagen te vinden — bij een grote outbox-
-- tabel een meetbare DB-load elke 24h. Met deze partial index op
-- `gepubliceerd_op` (alleen voor `GEPUBLICEERD`-rijen) reduceert de scan
-- tot een index-range-lookup. `idx_deliveries_claim` helpt niet: dat is
-- partial WHERE `TE_PUBLICEREN`, dus dekt het cleanup-pad niet.
CREATE INDEX idx_deliveries_gepubliceerd
    ON publicatie_deliveries (gepubliceerd_op)
    WHERE status = 'GEPUBLICEERD';

CREATE OR REPLACE VIEW publicatie_deliveries_oud AS
SELECT id, bericht_id, doel, gepubliceerd_op
FROM publicatie_deliveries
WHERE status = 'GEPUBLICEERD'
  AND gepubliceerd_op IS NOT NULL
  AND gepubliceerd_op < (CURRENT_TIMESTAMP - INTERVAL '30 days');
