#!/usr/bin/env bash
# Smoke: bewijst dat de uitvraag daadwerkelijk bij een magazijn kan afnemen, en dat die toegang
# ook echt afgedwongen én verantwoord wordt. Draai na `./federatie.sh up` en
# `./contracts/fbs-contracten.sh`.
#
#   1. contract — wederzijds ondertekend en `CONTRACT_STATE_VALID` op BEIDE managers;
#   2. data-pad — outway -> router -> inway -> upstream levert 200 met de echo van dát magazijn,
#      niet die van de uitvraag-peer zelf;
#   3. afdwinging — een onbekende grant-hash op de outway en een call rechtstreeks naar de inway
#      zónder token worden geweigerd. Zonder deze twee zegt een 200 niets: dan kan het pad
#      openstaan in plaats van geautoriseerd zijn;
#   4. verantwoording — beide peers loggen dezelfde transactie, de een als uitgaand en de ander als
#      inkomend, met een grant-hash erbij;
#   5. idempotentie — de bootstrap nog eens draaien levert geen tweede contract op.
#
# Linux + podman: gebruikt `podman` en `jq`.
set -euo pipefail

HERE="$(cd "$(dirname "$0")" && pwd)"
ENVDIR="$(cd "${HERE}/.." && pwd)"

# shellcheck source=../lib/fsc-harness.sh
. "${ENVDIR}/lib/fsc-harness.sh"
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

# De outway spreekt plain HTTP op zijn eigen adres; de mTLS begint pas aan de andere kant, richting
# de inway van de provider.
OUTWAY="http://$(fsc_component_adres "$CONS_NET" outway):8443"

# Thumbprint van de consumer-outway — zelfde grootheid als bootstrap.sh gebruikt om een contract
# te identificeren. Nodig om hier dezelfde matcher (fsc_grant_actief) te kunnen hergebruiken.
CONS_OUTWAY_CERT="${ENVDIR}/${UITVRAAG}/pki/out/${UITVRAAG}/outway/cert.pem"
CONS_THUMB="$(fsc_outway_thumbprint "$CONS_OUTWAY_CERT")" \
  || { echo "FAIL: kon de outway-thumbprint niet berekenen uit ${CONS_OUTWAY_CERT}: $(fsc_last_error)" >&2; exit 1; }

# manager_json <peer> <net>: de contracten van die peer via zijn interne API, op het manager-adres
# binnen zijn eigen /24.
manager_json() {
  fsc_manager_contracts "$ENVDIR" "$1" "$(fsc_component_adres "$2" manager)"
}

