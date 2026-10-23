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

for gereedschap in bash mktemp tail head seq cat mkdir rm grep sed sort dirname timeout python3 stat awk env tr; do
  pad="$(command -v "$gereedschap")" || { echo "gereedschap $gereedschap ontbreekt voor de test" >&2; exit 1; }
  ln -s "$pad" "$basis/$gereedschap"
done

# Direct terug, tenzij een geval moet wachten op een stub die op de achtergrond werkt.
echte_sleep="$(command -v sleep)"
cat >"$basis/sleep" <<EOF
#!/bin/sh
[ -n "\${STUB_ECHTE_SLEEP:-}" ] && exec "$echte_sleep" "\$@"
exit 0
EOF

# `id -u` geeft STUB_UID als die gezet is: zo gaat een proces van deze gebruiker door voor dat van
# een ander, zonder dat er een vreemd proces nodig is (en dus ook als root).
echte_id="$(command -v id)"
cat >"$basis/id" <<EOF
#!/bin/sh
[ "\$1" = -u ] && [ -n "\${STUB_UID:-}" ] && { echo "\$STUB_UID"; exit 0; }
exec "$echte_id" "\$@"
EOF

# STUB_ANDERE_USERNS=<pid>: dat proces zit in een andere user namespace dan de aanroeper. Een echt
# proces daarin vraagt `unshare -U`, en dat staat niet elke runner toe.
echte_readlink="$(command -v readlink)"
cat >"$basis/readlink" <<EOF
#!/bin/sh
[ -n "\${STUB_ANDERE_USERNS:-}" ] && [ "\$1" = "/proc/\$STUB_ANDERE_USERNS/ns/user" ] && { echo 'user:[1]'; exit 0; }
exec "$echte_readlink" "\$@"
EOF

# `curl --unix-socket <pad> <url>`, gestuurd door markers naast de socket: `<pad>.dood` = niemand
# luistert (exit 7, verbinding geweigerd), `<pad>.traag` = timeout (exit 28). Met `-D` levert
# `/_ping` de headers: die van podman, met `<pad>.docker` die van Docker, met `<pad>.kleine-koppen`
# die van podman in kleine letters, en met `<pad>.typefout` een timeout. Elk ander pad dan `/_ping`
# geeft een timeout: zo valt het op als de bibliotheek weer `/version` gaat vragen.
cat >"$basis/curl" <<'EOF'
#!/bin/sh
koppen=""
while [ $# -gt 0 ]; do
  case "$1" in
    --unix-socket) sock="$2"; shift ;;
    -D) koppen=1; shift ;;
    http://*) url="$1" ;;
  esac
  shift
done
[ -e "$sock.dood" ] && exit 7
[ -e "$sock.traag" ] && exit 28
case "$url" in */_ping) ;; *) exit 28 ;; esac
if [ -n "$koppen" ]; then
  [ -e "$sock.typefout" ] && exit 28
  printf 'HTTP/1.1 200 OK\r\n'
  if [ -e "$sock.docker" ]; then
    printf 'Api-Version: 1.47\r\nServer: Docker/27.3.1 (linux)\r\n'
  elif [ -e "$sock.kleine-koppen" ]; then
    printf 'libpod-api-version: 5.4.2\r\n'
  else
    printf 'Api-Version: 1.41\r\nLibpod-Api-Version: 5.4.2\r\nServer: Libpod/5.4.2 (linux)\r\n'
  fi
  printf '\r\n'
  exit 0
fi
printf OK
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
  "system service")
    [ -n "${STUB_SERVICE_FAAL:-}" ] && { echo "Error: cannot setup namespace using newuidmap" >&2; exit 125; }
    # Met CONTAINER_HOST of CONTAINER_CONNECTION is podman een remote client zonder `system service`.
    [ -n "${CONTAINER_HOST:-}${CONTAINER_CONNECTION:-}" ] && { echo "Error: accepts at most 1 arg(s), received 2" >&2; exit 125; }
    s="${4#unix://}"
    rm -f "$s.dood"
    python3 -c 'import socket,sys; socket.socket(socket.AF_UNIX).bind(sys.argv[1])' "$s"
    exit 0 ;;
esac
exit 0
EOF

