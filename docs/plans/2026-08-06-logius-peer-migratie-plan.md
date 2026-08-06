**Status:** Concept

# Implementatieplan — FSC-peer `logius` naar `demo/environment/logius/`

> **Voor agentische uitvoerders:** VEREISTE SUB-SKILL: gebruik `superpowers:subagent-driven-development`
> (aanbevolen) of `superpowers:executing-plans` om dit plan taak-voor-taak uit te voeren. Stappen
> gebruiken checkbox-syntax (`- [ ]`) voor voortgang.

**Doel:** de FSC-peer van de uitvraag-organisatie (nu de losse repo `moza-fsc-testconsumer`,
peer-id `uitvraag-org`) migreren naar `demo/environment/logius/` in deze repo, met nieuwe
identiteit (`logius`, OIN `00000000000000001000`), co-locatie in ZAD-project `mpfb-8wh` in een
eigen deployment `fsc-logius`, en een upgrade van OpenFSC `v1.43.7` naar `v2.5.2`.

**Architectuur:** de peer is een deploy-/configuratieset, geen applicatiecode: een test-PKI
(cfssl-scripts + CSR-templates), een lokale docker-compose-harness (eigen directory + peer +
SNI-router + smokes) en een ZAD-rollout (`upsert-peer.sh` + runbooks). Componenten:
manager, controller, outway, inway, txlog en een self-hosted Postgres. Alles verhuist
grotendeels 1-op-1; wat verandert zijn de identiteit (peer-naam, OIN, componentnamen,
project/deployment), de OpenFSC-versie en de host-poorten van de lokale harness.

**Tech stack:** bash + jq + cfssl (PKI), docker compose (lokale harness), ZAD Operations
Manager v2-API (rollout), GitHub Actions (`deploy.yml`).

**Ontwerp:** [`2026-08-06-logius-peer-migratie-design.md`](2026-08-06-logius-peer-migratie-design.md),
bovenop [`2026-07-30-demo-environment-fsc-peers-design.md`](2026-07-30-demo-environment-fsc-peers-design.md).

## Global Constraints

