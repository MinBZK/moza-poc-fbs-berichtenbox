-- Partial index voor de telling van de publicatie-achterstand (PublicatieAchterstand).
--
-- Die telling draait elke minuut, per pod, en groepeert de niet-gepubliceerde
-- aanmeldingen per status en doel. Zonder deze index leest hij de hele tabel, inclusief
-- de GEPUBLICEERD-rijen die dertig dagen blijven staan voor de opschoner: het overgrote
-- deel, en er niet toe doend.
--
-- De bestaande idx_deliveries_claim is partial op status = 'TE_PUBLICEREN' en dekt
-- MISLUKT niet; daarom een eigen index op beide statussen. `doel` volgt `status` voor de
-- GROUP BY, `bericht_db_id` is de kolom van de JOIN op berichten. `aangemaakt_op` staat in
-- INCLUDE: de leeftijd leest hem, maar er wordt niet op gezocht.

CREATE INDEX idx_deliveries_achterstand
    ON publicatie_deliveries (status, doel, bericht_db_id)
    INCLUDE (aangemaakt_op)
    WHERE status IN ('TE_PUBLICEREN', 'MISLUKT');
