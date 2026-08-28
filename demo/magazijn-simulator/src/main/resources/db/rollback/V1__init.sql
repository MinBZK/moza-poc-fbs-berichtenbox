-- Rollback van V1__init.sql. Handmatig uit te voeren door een operator; Flyway Community kent geen
-- automatische undo-migraties (alleen Flyway Teams, met `U1__*.sql`). Deze map wordt door Flyway
-- niet gescand — de scanner kijkt alleen onder `db/migration/`.
--
-- Volgorde is omgekeerd t.o.v. V1: child-tabellen eerst, anders blokkeren de RESTRICT-FK's het.
-- Alle demo-data gaat verloren; dat is hier de bedoeling, maar controleer het vóór je dit draait.
--
-- Na uitvoeren ook de bijbehorende rij uit `flyway_schema_history` verwijderen, zodat een latere
-- `quarkus:dev` of `mvn test` V1 opnieuw kan toepassen:
--
--   DELETE FROM flyway_schema_history WHERE version = '1';

DROP TABLE IF EXISTS bericht_status;

DROP INDEX IF EXISTS idx_bijlage_bericht_db_id;

DROP TABLE IF EXISTS bijlage;

DROP INDEX IF EXISTS idx_bericht_magazijn_ontvanger_actief;

DROP TABLE IF EXISTS bericht;

DROP TABLE IF EXISTS magazijn;
