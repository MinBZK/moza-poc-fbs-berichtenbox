# Magazijn-a-peer-migratie Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Verhuis de FSC-provider-peer `magazijn-a` (nu in de losse repo `moza-fsc-org-a`) naar `demo/environment/magazijn-a/` in deze repo, met een nieuw 20-cijferig OIN en co-locatie in het bestaande ZAD-project `mpfm-w3h`, zodat `moza-poc-fbs-berichtenbox` de canonieke bron wordt voor deze peer.

**Architecture:** Mechanische verhuizing van `pki/`, `deploy/local/`, `deploy/zad/` en `docs/` (bron: `/home/claude/projects/moza-fsc-org-a`, aparte checkout naast deze repo) naar `demo/environment/magazijn-a/`, met drie inhoudelijke aanpassingen: (1) nieuw OIN `00000000000000100000` i.p.v. `00000001003214345000`, (2) ZAD-project `mpfoa-e2w` → `mpfm-w3h` (co-locatie met de bestaande `magazijna`/`magazijnb`/clickhouse-componenten), (3) ZAD-componentnamen krijgen het prefix `magazijna-fsc*` i.p.v. `mgz*` om dubbelzinnigheid in het gedeelde project te voorkomen. De bestaande `zad-deploy-peer.yml`-workflow vervalt; de componenten worden toegevoegd aan de bestaande `deploy-test-magazijnen`-job in `.github/workflows/deploy.yml`.

**Tech Stack:** Bash, cfssl (PKI), Docker Compose (lokale FSC-harness), ZAD Operations Manager v2-API (`upsert-peer.sh`), GitHub Actions, Quarkus `application.properties`.

## Global Constraints

- Bronrepo: `/home/claude/projects/moza-fsc-org-a` (sibling-checkout, read-only bron — wijzig daar niets).
- Nieuw OIN voor magazijn-a: `00000000000000100000` (oud: `00000001003214345000`).
- **OIN-scope is NAUW:** wijzig het OIN alléén in de vier bestanden die de échte "Magazijn A"-identiteit configureren (Taak 6). Het oude OIN-getal wordt óók als generieke, incidentele "geldig OIN"-testfixture hergebruikt in ~70 ongerelateerde unit-tests door de hele repo (bv. `fbs-common`, `fbs-magazijnregister`, losstaande `berichtenmagazijn`/`berichtenuitvraag`-testfixtures die geen `application.properties` lezen) — die blijven ONGEWIJZIGD. Geverifieerd: `WireMockBackendsResource.OIN_A`, `AfzenderMagazijnIndexTest`, `MagazijnRouterFscFilterTest`, `ServiceCoverageTest`, `OpenApiContractTest` in `berichtenuitvraag` bouwen hun eigen zelfstandige `Oin(...)`-fixtures en lezen de config-sleutel niet — een OIN-wissel in `application.properties` raakt ze niet.
- ZAD-project voor magazijn-a: `mpfm-w3h` (oud: `mpfoa-e2w`, eigen project — vervalt).
- ZAD-componentnamen: `mgzpg`→`magazijna-fscpg`, `mgzmgr`→`magazijna-fscmgr`, `mgzctl`→`magazijna-fscctl`, `mgzinway`→`magazijna-fscinway`, `mgztxlog`→`magazijna-fsctxlog`.
- `DIRECTORY_PEER_ID` (`00000000000000000010`, de externe `moza-fsc-testnet`-directory) blijft ONGEWIJZIGD — dat is niet magazijn-a's eigen identiteit.
- Scope van dit plan is de `test`-ZAD-deployment (singleton-peer, zoals in de brondesign) — GEEN PR-preview-componenten. Zie `docs/plans/2026-07-30-demo-environment-fsc-peers-design.md` voor de achterliggende spec.
- Geen `git push`, geen PR — alleen lokale commits per taak. `deploy.yml`/`.gitignore`/`application.properties`/Bruno-wijzigingen zijn tekstuele config-edits, geen code met eigen testsuite; verificatie gebeurt via de genoemde scripts/greps/testruns per taak.

---

### Taak 1: Skelet `demo/environment/` + `.gitignore`

**Files:**
- Create: `demo/environment/README.md`
- Create: `demo/environment/magazijn-a/README.md`
- Modify: `.gitignore`

**Interfaces:**
- Produces: de directorystructuur waarin Taak 2–7 hun bestanden plaatsen.

- [ ] **Stap 1: Maak de mappenstructuur**

```bash
mkdir -p demo/environment/magazijn-a/{pki,deploy/local,deploy/zad,docs}
```

- [ ] **Stap 2: Schrijf `demo/environment/README.md`**

```markdown
# FSC-demo-omgeving

Provider- en consumer-peers voor de FSC-federatie, co-located met de services die ze
begeleiden. De gedeelde directory/group-CA (group-anker) draait in de externe, aparte
repo `moza-fsc-testnet` — die verhuist bewust niet mee (org-onafhankelijke kern, door
meerdere consumer-repo's tegelijk gebruikt).

| Peer | Rol | OIN | ZAD-project |
|------|-----|-----|--------------|
| [`magazijn-a`](magazijn-a/) | provider (biedt `berichtenmagazijn` aan) | `00000000000000100000` | `mpfm-w3h` (co-located met de `magazijna`/`magazijnb`-app) |

Elke peer-map bevat dezelfde indeling: `pki/` (certificaat-scripts), `deploy/local/`
(lokale docker-compose-harness), `deploy/zad/` (ZAD-rollout-runbooks + plan/validate-
script) en `docs/` (ontwerpachtergrond).
```

- [ ] **Stap 3: Schrijf `demo/environment/magazijn-a/README.md`**

