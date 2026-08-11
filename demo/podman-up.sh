#!/usr/bin/env bash
# Start de demo-stack onder podman en kiest zelf de werkbare netwerkmodus.
#
#   bridge   — normale podman (Linux rootless, podman machine op macOS/Windows). Adressering en
#              poorten gelijk aan de Docker-stack; alleen image-namen en SELinux-labels wijken af.
#   hostnet  — vangnet voor omgevingen zonder bruikbaar bridge-netwerk, bijvoorbeeld podman-in-een-
#              container: `/proc/sys` is daar read-only (netavark kan geen bridge opzetten) en
#              aardvark-dns ontbreekt vaak, dus container-DNS resolvet niet. Alle containers delen
#              dan de netns van de aanroeper en praten over 127.0.0.1.
#
# In hostnet komt er een derde compose-bestand bovenop de basis en de podman-overlay. Dat bestand
# heeft geen healthchecks (zie de reden daar), dus start die modus uitsluitend via dit script: een
# kale `up -d` meldt daar succes zodra de containers gestart zijn, ook als Postgres nog
# initialiseert. In bridge doen de healthchecks uit compose.yaml dat werk wél.
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

# Doorgeven aan compose én aan de generator vanaf één plek; beide hebben hun eigen default.
export DEMO_MAGAZIJN_STUBS="${DEMO_MAGAZIJN_STUBS:-12}"

PROBE_IMAGE=docker.io/library/alpine:3.20
DEMO_IMAGES=(
    localhost/fbs-demo/fbs-berichtenmagazijn:demo
    localhost/fbs-demo/fbs-berichtenuitvraag:demo
    localhost/fbs-demo/fbs-demo-console:demo
)

# Best-effort preflight: botst een poort in de gedeelde netns, dan start die container niet en
# beantwoordt de reeds draaiende host-service de probe alsof alles goed ging. De lijst is niet
# uitputtend — hij dekt wat we kennen uit compose en toxiproxy/proxies.json, plus de vier poorten
# die ClickHouse zelf opent (8123 http, 9000 native, 9004 mysql, 9005 postgres, 9009 interserver).
HOSTNET_POORTEN=(5432 5433 6379 8081 8082 8083 8084 8086 8089 8090 8091 8092 8095 8123
                 9000 9004 9005 9009 16379 18084 18086 18089 18090 18091)

# --- gereedschap en podman-socket bepalen -----------------------------------------------------

for gereedschap in podman curl; do
    command -v "$gereedschap" >/dev/null 2>&1 || {
        echo "$gereedschap niet gevonden op PATH." >&2
        exit 1
    }
done

# macOS levert geen `timeout`; met coreutils heet hij `gtimeout`. Zonder deze check faalt elke
# probe pas na de volledige deadline op een 'command not found' die niemand verwacht.
TIMEOUT_BIN="$(command -v timeout || command -v gtimeout || true)"

[ -n "$TIMEOUT_BIN" ] || {
    echo "timeout(1) ontbreekt (macOS: brew install coreutils)." >&2
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
        SERVICE_LOG="$(mktemp)"
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

# `podman` leest DOCKER_HOST niet (dat is CONTAINER_HOST), compose wél. Wijst DOCKER_HOST naar een
# andere engine, dan zouden de podman-checks hieronder een andere machine beoordelen dan waarop de
# stack landt; ze worden dan overgeslagen in plaats van een verkeerd antwoord te geven.
PODMAN_RUNTIME=0
case "$DOCKER_HOST" in
    *podman*) PODMAN_RUNTIME=1 ;;
esac

if [ "$PODMAN_RUNTIME" -eq 0 ]; then
    echo "Let op: DOCKER_HOST=$DOCKER_HOST wijst niet naar een podman-socket."
    echo "  De image- en netwerkcontroles die podman rechtstreeks bevragen worden overgeslagen."
fi

# `docker compose` (v2) eerst: hostnet gebruikt `!reset`, dat pas v2.24.4 kent. Een los
# `docker-compose` op PATH kan nog de oude v1 zijn.
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

# --- modus bepalen ----------------------------------------------------------------------------

