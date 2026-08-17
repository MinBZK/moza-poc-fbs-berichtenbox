#!/usr/bin/env bash
# Fixture-tests voor de uitgangen van de twee bootstrap-helften.
#
# Deze bestaan omdat de exit-codes de coördinatie ZIJN: `zad-lus.sh` vertakt erop, en `bootstrap.sh`
# ook. Twee opeenvolgende reviewrondes introduceerden allebei een regressie in precies deze ladder —
# een kapotte toestand die als 0 naar buiten kwam — en geen enkele test merkte dat, omdat alle
# andere tests op bibliotheekniveau zitten en de smokes alleen het gelukkige pad tegen een draaiende
# federatie lopen.
#
# De helften worden hier zonder netwerk gedraaid: een `curl` vooraan op PATH levert een vaste
# contractenlijst, zodat elke combinatie van getekend/geweigerd/aandacht afdwingbaar is.
set -euo pipefail

HERE="$(cd "$(dirname "$0")" && pwd)"
CONTRACTS="$(cd "${HERE}/../federatie/contracts" && pwd)"

fails=0

assert_uitgang() {
  local desc="$1" verwacht="$2" kregen="$3"
  if [ "$kregen" = "$verwacht" ]; then
    echo "OK: $desc (exit $kregen)"
  else
    echo "FAIL: $desc — verwacht exit $verwacht, kreeg $kregen" >&2
    fails=$((fails + 1))
  fi
}

PROV=00000000000000100000
CONS=00000000000000001000
THUMB="$(printf 'a%.0s' $(seq 1 64))"
NU="$(date -u +%s)"

# contract <hash> <dienst> <getekend-door-provider> <not_after-offset>
contract() {
  jq -nc --arg h "$1" --arg svc "$2" --argjson sig "$3" --argjson na "$4" \
         --arg prov "$PROV" --arg cons "$CONS" --arg thumb "$THUMB" --argjson nu "$NU" '
    { hash: $h, state: "CONTRACT_STATE_PROPOSED", has_revoked: false, has_rejected: false,
      signatures: { accept: ({($cons): true} + (if $sig then {($prov): true} else {} end)) },
      content: { group_id: "moza-fbs-test", hash_algorithm: "HASH_ALGORITHM_SHA3_512",
                 validity: { not_before: ($nu - 60), not_after: ($nu + $na) },
                 grants: [ { type: "GRANT_TYPE_SERVICE_CONNECTION",
                   service: { type: "SERVICE_TYPE_SERVICE", name: $svc, peer_id: $prov },
                   outway: { peer_id: $cons,
                     identification: { type: "OUTWAY_IDENTIFICATION_TYPE_PUBLIC_KEY_THUMBPRINT",
                                       public_key_thumbprint: $thumb } } } ] } }'
}

# draai_provider <contract-json...>: de provider-helft tegen een gestubde manager; echoot de exit-code.
draai_provider() {
  local tmp lijst rc=0
  tmp="$(mktemp -d)"
  lijst="$(printf '%s\n' "$@" | jq -sc '{ contracts: . }')"

  # De stub antwoordt op de lijst-call en zegt op alles wat muteert simpelweg ja. Meer is hier niet
  # nodig: getoetst wordt de uitgang, niet de manager.
  printf '%s' "$lijst" > "${tmp}/lijst.json"
  cat > "${tmp}/curl" <<'STUB'
#!/usr/bin/env bash
for arg in "$@"; do
  case "$arg" in
    *"/v1/contracts?"*) cat "$(dirname "$0")/lijst.json"; exit 0 ;;
  esac
