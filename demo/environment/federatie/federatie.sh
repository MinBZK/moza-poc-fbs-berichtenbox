#!/usr/bin/env bash
# Bouwt de lokale twee-peer-federatie op, breekt 'm af, of toont de staat.
#
#   ./federatie.sh up       # gastheer-stack, dan gast-stack; wacht tot alle peers aangemeld zijn
#   ./federatie.sh down     # gast eerst, dan gastheer (inclusief volumes)
#   ./federatie.sh status   # containers + de luisteraars per peer-blok
#
# Voorwaarden: alle peers dragen dezelfde group-CA (`./deel-groep-ca.sh`), elke peer heeft zijn
# `deploy/local/.env`, en `net.ipv4.ip_unprivileged_port_start` staat op 0 zodat de router op
# :443 mag binden. README.md beschrijft ze alle drie.
#
# bash 3.2-compatibel (macOS-default), net als de rest van de harness-scripts.
set -euo pipefail

HERE="$(cd "$(dirname "$0")" && pwd)"
ENVDIR="$(cd "${HERE}/.." && pwd)"

# De gastheer levert postgres, router en directory; de gasten haken daarop aan. Volgorde telt
# bij `up` (de directory moet er zijn vóór een gast announcet) en omgekeerd bij `down`.
GASTHEER=logius
GASTEN="magazijn-a"

ANNOUNCE_TIMEOUT="${ANNOUNCE_TIMEOUT:-120}"
ANNOUNCE_INTERVAL="${ANNOUNCE_INTERVAL:-5}"

# Het aantal verwachte rijen in peers.peers: elke peer plus de directory zelf.
verwacht_peers() {
  local n=1
  for _ in $GASTEN; do n=$((n + 1)); done
  echo $((n + 1))   # + de gastheer-peer
}

# compose_args <peer>: echoot de vier -f-vlaggen voor die peer, in de juiste volgorde.
compose_args() {
  local peer="$1" local_dir="${ENVDIR}/$1/deploy/local"
  printf '%s\n' \
    -f "${local_dir}/docker-compose.yaml" \
    -f "${local_dir}/docker-compose.podman.yaml" \
    -f "${local_dir}/docker-compose.podman-hostnet.yaml" \
    -f "${HERE}/compose/${peer}.yaml"
}

# dc <peer> <compose-args...>: docker compose voor die peer.
dc() {
  local peer="$1"; shift
  local args=()
  while IFS= read -r a; do args+=("$a"); done < <(compose_args "$peer")
  docker compose "${args[@]}" "$@"
}

# psql_directory <sql>: query de directory-DB in de gastheer-postgres.
psql_directory() {
  podman exec "fsc-${GASTHEER}-postgres-1" \
    psql -U postgres -d fsc_directory -tA -c "$1" 2>/dev/null
}

# compose_project <peer>: de projectnaam zoals in het `name:`-veld van de basis-compose.
# `magazijn-a` -> `fsc-magazijna` (compose strippt het koppelteken niet zelf; de basis zet 'm
# expliciet zo), dus afleiden uit het bestand in plaats van gokken.
compose_project() {
  sed -n 's/^name:[[:space:]]*//p' "${ENVDIR}/$1/deploy/local/docker-compose.yaml" | head -n1
}

