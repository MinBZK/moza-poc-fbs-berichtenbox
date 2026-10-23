#!/usr/bin/env bash
# Unittests voor selinux-label-guard.sh, op handgemaakte merges en op de echte overlays van deze
# repo. Dat laatste deel vangt wat een fixture nooit ziet: dat de opstellingen zelf aan de eis
# voldoen, en dat de guard bij een verwijderd label daadwerkelijk rood wordt.

set -euo pipefail

HERE=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)
REPO_ROOT=$(cd "$HERE/../.." && pwd)
readonly HERE REPO_ROOT
readonly SCRIPT="$HERE/selinux-label-guard.sh"

werkmap=$(mktemp -d)
trap 'rm -rf "$werkmap"' EXIT

asserties=0
mislukt=0

ok() {
  asserties=$((asserties + 1))
  echo "OK: $1"
}

fout() {
  mislukt=1
  echo "FOUT: $1"
}

# draai <merge-json> [args…]: zet STATUS en UITVOER.
draai() {
  local merge=$1
  shift

  STATUS=0
  UITVOER=$(printf '%s' "$merge" | bash "$SCRIPT" "$@" 2>&1) || STATUS=$?
}

verwacht() {
  local wat=$1 status=$2 naald=$3

  if [ "$STATUS" -ne "$status" ]; then
    fout "$wat — exit $STATUS, verwacht $status. Uitvoer: $UITVOER"
    return
  fi

  case "$UITVOER" in
    *"$naald"*) ok "$wat" ;;
    *) fout "$wat — '$naald' ontbreekt in: $UITVOER" ;;
  esac
}

bind() {
  local bron=$1 doel=$2 label=${3:-}

  if [ -n "$label" ]; then
    printf '{"type":"bind","source":"%s","target":"%s","bind":{"selinux":"%s"}}' "$bron" "$doel" "$label"
  else
    printf '{"type":"bind","source":"%s","target":"%s","bind":{"create_host_path":true}}' "$bron" "$doel"
  fi
}

# --- handgemaakte merges ---

een_z="{\"services\":{\"router\":{\"volumes\":[$(bind /h/haproxy.cfg /etc/haproxy.cfg z)]}}}"
draai "$een_z" "één"
verwacht "één gelabelde bind-mount is groen" 0 "één: 1/1 bind-mounts"

meerdere_z="{\"services\":{\"router\":{\"volumes\":[$(bind /h/a /a z)]},
  \"manager\":{\"volumes\":[$(bind /h/pki /pki z),{\"type\":\"volume\",\"source\":\"data\",\"target\":\"/data\"}]}}}"
draai "$meerdere_z" "meerdere"
verwacht "meerdere gelabelde bind-mounts zijn groen; named volumes tellen niet mee" 0 "meerdere: 2/2 bind-mounts"

een_zonder="{\"services\":{\"router\":{\"volumes\":[$(bind /h/a /a z)]},
  \"manager\":{\"volumes\":[$(bind /h/pki /pki)]}}}"
draai "$een_zonder" "deels"
verwacht "één ongelabelde tussen gelabelde faalt" 1 "manager: /h/pki -> /pki (label: <geen>)"

case "$UITVOER" in
  *"router:"*) fout "de gelabelde mount wordt niet als bevinding gemeld — uitvoer: $UITVOER" ;;
  *) ok "de gelabelde mount wordt niet als bevinding gemeld" ;;
esac

hoofd_z="{\"services\":{\"manager\":{\"volumes\":[$(bind /h/pki /pki Z)]}}}"
draai "$hoofd_z" "privé"
verwacht "een privé-label (Z) faalt" 1 "(label: Z)"

zonder_bind_sectie='{"services":{"pg":{"volumes":[{"type":"bind","source":"/h/i.sql","target":"/i.sql"}]}}}'
draai "$zonder_bind_sectie" "kaal"
verwacht "een bind-mount zonder bind-sectie faalt" 1 "pg: /h/i.sql -> /i.sql (label: <geen>)"

draai '{"services":{"pg":{"image":"postgres"}}}' "leeg"
verwacht "een merge zonder bind-mounts meet niets en faalt" 1 "deze controle meet niets"

draai '{"services":{}}' "geen-services"
verwacht "een merge zonder services faalt" 1 "deze controle meet niets"

draai "" "stdin"
verwacht "lege invoer faalt" 1 "lege merge op stdin"

draai "geen json" "rommel"
verwacht "onleesbare invoer faalt" 1 "geen leesbare compose-JSON"

draai "$een_z"
verwacht "zonder label-argument weigert het script" 1 "usage"

draai "$een_z" "a" "b"
verwacht "met een extra argument weigert het script" 2 "precies één argument"

# --- de echte overlays ---

if ! docker compose version >/dev/null 2>&1; then
  fout "docker compose ontbreekt; de echte overlays zijn niet te renderen"
else
  render_peer() {
    local peer=$1
    shift
    local args=() f

    for f in "$@"; do args+=(-f "$f"); done

    (cd "$REPO_ROOT/demo/environment/$peer/deploy/local" \
       && PKI_DIR=../../pki HOST_UID=1000 HOST_GID=1000 \
          docker compose --env-file /dev/null "${args[@]}" config --format json 2>"$werkmap/render.err") \
      || echo "render faalde: $(tail -3 "$werkmap/render.err")" >&2
  }

  for peer in logius magazijn-a; do
    draai "$(render_peer "$peer" compose.yaml compose.podman.yaml)" "$peer bridge"
    verwacht "$peer: bridge-merge heeft elke bind-mount gelabeld" 0 "$peer bridge:"

    draai "$(render_peer "$peer" compose.yaml compose.podman.yaml compose.podman-hostnet.yaml)" "$peer hostnet"
    verwacht "$peer: hostnet-merge heeft elke bind-mount gelabeld" 0 "$peer hostnet:"

    draai "$(render_peer "$peer" compose.yaml compose.podman.yaml compose.podman-hostnet.yaml \
               "../../../federatie/compose/$peer.yaml")" "$peer federatie"
    verwacht "$peer: federatie-merge heeft elke bind-mount gelabeld" 0 "$peer federatie:"

    # De basis zonder podman-overlay draagt bewust geen labels. Blijft dat groen, dan meet de
    # guard op de echte merge niets.
    draai "$(render_peer "$peer" compose.yaml)" "$peer basis"
    verwacht "$peer: de kale basis (Docker) faalt zoals bedoeld" 1 "zonder gedeeld SELinux-label"
  done

  for lagen in "compose.podman.yaml" "compose.podman.yaml compose.podman-hostnet.yaml"; do
    args=(-f compose.yaml)
    for f in $lagen; do args+=(-f "$f"); done

    draai "$(cd "$REPO_ROOT" && DEMO_HOST=localhost DEMO_MAGAZIJNEN=98 \
               docker compose --profile demo "${args[@]}" config --format json 2>"$werkmap/render.err" \
             || echo "render faalde: $(tail -3 "$werkmap/render.err")" >&2)" "demo-stack"
    verwacht "demo-stack: merge met $lagen heeft elke bind-mount gelabeld" 0 "demo-stack:"
  done
fi

echo "ASSERTIES=$asserties"
exit "$mislukt"
