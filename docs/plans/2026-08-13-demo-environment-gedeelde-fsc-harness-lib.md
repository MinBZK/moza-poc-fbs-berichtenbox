**Status:** Uitgevoerd

# Demo-environment-gedeelde-FSC-harness-lib Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Vervang de duplicatie van het ERRLOG-/curl-/contract-bootstrap-idioom tussen `demo/environment/logius/deploy/local/*.sh` en `demo/environment/magazijn-a/deploy/local/*.sh` door één gedeelde `demo/environment/lib/fsc-harness.sh`, gesourced door beide peers (optie D uit PR #166, comment #issuecomment-5277020399). Aanleiding voor D boven C (lib.sh per peer): er komt binnenkort een derde peer bij, waarmee de "rule of three"-drempel uit `docs/plans/2026-07-30-demo-environment-fsc-peers-design.md:111-117` voor het eerst overschreden wordt.

**Architecture:** Eén nieuw bestand `demo/environment/lib/fsc-harness.sh` met de peer-onafhankelijke functies (ERRLOG-levenscyclus, ANSI/banner-filtering, curl-in-toolbox, UUID/validity, jq-contract-helpers). Peer-identiteit (OIN's, servicenamen, cert-paden, MANAGER/CONTROLLER-URL's, TIMEOUT/INTERVAL, GROUP_ID) blijft ongewijzigd in elk script staan. Elk van de 7 bestaande scripts (4× logius, 3× magazijn-a — magazijn-a heeft geen `consume-service.sh`) sourcet de lib met een pad relatief aan zichzelf, zodat er geen hardcoded absoluut pad ontstaat.

Dit is méér dan een mechanische refactor: `magazijn-a`'s scripts missen vandaag de `strip_wrapper_noise`/ANSI-fix en gebruiken `curl -s` i.p.v. `-sS` (logius kreeg dat al in commits `b2de38dd`/`64d4ffb8`). Door magazijn-a op dezelfde lib te zetten, krijgt het die fix automatisch mee — een bewuste, meegenomen bugfix (dit is ook "bevinding B" uit de PR-review), niet een toevallige bijwerking.

**Tech Stack:** Bash (`set -euo pipefail`), curl, jq, sed/grep (POSIX + GNU-sed-vermijding voor macOS-compat — bash 3.2, geen `mapfile`/associative arrays/`${var^^}`, zie `demo/environment/logius/deploy/local/smoke-services.sh:20`).

## Global Constraints

