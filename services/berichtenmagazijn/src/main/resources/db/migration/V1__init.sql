-- Initieel schema voor het berichtenmagazijn (PostgreSQL).
--
-- Ontwerpkeuzes:
--  * `id` is een door de database gegenereerde surrogate PK, losgekoppeld van
--    de bedrijfs-identifier `bericht_id`. Hibernate-mapping: @GeneratedValue IDENTITY.
--    `GENERATED ALWAYS AS IDENTITY` is SQL-standaard en wordt door Postgres en
--    moderne H2 ondersteund.
--  * `bericht_id` is UNIQUE — schendingen bij dubbele aanlevering worden via
--    SQLSTATE 23505 afgevangen door DbConstraintViolationExceptionMapper (409).
--  * Ontvanger wordt opgesplitst in `ontvanger_type` (BSN | RSIN | KVK | OIN)
--    en `ontvanger_waarde` zodat het type niet uit de lengte wordt afgeleid.
--  * `tijdstip_ontvangst` is server-side gezet (moment waarop het magazijn het
--    bericht ontving), niet afkomstig uit de payload.
--  * `inhoud` is TEXT: grote berichten (tot ~1 MiB) passen niet in VARCHAR
--    zonder truncatie. In Postgres is TEXT onbegrensd; in H2 ook.

CREATE TABLE berichten (
    id                 BIGINT       GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    bericht_id         UUID         NOT NULL,
    afzender           VARCHAR(20)  NOT NULL,
    ontvanger_type     VARCHAR(8)   NOT NULL,
    ontvanger_waarde   VARCHAR(20)  NOT NULL,
    onderwerp          VARCHAR(255) NOT NULL,
    inhoud             TEXT         NOT NULL,
    tijdstip_ontvangst TIMESTAMP    NOT NULL,
    CONSTRAINT uq_berichten_bericht_id UNIQUE (bericht_id)
);
