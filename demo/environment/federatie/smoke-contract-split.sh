#!/usr/bin/env bash
# Smoke: bewijst dat de twee helften van de contract-bootstrap los van elkaar werken en samen
# convergeren. Draai na `./federatie.sh up` en nadat elk magazijn zijn dienst gepubliceerd heeft.
#
# Waarom naast smoke-contract.sh. Die toetst de UITKOMST: er ligt een geldig contract en het
# data-pad werkt. Deze toetst de WEG ernaartoe, en dat is precies wat op ZAD anders is — daar
# draaien de helften in verschillende deployments die elkaars manager niet kunnen bereiken. Zonder
# deze smoke zou die opzet alleen op ZAD zelf blijken te werken of niet.
#
#   1. de consumer-helft dient in en meldt dat hij wacht (exit 3), zónder de provider aan te raken;
#   2. de provider-helft tekent wat er ligt, zónder de consumer aan te raken;
#   3. de consumer-helft ziet het contract daarna geldig worden (exit 0);
#   4. een tweede ronde levert geen tweede contract op en niets meer te tekenen.
#
# Linux + podman: gebruikt `podman`, `curl` en `jq`.
set -euo pipefail

HERE="$(cd "$(dirname "$0")" && pwd)"
ENVDIR="$(cd "${HERE}/.." && pwd)"

# shellcheck source=../lib/fsc-harness.sh
. "${ENVDIR}/lib/fsc-harness.sh"
# shellcheck source=../lib/fsc-contract.sh
. "${ENVDIR}/lib/fsc-contract.sh"
# shellcheck source=peers.env
. "${HERE}/peers.env"

fsc_errlog_init
fsc_have_jq

command -v curl >/dev/null 2>&1 || { echo "FAIL: 'curl' is vereist." >&2; exit 1; }
[ "$HAVE_JQ" -eq 1 ] || { echo "FAIL: 'jq' is vereist — elke assert hieronder leest contract-JSON." >&2; exit 1; }

FOUTEN=0
fout() { echo "FAIL: $*" >&2; FOUTEN=$((FOUTEN + 1)); }
ok()   { echo "OK: $*"; }

: "${UITVRAAG:?geen UITVRAAG in peers.env}"
: "${MAGAZIJNEN:?geen MAGAZIJNEN in peers.env}"
: "${MAGAZIJN_DIENST:?geen MAGAZIJN_DIENST in peers.env}"

CONS_NET="$(fsc_peer_waarde NET "$UITVRAAG")"
CONS_OIN="$(fsc_peer_waarde OIN "$UITVRAAG")"
[ -n "$CONS_NET" ] && [ -n "$CONS_OIN" ] || { echo "FAIL: NET_/OIN_ ontbreekt voor '${UITVRAAG}'." >&2; exit 1; }

CONS_OUTWAY_CERT="${ENVDIR}/${UITVRAAG}/pki/out/${UITVRAAG}/outway/cert.pem"
CONS_THUMB="$(fsc_outway_thumbprint "$CONS_OUTWAY_CERT")" \
  || { echo "FAIL: kon de outway-thumbprint niet berekenen uit ${CONS_OUTWAY_CERT}: $(fsc_last_error)" >&2; exit 1; }

CONTRACTS="${ENVDIR}/federatie/contracts"

# consumer_helft <provider-oin>: de consumer-helft met UITSLUITEND zijn eigen adres en certificaten.
#
# `env -i` en niet alleen "de andere variabelen weglaten": deze smoke moet kunnen aantonen dat de
# helft het zonder de overkant redt, en dat bewijst hij alleen als die overkant er echt niet is. Een
# geërfde FSC_PROVIDER_MANAGER uit de shell van de aanroeper zou dat stil ondergraven.
consumer_helft() {
  env -i PATH="$PATH" HOME="$HOME" \
    FSC_CONSUMER_OIN="$CONS_OIN" \
    FSC_PROVIDER_OIN="$1" \
    FSC_SERVICE_NAME="$MAGAZIJN_DIENST" \
    FSC_OUTWAY_CERT="$CONS_OUTWAY_CERT" \
    FSC_CONSUMER_MANAGER="https://manager.${UITVRAAG}.fsc-test.local:9443" \
    FSC_CONSUMER_ADRES="$(fsc_component_adres "$CONS_NET" manager)" \
    FSC_CONSUMER_CERT="${ENVDIR}/${UITVRAAG}/pki/internal/${UITVRAAG}/manager/cert.pem" \
    FSC_CONSUMER_KEY="${ENVDIR}/${UITVRAAG}/pki/internal/${UITVRAAG}/manager/key.pem" \
    FSC_CONSUMER_CA="${ENVDIR}/${UITVRAAG}/pki/internal/${UITVRAAG}/ca/root.pem" \
    "${CONTRACTS}/bootstrap-consumer.sh"
}

