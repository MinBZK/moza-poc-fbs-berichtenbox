# ZAD-deploy — peer logius

ZAD-rollout van de FSC-peer `logius` (manager `logius-fscmgr`, controller `logius-fscctl`, outway
`logius-fscoutway`, inway `logius-fscinway`, txlog `logius-fsctxlog`, self-hosted Postgres
`logius-fscpg`), **CO-LOCATED in het bestaande ZAD-project `mpfb-8wh`** — hetzelfde project als de
`uitvraag`-componenten die `deploy.yml` beheert. Peer en app delen het project, maar **niet de
deployment**: de app draait in `test` (en per PR in `pr-<n>`), de peer in zijn eigen deployment
**`fsc-logius`**. De `logius-fsc*`-componentnamen bestaan om botsingen met de app-componenten in
dat gedeelde project te voorkomen. De peer heeft nog geen gepubliceerde dienst en geen
upstream-app (`CreateService` volgt zodra de upstream bekend is): de outway bereikt de aanbiedende
peer straks rechtstreeks over de FSC-mesh, en de inway staat technisch klaar om zelf een dienst aan
te bieden. Bouwt voort op `pki/` (certs) en `deploy/local/` (lokale compose-proof van dezelfde
peer); zie die README's voor het cert-contract resp. de lokale smokes.

> **Group-CA komt uit repo A (fsc-testnet), niet uit `init-ca.sh`.** Om aan te sluiten op de
> échte directory moet logius's group-leaf ketenen naar fsc-testnet's group-root. Draai
> daarom voor ZAD **niet** `pki/init-ca.sh` (dat maakt een verse, vreemde CA); zet in plaats
> daarvan fsc-testnet's `ca/root.pem` + `ca/intermediate.pem` (+ keys) in `pki/ca/` en draai
> alleen `issue.sh`. Zie `pki/README.md`.

## Waarom een eigen deployment `fsc-logius`

`deploy-preview-uitvraag` in `.github/workflows/deploy.yml` deployt PR-previews van het gedeelde
project `mpfb-8wh` met `clone-from: test`. Of `clone-from` alléén de componenten uit de eigen
`components:`-lijst kloont of *alle* componenten van de bron-deployment, is bij
RijksICTGilde/zad-actions niet bevestigd. Bij het laatste zou elke PR-preview zes extra pods
krijgen: clones zonder de UI-only cert-attachments (die kloont de v2-API niet mee), dus direct
crashloopend — en worst-case meldt de gekloonde peer-manager zich met dezelfde federatie-OIN
(`00000000000000001000`) opnieuw aan bij de gedeelde fsc-testnet-directory, een tweede manager die
dezelfde peer-identiteit claimt.

De peer staat daarom in een eigen deployment `fsc-logius`: **wat niet in `test` staat, kan niet uit
`test` gekloond worden**, ongeacht hoe `clone-from` zich gedraagt. De peer blijft een singleton
(één deployment, één aanmelding van de federatie-OIN); previews van de app draaien zonder peer.
`fsc-logius` is zelf nooit een kloon-bron.

## Inhoud

| Bestand | Rol |
|---------|-----|
| `upsert-peer.sh` | `validate`/`plan`/`apply` tegen de ZAD v2 Operations Manager API — lokaal/handmatig hulpmiddel voor de eenmalige component-creatie (env/ports/certs) en voor debugging. |
| `cert-manifest.md` | Runbook: welk cert-bestand op welk `/etc/fsc/...`-pad, per component (UI-only bijlagen). |
| `verify-zad.md` | Runbook: announce/publiceren/discover ná een geslaagde apply, + de acceptatiecriteria. |
| `../../../federatie/contracts/zad-runbook.md` | Runbook: het contract-bootstrap-component (`logius-fscbootstrap`, rol consumer) neerzetten — env, cert-attachments, verificatie. |
| `../../../../../.github/workflows/deploy.yml` (root) | `deploy-test-uitvraag`-job: de DOORLOPENDE image-tag-updates (elke push naar main) — de `uitvraag`-componenten op deployment `test`, de zeven `logius-fsc*`-componenten op `fsc-logius`. Niet de eenmalige creatie (die doet `upsert-peer.sh`). |

## Volgorde

1. **Certs** — `pki/issue.sh` → `pki/verify.sh` (vereist `cfssl`; zie `pki/README.md`). **Niet**
   `pki/init-ca.sh` — zie de callout hierboven: dat maakt een verse, vreemde CA in plaats van
   fsc-testnet's group-CA te gebruiken.
2. **Bundle** — `pki/zad-bundle.sh logius` (hangt af van stap 1) →
   upload-klare cert-set in `pki/zad-upload/logius/`.
3. **Deployment `fsc-logius` éénmalig leeg aanmaken in de ZAD-UI** (project `mpfb-8wh`), **zonder
   clone-from** — anders komen de app-componenten mee. De raw v2-API `:upsert-deployment` maakt géén
   NIEUWE deployment aan (geeft wel 202 maar het deployment verschijnt niet in `/deployments`); het
   UPDATET alleen een bestaand deployment. Zonder deze stap doet `apply` dus niets zichtbaars.
4. **`upsert-peer.sh plan [deployment] [tag]`** (dry-run, wél uitvoerbaar — alleen `jq`, geen
   netwerk) — toont de deployment- + zes component-bodies zonder te muteren.
5. **`upsert-peer.sh validate`** (vereist `ZAD_API_KEY`) — read-only auth-check tegen
   de ZAD-API.