kan_bridge() {
    local net="fbs-netprobe-$$-${RANDOM}" log ok=1

    log="$(mktemp)"

    probe_opruimen() {
        podman rm -f "${net}-doel" >/dev/null 2>&1 || true
        podman network rm "$net" >/dev/null 2>&1 || true
        rm -f "$log"
    }

    # Probe-netwerken en -containers stapelen zich anders op in de podman-state. De signaalvariant
    # sluit zelf af: zonder `exit` slikt de handler het signaal en loopt het script gewoon door.
    trap 'probe_opruimen; trap - RETURN INT TERM; exit 130' INT TERM
    trap 'probe_opruimen; trap - RETURN INT TERM' RETURN

    # Het probe-image apart binnenhalen: een registry-fout (rate limit, air-gapped, proxy) zegt
    # niets over de bruikbaarheid van de bridge, maar zou anders als 'geen bridge' tellen.
    if ! podman image exists "$PROBE_IMAGE" && ! podman pull -q "$PROBE_IMAGE" >"$log" 2>&1; then
        echo "  probe-image $PROBE_IMAGE niet beschikbaar: $(tail -1 "$log")" >&2
        echo "  Modus niet vast te stellen; kies expliciet MODUS=bridge of MODUS=hostnet." >&2
        probe_opruimen
        exit 1
    fi

    if ! podman network create "$net" >"$log" 2>&1; then
        echo "  bridge niet bruikbaar: $(tail -1 "$log")"
        return 1
    fi

    # Bridge én naamresolutie moeten werken: zonder aardvark-dns start een container wel, maar
    # resolvet geen enkele service-naam en faalt de hele stack pas ná het opstarten.
    if podman run -d --rm --name "${net}-doel" --network "$net" \
           "$PROBE_IMAGE" sleep 60 >"$log" 2>&1; then

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
        echo "  bridge staat, maar een container starten erop lukt niet: $(tail -1 "$log")"
    fi

    return $ok
}

MODUS="${MODUS:-}"

if [ -z "$MODUS" ]; then
    echo "[1/4] netwerkmodus bepalen"

    # Buiten Linux draait podman in een VM; een gedeelde netns is dan die van de VM en niet die
    # van de host, zodat er zonder gepubliceerde poorten niets doorkomt. Hostnet is daar dus geen
    # bruikbaar vangnet en wordt nooit automatisch gekozen. Zonder podman-runtime is de probe
    # betekenisloos, dus dan houden we het ook op bridge.
    if [ "$(uname -s)" != "Linux" ] || [ "$PODMAN_RUNTIME" -eq 0 ]; then
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

C=("${COMPOSE_BIN[@]}" -f compose.yaml -f compose.podman.yaml)

if [ "$MODUS" = "hostnet" ]; then
    C+=(-f compose.podman-hostnet.yaml)
fi

C+=(--profile demo)

# Toets het gedrag, niet het versienummer: `version --short` geeft bij een externe compose-provider
# de versie van de provider (podman) terug en zegt dan niets over de ondersteunde spec. Struikelt
# een implementatie over `!reset`, dan blijkt dat hier — vóór er iets gestart is.
if ! MERGEFOUT="$("${C[@]}" config -q 2>&1)"; then
    echo "Deze compose kan de gestapelde bestanden niet verwerken:" >&2
    printf '%s\n' "$MERGEFOUT" >&2

    if [ "$MODUS" = "hostnet" ]; then
        echo "hostnet gebruikt \`!reset\`; dat vraagt compose v2.24.4+ en kent podman-compose niet." >&2
    fi

    exit 1
fi

echo "[2/4] artefacten genereren"
"$WORTEL/demo/podman-prepare.sh" "$MODUS"

# --- starten ----------------------------------------------------------------------------------

ontbrekende_images() {
    local image

    for image in "${DEMO_IMAGES[@]}"; do
        podman image exists "$image" || echo "$image"
    done
}

if [ "$PODMAN_RUNTIME" -eq 1 ]; then
    ONTBREEKT=()

    # Geen `mapfile`: die bestaat pas vanaf bash 4 en macOS levert bash 3.2 als /bin/bash.
    while IFS= read -r image; do ONTBREEKT+=("$image"); done < <(ontbrekende_images)

    if [ "${#ONTBREEKT[@]}" -gt 0 ]; then
        echo "Deze demo-images ontbreken lokaal:" >&2
        printf '  %s\n' "${ONTBREEKT[@]}" >&2
        echo "Bouw ze eerst met jib (zie docs/demo-runbook.md §2)." >&2
        exit 1
    fi
fi

# Connect-probe via bash zelf: `ss`/`lsof` ontbreken juist in de kale images waarvoor hostnet
# bedoeld is, en een preflight die zichzelf stil overslaat is geen preflight.
poort_bezet() {
    (exec 3<>"/dev/tcp/127.0.0.1/$1") 2>/dev/null
}

# Alleen zinvol bij een koude start: draait het project al, dan zijn de eigen containers de
# bezetters en zou de preflight een gewone herstart blokkeren.
if [ "$MODUS" = "hostnet" ] && [ -z "$("${C[@]}" ps -q 2>/dev/null)" ]; then
    BEZET=""

    for poort in "${HOSTNET_POORTEN[@]}"; do
        if poort_bezet "$poort"; then BEZET="$BEZET $poort"; fi
    done

    if [ -n "$BEZET" ]; then
        echo "Deze poorten zijn al bezet en botsen met de gedeelde netns:$BEZET" >&2
        echo "Stop die processen, of draai de stack in bridge-modus (MODUS=bridge)." >&2
        exit 1
    fi
fi

