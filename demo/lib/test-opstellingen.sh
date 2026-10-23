#!/usr/bin/env bash
# Unittests voor de twee scripts die podman-modus.sh sourcen: demo/environment/lib/peer-stack.sh en
# demo/up.sh. Ze draaien de echte scripts tegen stubs voor docker, podman en uname, en toetsen wat
# podman-modus.sh zelf niet kan zien: welke compose-bestanden bij welke modus horen, voor `up`,
# `down` en `ps`, en de afspraak in `.modus` tussen de federatie en een losse peer.
set -euo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO="$(cd "$HERE/../.." && pwd)"

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

stubs="$werkmap/stubs"
mkdir -p "$stubs"

# Elke compose-aanroep komt als één regel in STUB_LOG; `config` levert een lege render, `ps -aq`
# geeft STUB_PS (de containers die het project al heeft).
cat >"$stubs/docker" <<'EOF'
#!/bin/sh
[ "$1" = compose ] || exit 0
[ "$2" = version ] && exit 0
echo "$*" >>"$STUB_LOG"
for arg in "$@"; do
  case "$arg" in
    config) echo "services: {}"; exit 0 ;;
    -aq) printf '%s' "${STUB_PS:-}"; exit 0 ;;
  esac
done
exit 0
EOF

cat >"$stubs/podman" <<'EOF'
#!/bin/sh
case "$1" in
  inspect) echo healthy ;;
  image) exit 1 ;;
esac
exit 0
EOF

cat >"$stubs/uname" <<'EOF'
#!/bin/sh
echo Linux
EOF

chmod +x "$stubs/docker" "$stubs/podman" "$stubs/uname"

PODMAN_SOCK="unix://$werkmap/podman/podman.sock"
DOCKER_SOCK="unix:///var/run/docker.sock"
grens="$werkmap/poortgrens"
echo 0 >"$grens"

# draai <script> <args…>: draait het script met de stubs voorop PATH; zet STATUS, UITVOER en
# AANROEP (de laatste compose-aanroep, zonder het `compose`-voorvoegsel).
draai() {
  : >"$werkmap/log"
  STATUS=0
  UITVOER="$(env PATH="$stubs:$PATH" STUB_LOG="$werkmap/log" DOCKER_HOST="${ENGINE:-$PODMAN_SOCK}" \
               MODUS_POORTGRENS_BESTAND="$grens" WACHTTIJD=5 ${STUB_ENV:-} bash "$@" 2>&1 </dev/null)" \
    || STATUS=$?
  AANROEP="$(tail -n1 "$werkmap/log" | sed 's/^compose //')"
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

verwacht_aanroep() {
  local wat="$1" verwacht="$2"

  if [ "$AANROEP" = "$verwacht" ]; then
    ok "$wat"
  else
    fout "$wat — compose kreeg '$AANROEP', verwacht '$verwacht'"
  fi
}

# bestanden <map> <modus>: de -f's die bij een modus horen, in de volgorde van de scripts.
bestanden() {
  local map="$1"

  case "$2" in
    docker) echo "-f ${map}compose.yaml" ;;
    bridge) echo "-f ${map}compose.yaml -f ${map}compose.podman.yaml" ;;
    hostnet) echo "-f ${map}compose.yaml -f ${map}compose.podman.yaml -f ${map}compose.podman-hostnet.yaml" ;;
  esac
}

engine_voor() {
  if [ "$1" = docker ]; then echo "$DOCKER_SOCK"; else echo "$PODMAN_SOCK"; fi
}

# --- peer-stack.sh ---

# Dezelfde indeling als in de repo, zodat peer-stack.sh zijn bibliotheken op dezelfde relatieve
# paden vindt; die zijn symlinks naar de echte.
mkdir -p "$werkmap/demo/environment"
ln -s "$REPO/demo/lib" "$werkmap/demo/lib"
ln -s "$REPO/demo/environment/lib" "$werkmap/demo/environment/lib"

lokaal="$werkmap/demo/environment/proefpeer/deploy/local"
mkdir -p "$lokaal/pki/out"
echo "PKI_DIR=pki" >"$lokaal/.env"
echo "name: proefpeer" >"$lokaal/compose.yaml"
printf '#!/bin/sh\necho "alle services draaien"\n' >"$lokaal/smoke-services.sh"

PEER="$REPO/demo/environment/lib/peer-stack.sh"

for modus in docker bridge hostnet; do
  echo "$modus" >"$lokaal/.modus"
  ENGINE="$(engine_voor "$modus")" draai "$PEER" "$lokaal" ps
  verwacht_aanroep "peer ps in $modus: de bestanden van die modus" "$(bestanden "$lokaal/" "$modus") ps"

  ENGINE="$(engine_voor "$modus")" draai "$PEER" "$lokaal" down -v
  verwacht_aanroep "peer down in $modus: de bestanden van die modus" "$(bestanden "$lokaal/" "$modus") down -v"

  if [ -e "$lokaal/.modus" ]; then
    fout "peer down in $modus laat .modus staan"
  else
    ok "peer down in $modus ruimt .modus op"
  fi