# up_met_retry <peer>: `up -d` met een begrensd aantal pogingen.
#
# Podman kan bij een verse `up` twee transiënte fouten geven die niets met de configuratie te
# maken hebben, en beide laten containers op `Created` achter waardoor een simpele herhaling
# óók faalt:
#   - "container ID 0 cannot be mapped to a host ID" — twee containers maken tegelijk een
#     ID-mapped kopie van dezelfde image-laag; wie verliest, faalt. Na één geslaagde kopie is
#     de laag gecached en treedt het niet meer op.
#   - "error during connect: ... EOF" — de podman-API-service bezwijkt onder gelijktijdige
#     creates. Die moet je zelf herstarten; dit script meldt dat en stopt.
# Vandaar: restanten opruimen tussen pogingen, en de EOF-variant apart benoemen.
up_met_retry() {
  local peer="$1" project poging=1 max="${UP_POGINGEN:-3}" log rc
  project="$(compose_project "$peer")"
  log=$(mktemp)

  while [ "$poging" -le "$max" ]; do
    rc=0; dc "$peer" up -d >"$log" 2>&1 || rc=$?
    if [ "$rc" -eq 0 ]; then rm -f "$log"; return 0; fi

    if grep -q 'error during connect' "$log"; then
      echo "FAIL: de podman-API-service is niet bereikbaar. Herstart 'm en probeer opnieuw:" >&2
      echo "  podman system service --time=0 unix://\${XDG_RUNTIME_DIR:-/tmp/podman-run-\$(id -u)}/podman/podman.sock &" >&2
      tail -n 5 "$log" >&2; rm -f "$log"; return 1
    fi

    if [ "$poging" -lt "$max" ]; then
      echo "  poging ${poging}/${max} faalde (transiënt?), restanten opruimen en opnieuw..." >&2
      tail -n 2 "$log" >&2
      # Alleen containers van DIT project die het niet gehaald hebben; draaiende blijven staan.
      local achterblijvers
      achterblijvers="$(podman ps -aq \
        --filter "label=com.docker.compose.project=${project}" --filter status=created 2>/dev/null || true)"
      [ -n "$achterblijvers" ] && podman rm -f $achterblijvers >/dev/null 2>&1
      sleep 2
    fi
    poging=$((poging + 1))
  done

  echo "FAIL: '${peer}' kwam niet omhoog in ${max} pogingen:" >&2
  tail -n 10 "$log" >&2; rm -f "$log"; return 1
}

case "${1:-}" in
  up)
    echo "federatie: gastheer-stack (${GASTHEER}) — postgres, router, directory + peer..."
    up_met_retry "$GASTHEER"

    echo "federatie: wachten tot de directory zichzelf heeft geregistreerd..."
    elapsed=0
    while [ "$elapsed" -lt "$ANNOUNCE_TIMEOUT" ]; do
      [ "$(psql_directory 'SELECT count(*) FROM peers.peers' || echo 0)" -ge 1 ] && break
      sleep "$ANNOUNCE_INTERVAL"; elapsed=$((elapsed + ANNOUNCE_INTERVAL))
    done
    [ "$elapsed" -lt "$ANNOUNCE_TIMEOUT" ] || {
      echo "FAIL: directory kwam niet omhoog binnen ${ANNOUNCE_TIMEOUT}s." >&2
      podman logs --tail=40 "fsc-${GASTHEER}-manager-directory-1" >&2 || true
      exit 1
    }

    for gast in $GASTEN; do
      echo "federatie: gast-stack (${gast}) in dezelfde netns..."
      up_met_retry "$gast"
    done

    echo "federatie: wachten tot alle peers aangemeld zijn (op :443)..."
    doel="$(verwacht_peers)"
    elapsed=0
    while [ "$elapsed" -lt "$ANNOUNCE_TIMEOUT" ]; do
      n="$(psql_directory "SELECT count(*) FROM peers.peers WHERE manager_address LIKE '%:443'" || echo 0)"
      [ "${n:-0}" -ge "$doel" ] && break
      sleep "$ANNOUNCE_INTERVAL"; elapsed=$((elapsed + ANNOUNCE_INTERVAL))
      echo "  ...${n:-0}/${doel} aangemeld (${elapsed}s)"
    done
    podman exec "fsc-${GASTHEER}-postgres-1" \
      psql -U postgres -d fsc_directory -c "SELECT id, name, manager_address FROM peers.peers ORDER BY id;"
    [ "${n:-0}" -ge "$doel" ] || { echo "FAIL: ${n:-0}/${doel} peers aangemeld binnen ${ANNOUNCE_TIMEOUT}s." >&2; exit 1; }
    echo "FEDERATIE OP."
    ;;

  down)
    for gast in $GASTEN; do
      echo "federatie: gast-stack (${gast}) afbreken..."
      dc "$gast" down -v || true
    done
    echo "federatie: gastheer-stack (${GASTHEER}) afbreken..."
    dc "$GASTHEER" down -v || true
    echo "FEDERATIE NEER."
    ;;

  status)
    podman ps -a --format '{{.Names}}\t{{.Status}}' | sort
    echo
    echo "luisteraars in de gedeelde netns:"
    ss -ltn 2>/dev/null | awk 'NR>1 {print $4}' | grep -E '^127\.0\.0\.1:' | sort -t: -k2 -n
    ;;

  *)
    echo "usage: $0 <up|down|status>" >&2
    exit 2
    ;;
esac