chmod +x "$basis/sleep" "$basis/id" "$basis/readlink" "$basis/curl" "$basis/uname" "$basis/docker" "$met/podman"

# Een extra map met alleen setsid, voor de tak die de service van de aanroepende shell losmaakt.
met_setsid="$werkmap/met-setsid"
mkdir -p "$met_setsid"
ln -s "$(command -v setsid)" "$met_setsid/setsid"

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

# Echte socketbestanden: modus_engine eist dat er op DOCKER_HOST iets antwoordt.
maak_socket() {
  mkdir -p "$(dirname "$1")"
  python3 -c 'import socket,sys; socket.socket(socket.AF_UNIX).bind(sys.argv[1])' "$1"
}

maak_socket "$werkmap/podman/podman.sock"
maak_socket "$werkmap/docker/docker.sock"
touch "$werkmap/docker/docker.sock.docker"
PODMAN_SOCK="DOCKER_HOST=unix://$werkmap/podman/podman.sock"
DOCKER_SOCK="DOCKER_HOST=unix://$werkmap/docker/docker.sock"
# Beide opstellingen (demo/up.sh en peer-stack.sh) staan Docker toe en kennen dezelfde drie modi.
BEPAAL='MODUS_DOCKER_TOEGESTAAN=1; modus_engine; modus_bepalen docker bridge hostnet; echo "MODUS=$MODUS"'
# De federatie kent alleen hostnet; daarmee is een modus buiten de lijst te toetsen.
BEPAAL_BEPERKT='modus_engine; modus_bepalen bridge hostnet; echo "MODUS=$MODUS"'

# --- modus_engine ---

STUB_ENV="$PODMAN_SOCK" geval "$met" 'modus_engine; echo "PR=$PODMAN_RUNTIME"'
verwacht "een podman-socket in DOCKER_HOST telt als podman" 0 "PR=1"

STUB_ENV="$DOCKER_SOCK" geval "$met" 'modus_engine; echo "PR=$PODMAN_RUNTIME"'
verwacht "een andere DOCKER_HOST telt niet als podman, en dat wordt gemeld" 0 "wijst niet naar een podman-engine"

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
maak_socket "$machine_sock"

STUB_ENV="STUB_OS=Darwin STUB_MACHINE_SOCK=$machine_sock" geval "$met" 'modus_engine; echo "PR=$PODMAN_RUNTIME DH=$DOCKER_HOST"'
verwacht "buiten Linux wordt de socket van podman machine gebruikt" 0 "PR=1 DH=unix://$machine_sock"

STUB_ENV="STUB_OS=Darwin" geval "$met" 'modus_engine; echo "PR=$PODMAN_RUNTIME"'
verwacht "buiten Linux zonder draaiende podman machine: stoppen met de oorzaak" 1 "podman machine start"

STUB_ENV="STUB_OS=Darwin STUB_MACHINE_SOCK=$werkmap/bestaat-niet.sock" geval "$met" 'modus_engine; echo "PR=$PODMAN_RUNTIME"'
verwacht "een opgegeven machine-socket die niet bestaat telt als niet draaiend" 1 "bestaat-niet.sock"

# --- socket: antwoordt er iets op? ---

# Een socketbestand van een gestopte service blijft na een herstart van de container staan. Zonder
# deze controle faalt pas de eerste compose-aanroep, met een melding die de oorzaak overstemt.
SOCK_ENGINE='modus_engine; echo "PR=$PODMAN_RUNTIME DH=$DOCKER_HOST"'

# Alleen de eigen rootless socket wordt herstart: XDG_RUNTIME_DIR is in deze harness de werkmap, dus
# dat is $werkmap/podman/podman.sock — ook als DOCKER_HOST er expliciet naar wijst.
eigen_sock="$werkmap/podman/podman.sock"

herstel_eigen_sock() {
  rm -f "$eigen_sock" "$eigen_sock.dood"
  maak_socket "$eigen_sock"
}

touch "$eigen_sock.dood"
STUB_ENV="$PODMAN_SOCK STUB_ECHTE_SLEEP=1" geval "$met" "$SOCK_ENGINE"
verwacht "de eigen podman-socket waarop niemand luistert: service opnieuw starten" 0 "PR=1 DH=unix://$eigen_sock"

