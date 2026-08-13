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
#   2. bestaat er al een geldig contract voor precies deze combinatie? Zo ja, en staat het ook al
#      geldig bij de consumer: klaar. Staat het alleen bij de provider geldig (de accept-push naar
#      de consumer kan stranden, zie stap 5), dan wordt hier hetzelfde herstel toegepast als bij een
#      verse bootstrap, zónder opnieuw te posten;
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
#
# De existence-check (stap 2) en de POST (stap 3) zijn niet atomair: twee gelijktijdige of
# herhaalde runs kunnen allebei een nieuw contract posten. Dat wordt hier niet voorkomen (dat vergt
# een lock, en dus weer state), maar wel zelf-herstellend gemaakt: bij meer dan één geldig contract
# voor dezelfde combinatie trekt de eerstvolgende run de overtollige in via
# `PUT /v1/contracts/{hash}/revoke` en gebruikt verder altijd het (sorteer-)eerste hash — stabiel
# over runs heen, ook als de volgorde van de manager-API zelf niet gegarandeerd stabiel is.
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

THUMB="$(fsc_outway_thumbprint "$OUTWAY_CERT")" \
  || { echo "FAIL: kon de outway-thumbprint niet berekenen uit ${OUTWAY_CERT}: $(fsc_last_error)" >&2; exit 1; }
echo "bootstrap: outway public-key-thumbprint = ${THUMB}"

# --- Sync-helpers (gebruikt door zowel de existence-check als de verse bootstrap-flow) -----------
VALID_STATE=contract_state_valid

# contract_zichtbaar <hash>: staat dit contract al in de contractenlijst van de provider?
contract_zichtbaar() {
  local hash="$1" rc
  provider_api "${PROVIDER_MANAGER}/v1/contracts" 2>"$ERRLOG" \
    | jq -e --arg h "$hash" 'any(.contracts[]?; .hash == $h)' >/dev/null 2>>"$ERRLOG"
  rc=$?
  fsc_warn_errlog "sync-poll (provider)"
  return "$rc"
}

# consumer_state <hash>: manager-state van dit contract op de consumer, of "onbekend".
consumer_state() {
  local hash="$1" json rc state
  json="$(consumer_api "${CONSUMER_MANAGER}/v1/contracts" 2>"$ERRLOG")"
  rc=$?
  fsc_warn_errlog "state-poll (consumer)"
  [ "$rc" -eq 0 ] || { echo onbekend; return; }

  state="$(fsc_contract_state "$json" "$hash")"
  [ "$state" = unknown ] && echo onbekend || printf '%s\n' "$state"
}

# wacht_op_valid <hash>: pollt tot de consumer het contract als geldig ziet, of tot SYNC_TIMEOUT.
wacht_op_valid() {
  local hash="$1" elapsed=0
  while [ "$elapsed" -lt "$SYNC_TIMEOUT" ]; do
    [ "$(consumer_state "$hash")" = "$VALID_STATE" ] && return 0

    sleep "$SYNC_INTERVAL"; elapsed=$((elapsed + SYNC_INTERVAL))
    echo "  ...contract nog niet 'valid' op de consumer (${elapsed}s)" >&2
  done
  return 1
}

# zorg_dat_consumer_gesynct <hash>: wacht tot de consumer het contract geldig ziet; forceert één
# keer de her-distributie van de accept-handtekening als dat niet vanzelf lukt binnen SYNC_TIMEOUT
# (het canonieke herstel voor een gestrande best-effort-push, zie de moduledoc).
zorg_dat_consumer_gesynct() {
  local hash="$1"
  wacht_op_valid "$hash" && return 0

  echo "bootstrap: accept-handtekening opnieuw laten distribueren..." >&2
  provider_api -X PUT \
    "${PROVIDER_MANAGER}/v1/contracts/${hash}/distributions/${CONSUMER_OIN}/DISTRIBUTION_ACTION_SUBMIT_ACCEPT_SIGNATURE/retry" \
    -H 'Content-Type: application/json' >/dev/null \
    || echo "  WARN: her-distributie gaf een fout: $(fsc_last_error)" >&2

  wacht_op_valid "$hash"
}

