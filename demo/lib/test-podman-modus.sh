#!/usr/bin/env bash
# Unittests voor podman-modus.sh. Geen echte engine: een eigen PATH met stubs voor podman, docker en
# uname, gestuurd via STUB_*-variabelen. Elk geval draait in een subshell, want de bibliotheek
# eindigt bij een fout met `exit`.
set -euo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
LIB="$HERE/podman-modus.sh"

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

werkmap="$(mktemp -d)"
trap 'rm -rf "$werkmap"' EXIT

# Twee PATH's: één mét podman en docker, één zonder podman. Beide met alleen de gereedschappen die
# de bibliotheek gebruikt, zodat een echte engine op deze machine geen uitkomst kan beïnvloeden.
basis="$werkmap/basis"
met="$werkmap/met-podman"
zonder="$werkmap/zonder-podman"
mkdir -p "$basis" "$met" "$zonder"

for gereedschap in bash mktemp tail head seq cat id mkdir rm grep sed dirname timeout; do
  pad="$(command -v "$gereedschap")" || { echo "gereedschap $gereedschap ontbreekt voor de test" >&2; exit 1; }
  ln -s "$pad" "$basis/$gereedschap"
done

cat >"$basis/sleep" <<'EOF'
#!/bin/sh
exit 0
EOF

cat >"$basis/uname" <<'EOF'
#!/bin/sh
echo "${STUB_OS:-Linux}"
EOF

cat >"$basis/docker" <<'EOF'
#!/bin/sh
# `docker compose version` slaagt; `docker compose … config` levert STUB_RENDER of faalt.
[ "$1" = compose ] || exit 0
shift
case "$1" in version) exit 0 ;; esac
for arg in "$@"; do last="$arg"; done
if [ "$last" = config ]; then
  [ -n "${STUB_RENDER_FAAL:-}" ] && { echo "yaml: unknown tag !reset" >&2; exit 15; }
  printf '%s\n' "${STUB_RENDER:-services: {}}"
fi
EOF

cat >"$met/podman" <<'EOF'
#!/bin/sh
case "$1 $2" in
  "image exists") exit 1 ;;
  "pull -q") [ -n "${STUB_PULL_FAAL:-}" ] && { echo "toomanyrequests: rate limit" >&2; exit 125; }; exit 0 ;;
  "network create") [ -n "${STUB_NET_FAAL:-}" ] && { echo "netavark: failed to set autoconf sysctl" >&2; exit 1; }; exit 0 ;;
  "network rm"|"rm -f") exit 0 ;;
  "run -d") [ -n "${STUB_RUN_FAAL:-}" ] && { echo "crun: permission denied" >&2; exit 126; }; exit 0 ;;
  "run --rm") [ -n "${STUB_GETENT_FAAL:-}" ] && exit 2; exit 0 ;;
  "inspect "*) echo "${STUB_HEALTH:-healthy}"; exit 0 ;;
  "machine inspect") echo "${STUB_MACHINE_SOCK:-}"; exit 0 ;;
esac
exit 0
EOF

chmod +x "$basis/sleep" "$basis/uname" "$basis/docker" "$met/podman"

# geval <naam> <PATH-variant> <script>: draait het script na het sourcen, zet STATUS en UITVOER.
geval() {
  local pad="$1" script="$2"

  STATUS=0
  UITVOER="$(env -i HOME="$werkmap" XDG_RUNTIME_DIR="$werkmap" PATH="$pad:$basis" \
               ${STUB_ENV:-} "$basis/bash" -c ". '$LIB'; $script" 2>&1)" || STATUS=$?
}

verwacht() {
  local wat="$1" status="$2" naald="$3"

  if [ "$STATUS" -ne "$status" ]; then
    fout "$wat — exit $STATUS, verwacht $status. Uitvoer: $UITVOER"
    return
  fi

  case "$UITVOER" in
    *"$naald"*) ok "$wat" ;;
    *) fout "$wat — '$naald' ontbreekt in: $UITVOER" ;;
  esac
}