- Scope is `demo/environment/{logius,magazijn-a}/deploy/local/*.sh` + de nieuwe `demo/environment/lib/`. **Niet** de `pki/`-scripts (6 van de 8 zijn byte-identiek tussen peers, maar dat is een apart, groter deduplicatie-onderwerp dat hier bewust buiten scope blijft — geen scope creep op een al aanwezige review-discussie).
- `HERE="$(cd "$(dirname "$0")" && pwd)"` wordt de uniforme vorm in alle 7 scripts (i.p.v. de huidige mix van kale `$(dirname "$0")` en de robuustere `cd`-vorm) — nodig voor een betrouwbaar relatief pad naar de lib, ongeacht vanuit welke cwd het script wordt aangeroepen.
- Peer-specifieke variabelen (OIN's, `SERVICE_NAME`, cert-paden, `MANAGER`/`CONTROLLER`, `TIMEOUT`/`INTERVAL`, `GROUP_ID`, `STUB_URL`) blijven letterlijk staan waar ze nu staan — geen configuratie-indirectie toevoegen die niet gevraagd is.
- Geen wijziging aan `.github/workflows/fsc-harness-overlays.yml`: het bestaande `paths: ['demo/environment/**', ...]`-filter dekt `demo/environment/lib/` al automatisch; de job-inhoud zelf (`for peer in demo/environment/*/deploy/local`) matcht de nieuwe `lib/`-map sowieso niet (geen `deploy/local/docker-compose.yaml` erin), dus geen aanpassing nodig. Met één bron van waarheid (D) vervalt ook de drift-guard die C nodig had (verschillende lib.sh-kopieën identiek houden) — dat is een concreet voordeel van D t.o.v. C.
- Documentatie bijwerken: `docs/plans/2026-07-30-demo-environment-fsc-peers-design.md:111-117` (zegt nu expliciet "bewust NIET gededupliceerd... rule of three") en `docs/plans/2026-08-12-logius-harness-podman-smoke-diagnostiek-en-grant-hash.md:11` (Global Constraints: "Geen nieuwe gedeelde bash-library") zijn met dit plan feitelijk achterhaald — beide krijgen een korte "Aanvulling"-notitie die naar dit plan verwijst, zonder de historische tekst zelf te herschrijven (plannen zijn een append-only geschiedenis, zie CLAUDE.md "Plannen").
- `demo/environment/README.md:13-15` ("Elke peer-map bevat dezelfde indeling...") blijft feitelijk kloppen (de `lib/`-map staat naast de peer-mappen, niet erin) — geen wijziging nodig.
- Verificatie: `bash -n` op alle 8 gewijzigde/nieuwe bestanden, plus een klein fixture-testscript dat `fsc_scrub_errlog` toetst tegen de exacte ANSI-bytes uit de PR-bug-report (de reproductie die al gebruikt is bij het fixen van bevinding 3 op PR #166). Geen Docker/podman-stack nodig voor deze verificatie — puur bash-niveau.

---

### Taak 1: `demo/environment/lib/fsc-harness.sh` aanmaken

**Files:**
- Create: `demo/environment/lib/fsc-harness.sh`

**Interfaces:**
- Produces: `fsc_errlog_init`, `fsc_scrub_errlog [bestand]`, `fsc_warn_errlog "<prefix>"`, `fsc_last_error [n]`, `fsc_tb <curl-args...>`, `fsc_new_iv`, `fsc_validity`, `fsc_have_jq`, `fsc_accept_state <json> <content_hash> <oin>`, `fsc_contract_state <json> <content_hash>`, `fsc_grant_hash <json> <content_hash> <service_name> <outway_thumbprint>`.
- Consumes (uit caller-scope, niet hier gezet): `$ERRLOG` (door `fsc_errlog_init` zelf gezet), `$COMPOSE`/`$CERT`/`$KEY`/`$CA`/`$MANAGER` (peer-specifiek, door elk script zelf gezet vóór gebruik van `fsc_tb`/`fsc_have_jq`-afhankelijke functies).

- [ ] **Stap 1: Bestand aanmaken**

```bash
#!/usr/bin/env bash
# Gedeelde helpers voor de lokale FSC-peer-harnessen onder demo/environment/<peer>/deploy/local/.
# Peer-identiteit (OIN's, servicenamen, cert-paden, MANAGER/CONTROLLER-URL's, TIMEOUT/INTERVAL,
# GROUP_ID) staat in elk script zelf — hier alleen het generieke idioom. bash 3.2-compatibel
# (macOS-default, zie smoke-services.sh): geen associative arrays, geen mapfile, geen ${var^^}.

# --- ERRLOG-levenscyclus --------------------------------------------------------------------

# fsc_errlog_init: mktemp + trap, zet de globale $ERRLOG. Aanroepen ná `set -euo pipefail`.
fsc_errlog_init() {
  ERRLOG=$(mktemp)
  trap 'rm -f "$ERRLOG"' EXIT
}

# fsc_scrub_errlog [bestand]: verwijdert de podman-external-compose-provider-banner (met zijn
# SGR-ANSI-omhulsel, bv. ESC[4m vóór de tekst) uit het gegeven bestand (default $ERRLOG), in
# place. ANSI eerst strippen (portable LC_ALL=C-vorm i.p.v. \x1b, een GNU-sed-extensie die
# BSD-sed/macOS niet kent), dan zonder regelanker filteren (de banner start niet op kolom 1
# door de ANSI-prefix), dan lege regels weggooien die overblijven na het strippen van de losse
# ESC[0m-regel.
fsc_scrub_errlog() {
  local file="${1:-$ERRLOG}"
  LC_ALL=C sed -e $'s/\033\\[[0-9;]*m//g' "$file" \
    | grep -v 'Executing external compose provider' \
    | grep -v '^[[:space:]]*$' > "${file}.f" 2>/dev/null || :
  mv -f "${file}.f" "$file"
}

# fsc_warn_errlog "<prefix>": het poll-loop-idioom — scrub, en als er dan nog iets overblijft,
# print een WARN met de laatste regel en truncate. Retourneert altijd 0 (mag een poll-lus onder
# `set -e` nooit laten stoppen).
fsc_warn_errlog() {
  local prefix="$1"
  fsc_scrub_errlog
  if [ -s "$ERRLOG" ]; then
    echo "  WARN: ${prefix}: $(tail -n1 "$ERRLOG")" >&2
    : > "$ERRLOG"
  fi
  return 0
}

# fsc_last_error [n]: scrub + print de laatste n regels (default 1) van $ERRLOG. Voor gebruik
# in FAIL-strings (command substitution) of op de FAIL-paden. Print niets als leeg; faalt nooit.
fsc_last_error() {
  local n="${1:-1}"
  fsc_scrub_errlog
  tail -n "$n" "$ERRLOG" 2>/dev/null
  return 0
}

# --- curl-in-toolbox -------------------------------------------------------------------------

# fsc_tb <curl-args...>: curl binnen de toolbox-container, met de internal-client-cert van de
# caller. Leest $COMPOSE/$CERT/$KEY/$CA uit de caller-scope (peer-specifiek, hier niet gezet).
fsc_tb() {
  "${COMPOSE[@]}" exec -T toolbox curl -sS --fail-with-body \
    --cert "$CERT" --key "$KEY" --cacert "$CA" "$@" 2>"$ERRLOG"
}

# --- contract-bootstrap-helpers (consume-service.sh) ------------------------------------------

# fsc_new_iv: UUID v4. /proc is Linux-only; op macOS valt terug op uuidgen (lowercase).
fsc_new_iv() {
  cat /proc/sys/kernel/random/uuid 2>/dev/null || uuidgen | tr '[:upper:]' '[:lower:]'
}

# fsc_validity: zet de globale $NBF/$NAF (created_at resp. not_after, +10 jaar geldig) met een
# skew-backdate — Docker Desktop (macOS) draait in een VM waarvan de klok op de host kan
# achterlopen; de manager weigert dan created_at "in the future" (HTTP 500). Op Linux is de
# skew ~0, dus onschadelijk.
fsc_validity() {
  NBF=$(( $(date -u +%s) - 60 ))
  NAF=$((NBF + 315360000))
}

# fsc_have_jq: zet de globale $HAVE_JQ (1/0).
fsc_have_jq() {
  HAVE_JQ=0
  command -v jq >/dev/null 2>&1 && HAVE_JQ=1
  return 0
}

# fsc_accept_state <json> <content_hash> <oin>: "yes"/"no"/"unknown" — draagt het contract de
# accept-handtekening van $oin?
fsc_accept_state() {
  [ "$HAVE_JQ" -eq 1 ] || { echo unknown; return; }
  printf '%s' "$1" | jq -r --arg h "$2" --arg oin "$3" '
    [.. | objects | select((.hash? // .content_hash? // .content?.content_hash?) == $h)] as $c
    | if ($c | length) == 0 then "unknown"
      elif ([ $c[] | .signatures?.accept? | objects ] | length) == 0 then "unknown"
      elif ($c | any((.signatures?.accept? // {}) | has($oin))) then "yes"
      else "no" end' 2>/dev/null || echo unknown
}

# fsc_contract_state <json> <content_hash>: manager-state (bv. "valid"), "unknown" zonder jq/match.
fsc_contract_state() {
  [ "$HAVE_JQ" -eq 1 ] || { echo unknown; return; }
  printf '%s' "$1" | jq -r --arg h "$2" '
    [.. | objects | select((.hash? // .content_hash? // .content?.content_hash?) == $h) | .state?]
    | map(select(. != null)) | (first // "unknown") | ascii_downcase' 2>/dev/null || echo unknown
}

# fsc_grant_hash <json> <content_hash> <service_name> <outway_thumbprint>: het GRANT-hash uit
# content.grants[] (niet het contract-hash zelf) — matcht op service.name + outway-thumbprint
# zodat dit ook klopt zodra een contract ooit meer dan één grant draagt.
fsc_grant_hash() {
  [ "$HAVE_JQ" -eq 1 ] || { echo unknown; return; }
  printf '%s' "$1" | jq -r --arg h "$2" --arg svc "$3" --arg thumb "$4" '
    [.. | objects | select((.hash? // .content_hash? // .content?.content_hash?) == $h)] as $c
    | [$c[] | (.content?.grants? // [])[]
         | select(.service?.name == $svc and .outway?.identification?.public_key_thumbprint == $thumb)
         | .hash?] as $g
    | ($g[0] // "unknown")' 2>/dev/null || echo unknown
}
```

- [ ] **Stap 2: Uitvoerbaar maken en syntax-checken**

Run: `chmod +x demo/environment/lib/fsc-harness.sh && bash -n demo/environment/lib/fsc-harness.sh`
Expected: geen output (geldige syntax). Let op: dit bestand wordt **gesourced**, niet direct uitgevoerd door `run-smokes.sh` — de uitvoerbaar-bit is voor consistentie met de overige scripts, niet functioneel vereist.

---

### Taak 2: `demo/environment/lib/test-fsc-harness.sh` — fixture-test voor `fsc_scrub_errlog`

**Files:**
- Create: `demo/environment/lib/test-fsc-harness.sh`

**Interfaces:**
- Consumes: `demo/environment/lib/fsc-harness.sh` (source).
- Produces: exit 0 + `ALLE ASSERTS GROEN` bij succes (stijl matcht `pki/verify.sh` in beide peers), exit 1 met duidelijke FAIL-regel bij falen.

- [ ] **Stap 1: Testscript schrijven**

```bash
#!/usr/bin/env bash
# Fixture-test voor fsc_scrub_errlog: toetst tegen de exacte ANSI-bytes uit de PR-166-bug-report
# (ericwout-overheid, 2026-08-12) — reproductie van de podman-external-compose-provider-banner.
set -euo pipefail

HERE="$(cd "$(dirname "$0")" && pwd)"
# shellcheck source=fsc-harness.sh
source "$HERE/fsc-harness.sh"

fails=0

assert_empty_after_scrub() {
  local desc="$1" content="$2"
  ERRLOG=$(mktemp)
  printf '%s' "$content" > "$ERRLOG"
  fsc_scrub_errlog
  if [ -s "$ERRLOG" ]; then
    echo "FAIL: $desc — verwacht leeg na scrub, kreeg: $(cat "$ERRLOG")" >&2
    fails=$((fails + 1))
  else
    echo "OK: $desc"
  fi
  rm -f "$ERRLOG"
}

assert_survives_scrub() {
  local desc="$1" content="$2" expect_substr="$3"
  ERRLOG=$(mktemp)
  printf '%s' "$content" > "$ERRLOG"
  fsc_scrub_errlog
  if grep -qF "$expect_substr" "$ERRLOG"; then
    echo "OK: $desc"
  else
    echo "FAIL: $desc — verwachtte '$expect_substr' te overleven, kreeg: $(cat "$ERRLOG")" >&2
    fails=$((fails + 1))
  fi
  rm -f "$ERRLOG"
}

# Exacte bytes uit de bug-report: ESC[4m vóór de banner, lege regel, losse ESC[0m.
BANNER=$'\033[4m>>>> Executing external compose provider "/home/claude/.local/bin/docker-compose". Please note this can fail with unexpected errors.\n\n\033[0m\n'

assert_empty_after_scrub "banner-only wordt leeg (geen vals alarm)" "$BANNER"

REAL_ERROR="${BANNER}curl: (7) Failed to connect to manager.logius.fsc-test.local port 9443: Connection refused
"
assert_survives_scrub "echte curl-fout blijft zichtbaar na scrub" "$REAL_ERROR" "Connection refused"

if [ "$fails" -eq 0 ]; then
  echo "ALLE ASSERTS GROEN"
  exit 0
else
  echo "FAIL: $fails assert(s) gefaald" >&2
  exit 1
fi
```

- [ ] **Stap 2: Uitvoerbaar maken en draaien**

Run: `chmod +x demo/environment/lib/test-fsc-harness.sh && demo/environment/lib/test-fsc-harness.sh`
Expected:
```
OK: banner-only wordt leeg (geen vals alarm)
OK: echte curl-fout blijft zichtbaar na scrub
ALLE ASSERTS GROEN
```

---

### Taak 3: `demo/environment/logius/deploy/local/smoke-announce.sh` ombouwen

**Files:**
- Modify: `demo/environment/logius/deploy/local/smoke-announce.sh`

- [ ] **Stap 1: HERE + lib-source toevoegen, ERRLOG-init en strip_wrapper_noise vervangen**

```bash
old:
COMPOSE=(docker compose -f "$(dirname "$0")/docker-compose.yaml")
CONSUMER_OIN="00000000000000001000"
DIR_OIN="00000000000000000010"
TIMEOUT=120
INTERVAL=5

# Vang psql-stderr op i.p.v. weg te gooien: een persistente DB-fout (auth, ontbrekende
# kolom/tabel, dode container) mag niet als "nog niet aangemeld" maskeren — surface 'm
# op de FAIL-paden. Loop-stderr zelf blijft stil (transiënte boot-ruis).
ERRLOG=$(mktemp)
trap 'rm -f "$ERRLOG"' EXIT

# Onder podman schrijft de external-compose-provider-wrapper zelf een bannerregel naar stderr
# bij ELKE aanroep; dat is geen psql-fout. Filteren voorkomt dat de FAIL-diagnostiek die
# providermelding als "laatste psql-fout" presenteert.
strip_wrapper_noise() {
  # De banner draagt SGR-ANSI-codes (bv. ESC[4m vóór de tekst), dus een anker op regelbegin mist
  # 'm; ANSI eerst strippen (portable-vorm i.p.v. \x1b, een GNU-sed-extensie die BSD-sed/macOS
  # niet kent), dan zonder anker filteren, en de lege regel weggooien die overblijft na het
  # strippen van de losse ESC[0m-regel.
  LC_ALL=C sed -e $'s/\033\\[[0-9;]*m//g' "$ERRLOG" \
    | grep -v 'Executing external compose provider' \
    | grep -v '^[[:space:]]*$' > "${ERRLOG}.f" 2>/dev/null || :
  mv -f "${ERRLOG}.f" "$ERRLOG"
}

echo "smoke: wachten tot logius ($CONSUMER_OIN) announce't bij de directory (op :443)..."

new:
HERE="$(cd "$(dirname "$0")" && pwd)"
# shellcheck source=../../../lib/fsc-harness.sh
source "$HERE/../../../lib/fsc-harness.sh"

COMPOSE=(docker compose -f "$HERE/docker-compose.yaml")
CONSUMER_OIN="00000000000000001000"
DIR_OIN="00000000000000000010"
TIMEOUT=120
INTERVAL=5

# Vang psql-stderr op i.p.v. weg te gooien: een persistente DB-fout (auth, ontbrekende
# kolom/tabel, dode container) mag niet als "nog niet aangemeld" maskeren — surface 'm
# op de FAIL-paden. Loop-stderr zelf blijft stil (transiënte boot-ruis).
fsc_errlog_init

echo "smoke: wachten tot logius ($CONSUMER_OIN) announce't bij de directory (op :443)..."
```

- [ ] **Stap 2: FAIL-blok ombouwen**

```bash
old:
# Surface de laatste psql-stderr (leeg = schoon, dus echt geen announce).
strip_wrapper_noise
if [ -s "$ERRLOG" ]; then
  echo "  -> laatste psql-fout:" >&2
  tail -n 3 "$ERRLOG" >&2
fi

new:
# Surface de laatste psql-stderr (leeg = schoon, dus echt geen announce).
LAST=$(fsc_last_error 3)
if [ -n "$LAST" ]; then
  echo "  -> laatste psql-fout:" >&2
  printf '%s\n' "$LAST" >&2
fi
```

- [ ] **Stap 3: Syntax-check**

Run: `bash -n demo/environment/logius/deploy/local/smoke-announce.sh`
Expected: geen output.

---

### Taak 4: `demo/environment/logius/deploy/local/smoke-discover.sh` ombouwen

**Files:**
- Modify: `demo/environment/logius/deploy/local/smoke-discover.sh`

- [ ] **Stap 1: HERE + lib-source, ERRLOG-init, strip_wrapper_noise vervangen**

```bash
old:
HERE="$(dirname "$0")"
COMPOSE=(docker compose -f "$HERE/docker-compose.yaml")
SERVICE_NAME="profiel-service"
PROVIDER_OIN="00000000000000001000"
DIR_OIN="00000000000000000010"
# directory-propagatie na auto-sign is vrijwel direct; 10s volstaat na de inway-poll in publish-service.sh.
TIMEOUT=10
INTERVAL=2

# De provider bevraagt de directory via zijn EIGEN manager (internal-cert) naar de eigen
# gepubliceerde diensten. Robuuster dan de directory-DB pollen (geen tabelnaam-koppeling).
CERT=/pki/internal/logius/manager/cert.pem
KEY=/pki/internal/logius/manager/key.pem
CA=/pki/internal/logius/ca/root.pem
MANAGER=https://manager.logius.fsc-test.local:9443

# Vang toolbox-/curl-stderr op zodat een mTLS-/dode-container-fout niet als "nog niet vindbaar"
# maskeert (spiegelt smoke-announce.sh).
ERRLOG=$(mktemp)
trap 'rm -f "$ERRLOG"' EXIT

# Onder podman schrijft de external-compose-provider-wrapper zelf een bannerregel naar stderr
# bij ELKE aanroep (">>>> Executing external compose provider ... <<<<"), niet alleen bij een
# echte curl-fout. Zonder filter leest [ -s "$ERRLOG" ] die banner als "poll-fout" op elke poll.
strip_wrapper_noise() {
  # De banner draagt SGR-ANSI-codes (bv. ESC[4m vóór de tekst), dus een anker op regelbegin mist
  # 'm; ANSI eerst strippen (portable-vorm i.p.v. \x1b, een GNU-sed-extensie die BSD-sed/macOS
  # niet kent), dan zonder anker filteren, en de lege regel weggooien die overblijft na het
  # strippen van de losse ESC[0m-regel.
  LC_ALL=C sed -e $'s/\033\\[[0-9;]*m//g' "$ERRLOG" \
    | grep -v 'Executing external compose provider' \
    | grep -v '^[[:space:]]*$' > "${ERRLOG}.f" 2>/dev/null || :
  mv -f "${ERRLOG}.f" "$ERRLOG"
}

echo "smoke-discover: pollen tot ${SERVICE_NAME} vindbaar is bij de directory (mesh-API)..."
elapsed=0
while [ "$elapsed" -lt "$TIMEOUT" ]; do
  out=$("${COMPOSE[@]}" exec -T toolbox curl -sS \
          --cert "$CERT" --key "$KEY" --cacert "$CA" \
          "$MANAGER/v1/peers/$DIR_OIN/services?peer_id=$PROVIDER_OIN" 2>"$ERRLOG" || true)
  strip_wrapper_noise
  [ -s "$ERRLOG" ] && { echo "  WARN: poll-fout: $(tail -n1 "$ERRLOG")" >&2; : >"$ERRLOG"; }

new:
HERE="$(cd "$(dirname "$0")" && pwd)"
# shellcheck source=../../../lib/fsc-harness.sh
source "$HERE/../../../lib/fsc-harness.sh"

COMPOSE=(docker compose -f "$HERE/docker-compose.yaml")
SERVICE_NAME="profiel-service"
PROVIDER_OIN="00000000000000001000"
DIR_OIN="00000000000000000010"
# directory-propagatie na auto-sign is vrijwel direct; 10s volstaat na de inway-poll in publish-service.sh.
TIMEOUT=10
INTERVAL=2

# De provider bevraagt de directory via zijn EIGEN manager (internal-cert) naar de eigen
# gepubliceerde diensten. Robuuster dan de directory-DB pollen (geen tabelnaam-koppeling).
CERT=/pki/internal/logius/manager/cert.pem
KEY=/pki/internal/logius/manager/key.pem
CA=/pki/internal/logius/ca/root.pem
MANAGER=https://manager.logius.fsc-test.local:9443

# Vang toolbox-/curl-stderr op zodat een mTLS-/dode-container-fout niet als "nog niet vindbaar"
# maskeert (spiegelt smoke-announce.sh).
fsc_errlog_init

echo "smoke-discover: pollen tot ${SERVICE_NAME} vindbaar is bij de directory (mesh-API)..."
elapsed=0
while [ "$elapsed" -lt "$TIMEOUT" ]; do
  out=$("${COMPOSE[@]}" exec -T toolbox curl -sS \
          --cert "$CERT" --key "$KEY" --cacert "$CA" \
          "$MANAGER/v1/peers/$DIR_OIN/services?peer_id=$PROVIDER_OIN" 2>"$ERRLOG" || true)
  fsc_warn_errlog "poll-fout"
```

- [ ] **Stap 2: FAIL-pad ombouwen**

```bash
old:
"${COMPOSE[@]}" exec -T toolbox curl -sS --cert "$CERT" --key "$KEY" --cacert "$CA" \
   "$MANAGER/v1/services/publications" >&2 || true
strip_wrapper_noise
[ -s "$ERRLOG" ] && { echo "  -> laatste poll-fout:" >&2; tail -n 3 "$ERRLOG" >&2; }

new:
"${COMPOSE[@]}" exec -T toolbox curl -sS --cert "$CERT" --key "$KEY" --cacert "$CA" \
   "$MANAGER/v1/services/publications" >&2 || true
LAST=$(fsc_last_error 3)
[ -n "$LAST" ] && { echo "  -> laatste poll-fout:" >&2; printf '%s\n' "$LAST" >&2; }
```

- [ ] **Stap 3: Syntax-check**

Run: `bash -n demo/environment/logius/deploy/local/smoke-discover.sh`
Expected: geen output.

---

### Taak 5: `demo/environment/logius/deploy/local/publish-service.sh` ombouwen

**Files:**
- Modify: `demo/environment/logius/deploy/local/publish-service.sh`

- [ ] **Stap 1: HERE + lib-source, ERRLOG-init, tb()/strip_wrapper_noise vervangen, aanroepen naar fsc_tb**

```bash
old:
COMPOSE=(docker compose -f "$(dirname "$0")/docker-compose.yaml")
SERVICE_NAME="profiel-service"
PROVIDER_OIN="00000000000000001000"
DIR_OIN="00000000000000000010"
GROUP_ID="moza-fbs-test"
STUB_URL="http://stub-upstream:8080"

CERT=/pki/internal/logius/manager/cert.pem
KEY=/pki/internal/logius/manager/key.pem
CA=/pki/internal/logius/ca/root.pem
CONTROLLER=https://controller.logius.fsc-test.local:9444
MANAGER=https://manager.logius.fsc-test.local:9443

# Vang curl-/toolbox-stderr op i.p.v. weg te gooien: een mTLS-/netwerk-/dode-container-fout
# mag niet als "nog niet klaar" maskeren (spiegelt smoke-announce.sh). Surface 'm in de loop.
ERRLOG=$(mktemp)
trap 'rm -f "$ERRLOG"' EXIT

# curl binnen de toolbox, met de internal client-cert. Stderr -> ERRLOG.
tb() { "${COMPOSE[@]}" exec -T toolbox curl -sS --fail-with-body \
         --cert "$CERT" --key "$KEY" --cacert "$CA" "$@" 2>"$ERRLOG"; }

# Onder podman schrijft de external-compose-provider-wrapper zelf een bannerregel naar stderr
# bij ELKE aanroep; dat is geen curl-fout. Filteren voorkomt vals-alarm-WARN's en een
# misleidende "laatste fout" op de FAIL-paden hieronder.
strip_wrapper_noise() {
  # De banner draagt SGR-ANSI-codes (bv. ESC[4m vóór de tekst), dus een anker op regelbegin mist
  # 'm; ANSI eerst strippen (portable-vorm i.p.v. \x1b, een GNU-sed-extensie die BSD-sed/macOS
  # niet kent), dan zonder anker filteren, en de lege regel weggooien die overblijft na het
  # strippen van de losse ESC[0m-regel.
  LC_ALL=C sed -e $'s/\033\\[[0-9;]*m//g' "$ERRLOG" \
    | grep -v 'Executing external compose provider' \
    | grep -v '^[[:space:]]*$' > "${ERRLOG}.f" 2>/dev/null || :
  mv -f "${ERRLOG}.f" "$ERRLOG"
}

echo "publish: wachten op inway-registratie bij de controller..."
# inway->controller-registratie is asynchroon na boot; poll (spiegelt smoke-announce.sh)
# i.p.v. één harde fetch, anders racet een koude start de eerste publish-run.
INWAY_ADDR=""
elapsed=0
while [ "$elapsed" -lt 60 ]; do
  # CreateService verwacht het inway-ADRES (https://...:443, = SELF_ADDRESS), niet de naam.
  INWAY_ADDR=$(tb "$CONTROLLER/v1/inways" | grep -o 'https://inway\.logius\.fsc-test\.local:443' | head -1 || true)
  strip_wrapper_noise
  [ -n "$INWAY_ADDR" ] && break
  # Persistente fout (verkeerd cert-pad, dode toolbox, DNS) mag niet als "traag boot" maskeren.
  [ -s "$ERRLOG" ] && { echo "  WARN: controller-fout: $(tail -n1 "$ERRLOG")" >&2; : >"$ERRLOG"; }
  sleep 5; elapsed=$((elapsed + 5))
  echo "  ...inway nog niet geregistreerd (${elapsed}s)"
done
[ -n "$INWAY_ADDR" ] || { echo "FAIL: geen geregistreerde inway op de controller binnen 60s." >&2; exit 1; }
echo "  inway_address=$INWAY_ADDR"

echo "publish: $SERVICE_NAME aanmaken (idempotent)..."
if tb "$CONTROLLER/v1/services" | grep -q "\"$SERVICE_NAME\""; then
  echo "  bestaat al, skip create."
else
  tb -X POST "$CONTROLLER/v1/services" -H 'Content-Type: application/json' \
     -d "{\"name\":\"$SERVICE_NAME\",\"endpoint_url\":\"$STUB_URL\",\"inway_address\":\"$INWAY_ADDR\"}"
  echo "  aangemaakt."
fi

echo "publish: servicePublication-contract indienen (idempotent)..."
if tb "$MANAGER/v1/services/publications" | grep -q "\"$SERVICE_NAME\""; then
  echo "  al gepubliceerd, skip contract."
else

new:
HERE="$(cd "$(dirname "$0")" && pwd)"
# shellcheck source=../../../lib/fsc-harness.sh
source "$HERE/../../../lib/fsc-harness.sh"

COMPOSE=(docker compose -f "$HERE/docker-compose.yaml")
SERVICE_NAME="profiel-service"
PROVIDER_OIN="00000000000000001000"
DIR_OIN="00000000000000000010"
GROUP_ID="moza-fbs-test"
STUB_URL="http://stub-upstream:8080"

CERT=/pki/internal/logius/manager/cert.pem
KEY=/pki/internal/logius/manager/key.pem
CA=/pki/internal/logius/ca/root.pem
CONTROLLER=https://controller.logius.fsc-test.local:9444
MANAGER=https://manager.logius.fsc-test.local:9443

# Vang curl-/toolbox-stderr op i.p.v. weg te gooien: een mTLS-/netwerk-/dode-container-fout
# mag niet als "nog niet klaar" maskeren (spiegelt smoke-announce.sh). Surface 'm in de loop.
fsc_errlog_init

echo "publish: wachten op inway-registratie bij de controller..."
# inway->controller-registratie is asynchroon na boot; poll (spiegelt smoke-announce.sh)
# i.p.v. één harde fetch, anders racet een koude start de eerste publish-run.
INWAY_ADDR=""
elapsed=0
while [ "$elapsed" -lt 60 ]; do
  # CreateService verwacht het inway-ADRES (https://...:443, = SELF_ADDRESS), niet de naam.
  INWAY_ADDR=$(fsc_tb "$CONTROLLER/v1/inways" | grep -o 'https://inway\.logius\.fsc-test\.local:443' | head -1 || true)
  [ -n "$INWAY_ADDR" ] && break
  # Persistente fout (verkeerd cert-pad, dode toolbox, DNS) mag niet als "traag boot" maskeren.
  fsc_warn_errlog "controller-fout"
  sleep 5; elapsed=$((elapsed + 5))
  echo "  ...inway nog niet geregistreerd (${elapsed}s)"
done
[ -n "$INWAY_ADDR" ] || { echo "FAIL: geen geregistreerde inway op de controller binnen 60s." >&2; exit 1; }
echo "  inway_address=$INWAY_ADDR"

echo "publish: $SERVICE_NAME aanmaken (idempotent)..."
if fsc_tb "$CONTROLLER/v1/services" | grep -q "\"$SERVICE_NAME\""; then
  echo "  bestaat al, skip create."
else
  fsc_tb -X POST "$CONTROLLER/v1/services" -H 'Content-Type: application/json' \
     -d "{\"name\":\"$SERVICE_NAME\",\"endpoint_url\":\"$STUB_URL\",\"inway_address\":\"$INWAY_ADDR\"}"
  echo "  aangemaakt."
fi

echo "publish: servicePublication-contract indienen (idempotent)..."
if fsc_tb "$MANAGER/v1/services/publications" | grep -q "\"$SERVICE_NAME\""; then
  echo "  al gepubliceerd, skip contract."
else
```

- [ ] **Stap 2: POST /v1/contracts-blok ombouwen (`tb` → `fsc_tb`, FAIL-string)**

```bash
old:
  RESP=$(tb -X POST "$MANAGER/v1/contracts" -H 'Content-Type: application/json' -d "{

new:
  RESP=$(fsc_tb -X POST "$MANAGER/v1/contracts" -H 'Content-Type: application/json' -d "{
```

```bash
old:
  }") || { echo "FAIL: POST /v1/contracts geweigerd (iv=$IV): ${RESP:-<leeg>} $(strip_wrapper_noise; tail -n1 "$ERRLOG" 2>/dev/null)" >&2; exit 1; }

new:
  }") || { echo "FAIL: POST /v1/contracts geweigerd (iv=$IV): ${RESP:-<leeg>} $(fsc_last_error)" >&2; exit 1; }
```

- [ ] **Stap 3: Syntax-check**

Run: `bash -n demo/environment/logius/deploy/local/publish-service.sh`
Expected: geen output.

---

### Taak 6: `demo/environment/logius/deploy/local/consume-service.sh` ombouwen

**Files:**
- Modify: `demo/environment/logius/deploy/local/consume-service.sh`

- [ ] **Stap 1: HERE-regel + lib-source toevoegen (HERE bestond al in de robuuste vorm)**

```bash
old:
HERE="$(cd "$(dirname "$0")" && pwd)"
COMPOSE=(docker compose -f "${HERE}/docker-compose.yaml")

new:
HERE="$(cd "$(dirname "$0")" && pwd)"
# shellcheck source=../../../lib/fsc-harness.sh
source "$HERE/../../../lib/fsc-harness.sh"

COMPOSE=(docker compose -f "${HERE}/docker-compose.yaml")
```

- [ ] **Stap 2: ERRLOG-init, `tb()`, `strip_wrapper_noise()`, `manager_contracts()` ombouwen**

```bash
old:
ERRLOG=$(mktemp)
trap 'rm -f "$ERRLOG"' EXIT

tb() { "${COMPOSE[@]}" exec -T toolbox curl -sS --fail-with-body \
         --cert "$CERT" --key "$KEY" --cacert "$CA" "$@" 2>"$ERRLOG"; }

# Onder podman schrijft de external-compose-provider-wrapper zelf een bannerregel naar stderr
# bij ELKE aanroep; dat is geen curl-fout. Filteren voorkomt vals-alarm-WARN's en een
# misleidende "laatste fout" op de FAIL-paden hieronder.
strip_wrapper_noise() {
  # De banner draagt SGR-ANSI-codes (bv. ESC[4m vóór de tekst), dus een anker op regelbegin mist
  # 'm; ANSI eerst strippen (portable-vorm i.p.v. \x1b, een GNU-sed-extensie die BSD-sed/macOS
  # niet kent), dan zonder anker filteren, en de lege regel weggooien die overblijft na het
  # strippen van de losse ESC[0m-regel.
  LC_ALL=C sed -e $'s/\033\\[[0-9;]*m//g' "$ERRLOG" \
    | grep -v 'Executing external compose provider' \
    | grep -v '^[[:space:]]*$' > "${ERRLOG}.f" 2>/dev/null || :
  mv -f "${ERRLOG}.f" "$ERRLOG"
}

manager_contracts() {
  local out; out=$(tb "$MANAGER/v1/contracts") || {
    echo "  WARN: GET /v1/contracts faalde: $(strip_wrapper_noise; tail -n1 "$ERRLOG" 2>/dev/null)" >&2; : >"$ERRLOG"; }
  printf '%s' "$out"
}

HAVE_JQ=0; command -v jq >/dev/null 2>&1 && HAVE_JQ=1

accept_state() {  # $1=json $2=content_hash $3=oin
  [ "$HAVE_JQ" -eq 1 ] || { echo unknown; return; }
  printf '%s' "$1" | jq -r --arg h "$2" --arg oin "$3" '
    [.. | objects | select((.hash? // .content_hash? // .content?.content_hash?) == $h)] as $c
    | if ($c | length) == 0 then "unknown"
      elif ([ $c[] | .signatures?.accept? | objects ] | length) == 0 then "unknown"
      elif ($c | any((.signatures?.accept? // {}) | has($oin))) then "yes"
      else "no" end' 2>/dev/null || echo unknown
}

# Aanvullend op accept_state(): de daadwerkelijke bruikbaarheid van de grant hangt af van de
# manager-state (CONTRACT_STATE_VALID), niet alleen van accept-signature-aanwezigheid — die twee
# vielen tot dusver steeds samen, maar zijn niet gegarandeerd identiek. Zelfde jq-aanpak als
# contract_state() in moza-fsc-testnet/contracts/bootstrap.sh.
contract_state() {  # $1=json $2=content_hash
  [ "$HAVE_JQ" -eq 1 ] || { echo unknown; return; }
  printf '%s' "$1" | jq -r --arg h "$2" '
    [.. | objects | select((.hash? // .content_hash? // .content?.content_hash?) == $h) | .state?]
    | map(select(. != null)) | (first // "unknown") | ascii_downcase' 2>/dev/null || echo unknown
}

# Het top-level `hash`/`content_hash`-veld op een contract-entry is het CONTRACT-hash, niet het
# grant-hash waarop de outway routeert (Fsc-Grant-Hash) — dat zit als eigen `hash`-veld op de
# individuele grant in content.grants[]. Matcht op service.name + outway-thumbprint zodat dit
# ook klopt zodra een contract ooit meer dan één grant draagt.
grant_hash() {  # $1=json $2=content_hash $3=service_name $4=outway_thumbprint
  [ "$HAVE_JQ" -eq 1 ] || { echo unknown; return; }
  printf '%s' "$1" | jq -r --arg h "$2" --arg svc "$3" --arg thumb "$4" '
    [.. | objects | select((.hash? // .content_hash? // .content?.content_hash?) == $h)] as $c
    | [$c[] | (.content?.grants? // [])[]
         | select(.service?.name == $svc and .outway?.identification?.public_key_thumbprint == $thumb)
         | .hash?] as $g
    | ($g[0] // "unknown")' 2>/dev/null || echo unknown
}

new:
fsc_errlog_init
fsc_have_jq

manager_contracts() {
  local out; out=$(fsc_tb "$MANAGER/v1/contracts") || {
    echo "  WARN: GET /v1/contracts faalde: $(fsc_last_error)" >&2; : >"$ERRLOG"; }
  printf '%s' "$out"
}
```

- [ ] **Stap 3: Aanroepen van de verwijderde lokale functies hernoemen naar hun `fsc_*`-tegenhanger**

`accept_state` → `fsc_accept_state`, `contract_state` → `fsc_contract_state`, `grant_hash` → `fsc_grant_hash`, `tb ` → `fsc_tb ` (met een spatie erna, om `tb` in `manager_contracts`/comments niet per ongeluk te raken — die is al herschreven in Stap 2). Concreet, per regel (regelnummers vóór deze taak):

```bash
old (regel 126): case "$(accept_state "$LIST" "$SAVED" "$PROVIDER_OIN")" in
new:              case "$(fsc_accept_state "$LIST" "$SAVED" "$PROVIDER_OIN")" in

old (regel 128): CSTATE=$(contract_state "$LIST" "$SAVED")
new:              CSTATE=$(fsc_contract_state "$LIST" "$SAVED")

old (regel 131): GRANT=$(grant_hash "$LIST" "$SAVED" "$SERVICE_NAME" "$THUMB")
new:              GRANT=$(fsc_grant_hash "$LIST" "$SAVED" "$SERVICE_NAME" "$THUMB")

old (regel 145): GRANT=$(grant_hash "$LIST" "$SAVED" "$SERVICE_NAME" "$THUMB")
new:              GRANT=$(fsc_grant_hash "$LIST" "$SAVED" "$SERVICE_NAME" "$THUMB")

old (regel 166): RESP=$(tb -X POST "$MANAGER/v1/contracts" -H 'Content-Type: application/json' -d "{
new:              RESP=$(fsc_tb -X POST "$MANAGER/v1/contracts" -H 'Content-Type: application/json' -d "{

old (regel 185): }") || { echo "FAIL: POST /v1/contracts geweigerd: ${RESP:-<leeg>} $(strip_wrapper_noise; tail -n1 "$ERRLOG" 2>/dev/null)" >&2; exit 1; }
new:              }") || { echo "FAIL: POST /v1/contracts geweigerd: ${RESP:-<leeg>} $(fsc_last_error)" >&2; exit 1; }

old (regel 203): tb -X PUT "$MANAGER/v1/contracts/$HASH/accept" -H 'Content-Type: application/json' \
new:              fsc_tb -X PUT "$MANAGER/v1/contracts/$HASH/accept" -H 'Content-Type: application/json' \

old (regel 204):   || { echo "FAIL: PUT accept ($HASH) geweigerd: $(strip_wrapper_noise; tail -n1 "$ERRLOG" 2>/dev/null)" >&2; exit 1; }
new:                || { echo "FAIL: PUT accept ($HASH) geweigerd: $(fsc_last_error)" >&2; exit 1; }

old (regel 209): case "$(accept_state "$FINAL" "$HASH" "$PROVIDER_OIN")" in
new:              case "$(fsc_accept_state "$FINAL" "$HASH" "$PROVIDER_OIN")" in

old (regel 226): CONTRACT_STATE=$(contract_state "$FINAL" "$HASH")
new:              CONTRACT_STATE=$(fsc_contract_state "$FINAL" "$HASH")

old (regel 233): GRANT=$(grant_hash "$FINAL" "$HASH" "$SERVICE_NAME" "$THUMB")
new:              GRANT=$(fsc_grant_hash "$FINAL" "$HASH" "$SERVICE_NAME" "$THUMB")
```

- [ ] **Stap 4: UUID/validity naar de lib-helpers**

```bash
old:
IV=$(cat /proc/sys/kernel/random/uuid 2>/dev/null || uuidgen | tr '[:upper:]' '[:lower:]')
NBF=$(( $(date -u +%s) - 60 ))
NAF=$((NBF + 315360000))                 # +10 jaar

new:
IV=$(fsc_new_iv)
fsc_validity
```

- [ ] **Stap 5: Syntax-check**

Run: `bash -n demo/environment/logius/deploy/local/consume-service.sh`
Expected: geen output.

---

### Taak 7: `demo/environment/magazijn-a/deploy/local/smoke-announce.sh` ombouwen (+ parity-fix)

**Files:**
- Modify: `demo/environment/magazijn-a/deploy/local/smoke-announce.sh`

Dit script had nog géén `strip_wrapper_noise` — door de lib te sourcen krijgt het die nu wél (parity-fix, zie Architecture-sectie).

- [ ] **Stap 1: HERE + lib-source, ERRLOG-init**

```bash
old:
COMPOSE=(docker compose -f "$(dirname "$0")/docker-compose.yaml")
PROVIDER_OIN="00000000000000100000"
DIR_OIN="00000000000000000010"
TIMEOUT=120
INTERVAL=5

# Vang psql-stderr op i.p.v. weg te gooien: een persistente DB-fout (auth, ontbrekende
# kolom/tabel, dode container) mag niet als "nog niet aangemeld" maskeren — surface 'm
# op de FAIL-paden. Loop-stderr zelf blijft stil (transiënte boot-ruis).
ERRLOG=$(mktemp)
trap 'rm -f "$ERRLOG"' EXIT

echo "smoke: wachten tot magazijn-a ($PROVIDER_OIN) announce't bij de directory (op :443)..."

new:
HERE="$(cd "$(dirname "$0")" && pwd)"
# shellcheck source=../../../lib/fsc-harness.sh
source "$HERE/../../../lib/fsc-harness.sh"

COMPOSE=(docker compose -f "$HERE/docker-compose.yaml")
PROVIDER_OIN="00000000000000100000"
DIR_OIN="00000000000000000010"
TIMEOUT=120
INTERVAL=5

# Vang psql-stderr op i.p.v. weg te gooien: een persistente DB-fout (auth, ontbrekende
# kolom/tabel, dode container) mag niet als "nog niet aangemeld" maskeren — surface 'm
# op de FAIL-paden. Loop-stderr zelf blijft stil (transiënte boot-ruis).
fsc_errlog_init

echo "smoke: wachten tot magazijn-a ($PROVIDER_OIN) announce't bij de directory (op :443)..."
```

- [ ] **Stap 2: FAIL-blok ombouwen**

```bash
old:
# Surface de laatste psql-stderr (leeg = schoon, dus echt geen announce).
if [ -s "$ERRLOG" ]; then
  echo "  -> laatste psql-fout:" >&2
  tail -n 3 "$ERRLOG" >&2
fi

new:
# Surface de laatste psql-stderr (leeg = schoon, dus echt geen announce).
LAST=$(fsc_last_error 3)
if [ -n "$LAST" ]; then
  echo "  -> laatste psql-fout:" >&2
  printf '%s\n' "$LAST" >&2
fi
```

- [ ] **Stap 3: Syntax-check**

Run: `bash -n demo/environment/magazijn-a/deploy/local/smoke-announce.sh`
Expected: geen output.

---

### Taak 8: `demo/environment/magazijn-a/deploy/local/smoke-discover.sh` ombouwen (+ parity-fix + curl -sS)

**Files:**
- Modify: `demo/environment/magazijn-a/deploy/local/smoke-discover.sh`

- [ ] **Stap 1: HERE + lib-source, ERRLOG-init, poll-loop naar `fsc_warn_errlog` + `curl -sS`**

```bash
old:
HERE="$(dirname "$0")"
COMPOSE=(docker compose -f "$HERE/docker-compose.yaml")
SERVICE_NAME="berichtenmagazijn"
PROVIDER_OIN="00000000000000100000"
DIR_OIN="00000000000000000010"
# directory-propagatie na auto-sign is vrijwel direct; 10s volstaat na de inway-poll in publish-service.sh.
TIMEOUT=10
INTERVAL=2

# De provider bevraagt de directory via zijn EIGEN manager (internal-cert) naar de eigen
# gepubliceerde diensten. Robuuster dan de directory-DB pollen (geen tabelnaam-koppeling).
CERT=/pki/internal/magazijn-a/manager/cert.pem
KEY=/pki/internal/magazijn-a/manager/key.pem
CA=/pki/internal/magazijn-a/ca/root.pem
MANAGER=https://manager.magazijn-a.fsc-test.local:9443

# Vang toolbox-/curl-stderr op zodat een mTLS-/dode-container-fout niet als "nog niet vindbaar"
# maskeert (spiegelt smoke-announce.sh).
ERRLOG=$(mktemp)
trap 'rm -f "$ERRLOG"' EXIT

echo "smoke-discover: pollen tot ${SERVICE_NAME} vindbaar is bij de directory (mesh-API)..."
elapsed=0
while [ "$elapsed" -lt "$TIMEOUT" ]; do
  out=$("${COMPOSE[@]}" exec -T toolbox curl -s \
          --cert "$CERT" --key "$KEY" --cacert "$CA" \
          "$MANAGER/v1/peers/$DIR_OIN/services?peer_id=$PROVIDER_OIN" 2>"$ERRLOG" || true)
  [ -s "$ERRLOG" ] && { echo "  WARN: poll-fout: $(tail -n1 "$ERRLOG")" >&2; : >"$ERRLOG"; }

new:
HERE="$(cd "$(dirname "$0")" && pwd)"
# shellcheck source=../../../lib/fsc-harness.sh
source "$HERE/../../../lib/fsc-harness.sh"

COMPOSE=(docker compose -f "$HERE/docker-compose.yaml")
SERVICE_NAME="berichtenmagazijn"
PROVIDER_OIN="00000000000000100000"
DIR_OIN="00000000000000000010"
# directory-propagatie na auto-sign is vrijwel direct; 10s volstaat na de inway-poll in publish-service.sh.
TIMEOUT=10
INTERVAL=2

# De provider bevraagt de directory via zijn EIGEN manager (internal-cert) naar de eigen
# gepubliceerde diensten. Robuuster dan de directory-DB pollen (geen tabelnaam-koppeling).
CERT=/pki/internal/magazijn-a/manager/cert.pem
KEY=/pki/internal/magazijn-a/manager/key.pem
CA=/pki/internal/magazijn-a/ca/root.pem
MANAGER=https://manager.magazijn-a.fsc-test.local:9443

# Vang toolbox-/curl-stderr op zodat een mTLS-/dode-container-fout niet als "nog niet vindbaar"
# maskeert (spiegelt smoke-announce.sh).
fsc_errlog_init

echo "smoke-discover: pollen tot ${SERVICE_NAME} vindbaar is bij de directory (mesh-API)..."
elapsed=0
while [ "$elapsed" -lt "$TIMEOUT" ]; do
  out=$("${COMPOSE[@]}" exec -T toolbox curl -sS \
          --cert "$CERT" --key "$KEY" --cacert "$CA" \
          "$MANAGER/v1/peers/$DIR_OIN/services?peer_id=$PROVIDER_OIN" 2>"$ERRLOG" || true)
  fsc_warn_errlog "poll-fout"
```

- [ ] **Stap 2: FAIL-pad ombouwen (`curl -s` → `-sS`, filter toevoegen)**

```bash
old:
"${COMPOSE[@]}" exec -T toolbox curl -s --cert "$CERT" --key "$KEY" --cacert "$CA" \
   "$MANAGER/v1/services/publications" >&2 || true
[ -s "$ERRLOG" ] && { echo "  -> laatste poll-fout:" >&2; tail -n 3 "$ERRLOG" >&2; }

new:
"${COMPOSE[@]}" exec -T toolbox curl -sS --cert "$CERT" --key "$KEY" --cacert "$CA" \
   "$MANAGER/v1/services/publications" >&2 || true
LAST=$(fsc_last_error 3)
[ -n "$LAST" ] && { echo "  -> laatste poll-fout:" >&2; printf '%s\n' "$LAST" >&2; }
```

- [ ] **Stap 3: Syntax-check**

Run: `bash -n demo/environment/magazijn-a/deploy/local/smoke-discover.sh`
Expected: geen output.

---

### Taak 9: `demo/environment/magazijn-a/deploy/local/publish-service.sh` ombouwen (+ parity-fix + curl -sS)

**Files:**
- Modify: `demo/environment/magazijn-a/deploy/local/publish-service.sh`

- [ ] **Stap 1: HERE + lib-source, ERRLOG-init, `tb()` verwijderen, aanroepen naar `fsc_tb`, `curl -s` → `-sS`**

```bash
old:
COMPOSE=(docker compose -f "$(dirname "$0")/docker-compose.yaml")
SERVICE_NAME="berichtenmagazijn"
PROVIDER_OIN="00000000000000100000"
DIR_OIN="00000000000000000010"
GROUP_ID="moza-fbs-test"                 # = GROUP_ID env-var op de manager; als de manager een directory-adres verwacht, gebruik DIRECTORY_MANAGER_ADDRESS
STUB_URL="http://stub-upstream:8080"

CERT=/pki/internal/magazijn-a/manager/cert.pem
KEY=/pki/internal/magazijn-a/manager/key.pem
CA=/pki/internal/magazijn-a/ca/root.pem
CONTROLLER=https://controller.magazijn-a.fsc-test.local:9444
MANAGER=https://manager.magazijn-a.fsc-test.local:9443

# Vang curl-/toolbox-stderr op i.p.v. weg te gooien: een mTLS-/netwerk-/dode-container-fout
# mag niet als "nog niet klaar" maskeren (spiegelt smoke-announce.sh). Surface 'm in de loop.
ERRLOG=$(mktemp)
trap 'rm -f "$ERRLOG"' EXIT

# curl binnen de toolbox, met de internal client-cert. Stderr -> ERRLOG.
tb() { "${COMPOSE[@]}" exec -T toolbox curl -s --fail-with-body \
         --cert "$CERT" --key "$KEY" --cacert "$CA" "$@" 2>"$ERRLOG"; }

echo "publish: wachten op inway-registratie bij de controller..."
# inway->controller-registratie is asynchroon na boot; poll (spiegelt smoke-announce.sh)
# i.p.v. één harde fetch, anders racet een koude start de eerste publish-run.
INWAY_ADDR=""
elapsed=0
while [ "$elapsed" -lt 60 ]; do
  # CreateService verwacht het inway-ADRES (https://...:443, = SELF_ADDRESS), niet de naam.
  INWAY_ADDR=$(tb "$CONTROLLER/v1/inways" | grep -o 'https://inway\.magazijn-a\.fsc-test\.local:443' | head -1 || true)
  [ -n "$INWAY_ADDR" ] && break
  # Persistente fout (verkeerd cert-pad, dode toolbox, DNS) mag niet als "traag boot" maskeren.
  [ -s "$ERRLOG" ] && { echo "  WARN: controller-fout: $(tail -n1 "$ERRLOG")" >&2; : >"$ERRLOG"; }
  sleep 5; elapsed=$((elapsed + 5))
  echo "  ...inway nog niet geregistreerd (${elapsed}s)"
done
[ -n "$INWAY_ADDR" ] || { echo "FAIL: geen geregistreerde inway op de controller binnen 60s." >&2; exit 1; }
echo "  inway_address=$INWAY_ADDR"

echo "publish: $SERVICE_NAME aanmaken (idempotent)..."
if tb "$CONTROLLER/v1/services" | grep -q "\"$SERVICE_NAME\""; then
  echo "  bestaat al, skip create."
else
  tb -X POST "$CONTROLLER/v1/services" -H 'Content-Type: application/json' \
     -d "{\"name\":\"$SERVICE_NAME\",\"endpoint_url\":\"$STUB_URL\",\"inway_address\":\"$INWAY_ADDR\"}"
  echo "  aangemaakt."
fi

echo "publish: servicePublication-contract indienen (idempotent)..."
if tb "$MANAGER/v1/services/publications" | grep -q "\"$SERVICE_NAME\""; then
  echo "  al gepubliceerd, skip contract."
else

new:
HERE="$(cd "$(dirname "$0")" && pwd)"
# shellcheck source=../../../lib/fsc-harness.sh
source "$HERE/../../../lib/fsc-harness.sh"

COMPOSE=(docker compose -f "$HERE/docker-compose.yaml")
SERVICE_NAME="berichtenmagazijn"
PROVIDER_OIN="00000000000000100000"
DIR_OIN="00000000000000000010"
GROUP_ID="moza-fbs-test"                 # = GROUP_ID env-var op de manager; als de manager een directory-adres verwacht, gebruik DIRECTORY_MANAGER_ADDRESS
STUB_URL="http://stub-upstream:8080"

CERT=/pki/internal/magazijn-a/manager/cert.pem
KEY=/pki/internal/magazijn-a/manager/key.pem
CA=/pki/internal/magazijn-a/ca/root.pem
CONTROLLER=https://controller.magazijn-a.fsc-test.local:9444
MANAGER=https://manager.magazijn-a.fsc-test.local:9443

# Vang curl-/toolbox-stderr op i.p.v. weg te gooien: een mTLS-/netwerk-/dode-container-fout
# mag niet als "nog niet klaar" maskeren (spiegelt smoke-announce.sh). Surface 'm in de loop.
fsc_errlog_init

echo "publish: wachten op inway-registratie bij de controller..."
# inway->controller-registratie is asynchroon na boot; poll (spiegelt smoke-announce.sh)
# i.p.v. één harde fetch, anders racet een koude start de eerste publish-run.
INWAY_ADDR=""
elapsed=0
while [ "$elapsed" -lt 60 ]; do
  # CreateService verwacht het inway-ADRES (https://...:443, = SELF_ADDRESS), niet de naam.
  INWAY_ADDR=$(fsc_tb "$CONTROLLER/v1/inways" | grep -o 'https://inway\.magazijn-a\.fsc-test\.local:443' | head -1 || true)
  [ -n "$INWAY_ADDR" ] && break
  # Persistente fout (verkeerd cert-pad, dode toolbox, DNS) mag niet als "traag boot" maskeren.
  fsc_warn_errlog "controller-fout"
  sleep 5; elapsed=$((elapsed + 5))
  echo "  ...inway nog niet geregistreerd (${elapsed}s)"
done
[ -n "$INWAY_ADDR" ] || { echo "FAIL: geen geregistreerde inway op de controller binnen 60s." >&2; exit 1; }
echo "  inway_address=$INWAY_ADDR"

echo "publish: $SERVICE_NAME aanmaken (idempotent)..."
if fsc_tb "$CONTROLLER/v1/services" | grep -q "\"$SERVICE_NAME\""; then
  echo "  bestaat al, skip create."
else
  fsc_tb -X POST "$CONTROLLER/v1/services" -H 'Content-Type: application/json' \
     -d "{\"name\":\"$SERVICE_NAME\",\"endpoint_url\":\"$STUB_URL\",\"inway_address\":\"$INWAY_ADDR\"}"
  echo "  aangemaakt."
fi

echo "publish: servicePublication-contract indienen (idempotent)..."
if fsc_tb "$MANAGER/v1/services/publications" | grep -q "\"$SERVICE_NAME\""; then
  echo "  al gepubliceerd, skip contract."
else
```

- [ ] **Stap 2: POST /v1/contracts-blok ombouwen (`tb` → `fsc_tb`, UUID/validity, FAIL-string)**

```bash
old:
  IV=$(cat /proc/sys/kernel/random/uuid 2>/dev/null || uuidgen | tr '[:upper:]' '[:lower:]')
  # Docker Desktop (macOS) draait in een VM waarvan de klok op de host kan achterlopen; de manager
  # weigert dan created_at "in the future" (HTTP 500). Backdate met een skew-marge — op Linux is de
  # skew ~0, dus onschadelijk. Blijft persistent falen? Herstart de Docker-VM (klok resynct).
  NBF=$(( $(date -u +%s) - 60 ))
  NAF=$((NBF + 315360000))                 # +10 jaar
  # --fail-with-body laat curl bij 4xx/5xx non-zero exiten MAAR print de body; vang beide zodat
  # `set -e` ons niet vóór de diagnostiek killt en de manager-respons zichtbaar is.
  RESP=$(tb -X POST "$MANAGER/v1/contracts" -H 'Content-Type: application/json' -d "{

new:
  IV=$(fsc_new_iv)
  # Docker Desktop (macOS) draait in een VM waarvan de klok op de host kan achterlopen; de manager
  # weigert dan created_at "in the future" (HTTP 500). Backdate met een skew-marge — op Linux is de
  # skew ~0, dus onschadelijk. Blijft persistent falen? Herstart de Docker-VM (klok resynct).
  fsc_validity
  # --fail-with-body laat curl bij 4xx/5xx non-zero exiten MAAR print de body; vang beide zodat
  # `set -e` ons niet vóór de diagnostiek killt en de manager-respons zichtbaar is.
  RESP=$(fsc_tb -X POST "$MANAGER/v1/contracts" -H 'Content-Type: application/json' -d "{
```

```bash
old:
  }") || { echo "FAIL: POST /v1/contracts geweigerd (iv=$IV): ${RESP:-<leeg>} $(tail -n1 "$ERRLOG" 2>/dev/null)" >&2; exit 1; }

new:
  }") || { echo "FAIL: POST /v1/contracts geweigerd (iv=$IV): ${RESP:-<leeg>} $(fsc_last_error)" >&2; exit 1; }
```

- [ ] **Stap 3: Syntax-check**

Run: `bash -n demo/environment/magazijn-a/deploy/local/publish-service.sh`
Expected: geen output.

---

### Taak 10: Documentatie bijwerken

**Files:**
- Modify: `docs/plans/2026-07-30-demo-environment-fsc-peers-design.md`
- Modify: `docs/plans/2026-08-12-logius-harness-podman-smoke-diagnostiek-en-grant-hash.md`

- [ ] **Stap 1: Aanvulling op het "rule of three"-ontwerpdocument**

```markdown
old (regel 111-117, ongewijzigd laten staan, alleen een regel ná dit blok toevoegen):
De bijna-identieke compose-/PKI-scripts tussen `magazijn-a` en `logius` worden bewust
**niet** gededupliceerd naar een gedeelde `_lib/`-laag: dat is ook nu al zo tussen de
twee losse repo's, en met precies twee peers is een gedeelde abstractie prematuur
(rule of three). Wel deelt elke peer zijn group-CA-materiaal (root/intermediate) met
de externe `moza-fsc-testnet`-directory — dat blijft zo, alleen de her-issue-stap
(`pki/issue.sh`) verhuist mee.

new:
De bijna-identieke compose-/PKI-scripts tussen `magazijn-a` en `logius` worden bewust
**niet** gededupliceerd naar een gedeelde `_lib/`-laag: dat is ook nu al zo tussen de
twee losse repo's, en met precies twee peers is een gedeelde abstractie prematuur
(rule of three). Wel deelt elke peer zijn group-CA-materiaal (root/intermediate) met
de externe `moza-fsc-testnet`-directory — dat blijft zo, alleen de her-issue-stap
(`pki/issue.sh`) verhuist mee.

**Aanvulling (2026-08-13):** met een derde peer op komst is de rule-of-three-drempel
bereikt — `docs/plans/2026-08-13-demo-environment-gedeelde-fsc-harness-lib.md` voert
alsnog een gedeelde `demo/environment/lib/fsc-harness.sh` in voor de `deploy/local/`-
scripts (niet de `pki/`-scripts, die blijven vooralsnog gedupliceerd).
```

- [ ] **Stap 2: Aanvulling op het smoke-diagnostiek-plan**

```markdown
old (regel 1):
**Status:** Uitgevoerd (Taak 1-3, inclusief aanvulling hieronder; Taak 4 — PR-reactie — nog niet geplaatst)

new:
**Status:** Uitgevoerd (Taak 1-3, inclusief aanvulling hieronder; Taak 4 — PR-reactie — nog niet geplaatst)

**Aanvulling (2026-08-13):** de "geen nieuwe gedeelde bash-library"-regel in de Global
Constraints hieronder is met een derde peer op komst achterhaald —
`docs/plans/2026-08-13-demo-environment-gedeelde-fsc-harness-lib.md` introduceert alsnog
`demo/environment/lib/fsc-harness.sh` en verhuist `strip_wrapper_noise`/`grant_hash`/etc.
daarheen.
```

- [ ] **Stap 3: Commit**

```bash
git add docs/plans/2026-07-30-demo-environment-fsc-peers-design.md \
        docs/plans/2026-08-12-logius-harness-podman-smoke-diagnostiek-en-grant-hash.md
```

(Wordt in Taak 11 samen met de rest gecommit.)

---

### Taak 11: Verifiëren en committen

**Files:** geen nieuwe.

- [ ] **Stap 1: Syntax-check alle gewijzigde/nieuwe bestanden in één keer**

Run:
```bash
for f in demo/environment/lib/fsc-harness.sh \
         demo/environment/lib/test-fsc-harness.sh \
         demo/environment/logius/deploy/local/{smoke-announce,smoke-discover,publish-service,consume-service}.sh \
         demo/environment/magazijn-a/deploy/local/{smoke-announce,smoke-discover,publish-service}.sh; do
  bash -n "$f" && echo "OK: $f" || echo "SYNTAX-FOUT: $f"
done
```
Expected: 9× `OK:`, geen `SYNTAX-FOUT`.

- [ ] **Stap 2: Fixture-test draaien**

Run: `demo/environment/lib/test-fsc-harness.sh`
Expected: `ALLE ASSERTS GROEN`.

- [ ] **Stap 3: Geen restanten van de oude, lokale functienamen**

Run:
```bash
grep -rn "strip_wrapper_noise\|^tb()\|^accept_state()\|^contract_state()\|^grant_hash()\|^HAVE_JQ=0" \
  demo/environment/logius/deploy/local/*.sh demo/environment/magazijn-a/deploy/local/*.sh
```
Expected: geen output (alle lokale definities zijn verwijderd; alleen `fsc_*`-aanroepen resteren).

- [ ] **Stap 4: Commit**

```bash
git add demo/environment/lib/ \
        demo/environment/logius/deploy/local/ \
        demo/environment/magazijn-a/deploy/local/ \
        docs/plans/2026-07-30-demo-environment-fsc-peers-design.md \
        docs/plans/2026-08-12-logius-harness-podman-smoke-diagnostiek-en-grant-hash.md \
        docs/plans/2026-08-13-demo-environment-gedeelde-fsc-harness-lib.md
git commit -m "$(cat <<'EOF'
refactor(demo): gedeelde demo/environment/lib/fsc-harness.sh voor logius + magazijn-a

Optie D uit de PR-166-reviewdiscussie over strip_wrapper_noise()-duplicatie
(comment #issuecomment-5277020399), gekozen boven optie C (lib.sh per peer)
omdat er binnenkort een derde peer bijkomt — de rule-of-three-drempel uit
docs/plans/2026-07-30-demo-environment-fsc-peers-design.md is daarmee bereikt.

Verhuist strip_wrapper_noise/tb/accept_state/contract_state/grant_hash/UUID-
en-validity-generatie naar fsc_*-functies in de nieuwe lib. Peer-identiteit
(OIN's, servicenamen, cert-paden, MANAGER/CONTROLLER-URL's) blijft in elk
script staan.

Magazijn-a's drie scripts kregen daarbij de ANSI-/banner-filter en de
curl -sS-vlag die logius al had (commits b2de38dd/64d4ffb8) — een bewuste
parity-fix, niet toevallig: zonder die fix zou magazijn-a onder podman
dezelfde vals-alarm-WARN's krijgen die logius had.

Co-Authored-By: Claude Sonnet 5 <noreply@anthropic.com>
EOF
)"
```
