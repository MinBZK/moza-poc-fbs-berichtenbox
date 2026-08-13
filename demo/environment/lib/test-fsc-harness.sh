#!/usr/bin/env bash
# Fixture-tests voor de pure-logica-helpers in fsc-harness.sh: fsc_scrub_errlog (tegen de exacte
# ANSI-bytes uit de PR-166-bug-report, ericwout-overheid, 2026-08-12), en de contract-matcher/
# thumbprint-helpers die bootstrap.sh en smoke-contract.sh delen.
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

fsc_have_jq
[ "$HAVE_JQ" -eq 1 ] || { echo "FAIL: jq is vereist voor deze fixture-tests." >&2; exit 1; }

# --- fsc_grant_actief -------------------------------------------------------------------------
# Vaste testidentiteit; de waarden zelf doen er niet toe, alleen dat thumbprint 1 != thumbprint 2.
SVC=berichtenmagazijn
PROV=00000000000000100000
CONS=00000000000000001000
THUMB="$(printf 'a%.0s' $(seq 1 64))"
ANDERE_THUMB="$(printf 'b%.0s' $(seq 1 64))"

# contract_json <hash> <state> <has_revoked> <heeft-provider-sig> <heeft-consumer-sig> <thumb>
contract_json() {
  jq -n --arg h "$1" --arg st "$2" --argjson rv "$3" --argjson hp "$4" --argjson hc "$5" \
        --arg svc "$SVC" --arg prov "$PROV" --arg cons "$CONS" --arg thumb "$6" '
    { hash: $h, state: $st, has_revoked: $rv,
      signatures: { accept: ( {}
        + (if $hp then {($prov): true} else {} end)
        + (if $hc then {($cons): true} else {} end) ) },
      content: { grants: [ {
        type: "GRANT_TYPE_SERVICE_CONNECTION",
        service: { name: $svc, peer_id: $prov },
        outway: { peer_id: $cons, identification: { public_key_thumbprint: $thumb } }
      } ] } }'
}

# contracts_json <contract-json...>: bundelt losse contract-objecten tot { "contracts": [...] }.
contracts_json() { printf '%s\n' "$@" | jq -s '{ contracts: . }'; }

assert_grant_hashes() {
  local desc="$1" json="$2" verwacht="$3" thumb="${4:-$THUMB}" kregen
  kregen="$(fsc_grant_actief "$json" "$SVC" "$PROV" "$CONS" "$thumb")"
  if [ "$kregen" = "$verwacht" ]; then
    echo "OK: $desc"
  else
    echo "FAIL: $desc — verwacht [$verwacht], kreeg [$kregen]" >&2
    fails=$((fails + 1))
  fi
}

VALID_VOLLEDIG="$(contract_json h1 CONTRACT_STATE_VALID false true true "$THUMB")"
assert_grant_hashes "geldig + niet-ingetrokken + beide handtekeningen -> gevonden" \
  "$(contracts_json "$VALID_VOLLEDIG")" "h1"

GEREVOKED="$(contract_json h2 CONTRACT_STATE_VALID true true true "$THUMB")"
assert_grant_hashes "ingetrokken contract telt niet mee" \
  "$(contracts_json "$GEREVOKED")" ""

ZONDER_CONSUMER_SIG="$(contract_json h3 CONTRACT_STATE_VALID false true false "$THUMB")"
assert_grant_hashes "ontbrekende consumer-handtekening telt niet mee" \
  "$(contracts_json "$ZONDER_CONSUMER_SIG")" ""

ANDERE_STATE="$(contract_json h4 CONTRACT_STATE_PROPOSED false true true "$THUMB")"
assert_grant_hashes "niet-VALID state telt niet mee" \
  "$(contracts_json "$ANDERE_STATE")" ""

ANDER_THUMB_CONTRACT="$(contract_json h5 CONTRACT_STATE_VALID false true true "$ANDERE_THUMB")"
assert_grant_hashes "afwijkende outway-thumbprint telt niet mee" \
  "$(contracts_json "$ANDER_THUMB_CONTRACT")" ""

assert_grant_hashes "twee geldige contracten voor dezelfde combinatie komen beide terug" \
  "$(contracts_json "$VALID_VOLLEDIG" "$(contract_json h1b CONTRACT_STATE_VALID false true true "$THUMB")")" \
  "$(printf 'h1\nh1b')"

assert_grant_hashes "lege contractenlijst geeft niets terug" \
  "$(contracts_json)" ""

# --- fsc_outway_thumbprint ---------------------------------------------------------------------
CERTDIR="$(mktemp -d)"
trap 'rm -rf "$CERTDIR"' EXIT
openssl req -x509 -newkey ec -pkeyopt ec_paramgen_curve:prime256v1 -nodes -days 1 \
  -subj "/CN=test-outway" -keyout "$CERTDIR/key.pem" -out "$CERTDIR/cert.pem" >/dev/null 2>&1

ERRLOG="$(mktemp)"
GEVONDEN="$(fsc_outway_thumbprint "$CERTDIR/cert.pem")" && THUMB_OK=1 || THUMB_OK=0
if [ "$THUMB_OK" -eq 1 ] && printf '%s' "$GEVONDEN" | grep -qE '^[0-9a-f]{64}$'; then
  echo "OK: fsc_outway_thumbprint geeft 64 lowercase hex-tekens voor een geldig cert"
else
  echo "FAIL: fsc_outway_thumbprint op een geldig cert gaf '$GEVONDEN' (ok=$THUMB_OK)" >&2
  fails=$((fails + 1))
fi

if GEVONDEN="$(fsc_outway_thumbprint "$CERTDIR/niet-bestaand.pem" 2>/dev/null)"; then
  echo "FAIL: fsc_outway_thumbprint had moeten falen op een ontbrekend bestand, gaf '$GEVONDEN'" >&2
  fails=$((fails + 1))
else
  echo "OK: fsc_outway_thumbprint faalt op een ontbrekend certificaatbestand"
fi

printf 'dit is geen certificaat' > "$CERTDIR/rommel.pem"
if GEVONDEN="$(fsc_outway_thumbprint "$CERTDIR/rommel.pem" 2>/dev/null)"; then
  echo "FAIL: fsc_outway_thumbprint had moeten falen op een corrupt bestand, gaf '$GEVONDEN'" >&2
  fails=$((fails + 1))
else
  echo "OK: fsc_outway_thumbprint faalt op een corrupt certificaatbestand"
fi
rm -f "$ERRLOG"

if [ "$fails" -eq 0 ]; then
  echo "ALLE ASSERTS GROEN"
  exit 0
else
  echo "FAIL: $fails assert(s) gefaald" >&2
  exit 1
fi