PODMAN_SOCK="DOCKER_HOST=unix://$werkmap/podman/podman.sock"
DOCKER_SOCK="DOCKER_HOST=unix:///var/run/docker.sock"
BEPAAL_DEMO='modus_engine; modus_bepalen bridge hostnet; echo "MODUS=$MODUS"'
BEPAAL_PEER='MODUS_DOCKER_TOEGESTAAN=1; modus_engine; modus_bepalen docker bridge hostnet; echo "MODUS=$MODUS"'

# --- modus_engine ---

STUB_ENV="$PODMAN_SOCK" geval "$met" 'modus_engine; echo "PR=$PODMAN_RUNTIME"'
verwacht "een podman-socket in DOCKER_HOST telt als podman" 0 "PR=1"

STUB_ENV="$DOCKER_SOCK" geval "$met" 'modus_engine; echo "PR=$PODMAN_RUNTIME"'
verwacht "een andere DOCKER_HOST telt niet als podman, en dat wordt gemeld" 0 "wijst niet naar een podman-socket"

STUB_ENV="$DOCKER_SOCK" geval "$met" 'MODUS_DOCKER_TOEGESTAAN=1; modus_engine; echo "PR=$PODMAN_RUNTIME"'
verwacht "waar Docker een modus is, is een Docker-socket geen waarschuwing waard" 0 "PR=0"

case "$UITVOER" in
  *"wijst niet naar"*) fout "waarschuwing bij toegestane Docker-engine — $UITVOER" ;;
  *) ok "geen waarschuwing bij een toegestane Docker-engine" ;;
esac

STUB_ENV="" geval "$zonder" 'MODUS_DOCKER_TOEGESTAAN=1; modus_engine; echo "PR=$PODMAN_RUNTIME"'
verwacht "zonder podman en met Docker toegestaan valt de keuze op Docker" 0 "PR=0"

STUB_ENV="" geval "$zonder" 'modus_engine; echo "PR=$PODMAN_RUNTIME"'
verwacht "zonder podman en zonder Docker-modus stopt het met de oorzaak" 1 "podman niet gevonden"

# Buiten Linux hoort de socket bij podman machine; een eigen `system service` starten kan daar niet.
machine_sock="$werkmap/machine/podman.sock"
mkdir -p "$(dirname "$machine_sock")"
python3 -c 'import socket,sys; socket.socket(socket.AF_UNIX).bind(sys.argv[1])' "$machine_sock"

STUB_ENV="STUB_OS=Darwin STUB_MACHINE_SOCK=$machine_sock" geval "$met" 'modus_engine; echo "PR=$PODMAN_RUNTIME DH=$DOCKER_HOST"'
verwacht "buiten Linux wordt de socket van podman machine gebruikt" 0 "PR=1 DH=unix://$machine_sock"

STUB_ENV="STUB_OS=Darwin" geval "$met" 'modus_engine; echo "PR=$PODMAN_RUNTIME"'
verwacht "buiten Linux zonder draaiende podman machine: stoppen met de oorzaak" 1 "podman machine start"

STUB_ENV="STUB_OS=Darwin STUB_MACHINE_SOCK=$werkmap/bestaat-niet.sock" geval "$met" 'modus_engine; echo "PR=$PODMAN_RUNTIME"'
verwacht "een opgegeven machine-socket die niet bestaat telt als niet draaiend" 1 "bestaat-niet.sock"

# --- modus_bepalen: automatisch ---

STUB_ENV="$PODMAN_SOCK" geval "$met" "$BEPAAL_DEMO"
verwacht "werkende bridge met naamresolutie → bridge" 0 "MODUS=bridge"

STUB_ENV="$PODMAN_SOCK STUB_NET_FAAL=1" geval "$met" "$BEPAAL_DEMO"
verwacht "geen bridge-netwerk → hostnet, met de oorzaak" 0 "MODUS=hostnet"

case "$UITVOER" in
  *"failed to set autoconf sysctl"*) ok "de netavark-melding wordt doorgegeven" ;;
  *) fout "de netavark-melding ontbreekt — $UITVOER" ;;
esac

STUB_ENV="$PODMAN_SOCK STUB_GETENT_FAAL=1" geval "$met" "$BEPAAL_DEMO"
verwacht "bridge zonder naamresolutie → hostnet" 0 "MODUS=hostnet"

