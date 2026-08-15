#!/usr/bin/env bash
# Fixture-tests voor de matchers in fsc-contract.sh.
#
# Het zwaartepunt ligt op fsc_contract_beoordeling: dat is de autorisatiegrens van de provider-helft.
# Waar de accept vóór de splitsing een gerichte handeling was (het script kende het hash dat het zelf
# net had ingediend), besluit de provider nu per binnengekomen contract of hij tekent — en de inhoud
# van dat contract komt van de tegenpartij. Elke eis heeft daarom zijn eigen weiger-test, inclusief
# het geval waar het contract een tweede grant meesmokkelt.
set -euo pipefail

HERE="$(cd "$(dirname "$0")" && pwd)"
# shellcheck source=fsc-harness.sh
source "$HERE/fsc-harness.sh"
# shellcheck source=fsc-contract.sh
source "$HERE/fsc-contract.sh"

fails=0

fsc_have_jq
[ "$HAVE_JQ" -eq 1 ] || { echo "FAIL: jq is vereist voor deze fixture-tests." >&2; exit 1; }

# Vaste testidentiteiten. De waarden zelf doen er niet toe, alleen dat ze van elkaar verschillen.
SVC=berichtenmagazijn
SVC2=berichtenarchief
PROV=00000000000000100000
CONS=00000000000000001000
CONS2=00000000000000002000
VREEMDE=00000000000000009999
THUMB="$(printf 'a%.0s' $(seq 1 64))"

DIENSTEN="$(fsc_json_lijst "$SVC")"
CONSUMERS="$(fsc_json_lijst "$CONS")"

# --- fixtures ------------------------------------------------------------------------------------

# grant <service> <service-peer> <consumer-peer> [type]: één grant-object.
grant() {
  jq -nc --arg svc "$1" --arg sp "$2" --arg cp "$3" --arg t "${4:-GRANT_TYPE_SERVICE_CONNECTION}" \
         --arg thumb "$THUMB" '
    { type: $t,
      service: { name: $svc, peer_id: $sp },
      outway: { peer_id: $cp, identification: { public_key_thumbprint: $thumb } } }'
}

# contract <hash> <state> <has_revoked> <getekend-door-provider> <grant-json...>
contract() {
  local h="$1" st="$2" rv="$3" ondertekend="$4"; shift 4
  printf '%s\n' "$@" | jq -sc --arg h "$h" --arg st "$st" --argjson rv "$rv" \
        --argjson sig "$ondertekend" --arg prov "$PROV" '
    { hash: $h, state: $st, has_revoked: $rv,
      signatures: { accept: (if $sig then {($prov): true} else {} end) },
      content: { grants: . } }'
}

bundel() { printf '%s\n' "$@" | jq -sc '{ contracts: . }'; }

# --- assert-helpers ------------------------------------------------------------------------------

assert_gelijk() {
  local desc="$1" verwacht="$2" kregen="$3"
  if [ "$kregen" = "$verwacht" ]; then
    echo "OK: $desc"
  else
    echo "FAIL: $desc — verwacht [$verwacht], kreeg [$kregen]" >&2
    fails=$((fails + 1))
  fi
}

# assert_beoordeling <desc> <json> <soort> <verwacht> [diensten-json] [consumers-json]
assert_beoordeling() {
  local desc="$1" json="$2" soort="$3" verwacht="$4" d="${5:-$DIENSTEN}" c="${6:-$CONSUMERS}"
  assert_gelijk "$desc" "$verwacht" \
    "$(fsc_contract_regels "$(fsc_contract_beoordeling "$json" "$PROV" "$d" "$c")" "$soort")"
}

# assert_weigerreden <desc> <json> <fragment>: de weigering noemt deze grond.
assert_weigerreden() {
  local desc="$1" json="$2" fragment="$3" reden
  reden="$(fsc_contract_regels "$(fsc_contract_beoordeling "$json" "$PROV" "$DIENSTEN" "$CONSUMERS")" WEIGER)"

  case "$reden" in
    *"$fragment"*) echo "OK: $desc" ;;
    *) echo "FAIL: $desc — verwachtte een reden met '$fragment', kreeg [$reden]" >&2
       fails=$((fails + 1)) ;;
  esac
}

echo "== fsc_contract_beoordeling: cardinaliteit =="

assert_beoordeling "lege contractenlijst levert niets op" '{"contracts":[]}' TEKEN ""
assert_beoordeling "lege contractenlijst weigert ook niets" '{"contracts":[]}' WEIGER ""

GOED="$(contract h1 CONTRACT_STATE_PROPOSED false false "$(grant "$SVC" "$PROV" "$CONS")")"
assert_beoordeling "één passend contract wordt getekend" "$(bundel "$GOED")" TEKEN "h1 ${CONS}"

# Twee passende contracten: een matcher die alleen het eerste teruggeeft zou hier de helft laten
# liggen, en dat is precies het geval dat op ZAD ontstaat zodra een tweede consumer aanhaakt.
GOED2="$(contract h2 CONTRACT_STATE_PROPOSED false false "$(grant "$SVC" "$PROV" "$CONS")")"
assert_beoordeling "twee passende contracten worden allebei getekend" \
  "$(bundel "$GOED" "$GOED2")" TEKEN "h1 ${CONS}
