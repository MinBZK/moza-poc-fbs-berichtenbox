#!/usr/bin/env bash
# Start de demo-stack onder podman en kiest zelf de werkbare netwerkmodus.
#
#   bridge   — normale podman (Linux rootless, podman machine op macOS/Windows). Adressering en
#              poorten gelijk aan de Docker-stack; image-namen, SELinux-labels en de CORS-origin
#              wijken af.
#   hostnet  — vangnet voor omgevingen zonder bruikbaar bridge-netwerk, bijvoorbeeld podman-in-een-
#              container: `/proc/sys` is daar read-only (netavark kan geen bridge opzetten) en
#              aardvark-dns ontbreekt vaak, dus container-DNS resolvet niet. Alle containers delen
#              dan de netns van de aanroeper en praten over 127.0.0.1. Deze modus stapelt een derde
#              overlay met `!reset`-velden en vereist daarmee compose v2.24.4 of nieuwer;
#              podman-compose kent `!reset` niet.
#
# Start de stack uitsluitend via dit script: de hostnet-overlay heeft geen healthchecks (podman
# voert die uit via systemd-timers, en juist daar waar die overlay nodig is draait geen systemd —
# een healthcheck blijft dan eeuwig 'starting' en `condition: service_healthy` loopt vast). Het
# wachten op de infra gebeurt daarom hieronder, niet in compose.
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

# Expliciet exporteren, niet aan de compose-default overlaten: de generator en de demo-console
# lezen dit getal onafhankelijk van elkaar en moeten hetzelfde aantal stubs zien.
export DEMO_MAGAZIJN_STUBS="${DEMO_MAGAZIJN_STUBS:-12}"

PROBE_IMAGE=docker.io/library/alpine:3.20
DEMO_IMAGES=(
    localhost/fbs-demo/fbs-berichtenmagazijn:demo
    localhost/fbs-demo/fbs-berichtenuitvraag:demo
    localhost/fbs-demo/fbs-demo-console:demo
)

# Poorten die de containers in één gedeelde netns zelf binden. Botst er één, dan start de
# container niet en is dat aan een poortprobe niet te zien — vandaar de preflight hieronder.
HOSTNET_POORTEN=(5432 5433 6379 8081 8082 8083 8084 8086 8089 8090 8091 8092 8095 8123 8474 9000
                 16379 18084 18086 18089 18090 18091)

# --- compose-commando en podman-socket bepalen ------------------------------------------------

command -v podman >/dev/null 2>&1 || {
    echo "podman niet gevonden op PATH." >&2
    exit 1
}

if [ -z "${DOCKER_HOST:-}" ]; then
    SOCK="${XDG_RUNTIME_DIR:-/run/user/$(id -u)}/podman/podman.sock"

    if [ ! -S "$SOCK" ]; then
        echo "Podman-API-socket ontbreekt ($SOCK) — starten…"
        mkdir -p "$(dirname "$SOCK")"

        # `--time=0` houdt de service draaien ná dit script, zodat een latere `… down` dezelfde
        # socket vindt. Het log blijft staan: zonder die uitvoer is een mislukte start (subuid,
        # storage.conf, bezette socket) niet te diagnosticeren.
        SERVICE_LOG="$(mktemp -t podman-service.XXXXXX.log)"
        podman system service --time=0 "unix://$SOCK" >"$SERVICE_LOG" 2>&1 &
        for _ in $(seq 1 20); do [ -S "$SOCK" ] && break; sleep 0.5; done

        [ -S "$SOCK" ] || {
            echo "podman system service kwam niet omhoog ($SOCK):" >&2
            tail -20 "$SERVICE_LOG" >&2
            exit 1
        }
    fi

    export DOCKER_HOST="unix://$SOCK"
fi

# `docker compose` (v2) eerst: hostnet gebruikt `!reset`, dat alleen v2.24.4+ kent. De v1-binary
# heet `docker-compose` en zou anders winnen op alfabetische toevalligheid.
if docker compose version >/dev/null 2>&1; then
    COMPOSE_BIN=(docker compose)
elif command -v docker-compose >/dev/null 2>&1; then
    COMPOSE_BIN=(docker-compose)
elif command -v podman-compose >/dev/null 2>&1; then
    COMPOSE_BIN=(podman-compose)
else
    echo "Geen compose-implementatie gevonden (docker compose, docker-compose of podman-compose)." >&2
    exit 1
fi

compose_versie() {
    "${COMPOSE_BIN[@]}" version --short 2>/dev/null | tr -d 'v' | head -1
}

# --- modus bepalen ----------------------------------------------------------------------------

