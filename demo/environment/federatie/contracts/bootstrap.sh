#!/usr/bin/env bash
# Zet idempotent een geldig, wederzijds ondertekend ServiceConnectionGrant-contract op tussen een
# consumer-peer en een provider-peer.
#
# Generiek: alle peers, adressen en certificaten komen uit env. `fbs-contracten.sh` ernaast vult
# die in voor de FBS-peers en loopt over de magazijnen.
#
# Stroom (OpenFSC Manager Internal-API):
#   1. bereken de SPKI-SHA256-thumbprint van het GROUP-cert van de consumer-outway. Dat is waarmee
#      de outway zich naar de provider-inway identificeert, en het is stabiel bij cert-rotatie
#      binnen hetzelfde sleutelpaar;
#   2. bestaat er al een geldig contract voor precies deze combinatie? -> klaar;
#   3. POST /v1/contracts op de EIGEN manager. Die tekent server-side namens de consumer en synct
#      het contract via de mesh naar de provider;
#   4. poll de provider tot het contract er is, dan PUT /v1/contracts/{hash}/accept op de
#      PROVIDER-manager. Expliciet, want `AUTO_SIGN_GRANTS` dekt alleen (delegated)servicePublication;
#   5. poll tot de CONSUMER het contract als `CONTRACT_STATE_VALID` ziet. De provider-accept wordt
#      async naar de consumer gepusht met een begrensde backoff en zonder cron-retry; landt die
#      niet, dan blijft het contract daar `proposed` en ziet de outway de grant nooit. Blijft het
#      hangen, dan forceren we de her-distributie.
#
# IDEMPOTENTIE ZONDER STATE-FILE. De generieke variant in `moza-fsc-testnet` onthoudt de
# content-hash van het geaccepteerde contract in een bestand. Dat werkt op een ontwikkelmachine,
# maar niet in een deploy: elke job start met een lege schijf, dus elke run maakt er nóg een
# geldig contract bij. Deze variant leidt het bestaan af uit de contracten zelf — service, provider,
# consumer-outway en thumbprint samen zijn de identiteit — zodat een herhaalde run een no-op is,
# waar hij ook draait.
set -euo pipefail

HERE="$(cd "$(dirname "$0")" && pwd)"
ENVDIR="$(cd "${HERE}/../.." && pwd)"

# shellcheck source=../../lib/fsc-harness.sh
. "${ENVDIR}/lib/fsc-harness.sh"

fsc_errlog_init
fsc_have_jq

# --- Parameters ---------------------------------------------------------------------------------
CONSUMER_OIN="${FSC_CONSUMER_OIN:?zet FSC_CONSUMER_OIN}"
PROVIDER_OIN="${FSC_PROVIDER_OIN:?zet FSC_PROVIDER_OIN}"
SERVICE_NAME="${FSC_SERVICE_NAME:?zet FSC_SERVICE_NAME}"
GROUP_ID="${FSC_GROUP_ID:-moza-fbs-test}"

# Het GROUP-cert van de consumer-outway (host-pad). Bewust het group- en niet het internal-cert:
# de outway registreert bij zijn controller met zijn group-cert en stuurt datzelfde thumbprint naar
# GetOutwayServices, waar de manager het tegen de grant matcht.
OUTWAY_CERT="${FSC_OUTWAY_CERT:?zet FSC_OUTWAY_CERT}"

CONSUMER_MANAGER="${FSC_CONSUMER_MANAGER:?zet FSC_CONSUMER_MANAGER}"
CONSUMER_CERT="${FSC_CONSUMER_CERT:?zet FSC_CONSUMER_CERT}"
CONSUMER_KEY="${FSC_CONSUMER_KEY:?zet FSC_CONSUMER_KEY}"
CONSUMER_CA="${FSC_CONSUMER_CA:?zet FSC_CONSUMER_CA}"

