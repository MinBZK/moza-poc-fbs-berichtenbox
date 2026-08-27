#!/usr/bin/env bash
# Unittests voor cross-domain-preview.sh. Geen netwerk: elke test zet een curl-stub op het pad die
# de opgevraagde URL en body wegschrijft en een geregisseerd antwoord teruggeeft.
#
# Wat hier bewaakt wordt is vooral stil falen. Een netwerkregel die niet staat, valt pas op wanneer
# iemand tijdens een demo op een knop drukt: de regel is geen onderdeel van een healthcheck en de
# deployment blijft `Healthy`. Elke tak die "gelukt" zou kunnen melden zonder dat er iets stond,
# heeft daarom een eigen assertie.

set -euo pipefail

REPO_ROOT=$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)
readonly REPO_ROOT
readonly SCRIPT="$REPO_ROOT/.github/scripts/cross-domain-preview.sh"

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

gelijk() {
  local wat=$1 verwacht=$2 gemeten=$3

  if [ "$verwacht" = "$gemeten" ]; then
    ok "$wat"
  else
    fout "$wat — verwacht '$verwacht', gemeten '$gemeten'"
  fi
}

bevat() {
  local wat=$1 naald=$2 hooiberg=$3

  case "$hooiberg" in
    *"$naald"*) ok "$wat" ;;
    *) fout "$wat — '$naald' ontbreekt in: $hooiberg" ;;
  esac
}

# Een curl-stub die elke aanroep wegschrijft (argumenten in `aanroepen`, JSON-bodies in `bodies`)
# en een geregisseerd antwoord teruggeeft: `patch_antwoord` voor de PATCH, `taakstatus` voor het
# pollen erna. Met `patch_rc` doet de stub alsof curl zelf faalde.
maak_stub() {
  local map=$1 patch_antwoord=$2 taakstatus=$3 patch_rc=${4:-0}

  cat >"$map/curl" <<STUB
#!/usr/bin/env bash
printf '%s\n' "\$*" >>"$map/aanroepen"
for arg in "\$@"; do
  case "\$arg" in
    '{'*) printf '%s\n' "\$arg" >>"$map/bodies" ;;
  esac
done

if printf '%s' "\$*" | grep -q '/tasks/'; then
  printf '{"task_id":"t-1","status":"$taakstatus"}'
  exit 0
fi

printf '%s' '$patch_antwoord'
exit $patch_rc
STUB
  chmod +x "$map/curl"
}

