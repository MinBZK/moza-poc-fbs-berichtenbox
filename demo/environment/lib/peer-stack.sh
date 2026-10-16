#!/usr/bin/env bash
# shellcheck source-path=SCRIPTDIR
# Start, bevraagt of breekt de lokale harness van één FSC-peer af, in de modus die op deze machine
# werkt. Elke peer roept dit aan via zijn eigen `deploy/local/up.sh`:
#
#   deploy/local/up.sh [up]              # modus bepalen, starten en wachten tot alles gezond is
#   MODUS=hostnet deploy/local/up.sh     # forceren (docker, bridge of hostnet)
#   deploy/local/up.sh down [-v]         # afbreken, met de bestanden van de laatste start
#   deploy/local/up.sh ps                # status
#   deploy/local/up.sh compose <args…>   # elk ander compose-commando, bv. `logs -f router`
#
# Welke modus wat betekent en hoe de keuze valt, staat in demo/lib/podman-modus.sh. Per modus:
#
#   docker   compose.yaml
#   bridge   compose.yaml + compose.podman.yaml
#   hostnet  compose.yaml + compose.podman.yaml + compose.podman-hostnet.yaml
#
# De modus van de laatste start staat in deploy/local/.modus (gitignored). Een compose-commando met
# minder bestanden rendert de services opnieuw uit de basis en zet de stack stilletjes terug naar
# bridge; via dit script kan dat niet.
#
# De federatie (demo/environment/federatie/) start de peers zelf, met haar eigen vierde laag. Een
# peer die zo draait, start dit script niet opnieuw: dat zou hem zonder die laag hercreëren.
set -euo pipefail

LOKAAL="$(cd "${1:?usage: peer-stack.sh <deploy/local-map> [up|down|ps|compose …]}" && pwd)"
shift
ENVDIR="$(cd "$LOKAAL/../../.." && pwd)"
PEER="$(basename "$(cd "$LOKAAL/../.." && pwd)")"

# shellcheck source=../../lib/podman-modus.sh
. "$ENVDIR/../lib/podman-modus.sh"
# shellcheck source=fsc-harness.sh
. "$ENVDIR/lib/fsc-harness.sh"

MODUS_BESTAND="$LOKAAL/.modus"
START="demo/environment/$PEER/deploy/local/up.sh"
COMMANDO="${1:-up}"
if [ "$#" -gt 0 ]; then shift; fi

case "$COMMANDO" in
    up|down|ps|compose) ;;
    *)
        echo "Onbekend commando '$COMMANDO'; kies up, down, ps of compose <args…>." >&2
        exit 2
        ;;
esac

if [ "$COMMANDO" = "up" ] && [ "$#" -gt 0 ]; then
    echo "\`up\` neemt geen argumenten; gebruik \`compose up …\` voor een gerichte start." >&2
    exit 2
fi

# Zonder .env faalt compose op `${PKI_DIR:?}` met een melding over een variabele, niet over de
# ontbrekende stap; en zonder certs start elke container die /pki mount en sterft hij bij boot.
if [ ! -f "$LOKAAL/.env" ]; then
    echo "FOUT: $LOKAAL/.env ontbreekt. Maak hem aan vanuit de peer-root:" >&2
    echo "  cp deploy/local/.env.example deploy/local/.env" >&2
    echo "  printf 'HOST_UID=%s\nHOST_GID=%s\n' \"\$(id -u)\" \"\$(id -g)\" >> deploy/local/.env" >&2
    exit 1
fi

# De basis draait ook op Docker; de podman-modi zijn overlays erop. Podman gaat voor zodra het op
# PATH staat — Docker kiezen naast podman gaat via DOCKER_HOST (zie modus_engine).
# shellcheck disable=SC2034  # gelezen door modus_engine en modus_past_op_engine.
MODUS_DOCKER_TOEGESTAAN=1
modus_gereedschap
modus_engine
modus_compose_kiezen

if [ "$COMMANDO" = "up" ]; then
    echo "netwerkmodus bepalen"
    modus_bepalen docker bridge hostnet
else
    modus_teruglezen "$MODUS_BESTAND" "$START"
fi

C=("${COMPOSE_BIN[@]}" -f "$LOKAAL/compose.yaml")

case "$MODUS" in
    bridge) C+=(-f "$LOKAAL/compose.podman.yaml") ;;
    hostnet) C+=(-f "$LOKAAL/compose.podman.yaml" -f "$LOKAAL/compose.podman-hostnet.yaml") ;;