PROVIDER_MANAGER="${FSC_PROVIDER_MANAGER:?zet FSC_PROVIDER_MANAGER}"
PROVIDER_CERT="${FSC_PROVIDER_CERT:?zet FSC_PROVIDER_CERT}"
PROVIDER_KEY="${FSC_PROVIDER_KEY:?zet FSC_PROVIDER_KEY}"
PROVIDER_CA="${FSC_PROVIDER_CA:?zet FSC_PROVIDER_CA}"

SYNC_TIMEOUT="${FSC_SYNC_TIMEOUT:-20}"
SYNC_INTERVAL="${FSC_SYNC_INTERVAL:-2}"

# De adressen worden geconcateneerd tot curl's URL-argument; een waarde die met `-` begint zou curl
# als optie lezen (`-K/pad` maakt er een config-file-lees van).
for _adres in "$CONSUMER_MANAGER" "$PROVIDER_MANAGER"; do
  case "$_adres" in
    https://*) ;;
    *) echo "FAIL: manager-adres moet met https:// beginnen: '${_adres}'" >&2; exit 2 ;;
  esac
done

[ "$HAVE_JQ" -eq 1 ] || {
  echo "FAIL: jq is vereist. De idempotentie leunt op het uitlezen van de contract-JSON; zonder jq" >&2
  echo "  zou elke run een nieuw contract aanmaken in plaats van een bestaand te herkennen." >&2
  exit 1
}

# --- curl-helpers -------------------------------------------------------------------------------
# Rechtstreeks vanaf de host: onder hostnet luisteren alle managers in de netns van de aanroeper.
# De namen staan niet in /etc/hosts (extra_hosts geldt alleen binnen containers), vandaar --resolve
# op elk manager-adres.
resolve_args() {
  local url="$1" hostnaam poort
  hostnaam="${url#https://}"; hostnaam="${hostnaam%%/*}"
  poort="${hostnaam##*:}"; hostnaam="${hostnaam%%:*}"
  printf '%s\n' --resolve "${hostnaam}:${poort}:127.0.0.1"
}

# api <manager-url> <cert> <key> <ca> <curl-args...>
api() {
  local url="$1" cert="$2" key="$3" ca="$4"; shift 4
  local r args=()
  while IFS= read -r r; do args+=("$r"); done < <(resolve_args "$url")

  curl -sS --fail-with-body --noproxy '*' "${args[@]}" \
    --cert "$cert" --key "$key" --cacert "$ca" "$@" 2>"$ERRLOG"
}

consumer_api() { api "$CONSUMER_MANAGER" "$CONSUMER_CERT" "$CONSUMER_KEY" "$CONSUMER_CA" "$@"; }
provider_api() { api "$PROVIDER_MANAGER" "$PROVIDER_CERT" "$PROVIDER_KEY" "$PROVIDER_CA" "$@"; }

# --- 1. Outway-thumbprint -----------------------------------------------------------------------
command -v openssl >/dev/null 2>&1 || { echo "FAIL: openssl niet gevonden." >&2; exit 1; }
[ -r "$OUTWAY_CERT" ] || { echo "FAIL: outway-cert niet leesbaar: ${OUTWAY_CERT} (pki/issue.sh gedraaid?)" >&2; exit 1; }

THUMB="$(openssl x509 -in "$OUTWAY_CERT" -pubkey -noout 2>"$ERRLOG" \
           | openssl pkey -pubin -outform DER 2>>"$ERRLOG" \
           | openssl dgst -sha256 -r 2>>"$ERRLOG" | cut -d' ' -f1)" || THUMB=""
case "$THUMB" in
  [0-9a-f]*) [ "${#THUMB}" -eq 64 ] || { echo "FAIL: thumbprint is geen 64 hex-tekens: '${THUMB}'" >&2; exit 1; } ;;
  *) echo "FAIL: kon de outway-thumbprint niet berekenen uit ${OUTWAY_CERT}: $(fsc_last_error)" >&2; exit 1 ;;
esac
echo "bootstrap: outway public-key-thumbprint = ${THUMB}"

