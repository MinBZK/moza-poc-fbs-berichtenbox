-- Initieel schema van de magazijn-simulator (PostgreSQL).
--
-- Eén database draagt álle gesimuleerde magazijnen; `magazijn_db_id` op `bericht` is de
-- discriminator. Dat is de hele reden dat de simulator bestaat: honderd echte magazijnen zouden
-- honderd databases zijn.
--
-- Conventies zoals in het stelsel: surrogate PK per tabel, FK's op die surrogate PK (niet op de
-- business-key), en géén ON DELETE CASCADE — soft-delete is de default voor berichten, en cascade
-- ondergraaft die semantiek zodra er ooit hard-delete bij komt.
--
-- Tijdstempels mét tijdzone. Het echte magazijn is daar in een latere migratie naartoe gegaan; een
-- vers schema hoort niet te beginnen met de vorm die daar net is opgeruimd. Hibernate schrijft een
-- `Instant` in beide gevallen als UTC, maar bij het met de hand inspecteren van demo-data scheelt
-- het de vraag welke tijdzone je voor je hebt.

CREATE TABLE magazijn (
    id   BIGINT       GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    oin  VARCHAR(20)  NOT NULL,
    naam VARCHAR(255) NOT NULL,
    CONSTRAINT uq_magazijn_oin UNIQUE (oin)
);

-- `bericht_id` is uniek BINNEN een magazijn, niet daarbuiten.
--
-- Elk magazijn deelt zijn eigen `berichtId` uit; twee organisaties kunnen dezelfde UUID kiezen
-- zonder dat iemand daar iets over te zeggen heeft, en de simulator hoort dat te kunnen. Dat het
-- de sessiecache van de uitvraag opbreekt (die slaat op onder `bericht:v1:<berichtId>` zonder
-- magazijn erin) is een gebrek in die cache — MinBZK/MijnOverheidZakelijk#1004 — en geen reden om
-- de simulator onwerkelijker te maken. Een globale UNIQUE hier zou dat gebrek verstoppen.
CREATE TABLE bericht (
    id                 BIGINT       GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    magazijn_db_id     BIGINT       NOT NULL,
    bericht_id         UUID         NOT NULL,
    afzender           VARCHAR(20)  NOT NULL,
    ontvanger_type     VARCHAR(8)   NOT NULL,
    ontvanger_waarde   VARCHAR(20)  NOT NULL,
    onderwerp          VARCHAR(255) NOT NULL,
    inhoud             TEXT         NOT NULL,
    tijdstip_ontvangst TIMESTAMPTZ  NOT NULL,
    publicatietijdstip TIMESTAMPTZ  NOT NULL,
    -- Soft-delete marker. NULL = actief; ophaal-endpoints filteren de rest eruit, de rij blijft
    -- staan zodat "verwijderd" niet "onherstelbaar gewist" betekent.
    verwijderd_op      TIMESTAMPTZ  NULL,
    CONSTRAINT uq_bericht_per_magazijn UNIQUE (magazijn_db_id, bericht_id),
    CONSTRAINT fk_bericht_magazijn FOREIGN KEY (magazijn_db_id) REFERENCES magazijn (id)
);

-- De lijst-query filtert op magazijn + ontvanger + actief en sorteert aflopend op
-- tijdstip_ontvangst. Een partial index (alleen actieve rijen) houdt hem compact en gericht op
-- precies dat pad; zonder index doet Postgres een seq-scan over álle magazijnen samen, en dat is
-- hier de hele tabel.
CREATE INDEX idx_bericht_magazijn_ontvanger_actief
    ON bericht (magazijn_db_id, ontvanger_type, ontvanger_waarde, tijdstip_ontvangst DESC)
    WHERE verwijderd_op IS NULL;

-- Aparte tabel, ook al is er hooguit één rij per bericht.
--
-- De spec laat het veld `status` wég zolang de ontvanger niets heeft gezet, en toont pas ná een
-- PATCH `gelezen` plus `gewijzigdOp`. Een ontbrekende rij ís dat onderscheid, één op één. Met
-- kolommen op `bericht` zou "nog niets gezet" en "op ongelezen gezet" allebei uit nullbare velden
-- moeten volgen, en dan is het aan de code om het verschil te onthouden.
CREATE TABLE bericht_status (
    id            BIGINT       GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    bericht_db_id BIGINT       NOT NULL,
    gelezen       BOOLEAN      NOT NULL DEFAULT FALSE,
    map           VARCHAR(128) NULL,
    gewijzigd_op  TIMESTAMPTZ  NOT NULL,
    CONSTRAINT uq_bericht_status_bericht_db_id UNIQUE (bericht_db_id),
    CONSTRAINT fk_bericht_status_bericht FOREIGN KEY (bericht_db_id) REFERENCES bericht (id)
);

-- `bijlage_id` is uniek binnen een bericht, om dezelfde reden als `bericht_id` binnen een
-- magazijn: het magazijn dat de bijlage aanneemt deelt het nummer uit.
CREATE TABLE bijlage (
    id            BIGINT       GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    bericht_db_id BIGINT       NOT NULL,
    bijlage_id    UUID         NOT NULL,
    naam          VARCHAR(255) NOT NULL,
    mime_type     VARCHAR(127) NOT NULL,
    -- BYTEA en geen Large Object: `@Lob byte[]` mapt op PostgreSQL naar `oid`, wat een heel andere
    -- opslagvorm is. De Hibernate-6-default voor een kaal `byte[]` levert BYTEA, en dat is wat hier
    -- staat.
    inhoud        BYTEA        NOT NULL,
    -- Deze unique-constraint levert meteen de index waarop de bijlage-queries draaien: hij begint
    -- met `bericht_db_id`, en dat is de kolom waarop zowel het ophalen per bericht als het
    -- batchgewijs ophalen per pagina filtert. Een losse index erbij zou alleen schrijfwerk kosten.
    CONSTRAINT uq_bijlage_per_bericht UNIQUE (bericht_db_id, bijlage_id),
    CONSTRAINT fk_bijlage_bericht FOREIGN KEY (bericht_db_id) REFERENCES bericht (id)
);
