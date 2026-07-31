# ZAD-deploy — provider-peer magazijn-a

ZAD-rollout van de FSC-provider-peer `magazijn-a` (manager `magazijna-fscmgr`, controller `magazijna-fscctl`, inway
`magazijna-fscinway`) in een **eigen ZAD-project `mpfm-w3h`**. De `magazijna`-app draait apart in
`mpfm-w3h`; de inway bereikt die cross-project via de ingress-URL. Bouwt voort op `pki/`
(certs) en `deploy/local/` (lokale compose-proof van dezelfde peer); zie die README's voor het
cert-contract resp. de lokale smokes.

> **Group-CA komt uit repo A (fsc-testnet), niet uit `init-ca.sh`.** Om aan te sluiten op de
> échte directory moet magazijn-a's group-leaf ketenen naar fsc-testnet's group-root. Draai
> daarom voor ZAD **niet** `pki/init-ca.sh` (dat maakt een verse, vreemde CA); zet in plaats
> daarvan fsc-testnet's `ca/root.pem` + `ca/intermediate.pem` (+ keys) in `pki/ca/` en draai
> alleen `issue.sh`. Zie `pki/README.md`.

## Inhoud

| Bestand | Rol |
|---------|-----|
| `upsert-peer.sh` | `validate`/`plan`/`apply` tegen de ZAD v2 Operations Manager API — één bron voor CLI + de workflow. |
| `cert-manifest.md` | Runbook: welk cert-bestand op welk `/etc/fsc/...`-pad, per component (UI-only bijlagen). |
| `verify-zad.md` | Runbook: announce/publiceren/discover ná een geslaagde apply, + de acceptatiecriteria. |
| `../../.github/workflows/zad-deploy-peer.yml` | SHA-gepinde workflow die `upsert-peer.sh apply` aanroept (deployt naar `test` op push/PR). |

## Volgorde

1. **Certs** — `pki/init-ca.sh` → `pki/issue.sh` → `pki/verify.sh`
   (vereist `cfssl`; zie `pki/README.md`).
2. **Bundle** — `pki/zad-bundle.sh magazijn-a` (hangt af van stap 1) →
   upload-klare cert-set in `pki/zad-upload/magazijn-a/`.
3. **Deployment `test` moet bestaan** in project `mpfm-w3h` — de raw v2-API `:upsert-deployment`
   maakt géén NIEUWE deployment aan (geeft wel 202 maar het deployment verschijnt niet); het UPDATET
   alleen een bestaand deployment. `test` is doorgaans het default-deployment van een nieuw project
   en bestaat dus al. Zo niet: maak het éénmalig handmatig (leeg) aan in de Operations Manager-UI.
   De workflow zet daarna de componenten + images.
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
| `ZAD_API_KEY` | — (verplicht bij `apply`) | Auth tegen de ZAD v2-API; **de key van project `mpfm-w3h`**, niet de magazijnen-key. **Niet** inline zetten (`export`, niet `ZAD_API_KEY=... ./upsert-peer.sh ...` — dat komt in de shell-history). |
| `ZAD_PROJECT` | `mpfm-w3h` | Eigen ZAD-project van de peer (los van het app-project). Bepaalt óók de namespace (`rig-prd-<project>`) in de cert-SAN's — `pki/gen-csr.sh` leest dezelfde var, dus een projectwissel is env-var-only (her-uitgeven + opnieuw uploaden). |
| `ZAD_DEPLOYMENT` | `test` | Default voor het `[deployment]`-argument (het CLI-arg wint). Gedeeld met `pki/gen-csr.sh` zodat cert-SAN's en deploy-adressen sporen. |
| `ZAD_MAGAZIJNA_PROJECT` | `mpfm-w3h` | ZAD-project waarin de `magazijna`-app draait; bron voor de cross-project inway-upstream-URL. |
| `ZAD_MAGAZIJNA_DEPLOYMENT` | `test` | Deployment van de `magazijna`-app waar de inway-upstream naar wijst (cross-project via ingress-URL). Zet bv. `pr-140` om tegen een app-preview te testen. |
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
| `ZAD_MAGAZIJNA_UPSTREAM_URL` | `https://magazijna-<ZAD_MAGAZIJNA_DEPLOYMENT>-<ZAD_MAGAZIJNA_PROJECT>.<base-domain>` | Volledige override van de endpoint-URL naar de `magazijna`-app; standaard afgeleid uit `ZAD_MAGAZIJNA_DEPLOYMENT` + `ZAD_MAGAZIJNA_PROJECT` (ingress-URL, https/:443). |

De workflow leest de ZAD-key uit het secret `ZAD_API_KEY_FSCORGA` (de key van project `mpfm-w3h`),
niet `ZAD_API_KEY` direct — dat blijft de scriptinterne naam, gezet via `env:` in de workflow. Zet
in GitHub dus **een secret `ZAD_API_KEY_FSCORGA`** en (optioneel) de var `ZAD_PROJECT_ID_MPFOA`.
