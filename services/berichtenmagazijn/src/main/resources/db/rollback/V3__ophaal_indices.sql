-- Rollback van V3__ophaal_indices.sql. Handmatig uit te voeren (zie de
-- toelichting in db/rollback/V2__ophaal_beheer.sql).

DROP INDEX IF EXISTS idx_berichten_ontvanger_actief;
