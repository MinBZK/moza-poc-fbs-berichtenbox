-- Rollback van V2__ophaal_beheer.sql. Handmatig uit te voeren door een operator;
-- Flyway Community kent geen automatische undo-migraties (alleen Flyway Teams,
-- die `U2__*.sql` ondersteunt). Deze map (`db/rollback/`) wordt door Flyway
-- niet gescand omdat de scanner alleen onder `db/migration/` kijkt.
--
-- Volgorde is omgekeerd t.o.v. V2: tabellen en kolommen die in V2 zijn
-- toegevoegd worden hier weer verwijderd. Eventuele data in bericht_status en
-- bijlagen gaat verloren — maak een backup voor je dit draait in een omgeving
-- met productiedata.
--
-- Na uitvoeren moet ook de bijbehorende rij uit `flyway_schema_history` worden
-- verwijderd zodat een latere `quarkus:dev` of `mvn test` V2 opnieuw kan
-- toepassen:
--
--   DELETE FROM flyway_schema_history WHERE version = '2';

DROP TABLE IF EXISTS bericht_status;

DROP INDEX IF EXISTS idx_bijlagen_bericht_id;

DROP TABLE IF EXISTS bijlagen;

ALTER TABLE berichten
    DROP COLUMN IF EXISTS verwijderd_op;
