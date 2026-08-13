-- Eén postgres voor de hele federatie-opstelling: de samenvoeging van de per-peer
-- `postgres-init.sql`-bestanden. `fsc_directory` staat in elk daarvan en is hier bewust
-- één database — de federatie heeft één directory, dat is het hele punt.
--
-- Een peer toevoegen = zijn drie regels uit `<peer>/deploy/local/postgres-init.sql`
-- hieronder overnemen.

-- Federatie-infra
CREATE DATABASE fsc_directory;

-- logius (uitvraag-consumer)
CREATE DATABASE fsc_logius;
CREATE DATABASE fsc_controller_logius;
CREATE DATABASE fsc_txlog_logius;

-- magazijn-a (provider)
CREATE DATABASE fsc_magazijn_a;
CREATE DATABASE fsc_controller_magazijn_a;
CREATE DATABASE fsc_txlog_magazijn_a;