for magazijn in $MAGAZIJNEN; do
  PROV_NET="$(fsc_peer_waarde NET "$magazijn")"
  PROV_OIN="$(fsc_peer_waarde OIN "$magazijn")"
  [ -n "$PROV_NET" ] && [ -n "$PROV_OIN" ] || { fout "NET_/OIN_ ontbreekt voor '${magazijn}'"; continue; }

  echo "===== ${UITVRAAG} -> ${magazijn} ====="

  # --- 1. Contract op beide managers ------------------------------------------------------------
  echo "== 1. contract wederzijds ondertekend =="
  GRANT=""
  for kant in "$UITVRAAG:$CONS_NET" "$magazijn:$PROV_NET"; do
    peer="${kant%%:*}"; net="${kant##*:}"

    if ! JSON="$(manager_json "$peer" "$net")"; then
      fout "${peer}: contracten niet op te halen: $(fsc_last_error)"
      continue
    fi

    # Zelfde matcher als contracts/bootstrap.sh gebruikt om een bestaand contract te herkennen:
    # lifecycle-state, niet-ingetrokken, beide handtekeningen én de outway-thumbprint. Dat laatste
    # ontbrak hier voorheen — zonder thumbprint-check zou een contract voor een andere (bv. na
    # certificaatrotatie verouderde) consumer-outway ook meetellen.
    CONTRACT_HASH="$(fsc_grant_actief "$JSON" "$MAGAZIJN_DIENST" "$PROV_OIN" "$CONS_OIN" "$CONS_THUMB" \
      | sort | head -n1)" || CONTRACT_HASH=""

    if [ -n "$CONTRACT_HASH" ]; then
      ok "${peer}: contract geldig met handtekeningen van beide peers"
      if [ -z "$GRANT" ]; then
        # De grant-hash (voor de Fsc-Grant-Hash-header) is niet het contract-hash zelf.
        GRANT="$(fsc_grant_hash "$JSON" "$CONTRACT_HASH" "$MAGAZIJN_DIENST" "$CONS_THUMB")"
      fi
    else
      fout "${peer}: geen geldig contract met beide handtekeningen voor ${MAGAZIJN_DIENST}"
    fi
  done

  if [ -z "$GRANT" ]; then
    fout "geen grant-hash gevonden — de asserts hieronder kunnen niet draaien"
    continue
  fi

  # --- 2. Data-pad --------------------------------------------------------------------------------
  # De outway resolvet de grant-hash zelf naar dienst en inway, en haalt daarbij zijn eigen token
  # op. Dat token wordt hier dus niet nagebouwd.
  echo "== 2. data-pad =="
  BODY="$(curl -sS --noproxy '*' -o - -w '\n%{http_code}' --max-time 20 \
            "${OUTWAY}/" -H "Fsc-Grant-Hash: ${GRANT}" 2>"$ERRLOG" || true)"
  CODE="$(printf '%s' "$BODY" | tail -n1)"
  PAYLOAD="$(printf '%s' "$BODY" | sed '$d')"

  # De echo van elk magazijn draagt zijn eigen naam, dus dit onderscheidt "de juiste peer
  # antwoordde" van "er kwam toevallig een 200 terug" — de stub van de uitvraag-peer zelf zegt
  # iets anders.
  if [ "$CODE" = "200" ] && printf '%s' "$PAYLOAD" | grep -qF "hello from ${magazijn}"; then
    ok "data-pad levert 200 met de echo van ${magazijn}"
  elif printf '%s' "$PAYLOAD" | grep -qF "service could not be found"; then
    # Een contract geeft toegang tot een dienst die gepubliceerd MOET zijn; is dat niet gebeurd, dan
    # faalt de token-uitgifte diep in de keten (outway -> manager -> controller) met een kale 500
    # waarin het woord 'contract' niet voorkomt. Zonder deze tak zoek je dat in de verkeerde hoek.
    fout "de dienst '${MAGAZIJN_DIENST}' is niet gepubliceerd op ${magazijn} — draai eerst ${magazijn}/deploy/local/publish-service.sh (of smoke-federatie.sh, die publiceert 'm)"
  else
    fout "data-pad niet geslaagd (HTTP ${CODE:-<geen>}): $(printf '%s' "$PAYLOAD" | head -n1) $(fsc_last_error)"
  fi

  # --- 3. Afdwinging ------------------------------------------------------------------------------
  echo "== 3. toegang wordt afgedwongen =="
  ONBEKEND="$(curl -sS --noproxy '*' -o /dev/null -w '%{http_code}' --max-time 15 \
                "${OUTWAY}/" -H 'Fsc-Grant-Hash: $1$3$deze-grant-bestaat-niet' 2>/dev/null || true)"
  if [ "$ONBEKEND" = "400" ]; then
    ok "onbekende grant-hash op de outway wordt geweigerd (400)"
  else
    fout "onbekende grant-hash gaf HTTP ${ONBEKEND:-<geen>}, verwacht 400"
  fi

  # Rechtstreeks naar de inway mét geldig group-cert maar zónder token: de mTLS-laag laat je binnen,
  # de autorisatielaag hoort je alsnog te weigeren.
  INWAY_NAAM="inway.${magazijn}.fsc-test.local"
  ZONDER_TOKEN="$(curl -sS --noproxy '*' -o /dev/null -w '%{http_code}' --max-time 15 \
                    --resolve "${INWAY_NAAM}:443:${ADRES_ROUTER}" \
                    --cert "${ENVDIR}/${UITVRAAG}/pki/out/${UITVRAAG}/outway/cert.pem" \
                    --key  "${ENVDIR}/${UITVRAAG}/pki/out/${UITVRAAG}/outway/key.pem" \
                    --cacert "${ENVDIR}/${UITVRAAG}/pki/ca/root.pem" \
                    "https://${INWAY_NAAM}/" 2>/dev/null || true)"
  if [ "$ZONDER_TOKEN" = "401" ]; then
    ok "inway weigert een call zonder token (401)"
  else
    fout "inway zonder token gaf HTTP ${ZONDER_TOKEN:-<geen>}, verwacht 401"
  fi

  # --- 4. Verantwoording --------------------------------------------------------------------------
  # Beide peers horen dezelfde transactie te loggen: bij de consumer uitgaand, bij de provider
  # inkomend. Dat is de fsc-logging-keten over de peer-grens.
  echo "== 4. verantwoording =="
  if ! PROJECT="$(fsc_compose_project "${ENVDIR}/${GASTHEER}/deploy/local/docker-compose.yaml")"; then
    fout "projectnaam van de gastheer niet af te leiden"
  else
    txids() {  # <db> <richting>
      podman exec "${PROJECT}-postgres-1" psql -U postgres -d "$1" -tA \
        -c "SELECT transaction_id FROM transactionlog.records
            WHERE direction = '$2' AND service_name = '${MAGAZIJN_DIENST}' AND grant_hash IS NOT NULL" \
        2>"$ERRLOG" | sort -u
    }

    CONS_DB="fsc_txlog_$(fsc_peer_var "$UITVRAAG")"
    PROV_DB="fsc_txlog_$(fsc_peer_var "$magazijn")"

    UIT="$(txids "$CONS_DB" out)" || UIT=""
    IN="$(txids "$PROV_DB" in)"   || IN=""
    GEDEELD="$(comm -12 <(printf '%s\n' "$UIT") <(printf '%s\n' "$IN") | grep -c . || true)"

    if [ "${GEDEELD:-0}" -gt 0 ]; then
      ok "${GEDEELD} transactie(s) in beide txlogs, uitgaand bij ${UITVRAAG} en inkomend bij ${magazijn}"
    else
      fout "geen gedeelde transactie-id tussen ${CONS_DB} (out) en ${PROV_DB} (in): $(fsc_last_error)"
    fi
  fi