case "$UITVOER" in
  *"service starten"*) ok "het herstarten van de service wordt gemeld" ;;
  *) fout "de herstart wordt niet gemeld — $UITVOER" ;;
esac

herstel_eigen_sock
touch "$eigen_sock.dood"
STUB_ENV="$PODMAN_SOCK STUB_ECHTE_SLEEP=1" geval "$met:$met_setsid" "$SOCK_ENGINE"
verwacht "met setsid start de service ook, losgemaakt van de shell" 0 "PR=1 DH=unix://$eigen_sock"

herstel_eigen_sock
touch "$eigen_sock.dood"
STUB_ENV="$PODMAN_SOCK STUB_SERVICE_FAAL=1" geval "$met" "$SOCK_ENGINE"
verwacht "een service die niet opkomt: stoppen met het log van de service" 1 "newuidmap"
herstel_eigen_sock

# Wie de podman-CLI naar dezelfde socket wijst als compose, zoals federatie.sh vraagt, maakt van
# podman een remote client; die kan geen service starten.
for remote in "CONTAINER_HOST=unix://$eigen_sock" "CONTAINER_CONNECTION=fbs"; do
  touch "$eigen_sock.dood"
  STUB_ENV="$PODMAN_SOCK $remote STUB_ECHTE_SLEEP=1" geval "$met" "$SOCK_ENGINE"
  verwacht "met ${remote%%=*} gezet start de service toch" 0 "PR=1 DH=unix://$eigen_sock"
  herstel_eigen_sock
done

touch "$eigen_sock.dood"
STUB_ENV="STUB_ECHTE_SLEEP=1" geval "$met" "$SOCK_ENGINE"
verwacht "zonder DOCKER_HOST: de eigen dode socket wordt herstart" 0 "service starten"
herstel_eigen_sock

# Een verse machine: podman heeft in de runtime-map nog nooit een socket neergezet.
vers="$werkmap/vers"
STUB_ENV="XDG_RUNTIME_DIR=$vers STUB_ECHTE_SLEEP=1" geval "$met" "$SOCK_ENGINE"
verwacht "zonder socketbestand: de service wordt gestart" 0 "PR=1 DH=unix://$vers/podman/podman.sock"

case "$UITVOER" in
  *"service starten"*) ok "het starten op een verse machine wordt gemeld" ;;
  *) fout "het starten op een verse machine wordt niet gemeld — $UITVOER" ;;
esac

# Een ander pad met "podman" erin kan een rootful socket zijn, en een geweigerde toegang geeft
# dezelfde curl-exit als "niemand luistert". Die is niet van ons om weg te halen.
vreemd_sock="$werkmap/vreemd/podman.sock"
maak_socket "$vreemd_sock"
touch "$vreemd_sock.dood"
STUB_ENV="DOCKER_HOST=unix://$vreemd_sock STUB_ECHTE_SLEEP=1" geval "$met" "$SOCK_ENGINE"
verwacht "een vreemde podman-socket zonder luisteraar: melden, niet herstarten" 1 "Geen engine die antwoordt"
[ -S "$vreemd_sock" ] && ok "de vreemde socket blijft staan" || fout "de vreemde socket is verwijderd"

# Een service die te traag antwoordt leeft nog. Zijn socket weghalen laat hem onbereikbaar
# doordraaien, en een tweede service ernaast lost niets op.
traag_sock="$werkmap/traag/podman.sock"
maak_socket "$traag_sock"
touch "$traag_sock.traag"
STUB_ENV="DOCKER_HOST=unix://$traag_sock" geval "$met" "$SOCK_ENGINE"
verwacht "een socket die niet op tijd antwoordt: stoppen, niet herstarten" 1 "niet binnen 5 seconden"
[ -S "$traag_sock" ] && ok "de socket van een trage service blijft staan" || fout "de socket van een trage service is verwijderd"

case "$UITVOER" in
  *"service starten"*) fout "een trage service wordt als dood herstart — $UITVOER" ;;
  *) ok "een trage service wordt niet herstart" ;;
esac

# Het pad zegt niets over de engine: podman-docker publiceert podman op een docker.sock.
podman_als_docker="$werkmap/run/docker.sock"
maak_socket "$podman_als_docker"
STUB_ENV="DOCKER_HOST=unix://$podman_als_docker" geval "$met" "$SOCK_ENGINE"
verwacht "podman achter een docker.sock telt als podman" 0 "PR=1"