kan_bridge() {
    local net="fbs-netprobe-$$-${RANDOM}" log ok=1

    log="$(mktemp -t netprobe.XXXXXX.log)"

    # Ook bij een onderbroken run opruimen: een achtergebleven netwerk laat `network create` bij
    # een volgende run falen en stuurt zo stil de modus-keuze.
    # shellcheck disable=SC2064  # $net/$log nu expanderen, niet bij het uitvoeren van de trap
    trap "podman rm -f '${net}-doel' >/dev/null 2>&1 || true
          podman network rm '$net' >/dev/null 2>&1 || true
          rm -f '$log'" RETURN INT TERM

    # Het probe-image apart binnenhalen: een registry-fout (rate limit, air-gapped, proxy) zegt
    # niets over de bruikbaarheid van de bridge, maar zou anders als 'geen bridge' tellen.
    if ! podman image exists "$PROBE_IMAGE" && ! podman pull -q "$PROBE_IMAGE" >"$log" 2>&1; then
        echo "  probe-image $PROBE_IMAGE niet beschikbaar: $(tail -1 "$log")" >&2
        echo "  Modus niet vast te stellen; kies expliciet MODUS=bridge of MODUS=hostnet." >&2
        exit 1
    fi

    if ! podman network create "$net" >"$log" 2>&1; then
        echo "  bridge niet bruikbaar: $(tail -1 "$log")"
        return 1
    fi

    # Bridge én naamresolutie moeten werken: zonder aardvark-dns start een container wel, maar
    # resolvet geen enkele service-naam en faalt de hele stack pas ná het opstarten.
    if podman run -d --rm --name "${net}-doel" --network "$net" \
           "$PROBE_IMAGE" sleep 60 >/dev/null 2>&1; then

        # Een paar pogingen: aardvark-dns publiceert het record niet altijd meteen, en één
        # vals-negatief zou de stack naar de riskantere modus duwen.
        for _ in 1 2 3; do
            podman run --rm --network "$net" "$PROBE_IMAGE" \
                getent hosts "${net}-doel" >/dev/null 2>&1 && { ok=0; break; }

            sleep 2
        done

        if [ "$ok" -ne 0 ]; then
            echo "  bridge staat, maar containernamen resolven niet (aardvark-dns?)"
        fi
    else
        echo "  bridge staat, maar een container starten erop lukt niet"
    fi

    return $ok
}

MODUS="${MODUS:-}"

if [ -z "$MODUS" ]; then
    echo "[1/4] netwerkmodus bepalen"

    # Buiten Linux draait podman in een VM; een gedeelde netns is dan die van de VM en niet die
    # van de host, zodat er zonder gepubliceerde poorten niets doorkomt. Hostnet is daar dus geen
    # bruikbaar vangnet en wordt nooit automatisch gekozen.
    if [ "$(uname -s)" != "Linux" ]; then
        MODUS=bridge
    elif kan_bridge; then
        MODUS=bridge
    else
        MODUS=hostnet
    fi

    echo "  → $MODUS"
else
    echo "[1/4] netwerkmodus: $MODUS (opgegeven)"
fi

if [ "$MODUS" = "hostnet" ]; then
    VERSIE="$(compose_versie || true)"

    # `!reset` in de hostnet-overlay bestaat pas vanaf compose v2.24.4; oudere implementaties
    # struikelen over de YAML-tag met een melding die niet naar de oorzaak wijst.
    if [ "${COMPOSE_BIN[0]}" = "podman-compose" ] ||
       [ -z "$VERSIE" ] ||
       [ "$(printf '%s\n2.24.4\n' "$VERSIE" | sort -V | head -1)" != "2.24.4" ]; then
        echo "hostnet vereist compose v2.24.4+ (voor \`!reset\`); gevonden: ${COMPOSE_BIN[*]} ${VERSIE:-onbekend}." >&2
        exit 1
    fi
fi

echo "[2/4] artefacten genereren"
"$WORTEL/demo/podman-prepare.sh" "$MODUS"

# --- starten ----------------------------------------------------------------------------------

C=("${COMPOSE_BIN[@]}" -f compose.yaml -f compose.podman.yaml)

if [ "$MODUS" = "hostnet" ]; then
    C+=(-f compose.podman-hostnet.yaml)
fi

C+=(--profile demo)

ontbrekende_images() {
    local image

    for image in "${DEMO_IMAGES[@]}"; do
        podman image exists "$image" || echo "$image"
    done
}

mapfile -t ONTBREEKT < <(ontbrekende_images)

if [ "${#ONTBREEKT[@]}" -gt 0 ]; then
    echo "Deze demo-images ontbreken lokaal:" >&2
    printf '  %s\n' "${ONTBREEKT[@]}" >&2
    echo "Bouw ze eerst met jib (zie docs/demo-runbook.md §2)." >&2
    exit 1
fi

if [ "$MODUS" = "hostnet" ]; then
    BEZET=""

    # Eén netns is één poortruimte: een lokale Postgres of Redis laat de container stilletjes
    # falen op 'address already in use', terwijl elke poortprobe daarna door díe host-service
    # beantwoord wordt.
    if command -v ss >/dev/null 2>&1; then
        for poort in "${HOSTNET_POORTEN[@]}"; do
            ss -ltnH "sport = :$poort" 2>/dev/null | grep -q . && BEZET="$BEZET $poort"
        done
    fi

    if [ -n "$BEZET" ]; then
        echo "Deze poorten zijn al bezet en botsen met de gedeelde netns:$BEZET" >&2
        echo "Stop die processen, of draai de stack in bridge-modus (MODUS=bridge)." >&2
        exit 1
    fi
