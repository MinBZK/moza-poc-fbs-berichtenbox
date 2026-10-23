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

for gereedschap in bash mktemp tail head seq cat id mkdir rm grep sed sort dirname timeout awk; do
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
# `docker compose version` slaagt; `docker compose … config` levert STUB_RENDER (of de inhoud van
# STUB_RENDER_BESTAND) of faalt, en `docker compose … ps` geeft STUB_PS. `docker info` faalt met
# STUB_INFO_FAAL.
if [ "$1" = info ]; then
  [ -n "${STUB_INFO_FAAL:-}" ] && { echo "Cannot connect to the Docker daemon" >&2; exit 1; }
  exit 0
fi
[ "$1" = compose ] || exit 0
shift
case "$1" in version) exit 0 ;; esac
for arg in "$@"; do last="$arg"; [ "$arg" = ps ] && { printf '%s' "${STUB_PS:-}"; exit 0; }; done
if [ "$last" = config ]; then
  [ -n "${STUB_RENDER_FAAL:-}" ] && { echo "yaml: unknown tag !reset" >&2; exit 15; }
  [ -n "${STUB_RENDER_BESTAND:-}" ] && { cat "$STUB_RENDER_BESTAND"; exit 0; }
  printf '%s\n' "${STUB_RENDER:-services: {}}"
fi
EOF

cat >"$met/podman" <<'EOF'
#!/bin/sh
# `podman --url <socket> info` faalt met STUB_URL_FAAL: de socket waar compose mee praat antwoordt niet.
if [ "$1" = --url ]; then
  [ "$3" = info ] || exit 0
  [ -n "${STUB_URL_FAAL:-}" ] && { echo "unable to connect to Podman socket: $2: no such file or directory" >&2; exit 125; }
  exit 0
fi
case "$1 $2" in
  "image exists") exit 1 ;;
  "pull -q") [ -n "${STUB_PULL_FAAL:-}" ] && { echo "toomanyrequests: rate limit" >&2; exit 125; }; exit 0 ;;
  "network create") [ -n "${STUB_NET_FAAL:-}" ] && { echo "netavark: failed to set autoconf sysctl" >&2; exit 1; }; exit 0 ;;
  "network rm"|"rm -f") exit 0 ;;
  "run -d") [ -n "${STUB_RUN_FAAL:-}" ] && { echo "crun: permission denied" >&2; exit 126; }; exit 0 ;;
  "run --rm") [ -n "${STUB_GETENT_FAAL:-}" ] && exit 2; exit 0 ;;
  "inspect "*) echo "${STUB_HEALTH:-healthy}"; exit 0 ;;
  "machine inspect") echo "${STUB_MACHINE_SOCK:-}"; exit 0 ;;
  "system service") echo "Error: cannot setup namespace using newuidmap" >&2; exit 125 ;;
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
# Beide opstellingen (demo/up.sh en peer-stack.sh) staan Docker toe en kennen dezelfde drie modi.
BEPAAL='MODUS_DOCKER_TOEGESTAAN=1; modus_engine; modus_bepalen docker bridge hostnet; echo "MODUS=$MODUS"'
# De federatie kent alleen hostnet; daarmee is een modus buiten de lijst te toetsen.
BEPAAL_BEPERKT='modus_engine; modus_bepalen bridge hostnet; echo "MODUS=$MODUS"'

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

# Rootless podman op Linux zonder DOCKER_HOST: de socket onder XDG_RUNTIME_DIR, of zelf starten.
socketmap="$werkmap/run-met-socket"
mkdir -p "$socketmap/podman"
python3 -c 'import socket, sys; socket.socket(socket.AF_UNIX).bind(sys.argv[1])' "$socketmap/podman/podman.sock"

STUB_ENV="XDG_RUNTIME_DIR=$socketmap" geval "$met" 'modus_engine; echo "DH=$DOCKER_HOST PR=$PODMAN_RUNTIME"'
verwacht "zonder DOCKER_HOST wordt de bestaande podman-socket gebruikt" 0 "DH=unix://$socketmap/podman/podman.sock PR=1"

case "$UITVOER" in
  *"ontbreekt"*) fout "een bestaande socket wordt toch opnieuw gestart — $UITVOER" ;;
  *) ok "een bestaande socket wordt niet opnieuw gestart" ;;
esac

STUB_ENV="XDG_RUNTIME_DIR=$werkmap/run-zonder-socket" geval "$met" 'modus_engine; echo "DH=$DOCKER_HOST"'
verwacht "een podman-service die niet start, stopt met de socket erbij" 1 "kwam niet omhoog ($werkmap/run-zonder-socket/podman/podman.sock)"

