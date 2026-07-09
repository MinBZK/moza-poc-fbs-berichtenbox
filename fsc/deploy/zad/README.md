# ZAD-deploy — provider-peer magazijn-a

ZAD-rollout van de FSC-provider-peer `magazijn-a` (manager `mgzmgr`, controller `mgzctl`, inway
`mgzinway`) in een **eigen ZAD-project `mpfoa-e01`**. De `magazijna`-app draait apart in
`mpfm-w3h`; de inway bereikt die cross-project via de ingress-URL. Bouwt voort op `fsc/pki/`
(certs) en `fsc/deploy/local/` (lokale compose-proof van dezelfde peer); zie die README's voor het
cert-contract resp. de lokale smokes.

> **Group-CA komt uit repo A (fsc-testnet), niet uit `init-ca.sh`.** Om aan te sluiten op de
> échte directory moet magazijn-a's group-leaf ketenen naar fsc-testnet's group-root. Draai
> daarom voor ZAD **niet** `fsc/pki/init-ca.sh` (dat maakt een verse, vreemde CA); zet in plaats
> daarvan fsc-testnet's `ca/root.pem` + `ca/intermediate.pem` (+ keys) in `fsc/pki/ca/` en draai
> alleen `issue.sh`. Zie `fsc/pki/README.md`.

> **UITGESTELD — vereist ZAD-key / cluster / certs.** Er is in deze omgeving geen
> `ZAD_API_KEY_MAGAZIJNEN`, geen `cfssl` en geen draaiende cluster-toegang. Alleen
> `upsert-peer.sh plan` (jq-only, geen netwerk) en de YAML-lint op de workflow zijn hier
> daadwerkelijk gedraaid — zie de bestandsheaders (`upsert-peer.sh`, `cert-manifest.md`,
> `verify-zad.md`) voor precies welke stappen UITGESTELD zijn.

## Inhoud

| Bestand | Rol |
|---------|-----|
| `upsert-peer.sh` | `validate`/`plan`/`apply` tegen de ZAD v2 Operations Manager API — één bron voor CLI + de workflow. |
| `cert-manifest.md` | Runbook: welk cert-bestand op welk `/etc/fsc/...`-pad, per component (UI-only bijlagen). |
| `verify-zad.md` | Runbook: announce/publiceren/discover/app-tests ná een geslaagde apply, + de vier AC's van #780. |
| `../../.github/workflows/zad-deploy-peer.yml` | SHA-gepinde workflow die `upsert-peer.sh apply` aanroept (push main → `test`; PR → `pr-<nummer>`). |

## Volgorde

1. **Certs** — `fsc/pki/init-ca.sh` → `fsc/pki/issue.sh` → `fsc/pki/verify.sh` (UITGESTELD,
   vereist `cfssl`; zie `fsc/pki/README.md`).
2. **Bundle** — `fsc/pki/zad-bundle.sh magazijn-a` (UITGESTELD, hierboven van afhankelijk) →
   upload-klare cert-set in `fsc/pki/zad-upload/magazijn-a/`.
3. **Deployment `test` moet bestaan** in project `mpfoa-e01` — de raw v2-API `:upsert-deployment`
   maakt géén NIEUWE deployment aan (geeft wel 202 maar het deployment verschijnt niet); het UPDATET
   alleen een bestaand deployment. `test` is doorgaans het default-deployment van een nieuw project
   en bestaat dus al. Zo niet: maak het éénmalig handmatig (leeg) aan in de Operations Manager-UI.
   De workflow zet daarna de componenten + images.
4. **`upsert-peer.sh plan [deployment] [tag]`** (dry-run, wél uitvoerbaar — alleen `jq`, geen
   netwerk) — toont de deployment- + drie component-bodies zonder te muteren.
5. **`upsert-peer.sh validate`** (UITGESTELD, vereist `ZAD_API_KEY`) — read-only auth-check tegen
   de ZAD-API.
5. **`upsert-peer.sh apply [deployment] [tag]`** (UITGESTELD, vereist `ZAD_API_KEY`) — upsert het
   deployment + de drie componenten, pollt de resulterende tasks.
6. **UI-mount** (UITGESTELD, zie `cert-manifest.md`) — cert-attachments + "Publicatie op het web"
   (passthrough-TLS) zijn UI-only; de v2-API dekt dit niet.
7. **`verify-zad.md`** (UITGESTELD) — announce, dienst-publicatie, discover, app-tests.

## Env-vars

| Variabele | Default | Rol |
|-----------|---------|-----|
| `ZAD_API_KEY` | — (verplicht bij `apply`) | Auth tegen de ZAD v2-API; **de key van project `mpfoa-e01`**, niet de magazijnen-key. **Niet** inline zetten (`export`, niet `ZAD_API_KEY=... ./upsert-peer.sh ...` — dat komt in de shell-history). |
| `ZAD_PROJECT` | `mpfoa-e01` | Eigen ZAD-project van de peer (los van het app-project). |
| `ZAD_MAGAZIJNA_PROJECT` | `mpfm-w3h` | ZAD-project waarin de `magazijna`-app draait; bron voor de cross-project inway-upstream-URL. |
| `ZAD_MAGAZIJNA_DEPLOYMENT` | `test` | Deployment van de `magazijna`-app waar de inway-upstream naar wijst (cross-project via ingress-URL). Zet bv. `pr-140` om tegen een app-preview te testen. |
| `ZAD_BASE` | `https://zad.rijksapp.nl` | Basis-URL van de ZAD v2 Operations Manager API. |
| `ZAD_BASE_DOMAIN` | `rig.prd1.gn2.quattro.rijksapps.nl` | Base-domain voor de per-component mesh-hostnamen. |
| `ZAD_MANAGER_TAG` | = het `tag`-argument | Losse override voor de manager-wrapper-tag (ghcr), los van de OpenFSC stock-tag voor controller/inway. |
| `ZAD_DIRECTORY_MANAGER_HOST` | `dirmgr-test-mft-tp9.<base-domain>` | Repo A's directory-manager-host op ZAD — pas aan als de directory op een andere deployment/project draait. |
| `ZAD_PG_SSLMODE` | `disable` | SSL-mode voor de managed-Postgres-DSN (intra-cluster plaintext, zoals berichtenbox-JDBC). |
| `ZAD_MAGAZIJNA_UPSTREAM_URL` | `https://magazijna-<ZAD_MAGAZIJNA_DEPLOYMENT>-<ZAD_MAGAZIJNA_PROJECT>.<base-domain>` | Volledige override van de endpoint-URL naar de `magazijna`-app; standaard afgeleid uit `ZAD_MAGAZIJNA_DEPLOYMENT` + `ZAD_MAGAZIJNA_PROJECT` (ingress-URL, https/:443). |

De workflow leest de ZAD-key uit het secret `ZAD_API_KEY_FSCORGA` (de key van project `mpfoa-e01`),
niet `ZAD_API_KEY` direct — dat blijft de scriptinterne naam, gezet via `env:` in de workflow. Zet
in GitHub dus **een secret `ZAD_API_KEY_FSCORGA`** en (optioneel) de var `ZAD_PROJECT_ID_MPFOA`.