fi

vereis_draaiend() {
    local svc

    for svc in "$@"; do
        if [ -z "$("${C[@]}" ps -q --status running "$svc" 2>/dev/null)" ]; then
            echo "FOUT: container '$svc' draait niet." >&2
            "${C[@]}" logs --tail=30 "$svc" >&2 || true
            exit 1
        fi
    done
}

wacht_op() {
    local naam="$1" eind=$((SECONDS + 180)) uitvoer
    shift

    while :; do
        # Elke poging begrensd: een hangende `compose exec` (socket weg, container in Created)
        # keert anders nooit terug en de deadline hieronder wordt dan nooit bereikt.
        if uitvoer="$(timeout 10 "$@" 2>&1)"; then
            break
        fi

        if [ "$SECONDS" -ge "$eind" ]; then
            echo "TIMEOUT: $naam kwam niet omhoog. Laatste uitvoer:" >&2
            printf '%s\n' "$uitvoer" >&2
            exit 1
        fi

        sleep 2
    done

    echo "  ✓ $naam"
}

INFRA=(redis postgres-a postgres-b clickhouse profiel-service magazijn-a magazijn-b
       aanmeld-stub notificatie-stub magazijn-stubs toxiproxy)
SERVICES=(berichtenmagazijn-a berichtenmagazijn-b berichtenuitvraag demo-console)

echo "[3/4] infra starten ($MODUS)"
"${C[@]}" up -d "${INFRA[@]}"

# `up -d` geeft 0 zodra een container gestart is, ook als hij een seconde later crasht — en in
# een gedeelde netns beantwoordt een reeds draaiende host-service de poortprobes alsof alles
# goed ging. Daarom eerst de containerstatus, dan pas de functionele probes.
vereis_draaiend "${INFRA[@]}"

# Postgres-b luistert in hostnet op 5433 (PGPORT), in bridge intern gewoon op 5432; `pg_isready`
# draait binnen de container en volgt die keuze.
PGPORT_B=5432
[ "$MODUS" = "hostnet" ] && PGPORT_B=5433

wacht_op "redis"      "${C[@]}" exec -T redis redis-cli ping
wacht_op "postgres-a" "${C[@]}" exec -T postgres-a pg_isready -U berichtenmagazijn -d berichtenmagazijn
wacht_op "postgres-b" "${C[@]}" exec -T postgres-b pg_isready -U berichtenmagazijn -d berichtenmagazijn -p "$PGPORT_B"

# De stubs en ClickHouse luisteren in bridge op hun gepubliceerde poort en in hostnet op dezelfde
# poort in de gedeelde netns, dus deze probes zijn modus-onafhankelijk. Zonder deze drie meldt het
# script succes terwijl de uitvraag zijn register, de profielvoorkeuren of het logboek niet bereikt.
wacht_op "clickhouse"     curl -sf --max-time 3 http://127.0.0.1:8123/ping
wacht_op "profiel-stub"   curl -sf --max-time 3 http://127.0.0.1:8089/__admin/mappings
wacht_op "magazijn-stubs" curl -sf --max-time 3 http://127.0.0.1:8092/__admin/mappings

# Niet alleen of de admin-API leeft: met een niet-geladen proxies.json start Toxiproxy gezond op
# met NUL proxies, geeft `GET /proxies` een lege `{}` met status 200, en is de hele keten dood
# terwijl elke probe groen is. Geldt in beide modi.
wacht_op "toxiproxy" bash -c \
    'curl -sf --max-time 3 http://127.0.0.1:8474/proxies | grep -q "\"magazijn-a\""'

echo "[4/4] services starten en afwachten"
"${C[@]}" up -d "${SERVICES[@]}"
vereis_draaiend "${SERVICES[@]}"

wacht_op "magazijn-a" curl -sf --max-time 3 http://127.0.0.1:8090/q/health/ready
wacht_op "magazijn-b" curl -sf --max-time 3 http://127.0.0.1:8091/q/health/ready
wacht_op "uitvraag"   curl -sf --max-time 3 http://127.0.0.1:8086/q/health/ready
wacht_op "console"    curl -sf --max-time 3 http://127.0.0.1:8095/

# Alle probes hierboven lopen over 127.0.0.1; is de demo op een ander adres aangekondigd, dan is
# dát het adres dat moet werken (firewall, of hostnet in een VM waar niets naar buiten komt).
if [ "$DEMO_HOST" != "localhost" ]; then
    wacht_op "console op $DEMO_HOST" curl -sf --max-time 3 "http://${DEMO_HOST}:8095/"
fi

echo
echo "Draait ($MODUS). Bedieningspaneel: http://${DEMO_HOST}:8095/"
echo "                 Berichtenbox:    http://${DEMO_HOST}:8095/berichtenbox.html"
