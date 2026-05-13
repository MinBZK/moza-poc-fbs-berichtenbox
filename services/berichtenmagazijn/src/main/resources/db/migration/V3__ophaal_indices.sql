-- Index op `berichten` voor de Ophaal-lijst-query.
--
-- `lijstVoorOntvanger` filtert op `(ontvanger_type, ontvanger_waarde)` en
-- `verwijderd_op IS NULL`, gesorteerd op `tijdstip_ontvangst DESC`. Zonder
-- index doet Postgres een seq-scan + sort; dat schaalt slecht zodra er
-- duizenden berichten zijn. Een partial index (alleen actieve rijen) houdt
-- de index compact en gerichte op de hot path.

CREATE INDEX idx_berichten_ontvanger_actief
    ON berichten (ontvanger_type, ontvanger_waarde, tijdstip_ontvangst DESC)
    WHERE verwijderd_op IS NULL;