# provider_helft <magazijn> <net> <oin>: idem voor de provider-kant.
provider_helft() {
  env -i PATH="$PATH" HOME="$HOME" \
    FSC_PROVIDER_OIN="$3" \
    FSC_DIENSTEN="$MAGAZIJN_DIENST" \
    FSC_CONSUMERS="$CONS_OIN" \
    FSC_PROVIDER_MANAGER="https://manager.${1}.fsc-test.local:9443" \
    FSC_PROVIDER_ADRES="$(fsc_component_adres "$2" manager)" \
    FSC_PROVIDER_CERT="${ENVDIR}/${1}/pki/internal/${1}/manager/cert.pem" \
    FSC_PROVIDER_KEY="${ENVDIR}/${1}/pki/internal/${1}/manager/key.pem" \
    FSC_PROVIDER_CA="${ENVDIR}/${1}/pki/internal/${1}/ca/root.pem" \
    "${CONTRACTS}/bootstrap-provider.sh"
}

# geldige_contracten <peer> <net> <provider-oin>: hashes van geldige contracten voor deze combinatie.
geldige_contracten() {
  local json
  json="$(fsc_manager_contracts "$ENVDIR" "$1" "$(fsc_component_adres "$2" manager)")" || return 1
  fsc_grant_actief "$json" "$MAGAZIJN_DIENST" "$3" "$CONS_OIN" "$CONS_THUMB" | sort
}

# aantal_regels <tekst>: 0 voor leeg, anders het aantal niet-lege regels.
aantal_regels() { printf '%s' "${1:-}" | grep -c . || true; }

# tel_geldige <peer> <net> <provider-oin>: aantal geldige contracten op stdout, non-zero bij een
# leesfout.
#
# Zonder deze tak zou een mislukte managerbevraging als "0 contracten" doorgaan. Twee mislukte
# metingen op rij zijn dan gelijk aan elkaar, en de vergelijking "aantal ongewijzigd" wordt groen
# op grond van twee fouten.
#
# De functie meldt zelf NIET: hij wordt in een commando-substitutie aangeroepen, dus een `fout`
# hier zou `FOUTEN` in een subshell ophogen en bij terugkeer verdwenen zijn — waarna de smoke
# alsnog groen eindigt. De aanroeper doet de melding.
tel_geldige() {
  local regels
  regels="$(geldige_contracten "$1" "$2" "$3")" || return 1

  aantal_regels "$regels"
}

# meet <naam> <peer> <net> <provider-oin>: tel_geldige met de melding op de juiste plek.
meet() {
  local naam="$1"; shift

  tel_geldige "$@" || {
    fout "kon de contracten van ${1} niet ophalen (${naam}): $(fsc_last_error)"
    return 1
  }
}