vereis_draaiend() {
    local svc uit

    for svc in "$@"; do
        if ! uit="$("${C[@]}" ps -q --status running "$svc" 2>&1)"; then
            echo "FOUT: kon de status van '$svc' niet opvragen: $uit" >&2
            exit 1
        fi

        if [ -z "$uit" ]; then
            echo "FOUT: container '$svc' draait niet." >&2
            "${C[@]}" logs --tail=30 "$svc" >&2 || true
            exit 1
        fi
    done
}

# wacht_op <label> <compose-service|-> <commando…>
wacht_op() {
    local naam="$1" svc="$2" eind=$((SECONDS + 180)) uitvoer status
    shift 2

    while :; do
        if uitvoer="$("$TIMEOUT_BIN" 10 "$@" 2>&1)"; then
            break
        fi

        status=$?

        if [ "$SECONDS" -ge "$eind" ]; then
            echo "TIMEOUT: $naam kwam niet omhoog (laatste exit $status)." >&2

            if [ "$status" -eq 124 ]; then
                echo "  elke poging liep in de timeout van 10s" >&2
            fi

            if [ -n "$uitvoer" ]; then
                printf '%s\n' "$uitvoer" >&2
            fi

            if [ "$svc" != "-" ]; then
                "${C[@]}" logs --tail=30 "$svc" >&2 || true
            fi

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

# `up -d` geeft 0 zodra een container gestart is, ook als hij een seconde later crasht; daarom
# eerst de containerstatus, dan pas de functionele probes.
vereis_draaiend "${INFRA[@]}"

# Postgres-b luistert in hostnet op 5433 (PGPORT), in bridge intern gewoon op 5432; `pg_isready`
# draait binnen de container en volgt die keuze.
PGPORT_B=5432
[ "$MODUS" = "hostnet" ] && PGPORT_B=5433

wacht_op "redis"      redis      "${C[@]}" exec -T redis redis-cli ping
wacht_op "postgres-a" postgres-a "${C[@]}" exec -T postgres-a pg_isready -U berichtenmagazijn -d berichtenmagazijn
wacht_op "postgres-b" postgres-b "${C[@]}" exec -T postgres-b pg_isready -U berichtenmagazijn -d berichtenmagazijn -p "$PGPORT_B"

# Deze drie draaien vanaf de host: in bridge via de gepubliceerde poort, in hostnet via diezelfde
# poort in de gedeelde netns. Zonder ze meldt het script succes terwijl register,
# profielvoorkeuren of logboek onbereikbaar zijn.
wacht_op "clickhouse"     clickhouse      curl -sSf --max-time 3 http://127.0.0.1:8123/ping
wacht_op "profiel-service" profiel-service curl -sSf --max-time 3 http://127.0.0.1:8089/__admin/mappings
wacht_op "magazijn-stubs" magazijn-stubs  curl -sSf --max-time 3 http://127.0.0.1:8092/__admin/mappings

# Niet alleen of de admin-API leeft: met een niet-geladen proxies.json start Toxiproxy gezond op
# met NUL proxies, geeft `GET /proxies` een lege `{}` met status 200, en is de hele keten dood
# terwijl elke probe groen is. Geldt in beide modi.
wacht_op "toxiproxy" toxiproxy bash -c \
    'curl -sSf --max-time 3 http://127.0.0.1:8474/proxies | grep -q "\"magazijn-a\""'

# Een poortprobe bewijst niet dat ónze container antwoordde: crasht hij op een bezette poort, dan
# neemt de bestaande host-service het antwoord over. Daarom na afloop opnieuw de status.
vereis_draaiend "${INFRA[@]}"

echo "[4/4] services starten en afwachten"
"${C[@]}" up -d "${SERVICES[@]}"
vereis_draaiend "${SERVICES[@]}"

wacht_op "berichtenmagazijn-a" berichtenmagazijn-a curl -sSf --max-time 3 http://127.0.0.1:8090/q/health/ready
wacht_op "berichtenmagazijn-b" berichtenmagazijn-b curl -sSf --max-time 3 http://127.0.0.1:8091/q/health/ready
wacht_op "uitvraag"            berichtenuitvraag   curl -sSf --max-time 3 http://127.0.0.1:8086/q/health/ready
wacht_op "console"             demo-console        curl -sSf --max-time 3 http://127.0.0.1:8095/

vereis_draaiend "${SERVICES[@]}"

# Alle probes hierboven lopen over 127.0.0.1; is de demo op een ander adres aangekondigd, dan is
# dát het adres dat moet werken (firewall, of hostnet in een VM waar niets naar buiten komt).
if [ "$DEMO_HOST" != "localhost" ]; then
    wacht_op "console op $DEMO_HOST" demo-console curl -sSf --max-time 3 "http://${DEMO_HOST}:8095/"
fi

echo
echo "Draait ($MODUS). Bedieningspaneel: http://${DEMO_HOST}:8095/"
echo "                 Berichtenbox:    http://${DEMO_HOST}:8095/berichtenbox.html"