# --- modus_engine_bereikbaar ---

STUB_ENV="$PODMAN_SOCK" geval "$met" 'modus_engine; modus_engine_bereikbaar; echo BEREIKBAAR'
verwacht "een engine die antwoordt, laat de start door" 0 "BEREIKBAAR"

STUB_ENV="$DOCKER_SOCK STUB_INFO_FAAL=1" geval "$met" 'MODUS_DOCKER_TOEGESTAAN=1; modus_engine; modus_engine_bereikbaar; echo BEREIKBAAR'
verwacht "een engine die niet antwoordt, stopt met de melding van de engine" 1 "Cannot connect to the Docker daemon"

case "$UITVOER" in
  *BEREIKBAAR*) fout "na een onbereikbare engine loopt het script door — $UITVOER" ;;
  *) ok "na een onbereikbare engine stopt het script" ;;
esac

# `docker` als shim naar podman (podman-docker) slaagt lokaal, ook als de socket in DOCKER_HOST weg is.
STUB_ENV="$PODMAN_SOCK STUB_URL_FAAL=1" geval "$met" 'modus_engine; modus_engine_bereikbaar; echo BEREIKBAAR'
verwacht "bij podman telt de socket in DOCKER_HOST, niet een slagende docker info" 1 "no such file or directory"

case "$UITVOER" in
  *BEREIKBAAR*) fout "een weggevallen podman-socket laat de start door — $UITVOER" ;;
  *) ok "een weggevallen podman-socket stopt de start" ;;
esac

# --- modus_poortgrens ---

for geval_grens in "0:0" "443:0" "444:1" "1024:1" "abc:1" ":1"; do
  waarde="${geval_grens%%:*}"
  verwacht_status="${geval_grens##*:}"
  grensbestand="$werkmap/poortgrens"
  printf '%s\n' "$waarde" >"$grensbestand"

  STUB_ENV="MODUS_POORTGRENS_BESTAND=$grensbestand" geval "$met" 'modus_poortgrens; echo GRENS-OK'

  if [ "$verwacht_status" -eq 0 ]; then
    verwacht "ip_unprivileged_port_start=$waarde laat hostnet toe" 0 "GRENS-OK"
  else
    verwacht "ip_unprivileged_port_start='$waarde' wordt geweigerd" 1 "ip_unprivileged_port_start <= 443"
  fi
done

STUB_ENV="MODUS_POORTGRENS_BESTAND=$werkmap/bestaat-niet" geval "$met" 'modus_poortgrens; echo GRENS-OK'
verwacht "een onleesbare poortgrens wordt geweigerd als onbekend" 1 "(nu: onbekend)"

# --- modus_bepalen: automatisch ---

STUB_ENV="$PODMAN_SOCK" geval "$met" "$BEPAAL"
verwacht "werkende bridge met naamresolutie → bridge" 0 "MODUS=bridge"

STUB_ENV="$PODMAN_SOCK STUB_NET_FAAL=1" geval "$met" "$BEPAAL"
verwacht "geen bridge-netwerk → hostnet, met de oorzaak" 0 "MODUS=hostnet"

case "$UITVOER" in
  *"failed to set autoconf sysctl"*) ok "de netavark-melding wordt doorgegeven" ;;
  *) fout "de netavark-melding ontbreekt — $UITVOER" ;;
esac

STUB_ENV="$PODMAN_SOCK STUB_GETENT_FAAL=1" geval "$met" "$BEPAAL"
verwacht "bridge zonder naamresolutie → hostnet" 0 "MODUS=hostnet"

STUB_ENV="$PODMAN_SOCK STUB_RUN_FAAL=1" geval "$met" "$BEPAAL"
verwacht "bridge waarop geen container start → hostnet" 0 "MODUS=hostnet"

STUB_ENV="$PODMAN_SOCK STUB_HEALTH=starting" geval "$met" "$BEPAAL"
verwacht "werkende bridge, maar healthchecks lopen niet vanzelf → hostnet" 0 "MODUS=hostnet"

case "$UITVOER" in
  *"healthchecks niet vanzelf"*) ok "de healthcheck-oorzaak wordt genoemd" ;;
  *) fout "de healthcheck-oorzaak ontbreekt — $UITVOER" ;;
esac

STUB_ENV="$PODMAN_SOCK STUB_HEALTH=starting STUB_OS=Darwin" geval "$met" "$BEPAAL"
verwacht "buiten Linux geen healthcheck-probe: bridge" 0 "MODUS=bridge"

STUB_ENV="$PODMAN_SOCK STUB_NET_FAAL=1 STUB_OS=Darwin" geval "$met" "$BEPAAL"
verwacht "buiten Linux nooit automatisch hostnet, ook zonder bridge" 0 "MODUS=bridge"

