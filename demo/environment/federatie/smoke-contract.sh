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

CONS_BLOK="$(fsc_peer_waarde BLOK "$UITVRAAG")"
CONS_OIN="$(fsc_peer_waarde OIN "$UITVRAAG")"
[ -n "$CONS_BLOK" ] && [ -n "$CONS_OIN" ] || { echo "FAIL: BLOK_/OIN_ ontbreekt voor '${UITVRAAG}'." >&2; exit 1; }

OUTWAY="http://127.0.0.1:$((CONS_BLOK + 40))"

# manager_json <peer> <blok>: de contracten van die peer via zijn interne API (blok+01).
manager_json() {
  local peer="$1" blok="$2" naam="manager.$1.fsc-test.local" poort=$(( $2 + 1 ))
  curl -sS --fail-with-body --noproxy '*' \
    --resolve "${naam}:${poort}:127.0.0.1" \
    --cert "${ENVDIR}/${peer}/pki/internal/${peer}/manager/cert.pem" \
    --key  "${ENVDIR}/${peer}/pki/internal/${peer}/manager/key.pem" \
    --cacert "${ENVDIR}/${peer}/pki/internal/${peer}/ca/root.pem" \
    "https://${naam}:${poort}/v1/contracts" 2>"$ERRLOG"
}

for magazijn in $MAGAZIJNEN; do
  PROV_BLOK="$(fsc_peer_waarde BLOK "$magazijn")"
  PROV_OIN="$(fsc_peer_waarde OIN "$magazijn")"
  [ -n "$PROV_BLOK" ] && [ -n "$PROV_OIN" ] || { fout "BLOK_/OIN_ ontbreekt voor '${magazijn}'"; continue; }

  echo "===== ${UITVRAAG} -> ${magazijn} ====="

  # --- 1. Contract op beide managers ------------------------------------------------------------
  echo "== 1. contract wederzijds ondertekend =="
  GRANT=""
  for kant in "$UITVRAAG:$CONS_BLOK" "$magazijn:$PROV_BLOK"; do
    peer="${kant%%:*}"; blok="${kant##*:}"

    if ! JSON="$(manager_json "$peer" "$blok")"; then
      fout "${peer}: contracten niet op te halen: $(fsc_last_error)"
      continue
    fi

    # Beide handtekeningen én de lifecycle-state. `signatures.accept` alleen is niet genoeg: de
    # manager gebruikt de grant pas als het contract óók `CONTRACT_STATE_VALID` is.
    GEVONDEN="$(printf '%s' "$JSON" | jq -r \
      --arg svc "$MAGAZIJN_DIENST" --arg prov "$PROV_OIN" --arg cons "$CONS_OIN" '
      [ .contracts[]?
        | select(.state == "CONTRACT_STATE_VALID")
        | select(any(.content.grants[]?;
              .type == "GRANT_TYPE_SERVICE_CONNECTION"
              and .service.name == $svc and .service.peer_id == $prov
              and .outway.peer_id == $cons))
        | select((.signatures.accept | has($prov)) and (.signatures.accept | has($cons)))
        | .content.grants[] | select(.type == "GRANT_TYPE_SERVICE_CONNECTION") | .hash ]
      | .[0] // ""' 2>/dev/null)"

    if [ -n "$GEVONDEN" ]; then
      ok "${peer}: contract geldig met handtekeningen van beide peers"
      [ -n "$GRANT" ] || GRANT="$GEVONDEN"
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
                    --resolve "${INWAY_NAAM}:443:127.0.0.1" \
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
# Anders zou elke deploy er een geldig contract bij maken.
echo "===== 5. idempotentie ====="
VOOR="$(manager_json "$UITVRAAG" "$CONS_BLOK" | jq '[.contracts[]?|select(any(.content.grants[]?; .type=="GRANT_TYPE_SERVICE_CONNECTION"))]|length' 2>/dev/null || echo -1)"

if "${HERE}/contracts/fbs-contracten.sh" >/dev/null 2>&1; then
  NA="$(manager_json "$UITVRAAG" "$CONS_BLOK" | jq '[.contracts[]?|select(any(.content.grants[]?; .type=="GRANT_TYPE_SERVICE_CONNECTION"))]|length' 2>/dev/null || echo -2)"

  if [ "$VOOR" -gt 0 ] && [ "$NA" -eq "$VOOR" ]; then
    ok "her-draaien van de bootstrap levert geen extra contract op (${VOOR} -> ${NA})"
  else
    fout "aantal serviceConnection-contracten ging van ${VOOR} naar ${NA}"
  fi
else
  fout "fbs-contracten.sh faalde bij de her-draai"
fi

echo
if [ "$FOUTEN" -eq 0 ]; then
  echo "== CONTRACT-SMOKE GROEN =="
else
  echo "== CONTRACT-SMOKE ROOD: ${FOUTEN} bevinding(en) ==" >&2
  exit 1
fi
