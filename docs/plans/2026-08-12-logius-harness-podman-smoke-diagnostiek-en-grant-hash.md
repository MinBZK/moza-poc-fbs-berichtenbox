**Status:** Uitgevoerd (Taak 1-3; Taak 4 — PR-reactie — nog niet geplaatst)

# Logius-harness-podman-smoke-diagnostiek-en-grant-hash Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Verhelp twee bevindingen van ericwout-overheid op PR #166 (review d.d. 2026-08-12, CHANGES_REQUESTED), gevonden tijdens een volledige lokale run van de `demo/environment/logius/deploy/local/`-harness onder rootless podman: (1) de `ERRLOG`-foutdetectie in de onboarding-/smoke-scripts leest de stderr-banner van de podman-compose-wrapper aan voor een curl-/psql-fout, en (2) `consume-service.sh` geeft het **contract**-hash af als `GRANT-HASH`, terwijl de outway op het **grant**-hash uit `content.grants[]` routeert — wie de scriptoutput letterlijk op `Fsc-Grant-Hash` zet (zoals de README suggereert) krijgt structureel `400 UNKNOWN_GRANT_HASH_IN_HEADER`.

**Architecture:** Beide bevindingen zitten in bestaande, imperatieve bash-scripts (geen gedeelde lib — elk script is zelfstandig, conventie in deze harness). De fix volgt dezelfde stijl: klein, lokaal, geen nieuwe abstractie-laag. Bevinding 1 wordt per script opgelost met een kleine `strip_wrapper_noise()`-functie (dezelfde functie-tekst in de vier betrokken scripts, consistent met hoe deze scripts nu al onderling dupliceren i.p.v. delen — zie hun eigen "spiegelt X.sh"-commentaren). Bevinding 2 wordt opgelost met een nieuwe `grant_hash()`-jq-functie in `consume-service.sh`, naar het patroon van de bestaande `accept_state()`/`contract_state()`-functies in datzelfde bestand.

**Tech Stack:** Bash (`set -euo pipefail`), curl (mTLS via de `toolbox`-container), jq, Docker Compose / podman-compose-wrapper.

## Global Constraints

- Scope is uitsluitend `demo/environment/logius/deploy/local/` + de bijbehorende `README.md` — **niet** `demo/environment/magazijn-a/` (zelfde scripts bestaan daar, maar vallen buiten deze diff; dat is al zo vastgesteld in de vorige reviewronde op deze PR).
- Geen nieuwe gedeelde bash-library. De bestaande scripts zijn bewust zelfstandig; volg die conventie.
- `GRANT-HASH:`/`CONSUME OK.` wordt nergens machinaal geparsed in deze repo (`run-smokes.sh` roept de scripts alleen sequentieel aan, geen output-scraping) — het uitbreiden van de output met een extra `CONTRACT-HASH:`-regel is dus veilig.
- `jq` wordt met deze fix een harde vereiste voor een geslaagde `consume-service.sh`-run (zie Taak 2 voor de motivatie: een tekstuele fallback zonder jq zou het exact-zelfde soort bug kunnen herintroduceren — het contract-hash en het grant-hash delen hetzelfde JSON-veldnaam `hash` op verschillende nestingniveaus, dus een `grep`-gebaseerde fallback kan niet betrouwbaar onderscheiden welke waarde bij welk niveau hoort). Dit wijkt af van de letterlijke reviewvraag ("de fallback heeft dezelfde behandeling nodig") — gemotiveerde afwijking, geen stilzwijgende afwijking; wordt ook zo teruggekoppeld op de PR.
- Geen `git push` in dit plan — alleen lokale commits per taak. Pushen/PR-comment-reactie is een aparte, expliciete stap na afronding.

---

### Taak 1: Compose-wrapper-banner niet meer als curl-/psql-fout lezen

**Files:**
- Modify: `demo/environment/logius/deploy/local/smoke-discover.sh`
- Modify: `demo/environment/logius/deploy/local/publish-service.sh`
- Modify: `demo/environment/logius/deploy/local/consume-service.sh`
- Modify: `demo/environment/logius/deploy/local/smoke-announce.sh`

