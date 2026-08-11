#!/usr/bin/env bash
# Smoke: elke langlopende service draait en elke migrate-job is met 0 afgesloten.
#
# De announce-smoke raakt maar een deel van de stack: de manager announce't ook prima terwijl
# outway, inway, controller en txlog crash-loopen, want die worden lui gedialed. Juist dat zijn de
# componenten waarvan de hostnet-overlay de poorten hernummert, dus een fout daarin moet luid
# falen in plaats van onder een groene announce te verdwijnen.
set -euo pipefail

COMPOSE=(docker compose -f "$(dirname "$0")/docker-compose.yaml")

echo "smoke: containerstatus controleren..."

status=$("${COMPOSE[@]}" ps -a --format '{{.Service}} {{.State}} {{.ExitCode}}')

# De verwachte set is de vereniging van twee onvolledige lijsten. Alleen `config --services`
# missen we een service die enkel in een overlay bestaat (dit script kent maar één `-f`); alleen
# `ps` missen we een service die nooit is aangemaakt. Samen dekken ze allebei die gaten.
mapfile -t verwacht < <( { "${COMPOSE[@]}" config --services; awk '{print $1}' <<<"$status"; } \
  | grep -v '^$' | sort -u)

if [ "${#verwacht[@]}" -eq 0 ]; then
  echo "FAIL: geen services gevonden — klopt het pad naar docker-compose.yaml?" >&2
  exit 1
fi

fout=0

for service in "${verwacht[@]}"; do
  regel=$(grep -m1 "^${service} " <<<"$status" || true)

  if [ -z "$regel" ]; then
    echo "  FAIL: $service heeft geen container — is de stack volledig gestart?" >&2
    fout=1
    continue
  fi

  read -r _ state code <<<"$regel"

  # migrate-jobs zijn one-shots: afgesloten met 0. Compose bewaakt dat via
  # `condition: service_completed_successfully`, maar alleen voor jobs waar iets op wacht.
  case "$service" in
    migrate-*)
      if [ "$state" != "exited" ] || [ "$code" != "0" ]; then
        echo "  FAIL: $service staat op '$state' (exit $code), verwacht 'exited' met 0" >&2
        "${COMPOSE[@]}" logs --tail=20 "$service" >&2 || true
        fout=1
      fi
      ;;
    *)
      if [ "$state" != "running" ]; then
        echo "  FAIL: $service staat op '$state', verwacht 'running'" >&2
        "${COMPOSE[@]}" logs --tail=20 "$service" >&2 || true
        fout=1
      fi
      ;;
  esac
done

if [ "$fout" -ne 0 ]; then
  echo "FAIL: niet alle services zijn gezond." >&2
  exit 1
fi

echo "OK: alle ${#verwacht[@]} services gezond (langlopend draait, migrate-jobs afgerond)."