```markdown
# FSC-peer `magazijn-a`

Provider-peer die de dienst `berichtenmagazijn` in de FSC-federatie publiceert
(OIN `00000000000000100000`). Migratie van de losse repo `moza-fsc-org-a`; zie
`docs/design.md` voor de volledige ontwerpachtergrond en
`../../../docs/plans/2026-07-30-demo-environment-fsc-peers-design.md` voor de
directorystructuur-beslissing.

## Lokaal draaien

```bash
cd pki && ./init-ca.sh && ./issue.sh && ./verify.sh && cd -
cp deploy/local/.env.example deploy/local/.env
printf 'HOST_UID=%s\nHOST_GID=%s\n' "$(id -u)" "$(id -g)" >> deploy/local/.env
docker compose -f deploy/local/docker-compose.yaml up -d
./deploy/local/run-smokes.sh   # verwacht: "ALLE SMOKES GROEN."
docker compose -f deploy/local/docker-compose.yaml down -v
```

## ZAD

Draait co-located met de `magazijna`/`magazijnb`-app in project `mpfm-w3h`, deployment
`test`. `.github/workflows/deploy.yml` beheert alleen de image-tags (bestaande
`deploy-test-magazijnen`-job); eerste creatie van de componenten + hun env/ports/certs
is een eenmalige handmatige stap — zie `deploy/zad/README.md`, `cert-manifest.md` en
`verify-zad.md`.
```

- [ ] **Stap 4: `.gitignore`-regels toevoegen**

```bash
cat >> .gitignore << 'EOF'

# FSC-peer certificaten (privésleutels — NOOIT committen)
demo/environment/*/pki/ca/
demo/environment/*/pki/out/
demo/environment/*/pki/internal/
demo/environment/*/pki/zad-upload/
EOF
```

- [ ] **Stap 5: Verifieer**

```bash
test -d demo/environment/magazijn-a/pki && \
test -d demo/environment/magazijn-a/deploy/local && \
test -d demo/environment/magazijn-a/deploy/zad && \
test -d demo/environment/magazijn-a/docs && \
tail -6 .gitignore
```

Verwacht: alle vier `test -d` slagen (geen output = succes); de laatste 6 regels van
`.gitignore` tonen de vier nieuwe `demo/environment/*/pki/...`-regels.

- [ ] **Stap 6: Commit**

```bash
git add demo/environment/README.md demo/environment/magazijn-a/README.md .gitignore
git commit -m "chore(demo): skelet demo/environment/magazijn-a + gitignore voor peer-certs"
```

---

### Taak 2: Migreer en herparametriseer de PKI

**Files:**
- Create: `demo/environment/magazijn-a/pki/{README.md,ca.json,certportal-proof.md,combine-pem.sh,config.json,fix-permissions.sh,gen-crl.sh,gen-csr.sh,init-ca.sh,intermediate.json,internal-ca.json,issue.sh,verify.sh,zad-bundle.sh}`
- Create: `demo/environment/magazijn-a/pki/peers/directory/directory/csr.json`
- Create: `demo/environment/magazijn-a/pki/peers/directory/manager/csr.json`
- Create: `demo/environment/magazijn-a/pki/peers/magazijn-a/{controller,inway,manager,txlog}/csr.json`
- Modify (na copy): `demo/environment/magazijn-a/pki/gen-csr.sh`, `pki/README.md`, `pki/certportal-proof.md`

