# ZAD-deploy — provider-peer magazijn-a

ZAD-rollout van de FSC-provider-peer `magazijn-a` (manager `magazijna-fscmgr`, controller
`magazijna-fscctl`, inway `magazijna-fscinway`, txlog `magazijna-fsctxlog`, self-hosted Postgres
`magazijna-fscpg`), **CO-LOCATED in het bestaande ZAD-project `mpfm-w3h`** — hetzelfde project als
de `magazijna`/`magazijnb`/clickhouse-componenten die `deploy.yml` beheert. Peer en app delen dus
zowel project als deployment (`test`); de `magazijna-fsc*`-componentnamen bestaan om botsingen met
de app-componenten in dat gedeelde project te voorkomen. Bouwt voort op `pki/` (certs) en
`deploy/local/` (lokale compose-proof van dezelfde peer); zie die README's voor het cert-contract
resp. de lokale smokes.

> **Group-CA komt uit repo A (fsc-testnet), niet uit `init-ca.sh`.** Om aan te sluiten op de
> échte directory moet magazijn-a's group-leaf ketenen naar fsc-testnet's group-root. Draai
> daarom voor ZAD **niet** `pki/init-ca.sh` (dat maakt een verse, vreemde CA); zet in plaats
> daarvan fsc-testnet's `ca/root.pem` + `ca/intermediate.pem` (+ keys) in `pki/ca/` en draai
> alleen `issue.sh`. Zie `pki/README.md`.

## Inhoud

| Bestand | Rol |
|---------|-----|
| `upsert-peer.sh` | `validate`/`plan`/`apply` tegen de ZAD v2 Operations Manager API — lokaal/handmatig hulpmiddel voor de eenmalige component-creatie (env/ports/certs) en voor debugging. |
| `cert-manifest.md` | Runbook: welk cert-bestand op welk `/etc/fsc/...`-pad, per component (UI-only bijlagen). |
| `verify-zad.md` | Runbook: announce/publiceren/discover ná een geslaagde apply, + de acceptatiecriteria. |
| `../../../../../.github/workflows/deploy.yml` (root) | `deploy-test-magazijnen`-job: de DOORLOPENDE image-tag-updates (elke push naar main) van de `magazijna-fsc*`-componenten, naast `magazijna`/`magazijnb`/clickhouse — niet de eenmalige creatie (die doet `upsert-peer.sh`). |

## Volgorde

1. **Certs** — `pki/init-ca.sh` → `pki/issue.sh` → `pki/verify.sh`
   (vereist `cfssl`; zie `pki/README.md`).
2. **Bundle** — `pki/zad-bundle.sh magazijn-a` (hangt af van stap 1) →
   upload-klare cert-set in `pki/zad-upload/magazijn-a/`.
3. **Deployment `test` bestaat al** in het gedeelde project `mpfm-w3h` — `deploy.yml` beheert het
   al voor `magazijna`/`magazijnb`/clickhouse. De raw v2-API `:upsert-deployment` maakt sowieso géén
   NIEUWE deployment aan (geeft wel 202 maar het deployment verschijnt niet in `/deployments`); het
   UPDATET alleen een bestaand deployment — hier dus geen punt van zorg.
4. **`upsert-peer.sh plan [deployment] [tag]`** (dry-run, wél uitvoerbaar — alleen `jq`, geen
   netwerk) — toont de deployment- + drie component-bodies zonder te muteren.
5. **`upsert-peer.sh validate`** (vereist `ZAD_API_KEY`) — read-only auth-check tegen
   de ZAD-API.
6. **`upsert-peer.sh apply [deployment] [tag]`** (vereist `ZAD_API_KEY`) — upsert het
   deployment + de drie componenten, pollt de resulterende tasks.
7. **UI-mount** (zie `cert-manifest.md`) — cert-attachments + "Publicatie op het web"
   (passthrough-TLS) zijn UI-only; de v2-API dekt dit niet.
8. **`verify-zad.md`** — announce, dienst-publicatie, discover.

## Env-vars

