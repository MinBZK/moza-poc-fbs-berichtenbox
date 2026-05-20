-- Partial index op de kandidaten voor hard-delete (soft-deleted berichten).
--
-- De V3-index `idx_berichten_ontvanger_actief` is een partial index met
-- WHERE verwijderd_op IS NULL — die dekt de ophaal-query, maar niet de
-- retentie-claim die juist op WHERE verwijderd_op IS NOT NULL filtert.
--
-- De index is samengesteld op (verwijderd_op, tijdstip_ontvangst) zodat de
-- claim-query (ORDER BY verwijderd_op ASC + filter op tijdstip_ontvangst)
-- via een index-scan terechtkomt. Partial op `verwijderd_op IS NOT NULL`
-- houdt 'm compact — actieve berichten vormen het overgrote deel.

CREATE INDEX idx_berichten_retentie_kandidaat
    ON berichten (verwijderd_op, tijdstip_ontvangst)
    WHERE verwijderd_op IS NOT NULL;