docker_als_podman="$werkmap/docker-podman/podman.sock"
maak_socket "$docker_als_podman"
touch "$docker_als_podman.docker"
STUB_ENV="DOCKER_HOST=unix://$docker_als_podman" geval "$met" "$SOCK_ENGINE"
verwacht "Docker achter een pad met 'podman' erin telt niet als podman" 0 "PR=0"

klein_sock="$werkmap/klein/docker.sock"
maak_socket "$klein_sock"
touch "$klein_sock.kleine-koppen"
STUB_ENV="DOCKER_HOST=unix://$klein_sock" geval "$met" "$SOCK_ENGINE"
verwacht "de podman-header telt ook in kleine letters" 0 "PR=1"

# Een engine die op /_ping antwoordt maar niet zegt wat hij is, is geen Docker: dan zouden de
# podman-overlays en de seriële start wegvallen.
typefout_sock="$werkmap/typefout/podman.sock"
maak_socket "$typefout_sock"
touch "$typefout_sock.typefout"
STUB_ENV="DOCKER_HOST=unix://$typefout_sock" geval "$met" "$SOCK_ENGINE"
verwacht "een engine die zijn type niet prijsgeeft: stoppen, niet Docker aannemen" 1 "Niet vast te stellen welke engine"

dode_docker="$werkmap/dood/docker.sock"
maak_socket "$dode_docker"
touch "$dode_docker.dood"
STUB_ENV="DOCKER_HOST=unix://$dode_docker" geval "$met" "$SOCK_ENGINE"
verwacht "een Docker-socket waarop niemand luistert: stoppen vóór compose" 1 "Geen engine die antwoordt"

STUB_ENV="DOCKER_HOST=unix://$werkmap/bestaat/niet/podman.sock STUB_OS=Darwin" geval "$met" "$SOCK_ENGINE"
verwacht "buiten Linux wordt een opgegeven podman-socket niet zelf gestart" 1 "Geen engine die antwoordt"

# Zonder DOCKER_HOST wijst XDG_RUNTIME_DIR (in deze harness de werkmap) naar een levende socket.
STUB_ENV="" geval "$met" "$SOCK_ENGINE"
verwacht "zonder DOCKER_HOST: de levende podman-socket in XDG_RUNTIME_DIR" 0 "PR=1 DH=unix://$werkmap/podman/podman.sock"

case "$UITVOER" in
  *"service starten"*) fout "een levende socket wordt toch herstart — $UITVOER" ;;
  *) ok "een levende socket wordt niet herstart" ;;
esac

# Eén runtime-map voor pause.pid en socket, met dezelfde keuze als podman.
STUB_ENV="" geval "$met" 'modus_runtime_dir'
verwacht "de runtime-map volgt XDG_RUNTIME_DIR" 0 "$werkmap"

UITVOER="$(env -i HOME="$werkmap" PATH="$met:$basis" "$basis/bash" -c ". '$LIB'; modus_runtime_dir" 2>&1)"

case "$UITVOER" in
  "/run/user/$(id -u)"|"/tmp/podman-run-$(id -u)") ok "zonder XDG_RUNTIME_DIR: /run/user/<uid> of /tmp/podman-run-<uid> ($UITVOER)" ;;
  *) fout "zonder XDG_RUNTIME_DIR een onverwachte runtime-map — $UITVOER" ;;
esac

# Welke tak geldt, hangt aan de machine; beide tellen als één assertie, zodat het minimum in CI
# niet afhangt van of de runner een schrijfbare /run/user/<uid> heeft.
if [ -w "/run/user/$(id -u)" ]; then
  [ "$UITVOER" = "/run/user/$(id -u)" ] \
    && ok "met schrijfbare /run/user/<uid> wordt die de runtime-map" \
    || fout "met schrijfbare /run/user/<uid> toch een andere runtime-map — $UITVOER"
else
  [ "$UITVOER" = "/tmp/podman-run-$(id -u)" ] \
    && ok "zonder schrijfbare /run/user/<uid> valt de runtime-map terug op /tmp" \
    || fout "zonder schrijfbare /run/user/<uid> geen terugval op /tmp — $UITVOER"
fi

