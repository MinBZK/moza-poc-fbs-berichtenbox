-- Rollback voor V4__hard_delete_index.sql.
-- Handmatig draaien + flyway_schema_history-rij verwijderen.

DROP INDEX IF EXISTS idx_berichten_retentie_kandidaat;
