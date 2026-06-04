-- Vergroot de `map`-kolom (mapnaam waar de ontvanger zijn bericht in heeft geplaatst)
-- van VARCHAR(64) naar VARCHAR(128) zodat langere mapnamen geaccepteerd worden. We zijn
-- nog niet live; bestaande rijen vallen binnen 64 chars en passen sowieso.
ALTER TABLE bericht_status ALTER COLUMN map TYPE VARCHAR(128);
