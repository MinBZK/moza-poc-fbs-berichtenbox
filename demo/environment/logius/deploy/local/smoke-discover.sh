#!/usr/bin/env bash
# Smoke: bewijst dat de door logius gepubliceerde dienst `profiel-service` als GELDIGE
# publicatie vindbaar is bij de directory. Pollt de manager Internal-API
# (GET /v1/peers/{dir}/services?peer_id={provider}) — de mesh-API, NIET een directory-DB-tabel:
# gepubliceerde diensten leven niet in een plain `services`-tabel maar worden via de mesh
# opgevraagd (spiegelt magazijn-a's smoke-discover.sh). Vereist dat publish-service.sh eerst
# draaide (run-smokes.sh doet dat).
set -euo pipefail

HERE="$(dirname "$0")"
COMPOSE=(docker compose -f "$HERE/docker-compose.yaml")
SERVICE_NAME="profiel-service"
PROVIDER_OIN="00000000000000001000"
DIR_OIN="00000000000000000010"
# directory-propagatie na auto-sign is vrijwel direct; 10s volstaat na de inway-poll in publish-service.sh.
TIMEOUT=10
INTERVAL=2

# De provider bevraagt de directory via zijn EIGEN manager (internal-cert) naar de eigen
# gepubliceerde diensten. Robuuster dan de directory-DB pollen (geen tabelnaam-koppeling).
CERT=/pki/internal/logius/manager/cert.pem
KEY=/pki/internal/logius/manager/key.pem
CA=/pki/internal/logius/ca/root.pem
MANAGER=https://manager.logius.fsc-test.local:9443

# Vang toolbox-/curl-stderr op zodat een mTLS-/dode-container-fout niet als "nog niet vindbaar"
# maskeert (spiegelt smoke-announce.sh).
ERRLOG=$(mktemp)
trap 'rm -f "$ERRLOG"' EXIT

# Onder podman schrijft de external-compose-provider-wrapper zelf een bannerregel naar stderr
# bij ELKE aanroep (">>>> Executing external compose provider ... <<<<"), niet alleen bij een
# echte curl-fout. Zonder filter leest [ -s "$ERRLOG" ] die banner als "poll-fout" op elke poll.
strip_wrapper_noise() {
  # De banner draagt SGR-ANSI-codes (bv. ESC[4m vóór de tekst), dus een anker op regelbegin mist
  # 'm; ANSI eerst strippen (portable-vorm i.p.v. \x1b, een GNU-sed-extensie die BSD-sed/macOS
  # niet kent), dan zonder anker filteren, en de lege regel weggooien die overblijft na het
  # strippen van de losse ESC[0m-regel.
  LC_ALL=C sed -e $'s/\033\\[[0-9;]*m//g' "$ERRLOG" \
    | grep -v 'Executing external compose provider' \
    | grep -v '^[[:space:]]*$' > "${ERRLOG}.f" 2>/dev/null || :
  mv -f "${ERRLOG}.f" "$ERRLOG"
}

echo "smoke-discover: pollen tot ${SERVICE_NAME} vindbaar is bij de directory (mesh-API)..."
elapsed=0
while [ "$elapsed" -lt "$TIMEOUT" ]; do
  out=$("${COMPOSE[@]}" exec -T toolbox curl -sS \
          --cert "$CERT" --key "$KEY" --cacert "$CA" \
          "$MANAGER/v1/peers/$DIR_OIN/services?peer_id=$PROVIDER_OIN" 2>"$ERRLOG" || true)
  strip_wrapper_noise
  [ -s "$ERRLOG" ] && { echo "  WARN: poll-fout: $(tail -n1 "$ERRLOG")" >&2; : >"$ERRLOG"; }

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
strip_wrapper_noise
[ -s "$ERRLOG" ] && { echo "  -> laatste poll-fout:" >&2; tail -n 3 "$ERRLOG" >&2; }
"${COMPOSE[@]}" logs --tail=50 manager-logius manager-directory inway-logius >&2 || true
exit 1
