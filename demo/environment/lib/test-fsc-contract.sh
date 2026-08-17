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

fsc_errlog_init
fsc_have_jq
[ "$HAVE_JQ" -eq 1 ] || { echo "FAIL: jq is vereist voor deze fixture-tests." >&2; exit 1; }

fails=0

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
GROEP=moza-fbs-test
MAXGELDIG=316224000
# Vaste "nu": de geldigheidstoets meet vanaf het heden, dus zonder een gepinde klok zou de suite
# afhangen van wanneer hij draait.
NU=1000000000

# --- fixtures ------------------------------------------------------------------------------------

# grant <service> <service-peer> <consumer-peer> [grant-type] [identificatie-type]: één grant-object,
# in de vorm die de consumer-helft daadwerkelijk indient.
grant() {
  jq -nc --arg svc "$1" --arg sp "$2" --arg cp "$3" --arg t "${4:-GRANT_TYPE_SERVICE_CONNECTION}" \
         --arg it "${5:-OUTWAY_IDENTIFICATION_TYPE_PUBLIC_KEY_THUMBPRINT}" --arg thumb "$THUMB" '
    { type: $t,
      service: { type: "SERVICE_TYPE_SERVICE", name: $svc, peer_id: $sp },
      outway: { peer_id: $cp, identification: { type: $it, public_key_thumbprint: $thumb } } }'
}

# contract <hash> <state> <has_revoked> <getekend-door-provider> <grant-json...>
#
# `signatures.accept` draagt ALTIJD de consumer, want zo ziet een echt ingediend contract eruit: de
# manager van de consumer tekent server-side bij de POST. Een fixture met een lege `accept` zou het
# verschil verbergen tussen "heeft ONZE handtekening" en "heeft een handtekening" — precies de
# vergissing die de provider elk contract als al-getekend zou laten zien, waardoor hij nooit tekent.
#
# Ook group_id, hash_algorithm en validity horen erbij: die wegen sinds de uitbreiding mee in de
# toets, en een fixture zonder die velden zou een andere weiger-grond raken dan de test bedoelt.
contract() {
  local h="$1" st="$2" rv="$3" ondertekend="$4"; shift 4
  printf '%s\n' "$@" | jq -sc --arg h "$h" --arg st "$st" --argjson rv "$rv" \
        --argjson sig "$ondertekend" --arg prov "$PROV" --arg cons "$CONS" --arg groep "$GROEP" \
        --argjson nu "$NU" '
    { hash: $h, state: $st, has_revoked: $rv,
      signatures: { accept: ({($cons): true} + (if $sig then {($prov): true} else {} end)) },
      content: { group_id: $groep, hash_algorithm: "HASH_ALGORITHM_SHA3_512",
                 validity: { not_before: ($nu - 60), not_after: ($nu + 315360000) },
                 grants: . } }'
}