# --- pause.pid ---

pid_bestand="$werkmap/libpod/tmp/pause.pid"
mkdir -p "$(dirname "$pid_bestand")"

# Een pid die gegarandeerd niet meer bestaat: een beëindigd en opgeruimd kind van deze shell.
true &
dode_pid=$!
wait "$dode_pid" || true

# Een proces met dezelfde comm als het pauzeproces van podman. Een script en geen kopie van sleep:
# een multi-call binary (busybox, uutils) draait onder een andere naam niet als sleep.
printf '#!/bin/sh\nwhile :; do %s 1; done\n' "$echte_sleep" >"$werkmap/podman pause"
chmod +x "$werkmap/podman pause"
"$werkmap/podman pause" &
pauze_pid=$!
# Een podman-variant waarvan het pauzeproces anders heet: in twijfel niet opruimen.
mkdir -p "$werkmap/variant" "$werkmap/catatonit-map"
cp "$werkmap/podman pause" "$werkmap/variant/podman"
"$werkmap/variant/podman" &
podman_variant_pid=$!
# Met catatonit geïnstalleerd voert podman `catatonit -P` uit als pauzeproces.
cp "$werkmap/podman pause" "$werkmap/catatonit-map/catatonit"
"$werkmap/catatonit-map/catatonit" &
catatonit_pid=$!
trap 'kill "$pauze_pid" "$podman_variant_pid" "$catatonit_pid" 2>/dev/null || true; rm -rf "$werkmap"' EXIT

for _ in 1 2 3 4 5 6 7 8 9 10; do
  [ "$(cat "/proc/$pauze_pid/comm" 2>/dev/null || true)" = "podman pause" ] \
    && [ "$(cat "/proc/$podman_variant_pid/comm" 2>/dev/null || true)" = "podman" ] \
    && [ "$(cat "/proc/$catatonit_pid/comm" 2>/dev/null || true)" = "catatonit" ] && break
  "$echte_sleep" 0.1
done

# <verwacht 0/1> <omschrijving> <inhoud, of - voor geen bestand> [STUB_*-variabelen]
verweesd_geval() {
  rm -f "$pid_bestand"
  [ "$3" = - ] || printf '%s\n' "$3" >"$pid_bestand"
  STUB_ENV="${4:-}" geval "$met" "modus_pause_pid_verweesd '$pid_bestand'"
  [ "$STATUS" -eq "$1" ] && ok "$2" || fout "$2 (verwacht $1, kreeg $STATUS)"
}

# De testprocessen delen de userns van deze shell; dat is de situatie onder `podman unshare`, waar
# alleen de naam het pauzeproces nog herkent.
verweesd_geval 1 "geen pause.pid: niets verweesd" -
verweesd_geval 0 "een pid die niet meer bestaat is verweesd" "$dode_pid"
verweesd_geval 0 "een hergebruikte pid (ander proces, eigen userns) is verweesd" "$$"
verweesd_geval 0 "een leeg bestand is verweesd" ""
verweesd_geval 0 "een bestand zonder getal is verweesd" "abc"
verweesd_geval 1 "een pid van het pauzeproces is niet verweesd" "$pauze_pid"
verweesd_geval 1 "een eigen podman-proces met een andere naam telt als levend" "$podman_variant_pid"
verweesd_geval 1 "catatonit als pauzeproces telt als levend" "$catatonit_pid"
verweesd_geval 1 "een eigen proces in een andere userns telt als levend, ook zonder podman-naam" \
  "$$" "STUB_ANDERE_USERNS=$$"
verweesd_geval 0 "een proces van een andere gebruiker is verweesd, ook als userns en naam kloppen" \
  "$pauze_pid" "STUB_ANDERE_USERNS=$pauze_pid STUB_UID=$(($(id -u) + 1))"

printf '%s\n' "$dode_pid" >"$pid_bestand"
STUB_ENV="$PODMAN_SOCK" geval "$met" 'modus_engine'
verwacht "modus_engine meldt het opruimen van een verweesde pause.pid" 0 "Verweesde $pid_bestand opgeruimd"
[ ! -e "$pid_bestand" ] && ok "de verweesde pause.pid is weg" || fout "de verweesde pause.pid staat er nog"

