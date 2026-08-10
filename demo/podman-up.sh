#!/usr/bin/env bash
# Start de demo-stack onder podman en kiest zelf de werkbare netwerkmodus.
#
#   bridge   — normale podman (Linux rootless, podman machine op macOS/Windows). Adressering en
#              poorten gelijk aan de Docker-stack; alleen image-namen en SELinux-labels wijken af.
#   hostnet  — vangnet voor omgevingen zonder bruikbaar bridge-netwerk, bijvoorbeeld podman-in-een-
#              container: `/proc/sys` is daar read-only (netavark kan geen bridge opzetten) en
#              aardvark-dns ontbreekt vaak, dus container-DNS resolvet niet. Alle containers delen
#              dan de netns van de aanroeper en praten over 127.0.0.1. Deze modus stapelt een derde
#              overlay met `!reset`-velden en vereist daarmee een compose die de Compose-spec van
#              2024 of later kent (docker compose v2.24+); podman-compose kent `!reset` niet.
#
# Gebruik:
#   demo/podman-up.sh                 # modus automatisch bepalen
#   MODUS=hostnet demo/podman-up.sh   # forceren
#   DEMO_HOST=10.0.0.5 demo/podman-up.sh
#
# DEMO_HOST is het adres waarop je de demo benadert (voor de CORS-allowlist van de uitvraag);
# default localhost.
set -euo pipefail

WORTEL="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$WORTEL"

export DEMO_HOST="${DEMO_HOST:-localhost}"
export DEMO_MAGAZIJN_STUBS="${DEMO_MAGAZIJN_STUBS:-12}"

# --- compose-commando en podman-socket bepalen ------------------------------------------------

if [ -z "${DOCKER_HOST:-}" ]; then
    SOCK="${XDG_RUNTIME_DIR:-/run/user/$(id -u)}/podman/podman.sock"

    if [ ! -S "$SOCK" ]; then
        echo "Podman-API-socket ontbreekt ($SOCK) — starten…"
        mkdir -p "$(dirname "$SOCK")"
        podman system service --time=0 "unix://$SOCK" >/dev/null 2>&1 &
        for _ in $(seq 1 20); do [ -S "$SOCK" ] && break; sleep 0.5; done

        [ -S "$SOCK" ] || { echo "podman system service kwam niet omhoog ($SOCK)" >&2; exit 1; }
    fi

    export DOCKER_HOST="unix://$SOCK"
fi

if command -v docker-compose >/dev/null 2>&1; then
    COMPOSE_BIN=(docker-compose)
elif docker compose version >/dev/null 2>&1; then
    COMPOSE_BIN=(docker compose)
elif command -v podman-compose >/dev/null 2>&1; then
    COMPOSE_BIN=(podman-compose)
else
    echo "Geen compose-implementatie gevonden (docker-compose, docker compose of podman-compose)." >&2
    exit 1
fi

# --- modus bepalen ----------------------------------------------------------------------------

kan_bridge() {
    local net="fbs-netprobe-$$" ok=1

    podman network create "$net" >/dev/null 2>&1 || return 1

    # Bridge én naamresolutie moeten werken: zonder aardvark-dns start een container wel, maar
    # resolvet geen enkele service-naam en faalt de hele stack pas ná het opstarten.
    if podman run -d --rm --name "${net}-doel" --network "$net" \
           docker.io/library/alpine:3.20 sleep 30 >/dev/null 2>&1; then

        podman run --rm --network "$net" docker.io/library/alpine:3.20 \
            getent hosts "${net}-doel" >/dev/null 2>&1 && ok=0

        podman rm -f "${net}-doel" >/dev/null 2>&1 || true
    fi

    podman network rm "$net" >/dev/null 2>&1 || true

    return $ok
}

MODUS="${MODUS:-}"

if [ -z "$MODUS" ]; then
    echo "[0/3] netwerkmodus bepalen"

    if kan_bridge; then
        MODUS=bridge
    else
        MODUS=hostnet
    fi

    echo "  → $MODUS"
fi

echo "[1/3] artefacten genereren"
"$WORTEL/demo/podman-prepare.sh" "$MODUS"

# --- starten ----------------------------------------------------------------------------------

wacht_op() {
    local naam="$1" poging=0
    shift

    until "$@" >/dev/null 2>&1; do
        poging=$((poging + 1))

        if [ "$poging" -gt 90 ]; then
            echo "TIMEOUT: $naam kwam niet omhoog" >&2
            exit 1
        fi

        sleep 2
    done

    echo "  ✓ $naam"
}

if [ "$MODUS" = "bridge" ]; then
    echo "[2/3] stack starten (bridge)"

    # Healthchecks en depends_on-condities uit compose.yaml doen hier het wachtwerk.
    "${COMPOSE_BIN[@]}" -f compose.yaml -f compose.podman.yaml --profile demo up -d
else
    echo "[2/3] infra starten (hostnet)"

    C=("${COMPOSE_BIN[@]}" -f compose.yaml -f compose.podman.yaml -f compose.podman-hostnet.yaml
       --profile demo)

    # Geen healthchecks in deze variant: podman draait die via systemd-timers, en juist in de
    # omgevingen die hostnet nodig hebben ontbreekt systemd — een healthcheck blijft dan eeuwig
    # 'starting' en `condition: service_healthy` loopt vast. Vandaar de expliciete wachtlus.
    "${C[@]}" up -d redis postgres-a postgres-b clickhouse profiel-service magazijn-a magazijn-b \
        aanmeld-stub notificatie-stub magazijn-stubs toxiproxy

    wacht_op "redis"      "${C[@]}" exec -T redis      redis-cli ping
    wacht_op "postgres-a" "${C[@]}" exec -T postgres-a pg_isready -U berichtenmagazijn -d berichtenmagazijn
    wacht_op "postgres-b" "${C[@]}" exec -T postgres-b pg_isready -U berichtenmagazijn -d berichtenmagazijn -p 5433

    # Niet alleen of de admin-API leeft: met een niet-geladen proxies.json start Toxiproxy
    # gezond op met NUL proxies, geeft `GET /proxies` een lege `{}` met status 200, en is de
    # hele keten dood terwijl elke probe groen is.
    wacht_op "toxiproxy"  bash -c 'curl -sf --max-time 3 http://127.0.0.1:8474/proxies | grep -q magazijn-a'

    "${C[@]}" up -d berichtenmagazijn-a berichtenmagazijn-b berichtenuitvraag demo-console
fi

echo "[3/3] wachten op de services"
wacht_op "magazijn-a" curl -sf --max-time 3 http://127.0.0.1:8090/q/health/ready
wacht_op "magazijn-b" curl -sf --max-time 3 http://127.0.0.1:8091/q/health/ready
wacht_op "uitvraag"   curl -sf --max-time 3 http://127.0.0.1:8086/q/health/ready
wacht_op "console"    curl -sf --max-time 3 http://127.0.0.1:8095/

echo
echo "Draait ($MODUS). Bedieningspaneel: http://${DEMO_HOST}:8095/"
echo "                 Berichtenbox:    http://${DEMO_HOST}:8095/berichtenbox.html"