# --- 2. Bestaat het contract al? ----------------------------------------------------------------
# De identiteit van een serviceConnection is de combinatie service + provider + consumer-outway +
# thumbprint. Alleen op servicenaam matchen zou het servicePublication-contract voor dezelfde
# dienst meetellen, dat op dezelfde manager staat en altijd matcht.
bestaande_contracten() {  # echoot de hashes van geldige, niet-ingetrokken contracten voor deze combinatie
  local json
  json="$(provider_api "${PROVIDER_MANAGER}/v1/contracts")" || {
    echo "FAIL: kon de contracten van de provider niet ophalen: $(fsc_last_error)" >&2
    return 1
  }

  printf '%s' "$json" | jq -r \
    --arg svc "$SERVICE_NAME" --arg prov "$PROVIDER_OIN" \
    --arg cons "$CONSUMER_OIN" --arg thumb "$THUMB" '
    [ .contracts[]?
      | select(.state == "CONTRACT_STATE_VALID" and (.has_revoked // false) == false)
      | select(any(.content.grants[]?;
            .type == "GRANT_TYPE_SERVICE_CONNECTION"
            and .service.name == $svc
            and .service.peer_id == $prov
            and .outway.peer_id == $cons
            and .outway.identification.public_key_thumbprint == $thumb))
      | .hash ] | .[]' 2>"$ERRLOG" || {
    echo "FAIL: contract-JSON niet te parsen: $(fsc_last_error)" >&2
    return 1
  }
}

BESTAAND="$(bestaande_contracten)" || exit 1
if [ -n "$BESTAAND" ]; then
  AANTAL="$(printf '%s\n' "$BESTAAND" | grep -c .)"
  echo "OK: er is al een geldig contract voor ${SERVICE_NAME} (${CONSUMER_OIN} -> ${PROVIDER_OIN})."

  if [ "$AANTAL" -gt 1 ]; then
    # Duplicaten zijn niet fataal — de outway gebruikt er één — maar ze wijzen op een eerdere run
    # zonder deze controle, en ze stapelen zich op.
    echo "  WAARSCHUWING: ${AANTAL} geldige contracten voor dezelfde combinatie; ruim de overtollige op." >&2
  fi

  printf '%s\n' "$BESTAAND" | sed 's/^/  contract: /'
  echo "BOOTSTRAP OK (bestaand contract)."
  exit 0
fi

# --- 3. Contract opstellen en indienen bij de eigen manager --------------------------------------
IV="$(fsc_new_iv)"
fsc_validity

echo "bootstrap: serviceConnection-contract indienen bij de consumer-manager..."
# `service.type` is verplicht op een connection-grant (de publicatie-grant defaultte 'm, deze niet),
# en `outway.identification` is sinds OpenFSC v2.0.0 een union met `type` als discriminator — de
# platte v1-vorm wordt niet meer geaccepteerd. `fsc_version` zetten we niet zelf: de POST neemt
# `createContractContent`, waar dat veld ontbreekt; de manager vult 'm en neemt 'm mee in de hash.
RESP="$(consumer_api -X POST "${CONSUMER_MANAGER}/v1/contracts" -H 'Content-Type: application/json' -d "{
  \"contract_content\": {
    \"iv\": \"${IV}\",
    \"group_id\": \"${GROUP_ID}\",
    \"hash_algorithm\": \"HASH_ALGORITHM_SHA3_512\",
    \"created_at\": ${NBF},
    \"validity\": { \"not_before\": $((NBF - 60)), \"not_after\": ${NAF} },
    \"grants\": [ {
      \"type\": \"GRANT_TYPE_SERVICE_CONNECTION\",
      \"service\": { \"type\": \"SERVICE_TYPE_SERVICE\", \"peer_id\": \"${PROVIDER_OIN}\", \"name\": \"${SERVICE_NAME}\" },
      \"outway\": {
        \"peer_id\": \"${CONSUMER_OIN}\",
        \"identification\": {
          \"type\": \"OUTWAY_IDENTIFICATION_TYPE_PUBLIC_KEY_THUMBPRINT\",
          \"public_key_thumbprint\": \"${THUMB}\"
        }
      }
    } ]
  }
}")" || { echo "FAIL: POST /v1/contracts geweigerd: ${RESP:-<leeg>} $(fsc_last_error)" >&2; exit 1; }

