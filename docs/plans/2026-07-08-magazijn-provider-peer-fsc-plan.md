**Status:** Concept

# Magazijn-provider-peer op de FSC-federatie — Implementatieplan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development
> (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use
> checkbox (`- [ ]`) syntax for tracking.

**Goal:** Een FSC provider-peer voor magazijn-a (manager + inway + controller + DB) die
`berichtenmagazijn` als vindbare dienst in de FSC-directory publiceert — eerst lokaal bewezen
via docker-compose, daarna uitgerold op ZAD naast de bestaande magazijn-app.

**Architecture:** Kopieer-template uit repo A (`moza-fsc-testnet` `example-provider`),
gesubstitueerd naar de echte magazijn-OIN en dienst `berichtenmagazijn`. Nieuwe artefacten in
een `fsc/`-boom in repo B. Control-plane only (announce + dienst-publicatie); het data-pad dóór
de inway is #728. Cert-pad: lokaal cfssl `issue.sh` + ZAD-attachment (het cert-portal draait niet
op ZAD — zie het bijbehorende design-document).

**Tech Stack:** OpenFSC (manager/inway/controller/directory-ui `v1.43.7`), cfssl (PKI), Docker
Compose + HAProxy (lokale SNI-mesh), Postgres 17, ZAD Operations Manager v2-API (`bash` + `curl`
+ `jq`), GitHub Actions (SHA-pinned, `zad-actions`).

## Global Constraints

- **Peer-OIN = Peer ID = `magazijnId`:** `00000001003214345000`. Staat 1:1 in het veld
  `serialnumber` van élke `csr.json` van deze peer. Nooit een andere waarde.
- **Peer-naam:** `magazijn-a` (nooit `example-*`; repo B verbiedt de neutrale template-namen).
- **Group ID:** `moza-fbs-test`. **Directory-OIN:** `00000000000000000010`.
- **ZAD-project:** `magazijnen` / id `mpfm-w3h` (co-located met de `magazijna`-app-component).
- **Dienst-naam:** `berichtenmagazijn`.
- **Image-pins:** FSC-images op `v1.43.7`; manager-wrapper
  `ghcr.io/minbzk/moza-fsc-testnet/manager-migrate:v1.43.7`. Nooit `:latest`.
- **Bestandsnamen:** geen spaties; `kebab-case`/`snake_case` (scripts/config) of `PascalCase`
  (bronnen). Shellscripts `set -euo pipefail`.
- **Taal:** comments/docs Nederlands; vaste FSC-idiomen (inway, outway, announce, grant, peer)
  Engels. Comment het *waarom*, niet het *wat*.
- **Git:** feature branch `feature/magazijn-provider-peer-fsc`, PR (nooit direct naar `main`,
  nooit een reviewer toevoegen; draft zolang CODEOWNERS bestaat).
- **Geen echte persoonsgegevens/BSN** in deze onboarding; `berichtenmagazijn`-upstream is
  control-plane (CreateService registreert alleen adres + endpoint_url).

## Bronbestanden in repo A (kopieer-template)

Repo B checkt repo A niet uit. Haal een bestand op met:

```bash
gh api "repos/MinBZK/moza-fsc-testnet/contents/<pad>" --jq '.content' | base64 -d > <doel>
```

Relevante paden: `pki/{init-ca.sh,issue.sh,verify.sh,zad-bundle.sh,config.json,internal-ca.json}`,
`pki/peers/example-provider/{manager,controller,inway,txlog}/csr.json`,
`deploy/local/{docker-compose.yaml,haproxy.cfg,postgres-init.sql,smoke-announce.sh,publish-service.sh,smoke-discover.sh,README.md}`,
`deploy/zad/{upsert-directory.sh,manager-migrate/*}`.

## Substitutie-tabel (repo A → repo B)

Pas overal toe bij het kopiëren:

| Repo A | Repo B |
|--------|--------|
| `example-provider` (naam/paden/hostnamen/aliases) | `magazijn-a` |
| OIN `00000000000000000030` | `00000001003214345000` |
| `example-service` | `berichtenmagazijn` |
| `stub-upstream` als endpoint_url (lokaal) | blijft stub lokaal; op ZAD → `magazijna`-component |
| DB-namen `fsc_example_provider` / `fsc_controller_example_provider` | `fsc_magazijn_a` / `fsc_controller_magazijn_a` |
| consumer-services (`example-consumer`, `-org`, keycloak) | **weglaten** (buiten scope) |

## File Structure (repo B, nieuw onder `fsc/`)

- `fsc/pki/` — `init-ca.sh`, `issue.sh`, `verify.sh`, `zad-bundle.sh`, `config.json`,
  `internal-ca.json`, `internal-ca` bundle, `group/` CA-materiaal (uit repo A group-config),
  `peers/magazijn-a/{manager,controller,inway,txlog}/csr.json`. Output (`out/`, `internal/`,
  `zad-upload/`) gitignored (privésleutels).
