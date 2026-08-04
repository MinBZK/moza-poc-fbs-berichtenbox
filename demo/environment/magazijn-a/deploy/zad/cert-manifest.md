# Cert-attachments op ZAD — magazijn-a-peer

> Draaiboek voor de mens: de cert-attachments mounten. Uit te voeren ná `pki/issue.sh` (zie
> `pki/README.md`) en rond `upsert-peer.sh apply`.

## Waarom UI-only

De ZAD v2 Operations Manager API dekt deployment + componenten (image, env_vars, aliases,
services) maar **geen bijlagen** — net als repo A's directory-deploy
(`deploy/zad/upsert-directory.sh`, zie de header-comment daar). Cert-mounts op
`/etc/fsc/...`-paden gaan dus via de ZAD-UI, per component, als losse attachment-bestanden
(geen `combined.pem` nodig — zie `pki/zad-bundle.sh`, modus 2/passthrough).

## Volgorde

0. **Group-CA plaatsen (NIET `init-ca.sh`).** Voor de échte directory moet de group-leaf ketenen
   naar fsc-testnet's group-root. Zet fsc-testnet's `ca/root.pem` + `ca/intermediate.pem` (+ keys)
   in `pki/ca/` — draai `init-ca.sh` **niet** (dat maakt een verse, vreemde CA). De
   INTERNAL-CA blijft wél lokaal/self-signed (die maakt `issue.sh` per-peer aan).
