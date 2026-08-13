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
# shellcheck source=../../../lib/fsc-harness.sh
source "$HERE/../../../lib/fsc-harness.sh"

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

fsc_errlog_init
fsc_have_jq

manager_contracts() {
  local out; out=$(fsc_tb "$MANAGER/v1/contracts") || {
    echo "  WARN: GET /v1/contracts faalde: $(fsc_last_error)" >&2; : >"$ERRLOG"; }
  printf '%s' "$out"
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
    case "$(fsc_accept_state "$LIST" "$SAVED" "$PROVIDER_OIN")" in
      yes)
        CSTATE=$(fsc_contract_state "$LIST" "$SAVED")
        case "$CSTATE" in
          valid|contract_state_valid|unknown)
            GRANT=$(fsc_grant_hash "$LIST" "$SAVED" "$SERVICE_NAME" "$THUMB")
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
      unknown)
        if printf '%s' "$LIST" | grep -qF "$SAVED"; then
          GRANT=$(fsc_grant_hash "$LIST" "$SAVED" "$SERVICE_NAME" "$THUMB")
          [ "$GRANT" != "unknown" ] || {
            echo "FAIL: contract $SAVED aanwezig, maar kon geen grant-hash lezen (vereist jq; installeer jq)." >&2
            exit 1
          }
          echo "OK: eerder geaccepteerd contract $SAVED nog aanwezig (idempotent, skip)."
          echo "CONTRACT-HASH: $SAVED"
          echo "GRANT-HASH: $GRANT"; exit 0
        fi ;;
      no) echo "consume: state-file-contract $SAVED draagt geen provider-accept meer." ;;
    esac
  fi
  echo "consume: geen bruikbaar bestaand contract — opnieuw opzetten."
fi

# --- 2. Contract opstellen + indienen -----------------------------------------------------------
IV=$(fsc_new_iv)
fsc_validity

echo "consume: serviceConnection-contract indienen (zelfreferentieel: consumer=provider=$CONSUMER_OIN)..."
RESP=$(fsc_tb -X POST "$MANAGER/v1/contracts" -H 'Content-Type: application/json' -d "{
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
}") || { echo "FAIL: POST /v1/contracts geweigerd: ${RESP:-<leeg>} $(fsc_last_error)" >&2; exit 1; }

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
fsc_tb -X PUT "$MANAGER/v1/contracts/$HASH/accept" -H 'Content-Type: application/json' \
  || { echo "FAIL: PUT accept ($HASH) geweigerd: $(fsc_last_error)" >&2; exit 1; }
echo "  provider-handtekening gezet (2xx)."

# --- 4. Onafhankelijk verifiëren ----------------------------------------------------------------
FINAL=$(manager_contracts)
case "$(fsc_accept_state "$FINAL" "$HASH" "$PROVIDER_OIN")" in
  yes) echo "OK: contract $HASH draagt de accept-handtekening (geverifieerd)." ;;
  unknown)
    if printf '%s' "$FINAL" | grep -qF "$HASH"; then
      echo "OK (fallback, geen jq/afwijkende vorm): contract $HASH aanwezig na een 2xx-accept."
    else
      echo "FAIL: contract $HASH niet teruggevonden na accept." >&2
      exit 1
    fi ;;
  no) echo "FAIL: contract $HASH draagt geen accept-handtekening (accept-PUT gaf 2xx, staat zegt nee — inspecteer handmatig)." >&2; exit 1 ;;
esac

# Aanvullende gate naast de accept-signature-check hierboven: bevestigt dat de manager het
# contract ook daadwerkelijk als CONTRACT_STATE_VALID beschouwt (de echte poort voor
# grant-gebruik door de outway). Bij "unknown" (geen jq / afwijkende JSON-vorm) valt dit terug
# op de reeds bevestigde accept-aanwezigheid hierboven, zoals de rest van dit script bij
# ontbrekende jq consequent doet.
CONTRACT_STATE=$(fsc_contract_state "$FINAL" "$HASH")
case "$CONTRACT_STATE" in
  valid|contract_state_valid) echo "OK: contract $HASH heeft manager-state CONTRACT_STATE_VALID (geverifieerd)." ;;
  unknown) echo "  (state-check niet mogelijk: geen jq of afwijkende JSON-vorm — accept-aanwezigheid hierboven blijft de gate.)" ;;
  *) echo "FAIL: contract $HASH draagt de accept-handtekening maar staat niet op CONTRACT_STATE_VALID (state=$CONTRACT_STATE)." >&2; exit 1 ;;
esac

GRANT=$(fsc_grant_hash "$FINAL" "$HASH" "$SERVICE_NAME" "$THUMB")
[ "$GRANT" != "unknown" ] || {
  echo "FAIL: kon geen grant-hash vinden voor service=$SERVICE_NAME, outway-thumbprint=$THUMB" \
       "in contract $HASH (vereist jq; installeer jq)." >&2
  exit 1
}

mkdir -p "$STATE_DIR" && printf '%s\n' "$HASH" > "$STATE_FILE"
echo "CONTRACT-HASH: $HASH"
echo "GRANT-HASH: $GRANT"
echo "CONSUME OK."