- **Branch:** `feature/logius-peer-migratie`, afgetakt van `worktree-magazijn-a-peer-migratie`
  (PR #160). Nooit direct naar `main` pushen; geen reviewer toevoegen bij de PR.
- **Bronrepo (alleen lezen):** `~/projects/moza-fsc-testconsumer`. **Referentie-peer:**
  `demo/environment/magazijn-a/` in deze branch — bij twijfel over vorm/toon/structuur is dát
  de norm, niet de bronrepo.
- **Identiteit — overal consistent:** peer-naam `logius`; OIN/Peer-ID `00000000000000001000`;
  group `moza-fbs-test`; directory-OIN `00000000000000000010`; ZAD-project `mpfb-8wh`;
  ZAD-deployment `fsc-logius`; componenten `logius-fscpg`, `logius-fscmgr`, `logius-fscctl`,
  `logius-fscoutway`, `logius-fscinway`, `logius-fsctxlog`; API-key-secret `ZAD_API_KEY_UITVRAAG`.
- **OpenFSC-versie:** `v2.5.2` overal. Migratie-wrappers van
  `ghcr.io/minbzk/moza-fsc-testnet-{manager,controller,txlog}-migrate:v2.5.2`; outway en inway
  van `docker.io/federatedserviceconnectivity/{outway,inway}:v2.5.2`; Postgres
  `docker.io/library/postgres:17`.
- **Geen secrets in git.** Geen sleutels, certs, `.env` of wachtwoorden. `.gitignore` dekt
  `demo/environment/*/pki/{ca,out,internal,zad-upload}/` al — niet aanpassen.
- **Taal:** documentatie en comments in het Nederlands; FSC-idiomen (inway, outway, manager,
  controller, directory, peer, grant, contract, passthrough, SNI, txlog, announce) blijven Engels.
- **Geen EUPL-copyright-headers** in de gemigreerde bestanden: deze repo heeft één `LICENSE` aan
  de root. `magazijn-a` heeft die headers ook laten vallen.
- **Commits:** per taak één commit, prefix `feat(fsc)`/`docs(fsc)`/`chore(fsc)`, met
  `Co-Authored-By: Claude Opus 5 (1M context) <noreply@anthropic.com>`.
- **Omgeving:** `jq` en `podman` zijn beschikbaar; `docker`, `cfssl`, `shellcheck` en `yamllint`
  NIET. Stappen die die tools vereisen worden expliciet als openstaand gemarkeerd — nooit
  stilzwijgend overslaan of "groen" melden zonder uitvoer.

---

### Taak 1: Skelet `demo/environment/logius/` + README's

**Files:**
- Create: `demo/environment/logius/README.md`
- Modify: `demo/environment/README.md`

**Interfaces:**
- Produces: de mapstructuur `demo/environment/logius/{pki,deploy/local,deploy/zad,docs}/` waar
  taken 2–5 hun bestanden in plaatsen.

- [ ] **Stap 1: Maak de mappenstructuur**

```bash
cd demo/environment
mkdir -p logius/pki/peers/directory/{directory,manager} \
         logius/pki/peers/logius/{manager,outway,inway,controller,txlog} \
         logius/deploy/local logius/deploy/zad logius/docs
```

- [ ] **Stap 2: Schrijf `demo/environment/logius/README.md`**

Spiegel `demo/environment/magazijn-a/README.md` (lees dat eerst). Inhoud:

```markdown
# FSC-peer `logius`

Peer van de uitvraag-organisatie (OIN `00000000000000001000`). Neemt af via een outway
(roept `berichtenmagazijn` bij `magazijn-a` aan) en biedt aan via een inway — die laatste
heeft nog geen gepubliceerde dienst. Migratie van de losse repo `moza-fsc-testconsumer`;
zie `docs/design.md` voor de ontwerpachtergrond en
`../../../docs/plans/2026-08-06-logius-peer-migratie-design.md` voor de migratiebeslissingen.

## Lokaal draaien

```bash
cd pki && ./init-ca.sh && ./issue.sh && ./verify.sh && cd -
cp deploy/local/.env.example deploy/local/.env
printf 'HOST_UID=%s\nHOST_GID=%s\n' "$(id -u)" "$(id -g)" >> deploy/local/.env
docker compose -f deploy/local/docker-compose.yaml up -d
./deploy/local/run-smokes.sh   # verwacht: "ALLE SMOKES GROEN."
docker compose -f deploy/local/docker-compose.yaml down -v
```

De harness bindt `127.0.0.1:8081` (directory-UI) en `:8091` (controller-UI) — bewust
verschoven t.o.v. `magazijn-a` (`:8080`/`:8090`), zodat beide peer-harnessen tegelijk
kunnen draaien.

## ZAD

Draait co-located met de `uitvraag`-app in project `mpfb-8wh`, in de EIGEN deployment
`fsc-logius` (niet `test`: PR-previews klonen `test` en zouden de peer met dezelfde
federatie-OIN dupliceren). `.github/workflows/deploy.yml` beheert alleen de image-tags;
eerste creatie van de componenten + hun env/ports/certs is een eenmalige handmatige stap —
zie `deploy/zad/README.md`, `cert-manifest.md` en `verify-zad.md`.
```

- [ ] **Stap 3: Voeg de `logius`-rij toe aan `demo/environment/README.md`**

Onder de bestaande `magazijn-a`-rij in de tabel:

```markdown
| [`logius`](logius/) | afnemer (outway naar `magazijn-a`) + aanbieder (inway, nog zonder dienst) | `00000000000000001000` | `mpfb-8wh`, deployment `fsc-logius` (co-located met de `uitvraag`-app) |
```

Werk in dezelfde tabel de `magazijn-a`-rij bij zodat ook daar de deployment (`fsc-magazijna`)
zichtbaar is, en pas de kolomkop aan naar `ZAD-project / deployment`.

- [ ] **Stap 4: Verifieer**

```bash
find demo/environment/logius -type d | sort
grep -n "logius" demo/environment/README.md
```

Verwacht: negen mappen; één tabelrij met OIN `00000000000000001000` en `fsc-logius`.

- [ ] **Stap 5: Commit**

```bash
git add demo/environment/logius/README.md demo/environment/README.md
git commit -m "docs(fsc): skelet demo/environment/logius met README's"
```

---

### Taak 2: PKI migreren en herparametriseren

**Files:**
- Create: `demo/environment/logius/pki/{init-ca.sh,issue.sh,verify.sh,gen-csr.sh,gen-crl.sh,combine-pem.sh,fix-permissions.sh,zad-bundle.sh}`
- Create: `demo/environment/logius/pki/{ca.json,config.json,intermediate.json,internal-ca.json}`
- Create: `demo/environment/logius/pki/README.md`
- Create: `demo/environment/logius/pki/peers/directory/{directory,manager}/csr.json`
- Create: `demo/environment/logius/pki/peers/logius/{manager,outway,inway,controller,txlog}/csr.json`

**Interfaces:**
- Consumes: de mappenstructuur uit taak 1.
- Produces: `gen-csr.sh` met `PEER=logius`, `OIN=00000000000000001000` en
  `ENDPOINTS=( "manager:logius-fscmgr" "outway:logius-fscoutway" "inway:logius-fscinway"
  "controller:logius-fscctl" "txlog:logius-fsctxlog" )`; de SAN-namen
  `<endpoint>.logius.fsc-test.local`, `logius-fsc<x>-fsc-logius-mpfb-8wh.rig.prd1.gn2.quattro.rijksapps.nl`
  en `fsc-logius-logius-fsc<x>` waar taak 3 en 4 op leunen.

- [ ] **Stap 1: Kopieer de scripts en CA-configs van `magazijn-a`, niet van de bronrepo**

`magazijn-a`'s kopieën zijn al repo-conform (geen EUPL-header, geen `#722`-issue-drift) en
verschillen inhoudelijk maar op één plek van de bronrepo: `gen-csr.sh`.

```bash
cd demo/environment
cp magazijn-a/pki/{init-ca.sh,issue.sh,verify.sh,gen-csr.sh,gen-crl.sh,combine-pem.sh,fix-permissions.sh,zad-bundle.sh} logius/pki/
cp magazijn-a/pki/{ca.json,config.json,intermediate.json,internal-ca.json} logius/pki/
cp magazijn-a/pki/peers/directory/directory/csr.json logius/pki/peers/directory/directory/csr.json
cp magazijn-a/pki/peers/directory/manager/csr.json logius/pki/peers/directory/manager/csr.json
chmod +x logius/pki/*.sh
```

- [ ] **Stap 2: Herparametriseer `logius/pki/gen-csr.sh`**

Vervang in de blokken "ZAD-topologie" en "Peer-identiteit":

```bash
PROJECT="${ZAD_PROJECT:-mpfb-8wh}"
DEPLOYMENT="${ZAD_DEPLOYMENT:-fsc-logius}"                # upsert-peer.sh neemt dit als arg (zelfde default)
...
PEER="logius"
OIN="00000000000000001000"                                # = subject.serialNumber = Peer ID
ENDPOINTS=( "manager:logius-fscmgr" "outway:logius-fscoutway" "inway:logius-fscinway" "controller:logius-fscctl" "txlog:logius-fsctxlog" )
```

Werk ook de header-comment bij: `de per-endpoint csr.json's van de ZAD-peer 'logius'
(pki/peers/logius/<endpoint>/csr.json)`. En de comment boven `ENDPOINTS`: die verwijst naar
`MGZ*_SVC` in `upsert-peer.sh` — maak er `LOG*_SVC` van (spoort met taak 4).

- [ ] **Stap 3: Genereer de CSR-templates**

`gen-csr.sh` is jq-only en heeft géén cfssl of netwerk nodig.

```bash
cd demo/environment/logius/pki && ./gen-csr.sh -f && cd -
```

- [ ] **Stap 4: Verifieer de gegenereerde CSR's**

```bash
jq -r '.CN, .names[0].serialNumber, (.hosts | join(" "))' \
  demo/environment/logius/pki/peers/logius/manager/csr.json
ls demo/environment/logius/pki/peers/logius/
```

Verwacht: `serialNumber` = `00000000000000001000`; hosts bevatten
`manager.logius.fsc-test.local`,
`logius-fscmgr-fsc-logius-mpfb-8wh.rig.prd1.gn2.quattro.rijksapps.nl`,
`fsc-logius-logius-fscmgr` en de bijbehorende `svc.cluster.local`-FQDN; vijf endpoint-mappen
(`manager`, `outway`, `inway`, `controller`, `txlog`).

```bash
grep -rn "uitvraag-org\|magazijn-a\|mpfuc-84g\|00000000000000000020" demo/environment/logius/pki/
```

Verwacht: geen treffers.

- [ ] **Stap 5: Schrijf `logius/pki/README.md`**

Neem `magazijn-a/pki/README.md` als basis (`cp`) en pas aan: peer-naam `logius`, OIN, de vijf
endpoints (dus mét `outway`), project/deployment `mpfb-8wh`/`fsc-logius`. Behoud de
kernwaarschuwing letterlijk: het trust-anchor is de test-CA van `moza-fsc-testnet` — kopieer
`ca/{root,intermediate}.pem` (+ keys) daaruit en draai **niet** `init-ca.sh`, dat maakt een
verse, vreemde CA (alleen bruikbaar voor de geïsoleerde lokale proof). De per-peer INTERNAL-CA
blijft wél lokaal/self-signed.

- [ ] **Stap 6: Commit**

```bash
git add demo/environment/logius/pki
git commit -m "feat(fsc): PKI voor peer logius (OIN 00000000000000001000, 5 endpoints)"
```

---

### Taak 3: Lokale docker-compose-harness migreren

**Files:**
- Create: `demo/environment/logius/deploy/local/{docker-compose.yaml,haproxy.cfg,postgres-init.sql,.env.example,README.md,run-smokes.sh,smoke-announce.sh}`

**Interfaces:**
- Consumes: de PKI-paden uit taak 2 (`pki/out/logius/<endpoint>/`, `pki/internal/logius/...`).
- Produces: een harness die op `127.0.0.1:8081` (directory-UI) en `127.0.0.1:8091`
  (controller-UI) bindt.

- [ ] **Stap 1: Kopieer de harness uit de bronrepo**

```bash
SRC=~/projects/moza-fsc-testconsumer
cp $SRC/deploy/local/{docker-compose.yaml,haproxy.cfg,postgres-init.sql,.env.example,README.md,run-smokes.sh,smoke-announce.sh} \
   demo/environment/logius/deploy/local/
chmod +x demo/environment/logius/deploy/local/*.sh
```

Let op: `deploy/local/.env` uit de bronrepo NIET kopiëren (bevat een wachtwoord en is daar ook
niet getrackt).

- [ ] **Stap 2: Hernoem de peer in alle zeven bestanden**

Service- en containernamen, PKI-paden en `NAME=`-env-vars:

```bash
cd demo/environment/logius/deploy/local
sed -i 's/uitvraag-org/logius/g' docker-compose.yaml haproxy.cfg postgres-init.sql .env.example README.md run-smokes.sh smoke-announce.sh
grep -rn "00000000000000000020" . && sed -i 's/00000000000000000020/00000000000000001000/g' *
cd -
```

Controleer daarna handmatig of `sed` geen samengestelde woorden heeft verminkt (bv. koppen als
"peer uitvraag-org" worden "peer logius" — dat is gewenst; "de uitvraag-organisatie" mag als
lopende tekst blijven staan en moet je dus terugzetten waar de betekenis "de organisatie" is,
niet "de peer").

- [ ] **Stap 3: Zet de OpenFSC-versie op v2.5.2 en gebruik de ghcr-wrappers**

In `docker-compose.yaml`: vervang elke `${IMAGE_TAG:-v1.43.7}` door `${IMAGE_TAG:-v2.5.2}`, en
vervang de lokaal gebouwde wrapper-image `manager-migrate:${IMAGE_TAG:-v1.43.7}` door
`ghcr.io/minbzk/moza-fsc-testnet-manager-migrate:${IMAGE_TAG:-v2.5.2}` — inclusief het
verwijderen van de bijbehorende `build:`-sectie als die er staat. Doe hetzelfde voor de
controller- en txlog-migratieservices als die een lokale build gebruiken; kijk hoe
`magazijn-a/deploy/local/docker-compose.yaml` het doet (regels met `migrate-`) en spiegel dat.

- [ ] **Stap 4: Verschuif de host-poorten naar 8081/8091**

```bash
cd demo/environment/logius/deploy/local
sed -i 's/127.0.0.1:8080:8080/127.0.0.1:8081:8080/; s/127.0.0.1:8090:8080/127.0.0.1:8091:8080/' docker-compose.yaml
grep -n "127.0.0.1:" docker-compose.yaml
cd -
```

Verwacht: `127.0.0.1:8081:8080` (directory-UI) en `127.0.0.1:8091:8080` (controller-UI). Alleen
de HOST-zijde verschuift; de container-poorten blijven `8080`. Documenteer de keuze in
`deploy/local/README.md` met de reden: `magazijn-a` bindt `8080`/`8090` al, en beide harnessen
moeten tegelijk kunnen draaien.

- [ ] **Stap 5: Verifieer zonder Docker**

```bash
cd demo/environment/logius/deploy/local
python3 -c "import yaml,sys; d=yaml.safe_load(open('docker-compose.yaml')); print(len(d['services']),'services'); print(sorted(d['services']))"
grep -n "image:" docker-compose.yaml
grep -rn "v1\.43\.7\|uitvraag-org\|manager-migrate:" .
bash -n run-smokes.sh smoke-announce.sh
cd -
```

Verwacht: de YAML parseert; elke `image:` staat op `v2.5.2` (behalve `postgres:17`,
`haproxy:2.9`, `curlimages/curl` en `busybox`-achtigen); geen treffers op `v1.43.7`,
`uitvraag-org` of een lokale `manager-migrate:`-tag; `bash -n` zwijgt.

- [ ] **Stap 6: Markeer de niet-uitvoerbare verificatie**

`docker compose config -q` en `./run-smokes.sh` vereisen Docker + door cfssl uitgegeven certs.
Ontbreken die in de omgeving, noteer dat expliciet in de commit-body en later in de PR-body als
openstaand — niet als "groen" rapporteren. (Met `podman` beschikbaar mag je
`podman compose config -q` proberen; slaagt dat niet, blijft het openstaand.)

- [ ] **Stap 7: Commit**

```bash
git add demo/environment/logius/deploy/local
git commit -m "feat(fsc): lokale compose-harness logius op OpenFSC v2.5.2, poorten 8081/8091"
```

---

### Taak 4: ZAD-rollout herparametriseren (co-locatie in `mpfb-8wh`, deployment `fsc-logius`)

**Files:**
- Create: `demo/environment/logius/deploy/zad/{upsert-peer.sh,postgres-init.sql,cert-manifest.md,README.md,verify-zad.md}`
- NIET meenemen: `deploy/zad/manager-migrate/` uit de bronrepo (build-context vervalt)

**Interfaces:**
- Consumes: de SAN-/Service-namen uit taak 2 (`fsc-logius-logius-fsc<x>`) — de interne
  FSC-edges in `upsert-peer.sh` moeten exact die namen gebruiken, anders faalt mTLS.
- Produces: componentnamen `logius-fsc{pg,mgr,ctl,outway,inway,txlog}` waar taak 6
  (`deploy.yml`) naar verwijst, en de cert-mountpaden `/etc/fsc/out/logius/...` en
  `/etc/fsc/internal/logius/...` waar `cert-manifest.md` op stuurt.

- [ ] **Stap 1: Kopieer uit de bronrepo (die heeft outway + inway; `magazijn-a` niet)**

```bash
SRC=~/projects/moza-fsc-testconsumer
cp $SRC/deploy/zad/{upsert-peer.sh,postgres-init.sql,cert-manifest.md,README.md,verify-zad.md} \
   demo/environment/logius/deploy/zad/
chmod +x demo/environment/logius/deploy/zad/upsert-peer.sh
```

- [ ] **Stap 2: Hernoem identiteit en componenten**

```bash
cd demo/environment/logius/deploy/zad
sed -i -e 's/uvrmgr/logius-fscmgr/g' -e 's/uvrctl/logius-fscctl/g' -e 's/uvrout/logius-fscoutway/g' \
       -e 's/uvrin/logius-fscinway/g' -e 's/uvrtxlog/logius-fsctxlog/g' -e 's/uvrpg/logius-fscpg/g' \
       -e 's/uitvraag-org/logius/g' -e 's/00000000000000000020/00000000000000001000/g' \
       -e 's/mpfuc-84g/mpfb-8wh/g' -e 's/ZAD_API_KEY_FSCUITVRAAG/ZAD_API_KEY_UITVRAAG/g' \
       upsert-peer.sh postgres-init.sql cert-manifest.md README.md verify-zad.md
cd -
```

Let op de volgorde: `uvrin` vóór `uvrtxlog`/`uvrpg` vervangen is veilig (geen overlap), maar
controleer met een grep dat er geen `uvr`-restanten zijn. De bash-variabelenamen (`UVRMGR_ENV`,
`UVROUT_SVC`, …) blijven hier ongewijzigd; die hernoem je in stap 3. `LOGOUTWAY_`/`LOGINWAY_`
in plaats van `LOGOUT_`/`LOGIN_`, zodat de prefixen niet als in-/uitloggen lezen.

- [ ] **Stap 3: Hernoem de bash-variabelen naar `LOG*`**

```bash
cd demo/environment/logius/deploy/zad
sed -i -e 's/\bUVRMGR_/LOGMGR_/g' -e 's/\bUVRCTL_/LOGCTL_/g' -e 's/\bUVROUT_/LOGOUTWAY_/g' \
       -e 's/\bUVRIN_/LOGINWAY_/g' -e 's/\bUVRTXLOG_/LOGTXLOG_/g' -e 's/\bUVRPG_/LOGPG_/g' upsert-peer.sh
grep -n "UVR\|uvr" upsert-peer.sh postgres-init.sql cert-manifest.md README.md verify-zad.md
cd -
```

Verwacht: geen treffers.

- [ ] **Stap 4: Zet deployment, project en versie**

In `upsert-peer.sh`, in de kop:

```bash
MODE="${1:?usage: upsert-peer.sh <validate|plan|apply> [deployment=fsc-logius] [tag=v2.5.2]}"
DEPLOYMENT="${2:-${ZAD_DEPLOYMENT:-fsc-logius}}"  # arg wint; anders ZAD_DEPLOYMENT (spoort met pki/gen-csr.sh)
IMAGE_TAG="${3:-v2.5.2}"                          # OpenFSC-versie: outway/inway stock-image + default-tag voor de migrate-wrappers
PROJECT="${ZAD_PROJECT:-mpfb-8wh}"
```

En in het image-blok het gewijzigde ghcr-pad (streepje in plaats van slash):

```bash
MANAGER_IMAGE="${ZAD_MANAGER_IMAGE:-ghcr.io/minbzk/moza-fsc-testnet-manager-migrate:${MANAGER_TAG}}"
CONTROLLER_IMAGE="${ZAD_CONTROLLER_IMAGE:-ghcr.io/minbzk/moza-fsc-testnet-controller-migrate:${CONTROLLER_TAG}}"
TXLOG_IMAGE="${ZAD_TXLOG_IMAGE:-ghcr.io/minbzk/moza-fsc-testnet-txlog-migrate:${TXLOG_TAG}}"
```

- [ ] **Stap 5: Neem de clone-veiligheidsnotitie over uit `magazijn-a`**

De bronrepo draaide in `test` van een eigen project; hier deelt de peer het project met de
`uitvraag`-app. Neem daarom de header-comment van `magazijn-a/deploy/zad/upsert-peer.sh`
letterlijk over (aangepast op naam): dat `:upsert-deployment` géén NIEUW deployment aanmaakt
(HTTP 202, maar het deployment verschijnt niet in `/deployments`) en `fsc-logius` dus éénmalig
leeg in de UI moet bestaan; en zet:

```bash
CLONE_FROM="${ZAD_PEER_CLONE_FROM:-}"            # leeg = geen clone; klonen van `test` zou de app-componenten meenemen
```

- [ ] **Stap 6: Werk de runbooks bij**

In `README.md` en `verify-zad.md`: project `mpfb-8wh`, deployment `fsc-logius`, secret
`ZAD_API_KEY_UITVRAAG`, de zes componentnamen, en de vervallen `zad-deploy-peer.yml`-workflow
vervangen door de verwijzing naar de `deploy-test-uitvraag`-job in
`.github/workflows/deploy.yml` (taak 6). Neem uit `magazijn-a/deploy/zad/README.md` de
waarschuwing over die daar staat over `clone-from: test` op de preview-deploy.

In `cert-manifest.md`: de mountpaden worden `/etc/fsc/out/logius/<endpoint>/` en
`/etc/fsc/internal/logius/<endpoint>/`, per component `logius-fsc<x>`. Behoud de twee
valkuilen letterlijk: internal-pad krijgt de INTERNAL-cert (group-cert daar geeft
`certificate is signed by 'Intermediate CA' and not by provided root CA`), group-pad krijgt de
group-cert INCLUSIEF aangehechte intermediate (leaf-only geeft dezelfde ketenfout).

- [ ] **Stap 7: Verifieer**

```bash
cd demo/environment/logius/deploy/zad
bash -n upsert-peer.sh
ZAD_API_KEY=dummy ./upsert-peer.sh plan 2>&1 | head -60
grep -rn "v1\.43\.7\|moza-fsc-testnet/\|mpfuc-84g\|uitvraag-org\|manager-migrate/" .
cd -
```

Verwacht: `bash -n` zwijgt; `plan` drukt de zes component-bodies af zonder netwerkcall, met
`"name": "logius-fscmgr"` etc., hosts op `*-fsc-logius-mpfb-8wh.rig.prd1.gn2.quattro.rijksapps.nl`,
interne edges op `fsc-logius-logius-fsc<x>:<poort>`, en een DSN met
`__SET_ZAD_PG_PASSWORD__` (placeholder — `apply` eist een echte `ZAD_PG_PASSWORD`). Geen
treffers op de grep.

Controleer in de `plan`-uitvoer expliciet de twee manager-poorten die niet uniform zijn: de
**outway** praat met de manager op `:9443` (`MANAGER_INTERNAL_ADDRESS`), de **inway** op
`:9444` (`MANAGER_INTERNAL_UNAUTHENTICATED_ADDRESS`). Niet gelijktrekken.

- [ ] **Stap 8: Commit**

```bash
git add demo/environment/logius/deploy/zad
git commit -m "feat(fsc): ZAD-rollout logius in mpfb-8wh/fsc-logius op v2.5.2"
```

---

### Taak 5: `docs/design.md` migreren + migratie-addendum

**Files:**
- Create: `demo/environment/logius/docs/design.md`

- [ ] **Stap 1: Kopieer en hernoem**

```bash
cp ~/projects/moza-fsc-testconsumer/docs/design.md demo/environment/logius/docs/design.md
cd demo/environment/logius/docs
sed -i -e 's/uitvraag-org/logius/g' -e 's/00000000000000000020/00000000000000001000/g' \
       -e 's/mpfuc-84g/mpfb-8wh/g' -e 's/v1\.43\.7/v2.5.2/g' \
       -e 's/uvrmgr/logius-fscmgr/g' -e 's/uvrctl/logius-fscctl/g' -e 's/uvrout/logius-fscoutway/g' \
       -e 's/uvrin/logius-fscinway/g' -e 's/uvrtxlog/logius-fsctxlog/g' -e 's/uvrpg/logius-fscpg/g' design.md
cd -
```

Loop het resultaat daarna handmatig na: waar "de uitvraag-organisatie" als organisatie bedoeld
is, hoort dat te blijven staan; alleen de peer-identiteit wordt `logius`.

- [ ] **Stap 2: Voeg onderaan een addendum toe**

```markdown
## Addendum 2026-08-06 — migratie naar demo/environment + co-locatie

Dit ontwerp is geschreven in de losse repo `moza-fsc-testconsumer`. Bij de migratie naar
`demo/environment/logius/` zijn de volgende punten gewijzigd:

- **Peer-identiteit:** `uitvraag-org` → `logius`, OIN `00000000000000000020` →
  `00000000000000001000`.
- **ZAD:** eigen project `mpfuc-84g`/deployment `test` → project `mpfb-8wh` (gedeeld met de
  `uitvraag`-app) in de eigen, preview-loze deployment `fsc-logius`. Componentnamen kregen de
  prefix `logius-fsc*`. API-key: `ZAD_API_KEY_UITVRAAG`.
- **OpenFSC:** `v1.43.7` → `v2.5.2` (de testfederatie handhaaft de FSC-versie als group rule).
- **Migratie-wrappers:** de lokale build-context `deploy/zad/manager-migrate/` is vervallen;
  manager, controller en txlog draaien de ghcr-wrappers uit `moza-fsc-testnet`.
- **CI:** `zad-deploy-peer.yml` is vervallen; tag-updates lopen via de `deploy-test-uitvraag`-job
  in `.github/workflows/deploy.yml`.
- **Lokale harness:** host-poorten `8080`/`8090` → `8081`/`8091`, zodat de harness naast die van
  `magazijn-a` kan draaien.

Rationale: `../../../docs/plans/2026-08-06-logius-peer-migratie-design.md`.
```

- [ ] **Stap 3: Verifieer**

```bash
grep -rn "uvr\|uitvraag-org\|mpfuc-84g\|v1\.43\.7" demo/environment/logius/docs/design.md
```

Verwacht: alleen treffers in het addendum (waar de oude waarden bewust als "was" genoemd worden).

- [ ] **Stap 4: Commit**

```bash
git add demo/environment/logius/docs/design.md
git commit -m "docs(fsc): design.md logius + migratie-addendum"
```

---

### Taak 6: FSC-peer-componenten toevoegen aan `deploy.yml`

**Files:**
- Modify: `.github/workflows/deploy.yml` (kop-comment rond regel 6; nieuwe stap in de job
  `deploy-test-uitvraag`, na de bestaande stap "Deploy uitvraag-project (test)")

**Interfaces:**
- Consumes: de componentnamen uit taak 4.

- [ ] **Stap 1: Werk de kop-comment bij**

Vervang de regel voor het uitvraag-project door de vorm die `magazijnen` al heeft:

```yaml
#   uitvraag       (mpfb-8wh)     componenten: redis + clickhouse + uitvraag in de deployments
#                                 `test`/`pr-<n>`, plus logius-fsc{pg,mgr,ctl,outway,inway,
#                                 txlog} (FSC-peer van de uitvraag-organisatie) in de EIGEN,
#                                 preview-loze deployment `fsc-logius` — zie
#                                 demo/environment/logius/
```

- [ ] **Stap 2: Voeg de deploy-stap toe aan `deploy-test-uitvraag`**

Direct onder de bestaande stap in die job, spiegel van de `fsc-magazijna`-stap:

```yaml
      # De FSC-peer draait in een EIGEN deployment `fsc-logius` binnen hetzelfde project, niet in
      # `test`. Previews klonen met `clone-from: test`; een gekloonde peer zou zich met dezelfde
      # federatie-OIN opnieuw aanmelden bij de gedeelde directory (en zonder de UI-only
      # cert-attachments direct crashloopen). Wat niet in `test` staat, kan niet meegekloond worden.
      # Deze stap doet alleen tag-updates; de eenmalige creatie (env/ports) loopt via
      # demo/environment/logius/deploy/zad/upsert-peer.sh.
      - name: Deploy uitvraag-project (fsc-logius — FSC-peer)
        uses: RijksICTGilde/zad-actions/deploy@13434cd415db0cd195a2c5f12bf67645acfcb635 # v4
        with:
          api-key: ${{ secrets.ZAD_API_KEY_UITVRAAG }}
          project-id: ${{ env.PROJECT_UITVRAAG }}
          deployment-name: fsc-logius
          components: |
            [
              {"name": "logius-fscpg", "image": "docker.io/library/postgres:17"},
              {"name": "logius-fscmgr", "image": "ghcr.io/minbzk/moza-fsc-testnet-manager-migrate:v2.5.2"},
              {"name": "logius-fscctl", "image": "ghcr.io/minbzk/moza-fsc-testnet-controller-migrate:v2.5.2"},
              {"name": "logius-fscoutway", "image": "docker.io/federatedserviceconnectivity/outway:v2.5.2"},
              {"name": "logius-fscinway", "image": "docker.io/federatedserviceconnectivity/inway:v2.5.2"},
              {"name": "logius-fsctxlog", "image": "ghcr.io/minbzk/moza-fsc-testnet-txlog-migrate:v2.5.2"}
            ]
```

- [ ] **Stap 3: Verifieer de YAML en de JSON-body**

```bash
python3 -c "import yaml; d=yaml.safe_load(open('.github/workflows/deploy.yml')); \
  s=d['jobs']['deploy-test-uitvraag']['steps']; print([x['name'] for x in s])"
python3 -c "import yaml,json; d=yaml.safe_load(open('.github/workflows/deploy.yml')); \
  s=[x for x in d['jobs']['deploy-test-uitvraag']['steps'] if 'fsc-logius' in x['name']][0]; \
  print(json.dumps(json.loads(s['with']['components']), indent=2))"
```

Verwacht: twee stappen in de job; de JSON parseert en bevat exact de zes componenten met
`v2.5.2`-tags. Let op: de `zad-actions/deploy`-SHA moet identiek zijn aan die van de andere
stappen in dit bestand — kopieer hem daar letterlijk vandaan in plaats van uit dit plan als hij
inmiddels is gebumpt.

- [ ] **Stap 4: Commit**

```bash
git add .github/workflows/deploy.yml
git commit -m "ci(zad): tag-updates voor de logius-FSC-peer in deployment fsc-logius"
```

---

### Taak 7: Eindverificatie — geen achtergebleven referenties

**Files:**
- Modify: `docs/plans/2026-08-06-logius-peer-migratie-design.md` (Status)
- Modify: `docs/plans/2026-08-06-logius-peer-migratie-plan.md` (Status)

- [ ] **Stap 1: Repo-brede greps op oude identiteiten**

```bash
git grep -n "uitvraag-org\|mpfuc-84g\|ZAD_API_KEY_FSCUITVRAAG\|00000000000000000020" -- . \
  ':!docs/plans/2026-08-06-*'
git grep -n "uvrmgr\|uvrctl\|uvrout\|uvrin\|uvrtxlog\|uvrpg" -- . ':!docs/plans/2026-08-06-*'
git grep -n "v1\.43\.7\|moza-fsc-testnet/manager-migrate" -- demo/
```

Verwacht: geen treffers buiten de twee plandocumenten (die noemen de oude waarden bewust als
"was"-kolom). Elke andere treffer is een gemiste rename — fix die vóór je verder gaat.

- [ ] **Stap 2: Controleer dat er geen sleutelmateriaal is meegekomen**

```bash
git status --porcelain
git ls-files demo/environment/logius | grep -E "\.(pem|key|crt)$|/(ca|out|internal|zad-upload)/|\.env$"
```

Verwacht: de tweede grep geeft niets. Zo niet: `git rm --cached` en controleer `.gitignore`.

- [ ] **Stap 3: Controleer de syntax van alles wat is toegevoegd**

```bash
for f in $(git ls-files 'demo/environment/logius/**/*.sh'); do bash -n "$f" || echo "SYNTAXFOUT: $f"; done
python3 -c "import yaml; yaml.safe_load(open('demo/environment/logius/deploy/local/docker-compose.yaml')); \
  yaml.safe_load(open('.github/workflows/deploy.yml')); print('yaml ok')"
jq -e . demo/environment/logius/pki/peers/logius/*/csr.json > /dev/null && echo "csr json ok"
```

- [ ] **Stap 4: Zet de Status van beide plandocumenten op Uitgevoerd**

```bash
sed -i '1s/.*/**Status:** Uitgevoerd/' docs/plans/2026-08-06-logius-peer-migratie-design.md \
                                       docs/plans/2026-08-06-logius-peer-migratie-plan.md
```

- [ ] **Stap 5: Commit**

```bash
git add -A
git commit -m "chore(fsc): eindverificatie logius-migratie + plandocumenten op Uitgevoerd"
```

- [ ] **Stap 6: Stel de PR-body samen**

Neem letterlijk op wat NIET geverifieerd kon worden (en waarom), plus de handmatige
vervolgstappen:

- Niet uitgevoerd in deze omgeving (geen `docker`/`cfssl`): certificaatuitgifte
  (`pki/issue.sh`, `verify.sh`, `zad-bundle.sh`) en de lokale smokes (`run-smokes.sh`).
- Handmatig, vóór de peer draait: `pki/ca/{root,intermediate}.pem` (+ keys) uit
  `moza-fsc-testnet` kopiëren (**niet** `init-ca.sh` draaien); deployment `fsc-logius` leeg
  aanmaken in de ZAD-UI; `ZAD_PG_PASSWORD` zetten en `upsert-peer.sh apply` draaien;
  cert-attachments mounten (UI-only, zie `cert-manifest.md`).
- Daarna: contract/grant tussen `logius` en `magazijn-a` aanmaken en de grant-hash als
  `MAGAZIJN_A_GRANT_HASH` + de outway-URL als `MAGAZIJN_A_URL` in de `mpfb-8wh`-projectspec
  zetten (buiten deze repo).
- Ten slotte: `moza-fsc-testconsumer` archiveren.

De PR staat op `feature/logius-peer-migratie`, gestapeld op `worktree-magazijn-a-peer-migratie`
(PR #160) — zet die branch als base zolang #160 open is. Geen reviewer toevoegen.
