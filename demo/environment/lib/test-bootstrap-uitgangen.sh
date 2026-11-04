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
echo "== bootstrap.sh: de coördinatie van de twee helften =="

# draai_bootstrap <consumer-uitgangen> <provider-uitgangen>: draait een kopie van bootstrap.sh naast
# gestubde helften. Elke helft geeft per aanroep de volgende uitgang uit zijn reeks; de laatste
# blijft herhalen. Echoot "<exit> <aantal provider-aanroepen>".
draai_bootstrap() {
  local tmp rc=0 helft aanroepen
  tmp="$(mktemp -d)"
  cp "${CONTRACTS}/bootstrap.sh" "$tmp/"
  printf '%s\n' $1 > "${tmp}/bootstrap-consumer.reeks"
  printf '%s\n' $2 > "${tmp}/bootstrap-provider.reeks"

  for helft in bootstrap-consumer bootstrap-provider; do
    cat > "${tmp}/${helft}.sh" <<'STUB'
#!/usr/bin/env bash
reeks="${0%.sh}.reeks"
echo x >> "${0%.sh}.aanroepen"
uitgang="$(head -1 "$reeks")"
if [ "$(wc -l < "$reeks")" -gt 1 ]; then tail -n +2 "$reeks" > "${reeks}.t" && mv "${reeks}.t" "$reeks"; fi
exit "$uitgang"
STUB
    chmod +x "${tmp}/${helft}.sh"
  done

  FSC_LIBDIR="$HERE" FSC_CONSUMER_OIN="$CONS" FSC_SERVICE_NAME=berichtenmagazijn \
  FSC_SYNC_TIMEOUT=2 FSC_SYNC_INTERVAL=1 \
    "${tmp}/bootstrap.sh" >/dev/null 2>&1 || rc=$?

  aanroepen=0
  [ -f "${tmp}/bootstrap-provider.aanroepen" ] && aanroepen="$(wc -l < "${tmp}/bootstrap-provider.aanroepen" | tr -d ' ')"
  rm -rf "$tmp"
  printf '%s %s' "$rc" "$aanroepen"
}

# assert_bootstrap <desc> <verwachte exit> <verwachte provider-aanroepen> <consumer> <provider>
assert_bootstrap() {
  local uit
  uit="$(draai_bootstrap "$4" "$5")"
  assert_uitgang "$1" "$2" "${uit% *}"

  if [ "${uit#* }" = "$3" ]; then
    echo "OK: $1 ($3 provider-aanroep(en))"
  else
    echo "FAIL: $1 — verwacht $3 provider-aanroep(en), kreeg ${uit#* }" >&2
    fails=$((fails + 1))
  fi
}

assert_bootstrap "contract bestaat al: provider niet nodig" 0 0 "0" "0"
assert_bootstrap "provider tekent meteen" 0 1 "3 0" "0"

# De race: het contract kruist via de mesh-sync, dus vlak na het indienen heeft de provider het
# nog niet. Dit gaf exit 3 en een rode bootstrap, terwijl een tweede poging slaagde.
assert_bootstrap "contract komt pas bij de tweede poging binnen" 0 2 "3 0" "3 0"
assert_bootstrap "contract komt nooit binnen: fout na de wachttijd" 1 3 "3" "3"

# Alleen 3 is het herhalen waard; een fout van de provider is meteen het antwoord.
assert_bootstrap "provider-fout: geen herhaling" 1 1 "3" "1"
assert_bootstrap "configuratiefout van de provider gaat door als 2" 2 1 "3" "2"
assert_bootstrap "consumer ziet het contract pas na even wachten geldig" 0 1 "3 3 0" "0"

echo
if [ "$fails" -ne 0 ]; then
  echo "ROOD: ${fails} test(s) gefaald." >&2
  exit 1
fi

echo "GROEN: alle uitgang-tests geslaagd."