STUB_ENV="$PODMAN_SOCK STUB_RUN_FAAL=1" geval "$met" "$BEPAAL_DEMO"
verwacht "bridge waarop geen container start → hostnet" 0 "MODUS=hostnet"

STUB_ENV="$PODMAN_SOCK STUB_HEALTH=starting" geval "$met" "$BEPAAL_DEMO"
verwacht "werkende bridge, maar healthchecks lopen niet vanzelf → hostnet" 0 "MODUS=hostnet"

case "$UITVOER" in
  *"healthchecks niet vanzelf"*) ok "de healthcheck-oorzaak wordt genoemd" ;;
  *) fout "de healthcheck-oorzaak ontbreekt — $UITVOER" ;;
esac

STUB_ENV="$PODMAN_SOCK STUB_HEALTH=starting STUB_OS=Darwin" geval "$met" "$BEPAAL_DEMO"
verwacht "buiten Linux geen healthcheck-probe: bridge" 0 "MODUS=bridge"

STUB_ENV="$PODMAN_SOCK STUB_NET_FAAL=1 STUB_OS=Darwin" geval "$met" "$BEPAAL_DEMO"
verwacht "buiten Linux nooit automatisch hostnet, ook zonder bridge" 0 "MODUS=bridge"

STUB_ENV="$PODMAN_SOCK STUB_PULL_FAAL=1" geval "$met" "$BEPAAL_DEMO"
verwacht "een onbereikbaar probe-image stopt in plaats van hostnet te kiezen" 1 "Modus niet vast te stellen"

STUB_ENV="$DOCKER_SOCK" geval "$met" "$BEPAAL_DEMO"
verwacht "demo-stack op een andere engine: bridge, zonder probe" 0 "MODUS=bridge"

STUB_ENV="$DOCKER_SOCK STUB_NET_FAAL=1" geval "$met" "$BEPAAL_PEER"
verwacht "peer op Docker: docker, zonder probe" 0 "MODUS=docker"

STUB_ENV="" geval "$zonder" "$BEPAAL_PEER"
verwacht "peer zonder podman: docker" 0 "MODUS=docker"

# --- modus_bepalen: opgegeven ---

STUB_ENV="$PODMAN_SOCK STUB_NET_FAAL=1 MODUS=bridge" geval "$met" "$BEPAAL_DEMO"
verwacht "een opgegeven modus wordt niet overruled door de probe" 0 "MODUS=bridge"

STUB_ENV="$PODMAN_SOCK MODUS=bridge STUB_HEALTH=starting" geval "$met" "$BEPAAL_DEMO"
verwacht "MODUS=bridge zonder lopende healthchecks wordt geweigerd" 2 "blijft de start hangen"

STUB_ENV="$PODMAN_SOCK MODUS=hostnet STUB_HEALTH=starting" geval "$met" "$BEPAAL_DEMO"
verwacht "MODUS=hostnet heeft geen healthchecks nodig" 0 "MODUS=hostnet"

STUB_ENV="$PODMAN_SOCK MODUS=docker" geval "$met" "$BEPAAL_DEMO"
verwacht "een modus die deze opstelling niet kent, wordt geweigerd" 2 "kies uit: bridge hostnet"

STUB_ENV="$PODMAN_SOCK MODUS=Hostnet" geval "$met" "$BEPAAL_PEER"
verwacht "modusnamen zijn hoofdlettergevoelig" 2 "MODUS='Hostnet' bestaat hier niet"

STUB_ENV="$PODMAN_SOCK MODUS=docker" geval "$met" "$BEPAAL_PEER"
verwacht "MODUS=docker terwijl compose op podman landt" 2 "compose landt op podman"

STUB_ENV="$DOCKER_SOCK MODUS=hostnet" geval "$met" "$BEPAAL_PEER"
verwacht "een podman-modus terwijl compose op Docker landt" 2 "compose landt op Docker"

STUB_ENV="$PODMAN_SOCK MODUS=hostnet STUB_OS=Darwin" geval "$met" "$BEPAAL_DEMO"
verwacht "hostnet buiten Linux wordt geweigerd" 2 "werkt alleen op Linux"