HASH="$(printf '%s' "$RESP" | jq -r '.content_hash // empty' 2>/dev/null)"
[ -n "$HASH" ] || { echo "FAIL: respons zonder content_hash (formaat geweigerd?): ${RESP}" >&2; exit 1; }
echo "  consumer-handtekening gezet; mesh-sync gestart; content_hash=${HASH}"

# --- 4. Provider accepteert ----------------------------------------------------------------------
contract_zichtbaar() {
  provider_api "${PROVIDER_MANAGER}/v1/contracts" 2>/dev/null \
    | jq -e --arg h "$HASH" 'any(.contracts[]?; .hash == $h)' >/dev/null 2>&1
}

echo "bootstrap: wachten tot het contract naar de provider gesynct is..."
elapsed=0; gesynct=0
while [ "$elapsed" -lt "$SYNC_TIMEOUT" ]; do
  if contract_zichtbaar; then gesynct=1; break; fi

  sleep "$SYNC_INTERVAL"; elapsed=$((elapsed + SYNC_INTERVAL))
  echo "  ...nog niet gesynct (${elapsed}s)" >&2
done

[ "$gesynct" -eq 1 ] || {
  echo "FAIL: contract ${HASH} is niet binnen ${SYNC_TIMEOUT}s naar de provider gesynct." >&2
  exit 1
}

echo "bootstrap: provider accepteert..."
provider_api -X PUT "${PROVIDER_MANAGER}/v1/contracts/${HASH}/accept" -H 'Content-Type: application/json' >/dev/null \
  || { echo "FAIL: PUT accept (${HASH}) geweigerd: $(fsc_last_error)" >&2; exit 1; }
echo "  provider-handtekening gezet."

# --- 5. Wachten tot de consumer het contract als geldig ziet --------------------------------------
consumer_state() {
  consumer_api "${CONSUMER_MANAGER}/v1/contracts" 2>/dev/null \
    | jq -r --arg h "$HASH" 'first(.contracts[]? | select(.hash == $h) | .state) // "onbekend"' 2>/dev/null \
    || echo onbekend
}

wacht_op_valid() {
  local elapsed=0
  while [ "$elapsed" -lt "$SYNC_TIMEOUT" ]; do
    [ "$(consumer_state)" = "CONTRACT_STATE_VALID" ] && return 0

    sleep "$SYNC_INTERVAL"; elapsed=$((elapsed + SYNC_INTERVAL))
    echo "  ...contract nog niet 'valid' op de consumer (${elapsed}s)" >&2
  done
  return 1
}

echo "bootstrap: wachten tot de consumer het contract als geldig ziet..."
if ! wacht_op_valid; then
  # De accept-handtekening wordt best-effort naar de consumer gepusht; strandt die, dan blijft het
  # contract daar `proposed` en ziet de outway de grant nooit. Dit is het canonieke herstel.
  echo "bootstrap: accept-handtekening opnieuw laten distribueren..." >&2
  provider_api -X POST \
    "${PROVIDER_MANAGER}/v1/contracts/${HASH}/distributions/${CONSUMER_OIN}/DISTRIBUTION_ACTION_SUBMIT_ACCEPT_SIGNATURE/retry" \
    -H 'Content-Type: application/json' >/dev/null \
    || echo "  WARN: her-distributie gaf een fout: $(fsc_last_error)" >&2

  wacht_op_valid || {
    echo "FAIL: contract ${HASH} werd niet 'valid' op de consumer (state: $(consumer_state))." >&2
    exit 1
  }
fi

echo "OK: contract ${HASH} is wederzijds ondertekend en geldig."
echo "BOOTSTRAP OK."