1. `pki/issue.sh` (vereist `cfssl`) — genereert `pki/out/magazijn-a/*` (group,
   getekend door fsc-testnet's intermediate) en `pki/internal/magazijn-a/*` (internal).
   **Let op (multi-poort-fix, 2026-07-13):** de internal-cert-SAN's bevatten nu ook de
   cluster-interne Service-DNS (`test-<comp>` + `test-<comp>.rig-prd-mpfm-w3h.svc.cluster.local`),
   waarnaar het interne mTLS-verkeer verbindt. Draaide je `issue.sh` vóór deze wijziging, geef de
   certs dan opnieuw uit met `issue.sh -f` (anders faalt de hostnaamverificatie op `fsc-magazijna-magazijna-fscmgr:9443`
   enz.) en upload de verse set opnieuw.
2. `pki/zad-bundle.sh magazijn-a` (hangt af van stap 1) — verzamelt de
   upload-klare set in `pki/zad-upload/magazijn-a/` met een eigen `MANIFEST.md`
   (bestand → pod-pad → `TLS_*`-env-var, zie dat script voor de exacte `env_for()`-mapping).
3. Per component (`magazijna-fscmgr`, `magazijna-fscctl`, `magazijna-fscinway`) in de ZAD-UI: bijlage toevoegen op het
   `/etc/fsc/...`-pad uit de tabellen hieronder, met de bestandsinhoud uit stap 2's
   upload-set. De paden zijn identiek aan de `TLS_*`-waarden die `upsert-peer.sh` al als
   `env_vars`/`aliases` naar de component stuurt — de attachment moet dus exact op dat pad
   gemount worden, anders faalt de container-boot met een ontbrekend-bestand-fout.

## magazijna-fscmgr (manager)

| Bijlage-pad (`/etc/fsc/...`) | Bronbestand (`pki/...`) | Env-var op magazijna-fscmgr |
|-------------------------------|-------------------------------------------|--------------------|
| `ca/root.pem` | `ca/root.pem` | `TLS_GROUP_ROOT_CERT` |
| `out/magazijn-a/manager/cert.pem` | `out/magazijn-a/manager/cert.pem` | `TLS_GROUP_CERT`, `TLS_GROUP_TOKEN_CERT`, `TLS_GROUP_CONTRACT_CERT` |
| `out/magazijn-a/manager/key.pem` | `out/magazijn-a/manager/key.pem` | `TLS_GROUP_KEY`, `TLS_GROUP_TOKEN_KEY`, `TLS_GROUP_CONTRACT_KEY` |
| `internal/magazijn-a/ca/root.pem` | `internal/magazijn-a/ca/root.pem` | `TLS_ROOT_CERT`, `TLS_INTERNAL_UNAUTHENTICATED_ROOT_CERT` |
| `internal/magazijn-a/manager/cert.pem` | `internal/magazijn-a/manager/cert.pem` | `TLS_CERT`, `TLS_INTERNAL_UNAUTHENTICATED_CERT` |
| `internal/magazijn-a/manager/key.pem` | `internal/magazijn-a/manager/key.pem` | `TLS_KEY`, `TLS_INTERNAL_UNAUTHENTICATED_KEY` |

## magazijna-fscctl (controller)

| Bijlage-pad (`/etc/fsc/...`) | Bronbestand (`pki/...`) | Env-var op magazijna-fscctl |
|-------------------------------|-------------------------------------------|--------------------|
| `internal/magazijn-a/ca/root.pem` | `internal/magazijn-a/ca/root.pem` | `TLS_ROOT_CERT` |
| `internal/magazijn-a/controller/cert.pem` | `internal/magazijn-a/controller/cert.pem` | `TLS_CERT` |
| `internal/magazijn-a/controller/key.pem` | `internal/magazijn-a/controller/key.pem` | `TLS_KEY` |

De controller heeft geen group-cert nodig (hij spreekt geen mesh-verkeer met andere peers, alleen
de eigen manager op de internal-PKI) — vandaar geen `out/magazijn-a/controller/*`-rij.

## magazijna-fscinway (inway)

| Bijlage-pad (`/etc/fsc/...`) | Bronbestand (`pki/...`) | Env-var op magazijna-fscinway |
|-------------------------------|-------------------------------------------|--------------------|
| `ca/root.pem` | `ca/root.pem` | `TLS_GROUP_ROOT_CERT` |
| `out/magazijn-a/inway/cert.pem` | `out/magazijn-a/inway/cert.pem` | `TLS_GROUP_CERT` |
| `out/magazijn-a/inway/key.pem` | `out/magazijn-a/inway/key.pem` | `TLS_GROUP_KEY` |
| `internal/magazijn-a/ca/root.pem` | `internal/magazijn-a/ca/root.pem` | `TLS_ROOT_CERT` |
| `internal/magazijn-a/inway/cert.pem` | `internal/magazijn-a/inway/cert.pem` | `TLS_CERT` |
| `internal/magazijn-a/inway/key.pem` | `internal/magazijn-a/inway/key.pem` | `TLS_KEY` |

## magazijna-fsctxlog (txlog-api)

txlog spreekt uitsluitend mTLS op de INTERNAL-PKI (geen group-cert — group-agnostische opslag),
net als in de lokale compose.

| Bijlage-pad (`/etc/fsc/...`) | Bronbestand (`pki/...`) | Env-var op magazijna-fsctxlog |
|-------------------------------|-------------------------------------------|--------------------|
| `internal/magazijn-a/ca/root.pem` | `internal/magazijn-a/ca/root.pem` | `TLS_ROOT_CERT` |
| `internal/magazijn-a/txlog/cert.pem` | `internal/magazijn-a/txlog/cert.pem` | `TLS_CERT` |
| `internal/magazijn-a/txlog/key.pem` | `internal/magazijn-a/txlog/key.pem` | `TLS_KEY` |

## magazijna-fscpg (self-hosted Postgres) — géén cert, wél een init-script-attachment

Sinds 2026-07-15 draait de peer een eigen postgres-component `magazijna-fscpg` (self-hosted) i.p.v. ZAD's
managed Postgres, zodat we de schema-init zelf beheren (zie `docs/design.md` + `upsert-peer.sh`). Geen
TLS-attachments (intra-cluster plaintext op `:5432`, `sslmode=disable`), maar wél één attachment: het
init-script dat de search_path-schema's `manager` + `txlog` aanmaakt (de controller maakt z'n eigen schema).

| Bijlage-pad (in de magazijna-fscpg-container) | Bronbestand (repo) | Werking |
|-------------------------------------|--------------------|---------|
| `/docker-entrypoint-initdb.d/10-schemas.sql` | `deploy/zad/postgres-init.sql` | postgres draait dit éénmalig bij lege PGDATA → `CREATE SCHEMA manager, txlog` |

Verder geen bijlagen op magazijna-fscpg. Het wachtwoord komt uit `ZAD_PG_PASSWORD` (env bij `apply`, niet in git);
`POSTGRES_USER`/`POSTGRES_DB`/`PGDATA` staan als component-env.

