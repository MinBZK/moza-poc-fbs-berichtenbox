-- Schema-uitbreiding voor de Ophaal- en Beheer-API.
--
-- 1. `berichten.verwijderd_op` — soft-delete timestamp. NULL betekent actief.
--    Ophaal-endpoints filteren rijen waar `verwijderd_op` niet NULL is uit.
--
-- 2. `bijlagen` — bytes per bijlage. `bericht_db_id` is een FK op de surrogate
--    PK `berichten.id`, zodat de relatie loskoppelt van de business-identifier
--    en eventuele wijzigingen aan `bericht_id` (UUID) niet door child-tabellen
--    moeten worden gevolgd. `content` is BYTEA (PostgreSQL).
--
-- 3. `bericht_status` — leesstatus van een bericht. Surrogate `id` als PK;
--    de relatie naar `berichten` loopt via `bericht_db_id` (FK op `berichten.id`)
--    met een unique-constraint zodat een bericht hooguit één status-rij heeft.

ALTER TABLE berichten
    ADD COLUMN verwijderd_op TIMESTAMP NULL;

CREATE TABLE bijlagen (
    id            BIGINT       GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    bijlage_id    UUID         NOT NULL,
    bericht_db_id BIGINT       NOT NULL,
    naam          VARCHAR(255) NOT NULL,
    mime_type     VARCHAR(127) NOT NULL,
    content       BYTEA        NOT NULL,
    CONSTRAINT uq_bijlagen_bijlage_id UNIQUE (bijlage_id),
    CONSTRAINT fk_bijlagen_bericht
        FOREIGN KEY (bericht_db_id) REFERENCES berichten (id) ON DELETE CASCADE
);

CREATE INDEX idx_bijlagen_bericht_db_id ON bijlagen (bericht_db_id);

CREATE TABLE bericht_status (
    id            BIGINT      GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    bericht_db_id BIGINT      NOT NULL,
    gelezen       BOOLEAN     NOT NULL DEFAULT FALSE,
    map           VARCHAR(64) NULL,
    gewijzigd_op  TIMESTAMP   NOT NULL,
    CONSTRAINT uq_bericht_status_bericht_db_id UNIQUE (bericht_db_id),
    CONSTRAINT fk_bericht_status_bericht
        FOREIGN KEY (bericht_db_id) REFERENCES berichten (id) ON DELETE CASCADE
);