# Zet een andere sessie tijdens de controle een nieuwe pid neer, dan is dat bestand niet beoordeeld.
printf '%s\n' "$dode_pid" >"$pid_bestand"
STUB_ENV="" geval "$met" 'modus_pause_pid_verweesd() { echo 424242 >"$1"; }; modus_pause_pid_opruimen'
[ "$(cat "$pid_bestand" 2>/dev/null || true)" = 424242 ] \
  && ok "een pause.pid die tijdens de controle is vervangen, blijft staan" \
  || fout "een tijdens de controle vervangen pause.pid is weggehaald — $UITVOER"

printf '%s\n' "$pauze_pid" >"$pid_bestand"
STUB_ENV="$PODMAN_SOCK" geval "$met" 'modus_engine'
[ -e "$pid_bestand" ] && ok "een levende pause.pid blijft staan" || fout "een levende pause.pid is verwijderd"

printf '%s\n' "$dode_pid" >"$pid_bestand"
STUB_ENV="$PODMAN_SOCK STUB_OS=Darwin" geval "$met" 'modus_engine'
[ -e "$pid_bestand" ] && ok "buiten Linux wordt geen pause.pid aangeraakt" || fout "buiten Linux is een pause.pid verwijderd"

kill "$pauze_pid" "$podman_variant_pid" "$catatonit_pid" 2>/dev/null || true
rm -f "$pid_bestand"

# --- modus_compose_up ---

# Een compose-stand-in die zijn argumenten logt. `version` meldt docker compose v2, of met
# STUB_OUDE_COMPOSE podman-compose (dat óók `--parallel` kent, dus daaraan niet te herkennen);
# elke `version`-aanroep laat een regel achter in de teller. `config --services` geeft STUB_SERVICES;
# STUB_COMPOSE_FAAL=<subcommando> laat dat subcommando falen.
teller="$werkmap/versie-teller"
COMPOSE_NEP='nep() {
  case " $* " in
    *" version "*)
      echo x >>"'"$teller"'"
      if [ -n "${STUB_OUDE_COMPOSE:-}" ]; then echo "podman-compose version 1.3.0"; else echo "Docker Compose version v2.32.4"; fi
      return 0 ;;
    *" config --services "*) printf "%s\n" ${STUB_SERVICES:-postgres manager outway} ;;
    *) echo "AANROEP $*" ;;
  esac
  case " $* " in *" ${STUB_COMPOSE_FAAL:-niets} "*) return 1 ;; esac
}'

STUB_ENV="" geval "$met" "$COMPOSE_NEP; PODMAN_RUNTIME=1; modus_compose_up nep -f a.yaml"
verwacht "op podman eerst de ontbrekende images ophalen" 0 "AANROEP -f a.yaml pull --policy missing --quiet"

# Volgorde is de kern: elke service apart, in de volgorde van config --services, en pas daarna up.
# Zonder --no-recreate: een gewijzigde container moet hier serieel opnieuw, niet straks in up -d.
VERWACHT_REEKS="AANROEP -f a.yaml pull --policy missing --quiet
AANROEP -f a.yaml create postgres
AANROEP -f a.yaml create manager
AANROEP -f a.yaml create outway
AANROEP -f a.yaml up -d"

if [ "$UITVOER" = "$VERWACHT_REEKS" ]; then
  ok "op podman per service één create, in afhankelijkheidsvolgorde, en dan up"
else
  fout "op podman niet de verwachte reeks aanroepen — kreeg: $UITVOER"
fi

STUB_ENV="STUB_SERVICES=enig" geval "$met" "$COMPOSE_NEP; PODMAN_RUNTIME=1; modus_compose_up nep"
verwacht "één service: één create" 0 "AANROEP create enig"

rm -f "$teller"
STUB_ENV="" geval "$met" "$COMPOSE_NEP; PODMAN_RUNTIME=1; modus_compose_up nep; modus_compose_up nep"
[ "$(wc -l <"$teller")" -eq 1 ] && ok "de compose-versie wordt één keer per proces gevraagd" \
  || fout "de compose-versie wordt $(wc -l <"$teller") keer gevraagd"

STUB_ENV="" geval "$met" "$COMPOSE_NEP; PODMAN_RUNTIME=0; modus_compose_up nep -f a.yaml"

