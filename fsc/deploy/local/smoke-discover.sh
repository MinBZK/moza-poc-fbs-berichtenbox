#!/usr/bin/env bash
# Smoke: bewijst dat de door magazijn-a gepubliceerde dienst `berichtenmagazijn` vindbaar is in
# de directory (system-of-record). Pollt de directory-DB tot de dienst voor de magazijn-OIN
# verschijnt. Vereist dat publish-service.sh eerst draaide.
set -euo pipefail

COMPOSE=(docker compose -f "$(dirname "$0")/docker-compose.yaml")
PROVIDER_OIN="00000001003214345000"
SERVICE_NAME="berichtenmagazijn"
TIMEOUT=60
INTERVAL=5

ERRLOG=$(mktemp); trap 'rm -f "$ERRLOG"' EXIT

echo "smoke-discover: wachten tot ${SERVICE_NAME} (${PROVIDER_OIN}) vindbaar is in de directory..."
elapsed=0
while [ "$elapsed" -lt "$TIMEOUT" ]; do
  rows=$("${COMPOSE[@]}" exec -T postgres psql -U postgres -d fsc_directory -tA \
    -c "SELECT name FROM services WHERE peer_id = '${PROVIDER_OIN}';" 2>"$ERRLOG" || true)
  if printf '%s\n' "$rows" | grep -qx "$SERVICE_NAME"; then
    echo "OK: ${SERVICE_NAME} is vindbaar in de directory."
    "${COMPOSE[@]}" exec -T postgres psql -U postgres -d fsc_directory \
      -c "SELECT peer_id, name FROM services;" || true
    echo "SMOKE-DISCOVER GROEN."; exit 0
  fi
  sleep "$INTERVAL"; elapsed=$((elapsed + INTERVAL))
  echo "  ...nog niet vindbaar (${elapsed}s)"
done

echo "FAIL: ${SERVICE_NAME} niet vindbaar binnen ${TIMEOUT}s (publish-service.sh gedraaid?)." >&2
[ -s "$ERRLOG" ] && { echo "  -> laatste psql-fout:" >&2; tail -n 3 "$ERRLOG" >&2; }
"${COMPOSE[@]}" logs --tail=50 manager-directory manager-magazijn-a controller-magazijn-a >&2 || true
exit 1