# Draait het script met de stub op het pad. Zet $RC op de exitcode en $UITVOER op wat het
# schreef, zodat een test op allebei kan toetsen.
draai() {
  local map=$1
  shift

  RC=0
  UITVOER=$(PATH="$map:$PATH" ZAD_API_KEY=sleutel ZAD_API_URL=http://api.test bash "$SCRIPT" "$@" 2>&1) || RC=$?
}

# --- de body per richting -----------------------------------------------------------------------
# De kern van dit script: in een inbound-regel is de tegenpartij `from`, in een outbound-regel `to`.
# Die twee verwisselen levert een regel op die valideert maar niets openzet — geen foutmelding,
# geen verkeer.

# shellcheck source=.github/scripts/cross-domain-preview.sh
source "$SCRIPT"

gelijk "inbound vult de from-kant in" \
  '{"add":[{"name":"r","from":{"deployment":"pr-7"}}]}' \
  "$(patch_body zet pr-7 inbound r)"

gelijk "outbound vult de to-kant in" \
  '{"add":[{"name":"r","to":{"deployment":"pr-7"}}]}' \
  "$(patch_body zet pr-7 outbound r)"

gelijk "verwijderen noemt alleen de regelnaam" \
  '{"remove":["r"]}' \
  "$(patch_body verwijder pr-7 inbound r)"

# Opruimen mag de richting niet nodig hebben: cleanup-preview.yml kent alleen de regelnaam, en een
# body die per richting verschilt zou daar een extra parameter afdwingen die niets toevoegt.
gelijk "verwijderen is gelijk voor beide richtingen" \
  "$(patch_body verwijder pr-7 inbound r)" \
  "$(patch_body verwijder pr-7 outbound r)"

# --- argumentcontrole ---------------------------------------------------------------------------
werkmap=$(mktemp -d)
trap 'rm -rf "$werkmap"' EXIT
maak_stub "$werkmap" '{"task_id":"t-1"}' completed

draai "$werkmap" zet mpfm-w3h pr-7 zijwaarts regel
gelijk "een onbekende richting stopt het script" 1 "$RC"

draai "$werkmap" plakken mpfm-w3h pr-7 inbound regel
gelijk "een onbekende actie stopt het script" 1 "$RC"

draai "$werkmap" zet mpfm-w3h '' inbound regel
gelijk "een lege deployment stopt het script" 1 "$RC"

draai "$werkmap" zet mpfm-w3h pr-7 inbound ''
gelijk "een lege regelnaam stopt het script" 1 "$RC"

RC=0
PATH="$werkmap:$PATH" ZAD_API_KEY='' ZAD_API_URL=http://api.test bash "$SCRIPT" zet p d inbound r >/dev/null 2>&1 || RC=$?
gelijk "zonder ZAD_API_KEY stopt het script" 1 "$RC"

# --- de gelukkige weg ---------------------------------------------------------------------------
rm -f "$werkmap/aanroepen" "$werkmap/bodies"
draai "$werkmap" zet mpfm-w3h pr-7 outbound democonsole-naar-redis
gelijk "een geslaagde patch eindigt met 0" 0 "$RC"

aanroepen=$(cat "$werkmap/aanroepen")
bevat "de patch gaat naar het deployment-pad van de richting" \
  '/v2/projects/mpfm-w3h/services/cross-domain-access/config/deployment/pr-7/outbound' "$aanroepen"
bevat "de patch draagt de API-sleutel" 'X-API-Key: sleutel' "$aanroepen"
bevat "de taak wordt daarna opgevraagd" '/tasks/t-1' "$aanroepen"
bevat "de body vult de to-kant met de deployment" \
  '"to":{"deployment":"pr-7"}' "$(cat "$werkmap/bodies")"

# --- stil falen ---------------------------------------------------------------------------------
# Elk van deze drie zou zonder eigen tak een groene stap opleveren over een regel die niet staat.

maak_stub "$werkmap" '{"detail":"nee"}' completed 22
draai "$werkmap" zet mpfm-w3h pr-7 inbound regel
gelijk "een geweigerde patch stopt het script" 1 "$RC"

# HTTP 200 met een foutmelding erin: curl klaagt niet, dus zonder de taak-id-controle zou het
# script hier "gelukt" melden over een regel die nooit geschreven is.
maak_stub "$werkmap" '{"melding":"aangenomen"}' completed
draai "$werkmap" zet mpfm-w3h pr-7 inbound regel
gelijk "een antwoord zonder taak-id stopt het script" 1 "$RC"
bevat "en zegt waaróm het stopte" 'Geen taak-id' "$UITVOER"

maak_stub "$werkmap" '{"task_id":"t-1"}' failed
draai "$werkmap" zet mpfm-w3h pr-7 inbound regel
gelijk "een gefaalde taak stopt het script" 1 "$RC"
bevat "en noemt de eindtoestand" "eindigde als 'failed'" "$UITVOER"

# --- sourcen ------------------------------------------------------------------------------------
# Deze suite sourcet het script voor patch_body; zou main dan meedraaien, dan zou hij bij het
# sourcen al een echte API aanroepen.
uitvoer=$(ZAD_API_KEY=sleutel bash -c "source '$SCRIPT'" 2>&1)
gelijk "sourcen voert main niet uit" "" "$uitvoer"

echo
if [ "$mislukt" -eq 0 ]; then
  echo "Alle tests geslaagd."
else
  echo "Er zijn tests mislukt."
fi

echo "ASSERTIES=$asserties"

exit "$mislukt"