**Interfaces:**
- Produces: elk script krijgt een lokale `strip_wrapper_noise()`-functie die `$ERRLOG` in-place filtert op de regel `>>>> Executing external compose provider ... <<<<` (podman's eigen banner op stderr bij élke `docker compose`/`podman compose`-aanroep, niet alleen bij fouten). Alle bestaande `[ -s "$ERRLOG" ]`-checks en `tail -n1/-n3 "$ERRLOG"`-diagnostiek in deze vier bestanden roepen 'm eerst aan.

- [ ] **Stap 1: `smoke-discover.sh` — filter toevoegen en overal toepassen**

Voeg de functie toe direct na de bestaande `trap`-regel:

```bash
old:
ERRLOG=$(mktemp)
trap 'rm -f "$ERRLOG"' EXIT

echo "smoke-discover: pollen tot ${SERVICE_NAME} vindbaar is bij de directory (mesh-API)..."

new:
ERRLOG=$(mktemp)
trap 'rm -f "$ERRLOG"' EXIT

# Onder podman schrijft de external-compose-provider-wrapper zelf een bannerregel naar stderr
# bij ELKE aanroep (">>>> Executing external compose provider ... <<<<"), niet alleen bij een
# echte curl-fout. Zonder filter leest [ -s "$ERRLOG" ] die banner als "poll-fout" op elke poll.
strip_wrapper_noise() {
  grep -v '^>>>> Executing external compose provider' "$ERRLOG" > "${ERRLOG}.f" 2>/dev/null || :
  mv -f "${ERRLOG}.f" "$ERRLOG"
}

echo "smoke-discover: pollen tot ${SERVICE_NAME} vindbaar is bij de directory (mesh-API)..."
```

Pas de poll-lus aan (curl `-s` → `-sS` zodat een échte curl-fout wél op stderr verschijnt — met kale `-s` onderdrukt curl zijn eigen foutmeldingen ook, waardoor `$ERRLOG` tot dusver sowieso nooit een curl-fout kón bevatten):

```bash
old:
  out=$("${COMPOSE[@]}" exec -T toolbox curl -s \
          --cert "$CERT" --key "$KEY" --cacert "$CA" \
          "$MANAGER/v1/peers/$DIR_OIN/services?peer_id=$PROVIDER_OIN" 2>"$ERRLOG" || true)
  [ -s "$ERRLOG" ] && { echo "  WARN: poll-fout: $(tail -n1 "$ERRLOG")" >&2; : >"$ERRLOG"; }

new:
  out=$("${COMPOSE[@]}" exec -T toolbox curl -sS \
          --cert "$CERT" --key "$KEY" --cacert "$CA" \
          "$MANAGER/v1/peers/$DIR_OIN/services?peer_id=$PROVIDER_OIN" 2>"$ERRLOG" || true)
  strip_wrapper_noise
  [ -s "$ERRLOG" ] && { echo "  WARN: poll-fout: $(tail -n1 "$ERRLOG")" >&2; : >"$ERRLOG"; }
```

En het FAIL-pad onderaan:

```bash
old:
"${COMPOSE[@]}" exec -T toolbox curl -s --cert "$CERT" --key "$KEY" --cacert "$CA" \
   "$MANAGER/v1/services/publications" >&2 || true
[ -s "$ERRLOG" ] && { echo "  -> laatste poll-fout:" >&2; tail -n 3 "$ERRLOG" >&2; }

new:
"${COMPOSE[@]}" exec -T toolbox curl -sS --cert "$CERT" --key "$KEY" --cacert "$CA" \
   "$MANAGER/v1/services/publications" >&2 || true
strip_wrapper_noise
[ -s "$ERRLOG" ] && { echo "  -> laatste poll-fout:" >&2; tail -n 3 "$ERRLOG" >&2; }
```

- [ ] **Stap 2: `publish-service.sh` — filter toevoegen en overal toepassen**

Voeg de functie toe direct na `tb()`, en zet `-s` → `-sS` in `tb()`:

```bash
old:
tb() { "${COMPOSE[@]}" exec -T toolbox curl -s --fail-with-body \
         --cert "$CERT" --key "$KEY" --cacert "$CA" "$@" 2>"$ERRLOG"; }

echo "publish: wachten op inway-registratie bij de controller..."

new:
tb() { "${COMPOSE[@]}" exec -T toolbox curl -sS --fail-with-body \
         --cert "$CERT" --key "$KEY" --cacert "$CA" "$@" 2>"$ERRLOG"; }

# Onder podman schrijft de external-compose-provider-wrapper zelf een bannerregel naar stderr
# bij ELKE aanroep; dat is geen curl-fout. Filteren voorkomt vals-alarm-WARN's en een
# misleidende "laatste fout" op de FAIL-paden hieronder.
strip_wrapper_noise() {
  grep -v '^>>>> Executing external compose provider' "$ERRLOG" > "${ERRLOG}.f" 2>/dev/null || :
  mv -f "${ERRLOG}.f" "$ERRLOG"
}

echo "publish: wachten op inway-registratie bij de controller..."
```

Pas de poll-lus aan:

```bash
old:
  INWAY_ADDR=$(tb "$CONTROLLER/v1/inways" | grep -o 'https://inway\.logius\.fsc-test\.local:443' | head -1 || true)
  [ -n "$INWAY_ADDR" ] && break
  # Persistente fout (verkeerd cert-pad, dode toolbox, DNS) mag niet als "traag boot" maskeren.
  [ -s "$ERRLOG" ] && { echo "  WARN: controller-fout: $(tail -n1 "$ERRLOG")" >&2; : >"$ERRLOG"; }

new:
  INWAY_ADDR=$(tb "$CONTROLLER/v1/inways" | grep -o 'https://inway\.logius\.fsc-test\.local:443' | head -1 || true)
  strip_wrapper_noise
  [ -n "$INWAY_ADDR" ] && break
  # Persistente fout (verkeerd cert-pad, dode toolbox, DNS) mag niet als "traag boot" maskeren.
  [ -s "$ERRLOG" ] && { echo "  WARN: controller-fout: $(tail -n1 "$ERRLOG")" >&2; : >"$ERRLOG"; }
```

En de FAIL-diagnostiek bij het contract-POST:

```bash
old:
  }") || { echo "FAIL: POST /v1/contracts geweigerd (iv=$IV): ${RESP:-<leeg>} $(tail -n1 "$ERRLOG" 2>/dev/null)" >&2; exit 1; }

new:
  }") || { echo "FAIL: POST /v1/contracts geweigerd (iv=$IV): ${RESP:-<leeg>} $(strip_wrapper_noise; tail -n1 "$ERRLOG" 2>/dev/null)" >&2; exit 1; }
```

- [ ] **Stap 3: `consume-service.sh` — filter toevoegen en overal toepassen**

```bash
old:
tb() { "${COMPOSE[@]}" exec -T toolbox curl -s --fail-with-body \
         --cert "$CERT" --key "$KEY" --cacert "$CA" "$@" 2>"$ERRLOG"; }

manager_contracts() {
  local out; out=$(tb "$MANAGER/v1/contracts") || {
    echo "  WARN: GET /v1/contracts faalde: $(tail -n1 "$ERRLOG" 2>/dev/null)" >&2; : >"$ERRLOG"; }
  printf '%s' "$out"
}

new:
tb() { "${COMPOSE[@]}" exec -T toolbox curl -sS --fail-with-body \
         --cert "$CERT" --key "$KEY" --cacert "$CA" "$@" 2>"$ERRLOG"; }

# Onder podman schrijft de external-compose-provider-wrapper zelf een bannerregel naar stderr
# bij ELKE aanroep; dat is geen curl-fout. Filteren voorkomt vals-alarm-WARN's en een
# misleidende "laatste fout" op de FAIL-paden hieronder.
strip_wrapper_noise() {
  grep -v '^>>>> Executing external compose provider' "$ERRLOG" > "${ERRLOG}.f" 2>/dev/null || :
  mv -f "${ERRLOG}.f" "$ERRLOG"
}

manager_contracts() {
  local out; out=$(tb "$MANAGER/v1/contracts") || {
    echo "  WARN: GET /v1/contracts faalde: $(strip_wrapper_noise; tail -n1 "$ERRLOG" 2>/dev/null)" >&2; : >"$ERRLOG"; }
  printf '%s' "$out"
}
```

En de twee resterende diagnostische `tail`-plekken:

```bash
old:
}") || { echo "FAIL: POST /v1/contracts geweigerd: ${RESP:-<leeg>} $(tail -n1 "$ERRLOG" 2>/dev/null)" >&2; exit 1; }

new:
}") || { echo "FAIL: POST /v1/contracts geweigerd: ${RESP:-<leeg>} $(strip_wrapper_noise; tail -n1 "$ERRLOG" 2>/dev/null)" >&2; exit 1; }
```

```bash
old:
tb -X PUT "$MANAGER/v1/contracts/$HASH/accept" -H 'Content-Type: application/json' \
  || { echo "FAIL: PUT accept ($HASH) geweigerd: $(tail -n1 "$ERRLOG" 2>/dev/null)" >&2; exit 1; }

new:
tb -X PUT "$MANAGER/v1/contracts/$HASH/accept" -H 'Content-Type: application/json' \
  || { echo "FAIL: PUT accept ($HASH) geweigerd: $(strip_wrapper_noise; tail -n1 "$ERRLOG" 2>/dev/null)" >&2; exit 1; }
```

- [ ] **Stap 4: `smoke-announce.sh` — filter toevoegen (psql, geen `-s`-vlag om aan te passen)**

```bash
old:
ERRLOG=$(mktemp)
trap 'rm -f "$ERRLOG"' EXIT

echo "smoke: wachten tot logius ($CONSUMER_OIN) announce't bij de directory (op :443)..."

new:
ERRLOG=$(mktemp)
trap 'rm -f "$ERRLOG"' EXIT

# Onder podman schrijft de external-compose-provider-wrapper zelf een bannerregel naar stderr
# bij ELKE aanroep; dat is geen psql-fout. Filteren voorkomt dat de FAIL-diagnostiek die
# providermelding als "laatste psql-fout" presenteert.
strip_wrapper_noise() {
  grep -v '^>>>> Executing external compose provider' "$ERRLOG" > "${ERRLOG}.f" 2>/dev/null || :
  mv -f "${ERRLOG}.f" "$ERRLOG"
}

echo "smoke: wachten tot logius ($CONSUMER_OIN) announce't bij de directory (op :443)..."
```

```bash
old:
# Surface de laatste psql-stderr (leeg = schoon, dus echt geen announce).
if [ -s "$ERRLOG" ]; then
  echo "  -> laatste psql-fout:" >&2
  tail -n 3 "$ERRLOG" >&2
fi

new:
# Surface de laatste psql-stderr (leeg = schoon, dus echt geen announce).
strip_wrapper_noise
if [ -s "$ERRLOG" ]; then
  echo "  -> laatste psql-fout:" >&2
  tail -n 3 "$ERRLOG" >&2
fi
```

- [ ] **Stap 5: Syntax-check alle vier bestanden**

Run: `for f in demo/environment/logius/deploy/local/{smoke-discover,publish-service,consume-service,smoke-announce}.sh; do bash -n "$f" || echo "SYNTAX-FOUT: $f"; done`
Expected: geen output (alle vier parsen schoon).

Run (best-effort, alleen als shellcheck lokaal beschikbaar is — was dat niet tijdens het schrijven van dit plan): `command -v shellcheck && shellcheck demo/environment/logius/deploy/local/{smoke-discover,publish-service,consume-service,smoke-announce}.sh || echo "shellcheck niet beschikbaar, overslaan"`
Expected: geen nieuwe findings t.o.v. de bestaande scripts (de SC2015-melding op `consume-service.sh:172` wordt in Taak 2 apart verholpen).

- [ ] **Stap 6: Commit**

```bash
git add demo/environment/logius/deploy/local/smoke-discover.sh \
        demo/environment/logius/deploy/local/publish-service.sh \
        demo/environment/logius/deploy/local/consume-service.sh \
        demo/environment/logius/deploy/local/smoke-announce.sh
git commit -m "fix(demo): filter podman-compose-wrapper-banner uit ERRLOG-foutdetectie in logius-smokes"
```

---

### Taak 2: `consume-service.sh` geeft het grant-hash af, niet het contract-hash

**Files:**
- Modify: `demo/environment/logius/deploy/local/consume-service.sh`

**Interfaces:**
- Consumes: `HAVE_JQ`, `manager_contracts()` (bestaand, ongewijzigd).
- Produces: nieuwe functie `grant_hash(json, content_hash, service_name, outway_thumbprint) -> grant_hash | "unknown"`. Scriptoutput krijgt een extra regel `CONTRACT-HASH: <hash>` vóór `GRANT-HASH: <hash>`; `GRANT-HASH` bevat vanaf nu het `content.grants[].hash`-veld i.p.v. het contract-hash. Bij `HAVE_JQ=0` of geen matchende grant faalt het script expliciet (`exit 1`) i.p.v. de oude, verkeerde waarde af te geven.

- [ ] **Stap 1: Voeg `grant_hash()` toe na `contract_state()`**

```bash
old:
contract_state() {  # $1=json $2=content_hash
  [ "$HAVE_JQ" -eq 1 ] || { echo unknown; return; }
  printf '%s' "$1" | jq -r --arg h "$2" '
    [.. | objects | select((.hash? // .content_hash? // .content?.content_hash?) == $h) | .state?]
    | map(select(. != null)) | (first // "unknown") | ascii_downcase' 2>/dev/null || echo unknown
}

# --- 0. Outway-public-key-thumbprint (host-side openssl) --------------------------------------

new:
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

# --- 0. Outway-public-key-thumbprint (host-side openssl) --------------------------------------
```

- [ ] **Stap 2: Idempotentie — "yes"-tak (manager-state bevestigd) geeft het grant-hash af**

```bash
old:
      yes)
        CSTATE=$(contract_state "$LIST" "$SAVED")
        case "$CSTATE" in
          valid|contract_state_valid|unknown)
            echo "OK: eerder geaccepteerd contract $SAVED draagt nog de provider-accept en heeft manager-state $CSTATE (idempotent, skip)."
            echo "GRANT-HASH: $SAVED"; exit 0 ;;
          *)
            echo "consume: state-file-contract $SAVED draagt de accept-handtekening, maar manager-state is $CSTATE (niet CONTRACT_STATE_VALID) — opnieuw opzetten." ;;
        esac ;;

new:
      yes)
        CSTATE=$(contract_state "$LIST" "$SAVED")
        case "$CSTATE" in
          valid|contract_state_valid|unknown)
            GRANT=$(grant_hash "$LIST" "$SAVED" "$SERVICE_NAME" "$THUMB")
            [ "$GRANT" != "unknown" ] || {
              echo "FAIL: contract $SAVED gevonden, maar geen grant-hash voor service=$SERVICE_NAME," \
                   "outway-thumbprint=$THUMB (jq beschikbaar? content.grants[] aanwezig?)." >&2
              exit 1
            }
            echo "OK: eerder geaccepteerd contract $SAVED draagt nog de provider-accept en heeft manager-state $CSTATE (idempotent, skip)."
            echo "CONTRACT-HASH: $SAVED"
            echo "GRANT-HASH: $GRANT"; exit 0 ;;
          *)
            echo "consume: state-file-contract $SAVED draagt de accept-handtekening, maar manager-state is $CSTATE (niet CONTRACT_STATE_VALID) — opnieuw opzetten." ;;
        esac ;;
```

- [ ] **Stap 3: Idempotentie — "unknown"-tak (geen jq/afwijkende staat-check) faalt expliciet i.p.v. het verkeerde hash af te geven**

```bash
old:
      unknown)
        if printf '%s' "$LIST" | grep -qF "$SAVED"; then
          echo "OK: eerder geaccepteerd contract $SAVED nog aanwezig (idempotent, skip; jq afwezig -> geen staat-check)."
          echo "GRANT-HASH: $SAVED"; exit 0
        fi ;;

new:
      unknown)
        if printf '%s' "$LIST" | grep -qF "$SAVED"; then
          GRANT=$(grant_hash "$LIST" "$SAVED" "$SERVICE_NAME" "$THUMB")
          [ "$GRANT" != "unknown" ] || {
            echo "FAIL: contract $SAVED aanwezig, maar kon geen grant-hash lezen (vereist jq; installeer jq)." >&2
            exit 1
          }
          echo "OK: eerder geaccepteerd contract $SAVED nog aanwezig (idempotent, skip)."
          echo "CONTRACT-HASH: $SAVED"
          echo "GRANT-HASH: $GRANT"; exit 0
        fi ;;
```

- [ ] **Stap 4: SC2015 in de accept-verificatie oplossen (`A && B || C` → `if/then/else`)**

```bash
old:
  unknown)
    printf '%s' "$FINAL" | grep -qF "$HASH" \
      && echo "OK (fallback, geen jq/afwijkende vorm): contract $HASH aanwezig na een 2xx-accept." \
      || { echo "FAIL: contract $HASH niet teruggevonden na accept." >&2; exit 1; } ;;

new:
  unknown)
    if printf '%s' "$FINAL" | grep -qF "$HASH"; then
      echo "OK (fallback, geen jq/afwijkende vorm): contract $HASH aanwezig na een 2xx-accept."
    else
      echo "FAIL: contract $HASH niet teruggevonden na accept." >&2
      exit 1
    fi ;;
```

- [ ] **Stap 5: Eindoutput — het verse contract geeft het grant-hash af, niet het contract-hash**

```bash
old:
mkdir -p "$STATE_DIR" && printf '%s\n' "$HASH" > "$STATE_FILE"
echo "GRANT-HASH: $HASH"
echo "CONSUME OK."

new:
GRANT=$(grant_hash "$FINAL" "$HASH" "$SERVICE_NAME" "$THUMB")
[ "$GRANT" != "unknown" ] || {
  echo "FAIL: kon geen grant-hash vinden voor service=$SERVICE_NAME, outway-thumbprint=$THUMB" \
       "in contract $HASH (vereist jq; installeer jq)." >&2
  exit 1
}

mkdir -p "$STATE_DIR" && printf '%s\n' "$HASH" > "$STATE_FILE"
echo "CONTRACT-HASH: $HASH"
echo "GRANT-HASH: $GRANT"
echo "CONSUME OK."
```

- [ ] **Stap 6: Syntax-check en jq-logica handmatig verifiëren**

Run: `bash -n demo/environment/logius/deploy/local/consume-service.sh`
Expected: geen output.

Run (zelfstandige jq-check tegen de vorm die ericwout in zijn PR-comment citeerde, zonder de harness op te hoeven starten):

```bash
echo '{"contracts":[{"hash":"$1$1$CONTRACTHASH","content":{"grants":[{"hash":"$1$3$GRANTHASH","type":"GRANT_TYPE_SERVICE_CONNECTION","service":{"name":"profiel-service","peer_id":"00000000000000001000"},"outway":{"identification":{"public_key_thumbprint":"d9c5293"}}}]}}]}' \
  | jq -r --arg h '$1$1$CONTRACTHASH' --arg svc "profiel-service" --arg thumb "d9c5293" '
    [.. | objects | select((.hash? // .content_hash? // .content?.content_hash?) == $h)] as $c
    | [$c[] | (.content?.grants? // [])[]
         | select(.service?.name == $svc and .outway?.identification?.public_key_thumbprint == $thumb)
         | .hash?] as $g
    | ($g[0] // "unknown")'
```

Expected: `$1$3$GRANTHASH` (niet `$1$1$CONTRACTHASH`, niet `unknown`).

- [ ] **Stap 7: Commit**

```bash
git add demo/environment/logius/deploy/local/consume-service.sh
git commit -m "fix(demo): consume-service.sh geeft het grant-hash af i.p.v. het contract-hash"
```

---

### Taak 3: README bijwerken op het nieuwe outputformaat en de jq-vereiste

**Files:**
- Modify: `demo/environment/logius/deploy/local/README.md`

**Interfaces:**
- Consumes: geen (documentatie-only).

- [ ] **Stap 1: `jq` toevoegen aan Benodigdheden**

```markdown
old:
## Benodigdheden

- **Docker** + `docker compose` (v2). Onder **rootless podman** stapel je
  `deploy/local/docker-compose.podman.yaml` erop — zie de podman-eisen onder
  [Troubleshooting](#troubleshooting).
- Gegenereerde certs uit `pki/` — draai daar eerst `./init-ca.sh`, `./issue.sh`,
  `./gen-crl.sh` en `./verify.sh` (zie `pki/README.md`, sectie "Uitvoeren"). Zonder certs
  faalt elke container die `/pki` mount bij boot (ontbrekend bestand).

new:
## Benodigdheden

- **Docker** + `docker compose` (v2). Onder **rootless podman** stapel je
  `deploy/local/docker-compose.podman.yaml` erop — zie de podman-eisen onder
  [Troubleshooting](#troubleshooting).
- Gegenereerde certs uit `pki/` — draai daar eerst `./init-ca.sh`, `./issue.sh`,
  `./gen-crl.sh` en `./verify.sh` (zie `pki/README.md`, sectie "Uitvoeren"). Zonder certs
  faalt elke container die `/pki` mount bij boot (ontbrekend bestand).
- **jq** (host-side) — `consume-service.sh` leest het grant-hash uit `content.grants[]`
  daarmee; zonder jq faalt het script expliciet in plaats van het verkeerde (contract-)hash
  af te geven.
```

- [ ] **Stap 2: Tabelregel `consume-service.sh` bijwerken**

```markdown
old:
| `consume-service.sh` | Een zelfreferentieel `serviceConnection`-contract is wederzijds ondertekend; levert de `Fsc-Grant-Hash`-waarde. |

new:
| `consume-service.sh` | Een zelfreferentieel `serviceConnection`-contract is wederzijds ondertekend; levert zowel het contract-hash als het grant-hash waarop de outway routeert (`Fsc-Grant-Hash`). |
```

- [ ] **Stap 3: Commit**

```bash
git add demo/environment/logius/deploy/local/README.md
git commit -m "docs(demo): documenteer jq-vereiste en het contract-/grant-hash-onderscheid in consume-service.sh"
```

---

### Taak 4: Reactie op de PR-review voorbereiden

**Files:** geen (communicatie-taak, geen codewijziging).

- [ ] **Stap 1: Concept-reactie op de twee comments van ericwout-overheid (2026-08-12)**

Volg de bestaande stijl van eerdere reacties van mreuvekamp op deze PR (zie de comment van
2026-08-11, opgebouwd per genummerd punt, technisch en zonder dankbetuigingen). Kernpunten:

1. **ERRLOG/wrapper-ruis:** bevestig de root cause (podman's external-compose-provider-banner
   op stderr bij élke aanroep, gecombineerd met `curl -s` dat curl's éígen foutmeldingen ook
   onderdrukt) en verwijs naar de commit uit Taak 1.
2. **Contract-hash vs. grant-hash:** bevestig de bug, verwijs naar de commit uit Taak 2, en
   noem expliciet de bewuste afwijking op het jq-loze-fallback-verzoek — met de reden
   (dubbelzinnig `hash`-veld op meerdere nestingniveaus maakt een tekstuele fallback
   riskant-fragiel, exact de soort fout die hier al gefixed wordt) zodat ericwout kan
   reageren als hij een andere afweging wil.

- [ ] **Stap 2: Reacties plaatsen (na expliciete goedkeuring van de gebruiker)**

Plaats de reactie als PR-comment (`gh pr comment 166 --body-file <bestand>` of, als het
specifiek op de twee losse review-comments moet, via
`gh api repos/MinBZK/moza-poc-fbs-berichtenbox/pulls/166/comments/{id}/replies` — zie de
GitHub-thread-repliesregel in de receiving-code-review-skill). Niet automatisch uitvoeren
zonder de inhoud eerst te tonen.