# --- modus_render_controleren ---

RENDER='modus_compose_kiezen; modus_render_controleren "${COMPOSE_BIN[@]}" -f x.yaml; echo RENDER-OK'

STUB_ENV="MODUS=hostnet STUB_RENDER_FAAL=1" geval "$met" "$RENDER"
verwacht "een compose die !reset niet kent: oorzaak en versie-eis" 1 'v2.24.4'

STUB_ENV="MODUS=bridge STUB_RENDER_FAAL=1" geval "$met" "$RENDER"
verwacht "een render die in bridge faalt, stopt met de compose-uitvoer" 1 "unknown tag"

case "$UITVOER" in
  *v2.24.4*) fout "de !reset-hint hoort niet bij bridge — $UITVOER" ;;
  *) ok "geen !reset-hint in bridge" ;;
esac

STUB_ENV="MODUS=hostnet STUB_RENDER=published:8080" geval "$met" "$RENDER"
verwacht "een gepubliceerde poort naast network_mode host stopt de start" 1 "gepubliceerde poort"

STUB_ENV="MODUS=bridge STUB_RENDER=published:8080" geval "$met" "$RENDER"
verwacht "in bridge horen gepubliceerde poorten er gewoon bij" 0 "RENDER-OK"

# --- modus_onthouden / modus_teruglezen / modus_voor_start ---

bestand="$werkmap/.modus"
# Na modus_engine, zodat de controle op de engine meedraait zoals in de echte scripts.
TERUG="modus_engine; modus_teruglezen '$bestand' up.sh docker bridge hostnet; echo \"MODUS=\$MODUS\""
START="modus_engine; modus_voor_start '$bestand' up.sh docker bridge hostnet; echo \"MODUS=\$MODUS\""

rm -f "$bestand"
STUB_ENV="$PODMAN_SOCK" geval "$met" "$TERUG"
verwacht "zonder vastgelegde modus en zonder MODUS: stoppen en zeggen hoe het wel kan" 2 "Start hem met \`up.sh\`"

STUB_ENV="$PODMAN_SOCK MODUS=hostnet" geval "$met" "$TERUG"
verwacht "zonder vastgelegde modus geldt een opgegeven MODUS" 0 "MODUS=hostnet"

STUB_ENV="$PODMAN_SOCK MODUS=hostnett" geval "$met" "$TERUG"
verwacht "zonder vastgelegde modus wordt een tikfout in MODUS geweigerd" 2 "MODUS='hostnett' bestaat hier niet"

STUB_ENV="$PODMAN_SOCK MODUS=docker" geval "$met" "$TERUG"
verwacht "zonder vastgelegde modus gaat een opgegeven MODUS ook langs de engine-controle" 2 "compose landt op podman"

STUB_ENV="MODUS=hostnet" geval "$met" "modus_onthouden '$bestand'; echo vastgelegd"
verwacht "modus_onthouden schrijft het bestand" 0 "vastgelegd"

if [ "$(wc -l <"$bestand" | tr -d ' ')" = "1" ]; then
  ok "zonder instellingen bevat het bestand alleen de modus"
else
  fout "zonder instellingen hoort het bestand één regel te hebben — $(cat "$bestand")"
fi

STUB_ENV="$PODMAN_SOCK" geval "$met" "$TERUG"
verwacht "een vastgelegde modus wordt teruggelezen" 0 "MODUS=hostnet"

STUB_ENV="$PODMAN_SOCK MODUS=hostnet" geval "$met" "$TERUG"
verwacht "een opgegeven MODUS gelijk aan de vastgelegde is goed" 0 "MODUS=hostnet"

STUB_ENV="$PODMAN_SOCK MODUS=bridge" geval "$met" "$TERUG"
verwacht "een opgegeven MODUS die afwijkt van de vastgelegde wordt geweigerd" 2 "gestart in hostnet"

STUB_ENV="$DOCKER_SOCK" geval "$met" "MODUS_DOCKER_TOEGESTAAN=1; $TERUG"
verwacht "een vastgelegde podman-modus terwijl compose nu op Docker landt" 2 "compose landt op Docker"

