#!/usr/bin/env bash
# Smoke: bewijst dat de door magazijn-a gepubliceerde dienst `berichtenmagazijn` als GELDIGE
# publicatie vindbaar is bij de directory. Pollt de manager Internal-API
# (GET /v1/peers/{dir}/services?peer_id={provider}) — de mesh-API, NIET een directory-DB-tabel:
# gepubliceerde diensten leven niet in een plain `services`-tabel maar worden via de mesh
# opgevraagd (spiegelt repo A's smoke-publish.sh). Vereist dat publish-service.sh eerst draaide
# (run-smokes.sh doet dat).
# fsc_tb() en de andere helpers uit lib/fsc-harness.sh lezen COMPOSE/CERT/KEY/CA uit de
# caller-scope. Shellcheck ziet die koppeling niet en vlagt ze als ongebruikt.
# shellcheck disable=SC2034
set -euo pipefail


HERE="$(cd "$(dirname "$0")" && pwd)"
# shellcheck source=../../../lib/fsc-harness.sh
source "$HERE/../../../lib/fsc-harness.sh"

COMPOSE=(docker compose -f "$HERE/docker-compose.yaml")
# Overrulebaar zodat één peer kan toetsen of hij de dienst van een ÁNDERE peer in de gedeelde
# catalogus ziet — de kerneigenschap van een federatie. Defaults = de eigen dienst, dus
# standalone verandert er niets.
SERVICE_NAME="${FSC_SERVICE_NAME:-berichtenmagazijn}"
PROVIDER_OIN="${FSC_PROVIDER_OIN:-00000000000000100000}"
DIR_OIN="00000000000000000010"
# directory-propagatie na auto-sign is vrijwel direct; 10s volstaat na de inway-poll in publish-service.sh.
TIMEOUT=10
INTERVAL=2

# De provider bevraagt de directory via zijn EIGEN manager (internal-cert) naar de eigen
# gepubliceerde diensten. Robuuster dan de directory-DB pollen (geen tabelnaam-koppeling).
CERT=/pki/internal/magazijn-a/manager/cert.pem
KEY=/pki/internal/magazijn-a/manager/key.pem
CA=/pki/internal/magazijn-a/ca/root.pem
# Overrulebaar: in de federatie-opstelling (../../../federatie/) verhuist de interne manager-poort
# naar het peer-blok. FSC_-prefix zodat een kale `MANAGER=` in de shell dit niet stil omleidt.
MANAGER="${FSC_MANAGER:-https://manager.magazijn-a.fsc-test.local:9443}"

# De adressen worden geconcateneerd tot curl's URL-argument. Een waarde die met `-` begint leest
# curl als OPTIE — `-K/pad` maakt er een config-file-lees van — dus eisen we het https-schema.
case "$MANAGER" in
  https://*) ;;
  *) echo "FAIL: FSC_MANAGER moet met https:// beginnen: '${MANAGER}'" >&2; exit 2 ;;
esac

# Vang toolbox-/curl-stderr op zodat een mTLS-/dode-container-fout niet als "nog niet vindbaar"
# maskeert (spiegelt smoke-announce.sh).
fsc_errlog_init

echo "smoke-discover: pollen tot ${SERVICE_NAME} vindbaar is bij de directory (mesh-API)..."
elapsed=0
while [ "$elapsed" -lt "$TIMEOUT" ]; do
  out=$("${COMPOSE[@]}" exec -T toolbox curl -sS \
          --cert "$CERT" --key "$KEY" --cacert "$CA" \
          "$MANAGER/v1/peers/$DIR_OIN/services?peer_id=$PROVIDER_OIN" 2>"$ERRLOG" || true)
  fsc_warn_errlog "poll-fout"

  if printf '%s' "$out" | grep -q "\"$SERVICE_NAME\""; then
    echo "OK: ${SERVICE_NAME} is gepubliceerd en vindbaar in de directory."
    printf 'Catalogus: %s\n' "$out"
    echo "SMOKE-DISCOVER GROEN."
    exit 0
  fi

  sleep "$INTERVAL"; elapsed=$((elapsed + INTERVAL))
  echo "  ...nog niet vindbaar (${elapsed}s)"
done

echo "FAIL: ${SERVICE_NAME} niet vindbaar binnen ${TIMEOUT}s (publish-service.sh gedraaid?)." >&2
echo "Debug: eigen publicaties (manager Internal-API) + logs:" >&2
"${COMPOSE[@]}" exec -T toolbox curl -sS --cert "$CERT" --key "$KEY" --cacert "$CA" \
   "$MANAGER/v1/services/publications" >&2 || true
LAST=$(fsc_last_error 3)
[ -n "$LAST" ] && { echo "  -> laatste poll-fout:" >&2; printf '%s\n' "$LAST" >&2; }
"${COMPOSE[@]}" logs --tail=50 manager-magazijn-a manager-directory inway-magazijn-a >&2 || true
exit 1