esac

case "$COMMANDO" in
    down)
        "${C[@]}" down "$@"
        rm -f "$MODUS_BESTAND"
        exit 0
        ;;
    ps)
        exec "${C[@]}" ps "$@"
        ;;
    compose)
        exec "${C[@]}" "$@"
        ;;
esac

# --- up ---------------------------------------------------------------------------------------

PROJECT="$(fsc_compose_project "$LOKAAL/compose.yaml")"

# Containers van dit project zonder vastgelegde modus zijn buiten dit script gestart — met de hand,
# of door de federatie met haar eigen laag. `up` zou ze hercreëren uit een andere set bestanden.
if [ ! -f "$MODUS_BESTAND" ]; then
    if ! bestaand="$("${C[@]}" ps -aq 2>/dev/null)"; then
        bestaand=""
    fi

    if [ -n "$bestaand" ]; then
        echo "FOUT: project '$PROJECT' heeft al containers, maar niet gestart via dit script." >&2
        echo "      Draait de federatie: gebruik demo/environment/federatie/federatie.sh." >&2
        echo "      Anders eerst afbreken met de bestanden waarmee je startte, of met" >&2
        echo "      \`MODUS=<modus> $START down\`." >&2
        exit 1
    fi
fi

# PKI_DIR is relatief aan de projectmap (deploy/local), net als in compose zelf.
pki="$(fsc_compose_env_lees "$LOKAAL/.env" PKI_DIR)"
case "$pki" in
    /*) ;;
    *) pki="$LOKAAL/$pki" ;;
esac

if [ -z "$pki" ] || [ ! -d "$pki/out" ]; then
    echo "FOUT: geen certs onder PKI_DIR (${pki:-<niet gezet>}/out). Genereer ze eerst (zie pki/README.md):" >&2
    echo "  cd demo/environment/$PEER/pki && ./init-ca.sh && ./issue.sh && ./gen-crl.sh && ./verify.sh" >&2
    exit 1
fi

# De router bindt :443. In bridge zet de basis daarvoor een sysctl per container; in een gedeelde
# netns mag een container dat niet, en geldt de waarde van de aanroeper. Hier controleren en niet
# afwachten: anders sterft alleen de router, op `bind 127.0.0.1:443: Permission denied`.
if [ "$MODUS" = "hostnet" ]; then
    poortgrens="$(cat /proc/sys/net/ipv4/ip_unprivileged_port_start 2>/dev/null || echo onbekend)"

    if [ "$poortgrens" = "onbekend" ] || [ "$poortgrens" -gt 443 ]; then
        echo "FOUT: hostnet vraagt net.ipv4.ip_unprivileged_port_start <= 443 (nu: $poortgrens)." >&2
        echo "      De router bindt :443 in de netns van deze shell. Zet hem met" >&2
        echo "      \`sudo sysctl -w net.ipv4.ip_unprivileged_port_start=0\`, of start in bridge." >&2
        exit 1
    fi
fi

modus_render_controleren "${C[@]}"

# Vóór de eerste container: ook een start die halverwege faalt, moet met `down` op te ruimen zijn.
modus_onthouden "$MODUS_BESTAND"

echo "$PEER starten ($MODUS)"
"${C[@]}" up -d

# `up -d` geeft 0 zodra de containers gestart zijn; managers en migrate-jobs hebben daarna nog tijd
# nodig. smoke-services.sh toetst precies wat "klaar" betekent (alles draait, elke migrate-job met
# 0 afgesloten) en noemt wat niet klopt. Begrensd, want een crashlus wordt hier nooit vanzelf groen.
WACHTTIJD="${WACHTTIJD:-300}"
eind=$((SECONDS + WACHTTIJD))

while :; do
    if uitvoer="$("$TIMEOUT_BIN" 60 bash "$LOKAAL/smoke-services.sh" 2>&1)"; then
        break
    fi

    if [ "$SECONDS" -ge "$eind" ]; then
        echo "TIMEOUT: $PEER werd binnen ${WACHTTIJD}s niet gezond. Laatste controle:" >&2
        printf '%s\n' "$uitvoer" >&2
        exit 1
    fi

    sleep 5
done

printf '%s\n' "$uitvoer" | tail -n1
echo
echo "Draait ($MODUS). Volgende stap: demo/environment/$PEER/deploy/local/run-smokes.sh"
echo "Afbreken:                       $START down -v"
