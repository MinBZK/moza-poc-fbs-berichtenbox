-- Schema-uitbreiding voor de Ophaal- en Beheer-API.
--
-- 1. `berichten.verwijderd_op` — soft-delete timestamp. NULL betekent actief.
--    Ophaal-endpoints filteren rijen waar `verwijderd_op` niet NULL is uit.
--
-- 2. `bijlagen` — bytes per bijlage. `bericht_id` verwijst naar de business-key
--    van het bericht (niet naar de surrogate PK `berichten.id`) om dezelfde
--    abstractie aan te houden als de API. De FK forceert verwijzingsintegriteit
--    en cascade-delete; soft-delete op het bericht raakt bijlagen niet.
--    `content` is BYTEA (PostgreSQL); past tot enkele tientallen MB.
--
-- 3. `bericht_status` — leesstatus per (bericht, ontvanger). Composite PK
--    voorkomt dubbele rijen. `bericht_id` verwijst naar de bedrijfsidentifier
--    van het bericht. Ontvanger-type+waarde matchen de structuur in `berichten`
--    zodat een status alleen kan bestaan voor de daadwerkelijke ontvanger.

ALTER TABLE berichten
    ADD COLUMN verwijderd_op TIMESTAMP NULL;

CREATE TABLE bijlagen (
    id          BIGINT       GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    bijlage_id  UUID         NOT NULL,
    bericht_id  UUID         NOT NULL,
    naam        VARCHAR(255) NOT NULL,
    mime_type   VARCHAR(127) NOT NULL,
    content     BYTEA        NOT NULL,
    CONSTRAINT uq_bijlagen_bijlage_id UNIQUE (bijlage_id),
    CONSTRAINT fk_bijlagen_bericht
        FOREIGN KEY (bericht_id) REFERENCES berichten (bericht_id) ON DELETE CASCADE
);

CREATE INDEX idx_bijlagen_bericht_id ON bijlagen (bericht_id);

CREATE TABLE bericht_status (
    bericht_id        UUID        NOT NULL,
    ontvanger_type    VARCHAR(8)  NOT NULL,
    ontvanger_waarde  VARCHAR(20) NOT NULL,
    gelezen           BOOLEAN     NOT NULL DEFAULT FALSE,
    map               VARCHAR(64) NULL,
    gewijzigd_op      TIMESTAMP   NOT NULL,
    PRIMARY KEY (bericht_id, ontvanger_type, ontvanger_waarde),
    CONSTRAINT fk_bericht_status_bericht
        FOREIGN KEY (bericht_id) REFERENCES berichten (bericht_id) ON DELETE CASCADE
);