- `fsc/deploy/local/` — `docker-compose.yaml`, `haproxy.cfg`, `postgres-init.sql`,
  `smoke-announce.sh`, `publish-service.sh`, `smoke-discover.sh`, `.env.example`, `README.md`.
- `fsc/deploy/zad/` — `upsert-peer.sh` (NIEUW), `cert-manifest.md` (attachment-runbook).
- `.github/workflows/zad-deploy-peer.yml` — deploy-workflow (SHA-pinned).
- `.gitignore` — regels voor `fsc/pki/{out,internal,zad-upload}` en `fsc/deploy/local/.env`.
- `docs/plans/2026-07-08-magazijn-provider-peer-fsc-{design,plan}.md` — dit werk.

---

## Fase 1 — PKI voor magazijn-a

### Task 1: PKI-scaffolding + CSR's met de echte OIN

**Files:**
- Create: `fsc/pki/{init-ca.sh,issue.sh,verify.sh,zad-bundle.sh,config.json,internal-ca.json}`
- Create: `fsc/pki/group/` (root + intermediate CA-materiaal uit repo A's group-config)
- Create: `fsc/pki/peers/magazijn-a/{manager,controller,inway,txlog}/csr.json`
- Create: `.gitignore` (regels voor pki-output), `fsc/pki/README.md`

**Interfaces:**
- Produces: `fsc/pki/out/magazijn-a/<endpoint>/{cert,key}.pem` (GROUP-keten),
  `fsc/pki/internal/magazijn-a/<endpoint>/{cert,key}.pem` + `internal/magazijn-a/ca/root.pem`
  (INTERNAL-keten), na `issue.sh`. `<endpoint>` ∈ {manager, controller, inway, txlog}.

- [ ] **Step 1: Haal de pki-scripts + group-CA op uit repo A**

```bash
mkdir -p fsc/pki/peers/magazijn-a
for f in init-ca.sh issue.sh verify.sh zad-bundle.sh config.json internal-ca.json; do
  gh api "repos/MinBZK/moza-fsc-testnet/contents/pki/$f" --jq '.content' | base64 -d > "fsc/pki/$f"
done
chmod +x fsc/pki/*.sh
```

Haal het group-CA-materiaal op zoals repo A het aanlevert (`pki/ca/` + group-config). Controleer
in `fsc/pki/issue.sh` welke paden het verwacht (`ca/intermediate.pem`, `ca/intermediate-key.pem`)
en leg dezelfde structuur onder `fsc/pki/`. Draai anders `init-ca.sh` om een test-CA te genereren.

- [ ] **Step 2: Schrijf de vier `csr.json` met de echte OIN**

Voor elk endpoint (`manager`, `controller`, `inway`, `txlog`) — voorbeeld `inway`
(`fsc/pki/peers/magazijn-a/inway/csr.json`):

```json
{
  "CN": "inway.magazijn-a.fsc-test.local",
  "key": { "algo": "rsa", "size": 4096 },
  "hosts": ["inway.magazijn-a.fsc-test.local"],
  "serialnumber": "00000001003214345000",
  "names": [{ "O": "magazijn-a", "C": "NL" }]
}
```

Idem voor `manager`/`controller`/`txlog` (CN/hosts `<endpoint>.magazijn-a.fsc-test.local`,
`serialnumber` = de OIN, `O` = `magazijn-a`).

- [ ] **Step 3: `.gitignore` voor privésleutels**

Voeg toe aan `.gitignore` (repo-root):

```gitignore
# FSC-peer certificaten (privésleutels — nooit committen)
fsc/pki/out/
fsc/pki/internal/
fsc/pki/zad-upload/
fsc/deploy/local/.env
```

- [ ] **Step 4: Genereer de certs en verifieer (dit is de "test")**

```bash
cd fsc/pki && ./issue.sh && ./verify.sh; cd -
```

Expected: `OK: group-certs in .../out, internal-certs in .../internal`, en `verify.sh` groen
(keten valideert tegen de trust-anchor). Controleer dat de OIN in het cert zit:

```bash
openssl x509 -in fsc/pki/out/magazijn-a/inway/cert.pem -noout -subject | grep -o '00000001003214345000'
```

Expected: de OIN wordt geëchood (zit in `serialNumber` van het subject).

- [ ] **Step 5: Commit**

```bash
git add fsc/pki .gitignore
git commit -m "feat(fsc): PKI-scaffolding + CSR's magazijn-a (echte OIN)"
```

### Task 2: Cert-portal lokaal aantonen (AC-dekking "cert via cert-portal")

**Files:**
- Create: `fsc/pki/certportal-proof.md` (gedocumenteerde stappen + verificatie-commando's)

**Interfaces:**
- Consumes: `fsc/pki/` group-CA-materiaal uit Task 1.

- [ ] **Step 1: Zoek repo A's portal-bewijs op als basis**

```bash
gh api "repos/MinBZK/moza-fsc-testnet/contents/docs/superpowers/specs/2026-06-29-fsc-generiek-provider-onboarding-design.md" \
  --jq '.content' | base64 -d | sed -n '/Cert-portal-bewijs/,+8p'
```

Gebruik de `ca-cfssl` (`open-fsc-ca-cfssl-unsafe`, :8888) + `ca-certportal`
(`open-fsc-ca-certportal`) images; de portal is een client van ca-cfssl (`--ca-host`) en tekent
tegen dezelfde group-CA-config uit `fsc/pki/`.

- [ ] **Step 2: Schrijf `certportal-proof.md`: portal starten + cert aanvragen voor de OIN**

Documenteer concreet: start ca-cfssl + ca-certportal (met de `fsc/pki/` root+intermediate),
vraag via de portal een group-cert aan voor test-OIN `00000001003214345000`, en toon dat het
teruggegeven cert tegen de trust-anchor verifieert:

```bash
openssl verify -CAfile fsc/pki/group/root.pem <portal-cert>.pem   # Expected: OK
openssl x509 -in <portal-cert>.pem -noout -subject | grep 00000001003214345000
```

- [ ] **Step 3: Voer de stappen uit en leg de output vast in het document**

Run de gedocumenteerde stappen; plak de `openssl verify: OK`-output in `certportal-proof.md` als
bewijs. Expected: portal geeft een geldig group-cert; `verify` = OK.

- [ ] **Step 4: Commit**

```bash
git add fsc/pki/certportal-proof.md
git commit -m "docs(fsc): cert-portal lokaal aangetoond voor magazijn-OIN"
```

---

## Fase 2 — Lokale compose-proof (announce + publiceren)

### Task 3: Docker-compose met directory + magazijn-a-peer

**Files:**
- Create: `fsc/deploy/local/{docker-compose.yaml,haproxy.cfg,postgres-init.sql,.env.example,README.md}`

**Interfaces:**
- Consumes: certs uit Task 1 (`fsc/pki/{out,internal}/...` gemount op `/pki:ro`).
- Produces: draaiende containers `postgres`, `router`, `manager-directory`, `directory-ui`,
  `migrate-magazijn-a`, `manager-magazijn-a`, `migrate-controller-magazijn-a`,
  `controller-magazijn-a`, `txlog-magazijn-a`, `inway-magazijn-a`, `stub-upstream`, `toolbox`.
  Service-hostnamen/aliases: `<endpoint>.magazijn-a.fsc-test.local`.

- [ ] **Step 1: Haal repo A's lokale harness op**

```bash
mkdir -p fsc/deploy/local
for f in docker-compose.yaml haproxy.cfg postgres-init.sql .env.example README.md; do
  gh api "repos/MinBZK/moza-fsc-testnet/contents/deploy/local/$f" --jq '.content' | base64 -d \
    > "fsc/deploy/local/$f"
done
```

- [ ] **Step 2: Strip consumer + pas de substitutie-tabel toe**

Verwijder uit `docker-compose.yaml` álle `*-example-consumer`-services, `keycloak`, en de
`example-consumer`-aliassen/backends. Hernoem `example-provider` → `magazijn-a` overal
(servicenamen, network-aliases, `SELF_ADDRESS`, `CONTROLLER_REGISTRATION_API_ADDRESS`,
`MANAGER_INTERNAL_UNAUTHENTICATED_ADDRESS`, TLS-paden), en de DB-namen
(`fsc_example_provider` → `fsc_magazijn_a`, `fsc_controller_example_provider` →
`fsc_controller_magazijn_a`). Behoud de `manager-directory` + `directory-ui` (lokale directory om
naar te announcen). Behoud `stub-upstream` als lokale inway-upstream (het echte data-pad is #728).

Kernwaarden die per component blijven staan (mirror example-provider):
`GROUP_ID=moza-fbs-test`, `DIRECTORY_PEER_ID=00000000000000000010`,
`DIRECTORY_MANAGER_ADDRESS=https://directory.fsc-test.local:443`,
manager `SELF_ADDRESS=https://magazijn-a.fsc-test.local:443`,
inway `SELF_ADDRESS=https://inway.magazijn-a.fsc-test.local:443` + `NAME=magazijn-a-inway`,
controller `MANAGER_ADDRESS_INTERNAL=https://manager.magazijn-a.fsc-test.local:9443` +
`AUTHN_TYPE=none`.

- [ ] **Step 3: `haproxy.cfg` — alleen directory + magazijn-a + inway-SNI**

Vervang de backends door (verwijder de `ec`-backend):

```haproxy
frontend https_sni
    bind *:443
    tcp-request inspect-delay 5s
    tcp-request content accept if { req_ssl_hello_type 1 }
    use_backend dir       if { req_ssl_sni -i directory.fsc-test.local }
    use_backend mgz       if { req_ssl_sni -i magazijn-a.fsc-test.local }
    use_backend inway_mgz if { req_ssl_sni -i inway.magazijn-a.fsc-test.local }

backend dir
    server s1 manager-directory:8443
backend mgz
    server s1 manager-magazijn-a:8443
backend inway_mgz
    server s1 inway-magazijn-a:8443
```

Zet in de `router`-service de network-aliases: `directory.fsc-test.local`,
`magazijn-a.fsc-test.local`, `inway.magazijn-a.fsc-test.local`.

- [ ] **Step 4: Boot de stack (dit is de "test": komt alles gezond op?)**

```bash
cd fsc/deploy/local
cp .env.example .env   # zet PKI_DIR=../../pki, HOST_UID=$(id -u), HOST_GID=$(id -g)
docker compose up -d
sleep 20 && docker compose ps
cd -
```

Expected: geen container in `Exit`-status behalve de `migrate-*` (die horen `Exited (0)` te zijn
na succesvolle migratie). `manager-directory`, `manager-magazijn-a`, `controller-magazijn-a`,
`inway-magazijn-a` draaien.

- [ ] **Step 5: Commit**

```bash
git add fsc/deploy/local
git commit -m "feat(fsc): lokale compose-harness directory + magazijn-a-peer"
```

### Task 4: Announce-smoke groen

**Files:**
- Create: `fsc/deploy/local/smoke-announce.sh`

**Interfaces:**
- Consumes: draaiende stack uit Task 3.

- [ ] **Step 1: Haal `smoke-announce.sh` op en substitueer de OIN**

```bash
gh api "repos/MinBZK/moza-fsc-testnet/contents/deploy/local/smoke-announce.sh" --jq '.content' \
  | base64 -d > fsc/deploy/local/smoke-announce.sh && chmod +x fsc/deploy/local/smoke-announce.sh
```

Zet `PROVIDER_OIN="00000001003214345000"`, en vervang in de logs/servicenamen `example-provider`
→ `magazijn-a` (de `docker compose logs`-servicelijst: `manager-directory`, `migrate-magazijn-a`,
`manager-magazijn-a`). De query op `peers.peers` (kolom `id`, `manager_address LIKE '%:443'`)
blijft ongewijzigd.

- [ ] **Step 2: Draai eerst tégen een neergehaalde peer om falen te zien**

```bash
cd fsc/deploy/local && docker compose stop manager-magazijn-a
./smoke-announce.sh; echo "exit=$?"
```

Expected: FAIL na de timeout ("niet aangemeld op :443"), exit≠0.

- [ ] **Step 3: Herstart de peer en draai de smoke opnieuw (de "pass")**

```bash
docker compose start manager-magazijn-a
./smoke-announce.sh; echo "exit=$?"; cd -
```

Expected: `OK: magazijn-a is aangemeld bij de directory (manager_address op :443).`, exit=0. De
OIN `00000001003214345000` staat in `peers.peers`.

- [ ] **Step 4: Commit**

```bash
git add fsc/deploy/local/smoke-announce.sh
git commit -m "test(fsc): announce-smoke magazijn-a groen (AC: announce)"
```

### Task 5: Dienst `berichtenmagazijn` publiceren

**Files:**
- Create: `fsc/deploy/local/publish-service.sh`

**Interfaces:**
- Consumes: draaiende stack (Task 3), announce groen (Task 4).
- Produces: dienst `berichtenmagazijn` aangemaakt op de controller + servicePublication-contract
  bij de manager.

- [ ] **Step 1: Haal `publish-service.sh` op en substitueer**

```bash
gh api "repos/MinBZK/moza-fsc-testnet/contents/deploy/local/publish-service.sh" --jq '.content' \
  | base64 -d > fsc/deploy/local/publish-service.sh && chmod +x fsc/deploy/local/publish-service.sh
```

Zet: `SERVICE_NAME="berichtenmagazijn"`, `PROVIDER_OIN="00000001003214345000"`,
`DIR_OIN="00000000000000000010"`, `GROUP_ID="moza-fbs-test"`,
`STUB_URL="http://stub-upstream:8080"` (lokaal). Vervang in cert-paden + controller/manager-URL's
`example-provider` → `magazijn-a`. De inway-adres-grep wordt
`https://inway\.magazijn-a\.fsc-test\.local:443`.

- [ ] **Step 2: Draai de publish (idempotent) — de "test" is een succesvol contract**

```bash
cd fsc/deploy/local && ./publish-service.sh; echo "exit=$?"; cd -
```

Expected: `inway_address=https://inway.magazijn-a.fsc-test.local:443`, `aangemaakt.` (of
`bestaat al, skip create.`), en `contract ingediend (manager signt; directory auto-accept)` met
een `content_hash` in de respons. exit=0.

- [ ] **Step 3: Draai nogmaals om idempotentie te bevestigen**

```bash
cd fsc/deploy/local && ./publish-service.sh; echo "exit=$?"; cd -
```

Expected: `bestaat al, skip create.` + `al gepubliceerd, skip contract.`, exit=0.

- [ ] **Step 4: Commit**

```bash
git add fsc/deploy/local/publish-service.sh
git commit -m "feat(fsc): berichtenmagazijn publiceren op de controller + ServicePublicationGrant"
```

### Task 6: Discover-smoke — `berichtenmagazijn` vindbaar in de directory

**Files:**
- Create: `fsc/deploy/local/smoke-discover.sh`

**Interfaces:**
- Consumes: publish uit Task 5.

- [ ] **Step 1: Bouw een discover-check zonder consumer-peer**

De repo-A `smoke-discover.sh` bevraagt via een consumer-manager; die peer bestaat hier niet.
Bewijs vindbaarheid in plaats daarvan direct op de directory-DB (tabel `services`), gemodelleerd
op de announce-smoke. Maak `fsc/deploy/local/smoke-discover.sh`:

```bash
#!/usr/bin/env bash
# Smoke: bewijst dat de door magazijn-a gepubliceerde dienst `berichtenmagazijn` vindbaar is in
# de directory (system-of-record). Pollt de directory-DB tot de dienst voor de magazijn-OIN
# verschijnt. Vereist dat publish-service.sh eerst draaide.
set -euo pipefail

COMPOSE=(docker compose -f "$(dirname "$0")/docker-compose.yaml")
PROVIDER_OIN="00000001003214345000"
SERVICE_NAME="berichtenmagazijn"
TIMEOUT=60
INTERVAL=5

ERRLOG=$(mktemp); trap 'rm -f "$ERRLOG"' EXIT

echo "smoke-discover: wachten tot ${SERVICE_NAME} (${PROVIDER_OIN}) vindbaar is in de directory..."
elapsed=0
while [ "$elapsed" -lt "$TIMEOUT" ]; do
  rows=$("${COMPOSE[@]}" exec -T postgres psql -U postgres -d fsc_directory -tA \
    -c "SELECT name FROM services WHERE peer_id = '${PROVIDER_OIN}';" 2>"$ERRLOG" || true)
  if printf '%s\n' "$rows" | grep -qx "$SERVICE_NAME"; then
    echo "OK: ${SERVICE_NAME} is vindbaar in de directory."
    "${COMPOSE[@]}" exec -T postgres psql -U postgres -d fsc_directory \
      -c "SELECT peer_id, name FROM services;" || true
    echo "SMOKE-DISCOVER GROEN."; exit 0
  fi
  sleep "$INTERVAL"; elapsed=$((elapsed + INTERVAL))
  echo "  ...nog niet vindbaar (${elapsed}s)"
done

echo "FAIL: ${SERVICE_NAME} niet vindbaar binnen ${TIMEOUT}s (publish-service.sh gedraaid?)." >&2
[ -s "$ERRLOG" ] && { echo "  -> laatste psql-fout:" >&2; tail -n 3 "$ERRLOG" >&2; }
"${COMPOSE[@]}" logs --tail=50 manager-directory manager-magazijn-a controller-magazijn-a >&2 || true
exit 1
```

> **Verifieer** eerst het echte schema van de directory `services`-tabel (kolomnamen
> `peer_id`/`name`) met `docker compose exec postgres psql -U postgres -d fsc_directory -c '\d+ services'`
> en pas de query aan indien afwijkend — de tabelnaam/kolom is een schema-contract.

- [ ] **Step 2: Run de smoke (pass, want Task 5 publiceerde al)**

```bash
cd fsc/deploy/local && chmod +x smoke-discover.sh && ./smoke-discover.sh; echo "exit=$?"; cd -
```

Expected: `OK: berichtenmagazijn is vindbaar in de directory.` + `SMOKE-DISCOVER GROEN.`, exit=0.

- [ ] **Step 3: Voeg een `run-smokes.sh`-wrapper toe (announce → publish → discover)**

```bash
#!/usr/bin/env bash
set -euo pipefail
d="$(dirname "$0")"
"$d/smoke-announce.sh"
"$d/publish-service.sh"
"$d/smoke-discover.sh"
echo "ALLE SMOKES GROEN."
```

Run `./run-smokes.sh` tegen de draaiende stack. Expected: `ALLE SMOKES GROEN.`

- [ ] **Step 4: Commit**

```bash
git add fsc/deploy/local/smoke-discover.sh fsc/deploy/local/run-smokes.sh
git commit -m "test(fsc): discover-smoke berichtenmagazijn vindbaar (AC: dienst gepubliceerd)"
```

---

## Fase 3 — ZAD-deploy naast de magazijn-app

### Task 7: Cert-bundle voor ZAD-attachments

**Files:**
- Create: `fsc/deploy/zad/cert-manifest.md`
- Uses: `fsc/pki/zad-bundle.sh` (uit Task 1)

**Interfaces:**
- Consumes: certs uit Task 1.
- Produces: `fsc/pki/zad-upload/magazijn-a/` met per-bestand het beoogde pod-pad (`/etc/fsc/...`)
  + de bijbehorende `TLS_*`-env-var (MANIFEST).

- [ ] **Step 1: Bundel de magazijn-a-certs**

```bash
cd fsc/pki && ./zad-bundle.sh magazijn-a; cd -
cat fsc/pki/zad-upload/magazijn-a/MANIFEST.md
```

Expected: `zad-upload/magazijn-a/` bevat de group- + internal-certs met een MANIFEST dat elk
bestand koppelt aan `/etc/fsc/...` + `TLS_GROUP_*`/`TLS_*`-env-var.

- [ ] **Step 2: Schrijf `cert-manifest.md` — de handmatige ZAD-attachment-runbook**

Documenteer per component (`mgzmgr`, `mgzctl`, `mgzinway`) welke bestanden uit
`zad-upload/magazijn-a/` als **bijlage** op welk pod-pad gemount worden (UI-only stap in
Operations Manager; de v2-API dekt geen bijlagen — zoals repo A's directory-deploy). Neem de
`TLS_*`-env-var-mapping over uit het MANIFEST.

- [ ] **Step 3: Commit** (alleen het manifest-document; `zad-upload/` is gitignored)

```bash
git add fsc/deploy/zad/cert-manifest.md
git commit -m "docs(fsc): ZAD cert-attachment-runbook magazijn-a"
```

### Task 8: `upsert-peer.sh` — provider-componenten op ZAD

**Files:**
- Create: `fsc/deploy/zad/upsert-peer.sh`

**Interfaces:**
- Consumes: `ZAD_API_KEY_MAGAZIJNEN` (env), project `mpfm-w3h`, ZAD v2-API.
- Produces: componenten `mgzmgr` + `mgzctl` + `mgzinway` op deployment `test` (of `pr-<n>`) in
  `mpfm-w3h`.

- [ ] **Step 1: Neem `upsert-directory.sh` als skelet**

```bash
gh api "repos/MinBZK/moza-fsc-testnet/contents/deploy/zad/upsert-directory.sh" --jq '.content' \
  | base64 -d > fsc/deploy/zad/upsert-peer.sh && chmod +x fsc/deploy/zad/upsert-peer.sh
```

Behoud de structuur: `validate|plan|apply`-modi, `component_body()` (jq),
`poll_task()`/`post()`, de managed-Postgres-alias
`STORAGE_POSTGRES_DSN=postgres://$DATABASE_SERVER_USER:$DATABASE_PASSWORD@$DATABASE_SERVER_HOST:5432/$DATABASE_DB?sslmode=disable`,
en het `domain_format:"component-deployment-project"`.

- [ ] **Step 2: Herdefinieer de env-blobs voor de drie provider-componenten**

Zet `PROJECT="${ZAD_PROJECT:-mpfm-w3h}"`. Vervang de directory-component-bodies door drie
provider-bodies. Spiegel de env uit de compose (Task 3) maar met ZAD-aliases
(`SELF_ADDRESS`/adressen op `:443` via `$DEPLOYMENT_NAME`-hostnamen, managed-Postgres-DSN):

- `mgzmgr` (image `manager-migrate`, poort 8443, service `["postgresql-database"]`): manager-mode
  env — `GROUP_ID=moza-fbs-test`, `DIRECTORY_PEER_ID=00000000000000000010`,
  `AUTO_SIGN_GRANTS=` (leeg; alleen de directory auto-signt), `TX_LOG_API_ADDRESS=` (leeg, #728),
  `CONTROLLER_REGISTRATION_API_ADDRESS=https://mgzctl-$DEPLOYMENT_NAME-mpfm-w3h.<base-domain>:443`,
  `LISTEN_ADDRESS_EXTERNAL=0.0.0.0:8443` + internal 9443/9444, alias
  `SELF_ADDRESS=https://mgzmgr-$DEPLOYMENT_NAME-mpfm-w3h.<base-domain>:443`,
  `DIRECTORY_MANAGER_ADDRESS=https://<directory-manager-host>:443`, de `TLS_*`-paden op de
  attachment-mounts (`/etc/fsc/...`, zie Task 7).
- `mgzctl` (image `controller:v1.43.7`, poort 8080, eigen managed DB): `AUTHN_TYPE=none`,
  `GROUP_ID`, `DIRECTORY_PEER_ID`,
  `MANAGER_ADDRESS_INTERNAL=https://mgzmgr-$DEPLOYMENT_NAME-mpfm-w3h.<base-domain>:443`,
  Registration/Administration-API-poorten, `TLS_*`-internal-paden.
- `mgzinway` (image `inway:v1.43.7`, poort 8443): `NAME=magazijn-a-inway`,
  `SELF_ADDRESS=https://mgzinway-$DEPLOYMENT_NAME-mpfm-w3h.<base-domain>:443`,
  `CONTROLLER_REGISTRATION_API_ADDRESS=…mgzctl…:443`,
  `MANAGER_INTERNAL_UNAUTHENTICATED_ADDRESS=…mgzmgr…:443`, en de **upstream** naar de app:
  `magazijna` intra-project (zie Open punt — verifieer de exacte intra-project-DNS-vorm).

> **Base-domain:** `rig.prd1.gn2.quattro.rijksapps.nl` (zie repo B `deploy.yml`). Gebruik de
> `$DEPLOYMENT_NAME`-substitutievar letterlijk (`\$` in de body) zodat previews hun eigen buren
> raken.

- [ ] **Step 3: `plan`-mode toont correcte bodies (de "test")**

```bash
ZAD_PROJECT=mpfm-w3h ./fsc/deploy/zad/upsert-peer.sh plan test v1.43.7
```

Expected: drie component-bodies (mgzmgr/mgzctl/mgzinway) met geldige JSON (jq-geparsed), correcte
`:443`-adressen en `mpfm-w3h` in de hostnamen. Geen mutatie.

- [ ] **Step 4: `validate` tegen ZAD (read-only auth-check)**

```bash
export ZAD_API_KEY="$ZAD_API_KEY_MAGAZIJNEN"
./fsc/deploy/zad/upsert-peer.sh validate
```

Expected: `auth OK` + lijst van bestaande deployments/componenten in `mpfm-w3h` (o.a. `magazijna`).

- [ ] **Step 5: Commit**

```bash
git add fsc/deploy/zad/upsert-peer.sh
git commit -m "feat(fsc): ZAD upsert-peer.sh voor magazijn-a provider-componenten"
```

### Task 9: Deploy-workflow `zad-deploy-peer.yml`

**Files:**
- Create: `.github/workflows/zad-deploy-peer.yml`

**Interfaces:**
- Consumes: `secrets.ZAD_API_KEY_MAGAZIJNEN`, `fsc/deploy/zad/upsert-peer.sh`.

- [ ] **Step 1: Modelleer op repo A's `zad-deploy-directory.yml` + repo B-conventies**

```bash
gh api "repos/MinBZK/moza-fsc-testnet/contents/.github/workflows/zad-deploy-directory.yml" \
  --jq '.content' | base64 -d > /tmp/zad-deploy-directory.yml
```

Neem over: `permissions: read-all` op workflow-niveau, path-filter op `fsc/deploy/zad/**` +
`.github/workflows/zad-deploy-peer.yml`, PR → `pr-<nummer>` / push main → `test`, en het aanroepen
van `upsert-peer.sh "${MODE}" "${DEPLOYMENT}" "${IMAGE_TAG}"`. **Pin alle actions op SHA**
(consistent met repo B's bestaande workflows). Secret: `ZAD_API_KEY_MAGAZIJNEN`.

- [ ] **Step 2: Valideer de workflow-syntax lokaal**

```bash
gh workflow view zad-deploy-peer.yml 2>/dev/null || python3 -c "import yaml,sys; yaml.safe_load(open('.github/workflows/zad-deploy-peer.yml'))" && echo "YAML OK"
```

Expected: `YAML OK` (of `gh` toont de workflow zonder parse-fout).

- [ ] **Step 3: Verifieer SHA-pinning (geen `@v`/`@main`-refs)**

```bash
grep -nE "uses:.*@(v[0-9]|main|master)" .github/workflows/zad-deploy-peer.yml && echo "FOUT: unpinned" || echo "OK: alles SHA-gepind"
```

Expected: `OK: alles SHA-gepind`.

- [ ] **Step 4: Commit**

```bash
git add .github/workflows/zad-deploy-peer.yml
git commit -m "ci(fsc): SHA-pinned deploy-workflow magazijn-provider-peer"
```

### Task 10: ZAD apply + handmatige mount-stappen

**Files:**
- Modify: `fsc/deploy/zad/cert-manifest.md` (aanvullen met de "Publicatie op het web"-stap)

**Interfaces:**
- Consumes: Task 7 (bundle), Task 8 (upsert), Task 9 (workflow).

- [ ] **Step 1: Apply de componenten naar deployment `test`**

```bash
export ZAD_API_KEY="$ZAD_API_KEY_MAGAZIJNEN"
./fsc/deploy/zad/upsert-peer.sh apply test v1.43.7
```

Expected: `HTTP 2xx` + `task ... completed` voor mgzmgr/mgzctl/mgzinway.

- [ ] **Step 2: Handmatige UI-stappen (documenteer + voer uit)**

In Operations Manager (`mpfm-w3h`, deployment `test`): (a) mount de cert-bijlagen op elk
component-pod-pad per `cert-manifest.md`; (b) zet "Publicatie op het web" modus 2 (SNI-passthrough)
op `mgzmgr` (external `:8443`) en `mgzinway` (data `:8443`), zodat het `:443`-mesh-adres klopt met
`SELF_ADDRESS`. Vul deze stappen aan in `cert-manifest.md`.

- [ ] **Step 3: Verifieer dat de pods gezond opkomen**

```bash
./fsc/deploy/zad/upsert-peer.sh validate
```

Expected: `mgzmgr`, `mgzctl`, `mgzinway` staan in `mpfm-w3h`/`test`. Controleer in de UI dat geen
pod crash-loopt (certs correct gemount).

- [ ] **Step 4: Commit**

```bash
git add fsc/deploy/zad/cert-manifest.md
git commit -m "docs(fsc): ZAD apply-runbook magazijn-provider-peer afgerond"
```

---

## Fase 4 — ZAD-verificatie tegen de echte directory

### Task 11: Announce + dienst vindbaar op ZAD; app-tests groen

**Files:**
- Create: `fsc/deploy/zad/verify-zad.md` (verificatie-runbook + vastgelegde output)

**Interfaces:**
- Consumes: draaiende peer op ZAD (Task 10), de echte directory (repo A, deployment `test`).

- [ ] **Step 1: Announce — magazijn-OIN in de directory op ZAD**

Bevraag de directory (UI `dirui` of directory-DB via de directory-deploy) op de peer-OIN:

```bash
# via directory-ui: open https://dirui-test-mft-tp9.<base-domain>/ en zoek magazijn-a / de OIN
# of via de directory-manager peers-API (group-cert vereist).
```

Expected: `00000001003214345000` met `manager_address` op `:443` staat als aangemelde peer.

- [ ] **Step 2: Publiceer `berichtenmagazijn` op de ZAD-controller**

Draai de publicatie tegen de ZAD-controller (`mgzctl`, Administration-API) + manager
(ServicePublicationGrant) — een ZAD-variant van `publish-service.sh` met de `:443`-adressen i.p.v.
de compose-servicenamen, of via de controller-beheer-UI. Documenteer de gekozen route in
`verify-zad.md`.

Expected: `berichtenmagazijn` aangemaakt + contract met `content_hash`; directory auto-signt.

- [ ] **Step 3: Discover — dienst vindbaar in de ZAD-directory**

Expected: `berichtenmagazijn` voor peer `00000001003214345000` verschijnt in de directory (UI +
DB). Leg een screenshot/CLI-output vast in `verify-zad.md`.

- [ ] **Step 4: Bestaande FBS-app-tests blijven groen (config-only geen app-wijziging)**

```bash
docker compose up -d
./mvnw clean test -pl services/berichtenmagazijn -am
```

Expected: `BUILD SUCCESS`. (De app-code is niet gewijzigd; dit bewijst dat de FSC-toevoeging de
build niet raakt.)

- [ ] **Step 5: Commit + de vier AC's van #780 afvinken**

```bash
git add fsc/deploy/zad/verify-zad.md
git commit -m "docs(fsc): ZAD-verificatie magazijn-provider-peer (AC's #780 gedekt)"
```

Vink in #780 af: peer draait (manager+inway+controller+DB) · cert (portal lokaal + attachment) ·
announce · `berichtenmagazijn` vindbaar.

---

## Afronding

- Draai `superpowers:requesting-code-review` op de branch vóór de PR.
- Open een **draft**-PR (CODEOWNERS bestaat → nooit auto-reviewer), koppel aan #780 en epic #737
  via de GitHub-issue-relatie.
- Werk `CLAUDE.md` bij met de nieuwe `fsc/`-boom + `zad-actions`-project-context als dat de
  volgende ontwikkelaar helpt (aparte, kleine commit).

## Verificatie-overzicht (Definition of Done)

| # | Bewijs | Hoe |
|---|--------|-----|
| 1 | PKI genereert magazijn-a-certs met de echte OIN | `issue.sh` + `verify.sh` + `openssl` (Task 1) |
| 2 | Cert-portal lokaal aangetoond | `certportal-proof.md` + `openssl verify: OK` (Task 2) |
| 3 | Peer meldt zich aan (announce) — lokaal | `smoke-announce.sh` groen (Task 4) |
| 4 | `berichtenmagazijn` vindbaar — lokaal | `publish-service.sh` + `smoke-discover.sh` groen (Task 5-6) |
| 5 | Peer draait op ZAD in mpfm-w3h | `upsert-peer.sh apply` + `validate` (Task 8-10) |
| 6 | Announce + dienst vindbaar op de echte directory | `verify-zad.md` (Task 11) |
| 7 | FBS-app-tests blijven groen | `./mvnw clean test -pl services/berichtenmagazijn -am` (Task 11) |