for magazijn in $MAGAZIJNEN; do
  PROV_NET="$(fsc_peer_waarde NET "$magazijn")"
  PROV_OIN="$(fsc_peer_waarde OIN "$magazijn")"
  [ -n "$PROV_NET" ] && [ -n "$PROV_OIN" ] || { fout "NET_/OIN_ ontbreekt voor '${magazijn}'"; continue; }

  echo "===== ${UITVRAAG} -> ${magazijn} ====="

  VOORAF="$(meet vooraf "$UITVRAAG" "$CONS_NET" "$PROV_OIN")" || { fout "meting vooraf mislukt"; continue; }

  # --- 1. Consumer dient in, alleen bij zijn eigen manager ---------------------------------------
  echo "== 1. consumer-helft =="
  rc=0
  consumer_helft "$PROV_OIN" || rc=$?

  case "$rc" in
    0) ok "consumer: contract was al geldig (${VOORAF} contract(en) vooraf)" ;;
    3) ok "consumer: ingediend en wachtend op de provider" ;;
    *) fout "consumer-helft brak af met exit ${rc}"; continue ;;
  esac

  # --- 2. Provider tekent, alleen bij zijn eigen manager -----------------------------------------
  echo "== 2. provider-helft =="

  # De helft draait onder `env -i` met uitsluitend zijn eigen adres en certificaten, dus dat hij
  # hier klaarkomt ís het bewijs dat hij de overkant niet nodig heeft.
  rc=0
  provider_helft "$magazijn" "$PROV_NET" "$PROV_OIN" || rc=$?

  case "$rc" in
    0|3) ok "provider: klaar met alleen zijn eigen manager in de omgeving" ;;
    # 4 = er lag iets dat de autorisatietoets niet haalde. Geen crash: stap 3 stelt vast of ons
    # eigen contract er wél doorheen kwam, en dat is wat deze smoke meet.
    4) ok "provider: klaar, met een of meer contracten buiten de allowlist" ;;
    *) fout "provider-helft brak af met exit ${rc}"; continue ;;
  esac

  # --- 3. Consumer ziet het contract geldig worden -----------------------------------------------
  echo "== 3. consumer-helft opnieuw =="
  rc=0
  POGING=0

  # De accept-handtekening reist via de mesh terug; dat duurt even. Zelfde grens als de orkestrator.
  while :; do
    rc=0
    consumer_helft "$PROV_OIN" || rc=$?
    [ "$rc" -eq 3 ] || break
    [ "$POGING" -lt "${SPLIT_POGINGEN:-10}" ] || break

    POGING=$((POGING + 1))
    sleep "${SPLIT_WACHT:-2}"
  done

  if [ "$rc" -eq 0 ]; then
    ok "consumer: contract is geldig geworden na de provider-helft"
  else
    fout "consumer zag het contract niet geldig worden (exit ${rc})"
    continue
  fi

  # --- 4. Tweede ronde: niets erbij --------------------------------------------------------------
  echo "== 4. tweede ronde =="
  NA_EEN="$(meet na-ronde-1 "$UITVRAAG" "$CONS_NET" "$PROV_OIN")" || { fout "meting na ronde 1 mislukt"; continue; }

  rc=0
  consumer_helft "$PROV_OIN" || rc=$?
  [ "$rc" -eq 0 ] && ok "consumer: herhaalde run is een no-op" \
    || fout "consumer: herhaalde run gaf exit ${rc} in plaats van 0"

  PROV_UIT="$(provider_helft "$magazijn" "$PROV_NET" "$PROV_OIN" 2>&1)" || {
    fout "provider: herhaalde run brak af — $(printf '%s' "$PROV_UIT" | tail -n1)"
    continue
  }

  case "$PROV_UIT" in
    *"niets te tekenen"*) ok "provider: herhaalde run heeft niets meer te tekenen" ;;
    *) fout "provider: herhaalde run tekende opnieuw — $(printf '%s' "$PROV_UIT" | tail -n1)" ;;
  esac

  NA_TWEE="$(meet na-ronde-2 "$UITVRAAG" "$CONS_NET" "$PROV_OIN")" || { fout "meting na ronde 2 mislukt"; continue; }

  if [ "$NA_TWEE" -eq "$NA_EEN" ]; then
    ok "aantal geldige contracten ongewijzigd na de tweede ronde (${NA_TWEE})"
  else
    fout "tweede ronde veranderde het aantal geldige contracten: ${NA_EEN} -> ${NA_TWEE}"
  fi

  # Eén contract is de bedoeling. Meer betekent dat de idempotentie lekt; de opruiming in de
  # consumer-helft hoort dat in de volgende ronde recht te trekken, maar dan is er iets te melden.
  if [ "$NA_TWEE" -eq 1 ]; then
    ok "precies één geldig contract voor ${MAGAZIJN_DIENST}"
  else
    fout "verwachtte precies één geldig contract, vond ${NA_TWEE}"
  fi

  echo
done

if [ "$FOUTEN" -ne 0 ]; then
  echo "SMOKE-CONTRACT-SPLIT ROOD: ${FOUTEN} bevinding(en)." >&2
  exit 1
fi

echo "SMOKE-CONTRACT-SPLIT OK."