if [ "$UITVOER" = "AANROEP -f a.yaml up -d" ]; then
  ok "op Docker alleen een gewone parallelle up"
else
  fout "op Docker niet alleen up -d — kreeg: $UITVOER"
fi

STUB_ENV="STUB_OUDE_COMPOSE=1" geval "$met" "$COMPOSE_NEP; PODMAN_RUNTIME=1; modus_compose_up nep -f a.yaml"
verwacht "podman-compose: gewone up, met de reden erbij" 0 "geen docker compose v2"

case "$UITVOER" in
  *create*|*" pull "*) fout "podman-compose krijgt toch v2-aanroepen — $UITVOER" ;;
  *) ok "podman-compose krijgt geen v2-aanroepen" ;;
esac

for faal in pull create; do
  STUB_ENV="STUB_COMPOSE_FAAL=$faal" geval "$met" "$COMPOSE_NEP; PODMAN_RUNTIME=1; modus_compose_up nep -f a.yaml"

  if [ "$STATUS" -ne 0 ] && [[ "$UITVOER" != *" up -d"* ]]; then
    ok "een mislukte $faal stopt vóór up, met een fout"
  else
    fout "een mislukte $faal loopt door naar up of slaagt — exit $STATUS: $UITVOER"
  fi
done

# Rootless podman op Linux zonder DOCKER_HOST: de socket onder XDG_RUNTIME_DIR, of zelf starten.
socketmap="$werkmap/run-met-socket"
maak_socket "$socketmap/podman/podman.sock"

STUB_ENV="XDG_RUNTIME_DIR=$socketmap" geval "$met" 'modus_engine; echo "DH=$DOCKER_HOST PR=$PODMAN_RUNTIME"'
verwacht "zonder DOCKER_HOST wordt de bestaande podman-socket gebruikt" 0 "DH=unix://$socketmap/podman/podman.sock PR=1"

case "$UITVOER" in
  *"service starten"*) fout "een bestaande socket wordt toch opnieuw gestart — $UITVOER" ;;
  *) ok "een bestaande socket wordt niet opnieuw gestart" ;;
esac

STUB_ENV="XDG_RUNTIME_DIR=$werkmap/run-zonder-socket STUB_SERVICE_FAAL=1" geval "$met" 'modus_engine; echo "DH=$DOCKER_HOST"'
verwacht "een podman-service die niet start, stopt met de socket erbij" 1 "kwam niet omhoog ($werkmap/run-zonder-socket/podman/podman.sock)"

# --- modus_engine_bereikbaar ---

STUB_ENV="$PODMAN_SOCK" geval "$met" 'modus_engine; modus_engine_bereikbaar; echo BEREIKBAAR'
verwacht "een engine die antwoordt, laat de start door" 0 "BEREIKBAAR"

# modus_engine heeft een unix-socket al gepingd; een tweede controle wacht bij podman op de
# storage-lock.
STUB_ENV="$PODMAN_SOCK STUB_URL_FAAL=1" geval "$met" 'modus_engine; modus_engine_bereikbaar; echo BEREIKBAAR'
verwacht "een unix-socket wordt niet nog eens bevraagd" 0 "BEREIKBAAR"

# Docker zonder DOCKER_HOST hangt aan een context; die socket heeft modus_engine niet gepingd.
STUB_ENV="STUB_INFO_FAAL=1" geval "$zonder" 'MODUS_DOCKER_TOEGESTAAN=1; modus_engine; modus_engine_bereikbaar; echo BEREIKBAAR'
verwacht "een engine die niet antwoordt, stopt met de melding van de engine" 1 "Cannot connect to the Docker daemon"

case "$UITVOER" in
  *BEREIKBAAR*) fout "na een onbereikbare engine loopt het script door — $UITVOER" ;;
  *) ok "na een onbereikbare engine stopt het script" ;;
esac

# `docker` als shim naar podman (podman-docker) slaagt lokaal, ook als de engine in DOCKER_HOST weg is.
STUB_ENV="DOCKER_HOST=tcp://podman.lokaal:8888 STUB_URL_FAAL=1" geval "$met" 'modus_engine; modus_engine_bereikbaar; echo BEREIKBAAR'
verwacht "bij podman telt de engine in DOCKER_HOST, niet een slagende docker info" 1 "no such file or directory"

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
