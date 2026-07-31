-- Init-script voor de self-hosted Postgres (component `mgzpg`) van de magazijn-a-peer op ZAD.
--
-- Waarom self-hosted i.p.v. ZAD's managed Postgres: die laat ons de init/schema's niet naar eigen
-- inzicht inrichten. Deze Postgres beheren we volledig; dit script draait ÉÉNMALIG bij een lege
-- PGDATA (postgres-image: alles in /docker-entrypoint-initdb.d/*.sql wordt dan als POSTGRES_USER
-- tegen POSTGRES_DB uitgevoerd).
--
-- Doel: geïsoleerde golang-migrate `schema_migrations`-tellers per FSC-component binnen één database
-- (POSTGRES_DB=fsc). Delen ze één teller, dan ziet de één de versie van de ander, denkt "al
-- gemigreerd" en slaat z'n eigen migraties over -> ontbrekende tabellen (o.a. `controller.services`
-- -> `42P01`). De echte tabellen zetten de componenten in hun eigen (gehardcodeerde) schema's:
-- manager in `peers`/`contracts`, txlog in `transactionlog`, controller in `controller` (die maken ze
-- zelf aan).
--
-- TWEE MECHANISMEN, want de componenten gedragen zich niet gelijk:
--   * manager en txlog: hun teller isoleren we via `search_path=<schema>` in de DSN (zie
--     upsert-peer.sh, _pg_dsn). Zo'n search_path-schema moet VOORAF bestaan -> we maken `manager` en
--     `txlog` hieronder aan.
--   * controller: UITZONDERING. De controller maakt z'n `controller`-schema ZELF aan en gebruikt
--     schema-gekwalificeerde DDL (`controller.services`, ...). Hem met `search_path=controller` +
--     een vóóraf aangemaakt `controller`-schema draaien liet migratie #1 vastlopen (dirty database
--     version 1). Draai de controller daarom ZONDER search_path (CTL_SCHEMA="" in upsert-peer.sh);
--     z'n `schema_migrations` landt dan in `public` -- nog steeds geïsoleerd, want manager/txlog
--     houden de hunne in `manager`/`txlog`. Maak `controller` hier dus NIET aan.
--
-- Attachment-pad in de ZAD-UI: /docker-entrypoint-initdb.d/10-schemas.sql (zie cert-manifest.md).
-- Let op: het script draait alleen bij een VERSE datamap. Zonder persistent volume is de DB
-- ephemeral (schema's + migraties worden bij elke nieuwe pod opnieuw opgebouwd) -- akkoord voor test;
-- voor een blijvende peer een persistent volume koppelen.

CREATE SCHEMA IF NOT EXISTS manager;
CREATE SCHEMA IF NOT EXISTS txlog;

-- POSTGRES_USER (=fsc) is eigenaar van de database en van de zojuist aangemaakte schema's, dus heeft
-- al volledige rechten. Expliciete GRANT's voor de duidelijkheid / mocht je later een aparte app-rol
-- introduceren:
GRANT ALL ON SCHEMA manager, txlog TO CURRENT_USER;