**Interfaces:**
- Consumes: niets (eerste inhoudelijke migratiestap).
- Produces: `pki/gen-csr.sh` met defaults `PROJECT=mpfm-w3h`, `OIN=00000000000000100000` — Taak 3/4 draaien hierop voort (lokale certs resp. ZAD-SAN's).

- [ ] **Stap 1: Kopieer de getrackte PKI-bestanden (permissies behouden)**

```bash
SRC=/home/claude/projects/moza-fsc-org-a/pki
DST=demo/environment/magazijn-a/pki
for f in README.md ca.json certportal-proof.md combine-pem.sh config.json \
         fix-permissions.sh gen-crl.sh gen-csr.sh init-ca.sh intermediate.json \
         internal-ca.json issue.sh verify.sh zad-bundle.sh \
         peers/directory/directory/csr.json peers/directory/manager/csr.json \
         peers/magazijn-a/controller/csr.json peers/magazijn-a/inway/csr.json \
         peers/magazijn-a/manager/csr.json peers/magazijn-a/txlog/csr.json; do
  mkdir -p "$DST/$(dirname "$f")"
  cp -p "$SRC/$f" "$DST/$f"
done
```

- [ ] **Stap 2: Update de defaults in `gen-csr.sh`**

```bash
sed -i \
  -e 's/PROJECT="\${ZAD_PROJECT:-mpfoa-e2w}"/PROJECT="${ZAD_PROJECT:-mpfm-w3h}"/' \
  -e 's/OIN="00000001003214345000"/OIN="00000000000000100000"/' \
  demo/environment/magazijn-a/pki/gen-csr.sh
```

- [ ] **Stap 3: Update de OIN-documentatie in `pki/README.md` en `pki/certportal-proof.md`**

```bash
sed -i 's/00000001003214345000/00000000000000100000/g' \
  demo/environment/magazijn-a/pki/README.md \
  demo/environment/magazijn-a/pki/certportal-proof.md
```

- [ ] **Stap 4: Regenereer de csr.json's en geef certs uit**

Vereist `cfssl` en `jq` lokaal geïnstalleerd.

```bash
cd demo/environment/magazijn-a/pki
./init-ca.sh
./issue.sh
./verify.sh
cd -
```

Expected: `verify.sh` eindigt met `== ALLE ASSERTS GROEN ==`.

- [ ] **Stap 5: Bevestig dat de csr's het nieuwe project/OIN dragen**

```bash
jq -r '.serialnumber' demo/environment/magazijn-a/pki/peers/magazijn-a/manager/csr.json
jq -r '.hosts[]' demo/environment/magazijn-a/pki/peers/magazijn-a/manager/csr.json | grep mpfm-w3h
```

Expected: eerste commando toont `00000000000000100000`; tweede commando toont minstens
`magazijna-fscmgr-test-mpfm-w3h.rig.prd1.gn2.quattro.rijksapps.nl` (let op: dit
component-kortnaam-deel klopt pas ná Taak 4, waar `gen-csr.sh`'s `ENDPOINTS`-array de
`mgz*`-kortnamen naar `magazijna-fsc*` hernoemt — draai deze stap opnieuw ná Taak 4 om
de uiteindelijke SAN's te bevestigen).

- [ ] **Stap 6: Commit**

```bash
git add demo/environment/magazijn-a/pki
git commit -m "feat(demo): migreer magazijn-a-PKI naar demo/environment (OIN 00000000000000100000)"
```

**Let op:** `pki/ca/`, `pki/out/`, `pki/internal/`, `pki/zad-upload/` zijn gegenereerde
output en staan in `.gitignore` (Taak 1) — die worden NIET gecommit, ondanks dat
`init-ca.sh`/`issue.sh` ze zojuist vulde.

---

### Taak 3: Migreer de lokale docker-compose-harness

**Files:**
- Create: `demo/environment/magazijn-a/deploy/local/{README.md,docker-compose.yaml,haproxy.cfg,postgres-init.sql,.env.example,run-smokes.sh,smoke-announce.sh,publish-service.sh,smoke-discover.sh}`
- Modify (na copy): `deploy/local/README.md`, `deploy/local/smoke-announce.sh`, `deploy/local/smoke-discover.sh`, `deploy/local/publish-service.sh`

**Interfaces:**
- Consumes: `demo/environment/magazijn-a/pki/` (Taak 2) — het `PKI_DIR`-pad in `.env` wijst hierheen.
- Produces: een lokaal draaiende FSC-harness die Taak 4/5's documentatie als bewijs citeert.

- [ ] **Stap 1: Kopieer de compose-bestanden (permissies behouden)**

```bash
SRC=/home/claude/projects/moza-fsc-org-a/deploy/local
DST=demo/environment/magazijn-a/deploy/local
for f in README.md docker-compose.yaml haproxy.cfg postgres-init.sql .env.example \
         run-smokes.sh smoke-announce.sh publish-service.sh smoke-discover.sh; do
  cp -p "$SRC/$f" "$DST/$f"
done
```

`docker-compose.yaml` en `haproxy.cfg` bevatten geen OIN- of ZAD-project-literals (de
lokale harness leidt de peer-identiteit af uit de gemounte PKI-certs, niet uit env) —
die twee bestanden blijven ongewijzigd.

- [ ] **Stap 2: Update het OIN in de smoke-scripts en README**

```bash
sed -i 's/00000001003214345000/00000000000000100000/' \
  demo/environment/magazijn-a/deploy/local/smoke-announce.sh \
  demo/environment/magazijn-a/deploy/local/smoke-discover.sh \
  demo/environment/magazijn-a/deploy/local/publish-service.sh
sed -i 's/00000001003214345000/00000000000000100000/' \
  demo/environment/magazijn-a/deploy/local/README.md
```

- [ ] **Stap 3: Zorg dat `.env` naar de gemigreerde PKI wijst**

```bash
cp demo/environment/magazijn-a/deploy/local/.env.example demo/environment/magazijn-a/deploy/local/.env
printf 'HOST_UID=%s\nHOST_GID=%s\n' "$(id -u)" "$(id -g)" >> demo/environment/magazijn-a/deploy/local/.env
```

`.env.example`'s `PKI_DIR=../../pki` is een relatief pad — dat klopt al voor de nieuwe
locatie (`deploy/local/` → `../../pki`), geen aanpassing nodig.

- [ ] **Stap 4: Start de stack en draai de smoke-suite**

```bash
docker compose -f demo/environment/magazijn-a/deploy/local/docker-compose.yaml up -d
sleep 20
./demo/environment/magazijn-a/deploy/local/run-smokes.sh
```

Expected: eindigt met `ALLE SMOKES GROEN.` (announce → publish → discover, alle drie
groen). Dit is de kern-verificatie van Taak 2 + Taak 3 samen: bewijst dat het nieuwe
OIN end-to-end door de lokale federatie stroomt.

- [ ] **Stap 5: Ruim op**

```bash
docker compose -f demo/environment/magazijn-a/deploy/local/docker-compose.yaml down -v
```

- [ ] **Stap 6: Commit**

```bash
git add demo/environment/magazijn-a/deploy/local
git commit -m "feat(demo): migreer magazijn-a lokale FSC-harness naar demo/environment"
```

`.env` staat via de generieke `*.local`/`.env`-regel al in `.gitignore` (root)
— controleer dat `git status` 'm niet als untracked-toe-te-voegen toont.

---

### Taak 4: Migreer en herparametriseer de ZAD-rollout (co-locatie in `mpfm-w3h`)

**Files:**
- Create: `demo/environment/magazijn-a/deploy/zad/{README.md,upsert-peer.sh,cert-manifest.md,verify-zad.md,postgres-init.sql}`

**Interfaces:**
- Consumes: `demo/environment/magazijn-a/pki/gen-csr.sh`'s nieuwe `ENDPOINTS`-kortnamen (deze taak past ze aan; Taak 2 stap 5 wordt hierna herhaald om te bevestigen dat de SAN's spannen).
- Produces: `magazijna-fsc{pg,mgr,ctl,inway,txlog}`-componentnamen in project `mpfm-w3h` — Taak 7 (`deploy.yml`) gebruikt exact deze namen.

- [ ] **Stap 1: Kopieer de ZAD-bestanden**

```bash
SRC=/home/claude/projects/moza-fsc-org-a/deploy/zad
DST=demo/environment/magazijn-a/deploy/zad
for f in README.md upsert-peer.sh cert-manifest.md verify-zad.md postgres-init.sql; do
  cp -p "$SRC/$f" "$DST/$f"
done
```

`postgres-init.sql` bevat geen OIN/project-literals — ongewijzigd.

- [ ] **Stap 2: Hernoem de ZAD-componentnamen (case-sensitive, laat bash-variabelen ongemoeid)**

De bash-variabelenamen in `upsert-peer.sh` (`MGZMGR_ENV`, `MGZCTL_SVC`, ...) zijn
UPPERCASE en blijven met opzet ongewijzigd — alleen de lowercase component-naam-
*strings* (hostnamen, `jq --arg name`, `reference:"..."` in `DEPLOY_BODY`, proza in de
`.md`-bestanden) veranderen:

```bash
for f in demo/environment/magazijn-a/deploy/zad/upsert-peer.sh \
         demo/environment/magazijn-a/deploy/zad/cert-manifest.md \
         demo/environment/magazijn-a/deploy/zad/verify-zad.md \
         demo/environment/magazijn-a/deploy/zad/README.md \
         demo/environment/magazijn-a/pki/gen-csr.sh; do
  sed -i \
    -e 's/mgzpg/magazijna-fscpg/g' \
    -e 's/mgzmgr/magazijna-fscmgr/g' \
    -e 's/mgzctl/magazijna-fscctl/g' \
    -e 's/mgzinway/magazijna-fscinway/g' \
    -e 's/mgztxlog/magazijna-fsctxlog/g' \
    "$f"
done
```

- [ ] **Stap 3: Wissel het ZAD-project (`mpfoa-e2w` → `mpfm-w3h`) in alle vijf bestanden**

```bash
sed -i 's/mpfoa-e2w/mpfm-w3h/g' \
  demo/environment/magazijn-a/deploy/zad/upsert-peer.sh \
  demo/environment/magazijn-a/deploy/zad/cert-manifest.md \
  demo/environment/magazijn-a/deploy/zad/verify-zad.md \
  demo/environment/magazijn-a/deploy/zad/README.md \
  demo/environment/magazijn-a/pki/gen-csr.sh
```

- [ ] **Stap 4: Vervang de OIN-vermeldingen in de runbooks**

```bash
sed -i 's/00000001003214345000/00000000000000100000/g' \
  demo/environment/magazijn-a/deploy/zad/verify-zad.md \
  demo/environment/magazijn-a/deploy/zad/README.md
```

- [ ] **Stap 5: Vereenvoudig de app-upstream-indirectie in `upsert-peer.sh`**

Peer en app zitten nu in hetzelfde project — de aparte `ZAD_MAGAZIJNA_PROJECT`/`ZAD_MAGAZIJNA_DEPLOYMENT`-indirectie is overbodig geworden (was nodig voor de oude cross-project-situatie). Zoek dit blok:

```bash
grep -n "MAGAZIJNA_PROJECT\|MAGAZIJNA_DEPLOYMENT\|MAGAZIJNA_UPSTREAM_URL" \
  demo/environment/magazijn-a/deploy/zad/upsert-peer.sh
```

en vervang de drie regels

```bash
MAGAZIJNA_PROJECT="${ZAD_MAGAZIJNA_PROJECT:-mpfm-w3h}"
MAGAZIJNA_DEPLOYMENT="${ZAD_MAGAZIJNA_DEPLOYMENT:-test}"
MAGAZIJNA_UPSTREAM_URL="${ZAD_MAGAZIJNA_UPSTREAM_URL:-https://magazijna-${MAGAZIJNA_DEPLOYMENT}-${MAGAZIJNA_PROJECT}.${BASE_DOMAIN}}"
```

door:

```bash
# Peer en app-component `magazijna` zitten sinds de co-locatie in HETZELFDE project +
# dezelfde deployment (geen aparte project-indirectie meer nodig).
MAGAZIJNA_UPSTREAM_URL="${ZAD_MAGAZIJNA_UPSTREAM_URL:-https://magazijna-${DEPLOYMENT}-${PROJECT}.${BASE_DOMAIN}}"
```

Gebruik de `Edit`-tool met exact deze oud/nieuw-tekst (niet `sed`, want het is een
multi-regel-vervanging).

- [ ] **Stap 6: Herschrijf de kopcommentaar (regels 1–50) naar het co-locatie-model**

Vervang (met de `Edit`-tool) de huidige regels 1–50 van
`demo/environment/magazijn-a/deploy/zad/upsert-peer.sh` — die beschrijven nog het oude
"eigen project"-model — door:

```bash
#!/usr/bin/env bash
# Zet de provider-peer magazijn-a (manager+controller+inway+txlog+DB) op ZAD via de v2
# Operations Manager API, CO-LOCATED in het bestaande project `mpfm-w3h` (samen met de
# `magazijna`/`magazijnb`/clickhouse-componenten die deploy.yml beheert). Gebaseerd op
# repo A's deploy/zad/upsert-directory.sh (MinBZK/moza-fsc-testnet) — zelfde
# validate/plan/apply-vorm.
#
# Gedeeld project = gedeelde ZAD-API-key: ZAD_API_KEY is de bestaande
# ZAD_API_KEY_MAGAZIJNEN (project mpfm-w3h) — GEEN eigen key meer nodig (was
# ZAD_API_KEY_FSCORGA in de oude project-isolatie-opzet).
#
# BELANGRIJK — dit script beheert alleen de EENMALIGE creatie + env/ports/certs van de
# FSC-peer-componenten. De DOORLOPENDE image-tag-updates (elke push naar main) lopen via
# de bestaande `deploy-test-magazijnen`-job in .github/workflows/deploy.yml, die de vijf
# `magazijna-fsc*`-componenten toevoegt aan zijn bestaande component-lijst (naast
# `magazijna`/`magazijnb`/clickhouse). Dit script is dus een LOKAAL/HANDMATIG
# plan/validate/apply-hulpmiddel voor de bootstrap en voor debugging — niet meer de
# CI-apply-stap (die rol had het script in de oude project-isolatie-opzet via
# zad-deploy-peer.yml, welke workflow is vervallen).
#
# `:upsert-deployment` maakt géén NIEUW deployment aan (geeft wel HTTP 202, maar het
# deployment verschijnt niet in /deployments); het UPDATET alleen een bestaand
# deployment. `test` bestaat al (deploy.yml beheert 'm al voor magazijna/magazijnb).
# NIET via de API (UI-only): bijlagen (cert-mount) + "Publicatie op het web"
# (passthrough-TLS) — zie cert-manifest.md.
#
# DB: eigen postgres-component `magazijna-fscpg` (self-hosted) i.p.v. ZAD's managed
# Postgres — die laat ons de init/schema's niet inrichten. De drie DB-componenten
# krijgen een CONCRETE STORAGE_POSTGRES_DSN naar `magazijna-fscpg:5432` (in env_vars,
# geen ZAD $DATABASE_*-substitutie); manager/txlog met een eigen `search_path`-schema,
# de controller ZONDER (die beheert z'n eigen `controller`-schema). Het wachtwoord komt
# uit ZAD_PG_PASSWORD (verplicht bij apply, niet gecommit). Zie postgres-init.sql voor
# de search_path-schema's (manager/txlog).
#
# BELANGRIJK — ZAD past component-config (env_vars/aliases) alleen bij COMPONENT-CREATIE
# toe, niet bij een re-POST op een bestaande component. Wijzig je de config van een
# bestaande component, verwijder 'm dan eerst in de UI zodat de volgende apply 'm
# opnieuw aanmaakt.
#
# De deployment is VAST (test/mpfm-w3h), dus we hebben ZAD's $DEPLOYMENT_NAME-
# substitutie niet nodig: bash lost alle inter-component-hostnamen concreet op
# (*_HOST_DISPLAY) en zet ze in `env_vars`.
#
# Usage:
#   export ZAD_API_KEY=...                                       # niet inline (echo't anders)
#   ./deploy/zad/upsert-peer.sh validate                          # read-only auth-check
#   ./deploy/zad/upsert-peer.sh plan   [deployment] [tag]         # toont bodies, muteert niet
#   ./deploy/zad/upsert-peer.sh apply  [deployment] [tag]         # muteert + pollt tasks
# Env: ZAD_API_KEY (verplicht bij apply; key van project mpfm-w3h — ZAD_API_KEY_MAGAZIJNEN),
#      ZAD_PROJECT (mpfm-w3h), ZAD_BASE (zad.rijksapp.nl), ZAD_BASE_DOMAIN (rig.prd1...),
#      ZAD_MANAGER_TAG (ghcr manager-tag, default = tag),
#      ZAD_DIRECTORY_MANAGER_HOST (repo A's directory-manager-host op ZAD),
#      ZAD_PG_SSLMODE (disable).
set -euo pipefail
```

- [ ] **Stap 7: Dry-run — bevestig de nieuwe bodies zonder live ZAD-toegang**

```bash
chmod +x demo/environment/magazijn-a/deploy/zad/upsert-peer.sh
./demo/environment/magazijn-a/deploy/zad/upsert-peer.sh plan
```

Expected (vereist alleen `jq`, geen netwerk/API-key): output bevat
`"reference":"magazijna-fscmgr"`, `"reference":"magazijna-fscctl"`,
`"reference":"magazijna-fscinway"`, `"reference":"magazijna-fsctxlog"`,
`"reference":"magazijna-fscpg"`; de host-regels tonen
`magazijna-fscmgr-test-mpfm-w3h.rig.prd1.gn2.quattro.rijksapps.nl` (en analoog voor
inway); de laatste regel `Upstream naar de app` toont
`https://magazijna-test-mpfm-w3h.rig.prd1.gn2.quattro.rijksapps.nl` (geen aparte
project-indirectie meer).

- [ ] **Stap 8: Herhaal Taak 2 stap 5 om de SAN's te bevestigen**

```bash
jq -r '.hosts[]' demo/environment/magazijn-a/pki/peers/magazijn-a/manager/csr.json
```

Expected: bevat nu `magazijna-fscmgr-test-mpfm-w3h.rig.prd1.gn2.quattro.rijksapps.nl` en
`test-magazijna-fscmgr` (Service-kortnaam) en
`test-magazijna-fscmgr.rig-prd-mpfm-w3h.svc.cluster.local` (Service-FQDN). Zo niet:
draai `./demo/environment/magazijn-a/pki/issue.sh -f` opnieuw (her-uitgifte met de
bijgewerkte `gen-csr.sh`-defaults uit Taak 2/4).

- [ ] **Stap 9: Commit**

```bash
git add demo/environment/magazijn-a/deploy/zad demo/environment/magazijn-a/pki/gen-csr.sh
git commit -m "feat(demo): co-locate magazijn-a-ZAD-peer in mpfm-w3h met magazijna-fsc*-componentnamen"
```

**Handmatige vervolgstap (buiten dit plan, niet automatiseerbaar):** de EERSTE creatie
van de vijf componenten in `mpfm-w3h` (met hun env/ports) via
`ZAD_API_KEY=<ZAD_API_KEY_MAGAZIJNEN> ZAD_PG_PASSWORD=<kies-een-wachtwoord> ./upsert-peer.sh apply`,
gevolgd door de cert-attachments (`cert-manifest.md`) en verificatie (`verify-zad.md`).
Dat vereist echte ZAD-toegang en kan niet door dit plan getest worden.

---

### Taak 5: Migreer `docs/design.md` + colocatie-addendum

**Files:**
- Create: `demo/environment/magazijn-a/docs/design.md`

**Interfaces:**
- Consumes: niets (documentatie-taak, geen functionele afhankelijkheid).

- [ ] **Stap 1: Kopieer het ontwerpdocument**

```bash
cp -p /home/claude/projects/moza-fsc-org-a/docs/design.md demo/environment/magazijn-a/docs/design.md
```

- [ ] **Stap 2: Vervang OIN en project doorheen het document**

```bash
sed -i \
  -e 's/00000001003214345000/00000000000000100000/g' \
  -e 's/mpfoa-e2w/mpfm-w3h/g' \
  -e 's/mgzpg/magazijna-fscpg/g' \
  -e 's/mgzmgr/magazijna-fscmgr/g' \
  -e 's/mgzctl/magazijna-fscctl/g' \
  -e 's/mgzinway/magazijna-fscinway/g' \
  -e 's/mgztxlog/magazijna-fsctxlog/g' \
  demo/environment/magazijn-a/docs/design.md
```

- [ ] **Stap 3: Voeg een colocatie-addendum toe**

Gebruik de `Edit`-tool om onderaan `demo/environment/magazijn-a/docs/design.md` toe te
voegen (na de laatste regel van het bestaande document):

```markdown

## Addendum 2026-07-31 — migratie naar demo/environment + co-locatie

Dit ontwerp beschreef oorspronkelijk een **eigen** ZAD-project (`mpfoa-e2w`) voor de
peer, in de losse repo `moza-fsc-org-a`. Bij de migratie naar
`demo/environment/magazijn-a/` in `moza-poc-fbs-berichtenbox` is dat gewijzigd:

- De peer draait sindsdien **co-located** in `mpfm-w3h` (het bestaande ZAD-project van
  de `magazijna`/`magazijnb`-app), niet meer in een eigen project.
- De ZAD-componentnamen kregen het prefix `magazijna-fsc*` (was `mgz*`) om
  dubbelzinnigheid met andere componenten in het gedeelde project te voorkomen.
- De doorlopende image-tag-updates lopen via de bestaande `deploy-test-magazijnen`-job
  in `.github/workflows/deploy.yml`; de losse `zad-deploy-peer.yml`-workflow is
  vervallen. `upsert-peer.sh` blijft bestaan als lokaal plan/validate/apply-hulpmiddel
  voor de eenmalige componentcreatie en voor debugging.
- Het OIN is gewijzigd naar `00000000000000100000` (was `00000001003214345000`,
  hergebruikt de niet-FSC-specifieke `MAGAZIJN_A_GRANT_HASH`-env-varnaam blijft gelijk).

Zie `docs/plans/2026-07-30-demo-environment-fsc-peers-design.md` (directorystructuur)
en `docs/plans/2026-07-31-magazijn-a-peer-migratie-plan.md` (uitvoering) in
`moza-poc-fbs-berichtenbox` voor de volledige besluitvorming.
```

- [ ] **Stap 4: Commit**

```bash
git add demo/environment/magazijn-a/docs/design.md
git commit -m "docs(demo): migreer magazijn-a-ontwerpdocument + colocatie-addendum"
```

---

### Taak 6: Synchroniseer de applicatie-identiteit (nieuw OIN)

**Files:**
- Modify: `services/berichtenmagazijn/src/main/resources/application.properties:138-139`
- Modify: `services/berichtenuitvraag/src/main/resources/application.properties:114-115,119,122,223`
- Modify: `bruno/berichtenmagazijn/environments/zad.bru:3`
- Modify: `bruno/berichtenuitvraag/environments/zad.bru:8`

**Interfaces:**
- Consumes: het nieuwe OIN uit de Global Constraints (`00000000000000100000`).
- Produces: berichtenmagazijn en berichtenuitvraag routeren onder hetzelfde OIN als de
  gemigreerde FSC-peer — vereist voor end-to-end-federatie (zie
  `docs/plans/2026-07-30-demo-environment-fsc-peers-design.md`, sectie OIN-consistentie).

**Belangrijk:** wijzig UITSLUITEND deze vier bestanden. Het oude OIN-getal komt ook voor
in ~70 ongerelateerde unit-testbestanden (zie Global Constraints) — die blijven staan.

- [ ] **Stap 1: `services/berichtenmagazijn/src/main/resources/application.properties`**

```bash
sed -i \
  '138s/00000001003214345000/00000000000000100000/;139s/00000001003214345000/00000000000000100000/' \
  services/berichtenmagazijn/src/main/resources/application.properties
```

Verifieer:

```bash
sed -n '136,139p' services/berichtenmagazijn/src/main/resources/application.properties
```

Expected:
```
# te publiceren. Dev/test gebruiken een vaste test-OIN voor lokaal draaien.
magazijn.publicatie.organisatie.oin=${MAGAZIJN_OIN}
%dev.magazijn.publicatie.organisatie.oin=00000000000000100000
%test.magazijn.publicatie.organisatie.oin=00000000000000100000
```

- [ ] **Stap 2: `services/berichtenuitvraag/src/main/resources/application.properties`**

Vervang regels 114, 115, 119, 122 en 223 (Magazijn-B's OIN `00000001823288444000` op
regels 120-121/123/224 blijft ONGEWIJZIGD — dit plan raakt alleen Magazijn A):

```bash
sed -i \
  -e '114s/00000001003214345000/00000000000000100000/' \
  -e '115s/00000001003214345000/00000000000000100000/' \
  -e '119s/00000001003214345000/00000000000000100000/' \
  -e '122s/00000001003214345000/00000000000000100000/' \
  -e '223s/00000001003214345000/00000000000000100000/' \
  services/berichtenuitvraag/src/main/resources/application.properties
```

Verifieer:

```bash
sed -n '114,123p;223p' services/berichtenuitvraag/src/main/resources/application.properties
```

Expected (regel 120-121/123 ongewijzigd, Magazijn B):
```
magazijnen."00000000000000100000".url=${MAGAZIJN_A_URL}
magazijnen."00000000000000100000".naam=Magazijn A
# FSC-outway-routering: aanwezig → Fsc-Grant-Hash/-Transaction-Id op elke call naar dit
# magazijn (zie FscOutwayHeadersFilter); lege default (geen env-var) → geen header, magazijn
# blijft direct/zonder outway bereikbaar. Magazijn B heeft nog geen outway-contract.
magazijnen."00000000000000100000".grantHash=${MAGAZIJN_A_GRANT_HASH:}
magazijnen."00000001823288444000".url=${MAGAZIJN_B_URL}
magazijnen."00000001823288444000".naam=Magazijn B
%test.magazijnen."00000000000000100000".url=http://localhost:8081
%test.magazijnen."00000001823288444000".url=http://localhost:8082
%dev.magazijnen."00000000000000100000".url=http://localhost:8090
```

- [ ] **Stap 3: Bruno-omgevingsbestanden**

```bash
sed -i 's/00000001003214345000/00000000000000100000/' \
  bruno/berichtenmagazijn/environments/zad.bru
sed -i 's/00000001003214345000/00000000000000100000/' \
  bruno/berichtenuitvraag/environments/zad.bru
```

Verifieer:

```bash
grep afzender bruno/berichtenmagazijn/environments/zad.bru
grep magazijnId bruno/berichtenuitvraag/environments/zad.bru
```

Expected: beide tonen `00000000000000100000`.

- [ ] **Stap 4: Draai de gerichte testsuites om te bevestigen dat er niets breekt**

```bash
./mvnw clean test -pl services/berichtenmagazijn -am -Dtest='*Publicatie*,*Beheer*'
./mvnw clean test -pl services/berichtenuitvraag -am -Dtest='AfzenderMagazijnIndexTest,MagazijnRouterFscFilterTest,ServiceCoverageTest,OpenApiContractTest'
```

Expected: `BUILD SUCCESS` op beide runs — bevestigt dat de OIN-wissel in
`application.properties` de zelfstandige testfixtures (die het eigen, ongewijzigde
`WireMockBackendsResource.OIN_A` gebruiken) niet raakt.

- [ ] **Stap 5: Repo-brede sanity-grep — geen gemiste "echte identiteit"-plek**

```bash
grep -rn "00000001003214345000" --include="*.properties" --include="*.bru" \
  services/berichtenmagazijn/src/main services/berichtenuitvraag/src/main \
  bruno/berichtenmagazijn/environments bruno/berichtenuitvraag/environments
```

Expected: geen output (lege grep = alle vier bestanden zijn consistent bijgewerkt; dit
commando doorzoekt bewust alleen `src/main` en de Bruno-environments, niet `src/test`,
om de opzettelijk ongewijzigde testfixtures niet als "gemist" te melden).

- [ ] **Stap 6: Commit**

```bash
git add services/berichtenmagazijn/src/main/resources/application.properties \
        services/berichtenuitvraag/src/main/resources/application.properties \
        bruno/berichtenmagazijn/environments/zad.bru \
        bruno/berichtenuitvraag/environments/zad.bru
git commit -m "feat(config): synchroniseer Magazijn-A-OIN met de gemigreerde FSC-peer (00000000000000100000)"
```

---

### Taak 7: Voeg de FSC-peer-componenten toe aan `deploy.yml`

**Files:**
- Modify: `.github/workflows/deploy.yml:514-532` (job `deploy-test-magazijnen`)
- Modify: `.github/workflows/deploy.yml:1-38` (kop-commentaar)

**Interfaces:**
- Consumes: de componentnamen uit Taak 4 (`magazijna-fscpg`, `magazijna-fscmgr`,
  `magazijna-fscctl`, `magazijna-fscinway`, `magazijna-fsctxlog`) en hun images (uit
  `upsert-peer.sh`'s `*_IMAGE`-defaults: `docker.io/library/postgres:17`,
  `ghcr.io/minbzk/moza-fsc-testnet/manager-migrate:v1.43.7`,
  `ghcr.io/minbzk/moza-fsc-testnet/controller-migrate:v1.43.7`,
  `docker.io/federatedserviceconnectivity/inway:v1.43.7`,
  `ghcr.io/minbzk/moza-fsc-testnet/txlog-migrate:v1.43.7`).
- Produces: bij elke push naar `main` update `deploy-test-magazijnen` ook de vijf
  FSC-peer-image-tags (naast `magazijna`/`magazijnb`/clickhouse).

- [ ] **Stap 1: Voeg de vijf componenten toe aan `deploy-test-magazijnen`**

Gebruik de `Edit`-tool om in de `deploy-test-magazijnen`-job (rond regel 527-532) het
bestaande `components:`-blok:

```yaml
          components: |
            [
              {"name": "clickhouse", "image": "${{ env.CLICKHOUSE_IMAGE }}"},
              {"name": "magazijna", "image": "${{ env.REGISTRY }}/${{ needs.meta.outputs.owner }}/fbs-berichtenmagazijn:${{ needs.meta.outputs.tag }}"},
              {"name": "magazijnb", "image": "${{ env.REGISTRY }}/${{ needs.meta.outputs.owner }}/fbs-berichtenmagazijn:${{ needs.meta.outputs.tag }}"}
            ]
```

te vervangen door:

```yaml
          components: |
            [
              {"name": "clickhouse", "image": "${{ env.CLICKHOUSE_IMAGE }}"},
              {"name": "magazijna", "image": "${{ env.REGISTRY }}/${{ needs.meta.outputs.owner }}/fbs-berichtenmagazijn:${{ needs.meta.outputs.tag }}"},
              {"name": "magazijnb", "image": "${{ env.REGISTRY }}/${{ needs.meta.outputs.owner }}/fbs-berichtenmagazijn:${{ needs.meta.outputs.tag }}"},
              {"name": "magazijna-fscpg", "image": "docker.io/library/postgres:17"},
              {"name": "magazijna-fscmgr", "image": "ghcr.io/minbzk/moza-fsc-testnet/manager-migrate:v1.43.7"},
              {"name": "magazijna-fscctl", "image": "ghcr.io/minbzk/moza-fsc-testnet/controller-migrate:v1.43.7"},
              {"name": "magazijna-fscinway", "image": "docker.io/federatedserviceconnectivity/inway:v1.43.7"},
              {"name": "magazijna-fsctxlog", "image": "ghcr.io/minbzk/moza-fsc-testnet/txlog-migrate:v1.43.7"}
            ]
```

**Bewust NIET aangepast:** `deploy-preview-magazijnen` (PR-previews) — de FSC-peer is
een singleton-`test`-deployment, geen per-PR-component (zie Global Constraints).

- [ ] **Stap 2: Werk de kop-commentaar bij**

Regel 8 van `deploy.yml` beschrijft de huidige componenten per project. Gebruik de
`Edit`-tool om:

```
#   magazijnen     (mpfm-w3h)     componenten: clickhouse + magazijna + magazijnb
```

te vervangen door:

```
#   magazijnen     (mpfm-w3h)     componenten: clickhouse + magazijna + magazijnb +
#                                 magazijna-fsc{pg,mgr,ctl,inway,txlog} (FSC-provider-peer,
#                                 co-located; zie demo/environment/magazijn-a/)
```

- [ ] **Stap 3: YAML-syntax verifiëren**

```bash
python3 -c "import yaml; yaml.safe_load(open('.github/workflows/deploy.yml'))" && echo "YAML OK"
```

Expected: `YAML OK` (geen parse-fout).

- [ ] **Stap 4: Verifieer dat de bestaande workflow-lint slaagt**

```bash
npx actionlint .github/workflows/deploy.yml 2>&1 || echo "actionlint niet beschikbaar — sla deze check over als het pakket ontbreekt"
```

Expected: geen `error`-regels voor `deploy.yml` (npm-package `actionlint` is optioneel;
als het commando niet bestaat, is Stap 3's YAML-parse-check de aanvaardbare fallback).

- [ ] **Stap 5: Commit**

```bash
git add .github/workflows/deploy.yml
git commit -m "feat(ci): voeg magazijn-a-FSC-peer-componenten toe aan deploy-test-magazijnen"
```

**Handmatige vervolgstap (buiten dit plan):** het secret `ZAD_API_KEY_FSCORGA` (de oude,
eigen key van `mpfoa-e2w`) kan na de migratie uit de repo-secrets verwijderd worden —
`ZAD_API_KEY_MAGAZIJNEN` dekt voortaan ook de FSC-peer-componenten (co-located in
hetzelfde project). Dit is een repo-settings-wijziging, niet via code uit te voeren.

---

### Taak 8: Eindverificatie — geen achtergebleven referenties

**Files:**
- Geen bestandswijzigingen — puur verificatie.

**Interfaces:**
- Consumes: alle voorgaande taken.

- [ ] **Stap 1: Grep op het oude ZAD-project binnen `demo/environment/`**

```bash
grep -rn "mpfoa-e2w" demo/environment/ || echo "GEEN treffers — OK"
```

Expected: `GEEN treffers — OK`.

- [ ] **Stap 2: Grep op de oude `mgz*`-componentnamen binnen `demo/environment/magazijn-a/`**

```bash
grep -rn '\bmgzmgr\b\|\bmgzctl\b\|\bmgzinway\b\|\bmgztxlog\b\|\bmgzpg\b' \
  demo/environment/magazijn-a/ || echo "GEEN treffers — OK"
```

Expected: `GEEN treffers — OK` (de bash-variabelenamen `MGZMGR_ENV` e.d. zijn UPPERCASE
en matchen deze lowercase-`\b`-patronen niet — dat is bedoeld, zie Taak 4 stap 2).

- [ ] **Stap 3: Grep op het oude OIN binnen de vier applicatie-identiteitsbestanden**

```bash
grep -n "00000001003214345000" \
  services/berichtenmagazijn/src/main/resources/application.properties \
  services/berichtenuitvraag/src/main/resources/application.properties \
  bruno/berichtenmagazijn/environments/zad.bru \
  bruno/berichtenuitvraag/environments/zad.bru \
  || echo "GEEN treffers — OK"
```

Expected: `GEEN treffers — OK`.

- [ ] **Stap 4: Volledige repo-status-controle**

```bash
git status --short
```

Expected: alleen `demo/environment/magazijn-a/pki/{ca,out,internal,zad-upload}/` (indien
nog aanwezig van lokale Taak 2/3-runs) als untracked — die vallen onder de nieuwe
`.gitignore`-regels en horen niet gestaged te worden. Verder een schone working tree
(alle taken zijn al gecommit).

- [ ] **Stap 5: Draai de volledige testsuites van beide services één keer**

```bash
./mvnw clean test -pl services/berichtenmagazijn -am
./mvnw clean test -pl services/berichtenuitvraag -am
```

Expected: `BUILD SUCCESS` op beide — bevestigt dat de smalle OIN-scope (Taak 6) de rest
van de repository niet geraakt heeft.

Dit plan levert een werkende, lokaal geverifieerde FSC-provider-peer op in
`demo/environment/magazijn-a/` met een correcte ZAD-co-locatie-configuratie. De
daadwerkelijke ZAD-`apply` (Taak 4's handmatige vervolgstap) en het archiveren van
`moza-fsc-org-a` blijven — net als in het brondesign — menselijke, niet-geautomatiseerde
stappen.