STUB_ENV="$PODMAN_SOCK STUB_PULL_FAAL=1" geval "$met" "$BEPAAL"
verwacht "een onbereikbaar probe-image stopt in plaats van hostnet te kiezen" 1 "Modus niet vast te stellen"

STUB_ENV="$DOCKER_SOCK STUB_NET_FAAL=1" geval "$met" "$BEPAAL"
verwacht "op Docker: docker, zonder probe" 0 "MODUS=docker"

STUB_ENV="$DOCKER_SOCK" geval "$met" "$BEPAAL_BEPERKT"
verwacht "een opstelling zonder docker-modus op Docker: bridge, zonder probe" 0 "MODUS=bridge"

STUB_ENV="" geval "$zonder" "$BEPAAL"
verwacht "zonder podman: docker" 0 "MODUS=docker"

# --- modus_bepalen: opgegeven ---

# Een opgegeven bridge valt niet terug op hostnet, maar stopt met de oorzaak van de probe.
STUB_ENV="$PODMAN_SOCK STUB_NET_FAAL=1 MODUS=bridge" geval "$met" "$BEPAAL"
verwacht "MODUS=bridge zonder bridge-netwerk wordt geweigerd" 2 "MODUS=bridge kan hier niet werken"

case "$UITVOER" in
  *"failed to set autoconf sysctl"*) ok "de geweigerde bridge noemt de netavark-melding" ;;
  *) fout "de geweigerde bridge noemt de oorzaak niet — $UITVOER" ;;
esac

STUB_ENV="$PODMAN_SOCK STUB_GETENT_FAAL=1 MODUS=bridge" geval "$met" "$BEPAAL"
verwacht "MODUS=bridge zonder naamresolutie wordt geweigerd" 2 "resolven niet"

STUB_ENV="$PODMAN_SOCK STUB_RUN_FAAL=1 MODUS=bridge" geval "$met" "$BEPAAL"
verwacht "MODUS=bridge waarop geen container start, noemt de podman-melding" 2 "crun: permission denied"

STUB_ENV="$PODMAN_SOCK MODUS=bridge STUB_HEALTH=starting" geval "$met" "$BEPAAL"
verwacht "MODUS=bridge zonder lopende healthchecks wordt geweigerd" 2 "healthchecks niet vanzelf"

STUB_ENV="$PODMAN_SOCK MODUS=bridge STUB_NET_FAAL=1 STUB_OS=Darwin" geval "$met" "$BEPAAL"
verwacht "buiten Linux geen probe voor een opgegeven bridge" 0 "MODUS=bridge"

STUB_ENV="$PODMAN_SOCK MODUS=hostnet STUB_HEALTH=starting" geval "$met" "$BEPAAL"
verwacht "MODUS=hostnet heeft geen healthchecks nodig" 0 "MODUS=hostnet"

STUB_ENV="$PODMAN_SOCK MODUS=docker" geval "$met" "$BEPAAL_BEPERKT"
verwacht "een modus die deze opstelling niet kent, wordt geweigerd" 2 "kies uit: bridge hostnet"

STUB_ENV="$PODMAN_SOCK MODUS=Hostnet" geval "$met" "$BEPAAL"
verwacht "modusnamen zijn hoofdlettergevoelig" 2 "MODUS='Hostnet' bestaat hier niet"

STUB_ENV="$PODMAN_SOCK MODUS=docker" geval "$met" "$BEPAAL"
verwacht "MODUS=docker terwijl compose op podman landt" 2 "compose landt op podman"

STUB_ENV="$DOCKER_SOCK MODUS=hostnet" geval "$met" "$BEPAAL"
verwacht "een podman-modus terwijl compose op Docker landt" 2 "compose landt op Docker"

STUB_ENV="$PODMAN_SOCK MODUS=hostnet STUB_OS=Darwin" geval "$met" "$BEPAAL"
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

# --- modus_poorten_vrij ---

# Een echte listener: de controle maakt een verbinding, en die moet ergens aankomen.
luisteraar="$(python3 -c 'import socket; s=socket.socket(); s.bind(("127.0.0.1", 0)); print(s.getsockname()[1])')"
python3 -c 'import socket, sys, time
s = socket.socket(); s.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
s.bind(("127.0.0.1", int(sys.argv[1]))); s.listen(8); time.sleep(60)' "$luisteraar" &
luisteraar_pid=$!
trap 'kill "$luisteraar_pid" 2>/dev/null; rm -rf "$werkmap"' EXIT

