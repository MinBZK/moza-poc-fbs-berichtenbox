#!/usr/bin/env bash
# Contract-bootstrap: zet idempotent een geldig, wederzijds ondertekend
# ServiceConnectionGrant-contract op tussen logius (consumer) en logius (provider) —
# ZELFREFERENTIEEL, omdat berichtenuitvraag's eigen outway de logius-outway IS
# (co-located identiteit, zie docs/design.md). Geadapteerd van het generieke patroon in
# de sibling-repo moza-fsc-testnet/contracts/bootstrap.sh.
#
# Stroom (OpenFSC Manager Internal-API, bewezen patroon uit publish-service.sh):
#   1. bereken de outway-GROUP-public-key-thumbprint (SPKI SHA-256 hex);
#   2. idempotentie: draagt een eerder geaccepteerd contract (state-file-hash) NOG de
#      provider-accept op de provider? -> no-op;
#   3. POST /v1/contracts (contract_content) op de manager -> tekent server-side namens
#      de consumer (2xx + content_hash = consumer-handtekening);
#   4. poll tot het contract (op content_hash) zichtbaar is, dan PUT /v1/contracts/{hash}/accept
#      (2xx = provider-handtekening) — expliciet, want AUTO_SIGN_GRANTS dekt alleen
#      (delegated)servicePublication, niet serviceConnection;
#   5. verifieer onafhankelijk (re-GET) dat het contract de provider-accept draagt, én dat de
#      manager het contract als CONTRACT_STATE_VALID beschouwt (de daadwerkelijke gate voor
#      grant-gebruik door de outway, apart van accept-signature-aanwezigheid).
set -euo pipefail

HERE="$(cd "$(dirname "$0")" && pwd)"
COMPOSE=(docker compose -f "${HERE}/docker-compose.yaml")

CONSUMER_OIN="00000000000000001000"
PROVIDER_OIN="00000000000000001000"
SERVICE_NAME="profiel-service"
GROUP_ID="moza-fbs-test"

# Outway-GROUP-cert (host-pad): hiervan de public-key-thumbprint voor de grant (SPKI-SHA256-hex,
# stabiel bij cert-rotatie). Zie moza-fsc-testnet/contracts/bootstrap.sh voor de OpenFSC-bron-
# verificatie van dit mechanisme.
OUTWAY_CERT_HOST="${HERE}/../../pki/out/logius/outway/cert.pem"

# Consumer- én provider-manager zijn hier DEZELFDE (zelfreferentieel) — internal-certs.
MANAGER="https://manager.logius.fsc-test.local:9443"
CERT=/pki/internal/logius/manager/cert.pem
KEY=/pki/internal/logius/manager/key.pem
CA=/pki/internal/logius/ca/root.pem

SYNC_TIMEOUT=10; SYNC_INTERVAL=2

STATE_DIR="${HERE}/../../contracts/.bootstrap-state"
STATE_FILE="${STATE_DIR}/${CONSUMER_OIN}-${PROVIDER_OIN}-${SERVICE_NAME}.hash"

ERRLOG=$(mktemp)
trap 'rm -f "$ERRLOG"' EXIT

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

# --- 0. Outway-public-key-thumbprint (host-side openssl) --------------------------------------
command -v openssl >/dev/null 2>&1 || { echo "FAIL: openssl niet gevonden op de host." >&2; exit 1; }
[ -r "$OUTWAY_CERT_HOST" ] || { echo "FAIL: outway-cert niet leesbaar: $OUTWAY_CERT_HOST (draai pki/issue.sh?)" >&2; exit 1; }
THUMB=$(openssl x509 -in "$OUTWAY_CERT_HOST" -pubkey -noout \
          | openssl pkey -pubin -outform DER \
          | openssl dgst -sha256 -r | cut -d' ' -f1) || THUMB=""
case "$THUMB" in
  [0-9a-f]*) [ "${#THUMB}" -eq 64 ] || { echo "FAIL: thumbprint geen 64 hex-tekens: '$THUMB'" >&2; exit 1; } ;;
  *) echo "FAIL: kon outway-public-key-thumbprint niet berekenen uit $OUTWAY_CERT_HOST." >&2; exit 1 ;;
esac
echo "consume: outway public-key-thumbprint = $THUMB"

# --- 1. Idempotentie ----------------------------------------------------------------------------
if [ -f "$STATE_FILE" ]; then
  SAVED=$(cat "$STATE_FILE" 2>/dev/null || true)
  if [ -n "$SAVED" ]; then
    LIST=$(manager_contracts)
    case "$(accept_state "$LIST" "$SAVED" "$PROVIDER_OIN")" in
      yes)
        CSTATE=$(contract_state "$LIST" "$SAVED")
        case "$CSTATE" in
          valid|contract_state_valid|unknown)
            echo "OK: eerder geaccepteerd contract $SAVED draagt nog de provider-accept en heeft manager-state $CSTATE (idempotent, skip)."
            echo "GRANT-HASH: $SAVED"; exit 0 ;;
          *)
            echo "consume: state-file-contract $SAVED draagt de accept-handtekening, maar manager-state is $CSTATE (niet CONTRACT_STATE_VALID) — opnieuw opzetten." ;;
        esac ;;
      unknown)
        if printf '%s' "$LIST" | grep -qF "$SAVED"; then
          echo "OK: eerder geaccepteerd contract $SAVED nog aanwezig (idempotent, skip; jq afwezig -> geen staat-check)."
          echo "GRANT-HASH: $SAVED"; exit 0
        fi ;;
      no) echo "consume: state-file-contract $SAVED draagt geen provider-accept meer." ;;
    esac
  fi
  echo "consume: geen bruikbaar bestaand contract — opnieuw opzetten."