**Persistentie:** zonder gekoppeld persistent volume is de DB ephemeral — bij een nieuwe pod draait het
init-script opnieuw en zijn de tabellen leeg (manager/controller/txlog migreren vanzelf via hun wrapper).
Voor een blijvende peer: een persistent volume op `PGDATA` koppelen.

**Schema-namen moeten sporen** met `ZAD_MGR_SCHEMA`/`ZAD_TXLOG_SCHEMA` in `upsert-peer.sh` (defaults
`manager`/`txlog`) — dat zijn de search_path-schema's die het init-script aanmaakt. `ZAD_CTL_SCHEMA` is
**leeg** (de controller draait zonder search_path); zet je 'm tóch, dan loopt migratie #1 dirty vast.

## Migraties per component

Alle drie de DB-componenten draaien een migrate-**wrapper** (`migrate up && serve`) — geen losse
migratiestap meer; `upsert-peer.sh` zet de wrapper-images (`{manager,controller,txlog}-migrate`).

- **manager** — `manager-migrate`-wrapper; teller in schema `manager` (search_path), echte tabellen in
  `peers`/`contracts`.
- **controller** — `controller-migrate`-wrapper, **zonder search_path**: de controller maakt z'n eigen
  `controller`-schema aan (schema-gekwalificeerde DDL) en houdt z'n teller in `public`. Mét een vooraf
  aangemaakt `controller`-schema + `search_path=controller` liep migratie #1 dirty vast.
- **txlog** — `txlog-migrate`-wrapper; teller in schema `txlog` (search_path=txlog), echte tabellen in
  `transactionlog`.

**Vastgelopen op `Dirty database version N`?** De vorige migratie brak halverwege af (onderbroken, of
door meerdere replica's die om de migratie-lock vochten). De wrapper herstelt dit niet zelf. Schoon de
migratie-state van dát component op en herstart 'm zodat de wrapper vers migreert — voor de controller
bleek: `DROP SCHEMA controller CASCADE` + de magazijna-fscctl-component herstarten (schaal desnoods tijdelijk naar
1 replica). Los draaien kan ook, tegen `fsc-magazijna-magazijna-fscpg` met de component-DSN (controller **zonder**,
manager/txlog **mét** hun `search_path`):

```sh
/usr/local/bin/controller migrate up --postgres-dsn "postgres://<user>:<pass>@fsc-magazijna-magazijna-fscpg:5432/fsc?sslmode=disable"
/usr/local/bin/txlog-api  migrate up --postgres-dsn "postgres://<user>:<pass>@fsc-magazijna-magazijna-fscpg:5432/fsc?sslmode=disable&search_path=txlog"
```

## Na het mounten

Herstart (of laat ZAD herstarten na attachment-wijziging) elk component en controleer de boot-log
op een TLS-laadfout — een fout pad of een verwisselde group/internal-cert faalt hard bij startup
("no such file", of een handshake-fout tegen de verkeerde CA). Ga daarna verder met
`verify-zad.md`.

## Bestaande componenten migreren naar de nieuwe env/poorten (multi-poort-fix)

ZAD past `env_vars`/`ports` alléén bij component-**creatie** toe, niet bij een re-POST op een
bestaande component (zie `design.md`). Bestaan de componenten al met de oude (`:443`-)config, dan
zijn er twee routes om de nieuwe interne adressen + poorten door te voeren:

- **Poorten los bijwerken via de API** (env blijft ongemoeid): `PATCH
  /api/v2/projects/mpfm-w3h/components/<comp>` met body `{"ports":[…]}` (magazijna-fscmgr `[8443,9443,9444]`,
  magazijna-fscctl `[8080,9443,9444]`). Zet daarnaast de interne adressen (`MANAGER_ADDRESS_INTERNAL` etc.)
  in de **UI**, want env is UI-beheerd op een bestaande component. Cert-attachments blijven behouden.
- **Component verwijderen + opnieuw aanmaken** (via `upsert-peer.sh apply`, die env+ports in één
  keer zet): dan raak je de cert-attachments kwijt en moet je ze **opnieuw mounten** (deze tabellen).

Na het her-uitgeven van de certs (`issue.sh -f`, want de SAN's zijn gewijzigd) is opnieuw uploaden
sowieso nodig — dus in de praktijk is verwijderen + opnieuw aanmaken + herattachen de schoonste weg.