h2 ${CONS}"

echo
echo "== fsc_contract_beoordeling: de autorisatie-eisen, elk apart =="

# De kern van de invariant. Een contract draagt een LIJST grants; wie alleen toetst of er één
# passende grant in zit, tekent de tweede mee.
SMOKKEL="$(contract hs CONTRACT_STATE_PROPOSED false false \
  "$(grant "$SVC" "$PROV" "$CONS")" "$(grant "$SVC2" "$PROV" "$VREEMDE")")"
assert_beoordeling "een tweede grant erbij: niet tekenen" "$(bundel "$SMOKKEL")" TEKEN ""
assert_weigerreden "en de reden noemt het aantal grants" "$(bundel "$SMOKKEL")" "draagt 2 grants"

GEEN_GRANTS="$(contract hg CONTRACT_STATE_PROPOSED false false)"
assert_beoordeling "nul grants: niet tekenen" "$(bundel "$GEEN_GRANTS")" TEKEN ""

VERKEERD_TYPE="$(contract ht CONTRACT_STATE_PROPOSED false false \
  "$(grant "$SVC" "$PROV" "$CONS" GRANT_TYPE_SERVICE_PUBLICATION)")"
assert_beoordeling "ander grant-type: niet tekenen" "$(bundel "$VERKEERD_TYPE")" TEKEN ""
assert_weigerreden "en de reden noemt het type" "$(bundel "$VERKEERD_TYPE")" "geen serviceConnection"

ANDERE_PEER="$(contract hp CONTRACT_STATE_PROPOSED false false "$(grant "$SVC" "$VREEMDE" "$CONS")")"
assert_beoordeling "dienst van een andere peer: niet tekenen" "$(bundel "$ANDERE_PEER")" TEKEN ""
assert_weigerreden "en de reden noemt de vreemde peer" "$(bundel "$ANDERE_PEER")" "niet bij ons"

ANDERE_DIENST="$(contract hd CONTRACT_STATE_PROPOSED false false "$(grant "$SVC2" "$PROV" "$CONS")")"
assert_beoordeling "dienst die wij niet aanbieden: niet tekenen" "$(bundel "$ANDERE_DIENST")" TEKEN ""
assert_weigerreden "en de reden noemt de dienst" "$(bundel "$ANDERE_DIENST")" "bieden wij niet aan"

VREEMDE_CONS="$(contract hc CONTRACT_STATE_PROPOSED false false "$(grant "$SVC" "$PROV" "$VREEMDE")")"
assert_beoordeling "onbekende consumer: niet tekenen" "$(bundel "$VREEMDE_CONS")" TEKEN ""
assert_weigerreden "en de reden noemt de consumer" "$(bundel "$VREEMDE_CONS")" "staat niet op de lijst"

echo
echo "== fsc_contract_beoordeling: allowlists met meer dan één waarde =="

# Een allowlist van één verbergt het verschil tussen "geeft de enige terug" en "kiest per sleutel".
BREED_D="$(fsc_json_lijst "$SVC2" "$SVC")"
BREED_C="$(fsc_json_lijst "$CONS2" "$CONS")"

assert_beoordeling "tweede dienst uit een bredere lijst telt mee" \
  "$(bundel "$ANDERE_DIENST")" TEKEN "hd ${CONS}" "$BREED_D" "$CONSUMERS"
assert_beoordeling "tweede consumer uit een bredere lijst telt mee" \
  "$(bundel "$(contract hb CONTRACT_STATE_PROPOSED false false "$(grant "$SVC" "$PROV" "$CONS2")")")" \
  TEKEN "hb ${CONS2}" "$DIENSTEN" "$BREED_C"
assert_beoordeling "een waarde buiten de bredere lijst blijft geweigerd" \
  "$(bundel "$VREEMDE_CONS")" TEKEN "" "$BREED_D" "$BREED_C"

echo
echo "== fsc_contract_beoordeling: al getekend en ingetrokken =="

AL_GETEKEND="$(contract hq CONTRACT_STATE_VALID false true "$(grant "$SVC" "$PROV" "$CONS")")"
assert_beoordeling "al getekend contract komt niet opnieuw langs" "$(bundel "$AL_GETEKEND")" TEKEN ""
assert_beoordeling "maar is wel als GETEKEND zichtbaar" "$(bundel "$AL_GETEKEND")" GETEKEND "hq ${CONS}"

# Het servicePublication-contract voor onze eigen dienst staat op dezelfde manager en wordt door
# AUTO_SIGN_GRANTS vanzelf getekend. Zou dat elke ronde een weigering opleveren, dan stond de log vol
# met contracten waar niets mee mis is.
PUBLICATIE="$(contract hpub CONTRACT_STATE_VALID false true \
  "$(grant "$SVC" "$PROV" "$CONS" GRANT_TYPE_SERVICE_PUBLICATION)")"