# contract_ruw <json-patch>: een contract met een afwijkende content, voor de eisen buiten de grant.
contract_ruw() {
  contract hx CONTRACT_STATE_PROPOSED false false "$(grant "$SVC" "$PROV" "$CONS")" \
    | jq -c "$1"
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

beoordeel() {
  fsc_contract_beoordeling "$1" "$PROV" "${2:-$DIENSTEN}" "${3:-$CONSUMERS}" "$GROEP" "$MAXGELDIG" "$NU"
}

# assert_beoordeling <desc> <json> <soort> <verwacht> [diensten-json] [consumers-json]
assert_beoordeling() {
  local desc="$1" json="$2" soort="$3" verwacht="$4" d="${5:-$DIENSTEN}" c="${6:-$CONSUMERS}"
  assert_gelijk "$desc" "$verwacht" "$(fsc_contract_regels "$(beoordeel "$json" "$d" "$c")" "$soort")"
}

# assert_weiger_regel <desc> <json> <verwachte volledige regel>: strikter dan op een fragment
# matchen, want zo is "juiste reden, verkeerd contract" wél te onderscheiden.
assert_weiger_regel() {
  assert_gelijk "$1" "$3" "$(fsc_contract_regels "$(beoordeel "$2")" WEIGER)"
}

# assert_weigerreden <desc> <json> <fragment>: de weigering noemt deze grond.
assert_weigerreden() {
  local desc="$1" json="$2" fragment="$3" reden
  reden="$(fsc_contract_regels "$(beoordeel "$json")" WEIGER)"

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

# Een gedelegeerde dienst zet een delegator-claim in het token en verandert welke handtekeningen
# vereist zijn. Wij bieden die niet aan.
assert_weiger_regel "gedelegeerde dienst: niet tekenen" \
  "$(bundel "$(contract_ruw '.content.grants[0].service.type = "SERVICE_TYPE_DELEGATED_SERVICE"')")" \
  "hx dienst-type SERVICE_TYPE_DELEGATED_SERVICE is geen gewone dienst"
# De discriminator is verplicht in de spec; een ontbrekende waarde als "gewone dienst" lezen zou
# precies de blinde ondertekening zijn die deze check moet voorkomen.
assert_weiger_regel "ontbrekend dienst-type: niet tekenen" \
  "$(bundel "$(contract_ruw 'del(.content.grants[0].service.type)')")" \
  "hx dienst-type ontbreekt is geen gewone dienst"

# `properties` schrijft claims die onze eigen manager in het token zet en die onze inway en de dienst
# erachter te zien krijgen — door de tegenpartij opgesteld. Blind mee-ondertekenen is dezelfde fout
# als een tweede grant mee-ondertekenen.
assert_weiger_regel "grant met properties: niet tekenen" \
  "$(bundel "$(contract_ruw '.content.grants[0].properties = {"rol": "beheerder"}')")" \
  "hx draagt grant-properties die wij niet ondertekenen"
assert_beoordeling "een leeg properties-object is onschadelijk" \
  "$(bundel "$(contract_ruw '.content.grants[0].properties = {}')")" TEKEN "hx ${CONS}"

# Een contract dat wij eerder afwezen draagt onze accept-handtekening niet, dus zonder filter komt
# het elke ronde terug als kandidaat en tekenen we alsnog wat we bewust weigerden.
assert_beoordeling "een eerder afgewezen contract komt niet terug" \
  "$(bundel "$(contract_ruw '.has_rejected = true')")" TEKEN ""
assert_beoordeling "en levert ook geen weigering op" \
  "$(bundel "$(contract_ruw '.has_rejected = true')")" WEIGER ""

echo
echo "== fsc_contract_beoordeling: allowlists met meer dan één waarde =="

# Een allowlist van één verbergt het verschil tussen "geeft de enige terug" en "kiest per sleutel".
BREED_D="$(fsc_json_lijst "$SVC2" "$SVC")"
BREED_C="$(fsc_json_lijst "$CONS2" "$CONS")"

assert_beoordeling "tweede dienst uit een bredere lijst telt mee" \
  "$(bundel "$ANDERE_DIENST")" TEKEN "hd ${CONS}" "$BREED_D" "$CONSUMERS"
# Let op de handtekening: een contract van CONS2 draagt de accept van CONS2, niet die van CONS.
assert_beoordeling "tweede consumer uit een bredere lijst telt mee" \
  "$(bundel "$(contract hb CONTRACT_STATE_PROPOSED false false "$(grant "$SVC" "$PROV" "$CONS2")" \
      | jq -c --arg c "$CONS2" '.signatures.accept = {($c): true}')")" \
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
  "$(bundel "$GOED")" "h1 contract_state_proposed nee -"
assert_combinatie "getekend en geldig komt met beide kenmerken terug" \
  "$(bundel "$AL_GETEKEND")" "hq contract_state_valid ja -"

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

# Anders dan bij de beoordeling blijft een AFGEWEZEN contract hier zichtbaar. Zou het wegvallen, dan
# ziet de consumer zijn eigen aanvraag niet meer en dient hij elke ronde een nieuwe in, terwijl de
# afwijzing nergens blijkt.
assert_combinatie "een afgewezen contract blijft zichtbaar, met vlag" \
  "$(bundel "$(contract hr2 CONTRACT_STATE_REJECTED false false "$(grant "$SVC" "$PROV" "$CONS")" \
      | jq -c '.has_rejected = true')")" \
  "hr2 contract_state_rejected nee afgewezen"
# Ook hier mag één misvormde rij de rest niet meenemen: de consumer zou zijn eigen contract dan niet
# meer zien en elke ronde een nieuw indienen.
#
# De exit-status hoort er expliciet bij. jq streamt, dus de goede regel is al uitgestuurd vóór de
# fout op de rotte rij — alleen op de uitvoer toetsen zou dus slagen terwijl de aanroeper
# (`eigen_contracten`, onder `pipefail`) de run juist als mislukt afbreekt.
for rot in '{"hash":"rot","content":"x"}' \
           '{"hash":"rot","content":{"grants":[42]}}' \
           '{"hash":"rot","content":{"grants":42}}'; do
  gemengd="$(printf '%s\n' "$GOED" "$rot" | jq -sc '{contracts: .}')"
  uitkomst="$(fsc_contract_voor_combinatie "$gemengd" "$SVC" "$PROV" "$CONS" "$THUMB")" && rc=0 || rc=$?

  assert_gelijk "naast $(printf '%s' "$rot" | cut -c1-34)… blijft het uitstaande contract zichtbaar" \
    "h1 contract_state_proposed nee -" "$uitkomst"
  assert_gelijk "  en de matcher zelf slaagt (exit 0)" "0" "$rc"
done

assert_combinatie "twee uitstaande contracten komen allebei terug" \
  "$(bundel "$GOED" "$GOED2")" "h1 contract_state_proposed nee -
h2 contract_state_proposed nee -"

echo
echo "== fsc_contract_beoordeling: de eisen buiten de grant =="

# Zonder deze vier bepaalt de tegenpartij in zijn eentje hoe lang en onder welke voorwaarden het
# contract geldt; de allowlist zegt daar niets over.
assert_weiger_regel "vreemde group_id: niet tekenen" \
  "$(bundel "$(contract_ruw '.content.group_id = "andere-groep"')")" \
  "hx hoort bij group andere-groep, niet bij de onze"
assert_weiger_regel "afwijkend hash-algoritme: niet tekenen" \
  "$(bundel "$(contract_ruw '.content.hash_algorithm = "HASH_ALGORITHM_SHA1"')")" \
  "hx hash-algoritme HASH_ALGORITHM_SHA1 wijkt af"
assert_weiger_regel "geen geldigheidsduur: niet tekenen" \
  "$(bundel "$(contract_ruw 'del(.content.validity)')")" \
  "hx draagt geen geldigheidsduur"
# Precies op de grens hoort het nog wél te mogen: een off-by-one hier zou de normale
# tienjaars-aanvraag van de consumer-helft weigeren.
assert_beoordeling "looptijd precies op het maximum mag" \
  "$(bundel "$(contract_ruw ".content.validity.not_after = ${NU} + ${MAXGELDIG}")")" TEKEN "hx ${CONS}"

# Gemeten vanaf nu, niet als vensterlengte: een venster van tien jaar dat pas over jaren begint, is
# een claim die veel verder reikt dan de grens die de lengte suggereert.
assert_weiger_regel "venster dat ver in de toekomst begint: niet tekenen" \
  "$(bundel "$(contract_ruw ".content.validity = {not_before: (${NU} + 200000000), not_after: (${NU} + 200000000 + 315360000)}")")" \
  "hx loopt nog 515360000s, meer dan het maximum van ${MAXGELDIG}s"

assert_weiger_regel "al verlopen contract: niet tekenen" \
  "$(bundel "$(contract_ruw ".content.validity.not_after = ${NU} - 1")")" \
  "hx is al verlopen"

assert_weiger_regel "einddatum vóór begindatum: niet tekenen" \
  "$(bundel "$(contract_ruw ".content.validity = {not_before: (${NU} + 100), not_after: (${NU} + 50)}")")" \
  "hx einddatum ligt niet ná de begindatum"

assert_weiger_regel "lege validity: niet tekenen" \
  "$(bundel "$(contract_ruw '.content.validity = {}')")" \
  "hx geldigheidsduur is onvolledig (not_before/not_after ontbreekt of is geen getal)"

assert_weiger_regel "validity zonder not_after: niet tekenen" \
  "$(bundel "$(contract_ruw 'del(.content.validity.not_after)')")" \
  "hx geldigheidsduur is onvolledig (not_before/not_after ontbreekt of is geen getal)"

# fsc-core wil onder een serviceConnection de handtekening van beide kanten; tekenen vóór de
# tegenpartij dat deed, is een verplichting aangaan die de ander nog niet is aangegaan.
assert_weiger_regel "consumer heeft nog niet getekend: wij ook niet" \
  "$(bundel "$(contract_ruw '.signatures.accept = {}')")" \
  "hx draagt de handtekening van de consumer nog niet"

# DOMAIN_NAME is een zwakkere binding dan een thumbprint; die hoort een provider niet te tekenen.
assert_weiger_regel "andere outway-identificatie: niet tekenen" \
  "$(bundel "$(contract hi CONTRACT_STATE_PROPOSED false false \
      "$(grant "$SVC" "$PROV" "$CONS" GRANT_TYPE_SERVICE_CONNECTION OUTWAY_IDENTIFICATION_TYPE_DOMAIN_NAME)")")" \
  "hi outway-identificatie OUTWAY_IDENTIFICATION_TYPE_DOMAIN_NAME is geen thumbprint"

echo
echo "== fsc_contract_beoordeling: volgorde en samenloop =="

# De gesmokkelde grant vooraan: een implementatie die "de eerste grant" toetst in plaats van "de
# enige" zou hier iets anders beslissen dan bij de omgekeerde volgorde.
OMGEKEERD="$(contract ho CONTRACT_STATE_PROPOSED false false \
  "$(grant "$SVC2" "$PROV" "$VREEMDE")" "$(grant "$SVC" "$PROV" "$CONS")")"
assert_weiger_regel "gesmokkelde grant vooraan geeft dezelfde uitkomst" \
  "$(bundel "$OMGEKEERD")" "ho draagt 2 grants in plaats van precies 1"

assert_weiger_regel "nul grants noemt het aantal" \
  "$(bundel "$GEEN_GRANTS")" "hg draagt 0 grants in plaats van precies 1"

# Twee eisen tegelijk geschonden: de doc belooft dat de éérste faalende eis genoemd wordt.
TWEE_FOUT="$(contract hw CONTRACT_STATE_PROPOSED false false \
  "$(grant "$SVC2" "$PROV" "$VREEMDE")" "$(grant "$SVC2" "$PROV" "$VREEMDE")")"
assert_weiger_regel "bij twee schendingen wint de eerste eis" \
  "$(bundel "$TWEE_FOUT")" "hw draagt 2 grants in plaats van precies 1"

echo
echo "== fsc_contract_beoordeling: tegenpartij-data blijft data =="

# Een peer die een newline in zijn dienstnaam zet, zou zonder sanitatie een tweede regel in de
# stroom schrijven die de aanroeper als eigen record leest — en zo de hele allowlist omzeilen.
INJECTIE="$(contract hj CONTRACT_STATE_PROPOSED false false "$(
  jq -nc --arg p "$PROV" --arg c "$CONS" --arg thumb "$THUMB" '
    { type: "GRANT_TYPE_SERVICE_CONNECTION",
      service: { name: "nep\nTEKEN GEKAAPT 00000000000000001000", peer_id: $p },
      outway: { peer_id: $c, identification: { type: "OUTWAY_IDENTIFICATION_TYPE_PUBLIC_KEY_THUMBPRINT", public_key_thumbprint: $thumb } } }')")"
assert_beoordeling "een newline in de dienstnaam levert geen TEKEN-regel op" \
  "$(bundel "$INJECTIE")" TEKEN ""
assert_gelijk "en de weigering blijft één regel" "1" \
  "$(fsc_contract_regels "$(beoordeel "$(bundel "$INJECTIE")")" WEIGER | grep -c .)"

echo
echo "== de contractenlijst moet een lijst zijn =="

# Een 200 met een andere vorm mag niet als "geen contracten" doorgaan: aan consumer-kant zou dat
# elke ronde een nieuw contract opleveren.
for vorm in '' ' ' '{}' '{"contracts":null}' '{"contracts":"x"}' '[]' '<html>502</html>'; do
  if beoordeel "$vorm" >/dev/null 2>&1; then
    echo "FAIL: respons '${vorm:-<leeg>}' werd stil als lege lijst gelezen" >&2
    fails=$((fails + 1))
  else
    echo "OK: respons '${vorm:-<leeg>}' wordt afgewezen"
  fi
done

assert_beoordeling "een echte lege lijst is wél geldig" '{"contracts":[]}' TEKEN ""

# Een cursor is géén reden om af te wijzen. De manager zet 'm op elke pagina die rijen bevat, ook
# als die pagina de hele lijst is; erop afbreken zou de bootstrap laten stuklopen zodra er één
# contract bestaat. Het doorlezen zit in fsc_contracten_paginas, niet in de beoordeling.
assert_beoordeling "een lijst mét cursor wordt gewoon beoordeeld" \
  '{"contracts":[],"pagination":{"next_cursor":"abc"}}' TEKEN ""

echo
echo "== één misvormde rij mag de rest niet meenemen =="

# Zonder afvangen per contract sloopt één rij met een afwijkend type het hele jq-programma, en dan
# wordt in die ronde geen enkel legitiem contract meer getekend.
for rot in '{"hash":"rot","content":"x"}' \
           '{"hash":"rot","content":{"validity":"morgen"}}' \
           '{"hash":"rot","content":{"grants":"a"}}' \
           '{"hash":"rot","signatures":"x"}'; do
  gemengd="$(printf '%s\n' "$GOED" "$rot" | jq -sc '{ contracts: . }')"
  assert_gelijk "naast $(printf '%s' "$rot" | cut -c1-38)… blijft het goede contract over" \
    "h1 ${CONS}" "$(fsc_contract_regels "$(beoordeel "$gemengd")" TEKEN)"
done

echo
echo "== fsc_hex64 =="

GELDIGE_THUMB="$(printf 'a%.0s' $(seq 1 64))"
assert_hex() {
  local desc="$1" waarde="$2" verwacht="$3" kregen=nee
  fsc_hex64 "$waarde" && kregen=ja
  assert_gelijk "$desc" "$verwacht" "$kregen"
}

assert_hex "64 lowercase hex is geldig" "$GELDIGE_THUMB" ja
assert_hex "leeg is ongeldig" "" nee
assert_hex "63 tekens is ongeldig" "${GELDIGE_THUMB%?}" nee
assert_hex "65 tekens is ongeldig" "${GELDIGE_THUMB}a" nee
assert_hex "hoofdletters zijn ongeldig" "$(printf 'A%.0s' $(seq 1 64))" nee
# De oude glob toetste alleen teken 1; deze twee kwamen er toen doorheen.
assert_hex "niet-hex ná het eerste teken is ongeldig" "a$(printf 'z%.0s' $(seq 1 63))" nee
assert_hex "een aanhalingsteken erin is ongeldig" "a\",\"x\":\"$(printf 'a%.0s' $(seq 1 56))" nee

echo
echo "== fsc_lijst_naar_json en fsc_json_lijst =="

assert_gelijk "één dienst" '["a"]' "$(fsc_lijst_naar_json T a | tr -d '\n')"
assert_gelijk "meerdere op spaties" '["a","b"]' "$(fsc_lijst_naar_json T "a  b" | tr -d '\n')"
# Een allowlist over meerdere regels: `read -r -a` zou hier alles na regel 1 laten vallen, en dat
# versmalt de autorisatiegrens zonder dat iemand het merkt.
assert_gelijk "meerdere over regels" '["a","b","c"]' "$(fsc_lijst_naar_json T "$(printf 'a\nb c')" | tr -d '\n')"
assert_gelijk "tabs tellen ook als scheiding" '["a","b"]' "$(fsc_lijst_naar_json T "$(printf 'a\tb')" | tr -d '\n')"

for leeg in "" " " "$(printf '\n\n')"; do
  if fsc_lijst_naar_json T "$leeg" >/dev/null 2>&1; then
    echo "FAIL: lege lijst '${leeg}' werd geaccepteerd" >&2
    fails=$((fails + 1))
  else
    echo "OK: lege lijst wordt afgewezen"
  fi
done

# De grens waar operator-env een jq-argument wordt.
assert_gelijk "aanhalingstekens worden ge-escaped" '["a\"b"]' "$(fsc_json_lijst 'a"b' | tr -d '\n')"
assert_gelijk "geen argumenten geeft een lege array" '[]' "$(fsc_json_lijst)"

echo
echo "== een rij die geen object is =="

# Zonder aparte typecheck laat élke veldtoets zo'n rij het hele programma afbreken, en dan wordt in
# die ronde geen enkel legitiem contract getekend.
for rot in '"oeps"' '42' '[]' 'null'; do
  gemengd="$(printf '%s\n' "$GOED" "$rot" | jq -sc '{contracts: .}')"
  uitkomst="$(beoordeel "$gemengd")" && rc=0 || rc=$?

  assert_gelijk "rij ${rot}: het goede contract blijft over" "h1 ${CONS}" \
    "$(fsc_contract_regels "$uitkomst" TEKEN)"
  assert_gelijk "  en de beoordeling zelf slaagt" "0" "$rc"
done

echo
echo "== eerder getekend, nu afgekeurd: niet stil =="

# Wordt een typefout in FSC_DIENSTEN gecorrigeerd ná de eerste tekenronde, of daalt de bovengrens,
# dan valt een contract dat wij al tekenden buiten de toets. Dat is geen besluit meer, maar wel iets
# waarvan de operator moet weten — anders meldt de provider "nog niets binnen" terwijl het er ligt.
GETEKEND_VERLOPEN="$(contract hv CONTRACT_STATE_VALID false true "$(grant "$SVC" "$PROV" "$CONS")" \
  | jq -c ".content.validity.not_after = ${NU} - 1")"
assert_beoordeling "een verlopen eigen contract levert een AANDACHT-regel op" \
  "$(bundel "$GETEKEND_VERLOPEN")" AANDACHT "hv is al verlopen"
assert_beoordeling "en geen WEIGER" "$(bundel "$GETEKEND_VERLOPEN")" WEIGER ""

# Het publicatiecontract voor onze eigen dienst draagt onze handtekening en haalt de toets ook niet;
# dát hoort wél stil te blijven, anders staat de log elke ronde vol.
assert_beoordeling "een getekend publicatiecontract blijft stil" "$(bundel "$PUBLICATIE")" AANDACHT ""

echo
echo "== fsc_getal_vereist =="

assert_getal() {
  local desc="$1" waarde="$2" verwacht="$3" kregen
  kregen="$(fsc_getal_vereist T "$waarde" 2>/dev/null)" || kregen="(afgewezen)"
  assert_gelijk "$desc" "$verwacht" "$kregen"
}

assert_getal "gewoon getal" 15 15
assert_getal "meercijferig" 3600 3600
# Nul zou de lus zonder pauze laten draaien — precies de hot loop waar de controle voor bestaat.
assert_getal "nul wordt afgewezen" 0 "(afgewezen)"
# Bash leest een voorloopnul als octaal; `$((x * 08))` breekt af midden in de lus.
assert_getal "voorloopnul wordt afgewezen" 08 "(afgewezen)"
assert_getal "eenheden erachter worden afgewezen" 15s "(afgewezen)"
assert_getal "negatief wordt afgewezen" -1 "(afgewezen)"
assert_getal "leeg wordt afgewezen" "" "(afgewezen)"

echo
echo "== fsc_hash_ok =="

assert_hash() {
  local desc="$1" waarde="$2" verwacht="$3" kregen=nee
  fsc_hash_ok "$waarde" && kregen=ja
  assert_gelijk "$desc" "$verwacht" "$kregen"
}

assert_hash "een echte FSC-hash" '$1$4$k4rwlWTsCM_j89Fc3nrbnQa9-KB43' ja
assert_hash "leeg" "" nee
# `.` en `..` bestaan uit toegestane tekens maar verleggen het pad: curl normaliseert ze weg.
assert_hash "een punt" "." nee
assert_hash "twee punten" ".." nee
assert_hash "een schuine streep" "a/b" nee
assert_hash "witruimte" "a b" nee
assert_hash "een newline" "$(printf 'a\nb')" nee

echo
echo "== fsc_contract_manager_ok =="

assert_manager() {
  local desc="$1" verwacht="$2"; shift 2
  local kregen=nee
  fsc_contract_manager_ok "$@" 2>/dev/null && kregen=ja
  assert_gelijk "$desc" "$verwacht" "$kregen"
}

assert_manager "https wordt geaccepteerd" ja https://manager:9443
assert_manager "http wordt geweigerd" nee http://manager:9443
assert_manager "leeg wordt geweigerd" nee ""
# De reden dat deze functie bestaat: curl leest een argument dat met `-` begint als optie, en
# `-K/pad` maakt er een lees-je-config-uit-dit-bestand van.
assert_manager "een curl-optie wordt geweigerd" nee -K/tmp/kwaadaardig
# Met twee argumenten: een implementatie die na de eerste stopt, zou de tweede missen.
assert_manager "een ongeldig tweede adres wordt ook geweigerd" nee https://manager:9443 http://andere:9443

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
