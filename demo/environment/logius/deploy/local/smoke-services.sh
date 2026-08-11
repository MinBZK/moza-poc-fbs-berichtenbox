#!/usr/bin/env bash
# Smoke: elke langlopende service draait en elke migrate-job is schoon afgesloten.
#
# De announce-smoke raakt maar een deel van de stack: de manager announce't ook prima terwijl
# outway, inway, controller en txlog crash-loopen, want die worden lui gedialed. Juist dat zijn de
# componenten waarvan de hostnet-overlay de poorten hernummert, dus een fout daarin moet luid
# falen in plaats van onder een groene announce te verdwijnen.
set -euo pipefail

COMPOSE=(docker compose -f "$(dirname "$0")/docker-compose.yaml")

echo "smoke: containerstatus controleren..."

status=$("${COMPOSE[@]}" ps -a --format '{{.Service}} {{.State}}')

if [ -z "$status" ]; then
  echo "FAIL: geen containers gevonden — draait de stack wel?" >&2
  exit 1
fi

fout=0

while read -r service state; do
  [ -z "$service" ] && continue

  # migrate-jobs zijn one-shots: die horen afgesloten te zijn, niet te draaien. Dat ze met 0
  # eindigden bewaakt compose zelf al via `condition: service_completed_successfully`.
  case "$service" in
    migrate-*) verwacht=exited ;;
    *)         verwacht=running ;;
  esac

  if [ "$state" != "$verwacht" ]; then
    echo "  FAIL: $service staat op '$state', verwacht '$verwacht'" >&2
    "${COMPOSE[@]}" logs --tail=20 "$service" >&2 || true
    fout=1
  fi
done <<< "$status"

if [ "$fout" -ne 0 ]; then
  echo "FAIL: niet alle services zijn gezond." >&2
  exit 1
fi

echo "OK: alle services draaien, alle migrate-jobs afgerond."
