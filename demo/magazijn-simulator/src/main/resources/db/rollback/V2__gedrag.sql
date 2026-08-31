-- Rollback van V2__gedrag.sql. Handmatig uit te voeren; Flyway Community kent geen undo-migraties.
-- Deze map wordt door Flyway niet gescand — de scanner kijkt alleen onder `db/migration/`.
--
-- Na uitvoeren ook de bijbehorende rij uit `flyway_schema_history` verwijderen:
--
--   DELETE FROM flyway_schema_history WHERE version = '2';

ALTER TABLE magazijn
    DROP CONSTRAINT IF EXISTS ck_magazijn_fout_status,
    DROP CONSTRAINT IF EXISTS ck_magazijn_latency,
    DROP CONSTRAINT IF EXISTS ck_magazijn_foutkans,
    DROP COLUMN IF EXISTS fout_status,
    DROP COLUMN IF EXISTS foutkans,
    DROP COLUMN IF EXISTS latency_p95_ms,
    DROP COLUMN IF EXISTS latency_p50_ms,
    DROP COLUMN IF EXISTS gedrag_modus;