done
exit 0
STUB
  chmod +x "${tmp}/curl"

  PATH="${tmp}:$PATH" \
  FSC_LIBDIR="$HERE" \
  FSC_PROVIDER_OIN="$PROV" FSC_DIENSTEN=berichtenmagazijn FSC_CONSUMERS="$CONS" \
  FSC_PROVIDER_MANAGER=https://manager:9443 \
  FSC_PROVIDER_CERT=/dev/null FSC_PROVIDER_KEY=/dev/null FSC_PROVIDER_CA=/dev/null \
    "${CONTRACTS}/bootstrap-provider.sh" >/dev/null 2>&1 || rc=$?

  rm -rf "$tmp"
  printf '%s' "$rc"
}

command -v jq >/dev/null 2>&1 || { echo "FAIL: jq is vereist." >&2; exit 1; }

echo "== provider-helft: de uitgangen per combinatie =="

TEKENBAAR="$(contract h1 berichtenmagazijn false 315360000)"
GEWEIGERD="$(contract h2 andere-dienst false 315360000)"
AANDACHT="$(contract h3 berichtenmagazijn true -1)"

assert_uitgang "lege lijst: wachten op de overkant" 3 "$(draai_provider)"
assert_uitgang "alleen iets tekenbaars" 0 "$(draai_provider "$TEKENBAAR")"
assert_uitgang "alleen iets geweigerds" 4 "$(draai_provider "$GEWEIGERD")"

# De regressie uit reviewronde 3: dit gaf exit 0, waarna de lus de wachtteller op nul zette en het
# component eeuwig groen bleef terwijl het datapad stuk was.
assert_uitgang "alleen een eerder getekend contract dat nu afvalt" 4 "$(draai_provider "$AANDACHT")"

assert_uitgang "geweigerd én aandacht, niets getekend" 4 "$(draai_provider "$GEWEIGERD" "$AANDACHT")"
assert_uitgang "getekend naast geweigerd" 0 "$(draai_provider "$TEKENBAAR" "$GEWEIGERD")"
assert_uitgang "getekend naast aandacht" 0 "$(draai_provider "$TEKENBAAR" "$AANDACHT")"

echo
echo "== provider-helft: configuratie eindigt op 2, niet op 1 =="

assert_config() {
  local desc="$1" rc=0; shift
  env -i PATH="$PATH" FSC_LIBDIR="$HERE" "$@" "${CONTRACTS}/bootstrap-provider.sh" >/dev/null 2>&1 || rc=$?
  assert_uitgang "$desc" 2 "$rc"
}

assert_config "ontbrekende OIN" FSC_DIENSTEN=d FSC_CONSUMERS=1 FSC_PROVIDER_MANAGER=https://m:9443 \
  FSC_PROVIDER_CERT=/dev/null FSC_PROVIDER_KEY=/dev/null FSC_PROVIDER_CA=/dev/null
assert_config "lege dienstenlijst" FSC_PROVIDER_OIN=1 FSC_DIENSTEN=" " FSC_CONSUMERS=1 \
  FSC_PROVIDER_MANAGER=https://m:9443 \
  FSC_PROVIDER_CERT=/dev/null FSC_PROVIDER_KEY=/dev/null FSC_PROVIDER_CA=/dev/null
assert_config "manager-adres als curl-optie" FSC_PROVIDER_OIN=1 FSC_DIENSTEN=d FSC_CONSUMERS=1 \
  FSC_PROVIDER_MANAGER=-K/etc/passwd \
  FSC_PROVIDER_CERT=/dev/null FSC_PROVIDER_KEY=/dev/null FSC_PROVIDER_CA=/dev/null
assert_config "limiet boven het maximum" FSC_PROVIDER_OIN=1 FSC_DIENSTEN=d FSC_CONSUMERS=1 \
  FSC_CONTRACT_LIMIET=5000 FSC_PROVIDER_MANAGER=https://m:9443 \
  FSC_PROVIDER_CERT=/dev/null FSC_PROVIDER_KEY=/dev/null FSC_PROVIDER_CA=/dev/null

echo
if [ "$fails" -ne 0 ]; then
  echo "ROOD: ${fails} test(s) gefaald." >&2
  exit 1
fi

echo "GROEN: alle uitgang-tests geslaagd."