# --- 2. Bestaat het contract al? ------------------------------------------------------------------
# De identiteit van een serviceConnection is de combinatie service + provider + consumer-outway +
# thumbprint. Alleen op servicenaam matchen zou het servicePublication-contract voor dezelfde
# dienst meetellen, dat op dezelfde manager staat en altijd matcht.
bestaande_contracten() {  # hashes van geldige, niet-ingetrokken contracten voor deze combinatie, gesorteerd
  local json
  json="$(provider_api "${PROVIDER_MANAGER}/v1/contracts")" || {
    echo "FAIL: kon de contracten van de provider niet ophalen: $(fsc_last_error)" >&2
    return 1
  }

  fsc_grant_actief "$json" "$SERVICE_NAME" "$PROVIDER_OIN" "$CONSUMER_OIN" "$THUMB" | sort
}

BESTAAND="$(bestaande_contracten)" || exit 1
if [ -n "$BESTAAND" ]; then
  AANTAL="$(printf '%s\n' "$BESTAAND" | grep -c .)"
  HASH="$(printf '%s\n' "$BESTAAND" | head -n1)"
  echo "OK: er is al een geldig contract voor ${SERVICE_NAME} (${CONSUMER_OIN} -> ${PROVIDER_OIN})."
  printf '%s\n' "$BESTAAND" | sed 's/^/  contract: /'

  if [ "$AANTAL" -gt 1 ]; then
    # Kan ontstaan doordat deze existence-check en de POST (stap 3) niet atomair zijn: twee
    # gelijktijdige of herhaalde runs kunnen allebei een nieuw contract posten. De outway gebruikt
    # er sowieso maar één — het gesorteerd-eerste hash hierboven — maar de rest ruimt zichzelf hier
    # op, zodat een volgende run weer bij één contract uitkomt.
    echo "  WAARSCHUWING: ${AANTAL} geldige contracten voor dezelfde combinatie; trek de overtollige in." >&2
    printf '%s\n' "$BESTAAND" | tail -n +2 | while IFS= read -r dup; do
      provider_api -X PUT "${PROVIDER_MANAGER}/v1/contracts/${dup}/revoke" \
          -H 'Content-Type: application/json' >/dev/null 2>"$ERRLOG" \
        && echo "  ingetrokken: ${dup}" >&2 \
        || echo "  WARN: kon duplicaat ${dup} niet intrekken: $(fsc_last_error)" >&2
    done
  fi

  if [ "$(consumer_state "$HASH")" = "$VALID_STATE" ]; then
    echo "BOOTSTRAP OK (bestaand contract)."
    exit 0
  fi

  # Geldig op de provider, maar (nog) niet gesynct naar de consumer — zonder deze controle zou een
  # retry hier ten onrechte "klaar" melden terwijl de outway de grant nooit ziet (zie moduledoc).
  echo "bootstrap: contract ${HASH} is geldig op de provider maar nog niet gesynct naar de consumer..." >&2
  zorg_dat_consumer_gesynct "$HASH" || {
    echo "FAIL: contract ${HASH} werd niet 'valid' op de consumer (state: $(consumer_state "$HASH"))." >&2
    exit 1
  }
  echo "OK: contract ${HASH} is alsnog wederzijds gesynct."
  echo "BOOTSTRAP OK (bestaand contract, opnieuw gesynct)."
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

HASH="$(printf '%s' "$RESP" | jq -r '.content_hash // empty' 2>/dev/null)" || HASH=""
[ -n "$HASH" ] || { echo "FAIL: respons zonder content_hash (formaat geweigerd?): ${RESP}" >&2; exit 1; }
echo "  consumer-handtekening gezet; mesh-sync gestart; content_hash=${HASH}"

# --- 4. Provider accepteert ----------------------------------------------------------------------
echo "bootstrap: wachten tot het contract naar de provider gesynct is..."
elapsed=0; gesynct=0
while [ "$elapsed" -lt "$SYNC_TIMEOUT" ]; do
  if contract_zichtbaar "$HASH"; then gesynct=1; break; fi

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
echo "bootstrap: wachten tot de consumer het contract als geldig ziet..."
zorg_dat_consumer_gesynct "$HASH" || {
  echo "FAIL: contract ${HASH} werd niet 'valid' op de consumer (state: $(consumer_state "$HASH"))." >&2
  exit 1
}

echo "OK: contract ${HASH} is wederzijds ondertekend en geldig."
echo "BOOTSTRAP OK."