6. **`upsert-peer.sh apply [deployment] [tag]`** (vereist `ZAD_API_KEY`) — upsert het
   deployment + de zes componenten, pollt de resulterende tasks.
7. **UI-mount** (zie `cert-manifest.md`) — cert-attachments + "Publicatie op het web"
   (passthrough-TLS) zijn UI-only; de v2-API dekt dit niet.
8. **`verify-zad.md`** — announce, dienst-publicatie, discover.

## Env-vars

| Variabele | Default | Rol |
|-----------|---------|-----|
| `ZAD_API_KEY` | — (verplicht bij `apply`) | Auth tegen de ZAD v2-API; de bestaande **`ZAD_API_KEY_UITVRAAG`** (key van het gedeelde project `mpfb-8wh`) — géén aparte peer-key. **Niet** inline zetten (`export`, niet `ZAD_API_KEY=... ./upsert-peer.sh ...` — dat komt in de shell-history). |
| `ZAD_PROJECT` | `mpfb-8wh` | Gedeeld ZAD-project van peer + app (`uitvraag`/`redis`). Bepaalt óók de namespace (`rig-prd-<project>`) in de cert-SAN's — `pki/gen-csr.sh` leest dezelfde var, dus een projectwissel is env-var-only (her-uitgeven + opnieuw uploaden). |
| `ZAD_DEPLOYMENT` | `fsc-logius` | Default voor het `[deployment]`-argument (het CLI-arg wint) — de eigen, preview-loze peer-deployment. Gedeeld met `pki/gen-csr.sh` zodat cert-SAN's en deploy-adressen sporen; een deployment-wissel vraagt dus om her-uitgeven + opnieuw uploaden van de certs. |
| `ZAD_BASE` | `https://zad.rijksapp.nl` | Basis-URL van de ZAD v2 Operations Manager API. |
| `ZAD_BASE_DOMAIN` | `rig.prd1.gn2.quattro.rijksapps.nl` | Base-domain voor de per-component mesh-hostnamen. |
| `ZAD_MANAGER_TAG` / `ZAD_CONTROLLER_TAG` / `ZAD_TXLOG_TAG` | = het `tag`-argument | Losse tag-override per migrate-wrapper (ghcr `{manager,controller,txlog}-migrate`), los van de OpenFSC stock-tag voor outway en inway. |
| `ZAD_MANAGER_IMAGE` / `ZAD_CONTROLLER_IMAGE` / `ZAD_TXLOG_IMAGE` | ghcr `…/{manager,controller,txlog}-migrate:<tag>` | Volledige image-override per wrapper — zet dit als het ghcr-pad afwijkt. manager/controller/txlog draaien een wrapper (`migrate up && serve`); outway en inway hebben geen DB en gebruiken het stock-image. |
| `ZAD_DIRECTORY_MANAGER_HOST` | `dirmgr-test-mft-tp9.<base-domain>` | Repo A's directory-manager-host op ZAD — pas aan als de directory op een andere deployment/project draait. |
| `ZAD_PG_SSLMODE` | `disable` | SSL-mode voor de `logius-fscpg`-DSN (intra-cluster plaintext, zoals de JDBC van de FBS-services). |
| `ZAD_PG_PASSWORD` | — (verplicht bij `apply`) | Wachtwoord voor de self-hosted Postgres (`logius-fscpg`). **Niet** committen; `export` (niet inline). Komt zowel in `POSTGRES_PASSWORD` als in de component-DSN's. |
| `ZAD_PG_USER` / `ZAD_PG_DB` | `fsc` / `fsc` | Rol resp. database van `logius-fscpg`. |
| `ZAD_MGR_SCHEMA` / `ZAD_TXLOG_SCHEMA` | `manager` / `txlog` | `search_path`-schema voor de migratie-teller van manager resp. txlog (isolatie). Moeten sporen met `postgres-init.sql` (dat die twee aanmaakt). Leeg = geen search_path. |
| `ZAD_CTL_SCHEMA` | _(leeg)_ | De controller draait **zonder** search_path — die maakt z'n eigen `controller`-schema aan; mét search_path loopt migratie #1 dirty vast. Alleen zetten als je weet wat je doet. |
| `ZAD_POSTGRES_IMAGE` | `docker.io/library/postgres:17` | Image voor de `logius-fscpg`-component. |
| `ZAD_PEER_CLONE_FROM` | _(leeg)_ | Optionele `cloneFrom` op `:upsert-deployment`. Leeg = geen clone (aanbevolen) — klonen van `test` zou de `uitvraag`-app-componenten meenemen. |

`deploy.yml`'s `deploy-test-uitvraag`-job gebruikt voor zijn image-tag-updates het bestaande secret
`ZAD_API_KEY_UITVRAAG` — dezelfde key als voor `uitvraag`/`redis`, geen apart secret
voor de peer. Voor een lokale `upsert-peer.sh apply`-run: `export ZAD_API_KEY=<waarde van
ZAD_API_KEY_UITVRAAG>` (en `export ZAD_PG_PASSWORD=...`), niet inline.

> **Let op bij `deploy-preview-uitvraag` (`clone-from: test`).** Previews van de `uitvraag`-app
> klonen uit `test` binnen hetzelfde project `mpfb-8wh`. Zolang de peer in zijn eigen deployment
> `fsc-logius` blijft (nooit in `test`), raakt een preview-clone de peer niet — zie de callout
> hierboven.