assert_beoordeling "een getekend contract dat de toets niet haalt zwijgt" "$(bundel "$PUBLICATIE")" WEIGER ""
assert_beoordeling "en verschijnt ook niet als GETEKEND" "$(bundel "$PUBLICATIE")" GETEKEND ""

INGETROKKEN="$(contract hr CONTRACT_STATE_PROPOSED true false "$(grant "$SVC" "$PROV" "$CONS")")"
assert_beoordeling "ingetrokken contract wordt niet getekend" "$(bundel "$INGETROKKEN")" TEKEN ""
assert_beoordeling "en levert geen weigering op" "$(bundel "$INGETROKKEN")" WEIGER ""

echo
echo "== fsc_contract_beoordeling: gemengde lijst =="

GEMENGD="$(bundel "$GOED" "$SMOKKEL" "$AL_GETEKEND" "$INGETROKKEN" "$VREEMDE_CONS")"
assert_beoordeling "uit een gemengde lijst alleen het juiste contract" "$GEMENGD" TEKEN "h1 ${CONS}"
assert_beoordeling "de getekende komt als GETEKEND terug" "$GEMENGD" GETEKEND "hq ${CONS}"
assert_gelijk "twee weigeringen met hun eigen grond" "hs
hc" \
  "$(fsc_contract_regels "$(fsc_contract_beoordeling "$GEMENGD" "$PROV" "$DIENSTEN" "$CONSUMERS")" WEIGER \
     | cut -d' ' -f1)"

echo
echo "== fsc_contract_voor_combinatie =="

assert_combinatie() {
  local desc="$1" json="$2" verwacht="$3"
  assert_gelijk "$desc" "$verwacht" "$(fsc_contract_voor_combinatie "$json" "$SVC" "$PROV" "$CONS" "$THUMB")"
}

assert_combinatie "lege lijst levert niets op" '{"contracts":[]}' ""
assert_combinatie "ingediend maar niet getekend telt mee als uitstaand" \
  "$(bundel "$GOED")" "h1 contract_state_proposed nee"
assert_combinatie "getekend en geldig komt met beide kenmerken terug" \
  "$(bundel "$AL_GETEKEND")" "hq contract_state_valid ja"

# Zonder deze regel zou de consumer een contract van een andere outway voor "het onze" aanzien en
# nooit meer indienen, terwijl zíjn outway de grant nooit krijgt.
ANDERE_THUMB_C="$(contract hz CONTRACT_STATE_VALID false true "$(
  jq -nc --arg svc "$SVC" --arg sp "$PROV" --arg cp "$CONS" '
    { type: "GRANT_TYPE_SERVICE_CONNECTION",
      service: { name: $svc, peer_id: $sp },
      outway: { peer_id: $cp, identification: { public_key_thumbprint: "ffff" } } }')")"
assert_combinatie "een andere outway-thumbprint is een ander contract" "$(bundel "$ANDERE_THUMB_C")" ""

assert_combinatie "een contract met twee grants hoort niet bij deze combinatie" "$(bundel "$SMOKKEL")" ""
assert_combinatie "ingetrokken telt niet mee" "$(bundel "$INGETROKKEN")" ""
assert_combinatie "twee uitstaande contracten komen allebei terug" \
  "$(bundel "$GOED" "$GOED2")" "h1 contract_state_proposed nee
h2 contract_state_proposed nee"

echo
echo "== de helften kennen elkaars manager niet =="

# Statisch, en daarom hier en niet in de smoke: dit is de eigenschap waar de hele ZAD-opzet op
# rust, en hij moet ook toetsbaar zijn zonder draaiende federatie. Een half dat de adres- of
# certificaat-variabelen van de overkant leest, werkt lokaal prima en faalt pas op ZAD — precies de
# terugkoppeling die we niet willen.
#
# De OIN's staan er bewust niet bij: dat zijn publieke organisatienummers, geen adressen, en beide
# helften hebben ze nodig om te weten waar het contract over gaat.
CONTRACTS_DIR="$(cd "$HERE/../federatie/contracts" && pwd)"

assert_geen_verwijzing() {
  local desc="$1" bestand="$2" patroon="$3" treffers
  treffers="$(grep -nE "$patroon" "$bestand" || true)"

  if [ -z "$treffers" ]; then
    echo "OK: $desc"
  else
    echo "FAIL: $desc — gevonden in $(basename "$bestand"):" >&2
    printf '%s\n' "$treffers" | sed 's/^/  /' >&2
    fails=$((fails + 1))
  fi
}

assert_geen_verwijzing "de consumer-helft noemt de provider-manager nergens" \
  "${CONTRACTS_DIR}/bootstrap-consumer.sh" 'FSC_PROVIDER_(MANAGER|CERT|KEY|CA|ADRES)'
assert_geen_verwijzing "de provider-helft noemt de consumer-manager nergens" \
  "${CONTRACTS_DIR}/bootstrap-provider.sh" 'FSC_CONSUMER_(MANAGER|CERT|KEY|CA|ADRES)'

echo
if [ "$fails" -ne 0 ]; then
  echo "ROOD: ${fails} test(s) gefaald." >&2
  exit 1
fi

echo "GROEN: alle fsc-contract-tests geslaagd."
