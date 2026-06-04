-- Rollback van V7: terug van VARCHAR(128) naar VARCHAR(64). Veilig zolang er geen
-- mapnaam-waarden langer dan 64 tekens in `bericht_status.map` staan (anders faalt
-- de ALTER door truncatie-risico in strict mode). Niet live: in PoC-omgeving prima.
ALTER TABLE bericht_status ALTER COLUMN map TYPE VARCHAR(64);