printf 'onzin\n' >"$bestand"
STUB_ENV="$PODMAN_SOCK" geval "$met" "$TERUG"
verwacht "een onbekende waarde in het bestand wordt geweigerd" 2 "MODUS='onzin' bestaat hier niet"

: >"$bestand"
STUB_ENV="$PODMAN_SOCK" geval "$met" "$TERUG"
verwacht "een leeg modusbestand telt als ontbrekend" 2 "niet vast te stellen"

# Instellingen naast de modus (de proeftuin-versie): leeg, één, meerdere.
STUB_ENV="MODUS=bridge" geval "$met" "modus_onthouden '$bestand' PROEFTUIN_IMAGE=ghcr.io/x:pr-1; modus_instelling '$bestand' PROEFTUIN_IMAGE"
verwacht "één instelling wordt vastgelegd en teruggelezen" 0 "ghcr.io/x:pr-1"

STUB_ENV="MODUS=bridge" geval "$met" "modus_onthouden '$bestand' A=1 PROEFTUIN_IMAGE=ghcr.io/x:pr-2 B=3; modus_instelling '$bestand' PROEFTUIN_IMAGE; modus_instelling '$bestand' B"
verwacht "tussen meerdere instellingen wordt de juiste gevonden" 0 $'ghcr.io/x:pr-2\n3'

STUB_ENV="MODUS=bridge" geval "$met" "modus_onthouden '$bestand'; echo \"[\$(modus_instelling '$bestand' PROEFTUIN_IMAGE)]\""
verwacht "een niet vastgelegde instelling is leeg" 0 "[]"

STUB_ENV="" geval "$met" "printf 'PROEFTUIN_IMAGE=fout\n' >'$bestand'; echo \"[\$(modus_instelling '$bestand' PROEFTUIN_IMAGE)]\""
verwacht "de modusregel zelf telt nooit als instelling" 0 "[]"

STUB_ENV="" geval "$met" "echo \"[\$(modus_instelling '$werkmap/bestaat-niet' PROEFTUIN_IMAGE)]\""
verwacht "zonder bestand is een instelling leeg" 0 "[]"

# modus_voor_start: een tweede `up` naast een draaiende stack zet hem niet om.
rm -f "$bestand"
STUB_ENV="$PODMAN_SOCK STUB_NET_FAAL=1" geval "$met" "$START"
verwacht "zonder vastgelegde modus bepaalt up hem opnieuw" 0 "MODUS=hostnet"

printf 'bridge\n' >"$bestand"
STUB_ENV="$PODMAN_SOCK STUB_NET_FAAL=1" geval "$met" "$START"
verwacht "met een vastgelegde modus houdt up die aan, ook als de probe nu anders zou kiezen" 0 "MODUS=bridge"

case "$UITVOER" in
  *"vastgelegd bij de vorige start"*) ok "up meldt dat de modus van de vorige start geldt" ;;
  *) fout "up meldt de vastgelegde modus niet — $UITVOER" ;;
esac

STUB_ENV="$PODMAN_SOCK MODUS=hostnet" geval "$met" "$START"
verwacht "up met een MODUS die afwijkt van een draaiende stack wordt geweigerd" 2 "gestart in bridge"

# Het probe-image hoort ook bij een opgegeven bridge eerst opgehaald te worden: anders leest een
# registry-fout als 'geen healthchecks' en wordt de gebruiker naar hostnet gestuurd.
STUB_ENV="$PODMAN_SOCK MODUS=bridge STUB_PULL_FAAL=1" geval "$met" "$BEPAAL_DEMO"
verwacht "MODUS=bridge met een onbereikbaar probe-image noemt die oorzaak" 1 "probe-image"

case "$UITVOER" in
  *"zonder lopende healthchecks"*) fout "een pull-fout wordt als healthcheck-probleem gemeld — $UITVOER" ;;
  *) ok "een pull-fout wordt niet als healthcheck-probleem gemeld" ;;
esac

echo "ASSERTIES=$asserties"
exit "$mislukt"