done

# --- 5. Idempotentie ------------------------------------------------------------------------------
# Zonder state-file: de bootstrap moet het bestaande contract herkennen uit de contracten zelf.
# Anders zou elke deploy er een geldig contract bij maken. Een aggregaat-COUNT over alle magazijnen
# zou een verschuiving (het ene magazijn erbij, het andere eraf) niet zien; per-magazijn hetzelfde
# canonieke contract-hash vóór en na is de eigenlijke garantie.
echo "===== 5. idempotentie ====="

contract_hashes_per_magazijn() {  # gebruikt $UITVRAAG/$CONS_NET/$CONS_THUMB uit de buitenste scope
  local json magazijn prov_oin hash
  json="$(manager_json "$UITVRAAG" "$CONS_NET")" || json=""
  for magazijn in $MAGAZIJNEN; do
    prov_oin="$(fsc_peer_waarde OIN "$magazijn")"
    hash="$(fsc_grant_actief "$json" "$MAGAZIJN_DIENST" "$prov_oin" "$CONS_OIN" "$CONS_THUMB" \
      | sort | head -n1)" || hash=""
    printf '%s=%s\n' "$magazijn" "$hash"
  done
}

VOOR="$(contract_hashes_per_magazijn)"
HERDRAAI_LOG="$(mktemp)"

if "${HERE}/contracts/fbs-contracten.sh" >"$HERDRAAI_LOG" 2>&1; then
  NA="$(contract_hashes_per_magazijn)"

  if [ -n "$VOOR" ] && [ "$VOOR" = "$NA" ] && ! printf '%s\n' "$VOOR" | grep -q '=$'; then
    ok "her-draaien van de bootstrap levert per magazijn hetzelfde contract op"
  else
    fout "contract-hash per magazijn verschilt (of ontbreekt) vóór/na de her-draai — vóór: [${VOOR}] na: [${NA}]"
  fi
else
  fout "fbs-contracten.sh faalde bij de her-draai: $(tail -n 10 "$HERDRAAI_LOG")"
fi
rm -f "$HERDRAAI_LOG"

echo
if [ "$FOUTEN" -eq 0 ]; then
  echo "== CONTRACT-SMOKE GROEN =="
else
  echo "== CONTRACT-SMOKE ROOD: ${FOUTEN} bevinding(en) ==" >&2
  exit 1
fi