for _ in $(seq 1 50); do
  (exec 3<>"/dev/tcp/127.0.0.1/$luisteraar") 2>/dev/null && break
  sleep 0.1
done

# Een gesloten poort: gebonden en weer losgelaten, dus vrij en zonder listener.
vrij="$(python3 -c 'import socket; s=socket.socket(); s.bind(("127.0.0.1", 0)); print(s.getsockname()[1])')"

POORTEN='modus_poorten_vrij docker compose -f x.yaml; echo POORTEN-OK'

STUB_ENV="STUB_RENDER=published:\"$vrij\"" geval "$met" "$POORTEN"
verwacht "een vrije gepubliceerde poort laat de start door" 0 "POORTEN-OK"

STUB_ENV="STUB_RENDER=published:\"$luisteraar\"" geval "$met" "$POORTEN"
verwacht "een bezette gepubliceerde poort stopt de start en noemt de poort" 1 "in gebruik: $luisteraar"

STUB_ENV="STUB_RENDER=published:\"$luisteraar\" STUB_PS=abc123" geval "$met" "$POORTEN"
verwacht "met eigen containers van het project geen poortcontrole" 0 "POORTEN-OK"

STUB_ENV="STUB_RENDER=services:{}" geval "$met" "$POORTEN"
verwacht "zonder poorten in de render niets te controleren" 0 "POORTEN-OK"

# In hostnet publiceert niets; de poorten staan in de configuratie van de services zelf. Per vorm
# één render met de bezette poort, plus één met alleen vrije poorten.
render="$werkmap/render.yaml"

hostnet_render() {
  printf '%s\n' "services:" "  svc:" "    network_mode: host" "$@" >"$render"
}

hostnet_render "    environment:" "      LISTEN_ADDRESS: 127.0.0.1:$luisteraar"
STUB_ENV="MODUS=hostnet STUB_RENDER_BESTAND=$render" geval "$met" "$POORTEN"
verwacht "hostnet: een bezet listen-adres stopt de start" 1 "in gebruik: $luisteraar"

hostnet_render "    environment:" "      DB_URL: jdbc:postgresql://127.0.0.1:$luisteraar/db"
STUB_ENV="MODUS=hostnet STUB_RENDER_BESTAND=$render" geval "$met" "$POORTEN"
verwacht "hostnet: een bezette poort in een URL stopt de start" 1 "in gebruik: $luisteraar"

hostnet_render "    environment:" "      PGPORT: \"$luisteraar\""
STUB_ENV="MODUS=hostnet STUB_RENDER_BESTAND=$render" geval "$met" "$POORTEN"
verwacht "hostnet: een bezette *PORT-variabele stopt de start" 1 "in gebruik: $luisteraar"

hostnet_render "    command:" "      - --port" "      - \"$luisteraar\""
STUB_ENV="MODUS=hostnet STUB_RENDER_BESTAND=$render" geval "$met" "$POORTEN"
verwacht "hostnet: een bezette --port stopt de start" 1 "in gebruik: $luisteraar"

hostnet_render "    command:" "      - -listen=127.0.0.1:$luisteraar"
STUB_ENV="MODUS=hostnet STUB_RENDER_BESTAND=$render" geval "$met" "$POORTEN"
verwacht "hostnet: een bezette poort in een commando-argument stopt de start" 1 "in gebruik: $luisteraar"

hostnet_render "    environment:" "      CORS_ORIGINS: http://127.0.0.1:$luisteraar" \
               "      LISTEN_ADDRESS: 127.0.0.1:$vrij" "      PGPORT: \"$vrij\""
STUB_ENV="MODUS=hostnet STUB_RENDER_BESTAND=$render" geval "$met" "$POORTEN"
verwacht "hostnet: een CORS-origin is geen listener, en vrije poorten laten de start door" 0 "POORTEN-OK"

# Een gepubliceerde poort telt in hostnet niet: daar bindt geen container op de host.
STUB_ENV="MODUS=hostnet STUB_RENDER=published:\"$luisteraar\"" geval "$met" "$POORTEN"
verwacht "hostnet: een published-regel telt niet als listener" 0 "POORTEN-OK"

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
STUB_ENV="$PODMAN_SOCK MODUS=bridge STUB_PULL_FAAL=1" geval "$met" "$BEPAAL"
verwacht "MODUS=bridge met een onbereikbaar probe-image noemt die oorzaak" 1 "probe-image"

case "$UITVOER" in
  *"zonder lopende healthchecks"*) fout "een pull-fout wordt als healthcheck-probleem gemeld — $UITVOER" ;;
  *) ok "een pull-fout wordt niet als healthcheck-probleem gemeld" ;;
esac

echo "ASSERTIES=$asserties"
exit "$mislukt"
