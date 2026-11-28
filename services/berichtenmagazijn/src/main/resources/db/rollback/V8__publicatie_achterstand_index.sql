-- Rollback voor V8__publicatie_achterstand_index.sql.
-- Handmatig draaien + flyway_schema_history-rij verwijderen.
-- Altijd veilig: alleen een index, geen data. Zonder index werkt de telling nog, alleen trager.

DROP INDEX IF EXISTS idx_deliveries_achterstand;