fi

# --- 2. Contract opstellen + indienen -----------------------------------------------------------
IV=$(cat /proc/sys/kernel/random/uuid 2>/dev/null || uuidgen | tr '[:upper:]' '[:lower:]')
NBF=$(( $(date -u +%s) - 60 ))
NAF=$((NBF + 315360000))                 # +10 jaar

echo "consume: serviceConnection-contract indienen (zelfreferentieel: consumer=provider=$CONSUMER_OIN)..."
RESP=$(tb -X POST "$MANAGER/v1/contracts" -H 'Content-Type: application/json' -d "{
  \"contract_content\": {
    \"iv\": \"$IV\",
    \"group_id\": \"$GROUP_ID\",
    \"hash_algorithm\": \"HASH_ALGORITHM_SHA3_512\",
    \"created_at\": $NBF,
    \"validity\": { \"not_before\": $((NBF - 60)), \"not_after\": $NAF },
    \"grants\": [ {
      \"type\": \"GRANT_TYPE_SERVICE_CONNECTION\",
      \"service\": { \"type\": \"SERVICE_TYPE_SERVICE\", \"peer_id\": \"$PROVIDER_OIN\", \"name\": \"$SERVICE_NAME\" },
      \"outway\": {
        \"peer_id\": \"$CONSUMER_OIN\",
        \"identification\": {
          \"type\": \"OUTWAY_IDENTIFICATION_TYPE_PUBLIC_KEY_THUMBPRINT\",
          \"public_key_thumbprint\": \"$THUMB\"
        }
      }
    } ]
  }
}") || { echo "FAIL: POST /v1/contracts geweigerd: ${RESP:-<leeg>} $(strip_wrapper_noise; tail -n1 "$ERRLOG" 2>/dev/null)" >&2; exit 1; }

HASH=$(printf '%s' "$RESP" | sed -n 's/.*"content_hash"[[:space:]]*:[[:space:]]*"\([^"]*\)".*/\1/p' | head -n1)
[ -n "$HASH" ] || { echo "FAIL: contract-respons zonder content_hash (formaat geweigerd?): $RESP" >&2; exit 1; }
echo "  consumer-handtekening gezet (2xx); content_hash=$HASH"

# --- 3. Accepteren (zelfde manager, expliciete PUT — AUTO_SIGN_GRANTS dekt dit niet) -----------
echo "consume: wachten tot het contract zichtbaar is..."
elapsed=0; visible=0
while [ "$elapsed" -lt "$SYNC_TIMEOUT" ]; do
  if printf '%s' "$(manager_contracts)" | grep -qF "$HASH"; then visible=1; break; fi
  sleep "$SYNC_INTERVAL"; elapsed=$((elapsed + SYNC_INTERVAL))
  echo "  ...nog niet zichtbaar (${elapsed}s)"
done
[ "$visible" -eq 1 ] || { echo "FAIL: contract $HASH niet zichtbaar binnen ${SYNC_TIMEOUT}s." >&2
  "${COMPOSE[@]}" logs --tail=50 manager-logius >&2 || true; exit 1; }

echo "consume: accepteren (PUT .../accept)..."
tb -X PUT "$MANAGER/v1/contracts/$HASH/accept" -H 'Content-Type: application/json' \
  || { echo "FAIL: PUT accept ($HASH) geweigerd: $(strip_wrapper_noise; tail -n1 "$ERRLOG" 2>/dev/null)" >&2; exit 1; }
echo "  provider-handtekening gezet (2xx)."

# --- 4. Onafhankelijk verifiëren ----------------------------------------------------------------
FINAL=$(manager_contracts)
case "$(accept_state "$FINAL" "$HASH" "$PROVIDER_OIN")" in
  yes) echo "OK: contract $HASH draagt de accept-handtekening (geverifieerd)." ;;
  unknown)
    printf '%s' "$FINAL" | grep -qF "$HASH" \
      && echo "OK (fallback, geen jq/afwijkende vorm): contract $HASH aanwezig na een 2xx-accept." \
      || { echo "FAIL: contract $HASH niet teruggevonden na accept." >&2; exit 1; } ;;
  no) echo "FAIL: contract $HASH draagt geen accept-handtekening (accept-PUT gaf 2xx, staat zegt nee — inspecteer handmatig)." >&2; exit 1 ;;
esac

# Aanvullende gate naast de accept-signature-check hierboven: bevestigt dat de manager het
# contract ook daadwerkelijk als CONTRACT_STATE_VALID beschouwt (de echte poort voor
# grant-gebruik door de outway). Bij "unknown" (geen jq / afwijkende JSON-vorm) valt dit terug
# op de reeds bevestigde accept-aanwezigheid hierboven, zoals de rest van dit script bij
# ontbrekende jq consequent doet.
CONTRACT_STATE=$(contract_state "$FINAL" "$HASH")
case "$CONTRACT_STATE" in
  valid|contract_state_valid) echo "OK: contract $HASH heeft manager-state CONTRACT_STATE_VALID (geverifieerd)." ;;
  unknown) echo "  (state-check niet mogelijk: geen jq of afwijkende JSON-vorm — accept-aanwezigheid hierboven blijft de gate.)" ;;
  *) echo "FAIL: contract $HASH draagt de accept-handtekening maar staat niet op CONTRACT_STATE_VALID (state=$CONTRACT_STATE)." >&2; exit 1 ;;
esac

mkdir -p "$STATE_DIR" && printf '%s\n' "$HASH" > "$STATE_FILE"
echo "GRANT-HASH: $HASH"
echo "CONSUME OK."
