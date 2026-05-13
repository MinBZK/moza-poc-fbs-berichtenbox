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
-- 3. `bericht_status` — leesstatus van een bericht. Surrogate `id` als PK; de
--    business-key `bericht_id` is uniek. Past dezelfde conventie toe als
--    `berichten` en `bijlagen`: een nietszeggende, automatisch gegenereerde PK
--    losgekoppeld van de bedrijfs-identifier.

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
    id            BIGINT      GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    bericht_id    UUID        NOT NULL,
    gelezen       BOOLEAN     NOT NULL DEFAULT FALSE,
    map           VARCHAR(64) NULL,
    gewijzigd_op  TIMESTAMP   NOT NULL,
    CONSTRAINT uq_bericht_status_bericht_id UNIQUE (bericht_id),
    CONSTRAINT fk_bericht_status_bericht
        FOREIGN KEY (bericht_id) REFERENCES berichten (bericht_id) ON DELETE CASCADE
);