| Variabele | Default | Rol |
|-----------|---------|-----|
| `ZAD_API_KEY` | — (verplicht bij `apply`) | Auth tegen de ZAD v2-API; de bestaande **`ZAD_API_KEY_MAGAZIJNEN`** (key van het gedeelde project `mpfm-w3h`) — géén aparte peer-key meer nodig. **Niet** inline zetten (`export`, niet `ZAD_API_KEY=... ./upsert-peer.sh ...` — dat komt in de shell-history). |
| `ZAD_PROJECT` | `mpfm-w3h` | Gedeeld ZAD-project van peer + app (`magazijna`/`magazijnb`/clickhouse). Bepaalt óók de namespace (`rig-prd-<project>`) in de cert-SAN's — `pki/gen-csr.sh` leest dezelfde var, dus een projectwissel is env-var-only (her-uitgeven + opnieuw uploaden). |
| `ZAD_DEPLOYMENT` | `test` | Default voor het `[deployment]`-argument (het CLI-arg wint). Gedeeld met `pki/gen-csr.sh` zodat cert-SAN's en deploy-adressen sporen; sinds de co-locatie ook de deployment van de `magazijna`-app zelf (geen aparte app-deployment meer). |
| `ZAD_BASE` | `https://zad.rijksapp.nl` | Basis-URL van de ZAD v2 Operations Manager API. |
| `ZAD_BASE_DOMAIN` | `rig.prd1.gn2.quattro.rijksapps.nl` | Base-domain voor de per-component mesh-hostnamen. |
| `ZAD_MANAGER_TAG` / `ZAD_CONTROLLER_TAG` / `ZAD_TXLOG_TAG` | = het `tag`-argument | Losse tag-override per migrate-wrapper (ghcr `{manager,controller,txlog}-migrate`), los van de OpenFSC stock-tag voor de inway. |
| `ZAD_MANAGER_IMAGE` / `ZAD_CONTROLLER_IMAGE` / `ZAD_TXLOG_IMAGE` | ghcr `…/{manager,controller,txlog}-migrate:<tag>` | Volledige image-override per wrapper — zet dit als het ghcr-pad afwijkt. manager/controller/txlog draaien een wrapper (`migrate up && serve`); de inway heeft geen DB en gebruikt het stock-image. |
| `ZAD_DIRECTORY_MANAGER_HOST` | `dirmgr-test-mft-tp9.<base-domain>` | Repo A's directory-manager-host op ZAD — pas aan als de directory op een andere deployment/project draait. |
| `ZAD_PG_SSLMODE` | `disable` | SSL-mode voor de `magazijna-fscpg`-DSN (intra-cluster plaintext, zoals berichtenbox-JDBC). |
| `ZAD_PG_PASSWORD` | — (verplicht bij `apply`) | Wachtwoord voor de self-hosted Postgres (`magazijna-fscpg`). **Niet** committen; `export` (niet inline). Komt zowel in `POSTGRES_PASSWORD` als in de component-DSN's. |
| `ZAD_PG_USER` / `ZAD_PG_DB` | `fsc` / `fsc` | Rol resp. database van `magazijna-fscpg`. |
| `ZAD_MGR_SCHEMA` / `ZAD_TXLOG_SCHEMA` | `manager` / `txlog` | `search_path`-schema voor de migratie-teller van manager resp. txlog (isolatie). Moeten sporen met `postgres-init.sql` (dat die twee aanmaakt). Leeg = geen search_path. |
| `ZAD_CTL_SCHEMA` | _(leeg)_ | De controller draait **zonder** search_path — die maakt z'n eigen `controller`-schema aan; mét search_path loopt migratie #1 dirty vast. Alleen zetten als je weet wat je doet. |
| `ZAD_POSTGRES_IMAGE` | `docker.io/library/postgres:17` | Image voor de `magazijna-fscpg`-component. |
| `ZAD_MAGAZIJNA_UPSTREAM_URL` | `https://magazijna-<deployment>-<project>.<base-domain>` | Volledige override van de endpoint-URL naar de `magazijna`-app; standaard afgeleid uit de eigen `ZAD_DEPLOYMENT`/`ZAD_PROJECT` (peer en app delen sinds de co-locatie dezelfde deployment + project, dus geen aparte app-indirectie meer). |

`deploy.yml`'s `deploy-test-magazijnen`-job gebruikt voor zijn image-tag-updates het bestaande
secret `ZAD_API_KEY_MAGAZIJNEN` — dezelfde key als voor `magazijna`/`magazijnb`/clickhouse, geen
apart secret voor de peer. Voor een lokale `upsert-peer.sh apply`-run: `export
ZAD_API_KEY=<waarde van ZAD_API_KEY_MAGAZIJNEN>` (en `export ZAD_PG_PASSWORD=...`), niet inline.