done

for modus in docker bridge hostnet; do
  rm -f "$lokaal/.modus"
  ENGINE="$(engine_voor "$modus")" STUB_ENV="MODUS=$modus" draai "$PEER" "$lokaal" up
  verwacht "peer up in $modus start" 0 "Draait ($modus)"
  verwacht_aanroep "peer up in $modus: de bestanden van die modus" "$(bestanden "$lokaal/" "$modus") up -d"

  if [ "$(head -n1 "$lokaal/.modus" 2>/dev/null)" = "$modus" ]; then
    ok "peer up in $modus legt de modus vast"
  else
    fout "peer up in $modus legt '$(cat "$lokaal/.modus" 2>/dev/null)' vast"
  fi
done

rm -f "$lokaal/.modus"
echo 1024 >"$grens"
STUB_ENV="MODUS=hostnet" draai "$PEER" "$lokaal" up
verwacht "peer up in hostnet met een te hoge poortgrens stopt" 1 "ip_unprivileged_port_start <= 443"

if grep -q ' up ' "$werkmap/log"; then
  fout "peer up startte containers ondanks de poortgrens"
else
  ok "peer up start geen containers bij een te hoge poortgrens"
fi

echo 0 >"$grens"

# De federatie zet `federatie` in .modus. Een losse up.sh mag zo'n peer niet aanraken: elk
# commando zou hem zonder de federatielaag hercreëren of een andere set bestanden voorleggen.
for commando in up ps down; do
  echo federatie >"$lokaal/.modus"
  draai "$PEER" "$lokaal" "$commando"
  verwacht "peer $commando op een federatie-peer wordt geweigerd" 2 "draait in de federatie"

  if [ -s "$werkmap/log" ]; then
    fout "peer $commando op een federatie-peer riep compose aan: $(cat "$werkmap/log")"
  else
    ok "peer $commando op een federatie-peer raakt compose niet aan"
  fi
done

# Containers zonder vastgelegde modus zijn buiten dit script gestart.
rm -f "$lokaal/.modus"
STUB_ENV="MODUS=hostnet STUB_PS=abc123" draai "$PEER" "$lokaal" up
verwacht "peer up naast containers zonder .modus wordt geweigerd" 1 "niet gestart via dit script"

if [ -e "$lokaal/.modus" ]; then
  fout "een geweigerde up legt toch een modus vast"
else
  ok "een geweigerde up legt geen modus vast"
fi

# --- demo/up.sh ---

# Een kopie van het script in een eigen root, want het leest en schrijft demo/generated/.modus
# relatief aan zijn eigen plek.
demoroot="$werkmap/demoroot"
mkdir -p "$demoroot/demo/generated"
cp "$REPO/demo/up.sh" "$demoroot/demo/up.sh"
ln -s "$REPO/demo/lib" "$demoroot/demo/lib"
demomodus="$demoroot/demo/generated/.modus"

for modus in docker bridge hostnet; do
  echo "$modus" >"$demomodus"
  ENGINE="$(engine_voor "$modus")" draai "$demoroot/demo/up.sh" ps
  verwacht_aanroep "demo ps in $modus: de bestanden van die modus" "$(bestanden "" "$modus") --profile demo ps"

  ENGINE="$(engine_voor "$modus")" draai "$demoroot/demo/up.sh" down -v
  verwacht_aanroep "demo down in $modus: de bestanden van die modus" "$(bestanden "" "$modus") --profile demo down -v"

  if [ -e "$demomodus" ]; then
    fout "demo down in $modus laat .modus staan"
  else
    ok "demo down in $modus ruimt .modus op"
  fi
done

# Een vastgelegde proeftuin-versie hoort bij de set bestanden, ook in een nieuwe shell.
printf '%s\n' hostnet "PROEFTUIN_IMAGE=ghcr.io/minbzk/moza-poc:proef" >"$demomodus"
draai "$demoroot/demo/up.sh" ps
verwacht_aanroep "demo ps met een vastgelegde proeftuin-versie: de versie-overlay erbij" \
  "$(bestanden "" hostnet) -f compose.proeftuin-versie.yaml --profile demo ps"

rm -f "$demomodus"
draai "$demoroot/demo/up.sh" ps
verwacht "demo ps zonder vastgelegde modus wordt geweigerd" 2 "niet vast te stellen in welke modus"

echo "ASSERTIES=$asserties"
exit "$mislukt"
